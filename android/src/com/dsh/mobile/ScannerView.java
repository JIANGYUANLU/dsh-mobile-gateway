package com.dsh.mobile;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 扫码页：相机取流 → 手动转灰度并旋转 → 画到 ImageView → 解码。
 *
 * 刻意不使用 TextureView + Matrix：那套方案在本机实测中 setTransform 未生效，
 * 画面始终横向。这里改为在像素层面完成旋转，结果完全可预期。
 */
public class ScannerView extends FrameLayout {

    public interface OnDecoded {
        void onDecoded(String text);
    }

    private final Activity activity;
    private final PreviewView display;
    private final View rectOverlay;

    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private HandlerThread bgThread;
    private Handler bgHandler;
    private volatile boolean busy = false;
    private volatile boolean done = false;

    private Size decodeSize = new Size(640, 480);
    private int sensorOrientation = 90;
    private int needRotate = 90;

    private static final boolean DEBUG = false;
    private static void dbg(String m) {
        if (!DEBUG) return;
        try {
            java.lang.reflect.Method d = Class.forName("android.util.Log")
                    .getMethod("d", String.class, String.class);
            d.invoke(null, "DSHSCAN", m);
        } catch (Throwable ignored) {
            System.err.println("[DSHSCAN] " + m);
        }
    }

    public ScannerView(Activity a) {
        super(a);
        this.activity = a;
        setBackgroundColor(0xFF000000);

        display = new PreviewView(a);
        addView(display, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // 取景框
        rectOverlay = new View(a);
        int size = (int) (getResources().getDisplayMetrics().widthPixels * 0.78);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        lp.gravity = Gravity.CENTER;
        addView(rectOverlay, lp);
        rectOverlay.setBackground(new android.graphics.drawable.Drawable() {
            private final android.graphics.Paint p = new android.graphics.Paint();
            {
                p.setColor(0xFF4D7CFF);
                p.setStyle(android.graphics.Paint.Style.STROKE);
                p.setStrokeWidth(5f);
                p.setAntiAlias(true);
            }
            @Override public void draw(android.graphics.Canvas canvas) {
                android.graphics.Rect r = getBounds();
                int corner = Math.min(r.width(), r.height()) / 7;
                canvas.drawRect(r.left + 2, r.top + 2, r.right - 2, r.bottom - 2, p);
                p.setStrokeWidth(9f);
                canvas.drawLine(r.left, r.top, r.left + corner, r.top, p);
                canvas.drawLine(r.left, r.top, r.left, r.top + corner, p);
                canvas.drawLine(r.right, r.top, r.right - corner, r.top, p);
                canvas.drawLine(r.right, r.top, r.right, r.top + corner, p);
                canvas.drawLine(r.left, r.bottom, r.left + corner, r.bottom, p);
                canvas.drawLine(r.left, r.bottom, r.left, r.bottom - corner, p);
                canvas.drawLine(r.right, r.bottom, r.right - corner, r.bottom, p);
                canvas.drawLine(r.right, r.bottom, r.right, r.bottom - corner, p);
            }
            @Override public void setAlpha(int alpha) {}
            @Override public void setColorFilter(android.graphics.ColorFilter cf) {}
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        });

        // 版本水印
        android.widget.TextView ver = new android.widget.TextView(a);
        ver.setText("v" + BuildTag.VERSION);
        ver.setTextColor(0xAAFFFFFF);
        ver.setTextSize(11);
        ver.setPadding(16, 16, 16, 16);
        FrameLayout.LayoutParams vp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vp.gravity = Gravity.TOP | Gravity.START;
        addView(ver, vp);
    }

    public void setOnDecoded(OnDecoded l) { this.listener = l; }
    private OnDecoded listener;

    // ---------------- 生命周期 ----------------

    public void start() {
        done = false;
        if (activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        // 解码线程：给解码单独一条流水线，避免拖慢预览
        if (decodeThread == null) {
            decodeThread = new HandlerThread("qr-decode");
            decodeThread.start();
            decodeHandler = new Handler(decodeThread.getLooper());
        }
        openCamera();
    }

    public void stop() {
        done = true;
        closeCamera();
        if (decodeThread != null) {
            decodeThread.quitSafely();
            decodeThread = null;
            decodeHandler = null;
        }
    }

    private void openCamera() {
        if (camera != null) return;
        try {
            CameraManager cm = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
            String id = null;
            for (String cid : cm.getCameraIdList()) {
                CameraCharacteristics cc = cm.getCameraCharacteristics(cid);
                Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraMetadata.LENS_FACING_BACK) { id = cid; break; }
            }
            if (id == null && cm.getCameraIdList().length > 0) id = cm.getCameraIdList()[0];
            if (id == null) return;

            CameraCharacteristics cc = cm.getCameraCharacteristics(id);
            Integer orient = cc.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = orient == null ? 90 : orient;

            int displayRot = 0;
            try {
                int r = activity.getWindowManager().getDefaultDisplay().getRotation();
                if (r == android.view.Surface.ROTATION_90) displayRot = 90;
                else if (r == android.view.Surface.ROTATION_180) displayRot = 180;
                else if (r == android.view.Surface.ROTATION_270) displayRot = 270;
            } catch (Throwable ignored) {}
            needRotate = (sensorOrientation - displayRot + 360) % 360;

            StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            if (map != null) {
                Size[] yuv = map.getOutputSizes(ImageFormat.YUV_420_888);
                if (yuv != null && yuv.length > 0) decodeSize = chooseDecodeSize(yuv);
            }

            dbg("open camera id=" + id + " sensor=" + sensorOrientation
                    + " displayRot=" + displayRot + " needRotate=" + needRotate
                    + " decodeSize=" + decodeSize.getWidth() + "x" + decodeSize.getHeight());

            if (bgThread == null) {
                bgThread = new HandlerThread("qr-scan");
                bgThread.start();
                bgHandler = new Handler(bgThread.getLooper());
            }

            reader = ImageReader.newInstance(decodeSize.getWidth(), decodeSize.getHeight(),
                    ImageFormat.YUV_420_888, 3);
            reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader r) {
                    Image img = null;
                    try {
                        img = r.acquireLatestImage();
                        if (img == null || done) return;
                        if (busy) return;
                        busy = true;
                        handleFrame(img);
                    } catch (Throwable ignored) {
                    } finally {
                        if (img != null) try { img.close(); } catch (Throwable ignored) {}
                        busy = false;
                    }
                }
            }, bgHandler);

