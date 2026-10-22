package com.kareem.headlink;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.view.Surface;

import com.kareem.headlink.adb.AdbConnection;
import com.kareem.headlink.adb.AdbKey;
import com.kareem.headlink.adb.AdbStream;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs scrcpy-server on the phone with a NEW virtual display sized to the head unit,
 * decodes its video and forwards touches. Nothing is installed on the phone.
 */
final class ScrcpySession {

    static final String SERVER_VERSION = "4.1"; // must match the scrcpy-server.jar fetched by the build
    private static final String SERVER_PATH = "/data/local/tmp/headlink-server.jar";
    private static final int CODEC_H264 = 0x68323634; // "h264"

    interface Callback {
        void onStatus(String text);

        void onLog(String line);

        void onApps(List<AppEntry> apps);

        void onStarted(int width, int height);

        void onEnded(String reason);
    }

    private final Context ctx;
    private final UsbManager usb;
    final UsbDevice device;
    private final Surface surface;
    private final int width;
    private final int height;
    private final int dpi;
    private final Callback cb;

    private volatile boolean running = true;
    private volatile AdbConnection adb;
    private volatile AdbStream control;
    private AdbStream video;
    private AdbStream server;
    private VideoDecoder decoder;
    private volatile int videoW;
    private volatile int videoH;
    private Thread worker;
    private final ExecutorService controlExec = Executors.newSingleThreadExecutor();

    ScrcpySession(Context ctx, UsbManager usb, UsbDevice device, Surface surface,
                  int width, int height, int dpi, Callback cb) {
        this.ctx = ctx.getApplicationContext();
        this.usb = usb;
        this.device = device;
        this.surface = surface;
        this.width = width;
        this.height = height;
        this.dpi = dpi;
        this.cb = cb;
    }

    void start() {
        worker = new Thread(this::run, "scrcpy-session");
        worker.start();
    }

    void stop() {
        running = false;
        AdbConnection a = adb;
        if (a != null) {
            a.close(); // unblocks every pending read
        }
        controlExec.shutdownNow();
        if (worker != null) {
            worker.interrupt();
        }
    }

    // ------------------------------------------------------------------ main

    private void run() {
        try {
            cb.onStatus("جاري الاتصال بالموبايل…");
            AdbKey key = AdbKey.loadOrCreate(ctx.getFilesDir());
            adb = AdbConnection.open(usb, device, key,
                    () -> cb.onStatus("على الموبايل: اقبل طلب USB debugging واختار Always allow"), 20_000);
            if (!running) {
                return;
            }
            cb.onLog("ADB: " + adb.getDeviceBanner());

            cb.onStatus("جاري رفع السيرفر…");
            adb.push(readAsset("scrcpy-server.jar"), SERVER_PATH, 0100644);

            loadAppsAsync();

            int scid = new SecureRandom().nextInt() & 0x7FFFFFFF;
            String cmd = "CLASSPATH=" + SERVER_PATH + " app_process / com.genymobile.scrcpy.Server " + SERVER_VERSION
                    + String.format(Locale.US, " scid=%08x", scid)
                    + " log_level=info audio=false video_codec=h264 max_fps=30 video_bit_rate=8000000"
                    + " tunnel_forward=true control=true cleanup=false clipboard_autosync=false"
                    + " keep_active=true display_ime_policy=local"
                    + String.format(Locale.US, " new_display=%dx%d/%d", width, height, dpi);
            cb.onLog("$ " + cmd);
            server = adb.openStream("shell:" + cmd, 10_000);
            pumpLines(server.inputStream(), "server-log");

            cb.onStatus("جاري تشغيل الشاشة الافتراضية…");
            String socket = String.format(Locale.US, "localabstract:scrcpy_%08x", scid);
            video = openWithRetry(socket);
            byte[] dummy = new byte[1];
            video.readFully(dummy, 1);
            control = openWithRetry(socket);
            drainInBackground(control);

            byte[] deviceName = new byte[64];
            video.readFully(deviceName, 64);
            cb.onLog("Device: " + new String(deviceName, StandardCharsets.UTF_8).trim());
            byte[] codec = new byte[4];
            video.readFully(codec, 4);
            int codecId = ByteBuffer.wrap(codec).getInt();
            if (codecId != CODEC_H264) {
                throw new IOException(codecId == 0 || codecId == 1
                        ? "السيرفر وقف الفيديو — افتح السجل"
                        : String.format(Locale.US, "codec غير متوقع 0x%08x", codecId));
            }
            decodeLoop();
            if (running) {
                cb.onEnded("الموبايل قفل الاتصال");
            }
        } catch (Exception e) {
            if (running) {
                String m = e.getMessage();
                cb.onEnded(m == null ? e.getClass().getSimpleName() : m);
            }
        } finally {
            running = false;
            cleanup();
        }
    }

