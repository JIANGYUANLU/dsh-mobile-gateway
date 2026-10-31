package com.dsh.mobile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 纯 Java 二维码解码器（字节/数字/字母模式 / 版本 1-10 / 纠错等级 M）。
 * 零第三方依赖：定位三个寻像图形 → 仿射采样 → 读格式与掩码 → 解交织 → 解析数据段。
 */
public final class QrDecoder {

    public static final class Result {
        public final String text;
        public final int version;
        public final int mask;
        Result(String text, int version, int mask) {
            this.text = text;
            this.version = version;
            this.mask = mask;
        }
    }

    private QrDecoder() {}

    private static final int THRESHOLD = 128;

    /** 采样微调参数：{横向偏移(模块), 纵向偏移(模块)}。
     *  拍照错位主要由透视造成，已由对齐图形单应校正覆盖，这里只保留原位一次以节省时间。 */
    private static final double[][] SAMPLE_TWEAKS = {
            {0.0, 0.0},
    };
    private static boolean DEBUG = false;
    public static void setDebug(boolean d) { DEBUG = d; }

    /** 调 android.util.Log；桌面 JVM 下没有这个类，回退到 stderr。用反射避免硬依赖。 */
    private static java.lang.reflect.Method LOG_D = null;
    private static boolean logInit = false;
    private static void dbg(String m) {
        if (!DEBUG) return;
        if (!logInit) {
            logInit = true;
            try {
                Class<?> lc = Class.forName("android.util.Log");
                LOG_D = lc.getMethod("d", String.class, String.class);
            } catch (Throwable ignored) {
                LOG_D = null;
            }
        }
        if (LOG_D != null) {
            try { LOG_D.invoke(null, "QRDBG", m); return; } catch (Throwable ignored) {}
        }
        System.err.println("[QRDBG] " + m);
    }

    // ---------------- 对外入口 ----------------

