package com.kareem.headlink;

import android.view.Surface;

import com.kareem.headlink.adb.AdbConnection;
import com.kareem.headlink.adb.AdbStream;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * One scrcpy-server instance on the phone with its own resizable ("flex") virtual display,
 * decoded into one pane of the head unit. Several sessions can share one ADB link.
 */
final class ScrcpySession {

    private static final int CODEC_H264 = 0x68323634; // "h264"

    interface Callback {
        void onLog(String line);

        void onStarted(int width, int height);

        void onEnded(String reason);
    }

    private final AdbConnection adb;
    private Surface surface; // guarded by decoderLock; null while the pane is hidden
    private final int width;
    private final int height;
    private final int dpi;
    private final int bitRate;
    private final boolean withAudio;
    private final String tag;
    private final Callback cb;

    private volatile boolean running = true;
    private volatile AdbStream control;
    private volatile AdbStream video;
    private volatile AdbStream audio;
    private AudioPlayer audioPlayer;
    private volatile AdbStream server;
    private VideoDecoder decoder; // guarded by decoderLock
    private final Object decoderLock = new Object();
    private volatile int videoW;
    private volatile int videoH;
    private Thread worker;
    private final ExecutorService controlExec = Executors.newSingleThreadExecutor();

    ScrcpySession(AdbConnection adb, Surface surface, int width, int height, int dpi, int bitRate,
                  boolean withAudio, String tag, Callback cb) {
        this.adb = adb;
        this.withAudio = withAudio;
        this.bitRate = bitRate;
        this.surface = surface;
        this.width = width;
        this.height = height;
        this.dpi = dpi;
        this.tag = tag;
        this.cb = cb;
    }

    void start() {
        worker = new Thread(this::run, "scrcpy-" + tag);
        worker.start();
    }

    /** Non-blocking stop; the phone-side server exits when its sockets close. */
    void stop() {
        running = false;
        controlExec.shutdownNow();
        if (worker != null) {
            worker.interrupt();
        }
        new Thread(this::closeStreams, "stop-" + tag).start();
    }

    private void closeStreams() {
        AdbStream v = video;
        AdbStream a = audio;
        AdbStream c = control;
        AdbStream s = server;
        if (audioPlayer != null) {
            audioPlayer.stop();
        }
        if (v != null) {
            v.close();
        }
        if (a != null) {
            a.close();
        }
        if (c != null) {
            c.close();
        }
        if (s != null) {
            s.close();
        }
    }

    private void log(String line) {
        cb.onLog("[" + tag + "] " + line);
    }

    // ------------------------------------------------------------------ main

    private void run() {
        try {
            int scid = new SecureRandom().nextInt() & 0x7FFFFFFF;
            // each session runs its own copy: cleanup=true deletes the jar it started from,
            // and restores the phone screen power when the session ends
            String jar = "/data/local/tmp/headlink-" + tag + ".jar";
            String cp = adb.shell("cp " + PhoneLink.SERVER_PATH + " " + jar + " && echo OK", 10_000);
            if (!cp.contains("OK")) {
                throw new IOException("could not copy server: " + cp.trim());
            }
            String cmd = "CLASSPATH=" + jar + " app_process / com.genymobile.scrcpy.Server "
                    + PhoneLink.SERVER_VERSION
                    + String.format(Locale.US, " scid=%08x", scid)
                    + " log_level=info video_codec=h264 max_fps=30 video_bit_rate=" + bitRate
                    + (withAudio ? " audio=true audio_codec=raw" : " audio=false")
                    + " tunnel_forward=true control=true cleanup=true clipboard_autosync=false"
                    + " keep_active=true display_ime_policy=local vd_system_decorations=false flex_display=true"
                    + String.format(Locale.US, " new_display=%dx%d/%d", width, height, dpi);
            log("start " + width + "x" + height + " @" + dpi + "dpi");
            server = adb.openStream("shell:" + cmd, 10_000);
            pumpLines(server.inputStream());

            String socket = String.format(Locale.US, "localabstract:scrcpy_%08x", scid);
            video = openWithRetry(socket);
            byte[] dummy = new byte[1];
            video.readFully(dummy, 1);
            if (withAudio) {
                // server accepts sockets in this order: video, audio, control
                audio = openWithRetry(socket);
            }
            control = openWithRetry(socket);
            drainInBackground(control);

            byte[] deviceName = new byte[64];
            video.readFully(deviceName, 64);
            if (audio != null) {
                audioPlayer = new AudioPlayer(audio, this::log);
                audioPlayer.start();
            }
            byte[] codec = new byte[4];
            video.readFully(codec, 4);
            int codecId = ByteBuffer.wrap(codec).getInt();
            if (codecId != CODEC_H264) {
                throw new IOException(codecId == 0 || codecId == 1
                        ? "Server disabled the video - open Log"
                        : String.format(Locale.US, "Unexpected codec 0x%08x", codecId));
            }
            decodeLoop();
            if (running) {
                cb.onEnded("Phone closed the connection");
            }
        } catch (Exception e) {
            if (running) {
                String m = e.getMessage();
                cb.onEnded(m == null ? e.getClass().getSimpleName() : m);
            }
        } finally {
            running = false;
            synchronized (decoderLock) {
                if (decoder != null) {
                    decoder.release();
                    decoder = null;
                }
            }
            controlExec.shutdownNow();
            closeStreams();
        }
    }

