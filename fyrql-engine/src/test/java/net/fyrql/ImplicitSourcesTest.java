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

package net.fyrql;

import net.fyrql.connector.BatchReader;
import net.fyrql.connector.Catalog;
import net.fyrql.connector.Connector;
import net.fyrql.connector.ConnectorFactory;
import net.fyrql.connector.FileReaderProvider;
import net.fyrql.connector.FileWriterProvider;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.connector.Sink;
import net.fyrql.connector.Split;
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.sql.SqlException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The implicit sources of the formats of files: factories with formats
 * come from a class loader over a directory with a service file of their
 * own, so that the other tests of the module do not see them.
 */
class ImplicitSourcesTest {

    /** The connectors the fake factories created, closed or not. */
    private static final List<FakeConnector> CREATED = Collections.synchronizedList(new ArrayList<>());

    @TempDir
    Path dir;

    /**
     * A source without tables that writes the number of rows into a file of
     * the format fake, and reads such a file as that many rows, numbered in
     * the first column.
     */
    static final class FakeConnector implements Connector, FileWriterProvider, FileReaderProvider {
        private final MemoryConnector inner;
        private final AtomicInteger files = new AtomicInteger();
        private volatile boolean closed;

        FakeConnector(String name) {
            this.inner = MemoryConnector.builder(name).build();
            CREATED.add(this);
        }

        @Override
        public String name() {
            return inner.name();
        }

        @Override
        public Catalog catalog() {
            return inner.catalog();
        }

        @Override
        public ScanPlan plan(TableRef table, ScanRequest request) {
            return inner.plan(table, request);
        }

        @Override
        public List<Split> splits(ScanPlan plan) {
            return inner.splits(plan);
        }

        @Override
        public BatchReader open(Split split) {
            return inner.open(split);
        }

        @Override
        public List<String> fileFormats() {
            return List.of("fake");
        }

