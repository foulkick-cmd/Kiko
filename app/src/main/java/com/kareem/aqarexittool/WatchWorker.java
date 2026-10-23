package com.kareem.aqarexittool;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Hourly background check: opens aqarexit.com in an off-screen WebView (same cookies and storage as the
 * app, so the site's protection and the saved watches/blacklist are the same), runs the injected
 * tool's window.axWatchRun(), and posts a notification for watches that found new units.
 */
public class WatchWorker extends Worker {

    private static final String URL = "https://aqarexit.com/opportunities?show=48";

    public WatchWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    /** Called from the page as window.AXWatch.watchDone(json). */
    public static class Bridge {
        final CountDownLatch latch;
        final Handler main;
        final boolean[] started;
        volatile String result;

        Bridge(CountDownLatch latch, Handler main, boolean[] started) {
            this.latch = latch;
            this.main = main;
            this.started = started;
        }

        @JavascriptInterface
        public void watchDone(String json) {
            if (json != null && json.contains("\"retry\":true")) {
                // the site showed its "checking your browser" page; run again after it reloads
                main.post(() -> started[0] = false);
                return;
            }
            result = json;
            latch.countDown();
        }
    }

    @NonNull
    @Override
    public Result doWork() {
        final Context ctx = getApplicationContext();
        final String js = Assets.read(ctx, "ax-tool.js");
        if (js == null) return Result.failure();

        final CountDownLatch latch = new CountDownLatch(1);
        final Handler main = new Handler(Looper.getMainLooper());
        final boolean[] started = {false};
        final Bridge bridge = new Bridge(latch, main, started);
        final WebView[] holder = {null};

        main.post(() -> {
            try {
                WebView w = new WebView(ctx);
                holder[0] = w;
                WebSettings s = w.getSettings();
                s.setJavaScriptEnabled(true);
                s.setDomStorageEnabled(true);
                s.setUserAgentString(s.getUserAgentString().replace("; wv", ""));
                CookieManager.getInstance().setAcceptCookie(true);
                w.addJavascriptInterface(bridge, "AXWatch");
                w.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String url) {
                        if (started[0] || url == null || !url.contains("aqarexit.com")) return;
                        started[0] = true;
                        view.evaluateJavascript("window.AX_HEADLESS=true;\n" + js + "\n;window.axWatchRun&&window.axWatchRun();", null);
                    }
                });
                w.loadUrl(URL);
            } catch (Throwable t) {
                latch.countDown();
            }
        });

        try {
            latch.await(8, TimeUnit.MINUTES);
        } catch (InterruptedException ignored) {
        }
        main.post(() -> {
            if (holder[0] != null) {
                holder[0].stopLoading();
                holder[0].destroy();
            }
        });
        CookieManager.getInstance().flush();
        if (bridge.result != null) Notifier.post(ctx, bridge.result);
        return Result.success();
    }
}
