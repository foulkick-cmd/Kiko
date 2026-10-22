package com.kareem.headlink;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;

/** H.264 decoder rendering straight to the SurfaceView. */
final class VideoDecoder {

    private final MediaCodec codec;
    private final Thread outThread;
    private volatile boolean running = true;

    VideoDecoder(Surface surface, int width, int height) throws IOException {
        codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        f.setInteger("low-latency", 1);
        codec.configure(f, surface, null, 0);
        codec.start();
        outThread = new Thread(this::drain, "decoder-out");
        outThread.start();
    }

    /** Returns false if the packet did not fit the decoder input buffer. */
    boolean feed(byte[] data, long ptsUs, boolean config) {
        while (running) {
            int idx;
            try {
                idx = codec.dequeueInputBuffer(100_000);
            } catch (IllegalStateException e) {
                return false;
            }
            if (idx < 0) {
                continue;
            }
            ByteBuffer b = codec.getInputBuffer(idx);
            if (b == null || b.capacity() < data.length) {
                codec.queueInputBuffer(idx, 0, 0, ptsUs, 0);
                return false;
            }
            b.clear();
            b.put(data);
            codec.queueInputBuffer(idx, 0, data.length, ptsUs, config ? MediaCodec.BUFFER_FLAG_CODEC_CONFIG : 0);
            return true;
        }
        return false;
    }

    private void drain() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (running) {
            try {
                int idx = codec.dequeueOutputBuffer(info, 50_000);
                if (idx >= 0) {
                    codec.releaseOutputBuffer(idx, true);
                }
            } catch (IllegalStateException e) {
                break;
            }
        }
    }

    void release() {
        running = false;
        try {
            outThread.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            codec.stop();
        } catch (RuntimeException ignored) {
            // already stopped
        }
        codec.release();
    }
}
