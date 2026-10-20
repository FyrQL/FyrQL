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

import net.fyrql.connector.ColumnPath;
import net.fyrql.connector.ConnectorFactory;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParquetConnectorTest {

    @TempDir
    Path dir;

    /** Lets DuckDB write a file of {@code n} rows: {@code id BIGINT} from {@code from} and {@code name VARCHAR}. */
    private Path rows(Path file, long from, long n) throws SQLException, IOException {
        Files.createDirectories(file.getParent());
        ParquetTestFiles.duckdb("COPY (SELECT range AS id, 'n' || range AS name FROM range(" + from + ", " + (from + n)
                + ")) TO " + ParquetTestFiles.sql(file) + " (FORMAT parquet)");
        return file;
    }

    private static TableRef ref(String table) {
        return TableRef.of("pq", table);
    }

    @Test
    void tablesAreFilesDirectoriesAndPatterns() throws Exception {
        rows(dir.resolve("orders.parquet"), 0, 1000);
        rows(dir.resolve("parts/p1.parquet"), 0, 10);
        rows(dir.resolve("parts/p2.parquet"), 10, 20);
        rows(dir.resolve("parts/q1.parquet"), 30, 5);
        Files.writeString(dir.resolve("notes.txt"), "ignored");
        Files.createDirectories(dir.resolve("empty"));
        ParquetConnector c = ParquetConnector.builder("pq")
                .directory(dir)
                .path("one", dir.resolve("parts/p2.parquet"))
                .path("ps", dir.resolve("parts") + "/p*.parquet")
                .path("single", dir.resolve("parts") + "/?1.parquet")
                .build();
        assertEquals(List.of("orders", "parts", "one", "ps", "single"), c.catalog().tables("main"));
        assertEquals(List.of(), c.catalog().tables("other"));
        assertEquals(List.of("main"), c.catalog().schemas());
        Schema orders = c.catalog().schema(ref("orders"));
        assertEquals(List.of(DataType.BIGINT, DataType.VARCHAR), orders.types());
        assertEquals(List.of("id", "name"), orders.fields().stream().map(Schema.Field::name).toList());
        assertEquals(OptionalLong.of(1000), c.catalog().rowCount(ref("orders")));
        assertEquals(OptionalLong.of(35), c.catalog().rowCount(ref("parts")));
        assertEquals(OptionalLong.of(20), c.catalog().rowCount(ref("one")));
        assertEquals(OptionalLong.of(30), c.catalog().rowCount(ref("ps")));
        assertEquals(OptionalLong.of(15), c.catalog().rowCount(ref("single")));
        assertEquals("No table pq.main.nope in the Parquet source pq", assertThrows(IllegalArgumentException.class,
                () -> c.catalog().schema(ref("nope"))).getMessage());

        ScanPlan plan = c.plan(ref("orders"), new ScanRequest(List.of(ColumnPath.whole(1)), List.of(),
                OptionalLong.of(5), List.of(), java.util.Optional.empty()));
        assertEquals(List.of(DataType.VARCHAR), plan.output().types());
        assertEquals(List.of(), plan.acceptedFilters());
        assertTrue(plan.limitAccepted());
    }

    @Test
    void theFilesOfATableShareTheirColumns() throws Exception {
        rows(dir.resolve("mixed/a.parquet"), 0, 3);
        ParquetTestFiles.duckdb("COPY (SELECT 1 AS id, 2.5 AS name) TO "
                + ParquetTestFiles.sql(dir.resolve("mixed/b.parquet")) + " (FORMAT parquet)");
        String message = assertThrows(IllegalArgumentException.class,
                () -> ParquetConnector.builder("pq").path("m", dir.resolve("mixed")).build()).getMessage();
        assertTrue(message.startsWith("The table m of the Parquet source pq: The files "), message);
        assertTrue(message.contains("a.parquet and ") && message.contains("b.parquet have different columns:"
                + " (id BIGINT, name VARCHAR) and (id INTEGER, name DECIMAL(2,1))"), message);

        ParquetTestFiles.write(dir.resolve("nullable/a.parquet"), "message m { required int64 id; }");
        ParquetTestFiles.write(dir.resolve("nullable/b.parquet"), "message m { optional int64 id; }");
        ParquetConnector c = ParquetConnector.builder("pq").path("n", dir.resolve("nullable")).build();
        assertTrue(c.catalog().schema(ref("n")).nullable(0));
        ParquetConnector only = ParquetConnector.builder("pq").path("r", dir.resolve("nullable/a.parquet")).build();
        assertEquals(false, only.catalog().schema(ref("r")).nullable(0));
        assertEquals(OptionalLong.of(0), only.catalog().rowCount(ref("r")));
    }

    @Test
    void locationsThatNameNoParquetFileAreErrors() throws Exception {
        Files.writeString(dir.resolve("text.parquet"), "not parquet");
        Files.write(dir.resolve("zero.parquet"), new byte[0]);
        Files.createDirectories(dir.resolve("empty"));
        assertMessage("The table t of the Parquet source pq: Cannot read the Parquet file "
                + dir.resolve("text.parquet").toAbsolutePath(), dir.resolve("text.parquet").toString());
        assertMessage("The table t of the Parquet source pq: Cannot read the Parquet file "
                + dir.resolve("zero.parquet").toAbsolutePath(), dir.resolve("zero.parquet").toString());
        assertMessage("The table t of the Parquet source pq: No Parquet files in the directory "
                + dir.resolve("empty").toAbsolutePath(), dir.resolve("empty").toString());
        assertMessage("The table t of the Parquet source pq: No files found that match the pattern \""
                + dir.resolve("none.parquet").toAbsolutePath() + "\"", dir.resolve("none.parquet").toString());
        assertMessage("The table t of the Parquet source pq: No files found that match the pattern \""
                + dir.toAbsolutePath() + java.io.File.separator + "x*.parquet\"", dir + "/x*.parquet");
        assertMessage("The table t of the Parquet source pq: A pattern is only supported in the name of a file: ",
                dir + "/p*/x.parquet");
        assertEquals("A table name may not start with #: #1", assertThrows(IllegalArgumentException.class,
                () -> ParquetConnector.builder("pq").path("#1", dir)).getMessage());
    }

    private static void assertMessage(String start, String location) {
        String message = assertThrows(IllegalArgumentException.class,
                () -> ParquetConnector.builder("pq").path("t", location).build()).getMessage();
        assertTrue(message.startsWith(start), message);
    }

    @Test
    void tableFunctionsGiveTheSameTableForUnchangedFiles() throws Exception {
        Path file = rows(dir.resolve("t.parquet"), 0, 7);
        ParquetConnector c = ParquetConnector.builder("pq").build();
        assertEquals(List.of("read_parquet", "parquet_scan"), c.tableFunctions());
        assertEquals(List.of(), c.catalog().tables("main"));
        TableRef a = c.bind("read_parquet", List.of(file.toString()), Map.of());
        assertEquals("#1", a.table());
        assertEquals(a, c.bind("parquet_scan", List.of(file.toString()), Map.of()));
        assertEquals(OptionalLong.of(7), c.catalog().rowCount(a));
        assertEquals(List.of(), c.catalog().tables("main"));

        rows(file, 0, 9);
        Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 2000));
        TableRef b = c.bind("read_parquet", List.of(file.toString()), Map.of());
        assertNotEquals(a, b);
        assertEquals(OptionalLong.of(9), c.catalog().rowCount(b));

        rows(dir.resolve("more/x1.parquet"), 0, 2);
        rows(dir.resolve("more/x2.parquet"), 0, 3);
        TableRef pattern = c.bind("read_parquet", List.of(dir.resolve("more") + "/x*.parquet"), Map.of());
        assertEquals(OptionalLong.of(5), c.catalog().rowCount(pattern));
        assertEquals(pattern, c.bind("read_parquet", List.of(dir.resolve("more").toString()), Map.of()));

        Map<String, Object> named = new LinkedHashMap<>();
        named.put("binary_as_string", true);
        assertEquals("Invalid named parameter \"binary_as_string\" for function read_parquet; it has none",
                assertThrows(IllegalArgumentException.class, () -> c.bind("read_parquet", List.of(file.toString()),
                        named)).getMessage());
        assertEquals("read_parquet takes the path of a file, a directory or a pattern as its argument",
                assertThrows(IllegalArgumentException.class, () -> c.bind("read_parquet", List.of(1), Map.of()))
                        .getMessage());
        assertThrows(IllegalArgumentException.class, () -> c.bind("read_csv", List.of(file.toString()), Map.of()));
        String message = assertThrows(IllegalArgumentException.class, () -> c.bind("read_parquet",
                List.of(dir.resolve("missing.parquet").toString()), Map.of())).getMessage();
        assertTrue(message.startsWith("No files found that match the pattern"), message);
    }

    @Test
    void theFactoryReadsTheOptions() throws Exception {
        rows(dir.resolve("data/a.parquet"), 0, 4);
        rows(dir.resolve("data/b.parquet"), 0, 6);
        ConnectorFactory factory = ServiceLoader.load(ConnectorFactory.class).stream()
                .map(ServiceLoader.Provider::get).filter(f -> f.type().equals("parquet")).findFirst().orElseThrow();
        ParquetConnector c = assertInstanceOf(ParquetConnector.class, factory.create("pq", Map.of(
                "directory", "data",
                "tables.both", "data/*.parquet",
                "tables.first.path", "data/a.parquet",
                "batchSize", "100"), dir));
        assertEquals(List.of("a", "b", "both", "first"), c.catalog().tables("main"));
        assertEquals(OptionalLong.of(10), c.catalog().rowCount(ref("both")));
        assertEquals(OptionalLong.of(4), c.catalog().rowCount(ref("first")));
        assertEquals(100, c.batchSize());
        assertEquals("The Parquet source pq has an unknown option files; known are batchSize, readAhead, directory,"
                + " tables.<name>, tables.<name>.path", assertThrows(IllegalArgumentException.class,
                        () -> factory.create("pq", Map.of("files", "x"), dir)).getMessage());
        assertEquals(List.of(), factory.create("pq", Map.of(), dir).catalog().tables("main"));
    }
}