    public static Result decode(byte[] gray, int w, int h) {
        try {
            // 先按原图解一次；不行再把黑白对调重试。
            // 深色终端主题下，屏幕上显示的二维码常常是"深底浅块"的反色码，
            // 标准扫码器（含本实现）默认只认浅底深块，因此必须支持反色。
            Result r = decodeOnce(gray, w, h);
            if (r != null) return r;

            byte[] inverted = new byte[gray.length];
            for (int i = 0; i < gray.length; i++) {
                inverted[i] = (byte) (255 - (gray[i] & 0xFF));
            }
            return decodeOnce(inverted, w, h);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Result decodeOnce(byte[] gray, int w, int h) {
        try {
            java.util.List<Finder[]> triples = findFinderTriples(gray, w, h);
            if (triples == null || triples.isEmpty()) return null;
            dbg("候选三点组 = " + triples.size());

            for (Finder[] ordered : triples) {
                int dim = estimateDimension(ordered);
                if (dim < 21 || dim > 57) continue;

                // 第一轮：纯仿射采样（对正对的画面足够）
                for (int attempt = 0; attempt < SAMPLE_TWEAKS.length; attempt++) {
                    double[] tw = SAMPLE_TWEAKS[attempt];
                    boolean[][] grid = sampleGrid(gray, w, h, ordered, dim, tw[0], tw[1]);
                    if (grid == null) continue;
                    if (!validateFinderPatterns(grid, dim)) continue;

                    Result r = decodeMatrix(grid, dim);
                    if (r != null) {
                        dbg("decoded(affine) attempt=" + attempt + " dim=" + dim);
                        return r;
                    }
                }

                // 第二轮：找到对齐图形后做透视校正再采样
                boolean[][] persp = sampleGridPerspective(gray, w, h, ordered, dim);
                if (persp != null && validateFinderPatterns(persp, dim)) {
                    Result r = decodeMatrix(persp, dim);
                    if (r != null) {
                        dbg("decoded(perspective) dim=" + dim);
                        return r;
                    }
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 检查采样网格的三个角是否为标准 7x7 寻像图形 */
    private static boolean validateFinderPatterns(boolean[][] g, int dim) {
        if (g == null || dim < 21) return false;
        return checkFinder(g, 0, 0)
                && checkFinder(g, 0, dim - 7)
                && checkFinder(g, dim - 7, 0);
    }

    private static boolean checkFinder(boolean[][] g, int r0, int c0) {
        int mismatch = 0;
        for (int r = 0; r < 7; r++) {
            for (int c = 0; c < 7; c++) {
                boolean expect;
                boolean ring = (r == 0 || r == 6 || c == 0 || c == 6);
                boolean core = (r >= 2 && r <= 4 && c >= 2 && c <= 4);
                expect = ring || core;
                if (g[r0 + r][c0 + c] != expect) mismatch++;
                // 允许极少量误差（拍摄噪声）
                if (mismatch > 4) return false;
            }
        }
        return mismatch <= 4;
    }

    /**
     * 由三个寻像图形生成所有可能方位。
     * 直角三角形的直角顶点可以对应二维码的左上角（正常），
     * 但当图像被旋转 90/180/270 度时，也可能是另外两个点之一。
     * 这里把三种顶点 × 两种交换都列出来，逐个尝试，用能否解出合法内容来裁决。
     */
    private static Finder[][] orderFindersVariants(Finder[] f) {
        java.util.List<Finder[]> out = new java.util.ArrayList<>();
        // 先试"按几何求出的直角顶点"
        Finder[] base = orderFinders(f);
        if (base != null) {
            out.add(new Finder[]{base[0], base[1], base[2]});
            out.add(new Finder[]{base[0], base[2], base[1]});
        }
        // 再试另外两个点当左上角（覆盖旋转情形）
        for (int pivot = 0; pivot < 3; pivot++) {
            int i = (pivot + 1) % 3;
            int j = (pivot + 2) % 3;
            out.add(new Finder[]{f[pivot], f[i], f[j]});
            out.add(new Finder[]{f[pivot], f[j], f[i]});
        }
        return out.toArray(new Finder[0][]);
    }

    // ---------------- 寻像图形检测 ----------------

    private static final class Finder {
        double x, y, moduleSize;
        int hits = 1;
    }

    private static java.util.List<Finder[]> findFinderTriples(byte[] gray, int w, int h) {
        List<Finder> raw = new ArrayList<>();

        for (int y = 0; y < h; y++) {
            // 把当前行压成段长度数组
            int[] runs = new int[Math.max(16, w / 2 + 2)];
            int[] colors = new int[runs.length];
            int n = 0;
            int curColor = (gray[y * w] & 0xFF) < THRESHOLD ? 1 : 0;
            int curLen = 1;
            for (int x = 1; x < w; x++) {
                int c = (gray[y * w + x] & 0xFF) < THRESHOLD ? 1 : 0;
                if (c == curColor) {
                    curLen++;
                } else {
                    if (n >= runs.length) break;
                    runs[n] = curLen;
                    colors[n] = curColor;
                    n++;
                    curColor = c;
                    curLen = 1;
                }
            }
            if (n < runs.length) {
                runs[n] = curLen;
                colors[n] = curColor;
                n++;
            }

            for (int i = 0; i + 4 < n; i++) {
                if (colors[i] != 1) continue;
                int total = runs[i] + runs[i + 1] + runs[i + 2] + runs[i + 3] + runs[i + 4];
                if (!isRatio(runs, i, total)) continue;

                int startX = 0;
                for (int k = 0; k < i; k++) startX += runs[k];

                Finder f = centerOfRun(gray, w, h, y, startX, runs, i, total);
                if (f != null) raw.add(f);
            }
        }

        if (raw.size() < 3) return null;
        dbg("raw hits = " + raw.size());

        // 空间聚簇
        List<Finder> clusters = new ArrayList<>();
        for (Finder f : raw) {
            Finder hit = null;
            for (Finder c : clusters) {
                double cx = c.x, cy = c.y;
                double radius = Math.max(c.moduleSize, f.moduleSize) * 2.5;
                double d = Math.hypot(cx - f.x, cy - f.y);
                if (d < radius) { hit = c; break; }
            }
            if (hit != null) {
                hit.x = (hit.x * hit.hits + f.x) / (hit.hits + 1);
                hit.y = (hit.y * hit.hits + f.y) / (hit.hits + 1);
                hit.moduleSize = (hit.moduleSize * hit.hits + f.moduleSize) / (hit.hits + 1);
                hit.hits++;
            } else {
                clusters.add(f);
            }
        }
        if (clusters.size() < 3) return null;

        // 不再按 moduleSize 精确分桶——真实照片里三个角的 moduleSize 会有透视差异，
// 分桶反而把真正的三点组拆散。这里改为：先按可信度筛出候选点，
// 再枚举三点组做宽松的几何筛选，最后由"采样后校验寻像图形"来裁决。
        List<Finder> cands = new ArrayList<>();
        for (Finder c : clusters) {
            // 真实寻像图形会在很多行被命中，且模块明显大于文字笔画
            if (c.hits >= 3 || c.moduleSize >= 6) cands.add(c);
        }
        if (cands.size() < 3) {
            cands.clear();
            cands.addAll(clusters);
        }
        if (cands.size() < 3) return null;

        // 太多了就按 hits 与 moduleSize 排个序，只留前 18 个
        cands.sort((a, b) -> Double.compare(b.hits * b.moduleSize, a.hits * a.moduleSize));
        if (cands.size() > 18) cands = new ArrayList<>(cands.subList(0, 18));
        if (DEBUG) {
            StringBuilder sb = new StringBuilder("top cands:");
            for (Finder c : cands) {
                sb.append(String.format(" [%.0f,%.0f m=%.1f n=%d]", c.x, c.y, c.moduleSize, c.hits));
            }
            dbg(sb.toString());
        }

        int m = cands.size();
        double[][] geo = new double[m][m];
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < m; j++) {
                geo[i][j] = Math.hypot(cands.get(i).x - cands.get(j).x,
                        cands.get(i).y - cands.get(j).y);
            }
        }

        java.util.List<double[]> scored = new java.util.ArrayList<>(); // {score,i,j,k,pivot}

        for (int i = 0; i < m; i++)
            for (int j = i + 1; j < m; j++)
                for (int k = j + 1; k < m; k++) {
                    Finder f0 = cands.get(i), f1 = cands.get(j), f2 = cands.get(k);
                    double d01 = geo[i][j], d02 = geo[i][k], d12 = geo[j][k];
                    double short1 = Math.min(d01, Math.min(d02, d12));
                    double longest = Math.max(d01, Math.max(d02, d12));
                    double short2 = d01 + d02 + d12 - short1 - longest;
                    if (short1 < 20 || short2 < 20) continue;

                    double modAvg = (f0.moduleSize + f1.moduleSize + f2.moduleSize) / 3.0;
                    if (modAvg <= 0) continue;

                    // 宽松容差：照片有透视，允许 20% 偏差
                    if (Math.abs(short1 / short2 - 1) > 0.22) continue;
                    if (Math.abs(longest / (short1 * Math.sqrt(2)) - 1) > 0.25) continue;
                    double modMax = Math.max(f0.moduleSize, Math.max(f1.moduleSize, f2.moduleSize));
                    double modMin = Math.min(f0.moduleSize, Math.min(f1.moduleSize, f2.moduleSize));
                    if (modMax / modMin > 1.6) continue;
                    if (short1 / modAvg < 10) continue;

                    // 直角顶点 = 斜边对面的点
                    int pivot;
                    if (d12 >= longest - 1e-6) pivot = 0;
                    else if (d02 >= longest - 1e-6) pivot = 1;
                    else pivot = 2;

                    Finder pv, o1, o2;
                    if (pivot == 0) { pv = f0; o1 = f1; o2 = f2; }
                    else if (pivot == 1) { pv = f1; o1 = f0; o2 = f2; }
                    else { pv = f2; o1 = f0; o2 = f1; }

                    double v1x = o1.x - pv.x, v1y = o1.y - pv.y;
                    double v2x = o2.x - pv.x, v2y = o2.y - pv.y;
                    double cosang = (v1x * v2x + v1y * v2y)
                            / (Math.hypot(v1x, v1y) * Math.hypot(v2x, v2y));
                    if (Math.abs(cosang) > 0.28) continue;

                    double score = Math.abs(short1 / short2 - 1) * 50
                            + Math.abs(longest / (short1 * Math.sqrt(2)) - 1) * 50
                            + Math.abs(cosang) * 50
                            + (modMax / modMin - 1) * 20
                            - Math.min(short1 / modAvg, 60) * 0.2;  // 边长/模块越大越可信
                    scored.add(new double[]{score, i, j, k, pivot});
                }

        scored.sort((a, b) -> Double.compare(a[0], b[0]));

        // 每个候选三点组给出两种方位（另外两个角互换），覆盖画面旋转
        java.util.List<Finder[]> out = new java.util.ArrayList<>();
        int limit = Math.min(scored.size(), 12);
        for (int s = 0; s < limit; s++) {
            double[] e = scored.get(s);
            int i = (int) e[1], j = (int) e[2], k = (int) e[3], pivot = (int) e[4];
            Finder pv, a1, a2;
            if (pivot == 0) { pv = cands.get(i); a1 = cands.get(j); a2 = cands.get(k); }
            else if (pivot == 1) { pv = cands.get(j); a1 = cands.get(i); a2 = cands.get(k); }
            else { pv = cands.get(k); a1 = cands.get(i); a2 = cands.get(j); }

            out.add(new Finder[]{pv, a1, a2});
            out.add(new Finder[]{pv, a2, a1});
        }
        if (DEBUG) dbg("候选三点组 = " + out.size() + "（来自 " + scored.size() + " 个几何合格组）");
        return out;
    }

    private static boolean lieOn(Finder a, Finder b, double dist) {
        return Math.abs(Math.hypot(a.x - b.x, a.y - b.y) - dist) < 1e-6;
    }

    private static boolean isRatio(int[] runs, int i, int total) {
        if (total < 7) return false;
        double u = total / 7.0;
        double tol = u * 0.7;
        return Math.abs(runs[i] - u) < tol
                && Math.abs(runs[i + 1] - u) < tol
                && Math.abs(runs[i + 2] - 3 * u) < 3 * tol
                && Math.abs(runs[i + 3] - u) < tol
                && Math.abs(runs[i + 4] - u) < tol;
    }

    private static Finder centerOfRun(byte[] gray, int w, int h, int y, int sx,
                                       int[] runs, int i, int total) {
        double cx = sx + (runs[i] + runs[i + 1] + runs[i + 2] / 2.0);
        int cxInt = (int) Math.floor(cx + 0.5);
        if (cxInt < 0 || cxInt >= w) return null;

        int up = 0, down = 0;
        int limit = total;
        int yy = y - 1;
        while (yy >= 0 && (gray[yy * w + cxInt] & 0xFF) < THRESHOLD && y - yy <= limit) { up++; yy--; }
        yy = y + 1;
        while (yy < h && (gray[yy * w + cxInt] & 0xFF) < THRESHOLD && yy - y <= limit) { down++; yy++; }
        int vert = up + down + 1;
        if (vert < 2) return null;

        Finder f = new Finder();
        f.x = cx;
        f.y = (y - up + y + down) / 2.0;
        f.moduleSize = total / 7.0;
        return f;
    }

    // ---------------- 排序 ----------------

    private static Finder[] orderFinders(Finder[] f) {
        double d01 = Math.hypot(f[0].x - f[1].x, f[0].y - f[1].y);
        double d02 = Math.hypot(f[0].x - f[2].x, f[0].y - f[2].y);
        double d12 = Math.hypot(f[1].x - f[2].x, f[1].y - f[2].y);

        int pivot, a, b;
        if (d01 >= d02 && d01 >= d12) { pivot = 2; a = 0; b = 1; }
        else if (d02 >= d01 && d02 >= d12) { pivot = 1; a = 0; b = 2; }
        else { pivot = 0; a = 1; b = 2; }

        Finder tl = f[pivot], p1 = f[a], p2 = f[b];
        // 屏幕坐标 y 向下：右上与左上同一水平线（y 更接近），左下 y 更远
        double dy1 = Math.abs(p1.y - tl.y);
        double dy2 = Math.abs(p2.y - tl.y);
        Finder tr, bl;
        if (Math.abs(dy1 - dy2) < 1e-6) {
            if (Math.abs(p1.x - tl.x) > Math.abs(p2.x - tl.x)) { tr = p1; bl = p2; }
            else { tr = p2; bl = p1; }
        } else if (dy1 < dy2) { tr = p1; bl = p2; }
        else { tr = p2; bl = p1; }

        return new Finder[]{tl, tr, bl};
    }

    private static int estimateDimension(Finder[] f) {
        double module = (f[0].moduleSize + f[1].moduleSize + f[2].moduleSize) / 3.0;
        double dTop = Math.hypot(f[0].x - f[1].x, f[0].y - f[1].y) / module;
        double dLeft = Math.hypot(f[0].x - f[2].x, f[0].y - f[2].y) / module;
        double est = (dTop + dLeft) / 2.0 + 7;
        int dim = 21;
        double bestErr = Double.MAX_VALUE;
        for (int v = 1; v <= 10; v++) {
            int d = v * 4 + 17;
            double err = Math.abs(d - est);
            if (err < bestErr) { bestErr = err; dim = d; }
        }
        return dim;
    }

    // ---------------- 采样 ----------------

    private static boolean[][] sampleGrid(byte[] gray, int w, int h, Finder[] f, int dim,
                                          double tweakX, double tweakY) {
        double[] gx = {3.5, dim - 3.5, 3.5};
        double[] gy = {3.5, 3.5, dim - 3.5};
        double[] ix = {f[0].x, f[1].x, f[2].x};
        double[] iy = {f[0].y, f[1].y, f[2].y};

        double det = gx[0] * (gy[1] - gy[2]) - gy[0] * (gx[1] - gx[2])
                + (gx[1] * gy[2] - gx[2] * gy[1]);
        if (Math.abs(det) < 1e-6) return null;

        double a = (ix[0] * (gy[1] - gy[2]) - gy[0] * (ix[1] - ix[2])
                + (ix[1] * gy[2] - ix[2] * gy[1])) / det;
        double b = (gx[0] * (ix[1] - ix[2]) - ix[0] * (gx[1] - gx[2])
                + (gx[1] * ix[2] - gx[2] * ix[1])) / det;
        double c = (gx[0] * (gy[1] * ix[2] - gy[2] * ix[1])
                - gy[0] * (gx[1] * ix[2] - gx[2] * ix[1])
                + ix[0] * (gx[1] * gy[2] - gx[2] * gy[1])) / det;

        double a2 = (iy[0] * (gy[1] - gy[2]) - gy[0] * (iy[1] - iy[2])
                + (iy[1] * gy[2] - iy[2] * gy[1])) / det;
        double b2 = (gx[0] * (iy[1] - iy[2]) - iy[0] * (gx[1] - gx[2])
                + (gx[1] * iy[2] - gx[2] * iy[1])) / det;
        double c2 = (gx[0] * (gy[1] * iy[2] - gy[2] * iy[1])
                - gy[0] * (gx[1] * iy[2] - gx[2] * iy[1])
                + iy[0] * (gx[1] * gy[2] - gx[2] * gy[1])) / det;

        boolean[][] g = new boolean[dim][dim];
        for (int r = 0; r < dim; r++) {
            for (int col = 0; col < dim; col++) {
                double gxx = col + 0.5 + tweakX;
                double gyy = r + 0.5 + tweakY;
                double px = a * gxx + b * gyy + c;
                double py = a2 * gxx + b2 * gyy + c2;
                int xx = (int) Math.floor(px + 0.5);
                int yy = (int) Math.floor(py + 0.5);
                if (xx < 0) xx = 0;
                if (yy < 0) yy = 0;
                if (xx >= w) xx = w - 1;
                if (yy >= h) yy = h - 1;
                g[r][col] = (gray[yy * w + xx] & 0xFF) < THRESHOLD;
            }
        }
        return g;
    }

    // ---------------- 透视校正采样 ----------------

    /**
     * 用对齐图形（alignment pattern）作为第 4 个锚点，拟合单应变换后采样。
     * 拍照有透视时，纯仿射会让中间模块偏移，这一轮把它纠正回来。
     */
    private static boolean[][] sampleGridPerspective(byte[] gray, int w, int h,
                                                     Finder[] f, int dim) {
        int ver = (dim - 17) / 4;
        if (ver < 2) return null;   // v1 没有对齐图形

        int[] centers = alignCenters(ver);
        if (centers == null || centers.length == 0) return null;

        // 选一个不与寻像图形重叠的对齐中心（通常是靠右下的那个）
        int ac = -1;
        for (int i = centers.length - 1; i >= 0; i--) {
            int v = centers[i];
            // 有效范围：至少偏离左上寻像图形 8 格，且不越过右下边界
            if (v >= 8 && v <= dim - 7) { ac = v; break; }
        }
        if (ac < 0) return null;

        // 先用仿射估计该对齐图形中心在图像里的位置
        double[] gx = {3.5, dim - 3.5, 3.5};
        double[] gy = {3.5, 3.5, dim - 3.5};
        double[] ix = {f[0].x, f[1].x, f[2].x};
        double[] iy = {f[0].y, f[1].y, f[2].y};
        double[] aff = solveAffine(gx, gy, ix, iy);
        if (aff == null) return null;

        double ex = aff[0] * (ac + 0.5) + aff[1] * (ac + 0.5) + aff[2];
        double ey = aff[3] * (ac + 0.5) + aff[4] * (ac + 0.5) + aff[5];

        double module = (f[0].moduleSize + f[1].moduleSize + f[2].moduleSize) / 3.0;
        if (module < 1) return null;

        // 在预测位置附近搜真正的对齐图形中心
        double[] found = findAlignment(gray, w, h, ex, ey, module);
        if (found == null) {
            dbg("perspective: alignment not found");
            return null;
        }
        dbg(String.format("perspective: align pred=(%.0f,%.0f) found=(%.0f,%.0f)",
                ex, ey, found[0], found[1]));

        // 由 4 个点（三角 + 对齐）构建 网格→图像 的单应变换
        double[] H = quadToQuad(
                new double[]{3.5, 3.5, dim - 3.5, 3.5, 3.5, dim - 3.5,
                        ac + 0.5, ac + 0.5},
                new double[]{f[0].x, f[0].y, f[1].x, f[1].y, f[2].x, f[2].y,
                        found[0], found[1]});
        if (H == null) return null;

        boolean[][] g = new boolean[dim][dim];
        for (int r = 0; r < dim; r++) {
            for (int c = 0; c < dim; c++) {
                double[] p = applyH(H, c + 0.5, r + 0.5);
                int xx = (int) Math.floor(p[0] + 0.5);
                int yy = (int) Math.floor(p[1] + 0.5);
                if (xx < 0) xx = 0;
                if (yy < 0) yy = 0;
                if (xx >= w) xx = w - 1;
                if (yy >= h) yy = h - 1;
                g[r][c] = (gray[yy * w + xx] & 0xFF) < THRESHOLD;
            }
        }
        return g;
    }

    /** 在 (px,py) 附近按 ±3 模块范围搜索对齐图形（5x5：黑框、白环、黑心） */
    private static double[] findAlignment(byte[] gray, int w, int h,
                                          double px, double py, double module) {
        double bestScore = Double.MAX_VALUE;
        double[] best = null;
        int step = Math.max(1, (int) (module / 3));
        int radius = (int) (module * 3);

        for (int dy = -radius; dy <= radius; dy += step) {
            for (int dx = -radius; dx <= radius; dx += step) {
                double cx = px + dx, cy = py + dy;
                if (cx < 0 || cy < 0 || cx >= w || cy >= h) continue;
                double score = alignmentScore(gray, w, h, cx, cy, module);
                if (score < bestScore) {
                    bestScore = score;
                    best = new double[]{cx, cy};
                }
            }
        }
        // 得分越低越好；太高说明没找到像样的图形
        if (best == null || bestScore > 6) return null;
        return best;
    }

    /** 对齐图形匹配得分：返回与期望图案不符的采样点数 */
    private static double alignmentScore(byte[] gray, int w, int h,
                                         double cx, double cy, double module) {
        int mismatch = 0;
        for (int r = -2; r <= 2; r++) {
            for (int c = -2; c <= 2; c++) {
                boolean expectDark = (Math.abs(r) == 2 || Math.abs(c) == 2 || (r == 0 && c == 0));
                double sx = cx + c * module;
                double sy = cy + r * module;
                int xx = (int) Math.floor(sx + 0.5);
                int yy = (int) Math.floor(sy + 0.5);
                if (xx < 0 || yy < 0 || xx >= w || yy >= h) { mismatch += 2; continue; }
                boolean dark = (gray[yy * w + xx] & 0xFF) < THRESHOLD;
                if (dark != expectDark) mismatch++;
            }
        }
        return mismatch;
    }

    /** 解仿射：由 3 对点求 (x,y) -> (u,v)，返回 {a,b,c,d,e,f} */
    private static double[] solveAffine(double[] gx, double[] gy, double[] ix, double[] iy) {
        double det = gx[0] * (gy[1] - gy[2]) - gy[0] * (gx[1] - gx[2])
                + (gx[1] * gy[2] - gx[2] * gy[1]);
        if (Math.abs(det) < 1e-9) return null;
        double a = (ix[0] * (gy[1] - gy[2]) - gy[0] * (ix[1] - ix[2])
                + (ix[1] * gy[2] - ix[2] * gy[1])) / det;
        double b = (gx[0] * (ix[1] - ix[2]) - ix[0] * (gx[1] - gx[2])
                + (gx[1] * ix[2] - gx[2] * ix[1])) / det;
        double c = (gx[0] * (gy[1] * ix[2] - gy[2] * ix[1])
                - gy[0] * (gx[1] * ix[2] - gx[2] * ix[1])
                + ix[0] * (gx[1] * gy[2] - gx[2] * gy[1])) / det;
        double a2 = (iy[0] * (gy[1] - gy[2]) - gy[0] * (iy[1] - iy[2])
                + (iy[1] * gy[2] - iy[2] * gy[1])) / det;
        double b2 = (gx[0] * (iy[1] - iy[2]) - iy[0] * (gx[1] - gx[2])
                + (gx[1] * iy[2] - gx[2] * iy[1])) / det;
        double c2 = (gx[0] * (gy[1] * iy[2] - gy[2] * iy[1])
                - gy[0] * (gx[1] * iy[2] - gx[2] * iy[1])
                + iy[0] * (gx[1] * gy[2] - gx[2] * gy[1])) / det;
        return new double[]{a, b, c, a2, b2, c2};
    }

    /**
     * 由 4 对点求单应变换（网格坐标 → 图像坐标）。
     * 源点顺序：TL, TR, BL, EXTRA（第 4 点为对齐图形中心）。
     */
    private static double[] quadToQuad(double[] src, double[] dst) {
        // 用一般 8 参数求解：对每对点
        //  x' = (a*x + b*y + c) / (g*x + h*y + 1)
        //  y' = (d*x + e*y + f) / (g*x + h*y + 1)
        double[][] A = new double[8][8];
        double[] B = new double[8];
        for (int i = 0; i < 4; i++) {
            double x = src[i * 2], y = src[i * 2 + 1];
            double X = dst[i * 2], Y = dst[i * 2 + 1];
            A[i * 2] = new double[]{x, y, 1, 0, 0, 0, -x * X, -y * X};
            B[i * 2] = X;
            A[i * 2 + 1] = new double[]{0, 0, 0, x, y, 1, -x * Y, -y * Y};
            B[i * 2 + 1] = Y;
        }
        double[] h = gauss(A, B);
        if (h == null) return null;
        return new double[]{h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7]};
    }

    private static double[] applyH(double[] H, double x, double y) {
        double den = H[6] * x + H[7] * y + 1.0;
        if (Math.abs(den) < 1e-12) den = 1e-12;
        double X = (H[0] * x + H[1] * y + H[2]) / den;
        double Y = (H[3] * x + H[4] * y + H[5]) / den;
        return new double[]{X, Y};
    }

    /** 高斯消元解 Ax=B */
    private static double[] gauss(double[][] A, double[] B) {
        int n = B.length;
        double[][] m = new double[n][n + 1];
        for (int i = 0; i < n; i++) {
            System.arraycopy(A[i], 0, m[i], 0, n);
            m[i][n] = B[i];
        }
        for (int col = 0; col < n; col++) {
            int piv = -1;
            double best = 1e-12;
            for (int r = col; r < n; r++) {
                if (Math.abs(m[r][col]) > best) { best = Math.abs(m[r][col]); piv = r; }
            }
            if (piv < 0) return null;
            double[] tmp = m[col]; m[col] = m[piv]; m[piv] = tmp;

            double d = m[col][col];
            for (int c = col; c <= n; c++) m[col][c] /= d;

            for (int r = 0; r < n; r++) {
                if (r == col) continue;
                double factor = m[r][col];
                if (factor == 0) continue;
                for (int c = col; c <= n; c++) m[r][c] -= factor * m[col][c];
            }
        }
        double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = m[i][n];
        return x;
    }

    // ---------------- 解码 ----------------

    private static final int[] ALIGN2 = {6, 18};
    private static final int[] ALIGN3 = {6, 22};
    private static final int[] ALIGN4 = {6, 26};
    private static final int[] ALIGN5 = {6, 30};
    private static final int[] ALIGN6 = {6, 34};
    private static final int[] ALIGN7 = {6, 22, 38};
    private static final int[] ALIGN8 = {6, 24, 42};
    private static final int[] ALIGN9 = {6, 26, 46};
    private static final int[] ALIGN10 = {6, 28, 50};

    private static int[] alignCenters(int ver) {
        switch (ver) {
            case 2: return ALIGN2;
            case 3: return ALIGN3;
            case 4: return ALIGN4;
            case 5: return ALIGN5;
            case 6: return ALIGN6;
            case 7: return ALIGN7;
            case 8: return ALIGN8;
            case 9: return ALIGN9;
            case 10: return ALIGN10;
            default: return new int[0];
        }
    }

    private static boolean[][] buildFuncMap(int dim, int ver) {
        boolean[][] f = new boolean[dim][dim];
        // 三个寻像图形 + 分隔符
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                f[r][c] = true;
                f[r][dim - 1 - c] = true;
                f[dim - 1 - r][c] = true;
            }
        }
        // 定时图形
        for (int i = 0; i < dim; i++) { f[6][i] = true; f[i][6] = true; }
        // 对齐图形
        int[] al = alignCenters(ver);
        for (int ar : al) {
            for (int ac : al) {
                if ((ar <= 8 && ac <= 8) || (ar <= 8 && ac >= dim - 9) || (ar >= dim - 9 && ac <= 8)) continue;
                for (int dr = -2; dr <= 2; dr++)
                    for (int dc = -2; dc <= 2; dc++)
                        if (ar + dr >= 0 && ar + dr < dim && ac + dc >= 0 && ac + dc < dim)
                            f[ar + dr][ac + dc] = true;
            }
        }
        // 格式信息
        for (int i = 0; i <= 8; i++) { f[8][i] = true; f[i][8] = true; }
        for (int i = 0; i < 8; i++) { f[8][dim - 1 - i] = true; f[dim - 1 - i][8] = true; }
        // 暗模块
        f[dim - 8][8] = true;
        // 版本信息（>=7）
        if (ver >= 7) {
            for (int i = 0; i < 18; i++) {
                int a = i / 3;
                int b = dim - 11 + (i % 3);
                f[b][a] = true;
                f[a][b] = true;
            }
        }
        return f;
    }

    private static boolean maskAt(int mask, int r, int c) {
        switch (mask) {
            case 0: return (r + c) % 2 == 0;
            case 1: return r % 2 == 0;
            case 2: return c % 3 == 0;
            case 3: return (r + c) % 3 == 0;
            case 4: return (r / 2 + c / 3) % 2 == 0;
            case 5: return ((r * c) % 2) + ((r * c) % 3) == 0;
            case 6: return (((r * c) % 2) + ((r * c) % 3)) % 2 == 0;
            case 7: return (((r + c) % 2) + ((r * c) % 3)) % 2 == 0;
            default: return false;
        }
    }

    private static boolean bchValid(int v) {
        int x = v;
        for (int i = 14; i >= 10; i--) {
            if (((x >> i) & 1) != 0) x ^= 0x537 << (i - 10);
        }
        return x == 0;
    }

    private static final int[] TOTAL_CW = {0, 26, 44, 70, 100, 134, 172, 196, 242, 292, 346};
    private static final int[] DATA_CW = {0, 16, 28, 44, 64, 86, 108, 124, 154, 182, 216};
    private static final int[] BLOCKS = {0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5};

    private static Result decodeMatrix(boolean[][] g, int dim) {
        int ver = (dim - 17) / 4;
        if (ver < 1 || ver > 10) return null;

        // 读格式信息
        int[] bits = new int[15];
        for (int c = 0; c <= 5; c++) bits[14 - c] = g[8][c] ? 1 : 0;
        bits[8] = g[8][7] ? 1 : 0;
        bits[7] = g[8][8] ? 1 : 0;
        bits[6] = g[7][8] ? 1 : 0;
        for (int i = 0; i <= 5; i++) bits[5 - i] = g[5 - i][8] ? 1 : 0;

        int raw = 0;
        for (int k = 0; k < 15; k++) raw = (raw << 1) | bits[k];
        raw ^= 0x5412;
        if (!bchValid(raw)) {
            int alt = 0;
            for (int k = 14; k >= 0; k--) alt = (alt << 1) | bits[k];
            alt ^= 0x5412;
            if (bchValid(alt)) raw = alt;
        }
        int mask = ((raw >>> 10) & 0x1f) & 0x07;

        // 读数据位
        boolean[][] funcMap = buildFuncMap(dim, ver);
        int capacity = dim * dim;
        boolean[] outBits = new boolean[capacity];
        int bitCount = 0;
        boolean upward = true;
        for (int col = dim - 1; col > 0; col -= 2) {
            if (col == 6) col--;
            for (int k = 0; k < dim; k++) {
                int row = upward ? dim - 1 - k : k;
                for (int dc = 0; dc < 2; dc++) {
                    int c = col - dc;
                    if (funcMap[row][c]) continue;
                    boolean v = g[row][c];
                    if (maskAt(mask, row, c)) v = !v;
                    if (bitCount < capacity) outBits[bitCount++] = v;
                }
            }
            upward = !upward;
        }

        // 解交织
        int totalCw = TOTAL_CW[ver];
        int dataCw = DATA_CW[ver];
        int blocks = BLOCKS[ver];
        int base = dataCw / blocks;
        int extra = dataCw % blocks;

        int[][] blockData = new int[blocks][];
        for (int i = 0; i < blocks; i++) {
            blockData[i] = new int[base + (i >= blocks - extra ? 1 : 0)];
        }

        int p = 0;
        int maxLen = base + (extra > 0 ? 1 : 0);
        for (int i = 0; i < maxLen; i++) {
            for (int b = 0; b < blocks; b++) {
                if (i < blockData[b].length) {
                    blockData[b][i] = readByteAt(outBits, p, bitCount);
                    p += 8;
                }
            }
        }

        int[] data = new int[dataCw];
        int dp = 0;
        for (int b = 0; b < blocks; b++) {
            for (int i = 0; i < blockData[b].length; i++) {
                if (dp < dataCw) data[dp++] = blockData[b][i];
            }
        }

        return parseSegments(data, ver, mask);
    }

    private static int readByteAt(boolean[] bits, int pos, int len) {
        int v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 1) | ((pos + i < len && bits[pos + i]) ? 1 : 0);
        }
        return v;
    }

    private static int readBits(int[] data, int pos, int n) {
        int v = 0;
        for (int i = 0; i < n; i++) {
            int idx = pos + i;
            int byteIdx = idx >> 3;
            int bitIdx = 7 - (idx & 7);
            int bit = (byteIdx < data.length) ? ((data[byteIdx] >> bitIdx) & 1) : 0;
            v = (v << 1) | bit;
        }
        return v;
    }

    private static Result parseSegments(int[] data, int ver, int mask) {
        int bitPos = 0;
        int totalBits = data.length * 8;
        StringBuilder out = new StringBuilder();
        int segments = 0;

        while (bitPos + 4 <= totalBits) {
            int mode = readBits(data, bitPos, 4);
            bitPos += 4;
            if (mode == 0) break;
            segments++;
            if (segments > 8) break; // 段数异常多说明解错了

            if (mode == 4) {
                int len = readBits(data, bitPos, ver <= 9 ? 8 : 16);
                bitPos += ver <= 9 ? 8 : 16;
                if (len <= 0 || bitPos + len * 8 > totalBits) return null;
                byte[] buf = new byte[len];
                for (int i = 0; i < len; i++) {
                    buf[i] = (byte) readBits(data, bitPos, 8);
                    bitPos += 8;
                }
                String s = utf8(buf);
                if (!printable(s)) return null;   // 解出不可打印字符 → 判定解错
                out.append(s);

            } else if (mode == 2) {
                int len = readBits(data, bitPos, ver <= 9 ? 9 : 11);
                bitPos += ver <= 9 ? 9 : 11;
                if (len <= 0 || bitPos + len * 11 > totalBits + 11) return null;
                String ALNUM = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:";
                for (int i = 0; i < len; i++) {
                    if (bitPos + 11 > totalBits) return null;
                    int v = readBits(data, bitPos, 11);
                    bitPos += 11;
                    if (v >= 45) return null;     // 非法字母表索引 → 解错
                    out.append(ALNUM.charAt(v));
                }

            } else if (mode == 1) {
                int len = readBits(data, bitPos, ver <= 9 ? 10 : 12);
                bitPos += ver <= 9 ? 10 : 12;
                // 数字模式：每 3 位一个 10bit 码字，值必须 < 1000
                if (len <= 0 || len > 7089) return null;
                for (int i = 0; i < len; i += 3) {
                    int remain = len - i;
                    int width = remain >= 3 ? 10 : (remain == 2 ? 7 : 4);
                    if (bitPos + width > totalBits) return null;
                    int v = readBits(data, bitPos, width);
                    bitPos += width;
                    // 严格校验：值必须落在该宽度允许的数字范围内
                    if (remain >= 3 && v > 999) return null;
                    if (remain == 2 && v > 99) return null;
                    if (remain == 1 && v > 9) return null;
                    if (remain >= 3) {
                        out.append((char) ('0' + v / 100));
                        out.append((char) ('0' + (v / 10) % 10));
                        out.append((char) ('0' + v % 10));
                    } else if (remain == 2) {
                        out.append((char) ('0' + v / 10));
                        out.append((char) ('0' + v % 10));
                    } else {
                        out.append((char) ('0' + v));
                    }
                }

            } else if (mode == 7) { // ECI
                int designator = readBits(data, bitPos, 8);
                bitPos += 8;
                if ((designator & 0x80) != 0) {
                    if ((designator & 0xC0) == 0x80) bitPos += 8;
                    else if ((designator & 0xE0) == 0xC0) bitPos += 16;
                }
            } else {
                return null;   // 不支持的段类型 → 判定解错
            }
        }

        if (out.length() == 0) return null;
        String result = out.toString();
        if (!printable(result)) return null;
        return new Result(result, ver, mask);
    }

    /** 检查字符串是否由可打印字符组成（允许 CJK 等非 ASCII） */
    private static boolean printable(String s) {
        if (s == null || s.length() == 0) return false;
        int bad = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\uFFFD') return false;            // UTF-8 解码失败的替换符
            if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') bad++;
        }
        return bad <= 0;
    }

    private static String utf8(byte[] buf) {
        try {
            return new String(buf, "UTF-8");
        } catch (Throwable t) {
            return new String(buf);
        }
    }
}