    private void decodeLoop() throws IOException {
        byte[] hdr = new byte[12];
        while (running) {
            video.readFully(hdr, 12);
            ByteBuffer h = ByteBuffer.wrap(hdr);
            if ((hdr[0] & 0x80) != 0) {
                // session packet: flags(4) width(4) height(4) - sent again after every resize
                h.getInt();
                int w = h.getInt();
                int hh = h.getInt();
                boolean created = false;
                synchronized (decoderLock) {
                    if (decoder != null && (w != videoW || hh != videoH)) {
                        decoder.release();
                        decoder = null;
                    }
                    videoW = w;
                    videoH = hh;
                    if (decoder == null && surface != null) {
                        decoder = new VideoDecoder(surface, w, hh);
                        created = true;
                    }
                }
                if (created) {
                    log("video " + w + "x" + hh);
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
            boolean config = (ptsFlags & (1L << 62)) != 0;
            long pts = config ? 0 : (ptsFlags & ((1L << 61) - 1));
            synchronized (decoderLock) {
                // while hidden there is no decoder: packets are dropped, the phone app keeps running
                if (decoder != null && !decoder.feed(pkt, pts, config)) {
                    log("decoder skipped a packet of " + size + " bytes");
                }
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
        throw new IOException("Server did not start - open Log" + (last != null ? " (" + last.getMessage() + ")" : ""));
    }

    private void pumpLines(InputStream in) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    log(line);
                }
            } catch (IOException ignored) {
                // stream ended
            }
        }, "log-" + tag);
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
        }, "ctl-in-" + tag);
        t.setDaemon(true);
        t.start();
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

    /** "+package" force-stops the app first. */
    void startApp(String name) {
        byte[] n = name.getBytes(StandardCharsets.UTF_8);
        if (n.length > 255) {
            return;
        }
        ByteBuffer b = ByteBuffer.allocate(2 + n.length);
        b.put((byte) 16);                     // TYPE_START_APP
        b.put((byte) n.length);
        b.put(n);
        sendControl(b.array());
    }

    /** Pane hidden (HeadLink in background): stop rendering, keep the phone session alive. */
    void detachSurface() {
        synchronized (decoderLock) {
            if (decoder != null) {
                decoder.release();
                decoder = null;
            }
            surface = null;
        }
    }

    /** Pane visible again: render to the new surface; RESET_VIDEO makes the phone send a fresh keyframe. */
    void attachSurface(Surface s) {
        synchronized (decoderLock) {
            if (s == surface && decoder != null) {
                return;
            }
            if (decoder != null) {
                decoder.release();
                decoder = null;
            }
            surface = s;
        }
        sendControl(new byte[]{17}); // TYPE_RESET_VIDEO -> new session packet + config + keyframe
    }

    /** Turns the phone's own panel off/on; the phone stays awake so apps keep running here. */
    void setPhoneScreen(boolean on) {
        sendControl(new byte[]{10, (byte) (on ? 1 : 0)}); // TYPE_SET_DISPLAY_POWER
    }

    /** Resizes the flex virtual display; the server answers with a new video session. */
    void resize(int w, int h) {
        if (w <= 0 || h <= 0 || w > 0xFFFF || h > 0xFFFF) {
            return;
        }
        ByteBuffer b = ByteBuffer.allocate(5);
        b.put((byte) 21);                     // TYPE_RESIZE_DISPLAY
        b.putShort((short) w).putShort((short) h);
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
        } catch (RejectedExecutionException ignored) {
            // stopped
        }
    }
}
