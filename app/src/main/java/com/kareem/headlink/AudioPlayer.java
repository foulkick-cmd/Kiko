package com.kareem.headlink;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

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

    private final AdbStream stream;
    private final Log log;
    private final Thread thread;
    private volatile boolean running = true;
    private AudioTrack track;

    AudioPlayer(AdbStream stream, Log log) {
        this.stream = stream;
        this.log = log;
        thread = new Thread(this::run, "audio");
        thread.setDaemon(true);
    }

    void start() {
        thread.start();
    }

    void stop() {
        running = false;
        thread.interrupt();
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
            track.play();
            log.log("audio: playing (raw PCM 48 kHz stereo)");

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
                    track.write(buf, 0, size & ~3); // whole stereo frames only
                }
            }
        } catch (IOException e) {
            if (running) {
                log.log("audio stopped: " + e.getMessage());
            }
        } catch (RuntimeException e) {
            log.log("audio error: " + e);
        } finally {
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
