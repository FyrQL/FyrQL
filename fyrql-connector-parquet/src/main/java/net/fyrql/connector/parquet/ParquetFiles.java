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
import net.fyrql.connector.FileStores;
import net.fyrql.connector.Globs;
import net.fyrql.connector.LocalFileStore;
import net.fyrql.core.type.Schema;
import org.apache.parquet.ParquetReadOptions;
import org.apache.parquet.filter2.compat.FilterCompat;
import org.apache.parquet.filter2.predicate.FilterPredicate;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.metadata.BlockMetaData;
import org.apache.parquet.hadoop.metadata.ParquetMetadata;
import org.apache.parquet.io.InputFile;
import org.apache.parquet.schema.MessageType;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The files of the tables of a connector: finds the files a location names
 * in the stores of files and keeps the footer of each file as long as its
 * size and version stay the same.
 * <p>
 * A location is a file, a directory, whose {@code .parquet} files make up
 * the table, or a pattern with {@code *} and {@code ?} in the name of the
 * file, such as {@code data/orders-*.parquet}, local or in a store such as
 * {@code s3://bucket/orders/}. The files are sorted by their locations.
 */
final class ParquetFiles {

    private static final String EXTENSION = ".parquet";

    /** The bytes a file of a store other than the local one is read ahead by default, see {@link StoreFile}. */
    static final int READ_AHEAD = 1 << 20;

    /**
     * A file in a version: its location, size and version, and the file
     * through which it is read, which two equal versions need not share.
     */
    record Version(String location, long size, String tag, FileObject file) {

        static Version of(FileObject file) {
            return new Version(file.location(), file.size(), file.version(), file);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Version v && location.equals(v.location) && size == v.size && tag.equals(v.tag);
        }

        @Override
        public int hashCode() {
            return Objects.hash(location, size, tag);
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

    private final Map<String, Footer> footers = new ConcurrentHashMap<>();
    private final int readAhead;
    private volatile FileStores stores;

    ParquetFiles(FileStores stores, int readAhead) {
        this.stores = stores;
        this.readAhead = readAhead;
    }

    /** Returns the stores of the files. */
    FileStores stores() {
        return stores;
    }

    /** Sets the stores of the files, those of the engine. */
    void stores(FileStores stores) {
        this.stores = stores;
    }

    /** Returns a file for the reader of parquet-java. */
    InputFile input(FileObject file) {
        return new StoreFile(file, readAhead);
    }

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
     * Returns the row groups of a file in which a row can satisfy a
     * predicate, by their statistics, dictionaries and Bloom filters.
     */
    List<BlockMetaData> passing(Footer footer, FilterPredicate predicate) {
        String file = footer.version().location();
        InputFile input = input(footer.version().file());
        try (ParquetFileReader reader = new ParquetFileReader(input, footer.metadata(),
                options(FilterCompat.get(predicate), true), input.newStream())) {
            return reader.getRowGroups();
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("Cannot read the Parquet file " + file + ": "
                    + (e.getMessage() == null ? e.toString() : e.getMessage()), e);
        }
    }

    /**
     * Returns a location as an absolute one, relative local ones resolved
     * against a directory, leaving a pattern in the name of the file and a
     * location with a scheme as they are. A local location is resolved as
     * {@link LocalFileStore#resolve} resolves it, also a URI of the scheme
     * {@code file}, so that the file read is the one the policy of the
     * engine checked.
     */
    static String absolute(String location, Path directory) {
        Globs.checkPattern(location);
        if (FileStores.scheme(location).isPresent()) {
            return location;
        }
        int slash = Globs.lastSeparator(location);
        String name = location.substring(slash + 1);
        String parent = slash < 0 ? "" : location.substring(0, slash + 1);
        try {
            if (!Globs.isPattern(name)) {
                return LocalFileStore.resolve(location, directory).toAbsolutePath().normalize().toString();
            }
            return LocalFileStore.resolve(parent, directory).toAbsolutePath().normalize() + java.io.File.separator
                    + name;
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Invalid path " + location, e);
        }
    }

    /**
     * Returns the files of a location, sorted.
     *
     * @throws IllegalArgumentException if the location names no file, has
     *         a pattern outside the name of the file, or cannot be listed
     */
    List<FileObject> resolve(String location) {
        List<FileObject> files;
        try {
            files = stores.list(location, EXTENSION);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot list " + location + ": " + e.getMessage(), e);
        }
        if (files.isEmpty()) {
            if (FileStores.isLocal(location) && !Globs.isPattern(location)
                    && Files.isDirectory(stores.local().path(location))) {
                throw new IllegalArgumentException("No Parquet files in the directory " + location);
            }
            throw new IllegalArgumentException("No files found that match the pattern \"" + location + "\"");
        }
        return files;
    }

    /**
     * Returns the file at a location.
     *
     * @throws IllegalArgumentException if there is none or it cannot be read
     */
    FileObject object(String location) {
        try {
            return stores.object(location);
        } catch (NoSuchFileException e) {
            throw new IllegalArgumentException("No files found that match the pattern \"" + location + "\"", e);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read " + location + ": " + e.getMessage(), e);
        }
    }

    /**
     * Tells whether a local file changed since it was looked up; a file of
     * another store is read in its version or not at all.
     */
    boolean changed(Version version) {
        if (version.file().path().isEmpty()) {
            return false;
        }
        try {
            return !Version.of(stores.local().object(version.location())).equals(version);
        } catch (IOException e) {
            return true;
        }
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

    /**
     * Returns the footer of a file in the version in which it was looked
     * up, read again if the file changed since it was read last.
     *
     * @throws IllegalArgumentException if the file is not a Parquet file or
     *         cannot be read
     */
    Footer footer(FileObject file) {
        Version version = Version.of(file);
        Footer footer = footers.get(version.location());
        if (footer != null && footer.version().equals(version)) {
            return footer;
        }
        footer = new Footer(version, read(file));
        footers.put(version.location(), footer);
        return footer;
    }

    private ParquetMetadata read(FileObject file) {
        try (ParquetFileReader reader = ParquetFileReader.open(input(file), options())) {
            return reader.getFooter();
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("Cannot read the Parquet file " + file.location() + ": "
                    + e.getMessage(), e);
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
        return table(location, resolve(location));
    }

    /** Reads the footers of the files of a location found before, see {@link #table(String)}. */
    Table table(String location, List<FileObject> files) {
        List<Version> versions = new ArrayList<>();
        Schema schema = null;
        String first = null;
        long rows = 0;
        for (FileObject file : files) {
            Footer footer = footer(file);
            Schema s;
            try {
                s = ParquetSchemas.schema(footer.schema());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(e.getMessage() + " in " + file.location(), e);
            }
            if (schema == null) {
                schema = s;
                first = file.location();
            } else {
                schema = shared(schema, s, first, file.location());
            }
            versions.add(footer.version());
            rows += footer.rows();
        }
        return new Table(location, versions, schema, rows);
    }

    /** Returns the columns two files share, nullable if nullable in either. */
    private static Schema shared(Schema a, Schema b, String fileA, String fileB) {
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
