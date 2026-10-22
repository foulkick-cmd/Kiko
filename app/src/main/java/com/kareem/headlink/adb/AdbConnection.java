package com.kareem.headlink.adb;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal ADB host over USB (the head unit is the USB host, the phone runs adbd).
 * Implements CNXN/AUTH handshake, stream multiplexing, shell and sync push.
 */
public final class AdbConnection implements Closeable {

    public interface AuthListener {
        void onWaitingForApproval();
    }

    static final int A_CNXN = 0x4e584e43;
    static final int A_AUTH = 0x48545541;
    static final int A_OPEN = 0x4e45504f;
    static final int A_OKAY = 0x59414b4f;
    static final int A_CLSE = 0x45534c43;
    static final int A_WRTE = 0x45545257;
    static final int A_STLS = 0x534c5453;

    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_RSAPUBLICKEY = 3;

    private static final int VERSION = 0x01000001;
    private static final int MAX_DATA = 256 * 1024;
    private static final int USB_CHUNK = 16384;

    private final UsbDeviceConnection conn;
    private final UsbInterface intf;
    private final UsbEndpoint epIn;
    private final UsbEndpoint epOut;
    private final Object writeLock = new Object();
    private final Map<Integer, AdbStream> streams = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private volatile boolean closed;
    private volatile int maxPayload = 4096;
    private volatile String deviceBanner = "";

    private static final class Message {
        int cmd;
        int arg0;
        int arg1;
        byte[] data;
    }

