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

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Reads the messages of a client: the startup packet, which has no type,
 * and then messages of a type byte, a length and a body.
 */
final class MessageReader {

    /** The longest message the server reads, so that a client cannot make it allocate without bound. */
    static final int MAX_MESSAGE = 256 * 1024 * 1024;
    /** The longest startup packet; PostgreSQL takes 10000 bytes. */
    static final int MAX_STARTUP = 10_000;

    private final DataInputStream in;

    MessageReader(InputStream in) {
        this.in = new DataInputStream(new BufferedInputStream(in, 16 * 1024));
    }

    /** A message: its type and its body. */
    record Message(char type, Body body) {
    }

    /**
     * Reads the startup packet, or one of the requests in its place, without its length.
     *
     * @throws ProtocolException if the length is out of range
     * @throws EOFException if the client closed the connection
     */
    Body startup() throws IOException {
        int length = in.readInt();
        if (length < 8 || length > MAX_STARTUP) {
            throw new ProtocolException("invalid length of startup packet: " + length);
        }
        byte[] body = new byte[length - 4];
        in.readFully(body);
        return new Body(body);
    }

    /**
     * Reads the next message.
     *
     * @throws ProtocolException if the length is out of range
     * @throws EOFException if the client closed the connection
     */
    Message next() throws IOException {
        int type = in.read();
        if (type < 0) {
            throw new EOFException();
        }
        int length = in.readInt();
        if (length < 4 || length > MAX_MESSAGE) {
            throw new ProtocolException("invalid message length " + length + " of a message of type '"
                    + (char) type + "'");
        }
        byte[] body = new byte[length - 4];
        in.readFully(body);
        return new Message((char) type, new Body(body));
    }

    /** A client that does not follow the protocol, which ends the connection. */
    static final class ProtocolException extends IOException {
        private static final long serialVersionUID = 1L;

        ProtocolException(String message) {
            super(message);
        }
    }

    /** The body of a message, read from its start. */
    static final class Body {
        private final byte[] bytes;
        private int position;

        Body(byte[] bytes) {
            this.bytes = bytes;
        }

        int remaining() {
            return bytes.length - position;
        }

        private void need(int n) throws ProtocolException {
            if (n < 0 || remaining() < n) {
                throw new ProtocolException("a message is shorter than its content");
            }
        }

        byte int8() throws ProtocolException {
            need(1);
            return bytes[position++];
        }

        short int16() throws ProtocolException {
            need(2);
            short v = (short) ((bytes[position] & 0xFF) << 8 | bytes[position + 1] & 0xFF);
            position += 2;
            return v;
        }

        int int32() throws ProtocolException {
            need(4);
            int v = (bytes[position] & 0xFF) << 24 | (bytes[position + 1] & 0xFF) << 16
                    | (bytes[position + 2] & 0xFF) << 8 | bytes[position + 3] & 0xFF;
            position += 4;
            return v;
        }

        byte[] bytes(int n) throws ProtocolException {
            need(n);
            byte[] b = new byte[n];
            System.arraycopy(bytes, position, b, 0, n);
            position += n;
            return b;
        }

        /** Reads a string that ends with a zero byte. */
        String string(Charset charset) throws ProtocolException {
            int end = position;
            while (end < bytes.length && bytes[end] != 0) {
                end++;
            }
            if (end == bytes.length) {
                throw new ProtocolException("a string of a message has no end");
            }
            String s = new String(bytes, position, end - position, charset);
            position = end + 1;
            return s;
        }

        /** Reads a string of the startup packet, which is always in UTF-8 or ASCII. */
        String string() throws ProtocolException {
            return string(StandardCharsets.UTF_8);
        }
    }
}
