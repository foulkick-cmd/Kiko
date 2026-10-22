package com.kareem.headlink.adb;

import java.io.IOException;

/** Byte pipe carrying ADB messages: USB bulk endpoints or a TCP socket. */
interface AdbTransport {

    /**
     * Reads exactly len bytes into dst. deadline is an absolute System.currentTimeMillis() value,
     * 0 = wait forever. Returns false if the deadline passed before any byte arrived.
     */
    boolean readExact(byte[] dst, int len, long deadline) throws IOException;

    /** Writes one complete ADB message (24-byte header + payload). Callers serialize calls. */
    void writeMessage(byte[] header, byte[] payload, int payloadLen) throws IOException;

    void close();
}