    public static UsbInterface findAdbInterface(UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface f = device.getInterface(i);
            if (f.getInterfaceClass() == 255 && f.getInterfaceSubclass() == 66
                    && f.getInterfaceProtocol() == 1 && f.getEndpointCount() == 2) {
                return f;
            }
        }
        return null;
    }

    private AdbConnection(UsbDeviceConnection conn, UsbInterface intf) throws IOException {
        this.conn = conn;
        this.intf = intf;
        UsbEndpoint in = null;
        UsbEndpoint out = null;
        for (int i = 0; i < intf.getEndpointCount(); i++) {
            UsbEndpoint e = intf.getEndpoint(i);
            if (e.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) {
                continue;
            }
            if (e.getDirection() == UsbConstants.USB_DIR_IN) {
                in = e;
            } else {
                out = e;
            }
        }
        if (in == null || out == null) {
            throw new IOException("ADB bulk endpoints not found");
        }
        epIn = in;
        epOut = out;
    }

    public static AdbConnection open(UsbManager manager, UsbDevice device, AdbKey key,
                                     AuthListener listener, long timeoutMs) throws IOException {
        UsbInterface intf = findAdbInterface(device);
        if (intf == null) {
            throw new IOException("الموبايل مش ظاهر كـ ADB — فعّل USB debugging");
        }
        UsbDeviceConnection c = manager.openDevice(device);
        if (c == null) {
            throw new IOException("مش قادر يفتح الـ USB (مفيش إذن؟)");
        }
        if (!c.claimInterface(intf, true)) {
            c.close();
            throw new IOException("الـ USB محجوز من تطبيق تاني (ZLink؟)");
        }
        AdbConnection adb = new AdbConnection(c, intf);
        try {
            adb.handshake(key, listener, timeoutMs);
        } catch (IOException e) {
            adb.close();
            throw e;
        }
        adb.startReader();
        return adb;
    }

    public String getDeviceBanner() {
        return deviceBanner;
    }

    int getMaxPayload() {
        return maxPayload;
    }

    private void handshake(AdbKey key, AuthListener listener, long timeoutMs) throws IOException {
        send(A_CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.UTF_8));
        long deadline = System.currentTimeMillis() + timeoutMs;
        boolean sentSignature = false;
        boolean sentPublicKey = false;
        while (true) {
            Message m = readMessage(deadline);
            if (m == null) {
                throw new IOException(sentPublicKey
                        ? "ما اتقبلش طلب USB debugging على الموبايل"
                        : "الموبايل ما ردش على ADB");
            }
            if (m.cmd == A_CNXN) {
                maxPayload = Math.max(1024, Math.min(m.arg1, MAX_DATA));
                deviceBanner = new String(m.data, StandardCharsets.UTF_8);
                return;
            }
            if (m.cmd == A_STLS) {
                throw new IOException("الموبايل طالب TLS — مش مدعوم على USB");
            }
            if (m.cmd == A_AUTH && m.arg0 == AUTH_TOKEN) {
                if (!sentSignature) {
                    send(A_AUTH, AUTH_SIGNATURE, 0, key.sign(m.data));
                    sentSignature = true;
                } else if (!sentPublicKey) {
                    send(A_AUTH, AUTH_RSAPUBLICKEY, 0, key.publicKeyPayload());
                    sentPublicKey = true;
                    // give the user time to tap "Allow" on the phone
                    deadline = System.currentTimeMillis() + 120_000;
                    if (listener != null) {
                        listener.onWaitingForApproval();
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- reader

    private void startReader() {
        Thread t = new Thread(() -> {
            try {
                while (!closed) {
                    Message m = readMessage(0);
                    if (m == null) {
                        break;
                    }
                    AdbStream s = streams.get(m.arg1);
                    switch (m.cmd) {
                        case A_OKAY:
                            if (s != null) {
                                s.onOkay(m.arg0);
                            }
                            break;
                        case A_WRTE:
                            if (s != null) {
                                s.onData(m.data);
                                send(A_OKAY, m.arg1, m.arg0, null);
                            } else {
                                send(A_CLSE, 0, m.arg0, null);
                            }
                            break;
                        case A_CLSE:
                            if (s != null) {
                                streams.remove(m.arg1);
                                s.onRemoteClose();
                            }
                            break;
                        default:
                            break;
                    }
                }
            } catch (IOException ignored) {
                // link lost
            } finally {
                closed = true;
                for (AdbStream s : new ArrayList<>(streams.values())) {
                    s.onRemoteClose();
                }
                streams.clear();
            }
        }, "adb-reader");
        t.setDaemon(true);
        t.start();
    }

    /** deadline 0 = block forever (used by the reader thread). */
    private Message readMessage(long deadline) throws IOException {
        byte[] h = new byte[24];
        if (!readExact(h, 24, deadline)) {
            return null;
        }
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Message m = new Message();
        m.cmd = b.getInt();
        m.arg0 = b.getInt();
        m.arg1 = b.getInt();
        int len = b.getInt();
        b.getInt(); // checksum, not verified (ADB >= 1.0.32 sends 0)
        int magic = b.getInt();
        if (magic != ~m.cmd) {
            throw new IOException("bad ADB header");
        }
        if (len < 0 || len > 1024 * 1024 + 64) {
            throw new IOException("bad ADB payload length " + len);
        }
        m.data = new byte[len];
        if (len > 0) {
            long payloadDeadline = deadline == 0 ? 0 : Math.max(deadline, System.currentTimeMillis() + 5000);
            if (!readExact(m.data, len, payloadDeadline)) {
                throw new IOException("short ADB payload");
            }
        }
        return m;
    }

    private boolean readExact(byte[] dst, int len, long deadline) throws IOException {
        int off = 0;
        while (off < len) {
            if (closed) {
                throw new IOException("closed");
            }
            int want = Math.min(len - off, USB_CHUNK);
            int timeout = 0;
            if (deadline != 0) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    if (off == 0) {
                        return false;
                    }
                    throw new IOException("USB read timeout");
                }
                timeout = (int) Math.min(1000, left);
            }
            int n = conn.bulkTransfer(epIn, dst, off, want, timeout);
            if (n > 0) {
                off += n;
            } else if (n < 0) {
                if (deadline == 0) {
                    throw new IOException("USB disconnected");
                }
                // timeout slice elapsed; loop re-checks the deadline
            }
            // n == 0: zero-length packet, keep reading
        }
        return true;
    }

    // ---------------------------------------------------------------- writer

    void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int len = data == null ? 0 : data.length;
        int sum = 0;
        for (int i = 0; i < len; i++) {
            sum += data[i] & 0xFF;
        }
        ByteBuffer b = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(cmd).putInt(arg0).putInt(arg1).putInt(len).putInt(sum).putInt(~cmd);
        synchronized (writeLock) {
            writeUsb(b.array(), 24);
            if (len > 0) {
                writeUsb(data, len);
                int mps = epOut.getMaxPacketSize();
                if (mps > 0 && len % mps == 0) {
                    conn.bulkTransfer(epOut, new byte[0], 0, 0, 1000); // zero-length packet
                }
            }
        }
    }

    private void writeUsb(byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            if (closed) {
                throw new IOException("closed");
            }
            int n = conn.bulkTransfer(epOut, buf, off, Math.min(USB_CHUNK, len - off), 5000);
            if (n < 0) {
                throw new IOException("USB write failed");
            }
            off += n;
        }
    }

    // ---------------------------------------------------------------- services

    public AdbStream openStream(String destination, long timeoutMs) throws IOException {
        if (closed) {
            throw new IOException("ADB closed");
        }
        int id = nextId.getAndIncrement();
        AdbStream s = new AdbStream(this, id);
        streams.put(id, s);
        send(A_OPEN, id, 0, (destination + "\0").getBytes(StandardCharsets.UTF_8));
        if (!s.awaitOpen(timeoutMs)) {
            streams.remove(id);
            throw new IOException("open failed: " + destination);
        }
        return s;
    }

    void removeStream(int localId) {
        streams.remove(localId);
    }

    public String shell(String command, long timeoutMs) throws IOException {
        AdbStream s = openStream("shell:" + command, timeoutMs);
        try {
            return new String(s.readAll(), StandardCharsets.UTF_8);
        } finally {
            s.close();
        }
    }

    /** adb push using the sync protocol (v1). */
    public void push(byte[] content, String remotePath, int mode) throws IOException {
        AdbStream s = openStream("sync:", 10_000);
        try {
            byte[] pathMode = (remotePath + "," + mode).getBytes(StandardCharsets.UTF_8);
            s.write(syncPacket("SEND", pathMode.length, pathMode, 0, pathMode.length));
            final int chunk = 64 * 1024;
            for (int off = 0; off < content.length; off += chunk) {
                int n = Math.min(chunk, content.length - off);
                s.write(syncPacket("DATA", n, content, off, n));
            }
            s.write(syncPacket("DONE", (int) (System.currentTimeMillis() / 1000), null, 0, 0));
            byte[] resp = new byte[8];
            s.readFully(resp, 8);
            String id = new String(resp, 0, 4, StandardCharsets.US_ASCII);
            if (!"OKAY".equals(id)) {
                int len = ByteBuffer.wrap(resp, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                String msg = "";
                if (len > 0 && len < 4096) {
                    byte[] m = new byte[len];
                    s.readFully(m, len);
                    msg = new String(m, StandardCharsets.UTF_8);
                }
                throw new IOException("push failed: " + id + " " + msg);
            }
            s.write(syncPacket("QUIT", 0, null, 0, 0));
        } finally {
            s.close();
        }
    }

    private static byte[] syncPacket(String id, int arg, byte[] data, int off, int len) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8 + len);
        out.write(id.getBytes(StandardCharsets.US_ASCII), 0, 4);
        byte[] a = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(arg).array();
        out.write(a, 0, 4);
        if (data != null && len > 0) {
            out.write(data, off, len);
        }
        return out.toByteArray();
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        closed = true;
        for (AdbStream s : new ArrayList<>(streams.values())) {
            s.onRemoteClose();
        }
        streams.clear();
        try {
            conn.releaseInterface(intf);
        } catch (RuntimeException ignored) {
            // device gone
        }
        conn.close();
    }
}
