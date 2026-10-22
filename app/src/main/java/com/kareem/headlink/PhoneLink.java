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
    static final int TCP_PORT = 5555;

    interface Listener {
        void onLinkStatus(String text);

        void onLinkLog(String line);

        void onLinkReady();

        void onLinkApps(List<AppEntry> apps);

        void onLinkFailed(String reason);

        /** USB link switched the phone to Wi-Fi ADB; adbd restarts, so the USB link must be reopened. */
        void onLinkWifiEnabled();
    }

    final UsbDevice device; // null for a Wi-Fi link
    final String host;      // null for a USB link
    private final boolean enableWifi;
    private final Context ctx;
    private final UsbManager usb;
    private final Listener listener;
    private volatile AdbConnection adb;
    private volatile boolean closed;

    /** USB link; if enableWifi, also switches the phone's adbd to TCP for later cable-free use. */
    PhoneLink(Context ctx, UsbManager usb, UsbDevice device, boolean enableWifi, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.usb = usb;
        this.device = device;
        this.host = null;
        this.enableWifi = enableWifi;
        this.listener = listener;
    }

    /** Wi-Fi link to a phone already in TCP mode. */
    PhoneLink(Context ctx, String host, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.usb = null;
        this.device = null;
        this.host = host;
        this.enableWifi = false;
        this.listener = listener;
    }

    boolean isWifi() {
        return host != null;
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
            AdbKey key = AdbKey.loadOrCreate(ctx.getFilesDir());
            AdbConnection.AuthListener approve =
                    () -> listener.onLinkStatus("On the phone: allow USB debugging (tick \"Always allow\")");
            if (isWifi()) {
                listener.onLinkStatus("Connecting over Wi-Fi to " + host + "...");
                adb = AdbConnection.openTcp(host, TCP_PORT, key, approve, 20_000);
            } else {
                listener.onLinkStatus("Connecting to phone (USB)...");
                adb = AdbConnection.open(usb, device, key, approve, 20_000);
            }
            if (closed) {
                adb.close();
                return;
            }
            listener.onLinkLog("ADB" + (isWifi() ? " (Wi-Fi " + host + ")" : " (USB)") + ": " + adb.getDeviceBanner());

            if (enableWifi) {
                String port = adb.shell("getprop service.adb.tcp.port", 10_000).trim();
                listener.onLinkLog("tcp port on phone: '" + port + "'");
                if (!String.valueOf(TCP_PORT).equals(port)) {
                    listener.onLinkStatus("Enabling Wi-Fi mode on the phone...");
                    adb.enableTcpip(TCP_PORT);
                    listener.onLinkLog("Wi-Fi ADB enabled on port " + TCP_PORT + " (phone adbd restarting)");
                    closed = true;
                    adb.close();
                    listener.onLinkWifiEnabled();
                    return;
                }
            }
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
