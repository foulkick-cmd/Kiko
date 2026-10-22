package com.kareem.headlink;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import com.kareem.headlink.adb.AdbConnection;
import com.kareem.headlink.adb.AdbKey;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** One USB ADB link to the phone, shared by every screen pane. Uploads the server once. */
final class PhoneLink {

    static final String SERVER_VERSION = "4.1"; // must match the scrcpy-server.jar fetched by the build
    static final String SERVER_PATH = "/data/local/tmp/headlink-server.jar";

    interface Listener {
        void onLinkStatus(String text);

        void onLinkLog(String line);

        void onLinkReady();

        void onLinkApps(List<AppEntry> apps);

        void onLinkFailed(String reason);
    }

    final UsbDevice device;
    private final Context ctx;
    private final UsbManager usb;
    private final Listener listener;
    private volatile AdbConnection adb;
    private volatile boolean closed;

    PhoneLink(Context ctx, UsbManager usb, UsbDevice device, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.usb = usb;
        this.device = device;
        this.listener = listener;
    }

    AdbConnection adb() {
        return adb;
    }

    void start() {
        Thread t = new Thread(this::run, "phone-link");
        t.start();
    }

    private void run() {
        try {
            listener.onLinkStatus("Connecting to phone...");
            AdbKey key = AdbKey.loadOrCreate(ctx.getFilesDir());
            adb = AdbConnection.open(usb, device, key,
                    () -> listener.onLinkStatus("On the phone: allow USB debugging (tick \"Always allow\")"), 20_000);
            if (closed) {
                adb.close();
                return;
            }
            listener.onLinkLog("ADB: " + adb.getDeviceBanner());
            listener.onLinkStatus("Uploading server...");
            adb.pushFile(readAsset("scrcpy-server.jar"), SERVER_PATH, listener::onLinkLog);
            if (closed) {
                return;
            }
            listener.onLinkReady();

            String out = adb.shell("CLASSPATH=" + SERVER_PATH + " app_process / com.genymobile.scrcpy.Server "
                    + SERVER_VERSION + " list_apps=true cleanup=false log_level=info", 30_000);
            List<AppEntry> apps = AppEntry.parse(out);
            listener.onLinkLog("Apps found: " + apps.size());
            if (apps.isEmpty()) {
                listener.onLinkLog(out);
            }
            listener.onLinkApps(apps);
        } catch (Exception e) {
            if (!closed) {
                String m = e.getMessage();
                listener.onLinkFailed(m == null ? e.getClass().getSimpleName() : m);
            }
        }
    }

    private byte[] readAsset(String name) throws IOException {
        try (InputStream in = ctx.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    /** Non-blocking: USB writes may stall if the phone was unplugged. */
    void close() {
        closed = true;
        AdbConnection a = adb;
        if (a != null) {
            new Thread(a::close, "link-close").start();
        }
    }
}
