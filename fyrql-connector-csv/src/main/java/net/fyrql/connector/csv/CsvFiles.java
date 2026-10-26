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

package net.fyrql.connector.csv;

import net.fyrql.connector.FileObject;
import net.fyrql.connector.FileStores;
import net.fyrql.connector.Globs;
import net.fyrql.connector.LocalFileStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * The files of a table of CSV files: a location names a file, a directory,
 * whose {@code .csv} and {@code .tsv} files at any depth, also compressed
 * with gzip, make up the table, or a pattern with {@code *}, {@code ?} and
 * {@code **}, whose files make it up whatever their names; local or in a
 * store of the engine. The files are sorted by their locations.
 */
final class CsvFiles {

    private CsvFiles() {
    }

    /**
     * Returns a location as an absolute one, relative local ones resolved
     * against a directory, leaving the parts from the first one with a
     * pattern on and a location with a scheme as they are. A local location
     * is resolved as {@link LocalFileStore#resolve} resolves it, also a URI
     * of the scheme {@code file}, so that the file read is the one the
     * policy of the engine checked.
     *
     * @throws IllegalArgumentException if the location has {@code **}
     *         besides other characters in a part, or is no valid path
     */
    static String absolute(String location, Path directory) {
        Globs.checkTreePattern(location);
        if (FileStores.scheme(location).isPresent()) {
            return location;
        }
        int start = Globs.patternStart(location);
        try {
            if (start < 0) {
                return LocalFileStore.resolve(location, directory).toAbsolutePath().normalize().toString();
            }
            return LocalFileStore.resolve(location.substring(0, start), directory).toAbsolutePath().normalize()
                    + java.io.File.separator + location.substring(start);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid path " + location, e);
        }
    }

    /**
     * Returns the files of a location, sorted.
     *
     * @param given the location as the user wrote it, for messages
     * @throws IllegalArgumentException if the location names no file or cannot be listed
     */
    static List<FileObject> list(FileStores stores, String location, String given) {
        List<FileObject> files;
        try {
            files = stores.listTree(location, "");
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot list " + given + ": " + e.getMessage(), e);
        }
        boolean itself = files.size() == 1 && Globs.patternStart(location) < 0 && sameFile(files.get(0), location);
        if (Globs.patternStart(location) < 0 && !itself) {
            files = files.stream().filter(f -> isCsvFile(f.name())).toList();
        }
        if (files.isEmpty()) {
            throw new IllegalArgumentException("No files found that match the pattern \"" + given + "\"");
        }
        return files;
    }

    private static boolean sameFile(FileObject file, String location) {
        if (file.path().isPresent() && FileStores.isLocal(location)) {
            try {
                return file.path().get().equals(LocalFileStore.resolve(location, Path.of("")).toAbsolutePath()
                        .normalize());
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
        return file.location().equals(location);
    }

    /** Tells whether a name is that of a CSV or TSV file, also compressed with gzip, in any case. */
    static boolean isCsvFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".csv") || lower.endsWith(".tsv") || lower.endsWith(".csv.gz")
                || lower.endsWith(".tsv.gz");
    }

    /** Tells whether a name is that of a TSV file, also compressed with gzip, in any case. */
    static boolean isTsvFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".tsv") || lower.endsWith(".tsv.gz");
    }

    /** Tells whether there is a CSV or TSV file anywhere under a directory. */
    static boolean hasCsvFiles(Path directory) throws IOException {
        try (Stream<Path> files = Files.walk(directory)) {
            return files.anyMatch(p -> Files.isRegularFile(p) && isCsvFile(p.getFileName().toString()));
        }
    }

    /**
     * Returns the delimiter by default of files: a tab if their names end
     * in {@code .tsv} or {@code .tsv.gz}, otherwise a comma.
     *
     * @throws IllegalArgumentException if some names end so and others do not
     */
    static char delimiter(List<String> names, String location) {
        long tsv = names.stream().filter(CsvFiles::isTsvFile).count();
        if (tsv > 0 && tsv < names.size()) {
            throw new IllegalArgumentException("The files of " + location + " mix TSV files with other ones;"
                    + " give the delimiter");
        }
        return tsv > 0 ? '\t' : ',';
    }
}