        @Override
        public Sink openFile(String format, Path file, Schema schema, Map<String, String> options) {
            return new Sink() {
                private long rows;

                @Override
                public void write(Batch batch) {
                    rows += batch.rowCount();
                }

                @Override
                public long commit() {
                    try {
                        Files.writeString(file, Long.toString(rows), StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    return rows;
                }

                @Override
                public void close() {
                }
            };
        }

        @Override
        public TableRef fileTable(String format, Path file, Schema schema, Map<String, String> options) {
            long rows;
            try {
                rows = Long.parseLong(Files.readString(file, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalArgumentException("Cannot read " + file, e);
            }
            List<Object[]> values = new ArrayList<>();
            for (long r = 1; r <= rows; r++) {
                Object[] row = new Object[schema.size()];
                row[0] = r;
                values.add(row);
            }
            TableRef table = TableRef.of(inner.name(), "file" + files.incrementAndGet());
            try (Sink sink = inner.writer().orElseThrow().create(table, schema)) {
                sink.write(Batch.ofRows(schema, values));
                sink.commit();
            }
            return table;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /** A source that writes files of the format fake, but reads none. */
    static final class WriterOnly implements Connector, FileWriterProvider {
        private final FakeConnector inner;

        WriterOnly(FakeConnector inner) {
            this.inner = inner;
        }

        @Override
        public String name() {
            return inner.name();
        }

        @Override
        public Catalog catalog() {
            return inner.catalog();
        }

        @Override
        public ScanPlan plan(TableRef table, ScanRequest request) {
            return inner.plan(table, request);
        }

        @Override
        public List<Split> splits(ScanPlan plan) {
            return inner.splits(plan);
        }

        @Override
        public BatchReader open(Split split) {
            return inner.open(split);
        }

        @Override
        public List<String> fileFormats() {
            return inner.fileFormats();
        }

        @Override
        public Sink openFile(String format, Path file, Schema schema, Map<String, String> options) {
            return inner.openFile(format, file, schema, options);
        }

        @Override
        public void close() {
            inner.close();
        }
    }

    /** Creates sources of the type fake with the format fake. */
    public static final class FakeFactory implements ConnectorFactory {
        @Override
        public String type() {
            return "fake";
        }

        @Override
        public List<String> fileFormats() {
            return List.of("FAKE");
        }

        @Override
        public Connector create(String name, Map<String, String> options, Path directory) {
            return new FakeConnector(name);
        }
    }

    /** A factory whose sources cannot be created; it comes after fake. */
    public static final class FaultyFactory implements ConnectorFactory {
        @Override
        public String type() {
            return "faulty";
        }

        @Override
        public List<String> fileFormats() {
            return List.of("faulty");
        }

        @Override
        public Connector create(String name, Map<String, String> options, Path directory) {
            throw new IllegalArgumentException("no way");
        }
    }

    /** A factory without formats of files, which never becomes an implicit source. */
    public static final class PlainFactory implements ConnectorFactory {
        @Override
        public String type() {
            return "plain";
        }

        @Override
        public Connector create(String name, Map<String, String> options, Path directory) {
            return MemoryConnector.builder(name).build();
        }
    }

    @BeforeEach
    void forget() {
        CREATED.clear();
    }

    private ClassLoader loader(Class<?>... factories) throws IOException {
        Path services = Files.createDirectories(dir.resolve("classes/META-INF/services"));
        StringBuilder text = new StringBuilder();
        for (Class<?> f : factories) {
            text.append(f.getName()).append('\n');
        }
        Files.writeString(services.resolve(ConnectorFactory.class.getName()), text, StandardCharsets.UTF_8);
        return new URLClassLoader(new URL[] {dir.resolve("classes").toUri().toURL()},
                ImplicitSourcesTest.class.getClassLoader());
    }

    private static MemoryConnector memory(String name) {
        return MemoryConnector.builder(name).build();
    }

    private static String fails(FyrQL engine, String sql) {
        return assertThrows(SqlException.class, () -> engine.update(sql), sql).reason();
    }

    @Test
    void aFactoryWithFormatsBecomesASourceAfterTheRegisteredOnes() throws IOException {
        ClassLoader loader = loader(PlainFactory.class, FakeFactory.class);
        FakeConnector fake;
        try (FyrQL engine = FyrQL.builder().register(memory("mem")).classLoader(loader).build()) {
            assertEquals(List.of("mem", "fake"), engine.sources());
            assertEquals("mem", engine.catalog().searchPath().defaultSource());
            assertEquals(List.of("fake"), engine.catalog().fileFormats());
            Path file = dir.resolve("n.fake");
            assertEquals(3, engine.update("COPY (SELECT * FROM (VALUES (1), (2), (3)) v(n)) TO '" + file
                    + "' (FORMAT fake)"));
            assertEquals("3", Files.readString(file));
            assertEquals(1, CREATED.size());
            fake = CREATED.get(0);
            assertEquals("fake", fake.name());
            assertFalse(fake.closed);
        }
        assertTrue(fake.closed);
    }

    @Test
    void anEngineWithoutRegisteredSourcesHasTheImplicitOnesWithoutADefault() throws IOException {
        try (FyrQL engine = FyrQL.builder().classLoader(loader(FakeFactory.class)).build()) {
            assertEquals(List.of("fake"), engine.sources());
            assertEquals(null, engine.catalog().searchPath().defaultSource());
        }
    }

    @Test
    void theyCanBeTurnedOff() throws IOException {
        try (FyrQL engine = FyrQL.builder().register(memory("mem")).classLoader(loader(FakeFactory.class))
                .implicitSources(false).build()) {
            assertEquals(List.of("mem"), engine.sources());
            assertEquals("No source writes files of the format fake",
                    fails(engine, "COPY (SELECT 1) TO '" + dir.resolve("x.fake") + "' (FORMAT fake)"));
        }
        assertTrue(CREATED.isEmpty());
    }

    @Test
    void aRegisteredSourceWithTheFormatOrTheNameComesInstead() throws IOException {
        ClassLoader loader = loader(FakeFactory.class);
        FakeConnector own = new FakeConnector("files");
        try (FyrQL engine = FyrQL.builder().register(memory("mem")).register(own).classLoader(loader).build()) {
            assertEquals(List.of("mem", "files"), engine.sources());
            assertEquals(own, engine.catalog().fileWriter("fake").orElseThrow());
        }
        try (FyrQL engine = FyrQL.builder().register(memory("fake")).register(memory("mem")).classLoader(loader)
                .build()) {
            assertEquals(List.of("fake", "mem"), engine.sources());
            assertTrue(engine.catalog().fileFormats().isEmpty());
        }
        assertEquals(List.of(own), CREATED);
    }

    @Test
    void aRegisteredSourceThatOnlyWritesTheFormatDoesNotComeInstead() throws IOException {
        WriterOnly writer = new WriterOnly(new FakeConnector("files"));
        try (FyrQL engine = FyrQL.builder().register(writer).classLoader(loader(FakeFactory.class)).build()) {
            assertEquals(List.of("files", "fake"), engine.sources());
            assertEquals(writer, engine.catalog().fileWriter("fake").orElseThrow());
            assertEquals(engine.connector("fake"), engine.catalog().fileReader("fake").orElseThrow());
        }
    }

    @Test
    void anImplicitSourceReadsFilesWithGivenColumns() throws IOException {
        try (FyrQL engine = FyrQL.builder().register(memory("mem")).classLoader(loader(FakeFactory.class)).build()) {
            assertEquals(List.of("fake"), engine.catalog().readFormats());
            Path file = dir.resolve("n.fake");
            Files.writeString(file, "2", StandardCharsets.UTF_8);
            Schema schema = Schema.of(new Schema.Field("n", DataType.BIGINT), new Schema.Field("s", DataType.VARCHAR));
            TableRef table = engine.catalog().fileTable("FAKE", file.toString(), schema, Map.of());
            assertEquals("fake", table.source());
            assertEquals(schema, engine.catalog().schema(table));
            assertEquals(List.of("[1, null]", "[2, null]"), engine.rows("SELECT * FROM fake.main." + table.table())
                    .stream().map(Arrays::toString).toList());

            assertEquals("No source reads files of the format csv; put fyrql-connector-csv on the class path or"
                    + " register a source of the type csv; there are fake", assertThrows(SqlException.class,
                            () -> engine.catalog().fileTable("csv", "x.csv", schema, Map.of())).reason());
            assertEquals("Cannot read " + dir.resolve("none.fake"), assertThrows(SqlException.class,
                    () -> engine.catalog().fileTable("fake", dir.resolve("none.fake").toString(), schema, Map.of()))
                    .reason());
            assertTrue(assertThrows(SqlException.class, () -> engine.catalog().fileTable("fake", "x\0y", schema,
                    Map.of())).reason().startsWith("Not a file: x\0y ("));
        }
    }

    @Test
    void copyFromLoadsAFileIntoATable() throws IOException {
        try (FyrQL engine = FyrQL.builder().register(memory("mem")).classLoader(loader(FakeFactory.class)).build()) {
            Path file = dir.resolve("n.fake");
            Files.writeString(file, "3", StandardCharsets.UTF_8);
            engine.update("CREATE TABLE t (n BIGINT NOT NULL, s VARCHAR, m INTEGER)");
            String copy = "COPY t (n, m) FROM '" + file + "' (FORMAT fake)";
            String plan = engine.explain(copy);
            assertTrue(plan.startsWith("Copy from '" + file + "' into mem.main.t [format: fake]\n  "), plan);
            assertEquals(List.of(), engine.rows("SELECT * FROM t"), "explaining loads nothing");

            assertEquals(3, engine.update(copy));
            assertEquals(List.of("[1, null, null]", "[2, null, null]", "[3, null, null]"),
                    engine.rows("SELECT * FROM t ORDER BY n").stream().map(Arrays::toString).toList());
            try (QueryResult result = engine.sql(copy)) {
                assertEquals(List.of("[3]"), result.rows().stream().map(Arrays::toString).toList());
            }
            PreparedQuery prepared = engine.prepare(copy);
            assertEquals(List.of(), prepared.parameterTypes());
            assertEquals(3, engine.update(prepared, List.of()));
            assertEquals("[9]", Arrays.toString(engine.rows("SELECT count(*) FROM t").get(0)));

            assertEquals("NOT NULL constraint failed: t.n, which the COPY gives no value",
                    fails(engine, "COPY t (s) FROM '" + file + "' (FORMAT fake)"));
            engine.update("CREATE TABLE u (s VARCHAR NOT NULL, n BIGINT)");
            assertEquals("NOT NULL constraint failed: u.s", fails(engine, "COPY u (n, s) FROM '" + file
                    + "' (FORMAT fake)"));
            assertEquals(List.of(), engine.rows("SELECT * FROM u"), "the load is abandoned");
        }
    }

    @Test
    void eachBuildHasItsOwnImplicitSources() throws IOException {
        FyrQL.Builder builder = FyrQL.builder().register(memory("mem")).classLoader(loader(FakeFactory.class));
        try (FyrQL first = builder.build(); FyrQL second = builder.build()) {
            assertEquals(List.of("mem", "fake"), first.sources());
            assertEquals(List.of("mem", "fake"), second.sources());
            assertTrue(first.connector("fake") != second.connector("fake"));
        }
        assertEquals(2, CREATED.size());
        assertTrue(CREATED.stream().allMatch(c -> c.closed));
    }

    @Test
    void theyAreClosedIfTheEngineCannotBeBuilt() throws IOException {
        ClassLoader loader = loader(FakeFactory.class, FaultyFactory.class);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> FyrQL.builder().register(memory("mem")).classLoader(loader).build());
        assertEquals("Cannot create the implicit source faulty: no way", e.getMessage());
        assertEquals(1, CREATED.size());
        assertTrue(CREATED.get(0).closed);
        CREATED.clear();
        assertThrows(IllegalArgumentException.class, () -> FyrQL.builder().register(memory("mem"))
                .defaultSource("nowhere").classLoader(loader(FakeFactory.class)).build());
        assertEquals(1, CREATED.size());
        assertTrue(CREATED.get(0).closed);
    }

    @Test
    void theMessagesNameTheModuleOfAFormat() {
        try (FyrQL engine = FyrQL.builder().register(memory("mem")).build()) {
            assertEquals(List.of("mem"), engine.sources());
            assertEquals("No source writes files of the format csv; put fyrql-connector-csv on the class path or"
                    + " register a source of the type csv", fails(engine, "COPY (SELECT 1) TO 'x.csv'"));
            assertEquals("Table function with name read_parquet does not exist; put fyrql-connector-parquet on"
                    + " the class path or register a source of the type parquet",
                    fails(engine, "SELECT * FROM read_parquet('x.parquet')"));
            assertEquals("Table function with name read_xml does not exist",
                    fails(engine, "SELECT * FROM read_xml('x.xml')"));
        }
    }
}