            if (activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice cam) { camera = cam; startSession(); }
                @Override public void onDisconnected(CameraDevice cam) {
                    try { cam.close(); } catch (Throwable ignored) {}
                    camera = null;
                }
                @Override public void onError(CameraDevice cam, int error) {
                    try { cam.close(); } catch (Throwable ignored) {}
                    camera = null;
                }
            }, bgHandler);

        } catch (Throwable t) {
            dbg("openCamera failed: " + t);
        }
    }

    private Size chooseDecodeSize(Size[] sizes) {
        // 扫码对分辨率要求高：每模块至少要有 ~10 像素才能稳定解码。
        // 电脑屏幕上那张码在整幅画面里通常只占 1/3 左右，所以取 1080p 级别最合适。
        // 优先 16:9 的 1920x1080；其次 1280x720 / 4:3 的 1440x1080。
        Size best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Size s : sizes) {
            int w = Math.max(s.getWidth(), s.getHeight());
            int h = Math.min(s.getWidth(), s.getHeight());
            if (w > 2000) continue;          // 太大处理慢
            if (w < 960) continue;           // 太小解不出
            double ratio = (double) w / h;
            boolean ok = Math.abs(ratio - 16.0 / 9.0) < 0.12
                    || Math.abs(ratio - 4.0 / 3.0) < 0.12;
            if (!ok) continue;
            // 目标 1920 宽
            int score = Math.abs(w - 1920);
            if (score < bestScore) { bestScore = score; best = s; }
        }
        if (best != null) return best;

        // 退一步：任意尺寸里最大且不超过 2000
        for (Size s : sizes) {
            int w = Math.max(s.getWidth(), s.getHeight());
            if (w > 2000) continue;
            if (best == null || w > Math.max(best.getWidth(), best.getHeight())) best = s;
        }
        if (best != null) return best;
        return sizes[0];
    }

    private void startSession() {
        try {
            if (camera == null || reader == null) return;

            CaptureRequest.Builder b = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(reader.getSurface());
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);

            List<android.view.Surface> outputs = new ArrayList<>();
            outputs.add(reader.getSurface());

            camera.createCaptureSession(outputs, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession s) {
                    session = s;
                    try {
                        s.setRepeatingRequest(b.build(), null, bgHandler);
                        dbg("capture session configured");
                    } catch (Throwable t) {
                        dbg("setRepeatingRequest failed: " + t);
                    }
                }
                @Override
                public void onConfigureFailed(CameraCaptureSession s) {
                    dbg("capture session configure FAILED");
                }
            }, bgHandler);
        } catch (Throwable t) {
            dbg("startSession failed: " + t);
        }
    }

    private void closeCamera() {
        try { if (session != null) session.close(); } catch (Throwable ignored) {}
        session = null;
        try { if (camera != null) camera.close(); } catch (Throwable ignored) {}
        camera = null;
        try { if (reader != null) reader.close(); } catch (Throwable ignored) {}
        reader = null;
    }

    // ---------------- 每帧处理 ----------------

    private int frameCount = 0;
    private long fpsMark = 0;
    private int fpsFrames = 0;
    private long lastDecodeStart = 0;
    /** 两次解码的最小间隔：约 6 次/秒，扫码够用，又不抢预览的 CPU */
    private static final long DECODE_MIN_INTERVAL_MS = 160;

    // 复用的缓冲区，避免每帧分配几 MB 内存造成 GC 卡顿
    private byte[] yBuf;
    private byte[] decodeBuf;
    private byte[] decodeRot;
    private int[] argbBuf;
    private Bitmap previewBmp;
    private int previewW, previewH;

    // 解码线程：解码较重，独立线程跑，不阻塞预览
    private HandlerThread decodeThread;
    private Handler decodeHandler;
    private volatile boolean decodeBusy = false;
    private volatile int decodeTries = 0;

    /** 预览降采样倍数：1440x1920 缩到约 480x640，画得动又够看 */
    private static final int PREVIEW_SCALE = 3;

    private void handleFrame(Image img) {
        int w = img.getWidth();
        int h = img.getHeight();

        // --- 1) 取 Y 通道（整行批量拷贝，比逐像素快很多）---
        if (yBuf == null || yBuf.length < w * h) yBuf = new byte[w * h];
        byte[] y = yBuf;

        Image.Plane plane = img.getPlanes()[0];
        ByteBuffer buf = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixStride = plane.getPixelStride();
        buf.rewind();
        int limit = buf.limit();

        if (pixStride == 1 && rowStride == w) {
            buf.get(y, 0, Math.min(w * h, limit));
        } else if (pixStride == 1) {
            for (int row = 0; row < h; row++) {
                int base = row * rowStride;
                int n = Math.min(w, Math.max(0, limit - base));
                if (n > 0) {
                    buf.position(base);
                    buf.get(y, row * w, n);
                }
            }
        } else {
            for (int row = 0; row < h; row++) {
                int base = row * rowStride;
                int off = row * w;
                for (int col = 0; col < w; col++) {
                    int idx = base + col * pixStride;
                    y[off + col] = (idx < limit) ? buf.get(idx) : 0;
                }
            }
        }

        frameCount++;

        // --- 2) 预览：直接旋转+降采样+转 ARGB，一次遍历完成 ---
        updatePreview(y, w, h);

        // 帧率统计：每 60 帧报一次，便于真机核对流畅度
        long now = System.currentTimeMillis();
        if (fpsMark == 0) { fpsMark = now; fpsFrames = 0; }
        fpsFrames++;
        if (now - fpsMark >= 2000) {
            dbg("fps=" + (fpsFrames * 1000L / (now - fpsMark))
                    + " size=" + w + "x" + h + " frame=" + frameCount);
            fpsMark = now;
            fpsFrames = 0;
        }

        // --- 3) 解码：放到独立线程，绝不阻塞预览；并限制最大频率，给预览留 CPU ---
        if (DEBUG && frameCount % 60 == 0) {
            dbg("gate handler=" + (decodeHandler != null) + " busy=" + decodeBusy
                    + " sinceLast=" + (System.currentTimeMillis() - lastDecodeStart)
                    + " tries=" + decodeTries);
        }
        if (decodeHandler == null || decodeBusy) return;
        long t = System.currentTimeMillis();
        if (t - lastDecodeStart < DECODE_MIN_INTERVAL_MS) return;
        lastDecodeStart = t;

        if (decodeBuf == null || decodeBuf.length < w * h) decodeBuf = new byte[w * h];
        if (decodeRot == null || decodeRot.length < w * h) decodeRot = new byte[w * h];
        final byte[] db = decodeBuf;
        System.arraycopy(y, 0, db, 0, w * h);
        final int fw = w, fh = h, frot = needRotate;
        decodeBusy = true;
        decodeHandler.post(new Runnable() {
            @Override public void run() {
                try {
                    int[] dims = rotateInto(db, fw, fh, frot, decodeRot);
                    decodeTries++;
                    String text = decodeFrom(decodeRot, dims[0], dims[1]);
                    if (DEBUG && decodeTries % 20 == 0) {
                        dbg("decode try#" + decodeTries + " -> " + (text == null ? "miss" : "HIT"));
                    }
                    if (text != null && listener != null && !done) {
                        done = true;
                        final String t = text;
                        activity.runOnUiThread(new Runnable() {
                            @Override public void run() { listener.onDecoded(t); }
                        });
                    }
                } catch (Throwable e) {
                    dbg("decode error: " + e);
                } finally {
                    decodeBusy = false;
                }
            }
        });
    }

    /** 把 y 旋转进 dst，返回 {宽, 高} */
    private int[] rotateInto(byte[] y, int w, int h, int rot, byte[] dst) {
        int rw, rh;
        if (rot == 90) {
            rw = h; rh = w;
            for (int row = 0; row < h; row++) {
                int rbase = row * w;
                for (int col = 0; col < w; col++) {
                    dst[col * rw + (rw - 1 - row)] = y[rbase + col];
                }
            }
        } else if (rot == 270) {
            rw = h; rh = w;
            for (int row = 0; row < h; row++) {
                int rbase = row * w;
                for (int col = 0; col < w; col++) {
                    dst[(rh - 1 - col) * rw + row] = y[rbase + col];
                }
            }
        } else if (rot == 180) {
            rw = w; rh = h;
            for (int row = 0; row < h; row++) {
                int rbase = row * w;
                int dstBase = (rh - 1 - row) * rw;
                for (int col = 0; col < w; col++) {
                    dst[dstBase + (rw - 1 - col)] = y[rbase + col];
                }
            }
        } else {
            rw = w; rh = h;
            System.arraycopy(y, 0, dst, 0, w * h);
        }
        return new int[]{rw, rh};
    }

    /** 旋转 + 降采样 + 灰度转 ARGB，一次遍历；复用 Bitmap 对象 */
    private void updatePreview(byte[] y, int w, int h) {
        try {
            int pw, ph;
            if (needRotate == 90 || needRotate == 270) { pw = h; ph = w; }
            else { pw = w; ph = h; }

            int ow = Math.max(1, pw / PREVIEW_SCALE);
            int oh = Math.max(1, ph / PREVIEW_SCALE);

            if (argbBuf == null || argbBuf.length < ow * oh) argbBuf = new int[ow * oh];
            int[] out = argbBuf;

            // 采样步长（整数，保证不越界）
            int sx = Math.max(1, pw / ow);
            int sy = Math.max(1, ph / oh);

            for (int oy = 0; oy < oh; oy++) {
                int py = oy * sy;                 // 旋转后坐标
                int dstBase = oy * ow;
                for (int ox = 0; ox < ow; ox++) {
                    int px = ox * sx;
                    int srcRow, srcCol;
                    if (needRotate == 90) {
                        // rot[py][px] = y[h-1-px][py]
                        srcRow = h - 1 - px;
                        srcCol = py;
                    } else if (needRotate == 270) {
                        // rot[py][px] = y[px][w-1-py]
                        srcRow = px;
                        srcCol = w - 1 - py;
                    } else if (needRotate == 180) {
                        // rot[py][px] = y[h-1-py][w-1-px]
                        srcRow = h - 1 - py;
                        srcCol = w - 1 - px;
                    } else {
                        srcRow = py;
                        srcCol = px;
                    }
                    if (srcRow < 0 || srcRow >= h || srcCol < 0 || srcCol >= w) {
                        out[dstBase + ox] = 0xFF000000;
                        continue;
                    }
                    int v = y[srcRow * w + srcCol] & 0xFF;
                    out[dstBase + ox] = 0xFF000000 | (v << 16) | (v << 8) | v;
                }
            }

            if (previewBmp == null || previewW != ow || previewH != oh) {
                previewBmp = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888);
                previewW = ow;
                previewH = oh;
            }
            previewBmp.setPixels(out, 0, ow, 0, 0, ow, oh);

            activity.runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (display != null) display.setFrame(previewBmp);
                }
            });
        } catch (Throwable ignored) {}
    }

    /** 预览视图：直接画 Bitmap，避免每帧 setImageBitmap 触发布局测量 */
    private static class PreviewView extends View {
        private Bitmap bmp;
        private final android.graphics.Paint paint =
                new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
        private final android.graphics.Rect srcRect = new android.graphics.Rect();
        private final android.graphics.RectF dstRect = new android.graphics.RectF();

        PreviewView(android.content.Context c) {
            super(c);
            setBackgroundColor(0xFF111111);
        }

        void setFrame(Bitmap b) {
            this.bmp = b;
            invalidate();
        }

        @Override protected void onDraw(android.graphics.Canvas canvas) {
            if (bmp == null || bmp.isRecycled()) return;
            int vw = getWidth(), vh = getHeight();
            if (vw <= 0 || vh <= 0) return;
            srcRect.set(0, 0, bmp.getWidth(), bmp.getHeight());

            // FIT_CENTER：等比缩放并居中，避免拉伸
            float scale = Math.min((float) vw / bmp.getWidth(), (float) vh / bmp.getHeight());
            float dw = bmp.getWidth() * scale;
            float dh = bmp.getHeight() * scale;
            float left = (vw - dw) / 2f;
            float top = (vh - dh) / 2f;
            dstRect.set(left, top, left + dw, top + dh);
            canvas.drawBitmap(bmp, srcRect, dstRect, paint);
        }
    }

    /** 尝试解码：先全图，再中心 70%（二维码通常就在取景框附近） */
    private String decodeFrom(byte[] gray, int w, int h) {
        try {
            QrDecoder.Result r = QrDecoder.decode(gray, w, h);
            if (r != null) return r.text;

            float f = 0.7f;
            int cw = (int) (w * f);
            int ch = (int) (h * f);
            if (cw >= 100 && ch >= 100) {
                int sx = (w - cw) / 2;
                int sy = (h - ch) / 2;
                byte[] crop = new byte[cw * ch];
                for (int row = 0; row < ch; row++) {
                    System.arraycopy(gray, (sy + row) * w + sx, crop, row * cw, cw);
                }
                QrDecoder.Result cr = QrDecoder.decode(crop, cw, ch);
                if (cr != null) return cr.text;
            }
        } catch (Throwable ignored) {}
        return null;
    }
}