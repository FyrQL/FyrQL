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

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongFunction;
import java.util.stream.Stream;

import org.apache.iceberg.DataFile;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.PartitionKey;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.RowDelta;
import org.apache.iceberg.Schema;
import org.apache.iceberg.StructLike;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.GenericFileWriterFactory;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.IcebergGenerics;
import org.apache.iceberg.data.InternalRecordWrapper;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.deletes.BaseDVFileWriter;
import org.apache.iceberg.deletes.EqualityDeleteWriter;
import org.apache.iceberg.deletes.PositionDelete;
import org.apache.iceberg.deletes.PositionDeleteWriter;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.io.DataWriter;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.io.OutputFileFactory;
import org.apache.iceberg.io.PositionOutputStream;
import org.apache.iceberg.io.SeekableInputStream;
import org.apache.iceberg.jdbc.JdbcCatalog;
import org.apache.iceberg.types.Types;

/**
 * Iceberg tables of every kind the connector reads, written by Iceberg Java
 * into a warehouse of the tests: all types, partitions with every
 * transform, position and equality deletes, deletion vectors, schema and
 * partition evolution, format version 1, manifests with every codec, tags
 * and branches. Iceberg Java reads them back as the reference.
 *
 * <p>The catalog is a JDBC catalog on SQLite inside the warehouse, the file
 * IO the local file system without Hadoop. Data and delete files are
 * Parquet with gzip, which needs no native code.
 */
public final class IcebergTestTables implements AutoCloseable {

    /** The tables, in the order they are written. */
    public static final List<String> NAMES = List.of("types", "part", "pos_v2", "eq_v2", "dv_v3", "evo", "v1",
            "codec_snappy", "codec_zstd", "codec_uncompressed", "branch");

    /** The number of rows of each table in its current snapshot. */
    public static final Map<String, Integer> ROWS = Map.ofEntries(Map.entry("types", 3), Map.entry("part", 80),
            Map.entry("pos_v2", 17), Map.entry("eq_v2", 9), Map.entry("dv_v3", 8), Map.entry("evo", 4),
            Map.entry("v1", 15), Map.entry("codec_snappy", 3), Map.entry("codec_zstd", 3),
            Map.entry("codec_uncompressed", 3), Map.entry("branch", 6));

    private static final Schema SIMPLE = new Schema(
            Types.NestedField.required(1, "id", Types.LongType.get()),
            Types.NestedField.optional(2, "cat", Types.StringType.get()),
            Types.NestedField.optional(3, "name", Types.StringType.get()));

    /** The least field id of the metadata columns of Iceberg Java. */
    private static final int METADATA_IDS = Integer.MAX_VALUE - 200;

    private static IcebergTestTables shared;

    private final Path warehouse;
    private final JdbcCatalog catalog;
    private final Map<String, Table> tables = new LinkedHashMap<>();
    private int task;

    private IcebergTestTables(Path warehouse) {
        this.warehouse = warehouse;
        String root = slashes(warehouse);
        catalog = new JdbcCatalog();
        catalog.initialize("tests", Map.of("warehouse", root, "io-impl", LocalIO.class.getName(),
                "uri", "jdbc:sqlite:" + root + "/catalog.db", "jdbc.schema-version", "V1"));
        catalog.createNamespace(Namespace.of("db"));
    }

    /** Writes all tables into a new warehouse in a directory. */
    public static IcebergTestTables create(Path warehouse) throws IOException {
        Files.createDirectories(warehouse);
        IcebergTestTables tables = new IcebergTestTables(warehouse);
        try {
            tables.types();
            tables.part();
            tables.positionDeletes();
            tables.equalityDeletes();
            tables.deletionVector();
            tables.evolution();
            tables.version1();
            for (String codec : List.of("snappy", "zstd", "uncompressed")) {
                tables.codec(codec);
            }
            tables.branch();
        } catch (IOException | RuntimeException e) {
            tables.close();
            throw e;
        }
        return tables;
    }

