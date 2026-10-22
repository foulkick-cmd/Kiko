package com.kareem.headlink.adb;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;

/** ADB over TCP ("adb tcpip" mode on the phone, classic port 5555). */
final class TcpTransport implements AdbTransport {

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    TcpTransport(String host, int port, int connectTimeoutMs) throws IOException {
        socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
        in = socket.getInputStream();
        out = socket.getOutputStream();
    }

    @Override
    public boolean readExact(byte[] dst, int len, long deadline) throws IOException {
        int off = 0;
        while (off < len) {
            int timeout = 0;
            if (deadline != 0) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    if (off == 0) {
                        return false;
                    }
                    throw new IOException("network read timeout");
                }
                timeout = (int) Math.min(1000, left);
            }
            socket.setSoTimeout(timeout);
            int n;
            try {
                n = in.read(dst, off, len - off);
            } catch (SocketTimeoutException e) {
                continue; // loop re-checks the deadline
            }
            if (n < 0) {
                throw new IOException("network connection closed");
            }
            off += n;
        }
        return true;
    }

    @Override
    public void writeMessage(byte[] header, byte[] payload, int payloadLen) throws IOException {
        out.write(header);
        if (payloadLen > 0) {
            out.write(payload, 0, payloadLen);
        }
        out.flush();
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // already closed
        }
    }
}
