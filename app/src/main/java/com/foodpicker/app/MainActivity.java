package com.foodpicker.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.util.Base64;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.FutureTask;

public class MainActivity extends Activity {

    private static final int REQ_FILE = 1;
    static final String EXTRA_COLLECTED = "collected";

    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    /** 别的 App 分享进来、还没交给网页的内容（JSON）；网页通过 Android.takeShared() 取走 */
    private volatile String pendingShare;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        webView.addJavascriptInterface(new Bridge(), "Android");
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                notifyShare();
            }
        });
        setContentView(webView);
        handleShare(getIntent());
        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl("file:///android_asset/index.html");
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShare(intent);
        notifyShare();
    }

    private void notifyShare() {
        if (pendingShare != null) webView.evaluateJavascript("window.onShared && onShared()", null);
    }

    /** 把分享进来的文字或图片整理成 JSON：{"text": "..."} 或 {"images": ["data:image/...;base64,..."]} */
    @SuppressWarnings("deprecation")
    private void handleShare(Intent intent) {
        if (intent == null) return;
        if (intent.getBooleanExtra(EXTRA_COLLECTED, false)) {
            // 自动采集服务截好的图
            intent.removeExtra(EXTRA_COLLECTED);
            try {
                JSONArray images = new JSONArray();
                synchronized (AutoCollectService.collected) {
                    for (String s : AutoCollectService.collected) images.put(s);
                    AutoCollectService.collected.clear();
                }
                if (images.length() > 0) pendingShare = new JSONObject().put("images", images).put("auto", true).toString();
            } catch (Exception ignored) {
            }
            return;
        }
        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) return;
        try {
            JSONObject out = new JSONObject();
            String type = intent.getType() == null ? "" : intent.getType();
            if (type.startsWith("text/")) {
                CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
                if (text == null) return;
                out.put("text", text.toString());
            } else {
                List<Uri> uris = new ArrayList<>();
                if (Intent.ACTION_SEND.equals(action)) {
                    Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                    if (u != null) uris.add(u);
                } else {
                    ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                    if (list != null) uris.addAll(list);
                }
                JSONArray images = new JSONArray();
                for (Uri u : uris) {
                    String mime = getContentResolver().getType(u);
                    byte[] bytes = readBytes(getContentResolver().openInputStream(u));
                    images.put("data:" + (mime == null ? "image/jpeg" : mime) + ";base64," + Base64.encodeToString(bytes, Base64.NO_WRAP));
                }
                if (images.length() == 0) return;
                out.put("images", images);
            }
            pendingShare = out.toString();
        } catch (Exception e) {
            Toast.makeText(this, "读取分享内容失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        // 处理过就清掉，避免旋转屏幕等重建时再导入一次
        intent.setAction(Intent.ACTION_MAIN);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_FILE || fileCallback == null) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null) {
                result = new Uri[clip.getItemCount()];
                for (int i = 0; i < clip.getItemCount(); i++) result[i] = clip.getItemAt(i).getUri();
            } else if (data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
        }
        fileCallback.onReceiveValue(result);
        fileCallback = null;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // 先让网页关闭弹窗 / 回到首页，网页不处理时才退出
        webView.evaluateJavascript("window.handleBack ? handleBack() : false", value -> {
            if (!"true".equals(value)) {
                MainActivity.super.onBackPressed();
            }
        });
    }

    private class Bridge {
        @JavascriptInterface
        public void copy(String text) {
            runOnUiThread(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("food-picker", text));
                Toast.makeText(MainActivity.this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
            });
        }

        /** unsupported（低于 Android 11）/ disabled（无障碍没开）/ ready */
        @JavascriptInterface
        public String autoStatus() {
            if (!AutoCollectService.isSupported()) return "unsupported";
            return AutoCollectService.isEnabled(MainActivity.this) ? "ready" : "disabled";
        }

        @JavascriptInterface
        public void openAccessibilitySettings() {
            runOnUiThread(() -> startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        }

        /** 打开本 App 的「应用信息」页（Android 13+ 侧载应用要先在这里「允许受限制的设置」） */
        @JavascriptInterface
        public void openAppInfo() {
            runOnUiThread(() -> startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()))));
        }

        /** 显示悬浮按钮并打开微信 */
        @JavascriptInterface
        public boolean startAutoCollect() {
            Intent wx = getPackageManager().getLaunchIntentForPackage("com.tencent.mm");
            AutoCollectService.arm();
            if (wx == null) return false;
            runOnUiThread(() -> startActivity(wx));
            return true;
        }

        @JavascriptInterface
        public String takeShared() {
            String s = pendingShare;
            pendingShare = null;
            return s;
        }

        /** 读剪贴板文字（必须在主线程读，Android 10+ 只有前台 App 能读到） */
        @JavascriptInterface
        public String readClipboard() {
            FutureTask<String> task = new FutureTask<>(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm == null || !cm.hasPrimaryClip()) return "";
                ClipData clip = cm.getPrimaryClip();
                if (clip == null || clip.getItemCount() == 0) return "";
                CharSequence t = clip.getItemAt(0).coerceToText(MainActivity.this);
                return t == null ? "" : t.toString();
            });
            runOnUiThread(task);
            try {
                return task.get();
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        @SuppressWarnings("deprecation")
        public void vibrate(int ms) {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(ms);
            }
        }

        /** 由原生发起 POST 请求（避开 WebView 的跨域限制），结果回调 window.__httpCb(id, status, body)。 */
        @JavascriptInterface
        public void post(String id, String url, String headersJson, String body) {
            new Thread(() -> {
                int status = 0;
                String text;
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setRequestMethod("POST");
                    conn.setConnectTimeout(20000);
                    conn.setReadTimeout(180000);
                    conn.setDoOutput(true);
                    JSONObject headers = new JSONObject(headersJson);
                    Iterator<String> keys = headers.keys();
                    while (keys.hasNext()) {
                        String k = keys.next();
                        conn.setRequestProperty(k, headers.getString(k));
                    }
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(body.getBytes(StandardCharsets.UTF_8));
                    }
                    status = conn.getResponseCode();
                    InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
                    text = is == null ? "" : readAll(is);
                } catch (Exception e) {
                    text = String.valueOf(e.getMessage() != null ? e.getMessage() : e);
                } finally {
                    if (conn != null) conn.disconnect();
                }
                String js = "window.__httpCb(" + JSONObject.quote(id) + "," + status + "," + JSONObject.quote(text) + ")";
                runOnUiThread(() -> webView.evaluateJavascript(js, null));
            }).start();
        }
    }

    private static String readAll(InputStream is) throws Exception {
        return new String(readBytes(is), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(InputStream is) throws Exception {
        try (InputStream in = is) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