    private void decodeLoop() throws IOException {
        byte[] hdr = new byte[12];
        while (running) {
            video.readFully(hdr, 12);
            ByteBuffer h = ByteBuffer.wrap(hdr);
            if ((hdr[0] & 0x80) != 0) {
                // session packet: flags(4) width(4) height(4)
                h.getInt();
                int w = h.getInt();
                int hh = h.getInt();
                if (decoder == null || w != videoW || hh != videoH) {
                    if (decoder != null) {
                        decoder.release();
                    }
                    videoW = w;
                    videoH = hh;
                    decoder = new VideoDecoder(surface, w, hh);
                    cb.onLog("Video session " + w + "x" + hh);
                    cb.onStarted(w, hh);
                }
                continue;
            }
            long ptsFlags = h.getLong();
            int size = h.getInt();
            if (size < 0 || size > 32 * 1024 * 1024) {
                throw new IOException("bad video packet size " + size);
            }
            byte[] pkt = new byte[size];
            video.readFully(pkt, size);
            if (decoder == null) {
                continue;
            }
            boolean config = (ptsFlags & (1L << 62)) != 0;
            long pts = config ? 0 : (ptsFlags & ((1L << 61) - 1));
            if (!decoder.feed(pkt, pts, config)) {
                cb.onLog("decoder skipped a packet of " + size + " bytes");
            }
        }
    }

    private AdbStream openWithRetry(String socket) throws IOException, InterruptedException {
        IOException last = null;
        for (int i = 0; i < 100 && running; i++) {
            try {
                return adb.openStream(socket, 2000);
            } catch (IOException e) {
                last = e;
                Thread.sleep(100);
            }
        }
        throw new IOException("السيرفر ما فتحش — افتح السجل" + (last != null ? " (" + last.getMessage() + ")" : ""));
    }

    private void loadAppsAsync() {
        Thread t = new Thread(() -> {
            try {
                String out = adb.shell("CLASSPATH=" + SERVER_PATH + " app_process / com.genymobile.scrcpy.Server "
                        + SERVER_VERSION + " list_apps=true cleanup=false log_level=info", 10_000);
                List<AppEntry> apps = AppEntry.parse(out);
                cb.onLog("Apps found: " + apps.size());
                if (apps.isEmpty()) {
                    cb.onLog(out);
                }
                cb.onApps(apps);
            } catch (IOException e) {
                cb.onLog("App list failed: " + e.getMessage());
            }
        }, "list-apps");
        t.setDaemon(true);
        t.start();
    }

    private void pumpLines(InputStream in, String name) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    cb.onLog(line);
                }
            } catch (IOException ignored) {
                // stream ended
            }
        }, name);
        t.setDaemon(true);
        t.start();
    }

    private void drainInBackground(AdbStream s) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[4096];
            try {
                while (s.read(buf, 0, buf.length) >= 0) {
                    // device messages (clipboard, acks) are not used
                }
            } catch (IOException ignored) {
                // closed
            }
        }, "control-in");
        t.setDaemon(true);
        t.start();
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

    private void cleanup() {
        if (decoder != null) {
            decoder.release();
            decoder = null;
        }
        AdbConnection a = adb;
        if (a != null) {
            a.close();
        }
        controlExec.shutdownNow();
    }

    // ------------------------------------------------------------------ control

    int getVideoWidth() {
        return videoW;
    }

    int getVideoHeight() {
        return videoH;
    }

    /** action: 0 down, 1 up, 2 move (Android MotionEvent values). */
    void touch(int action, long pointerId, int x, int y) {
        int w = videoW;
        int hh = videoH;
        if (w == 0 || hh == 0) {
            return;
        }
        x = Math.max(0, Math.min(w - 1, x));
        y = Math.max(0, Math.min(hh - 1, y));
        ByteBuffer b = ByteBuffer.allocate(32);
        b.put((byte) 2);                      // TYPE_INJECT_TOUCH_EVENT
        b.put((byte) action);
        b.putLong(pointerId);
        b.putInt(x).putInt(y).putShort((short) w).putShort((short) hh);
        b.putShort(action == 1 ? (short) 0 : (short) 0xFFFF); // pressure u16 fixed point
        b.putInt(0);                          // action button
        b.putInt(0);                          // buttons
        sendControl(b.array());
    }

    void key(int keycode) {
        for (int action = 0; action <= 1; action++) {
            ByteBuffer b = ByteBuffer.allocate(14);
            b.put((byte) 0);                  // TYPE_INJECT_KEYCODE
            b.put((byte) action);
            b.putInt(keycode).putInt(0).putInt(0);
            sendControl(b.array());
        }
    }

    void startApp(String packageName) {
        byte[] name = packageName.getBytes(StandardCharsets.UTF_8);
        if (name.length > 255) {
            return;
        }
        ByteBuffer b = ByteBuffer.allocate(2 + name.length);
        b.put((byte) 16);                     // TYPE_START_APP
        b.put((byte) name.length);
        b.put(name);
        sendControl(b.array());
    }

    private void sendControl(byte[] msg) {
        if (!running) {
            return;
        }
        try {
            controlExec.execute(() -> {
                AdbStream c = control;
                if (c == null) {
                    return;
                }
                try {
                    c.write(msg);
                } catch (IOException ignored) {
                    // session is ending
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // stopped
        }
    }
}
