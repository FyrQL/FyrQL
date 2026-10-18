/*
 * Copyright 2026 the FyrQL authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fyrql.connector.parquet;

import net.fyrql.core.type.Schema;
import org.apache.parquet.ParquetReadOptions;
import org.apache.parquet.filter2.compat.FilterCompat;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.metadata.BlockMetaData;
import org.apache.parquet.hadoop.metadata.ParquetMetadata;
import org.apache.parquet.schema.MessageType;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The files of the tables of a connector: finds the files a location names
 * and keeps the footer of each file as long as its size and time of
 * modification stay the same.
 * <p>
 * A location is a file, a directory, whose {@code .parquet} files make up
 * the table, or a pattern with {@code *} and {@code ?} in the name of the
 * file, such as {@code data/orders-*.parquet}. The files are sorted by
 * their paths.
 */
final class ParquetFiles {

    private static final String EXTENSION = ".parquet";

    /** A file in a version: its path, size and time of modification. */
    record Version(Path file, long size, FileTime modified) {

        static Version of(Path file) {
            try {
                return new Version(file, Files.size(file), Files.getLastModifiedTime(file));
            } catch (IOException e) {
                throw new IllegalArgumentException("Cannot read " + file + ": " + e.getMessage(), e);
            }
        }
    }

    /** The footer of a file in a version. */
    record Footer(Version version, ParquetMetadata metadata) {

        MessageType schema() {
            return metadata.getFileMetaData().getSchema();
        }

        long rows() {
            long rows = 0;
            for (BlockMetaData block : metadata.getBlocks()) {
                rows += block.getRowCount();
            }
            return rows;
        }
    }

    /**
     * The files of a table, with the columns they share and their rows.
     *
     * @param location the location as given, for messages
     * @param files the files, sorted, each in the version that was read
     * @param schema the columns; nullable if nullable in any file
     * @param rows the rows of all files
     */
    record Table(String location, List<Version> files, Schema schema, long rows) {
    }

    private final Map<Path, Footer> footers = new ConcurrentHashMap<>();

    /** Returns the options with which every file is read: the codecs in pure Java. */
    static ParquetReadOptions options() {
        return ParquetReadOptions.builder(new PlainParquetConfiguration())
                .withCodecFactory(new PureJavaCodecs())
                .build();
    }

    /**
     * Returns the options with which a file is read under a filter: if
     * {@code rowGroups}, a reader keeps only the row groups in which a row
     * can pass it, by their statistics, dictionaries and Bloom filters;
     * otherwise it keeps all of them, and reads of the pages of a row group
     * only those in which a row can pass, by the column index.
     */
    static ParquetReadOptions options(FilterCompat.Filter filter, boolean rowGroups) {
        return ParquetReadOptions.builder(new PlainParquetConfiguration())
                .withCodecFactory(new PureJavaCodecs())
                .withRecordFilter(filter)
                .useStatsFilter(rowGroups)
                .useDictionaryFilter(rowGroups)
                .useBloomFilter(rowGroups)
                .useColumnIndexFilter(!rowGroups)
                .useRecordFilter(false)
                .build();
    }

    /**
     * Returns a location as an absolute one, relative ones resolved against
     * a directory, leaving a pattern in the name of the file as it is.
     */
    static String absolute(String location, Path directory) {
        int slash = Math.max(location.lastIndexOf('/'), location.lastIndexOf('\\'));
        String name = location.substring(slash + 1);
        String parent = slash < 0 ? "" : location.substring(0, slash + 1);
        if (isPattern(parent)) {
            throw new IllegalArgumentException("A pattern is only supported in the name of a file: " + location);
        }
        try {
            if (!isPattern(name)) {
                return directory.resolve(location).toAbsolutePath().normalize().toString();
            }
            return directory.resolve(parent).toAbsolutePath().normalize() + java.io.File.separator + name;
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Invalid path " + location, e);
        }
    }

    private static boolean isPattern(String s) {
        return s.indexOf('*') >= 0 || s.indexOf('?') >= 0;
    }

