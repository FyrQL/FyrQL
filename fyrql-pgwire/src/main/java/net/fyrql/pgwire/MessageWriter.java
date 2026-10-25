/*
 * Copyright 2026 the FyrQL authors
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * This Source Code Form is "Incompatible With Secondary Licenses", as
 * defined by the Mozilla Public License, v. 2.0.
 */

package net.fyrql.pgwire;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.Arrays;

/**
 * Writes the messages of the server into a buffer, which goes to the
 * client on {@link #flush()} and whenever it holds more than 64 KiB after
 * a message.
 */
final class MessageWriter {

    private static final int FLUSH_AT = 64 * 1024;

    private final OutputStream out;
    private byte[] buffer = new byte[FLUSH_AT + 1024];
    private int size;
    /** Where the length of the message being written starts. */
    private int start = -1;
    private Charset charset;

    MessageWriter(OutputStream out, Charset charset) {
        this.out = out;
        this.charset = charset;
    }

    Charset charset() {
        return charset;
    }

    /** Sets the encoding of the strings that follow, as {@code client_encoding} says. */
    void charset(Charset newCharset) {
        this.charset = newCharset;
    }

    private void ensure(int n) {
        if (size + n > buffer.length) {
            buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, size + n));
        }
    }

    /** Starts a message of a type. */
    MessageWriter begin(char type) {
        if (start >= 0) {
            throw new IllegalStateException("A message is open");
        }
        ensure(5);
        buffer[size++] = (byte) type;
        start = size;
        size += 4;
        return this;
    }

    MessageWriter int8(int v) {
        ensure(1);
        buffer[size++] = (byte) v;
        return this;
    }

    MessageWriter int16(int v) {
        ensure(2);
        buffer[size++] = (byte) (v >>> 8);
        buffer[size++] = (byte) v;
        return this;
    }

    MessageWriter int32(int v) {
        ensure(4);
        buffer[size++] = (byte) (v >>> 24);
        buffer[size++] = (byte) (v >>> 16);
        buffer[size++] = (byte) (v >>> 8);
        buffer[size++] = (byte) v;
        return this;
    }

    MessageWriter bytes(byte[] b) {
        ensure(b.length);
        System.arraycopy(b, 0, buffer, size, b.length);
        size += b.length;
        return this;
    }

    /** Writes a string and a zero byte after it. */
    MessageWriter string(String s) {
        bytes(s.getBytes(charset));
        return int8(0);
    }

    /** Ends the message, and sends the buffer if it is full. */
    void end() throws IOException {
        int length = size - start;
        buffer[start] = (byte) (length >>> 24);
        buffer[start + 1] = (byte) (length >>> 16);
        buffer[start + 2] = (byte) (length >>> 8);
        buffer[start + 3] = (byte) length;
        start = -1;
        if (size >= FLUSH_AT) {
            flush();
        }
    }

    /** Writes one byte outside of a message, as the answer to {@code SSLRequest}, and sends it. */
    void raw(char b) throws IOException {
        ensure(1);
        buffer[size++] = (byte) b;
        flush();
    }

    /** Sends what the buffer holds. */
    void flush() throws IOException {
        if (start >= 0) {
            throw new IllegalStateException("A message is open");
        }
        if (size > 0) {
            out.write(buffer, 0, size);
            size = 0;
        }
        out.flush();
        if (buffer.length > 4 * FLUSH_AT) {
            buffer = new byte[FLUSH_AT + 1024];
        }
    }

    /** Drops a message that was begun but not ended, after an error while it was written. */
    void abandon() {
        if (start >= 0) {
            size = start - 1;
            start = -1;
        }
    }
}
