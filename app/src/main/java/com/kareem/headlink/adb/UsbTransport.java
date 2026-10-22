package com.kareem.headlink.adb;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;

import java.io.IOException;

/** ADB over USB bulk endpoints (head unit = USB host). */
final class UsbTransport implements AdbTransport {

    private static final int USB_CHUNK = 16384;

    private final UsbDeviceConnection conn;
    private final UsbInterface intf;
    private final UsbEndpoint epIn;
    private final UsbEndpoint epOut;
    private volatile boolean closed;

    UsbTransport(UsbDeviceConnection conn, UsbInterface intf) throws IOException {
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

    @Override
    public boolean readExact(byte[] dst, int len, long deadline) throws IOException {
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
            } else if (n < 0 && deadline == 0) {
                throw new IOException("USB disconnected");
            }
            // n == 0: zero-length packet; n < 0 with a deadline: time slice elapsed
        }
        return true;
    }

    @Override
    public void writeMessage(byte[] header, byte[] payload, int payloadLen) throws IOException {
        write(header, header.length);
        if (payloadLen > 0) {
            write(payload, payloadLen);
            int mps = epOut.getMaxPacketSize();
            if (mps > 0 && payloadLen % mps == 0) {
                conn.bulkTransfer(epOut, new byte[0], 0, 0, 1000); // zero-length packet
            }
        }
    }

    private void write(byte[] buf, int len) throws IOException {
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

    @Override
    public void close() {
        closed = true;
        try {
            conn.releaseInterface(intf);
        } catch (RuntimeException ignored) {
            // device gone
        }
        conn.close();
    }
}
