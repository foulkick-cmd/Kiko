package com.kareem.headlink;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.audiofx.AudioEffect;

import com.kareem.headlink.adb.AdbStream;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Plays scrcpy's raw audio stream (PCM 16-bit, 48 kHz, stereo) on the head unit speakers.
 * Stream layout: codec id (4 bytes), then packets of [pts+flags (8) | size (4) | data].
 */
final class AudioPlayer {

    private static final int CODEC_RAW = 0x00726177; // "raw"
    private static final int SAMPLE_RATE = 48000;

    interface Log {
        void log(String line);
    }

    private final Context ctx;
    private final AdbStream stream;
    private final Log log;
    private volatile float gain;
    private AudioFocusRequest focusRequest;
    private final Thread thread;
    private volatile boolean running = true;
    private AudioTrack track;

    AudioPlayer(Context ctx, AdbStream stream, float gain, Log log) {
        this.ctx = ctx.getApplicationContext();
        this.gain = gain;
        this.stream = stream;
        this.log = log;
        thread = new Thread(this::run, "audio");
        thread.setDaemon(true);
    }

    void start() {
        thread.start();
    }

    /** Software volume boost applied to every sample (1.0 = unchanged), clipped to 16-bit range. */
    void setGain(float g) {
        gain = g;
    }

    void stop() {
        running = false;
        thread.interrupt();
    }

    private void applyGain(byte[] b, int n) {
        float g = gain;
        if (g == 1f) {
            return;
        }
        for (int i = 0; i + 1 < n; i += 2) {
            int s = (short) ((b[i] & 0xFF) | (b[i + 1] << 8));
            int v = Math.round(s * g);
            if (v > Short.MAX_VALUE) {
                v = Short.MAX_VALUE;
            } else if (v < Short.MIN_VALUE) {
                v = Short.MIN_VALUE;
            }
            b[i] = (byte) v;
            b[i + 1] = (byte) (v >> 8);
        }
    }

    /**
     * Behave like a normal music player so the head unit treats this as its media source:
     * take audio focus, and announce the audio session so system equalizer apps attach to it.
     */
    private void requestFocusAndEffects() {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setOnAudioFocusChangeListener(c -> { })
                    .build();
            int r = am.requestAudioFocus(focusRequest);
            log.log("audio: focus " + (r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED ? "granted" : "not granted (" + r + ")"));
        }
        Intent i = new Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
        i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, track.getAudioSessionId());
        i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, ctx.getPackageName());
        i.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC);
        ctx.sendBroadcast(i);
    }

    private void releaseFocusAndEffects() {
        if (track != null) {
            Intent i = new Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
            i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, track.getAudioSessionId());
            i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, ctx.getPackageName());
            ctx.sendBroadcast(i);
        }
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am != null && focusRequest != null) {
            am.abandonAudioFocusRequest(focusRequest);
        }
    }

    private void run() {
        try {
            byte[] codec = new byte[4];
            stream.readFully(codec, 4);
            int id = ByteBuffer.wrap(codec).getInt();
            if (id == 0) {
                log.log("audio: phone could not capture audio (disabled)");
                return;
            }
            if (id == 1) {
                log.log("audio: configuration error on the phone");
                return;
            }
            if (id != CODEC_RAW) {
                log.log(String.format("audio: unexpected codec 0x%08x", id));
                return;
            }

            int minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build())
                    .setBufferSizeInBytes(Math.max(minBuf * 4, 48000)) // ~250 ms of headroom
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            requestFocusAndEffects();
            track.play();
            log.log("audio: playing (raw PCM 48 kHz stereo), session " + track.getAudioSessionId()
                    + ", boost x" + gain);

            byte[] hdr = new byte[12];
            byte[] buf = new byte[0];
            while (running) {
                stream.readFully(hdr, 12);
                ByteBuffer h = ByteBuffer.wrap(hdr);
                long ptsFlags = h.getLong();
                int size = h.getInt();
                if (size < 0 || size > 1024 * 1024) {
                    throw new IOException("bad audio packet size " + size);
                }
                if (buf.length < size) {
                    buf = new byte[size];
                }
                stream.readFully(buf, size);
                boolean config = (ptsFlags & (1L << 62)) != 0;
                if (!config && size > 0) {
                    int n = size & ~3; // whole stereo frames only
                    applyGain(buf, n);
                    track.write(buf, 0, n);
                }
            }
        } catch (IOException e) {
            if (running) {
                log.log("audio stopped: " + e.getMessage());
            }
        } catch (RuntimeException e) {
            log.log("audio error: " + e);
        } finally {
            releaseFocusAndEffects();
            if (track != null) {
                try {
                    track.stop();
                } catch (IllegalStateException ignored) {
                    // not playing
                }
                track.release();
            }
        }
    }
}
