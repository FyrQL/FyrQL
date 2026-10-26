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

package net.fyrql.connector.s3;

import net.fyrql.connector.FileObject;
import net.fyrql.connector.FileOutput;
import net.fyrql.connector.FileStore;
import net.fyrql.connector.Globs;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The objects of S3 as files, under the scheme {@code s3}. A location
 * {@code s3://bucket/key} names an object; if there is none and the key
 * does not end with {@code /}, the objects directly under the prefix
 * {@code key/}, as for one that ends with {@code /}; a pattern in the last
 * part names the objects directly under the prefix before it whose names
 * match; {@link #listTree} takes the objects at any depth under the
 * prefix. Keys that end with {@code /}, which tools create as folders, are
 * no files. A file is read in the version of its entity tag; written, it
 * is uploaded in parts of a size and appears with the commit, see
 * {@link FileOutput}.
 */
final class S3FileStore implements FileStore {

    private final S3Client client;
    private final int partSize;

    S3FileStore(S3Client client, int partSize) {
        this.client = client;
        this.partSize = partSize;
    }

    S3Client client() {
        return client;
    }

    @Override
    public String scheme() {
        return "s3";
    }

    @Override
    public List<FileObject> list(String location, String extension) throws IOException {
        Globs.checkPattern(location);
        S3Location at = S3Location.parse(location);
        String name = at.name();
        if (Globs.isPattern(name)) {
            return matching(at, at.parent(), Globs.regex(name), "/");
        }
        return objects(at, extension, "/");
    }

    /**
     * Returns the objects a location names under a prefix at any depth:
     * the object itself, those whose keys end with the extension under the
     * prefix {@code key/}, or those under the prefix before the first part
     * with a pattern whose rest of the key matches it.
     */
    @Override
    public List<FileObject> listTree(String location, String extension) throws IOException {
        Globs.checkTreePattern(location);
        S3Location at = S3Location.parse(location);
        int start = Globs.patternStart(at.key());
        if (start >= 0) {
            return matching(at, at.key().substring(0, start), Globs.pathRegex(at.key().substring(start)), null);
        }
        return objects(at, extension, null);
    }

    /** Returns the objects under a prefix whose rest of the key matches a pattern. */
    private List<FileObject> matching(S3Location at, String prefix, Pattern pattern, String delimiter)
            throws IOException {
        List<FileObject> files = new ArrayList<>();
        for (S3Client.Listed o : client.list(at.bucket(), prefix, delimiter)) {
            String n = o.key().substring(prefix.length());
            if (!o.key().endsWith("/") && pattern.matcher(n).matches()) {
                files.add(object(at.withKey(o.key()), o.size(), o.etag()));
            }
        }
        return files;
    }

    /** Returns the object at a location, or the objects under the prefix {@code key/} with an extension. */
    private List<FileObject> objects(S3Location at, String extension, String delimiter) throws IOException {
        List<FileObject> files = new ArrayList<>();
        String prefix = at.key();
        if (!prefix.isEmpty() && !prefix.endsWith("/")) {
            try {
                S3Client.Head head = client.head(at);
                files.add(object(at, head.size(), head.etag()));
                return files;
            } catch (NoSuchFileException e) {
                prefix += "/";
            }
        }
        String lower = extension.toLowerCase(Locale.ROOT);
        for (S3Client.Listed o : client.list(at.bucket(), prefix, delimiter)) {
            if (!o.key().endsWith("/") && o.key().toLowerCase(Locale.ROOT).endsWith(lower)) {
                files.add(object(at.withKey(o.key()), o.size(), o.etag()));
            }
        }
        return files;
    }

    @Override
    public FileObject object(String location) throws IOException {
        S3Location at = S3Location.parse(location);
        if (at.key().isEmpty() || at.key().endsWith("/")) {
            throw new NoSuchFileException(location);
        }
        S3Client.Head head = client.head(at);
        return object(at, head.size(), head.etag());
    }

    private FileObject object(S3Location location, long size, String etag) {
        return new S3Object(location, size, etag);
    }

    /**
     * Starts to write an object.
     *
     * @throws IllegalArgumentException if the location has no key, a key
     *         that ends with {@code /}, or a pattern
     */
    @Override
    public FileOutput create(String location) {
        S3Location at = S3Location.parse(location);
        if (at.key().isEmpty() || at.key().endsWith("/") || Globs.isPattern(at.key())) {
            throw new IllegalArgumentException("Cannot write " + location + ": it is not the location of an object");
        }
        return new S3Output(at);
    }

    /** An object in the version of its entity tag. */
    private final class S3Object implements FileObject {
        private final S3Location location;
        private final long size;
        private final String etag;

        S3Object(S3Location location, long size, String etag) {
            this.location = location;
            this.size = size;
            this.etag = etag;
        }

        @Override
        public String location() {
            return location.toString();
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public String version() {
            return etag;
        }

        @Override
        public InputStream open() throws IOException {
            return client.get(location, etag);
        }

        @Override
        public int read(long position, ByteBuffer buffer) throws IOException {
            if (position >= size) {
                return -1;
            }
            int n = (int) Math.min(buffer.remaining(), size - position);
            if (n == 0) {
                return 0;
            }
            ByteBuffer part = buffer.slice(buffer.position(), n);
            client.read(location, etag, position, part);
            buffer.position(buffer.position() + n);
            return n;
        }

        @Override
        public String toString() {
            return location();
        }
    }

    /**
     * An object being written: the bytes are kept until there are as many
     * as a part holds, then the upload in parts starts; an object smaller
     * than a part is written at once with the commit.
     */
    private final class S3Output implements FileOutput {
        private final S3Location location;
        private final List<String> etags = new ArrayList<>();
        private final Part stream = new Part();
        private String uploadId;
        private boolean done;

        S3Output(S3Location location) {
            this.location = location;
        }

        /** The bytes of the part being written. */
        private final class Part extends OutputStream {
            private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(partSize, 1 << 20));
            private boolean closed;

            @Override
            public void write(int b) throws IOException {
                write(new byte[] {(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                if (closed || done) {
                    throw new IOException("The upload of " + location + " is closed");
                }
                while (len > 0) {
                    int n = Math.min(len, partSize - bytes.size());
                    bytes.write(b, off, n);
                    off += n;
                    len -= n;
                    if (bytes.size() == partSize) {
                        upload();
                    }
                }
            }

            @Override
            public void close() {
                closed = true;
            }
        }

        /** Uploads the bytes kept as the next part, starting the upload with the first one. */
        private void upload() throws IOException {
            if (uploadId == null) {
                uploadId = client.createMultipart(location);
            }
            byte[] part = stream.bytes.toByteArray();
            etags.add(client.uploadPart(location, uploadId, etags.size() + 1, part, part.length));
            stream.bytes.reset();
        }

        @Override
        public String location() {
            return location.toString();
        }

        @Override
        public OutputStream stream() {
            return stream;
        }

        @Override
        public void commit() throws IOException {
            if (done) {
                throw new IllegalStateException("The upload of " + location + " is already closed");
            }
            done = true;
            stream.closed = true;
            if (uploadId == null) {
                byte[] bytes = stream.bytes.toByteArray();
                client.put(location, bytes, bytes.length);
                return;
            }
            try {
                if (stream.bytes.size() > 0) {
                    upload();
                }
                client.completeMultipart(location, uploadId, etags);
                uploadId = null;
            } catch (IOException | RuntimeException e) {
                abort();
                throw e;
            }
        }

        /** Abandons the upload unless it was committed. */
        @Override
        public void close() {
            done = true;
            stream.closed = true;
            stream.bytes.reset();
            abort();
        }

        private void abort() {
            if (uploadId != null) {
                String id = uploadId;
                uploadId = null;
                try {
                    client.abortMultipart(location, id);
                } catch (IOException | RuntimeException e) {
                    // The object does not appear anyway; S3 removes the parts of an abandoned upload by its rules.
                }
            }
        }
    }
}
