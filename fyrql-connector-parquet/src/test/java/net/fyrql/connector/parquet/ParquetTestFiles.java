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

import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.LocalOutputFile;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.MessageTypeParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Writes Parquet files for the tests, with parquet-java or with DuckDB, and asks DuckDB about them. */
final class ParquetTestFiles {

    private ParquetTestFiles() {
    }

    /** Writes a file of a schema in the notation of parquet-java, with the rows the function adds. */
    static Path write(Path file, String schema, CompressionCodecName codec, Consumer<RowWriter> rows)
            throws IOException {
        return write(file, schema, w -> w.withCompressionCodec(codec), rows);
    }

    /** Writes a file of a schema with the options the function sets and the rows the other one adds. */
    static Path write(Path file, String schema, Consumer<ExampleParquetWriter.Builder> options,
            Consumer<RowWriter> rows) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.deleteIfExists(file);
        MessageType type = MessageTypeParser.parseMessageType(schema);
        ExampleParquetWriter.Builder builder = ExampleParquetWriter.builder(new LocalOutputFile(file))
                .withType(type)
                .withConf(new PlainParquetConfiguration())
                .withCodecFactory(new PureJavaCodecs());
        options.accept(builder);
        try (ParquetWriter<Group> writer = builder.build()) {
            rows.accept(new RowWriter(type, writer));
        }
        return file;
    }

    /** Writes a file of a schema without rows. */
    static Path write(Path file, String schema) throws IOException {
        return write(file, schema, CompressionCodecName.UNCOMPRESSED, w -> {
        });
    }

    /** Adds rows to a file being written. */
    static final class RowWriter {
        private final MessageType type;
        private final ParquetWriter<Group> writer;

        RowWriter(MessageType type, ParquetWriter<Group> writer) {
            this.type = type;
            this.writer = writer;
        }

        /** Writes a row that the function fills. */
        void row(Consumer<Group> fill) {
            Group g = new org.apache.parquet.example.data.simple.SimpleGroup(type);
            fill.accept(g);
            try {
                writer.write(g);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
    }

    /** Runs statements in DuckDB, such as a {@code COPY ... TO} that writes a file. */
    static void duckdb(String... statements) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
            for (String sql : statements) {
                s.execute(sql);
            }
        }
    }

    /** Returns the rows of a query in DuckDB, each value as a string. */
    static List<List<String>> duckdbRows(String sql) throws SQLException {
        List<List<String>> rows = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= n; i++) {
                    row.add(rs.getString(i));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    /** Returns a path as DuckDB reads it in a string literal. */
    static String sql(Path file) {
        return "'" + file.toAbsolutePath().toString().replace('\\', '/').replace("'", "''") + "'";
    }
}
