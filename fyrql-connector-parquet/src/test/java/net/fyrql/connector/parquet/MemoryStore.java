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

package net.fyrql.connector.parquet;

import net.fyrql.connector.FileObject;
import net.fyrql.connector.FileOutput;
import net.fyrql.connector.FileStore;
import net.fyrql.connector.Globs;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * A store of files in memory under the scheme {@code mem}, as an object
 * store without directories: a location such as {@code mem://b/dir/x}
 * names an object, one that ends with {@code /} the objects under it. It
 * records every range read, so that a test sees which bytes a reader asks
 * for.
 */
final class MemoryStore implements FileStore {

    /** A range read: the location, the position and the number of bytes asked for. */
    record Request(String location, long position, int length) {
    }

    private final Map<String, byte[]> objects = new TreeMap<>();
    private final Map<String, String> versions = new TreeMap<>();
    private final List<Request> requests = new ArrayList<>();
    private final AtomicInteger version = new AtomicInteger();

    @Override
    public String scheme() {
        return "mem";
    }

    /** Puts an object. */
    synchronized void put(String location, byte[] bytes) {
        objects.put(location, bytes.clone());
        versions.put(location, "v" + version.incrementAndGet());
    }

    /** Returns the bytes of an object, or {@code null}. */
    synchronized byte[] get(String location) {
        return objects.get(location);
    }

    /** Returns the locations of the objects. */
    synchronized List<String> locations() {
        return List.copyOf(objects.keySet());
    }

    /** Returns the ranges read so far. */
    synchronized List<Request> requests() {
        return List.copyOf(requests);
    }

    /** Forgets the ranges read. */
    synchronized void clearRequests() {
        requests.clear();
    }

    @Override
    public synchronized List<FileObject> list(String location, String extension) throws IOException {
        Globs.checkPattern(location);
        int slash = Globs.lastSeparator(location);
        String parent = location.substring(0, slash + 1);
        String name = location.substring(slash + 1);
        List<FileObject> files = new ArrayList<>();
        if (!Globs.isPattern(name) && objects.containsKey(location)) {
            files.add(object(location));
            return files;
        }
        String prefix = Globs.isPattern(name) || name.isEmpty() ? parent : location + "/";
        Pattern pattern = Globs.isPattern(name) ? Globs.regex(name) : null;
        for (String key : objects.keySet()) {
            if (!key.startsWith(prefix) || key.indexOf('/', prefix.length()) >= 0) {
                continue;
            }
            String n = key.substring(prefix.length());
            if (pattern != null ? pattern.matcher(n).matches()
                    : n.toLowerCase(Locale.ROOT).endsWith(extension.toLowerCase(Locale.ROOT))) {
                files.add(object(key));
            }
        }
        return files;
    }

    @Override
    public synchronized FileObject object(String location) throws IOException {
        byte[] bytes = objects.get(location);
        if (bytes == null) {
            throw new NoSuchFileException(location);
        }
        return new MemoryObject(location, bytes, versions.get(location));
    }

    @Override
    public FileOutput create(String location) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        return new FileOutput() {
            @Override
            public String location() {
                return location;
            }

            @Override
            public OutputStream stream() {
                return stream;
            }

            @Override
            public void commit() {
                put(location, stream.toByteArray());
            }

            @Override
            public void close() {
            }
        };
    }

    private final class MemoryObject implements FileObject {
        private final String location;
        private final byte[] bytes;
        private final String version;

        MemoryObject(String location, byte[] bytes, String version) {
            this.location = location;
            this.bytes = bytes;
            this.version = version;
        }

        @Override
        public String location() {
            return location;
        }

        @Override
        public long size() {
            return bytes.length;
        }

        @Override
        public String version() {
            return version;
        }

        @Override
        public InputStream open() {
            synchronized (MemoryStore.this) {
                requests.add(new Request(location, 0, bytes.length));
            }
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public int read(long position, ByteBuffer buffer) {
            synchronized (MemoryStore.this) {
                requests.add(new Request(location, position, buffer.remaining()));
            }
            if (position >= bytes.length) {
                return -1;
            }
            int n = (int) Math.min(buffer.remaining(), bytes.length - position);
            buffer.put(bytes, (int) position, n);
            return n;
        }
    }
}
