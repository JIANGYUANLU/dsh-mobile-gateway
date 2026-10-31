package com.dsh.mobile;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {

    private static final String PREFS = "dsh_prefs";
    private static final String KEY_URL = "base_url";
    private static final int REQ_FILE = 1001;
    private static final int REQ_CAMERA = 1002;

    private static final int BG = 0xFF0D0F14;
    private static final int CARD = 0xFF151922;
    private static final int TEXT = 0xFFE6E8EE;
    private static final int SUB = 0xFF8B93A7;
    private static final int ACCENT = 0xFF4D7CFF;
    private static final int BORDER = 0xFF2B3040;

    private WebView web;
    private LinearLayout setupPanel;
    private EditText input;
    private TextView status;
    private FrameLayout root;
    private ValueCallback<Uri[]> filePathCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        buildUi();
        handleIntent(getIntent());
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String saved = sp.getString(KEY_URL, null);
        if (saved != null && saved.length() > 0) {
            loadUrl(saved);
        } else {
            showSetup(null);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    /** 支持 dsh://open?url=... 以及直接带 http(s) 链接启动 */
    private void handleIntent(Intent intent) {
        if (intent == null || intent.getData() == null) return;
        Uri data = intent.getData();
        String raw = null;
        if ("dsh".equals(data.getScheme())) {
            raw = data.getQueryParameter("url");
        } else if ("http".equals(data.getScheme()) || "https".equals(data.getScheme())) {
            raw = data.toString();
        }
        if (raw == null || raw.length() == 0) return;
        String norm = normalize(raw);
        if (norm == null) return;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_URL, norm).apply();
        loadUrl(norm);
    }

    /** 把用户输入规范化成 https://host 形式；无法识别返回 null */
    private String normalize(String raw) {
        String s = raw.trim();
        if (s.length() == 0) return null;
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            s = "https://" + s;
        }
        try {
            Uri u = Uri.parse(s);
            String host = u.getHost();
            if (host == null || host.length() == 0) return null;
            StringBuilder sb = new StringBuilder();
            sb.append("https://").append(host);
            if (u.getPath() != null && u.getPath().length() > 0) sb.append(u.getPath());
            if (u.getQuery() != null && u.getQuery().length() > 0) sb.append("?").append(u.getQuery());
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        web = new WebView(this);
        web.setBackgroundColor(BG);
        web.setVisibility(View.GONE);
        configureWebView();
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setupPanel = buildSetupPanel();
        root.addView(setupPanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
    }

    private LinearLayout buildSetupPanel() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setBackgroundColor(BG);
        wrap.setGravity(Gravity.CENTER);
        wrap.setPadding(dp(24), dp(24), dp(24), dp(24));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(CARD);
        card.setPadding(dp(20), dp(22), dp(20), dp(22));
        card.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("DSH");
        title.setTextColor(TEXT);
        title.setTextSize(26);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        card.addView(title);

        TextView sub = new TextView(this);
        sub.setText("连接电脑上的 DeepSeek Harness   ·   " + BuildTag.VERSION);
        sub.setTextColor(SUB);
        sub.setTextSize(13);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(6);
        subLp.bottomMargin = dp(20);
        card.addView(sub, subLp);

        TextView label = new TextView(this);
        label.setText("网关地址");
        label.setTextColor(SUB);
        label.setTextSize(12);
        card.addView(label);

        input = new EditText(this);
        input.setHint("https://xxxx.trycloudflare.com");
        input.setHintTextColor(0xFF5B6478);
        input.setTextColor(TEXT);
        input.setTextSize(15);
        input.setSingleLine(false);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setBackgroundColor(0xFF0A0C11);
        input.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams inLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inLp.topMargin = dp(8);
        inLp.bottomMargin = dp(6);
        card.addView(input, inLp);

        status = new TextView(this);
        status.setTextColor(0xFFFF6B6B);
        status.setTextSize(12);
        status.setVisibility(View.GONE);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stLp.bottomMargin = dp(8);
        card.addView(status, stLp);

        Button connect = new Button(this);
        connect.setText("连接");
        connect.setAllCaps(false);
        connect.setTextColor(Color.WHITE);
        connect.setTextSize(16);
        connect.setBackgroundColor(ACCENT);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        btnLp.topMargin = dp(10);
        connect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String norm = normalize(input.getText().toString());
                if (norm == null) {
                    setStatus("地址格式不对，示例：https://abc.trycloudflare.com");
                    return;
                }
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_URL, norm).apply();
                setStatus(null);
                loadUrl(norm);
            }
        });
        card.addView(connect, btnLp);

        // 扫描二维码连接（主要入口）
        Button scan = new Button(this);
        scan.setText("扫码连接");
        scan.setAllCaps(false);
        scan.setTextColor(Color.WHITE);
        scan.setTextSize(16);
        scan.setBackgroundColor(0xFF2E7D5B);
        LinearLayout.LayoutParams scanLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        scanLp.topMargin = dp(10);
        scan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openScanner();
            }
        });
        card.addView(scan, scanLp);

        Button help = new Button(this);
        help.setText("怎么获取地址？");
        help.setAllCaps(false);
        help.setTextColor(SUB);
        help.setTextSize(13);
        help.setBackgroundColor(0x00000000);
        help.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setStatus(null);
                android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(MainActivity.this);
                b.setTitle("获取网关地址");
                b.setMessage("1. 在电脑上双击 mobile\\start.cmd\n"
                        + "2. 等窗口里出现「手机上打开: https://...」\n"
                        + "3. 点上面的「扫码连接」，对着电脑窗口里的二维码扫一下即可\n\n"
                        + "也可以手动粘贴启动窗口里的完整链接（含 ?k=）。\n"
                        + "注意：每次重启网关地址都会变，扫码是最省事的办法。");
                b.setPositiveButton("知道了", null);
                b.show();
            }
        });
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        hLp.topMargin = dp(4);
        card.addView(help, hLp);

        wrap.addView(card);
        return wrap;
    }

    // ---------------- 扫码 ----------------

    private ScannerView scanner;

    /** 打开扫码页 */
    private void openScanner() {
        setStatus(null);
        requestPermission();
    }

    private void requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.CAMERA)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.CAMERA}, REQ_CAMERA);
                return;
            }
        }
        showScanner();
    }

    private void showScanner() {
        if (scanner != null) return;
        scanner = new ScannerView(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        root.addView(scanner, lp);

        // 取消按钮
        Button cancel = new Button(this);
        cancel.setText("取消");
        cancel.setAllCaps(false);
        cancel.setTextColor(0xFFFFFFFF);
        cancel.setTextSize(14);
        cancel.setBackgroundColor(0x99000000);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(dp(120), dp(44));
        cp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        cp.bottomMargin = dp(20);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeScanner();
            }
        });
        root.addView(cancel, cp);

        scanner.setOnDecoded(new ScannerView.OnDecoded() {
            @Override
            public void onDecoded(String text) {
                closeScanner();
                onScanResult(text);
            }
        });
        scanner.start();
    }

    private void closeScanner() {
        if (scanner == null) return;
        try { scanner.stop(); } catch (Throwable ignored) {}
        try { root.removeView(scanner); } catch (Throwable ignored) {}
        scanner = null;
        // 移除 hint 和 cancel（它们是横向最后一个添加的，按类型清理）
        int n = root.getChildCount();
        for (int i = n - 1; i >= 0; i--) {
            View v = root.getChildAt(i);
            if (v instanceof TextView || (v instanceof Button && ((Button) v).getText().toString().equals("取消"))) {
                root.removeViewAt(i);
            }
        }
    }

    /** 扫到内容：必须是我们自己的地址，才写入并加载 */
    private void onScanResult(String text) {
        if (text == null) return;
        final String t = text.trim();
        if (t.length() == 0) {
            showSetup("扫到的是空内容，请重试");
            return;
        }

        // 兼容 dsh://open?url=... 或我们的 AppLink
        String raw = t;
        if (t.startsWith("dsh://")) {
            try {
                Uri u = Uri.parse(t);
                String q = u.getQueryParameter("url");
                if (q != null && q.length() > 0) raw = q;
            } catch (Throwable ignored) {
            }
        }
        // 有些码会把链接放在 url= 之类参数里，尝试兜底提取
        if (!looksLikeUrl(raw)) {
            String extracted = extractUrl(t);
            if (extracted != null) raw = extracted;
        }

        final String finalRaw = raw;

        if (!looksLikeUrl(finalRaw)) {
            // 不是网址：把扫到的原文展示出来，让用户知道扫到了什么
            new android.app.AlertDialog.Builder(this)
                    .setTitle("扫到的内容不是网址")
                    .setMessage("扫描结果：\n\n" + t
                            + "\n\n请确认对着的是电脑上 start.cmd 窗口里的那张二维码。")
                    .setPositiveButton("重新扫描", new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface d, int w) { openScanner(); }
                    })
                    .setNegativeButton("手动输入", new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface d, int w) { showSetup(null); }
                    })
                    .show();
            return;
        }

        // 是网址就直接连（不再强制要求 trycloudflare 域名）
        connectTo(finalRaw);
    }

    /** 粗略判断是否像网址 */
    private boolean looksLikeUrl(String s) {
        if (s == null) return false;
        String v = s.trim();
        if (v.length() < 8) return false;
        String lower = v.toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /** 从任意文本里尝试提取一个 http(s) 链接 */
    private String extractUrl(String s) {
        try {
            String lower = s.toLowerCase();
            int i = lower.indexOf("https://");
            if (i < 0) i = lower.indexOf("http://");
            if (i < 0) return null;
            String rest = s.substring(i);
            // 截到空白或引号为止
            for (int k = 0; k < rest.length(); k++) {
                char c = rest.charAt(k);
                if (c == ' ' || c == '\n' || c == '\r' || c == '"' || c == '\'' || c == '\u3000') {
                    rest = rest.substring(0, k);
                    break;
                }
            }
            return rest;
        } catch (Throwable t) {
            return null;
        }
    }

    private void connectTo(String raw) {
        String norm = normalize(raw);
        if (norm == null) {
            showSetup("二维码里的地址格式不对");
            return;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_URL, norm).apply();
        setStatus(null);
        if (input != null) input.setText(norm);
        loadUrl(norm);
    }

    private void setStatus(String msg) {
        if (msg == null) {
            status.setVisibility(View.GONE);
        } else {
            status.setText(msg);
            status.setVisibility(View.VISIBLE);
        }
    }

    private void showSetup(String msg) {
        if (web != null) {
            web.setVisibility(View.GONE);
        }
        setupPanel.setVisibility(View.VISIBLE);
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String saved = sp.getString(KEY_URL, null);
        if (saved != null && input != null && input.getText().length() == 0) {
            input.setText(saved);
        }
        setStatus(msg);
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        // 去掉 UA 里的 "; wv"，避免前端把它当成功能受限的 WebView
        String ua = s.getUserAgentString();
        if (ua != null) {
            s.setUserAgentString(ua.replace("; wv", ""));
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cm.setAcceptThirdPartyCookies(web, true);
        }

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    return handleUrl(request.getUrl().toString());
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                CookieManager.getInstance().flush();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && request.isForMainFrame()) {
                    onMainFrameError();
                }
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                    onMainFrameError();
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = cb;
                try {
                    Intent i = params.createIntent();
                    startActivityForResult(i, REQ_FILE);
                    return true;
                } catch (Throwable t) {
                    filePathCallback = null;
                    return false;
                }
            }
        });

        web.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                return false;
            }
        });
    }

    /** 主域名之外的链接交给系统浏览器；同域留在 WebView */
    private boolean handleUrl(String url) {
        if (url == null) return false;
        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_URL, "");
        String baseHost = Uri.parse(saved).getHost();
        String thisHost = Uri.parse(url).getHost();
        if (baseHost != null && baseHost.equalsIgnoreCase(thisHost)) {
            return false;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable ignored) {
        }
        return true;
    }

    private void onMainFrameError() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                showSetup("连接失败。可能地址已过期——网关每次重启地址都会变，请回到电脑上重新启动并复制新地址。");
            }
        });
    }

    private void loadUrl(String url) {
        setupPanel.setVisibility(View.GONE);
        web.setVisibility(View.VISIBLE);
        web.loadUrl(url);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (granted) {
                showScanner();
            } else {
                showSetup("没有相机权限，无法扫码。可以改用上面的地址粘贴方式。");
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE) {
            if (filePathCallback == null) return;
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    results = new Uri[n];
                    for (int i = 0; i < n; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (scanner != null) {
                closeScanner();
                return true;
            }
            if (setupPanel.getVisibility() == View.VISIBLE) {
                return super.onKeyDown(keyCode, event);
            }
            if (event.getRepeatCount() == 0) {
                // 长按由 onKeyLongPress 处理
                event.startTracking();
                return true;
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // 长按返回键：重新配置地址
            showSetup(null);
            input.setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_URL, ""));
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (setupPanel.getVisibility() == View.VISIBLE) {
                return super.onKeyUp(keyCode, event);
            }
            if (event.isTracking() && !event.isCanceled()) {
                if (web.canGoBack()) {
                    web.goBack();
                } else {
                    // 已在首页：回到配置，方便在地址失效时重设
                    showSetup(null);
                }
            }
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
        if (web != null) web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onDestroy() {
        CookieManager.getInstance().flush();
        if (web != null) {
            web.stopLoading();
            web.destroy();
        }
        super.onDestroy();
    }
}