package com.kareem.headlink.adb;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** One logical ADB stream (shell, sync, localabstract socket...) multiplexed on the USB link. */
public final class AdbStream implements Closeable {

    private static final byte[] EOF_MARK = new byte[0];

    private final AdbConnection adb;
    final int localId;
    private volatile int remoteId;

    private final CountDownLatch openLatch = new CountDownLatch(1);
    private volatile boolean openOk;
    private final Semaphore writeCredits = new Semaphore(0);
    private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
    private volatile boolean closed;

    private byte[] cur;
    private int pos;

    AdbStream(AdbConnection adb, int localId) {
        this.adb = adb;
        this.localId = localId;
    }

    // ---- called from the ADB reader thread ----

    void onOkay(int remote) {
        if (openLatch.getCount() > 0) {
            remoteId = remote;
            openOk = true;
            openLatch.countDown();
        }
        // every OKAY (including the one answering OPEN) allows one more WRTE
        writeCredits.release();
    }

    void onData(byte[] data) {
        if (data.length > 0) {
            queue.offer(data);
        }
    }

    void onRemoteClose() {
        closed = true;
        openLatch.countDown();
        queue.offer(EOF_MARK);
        writeCredits.release(1000);
    }

    boolean awaitOpen(long timeoutMs) {
        try {
            return openLatch.await(timeoutMs, TimeUnit.MILLISECONDS) && openOk && !closed;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ---- reading ----

    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        if (cur == EOF_MARK) {
            return -1;
        }
        if (cur == null || pos >= cur.length) {
            try {
                cur = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("interrupted");
            }
            pos = 0;
            if (cur == EOF_MARK) {
                queue.offer(EOF_MARK);
                return -1;
            }
        }
        int n = Math.min(len, cur.length - pos);
        System.arraycopy(cur, pos, b, off, n);
        pos += n;
        return n;
    }

    public void readFully(byte[] b, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = read(b, off, len - off);
            if (n < 0) {
                throw new EOFException("stream closed by phone");
            }
            off += n;
        }
    }

    public byte[] readAll() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = read(buf, 0, buf.length)) >= 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    public InputStream inputStream() {
        return new InputStream() {
            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                int n = AdbStream.this.read(one, 0, 1);
                return n < 0 ? -1 : (one[0] & 0xFF);
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                return AdbStream.this.read(b, off, len);
            }
        };
    }

    // ---- writing ----

    public void write(byte[] data) throws IOException {
        write(data, 0, data.length);
    }

    public void write(byte[] data, int off, int len) throws IOException {
        int max = adb.getMaxPayload();
        while (len > 0) {
            int n = Math.min(len, max);
            try {
                if (!writeCredits.tryAcquire(15, TimeUnit.SECONDS)) {
                    throw new IOException("ADB write timeout");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("interrupted");
            }
            if (closed) {
                throw new IOException("stream closed");
            }
            adb.send(AdbConnection.A_WRTE, localId, remoteId, Arrays.copyOfRange(data, off, off + n));
            off += n;
            len -= n;
        }
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            try {
                adb.send(AdbConnection.A_CLSE, localId, remoteId, null);
            } catch (IOException ignored) {
                // link already gone
            }
        }
        adb.removeStream(localId);
        queue.offer(EOF_MARK);
        writeCredits.release(1000);
    }
}