    /**
     * Returns the files of a location, sorted.
     *
     * @throws IllegalArgumentException if the location names no file, has
     *         a pattern outside the name of the file, or cannot be listed
     */
    static List<Path> resolve(String location) {
        int slash = Math.max(location.lastIndexOf('/'), location.lastIndexOf('\\'));
        String name = location.substring(slash + 1);
        String parent = slash < 0 ? "" : location.substring(0, slash + 1);
        if (isPattern(parent)) {
            throw new IllegalArgumentException("A pattern is only supported in the name of a file: " + location);
        }
        List<Path> files = new ArrayList<>();
        try {
            if (isPattern(name)) {
                Pattern pattern = glob(name);
                Path dir = Path.of(parent.isEmpty() ? "." : parent);
                if (Files.isDirectory(dir)) {
                    try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir,
                            p -> Files.isRegularFile(p) && pattern.matcher(p.getFileName().toString()).matches())) {
                        entries.forEach(files::add);
                    }
                }
            } else {
                Path path = Path.of(location);
                if (Files.isDirectory(path)) {
                    files.addAll(parquetFiles(path));
                    if (files.isEmpty()) {
                        throw new IllegalArgumentException("No Parquet files in the directory " + location);
                    }
                } else if (Files.isRegularFile(path)) {
                    files.add(path);
                }
            }
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Invalid path " + location, e);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot list " + location + ": " + e.getMessage(), e);
        }
        if (files.isEmpty()) {
            throw new IllegalArgumentException("No files found that match the pattern \"" + location + "\"");
        }
        List<Path> sorted = new ArrayList<>();
        for (Path f : files) {
            sorted.add(f.toAbsolutePath().normalize());
        }
        sorted.sort(null);
        return sorted;
    }

    /** Returns the {@code .parquet} files directly in a directory, sorted. */
    static List<Path> parquetFiles(Path directory) throws IOException {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, ParquetFiles::isParquetFile)) {
            entries.forEach(files::add);
        }
        files.sort(null);
        return files;
    }

    /** Tells whether a path is a file with the extension {@code .parquet}, in any case. */
    static boolean isParquetFile(Path path) {
        return Files.isRegularFile(path) && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(EXTENSION);
    }

    /** Returns the name of a table for a {@code .parquet} file: the name without the extension. */
    static String tableName(Path file) {
        String name = file.getFileName().toString();
        return name.substring(0, name.length() - EXTENSION.length());
    }

    /** Turns {@code *} and {@code ?} into a regular expression; all else stands for itself. */
    private static Pattern glob(String name) {
        StringBuilder regex = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        for (char c : name.toCharArray()) {
            if (c == '*' || c == '?') {
                if (!literal.isEmpty()) {
                    regex.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                regex.append(c == '*' ? ".*" : ".");
            } else {
                literal.append(c);
            }
        }
        if (!literal.isEmpty()) {
            regex.append(Pattern.quote(literal.toString()));
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }

    /**
     * Returns the footer of a file in its current version, read again if
     * the file changed since it was read last.
     *
     * @throws IllegalArgumentException if the file is not a Parquet file or
     *         cannot be read
     */
    Footer footer(Path file) {
        Version version = Version.of(file);
        Footer footer = footers.get(file);
        if (footer != null && footer.version().equals(version)) {
            return footer;
        }
        footer = new Footer(version, read(file));
        footers.put(file, footer);
        return footer;
    }

    private static ParquetMetadata read(Path file) {
        try (ParquetFileReader reader = ParquetFileReader.open(new LocalFile(file), options())) {
            return reader.getFooter();
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("Cannot read the Parquet file " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * Reads the footers of the files of a location and checks that they
     * share their columns.
     *
     * @throws IllegalArgumentException if no file matches, a file cannot be
     *         read, or two files differ in the names or types of their
     *         columns
     */
    Table table(String location) {
        List<Version> versions = new ArrayList<>();
        Schema schema = null;
        Path first = null;
        long rows = 0;
        for (Path file : resolve(location)) {
            Footer footer = footer(file);
            Schema s;
            try {
                s = ParquetSchemas.schema(footer.schema());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(e.getMessage() + " in " + file, e);
            }
            if (schema == null) {
                schema = s;
                first = file;
            } else {
                schema = shared(schema, s, first, file);
            }
            versions.add(footer.version());
            rows += footer.rows();
        }
        return new Table(location, versions, schema, rows);
    }

    /** Returns the columns two files share, nullable if nullable in either. */
    private static Schema shared(Schema a, Schema b, Path fileA, Path fileB) {
        boolean same = a.size() == b.size();
        for (int c = 0; same && c < a.size(); c++) {
            same = a.name(c).equals(b.name(c)) && a.type(c).equals(b.type(c));
        }
        if (!same) {
            throw new IllegalArgumentException("The files " + fileA + " and " + fileB
                    + " have different columns: " + columns(a) + " and " + columns(b));
        }
        List<Schema.Field> fields = new ArrayList<>();
        for (int c = 0; c < a.size(); c++) {
            fields.add(new Schema.Field(a.name(c), a.type(c), a.nullable(c) || b.nullable(c)));
        }
        return new Schema(fields);
    }

    /** Returns the columns of a schema as a list of names and types, {@code NOT NULL} where not nullable. */
    static String columns(Schema s) {
        List<String> columns = new ArrayList<>();
        for (Schema.Field f : s.fields()) {
            columns.add(f.name() + " " + f.type().sqlName() + (f.nullable() ? "" : " NOT NULL"));
        }
        return "(" + String.join(", ", columns) + ")";
    }
}
