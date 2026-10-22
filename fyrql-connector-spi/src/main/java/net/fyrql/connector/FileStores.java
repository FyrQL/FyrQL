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

package net.fyrql.connector;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The stores of files of an engine by their schemes: a location with a
 * scheme, such as {@code s3://bucket/key}, goes to the store of the scheme,
 * any other one, also of the scheme {@code file}, to the local file system.
 */
public final class FileStores {

    /** A scheme followed by {@code ://}, so that a drive such as {@code C:\} is none. */
    private static final Pattern SCHEME = Pattern.compile("^([a-zA-Z][a-zA-Z0-9+.-]*)://");

    private final Map<String, FileStore> stores;
    private final LocalFileStore local;
    private final FileAccessPolicy policy;

    /**
     * Creates the stores, through which statements in SQL may read and
     * write anywhere.
     *
     * @param stores the stores by their schemes, besides the local one
     * @param directory the directory against which relative local paths are resolved
     */
    public FileStores(Map<String, FileStore> stores, Path directory) {
        this(stores, directory, FileAccessPolicy.ALL);
    }

    /**
     * Creates the stores.
     *
     * @param stores the stores by their schemes, besides the local one
     * @param directory the directory against which relative local paths are resolved
     * @param policy which locations statements in SQL may read and write
     */
    public FileStores(Map<String, FileStore> stores, Path directory, FileAccessPolicy policy) {
        this.stores = new LinkedHashMap<>();
        stores.forEach((scheme, store) -> this.stores.put(scheme.toLowerCase(Locale.ROOT), store));
        this.local = new LocalFileStore(directory);
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /** Returns the stores with only the local file system, relative paths resolved against a directory. */
    public static FileStores local(Path directory) {
        return new FileStores(Map.of(), directory);
    }

    /** Returns the scheme of a location in lower case, such as {@code s3}, or empty for a local path. */
    public static Optional<String> scheme(String location) {
        Matcher m = SCHEME.matcher(location);
        return m.find() ? Optional.of(m.group(1).toLowerCase(Locale.ROOT)) : Optional.empty();
    }

    /** Tells whether a location is in the local file system. */
    public static boolean isLocal(String location) {
        return scheme(location).map("file"::equals).orElse(true);
    }

    /** Returns the schemes of the stores besides the local one. */
    public List<String> schemes() {
        return List.copyOf(stores.keySet());
    }

    /** Returns the local file system. */
    public LocalFileStore local() {
        return local;
    }

    /**
     * Returns which locations statements in SQL may read and write. The
     * engine checks the locations the statements name; a connector checks
     * the further files it reads for a table of such a statement, such as
     * the data files of an Iceberg table, but not those of the tables
     * registered with it.
     */
    public FileAccessPolicy policy() {
        return policy;
    }

    /**
     * Returns the store of a location.
     *
     * @throws IllegalArgumentException if no store has its scheme
     */
    public FileStore store(String location) {
        Optional<String> scheme = scheme(location);
        if (scheme.isEmpty() || scheme.get().equals("file")) {
            return local;
        }
        FileStore store = stores.get(scheme.get());
        if (store == null) {
            throw new IllegalArgumentException("No source handles " + scheme.get() + ":// locations; register a"
                    + " source of the type " + scheme.get());
        }
        return store;
    }

    /** See {@link FileStore#list}. */
    public List<FileObject> list(String location, String extension) throws IOException {
        return store(location).list(location, extension);
    }

    /** See {@link FileStore#object}. */
    public FileObject object(String location) throws IOException {
        return store(location).object(location);
    }

    /** See {@link FileStore#create}. */
    public FileOutput create(String location) throws IOException {
        return store(location).create(location);
    }
}
