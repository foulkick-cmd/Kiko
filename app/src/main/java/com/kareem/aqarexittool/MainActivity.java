package com.kareem.aqarexittool;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.content.pm.PackageManager;
import android.provider.MediaStore;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {

    private static final String HOME = "https://aqarexit.com/opportunities";
    public static final String EXTRA_URL = "url";

    private WebView web;
    private String toolJs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        toolJs = Assets.read(this, "ax-tool.js");

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        // Present as regular Chrome on Android (drop the WebView "; wv" marker).
        s.setUserAgentString(s.getUserAgentString().replace("; wv", ""));

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(new Bridge(), "AXApp");
        web.setWebChromeClient(new WebChromeClient()); // enables the site's alert/confirm dialogs
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                String host = u.getHost() == null ? "" : u.getHost();
                String scheme = u.getScheme() == null ? "" : u.getScheme();
                if ((scheme.equals("https") || scheme.equals("http")) && host.endsWith("aqarexit.com")) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (ActivityNotFoundException ignored) {
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                inject();
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                inject(); // client-side navigations inside the site
            }
        });

        String start = getIntent().getStringExtra(EXTRA_URL);
        web.loadUrl(start != null ? start : HOME);
    }

    private void inject() {
        if (toolJs != null) web.evaluateJavascript(toolJs, null);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    /** Methods callable from the page as window.AXApp.* */
    private class Bridge {
        @JavascriptInterface
        public void openTab(String url) {
            Intent i = new Intent(MainActivity.this, MainActivity.class);
            i.putExtra(EXTRA_URL, url);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            startActivity(i);
        }

        /** the page tells us how many watches exist: keep the hourly check (and notification permission) in step */
        @JavascriptInterface
        public void watchesChanged(int n) {
            runOnUiThread(() -> {
                WatchScheduler.apply(MainActivity.this, n);
                if (n > 0 && Build.VERSION.SDK_INT >= 33
                        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
                }
            });
        }

        /** "check now" from the watches screen */
        @JavascriptInterface
        public void watchDone(String json) {
            Notifier.post(getApplicationContext(), json);
        }

        @JavascriptInterface
        public void closeTab() {
            runOnUiThread(MainActivity.this::finish);
        }

        @JavascriptInterface
        public void saveFile(String name, String content, String mime) {
            runOnUiThread(() -> {
                try {
                    ContentResolver cr = getContentResolver();
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    v.put(MediaStore.Downloads.MIME_TYPE, mime);
                    Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) throw new IllegalStateException("insert failed");
                    try (OutputStream os = cr.openOutputStream(uri)) {
                        os.write(content.getBytes(StandardCharsets.UTF_8));
                    }
                    Toast.makeText(MainActivity.this, "اتحفظ في Downloads: " + name, Toast.LENGTH_LONG).show();
                    Intent share = new Intent(Intent.ACTION_SEND);
                    share.setType(mime);
                    share.putExtra(Intent.EXTRA_STREAM, uri);
                    share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(share, "مشاركة الملف"));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "فشل الحفظ: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        }
    }
}
