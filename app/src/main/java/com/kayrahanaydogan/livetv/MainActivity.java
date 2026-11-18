package com.kayrahanaydogan.livetv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public final class MainActivity extends Activity {
    private static final int REQUEST_UPLOAD = 1001;
    private static final int REQUEST_SAVE = 1002;
    private static final int REQUEST_PLAYER = 1003;
    private static final String LOCAL_HOST = "appassets.androidplatform.net";
    private WebView webView;
    private android.webkit.ValueCallback<Uri[]> uploadCallback;
    private String pendingExport;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(9, 9, 11));
        getWindow().setNavigationBarColor(Color.rgb(9, 9, 11));

        WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(9, 9, 11));
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        if (Build.VERSION.SDK_INT >= 26) settings.setSafeBrowsingEnabled(true);
        webView.addJavascriptInterface(new AndroidBridge(), "AndroidTv");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (LOCAL_HOST.equalsIgnoreCase(uri.getHost()) && "https".equalsIgnoreCase(uri.getScheme())) return false;
                openExternal(uri.toString());
                return true;
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView web, android.webkit.ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (uploadCallback != null) uploadCallback.onReceiveValue(null);
                uploadCallback = callback;
                Intent choose = new Intent(Intent.ACTION_GET_CONTENT);
                choose.addCategory(Intent.CATEGORY_OPENABLE);
                // File extension filters are unreliable for M3U on several Android file managers.
                choose.setType("*/*");
                try { startActivityForResult(choose, REQUEST_UPLOAD); }
                catch (ActivityNotFoundException e) {
                    uploadCallback.onReceiveValue(null);
                    uploadCallback = null;
                    toast("file picker unavailable");
                }
                return true;
            }
        });
        setContentView(webView);
        if (Build.VERSION.SDK_INT >= 35) {
            webView.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                return insets;
            });
        }
        if (savedInstanceState == null) {
            webView.loadUrl("https://appassets.androidplatform.net/assets/index.html");
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void openExternal(String url) {
        try {
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            if (("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                    && uri.getHost() != null) {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } else toast("only http and https links are supported");
        } catch (Exception e) { toast("no app can open this link"); }
    }

    private final class AndroidBridge {
        @JavascriptInterface
        public void play(String payload) {
            if (payload == null || payload.length() > 350_000) return;
            runOnUiThread(() -> {
                try {
                    JSONObject json = new JSONObject(payload);
                    if (!json.has("sources") || json.getJSONArray("sources").length() == 0) {
                        toast("no playable sources were found");
                        return;
                    }
                    Intent intent = new Intent(MainActivity.this, PlayerActivity.class);
                    intent.putExtra("player_data", payload);
                    startActivityForResult(intent, REQUEST_PLAYER);
                } catch (Exception ignored) { toast("invalid channel data"); }
            });
        }

        @JavascriptInterface
        public void exportText(String filename, String mime, String content) {
            if (content == null || content.length() > 5_000_000) return;
            runOnUiThread(() -> {
                if (pendingExport != null) { toast("finish the previous export first"); return; }
                String cleanName = filename == null ? "export.txt" : filename
                        .replaceAll("[^A-Za-z0-9._-]", "_");
                if (cleanName.isEmpty()) cleanName = "export.txt";
                String type = "application/json".equals(mime) ? "application/json" : "text/plain";
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType(type);
                intent.putExtra(Intent.EXTRA_TITLE, cleanName);
                pendingExport = content;
                try { startActivityForResult(intent, REQUEST_SAVE); }
                catch (ActivityNotFoundException e) { pendingExport = null; toast("save dialog unavailable"); }
            });
        }

        @JavascriptInterface
        public void openExternal(String url) {
            runOnUiThread(() -> MainActivity.this.openExternal(url));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_UPLOAD) {
            if (uploadCallback != null) {
                Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
                uploadCallback.onReceiveValue(result);
                uploadCallback = null;
            }
        } else if (requestCode == REQUEST_SAVE) {
            try {
                if (resultCode == RESULT_OK && data != null && data.getData() != null && pendingExport != null) {
                    try (OutputStream stream = getContentResolver().openOutputStream(data.getData())) {
                        if (stream == null) throw new Exception("missing stream");
                        stream.write(pendingExport.getBytes(StandardCharsets.UTF_8));
                    }
                    toast("file saved");
                }
            } catch (Exception e) { toast("could not save file"); }
            finally { pendingExport = null; }
        } else if (requestCode == REQUEST_PLAYER) {
            if (data != null && webView != null) {
                try {
                    JSONObject result = new JSONObject();
                    result.put("url", data.getStringExtra("played_url"));
                    result.put("played", data.getBooleanExtra("ever_played", false));
                    webView.evaluateJavascript("window.androidPlaybackResult(" + JSONObject.quote(result.toString()) + ")", null);
                } catch (Exception ignored) { }
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        if (webView != null) webView.saveState(state);
        super.onSaveInstanceState(state);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (uploadCallback != null) {
            uploadCallback.onReceiveValue(null);
            uploadCallback = null;
        }
        if (webView != null) {
            webView.removeJavascriptInterface("AndroidTv");
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