    /**
     * Returns the tables in a temporary directory that the tests of a JVM
     * share and must not change; they are removed when the JVM ends.
     */
    public static synchronized IcebergTestTables shared() throws IOException {
        if (shared == null) {
            Path dir = Files.createTempDirectory("fyrql-iceberg");
            shared = create(dir.resolve("warehouse"));
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    shared.close();
                    delete(dir);
                } catch (IOException e) {
                    // A temporary directory left behind.
                }
            }));
        }
        return shared;
    }

    /** Deletes a directory with everything in it. */
    public static void delete(Path dir) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }

    /** Returns the directory of the warehouse. */
    public Path warehouse() {
        return warehouse;
    }

    /** Returns a table. */
    public Table table(String name) {
        Table table = tables.get(name);
        if (table == null) {
            throw new IllegalArgumentException("No test table " + name);
        }
        return table;
    }

    /** Returns the directory of a table, the one with {@code data/} and {@code metadata/}. */
    public Path location(String name) {
        return Path.of(table(name).location());
    }

    /** Returns the current metadata file of a table. */
    public Path metadata(String name) {
        return Path.of(((HasTableOperations) table(name)).operations().current().metadataFileLocation());
    }

    /** Returns the rows of the current snapshot of a table, read by Iceberg Java. */
    public List<Record> rows(String name) {
        return read(IcebergGenerics.read(table(name)).build());
    }

    /** Returns the rows of a snapshot of a table, read by Iceberg Java with the schema of that snapshot. */
    public List<Record> rows(String name, long snapshotId) {
        return read(IcebergGenerics.read(table(name)).useSnapshot(snapshotId).build());
    }

    /**
     * Returns the values of a row in the order of its fields, without the
     * metadata columns such as {@code _pos} that Iceberg Java adds to read
     * position deletes.
     */
    public static List<Object> values(Record row) {
        List<Object> values = new ArrayList<>(row.size());
        List<Types.NestedField> fields = row.struct().fields();
        for (int i = 0; i < row.size(); i++) {
            if (fields.get(i).fieldId() < METADATA_IDS) {
                values.add(row.get(i));
            }
        }
        return values;
    }

    /**
     * Copies the directory of a table to another place, so that the paths
     * in its metadata no longer point to the copy.
     *
     * @return the directory of the copy
     */
    public Path copy(String name, Path target) throws IOException {
        Path source = location(name);
        try (Stream<Path> files = Files.walk(source)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                Path to = target.resolve(source.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(to);
                } else {
                    Files.copy(file, to);
                }
            }
        }
        return target;
    }

    @Override
    public void close() throws IOException {
        catalog.close();
    }

    private static List<Record> read(CloseableIterable<Record> rows) {
        try (rows) {
            List<Record> list = new ArrayList<>();
            rows.forEach(r -> list.add(r.copy()));
            return list;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // The tables.

    private void types() throws IOException {
        Types.StructType struct = Types.StructType.of(
                Types.NestedField.optional(21, "x", Types.IntegerType.get()),
                Types.NestedField.optional(22, "y", Types.StringType.get()));
        Schema schema = new Schema(
                Types.NestedField.optional(1, "b", Types.BooleanType.get()),
                Types.NestedField.optional(2, "i", Types.IntegerType.get()),
                Types.NestedField.optional(3, "l", Types.LongType.get()),
                Types.NestedField.optional(4, "f", Types.FloatType.get()),
                Types.NestedField.optional(5, "d", Types.DoubleType.get()),
                Types.NestedField.optional(6, "dec", Types.DecimalType.of(10, 2)),
                Types.NestedField.optional(7, "dt", Types.DateType.get()),
                Types.NestedField.optional(8, "tm", Types.TimeType.get()),
                Types.NestedField.optional(9, "ts", Types.TimestampType.withoutZone()),
                Types.NestedField.optional(10, "tz", Types.TimestampType.withZone()),
                Types.NestedField.optional(11, "s", Types.StringType.get()),
                Types.NestedField.optional(12, "u", Types.UUIDType.get()),
                Types.NestedField.optional(13, "fx", Types.FixedType.ofLength(4)),
                Types.NestedField.optional(14, "bin", Types.BinaryType.get()),
                Types.NestedField.optional(15, "li", Types.ListType.ofOptional(16, Types.IntegerType.get())),
                Types.NestedField.optional(17, "m",
                        Types.MapType.ofOptional(18, 19, Types.StringType.get(), Types.LongType.get())),
                Types.NestedField.optional(20, "st", struct));
        Table table = create("types", schema, PartitionSpec.unpartitioned(), "2");
        List<Record> rows = new ArrayList<>();
        Record full = GenericRecord.create(schema);
        full.setField("b", true);
        full.setField("i", 1);
        full.setField("l", 10L);
        full.setField("f", 1.5f);
        full.setField("d", 2.5);
        full.setField("dec", new BigDecimal("1.23"));
        full.setField("dt", LocalDate.of(2024, 1, 2));
        full.setField("tm", LocalTime.of(12, 34, 56, 789_000));
        full.setField("ts", LocalDateTime.of(2024, 1, 2, 3, 4, 5, 6_000));
        full.setField("tz", OffsetDateTime.of(2024, 1, 2, 3, 4, 5, 6_000, ZoneOffset.UTC));
        full.setField("s", "a");
        full.setField("u", UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));
        full.setField("fx", new byte[] {'a', 'b', 'c', 'd'});
        full.setField("bin", ByteBuffer.wrap(new byte[] {0, 1}));
        full.setField("li", List.of(1, 2));
        full.setField("m", Map.of("k", 1L));
        Record st = GenericRecord.create(struct);
        st.setField("x", 1);
        st.setField("y", "y");
        full.setField("st", st);
        rows.add(full);
        rows.add(GenericRecord.create(schema));
        Record other = GenericRecord.create(schema);
        other.setField("b", false);
        other.setField("i", -2_000_000_000);
        other.setField("l", Long.MIN_VALUE + 1);
        other.setField("f", -0.25f);
        other.setField("d", 1e300);
        other.setField("dec", new BigDecimal("-99999999.99"));
        other.setField("dt", LocalDate.of(1969, 12, 31));
        other.setField("tm", LocalTime.of(0, 0));
        other.setField("ts", LocalDateTime.of(1900, 1, 1, 0, 0));
        other.setField("tz", OffsetDateTime.of(2024, 3, 31, 1, 30, 0, 0, ZoneOffset.UTC));
        other.setField("s", "\u00E4\u00F6\u00FC \u20AC");
        other.setField("u", UUID.fromString("00000000-0000-0000-0000-000000000001"));
        other.setField("fx", new byte[] {0, 0, 0, 0});
        other.setField("bin", ByteBuffer.wrap(new byte[0]));
        other.setField("li", Arrays.asList(null, 3));
        other.setField("m", Collections.singletonMap("n", null));
        Record st2 = GenericRecord.create(struct);
        st2.setField("y", "z");
        other.setField("st", st2);
        rows.add(other);
        table.newAppend().appendFile(data(table, rows)).commit();
    }

    private void part() throws IOException {
        Schema schema = new Schema(
                Types.NestedField.required(1, "id", Types.LongType.get()),
                Types.NestedField.optional(2, "cat", Types.StringType.get()),
                Types.NestedField.optional(3, "ts", Types.TimestampType.withoutZone()),
                Types.NestedField.optional(4, "v", Types.DoubleType.get()),
                Types.NestedField.optional(5, "name", Types.StringType.get()));
        PartitionSpec spec = PartitionSpec.builderFor(schema).identity("cat").day("ts").bucket("id", 4)
                .truncate("name", 2).build();
        Table table = create("part", schema, spec, "2");
        String[] names = {"alpha", "beta", "gamma"};
        LongFunction<Record> row = n -> {
            Record r = GenericRecord.create(schema);
            r.setField("id", n);
            r.setField("cat", n % 2 == 0 ? "a" : "b");
            r.setField("ts", LocalDateTime.of(2024, 1, 1 + (int) (n % 3), (int) (n % 24), 0));
            r.setField("v", n * 0.5);
            r.setField("name", names[(int) (n % 3)] + n);
            return r;
        };
        append(table, rows(0, 40, row));
        append(table, rows(40, 80, row));
        table.newDelete().deleteFromRowFilter(Expressions.equal("cat", "a")).commit();
        append(table, rows(80, 120, row));
    }

    private void positionDeletes() throws IOException {
        Table table = create("pos_v2", SIMPLE, PartitionSpec.builderFor(SIMPLE).identity("cat").build(), "2");
        DataFile a = data(table, simple(table, 0, 10, "a"));
        DataFile b = data(table, simple(table, 10, 20, "b"));
        table.newAppend().appendFile(a).appendFile(b).commit();
        table.newRowDelta().addDeletes(positions(table, a, 1, 3)).addDeletes(positions(table, b, 0)).commit();
    }

    private void equalityDeletes() throws IOException {
        Table table = create("eq_v2", SIMPLE, PartitionSpec.unpartitioned(), "2");
        table.newAppend().appendFile(data(table, simple(table, 0, 10, "x"))).commit();
        table.newRowDelta().addDeletes(equality(table, 2, 5)).commit();
        table.newAppend().appendFile(data(table, simple(table, 5, 6, "y"))).commit();
    }

    private void deletionVector() throws IOException {
        Table table = create("dv_v3", SIMPLE, PartitionSpec.unpartitioned(), "3");
        DataFile file = data(table, simple(table, 0, 10, "z"));
        table.newAppend().appendFile(file).commit();
        BaseDVFileWriter writer = new BaseDVFileWriter(
                OutputFileFactory.builderFor(table, 1, ++task).format(FileFormat.PUFFIN).build(), path -> null);
        writer.delete(file.location(), 0, table.spec(), null);
        writer.delete(file.location(), 9, table.spec(), null);
        writer.close();
        RowDelta delta = table.newRowDelta();
        writer.result().deleteFiles().forEach(delta::addDeletes);
        delta.commit();
    }

    private void evolution() throws IOException {
        Schema schema = new Schema(
                Types.NestedField.optional(1, "a", Types.IntegerType.get()),
                Types.NestedField.optional(2, "f", Types.FloatType.get()),
                Types.NestedField.optional(3, "c", Types.StringType.get()),
                Types.NestedField.optional(4, "d", Types.DecimalType.of(5, 2)),
                Types.NestedField.optional(5, "b", Types.StringType.get()));
        Table table = create("evo", schema, PartitionSpec.unpartitioned(), "2");
        List<Record> first = new ArrayList<>();
        for (int i = 1; i <= 2; i++) {
            Record r = GenericRecord.create(schema);
            r.setField("a", i);
            r.setField("f", i + 0.5f);
            r.setField("c", "x" + i);
            r.setField("d", new BigDecimal(i + ".25"));
            r.setField("b", "gone" + i);
            first.add(r);
        }
        table.newAppend().appendFile(data(table, first)).commit();
        table.updateSchema().addColumn("e", Types.StringType.get()).renameColumn("c", "c2").deleteColumn("b")
                .updateColumn("a", Types.LongType.get()).updateColumn("f", Types.DoubleType.get())
                .updateColumn("d", Types.DecimalType.of(9, 2)).commit();
        Record second = GenericRecord.create(table.schema());
        second.setField("a", 1L << 40);
        second.setField("f", 0.1);
        second.setField("c2", "y");
        second.setField("d", new BigDecimal("1234567.25"));
        second.setField("e", "new");
        table.newAppend().appendFile(data(table, List.of(second))).commit();
        table.updateSpec().addField("c2").commit();
        Record third = GenericRecord.create(table.schema());
        third.setField("a", 3L);
        third.setField("c2", "z");
        append(table, List.of(third));
    }

    private void version1() throws IOException {
        Table table = create("v1", SIMPLE, PartitionSpec.builderFor(SIMPLE).identity("cat").build(), "1");
        table.newAppend().appendFile(data(table, simple(table, 0, 10, "a")))
                .appendFile(data(table, simple(table, 10, 15, "b"))).commit();
        table.newDelete().deleteFromRowFilter(Expressions.equal("cat", "a")).commit();
        table.newAppend().appendFile(data(table, simple(table, 15, 25, "c"))).commit();
    }

    private void codec(String codec) throws IOException {
        Table table = create("codec_" + codec, SIMPLE, PartitionSpec.unpartitioned(), "2",
                "write.manifest.compression-codec", codec);
        table.newAppend().appendFile(data(table, simple(table, 0, 3, "c"))).commit();
    }

    private void branch() throws IOException {
        Table table = create("branch", SIMPLE, PartitionSpec.unpartitioned(), "2");
        table.newAppend().appendFile(data(table, simple(table, 0, 3, "t"))).commit();
        long tagged = table.currentSnapshot().snapshotId();
        table.newAppend().appendFile(data(table, simple(table, 3, 6, "m"))).commit();
        table.manageSnapshots().createTag("t1", tagged)
                .createBranch("b1", table.currentSnapshot().snapshotId()).commit();
        table.newAppend().appendFile(data(table, simple(table, 6, 8, "b"))).toBranch("b1").commit();
    }

    // Writing.

    private Table create(String name, Schema schema, PartitionSpec spec, String version, String... properties) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("format-version", version);
        map.put("write.parquet.compression-codec", "gzip");
        map.put("write.delete.parquet.compression-codec", "gzip");
        for (int i = 0; i < properties.length; i += 2) {
            map.put(properties[i], properties[i + 1]);
        }
        Table table = catalog.createTable(TableIdentifier.of("db", name), schema, spec, map);
        tables.put(name, table);
        return table;
    }

    private static List<Record> rows(long from, long to, LongFunction<Record> row) {
        List<Record> rows = new ArrayList<>();
        for (long n = from; n < to; n++) {
            rows.add(row.apply(n));
        }
        return rows;
    }

    private static List<Record> simple(Table table, long from, long to, String cat) {
        return rows(from, to, n -> {
            Record r = GenericRecord.create(table.schema());
            r.setField("id", n);
            r.setField("cat", cat);
            r.setField("name", "n" + n);
            return r;
        });
    }

    /** Appends rows in one data file per partition. */
    private void append(Table table, List<Record> rows) throws IOException {
        Map<StructLike, List<Record>> parts = new LinkedHashMap<>();
        PartitionKey key = new PartitionKey(table.spec(), table.schema());
        InternalRecordWrapper wrapper = new InternalRecordWrapper(table.schema().asStruct());
        for (Record row : rows) {
            key.partition(wrapper.wrap(row));
            parts.computeIfAbsent(key.copy(), k -> new ArrayList<>()).add(row);
        }
        var append = table.newAppend();
        for (List<Record> part : parts.values()) {
            append.appendFile(data(table, part));
        }
        append.commit();
    }

    /** Writes rows of one partition into a data file. */
    private DataFile data(Table table, List<Record> rows) throws IOException {
        StructLike partition = partition(table, rows.get(0));
        OutputFileFactory files = OutputFileFactory.builderFor(table, 1, ++task).format(FileFormat.PARQUET).build();
        DataWriter<Record> writer = factory(table).newDataWriter(partition == null ? files.newOutputFile()
                : files.newOutputFile(table.spec(), partition), table.spec(), partition);
        try (writer) {
            rows.forEach(writer::write);
        }
        return writer.toDataFile();
    }

    private DeleteFile positions(Table table, DataFile file, long... rows) throws IOException {
        OutputFileFactory files = OutputFileFactory.builderFor(table, 1, ++task).format(FileFormat.PARQUET).build();
        PositionDeleteWriter<Record> writer = factory(table).newPositionDeleteWriter(
                files.newOutputFile(table.spec(), file.partition()), table.spec(), file.partition());
        try (writer) {
            for (long row : rows) {
                writer.write(PositionDelete.<Record>create().set(file.location(), row));
            }
        }
        return writer.toDeleteFile();
    }

    private DeleteFile equality(Table table, long... ids) throws IOException {
        OutputFileFactory files = OutputFileFactory.builderFor(table, 1, ++task).format(FileFormat.PARQUET).build();
        EqualityDeleteWriter<Record> writer = factory(table).newEqualityDeleteWriter(files.newOutputFile(),
                table.spec(), null);
        Schema idOnly = table.schema().select("id");
        try (writer) {
            for (long id : ids) {
                Record r = GenericRecord.create(idOnly);
                r.setField("id", id);
                writer.write(r);
            }
        }
        return writer.toDeleteFile();
    }

    private static StructLike partition(Table table, Record row) {
        if (table.spec().isUnpartitioned()) {
            return null;
        }
        PartitionKey key = new PartitionKey(table.spec(), table.schema());
        key.partition(new InternalRecordWrapper(table.schema().asStruct()).wrap(row));
        return key;
    }

    private static GenericFileWriterFactory factory(Table table) {
        GenericFileWriterFactory.Builder builder = new GenericFileWriterFactory.Builder(table)
                .dataFileFormat(FileFormat.PARQUET).deleteFileFormat(FileFormat.PARQUET);
        if (table.schema().findField("id") != null) {
            builder.equalityFieldIds(new int[] {table.schema().findField("id").fieldId()})
                    .equalityDeleteRowSchema(table.schema().select("id"));
        }
        return builder.build();
    }

    private static String slashes(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }

    /** A file IO over the local file system without Hadoop. */
    public static final class LocalIO implements FileIO {

        private static final long serialVersionUID = 1L;

        /** Creates the file IO; the catalog instantiates it by name. */
        public LocalIO() {
        }

        @Override
        public InputFile newInputFile(String path) {
            InputFile file = org.apache.iceberg.Files.localInput(path);
            return new InputFile() {
                @Override
                public long getLength() {
                    return file.getLength();
                }

                @Override
                public SeekableInputStream newStream() {
                    return file.newStream();
                }

                @Override
                public String location() {
                    return path;
                }

                @Override
                public boolean exists() {
                    return file.exists();
                }
            };
        }

        @Override
        public OutputFile newOutputFile(String path) {
            new File(path).getParentFile().mkdirs();
            OutputFile file = org.apache.iceberg.Files.localOutput(path);
            return new OutputFile() {
                @Override
                public PositionOutputStream create() {
                    return file.create();
                }

                @Override
                public PositionOutputStream createOrOverwrite() {
                    return file.createOrOverwrite();
                }

                @Override
                public String location() {
                    return path;
                }

                @Override
                public InputFile toInputFile() {
                    return newInputFile(path);
                }
            };
        }

        @Override
        public void deleteFile(String path) {
            new File(path).delete();
        }
    }
}
