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

package net.fyrql.connector.iceberg;

import net.fyrql.connector.FileObject;
import net.fyrql.connector.FileStores;
import net.fyrql.connector.Globs;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where an Iceberg table is: its directory, the one with {@code metadata/}
 * and {@code data/}, and its current metadata file.
 * <p>
 * A location is the directory of a table or a metadata file. In a
 * directory, {@code metadata/version-hint.text} names the version if it is
 * there; otherwise the metadata file with the highest version is the
 * current one, by the number at the start of its name, as in
 * {@code v3.metadata.json} or {@code 00003-<uuid>.metadata.json}.
 * <p>
 * The paths in the metadata are absolute and name the place where the
 * table was written. If the table was moved or copied, a path that starts
 * with the location in the metadata is taken relative to the directory of
 * the table instead; {@code file:} prefixes are dropped and {@code s3a://}
 * and {@code s3n://} become {@code s3://}.
 *
 * @param root the directory of the table, or {@code null} if the metadata
 *        file is not in a directory {@code metadata}
 * @param metadataFile the current metadata file
 */
record TableLocation(String root, FileObject metadataFile) {

    private static final String METADATA = "metadata";
    private static final String SUFFIX = ".metadata.json";
    private static final Pattern VERSION = Pattern.compile("v?(\\d+)(?:-.*)?\\.metadata\\.json");

    /**
     * Finds the current metadata file of a table.
     *
     * @param location the directory of the table or a metadata file, absolute
     * @throws IllegalArgumentException if there is no table at the location
     */
    static TableLocation resolve(FileStores stores, String location) {
        try {
            if (location.endsWith(SUFFIX)) {
                FileObject file = stores.object(location);
                int slash = Globs.lastSeparator(location);
                String dir = location.substring(0, Math.max(slash, 0));
                int up = Globs.lastSeparator(dir);
                String root = dir.substring(up + 1).equals(METADATA) && up > 0 ? dir.substring(0, up) : null;
                return new TableLocation(root, file);
            }
            String root = trim(location);
            String metadata = join(root, METADATA);
            FileObject hinted = hinted(stores, metadata);
            if (hinted != null) {
                return new TableLocation(root, hinted);
            }
            FileObject latest = null;
            long version = -1;
            for (FileObject f : stores.list(metadata, SUFFIX)) {
                Matcher m = VERSION.matcher(f.name());
                if (m.matches()) {
                    long v = Long.parseLong(m.group(1));
                    if (v > version || v == version && f.location().compareTo(latest.location()) > 0) {
                        version = v;
                        latest = f;
                    }
                }
            }
            if (latest == null) {
                throw new IllegalArgumentException("No Iceberg table at " + location + ": no metadata files in "
                        + metadata);
            }
            return new TableLocation(root, latest);
        } catch (NoSuchFileException e) {
            throw new IllegalArgumentException("No Iceberg metadata file " + location, e);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read the Iceberg table " + location + ": " + e.getMessage(), e);
        }
    }

    /** Returns the metadata file that {@code version-hint.text} names, or {@code null}. */
    private static FileObject hinted(FileStores stores, String metadata) throws IOException {
        String hint;
        try {
            hint = new String(bytes(stores.object(join(metadata, "version-hint.text"))), StandardCharsets.UTF_8)
                    .trim();
        } catch (NoSuchFileException e) {
            return null;
        }
        if (hint.isEmpty()) {
            return null;
        }
        List<String> names = hint.chars().allMatch(Character::isDigit)
                ? List.of("v" + hint + SUFFIX, hint + SUFFIX) : List.of(hint, hint + SUFFIX);
        for (String name : names) {
            try {
                return stores.object(join(metadata, name));
            } catch (NoSuchFileException e) {
                // The next name.
            }
        }
        if (hint.chars().allMatch(Character::isDigit)) {
            long version = Long.parseLong(hint);
            for (FileObject f : stores.list(metadata, SUFFIX)) {
                Matcher m = VERSION.matcher(f.name());
                if (m.matches() && Long.parseLong(m.group(1)) == version) {
                    return f;
                }
            }
        }
        return null;
    }

    /** Returns all bytes of a file. */
    static byte[] bytes(FileObject file) throws IOException {
        try (InputStream in = file.open()) {
            return in.readAllBytes();
        }
    }

    /**
     * Returns where a path of the metadata is now: under the directory of
     * the table if it starts with the location the metadata gives, as it
     * is otherwise.
     *
     * @param path a path from the metadata, a manifest or a delete file
     * @param tableLocation the location the metadata gives
     */
    String relocate(String path, String tableLocation) {
        String p = normalize(path);
        if (root == null) {
            return p;
        }
        String from = trim(normalize(tableLocation));
        if (p.startsWith(from) && (p.length() == from.length() || p.charAt(from.length()) == '/'
                || p.charAt(from.length()) == '\\')) {
            String rest = p.substring(from.length());
            return rest.isEmpty() ? root : join(root, rest.substring(1));
        }
        return p;
    }

    /** Returns a path without a {@code file:} prefix and with {@code s3://} for the other schemes of S3. */
    static String normalize(String path) {
        String p = path.startsWith("file://") ? path.substring("file://".length())
                : path.startsWith("file:") ? path.substring("file:".length()) : path;
        if (p.length() < path.length() && p.length() > 2 && p.charAt(0) == '/' && p.charAt(2) == ':'
                && Character.isLetter(p.charAt(1))) {
            p = p.substring(1);
        }
        if (p.startsWith("s3a://") || p.startsWith("s3n://")) {
            p = "s3://" + p.substring("s3a://".length());
        }
        return p;
    }

    private static String trim(String location) {
        String l = location;
        while (l.length() > 1 && (l.endsWith("/") || l.endsWith("\\")) && !l.endsWith("://")) {
            l = l.substring(0, l.length() - 1);
        }
        return l;
    }

    private static String join(String dir, String name) {
        return dir.endsWith("/") || dir.endsWith("\\") ? dir + name : dir + "/" + name;
    }
}
