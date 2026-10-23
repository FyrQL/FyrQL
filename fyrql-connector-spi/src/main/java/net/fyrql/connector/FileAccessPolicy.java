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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Which files and objects statements in SQL may read and write: the
 * {@link FileAccess} says what, the allowed locations say where. A location
 * is allowed if it lies under one of them; without allowed locations, every
 * location is, as far as the access goes.
 * <p>
 * A local location is resolved as {@link LocalFileStore} resolves it,
 * made absolute and normalized, and symbolic links are followed as far as
 * the path exists; for a pattern such as {@code data/*.parquet}, the
 * directory before the pattern counts. A local location on another drive
 * or share than all allowed directories, such as {@code \\host\share\x},
 * is denied before the file system is asked about it. A location in a store such as
 * {@code s3://lake/sales/} is compared as text, with the scheme in lower
 * case, and must not have the parts {@code .} or {@code ..}; an allowed
 * location {@code s3://lake} allows {@code s3://lake/x}, but not
 * {@code s3://lakehouse/x}. The allowed locations are resolved in the same
 * way when the policy is created.
 * <p>
 * The policy covers only locations that statements in SQL name, such as
 * {@code FROM 'orders.csv'}, the first argument of a table function, and
 * the files of {@code COPY}; the engine checks them before a connector
 * gets them. A connector that reads further files on behalf of such a
 * statement, such as the data files of an Iceberg table, checks them
 * through {@link FileStores#policy()}. The tables an application or a
 * configuration file registers are not affected.
 */
public final class FileAccessPolicy {

    /** Reading and writing anywhere, as without a policy. */
    public static final FileAccessPolicy ALL = new FileAccessPolicy(FileAccess.READ_WRITE, Path.of(""), List.of(),
            List.of(), List.of());

    private final FileAccess access;
    private final Path directory;
    private final List<String> locations;
    private final List<Path> directories;
    private final List<String> prefixes;

    private FileAccessPolicy(FileAccess access, Path directory, List<String> locations, List<Path> directories,
            List<String> prefixes) {
        this.access = access;
        this.directory = directory;
        this.locations = List.copyOf(locations);
        this.directories = List.copyOf(directories);
        this.prefixes = List.copyOf(prefixes);
    }

    /**
     * Creates a policy.
     *
     * @param access what statements may do with files
     * @param locations the directories and prefixes under which they may do
     *        it, none for anywhere
     * @param directory the directory against which relative local paths are
     *        resolved, those of the allowed locations and those that
     *        statements name
     * @throws IllegalArgumentException if there are allowed locations with
     *         the access {@code none}, or a location is empty, no valid path,
     *         or has the parts {@code .} or {@code ..} after a scheme
     */
    public static FileAccessPolicy of(FileAccess access, List<String> locations, Path directory) {
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(directory, "directory");
        if (access == FileAccess.NONE && !locations.isEmpty()) {
            throw new IllegalArgumentException("allowedLocations needs fileAccess read or readWrite, not none");
        }
        List<String> shown = new ArrayList<>();
        List<Path> directories = new ArrayList<>();
        List<String> prefixes = new ArrayList<>();
        for (String l : locations) {
            String location = l.strip();
            if (location.isEmpty()) {
                throw new IllegalArgumentException("An allowed location is empty");
            }
            if (FileStores.isLocal(location)) {
                Path p = real(absolute(location, directory));
                directories.add(p);
                shown.add(p.toString());
            } else {
                String prefix = remote(location);
                if (prefix == null) {
                    throw new IllegalArgumentException("An allowed location must not have the parts . or ..: "
                            + location);
                }
                prefix = prefix.endsWith("/") ? prefix : prefix + "/";
                prefixes.add(prefix);
                shown.add(prefix);
            }
        }
        return new FileAccessPolicy(access, directory, shown, directories, prefixes);
    }

    /** Returns what statements may do with files. */
    public FileAccess access() {
        return access;
    }

    /** Returns the allowed locations as they were resolved, none for anywhere. */
    public List<String> locations() {
        return locations;
    }

    /**
     * Checks that statements may read a location.
     *
     * @throws FileAccessException if they may not
     * @throws IllegalArgumentException if a local location is no valid path
     */
    public void checkRead(String location) {
        check(location, false);
    }

    /**
     * Checks that statements may write a location.
     *
     * @throws FileAccessException if they may not
     * @throws IllegalArgumentException if a local location is no valid path
     */
    public void checkWrite(String location) {
        check(location, true);
    }

    private void check(String location, boolean write) {
        if (access == FileAccess.READ_WRITE && locations.isEmpty()) {
            return;
        }
        String what = (write ? "Writing " : "Reading ") + location + " is not allowed: ";
        if (!access.reads()) {
            throw new FileAccessException(what + "the engine allows no access to files from SQL (fileAccess none)");
        }
        if (write && !access.writes()) {
            throw new FileAccessException(what + "the engine allows only reading files from SQL (fileAccess read)");
        }
        if (!locations.isEmpty() && !allowed(location)) {
            throw new FileAccessException(what + "it is not under the allowed locations "
                    + String.join(", ", locations));
        }
    }

    private boolean allowed(String location) {
        if (FileStores.isLocal(location)) {
            if (directories.isEmpty()) {
                return false;
            }
            Path path = absolute(withoutPattern(location), directory);
            if (!sameRoot(path)) {
                // A path on another drive or share is denied before it is touched, so that a share such as
                // \\host\share is not asked for, which would send the credentials of the process to the host.
                return false;
            }
            // Readers normalize a path before they open it, the file system follows links before "..": both count.
            return under(real(path)) && under(real(path.normalize()));
        }
        String text = remote(location);
        if (text == null) {
            return false;
        }
        for (String prefix : prefixes) {
            if (text.startsWith(prefix) || (text + "/").equals(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** Tells whether a path has the root of an allowed directory, such as {@code /} or {@code C:\}. */
    private boolean sameRoot(Path path) {
        for (Path d : directories) {
            if (d.getRoot() != null && d.getRoot().equals(path.getRoot())) {
                return true;
            }
        }
        return false;
    }

    private boolean under(Path path) {
        for (Path d : directories) {
            if (path.startsWith(d)) {
                return true;
            }
        }
        return false;
    }

    /** Returns a location without a pattern in its last part: the directory before it. */
    private static String withoutPattern(String location) {
        int slash = Globs.lastSeparator(location);
        if (!Globs.isPattern(location.substring(slash + 1))) {
            return location;
        }
        return slash < 0 ? "." : location.substring(0, slash + 1);
    }

    /** Returns a local location as an absolute path, not yet normalized. */
    private static Path absolute(String location, Path directory) {
        return LocalFileStore.resolve(location, directory).toAbsolutePath();
    }

    /**
     * Returns a path with the symbolic links of its longest part that exists
     * followed, and the rest normalized.
     */
    private static Path real(Path path) {
        Path existing = path;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return path.normalize();
        }
        try {
            Path real = existing.toRealPath();
            int known = existing.getNameCount();
            return known == path.getNameCount() ? real
                    : real.resolve(path.subpath(known, path.getNameCount())).normalize();
        } catch (IOException | SecurityException e) {
            return path.normalize();
        }
    }

    /**
     * Returns a location with a scheme with the scheme in lower case, or
     * {@code null} if a part of it is {@code .} or {@code ..}.
     */
    private static String remote(String location) {
        int colon = location.indexOf("://");
        String rest = location.substring(colon + 3);
        for (String part : rest.split("/", -1)) {
            if (part.equals(".") || part.equals("..")) {
                return null;
            }
        }
        return location.substring(0, colon).toLowerCase(Locale.ROOT) + "://" + rest;
    }

    /**
     * Describes the policy, such as {@code none}, {@code readWrite anywhere}
     * or {@code read under /data, s3://lake/}.
     */
    @Override
    public String toString() {
        return access == FileAccess.NONE ? "none"
                : access + (locations.isEmpty() ? " anywhere" : " under " + String.join(", ", locations));
    }
}
