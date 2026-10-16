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

package net.fyrql.connector.jdbc;

import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.sql.render.SqlRenderer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** A database to test against: an H2 database in memory, or an SQLite file in a temporary directory. */
record TestDatabase(JdbcDialect dialect, String url) {

    private static final AtomicInteger COUNT = new AtomicInteger();

    /** A new, empty database of a dialect that runs in this process. */
    static TestDatabase create(JdbcDialect dialect) {
        return switch (dialect) {
            case H2 -> new TestDatabase(dialect, "jdbc:h2:mem:fyrql" + COUNT.incrementAndGet() + ";DB_CLOSE_DELAY=-1");
            case SQLITE -> {
                try {
                    Path dir = Files.createTempDirectory("fyrql-sqlite");
                    dir.toFile().deleteOnExit();
                    Path file = dir.resolve("test.db");
                    file.toFile().deleteOnExit();
                    yield new TestDatabase(dialect, "jdbc:sqlite:" + file);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            default -> throw new IllegalArgumentException(dialect + " does not run in this process");
        };
    }

    /** A connector over the database that records its statements. */
    JdbcConnector connector(String name) {
        return JdbcConnector.builder(name).url(url).recordStatements(true).build();
    }

    Connection connect() throws SQLException {
        return DriverManager.getConnection(url);
    }

    /** Runs statements. */
    void execute(String... sql) {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            for (String statement : sql) {
                s.execute(statement);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Creates a table under a name, quoted as given, and fills it with rows of Java values. */
    void load(String table, Schema schema, List<Object[]> rows) {
        SqlRenderer renderer = new SqlRenderer(dialect.sql(), t -> {
            throw new UnsupportedOperationException();
        });
        String name = dialect.sql().quoteIdentifier(table);
        List<String> statements = new ArrayList<>();
        statements.add(renderer.createTable(name, schema));
        for (int from = 0; from < rows.size(); from += 500) {
            statements.add(renderer.insert(name, schema, rows.subList(from, Math.min(rows.size(), from + 500))));
        }
        execute(statements.toArray(String[]::new));
    }

    /** A table of every type the database stores exactly, without the ones SQLite does not have. */
    static Schema allTypes(JdbcDialect dialect) {
        List<Schema.Field> fields = new ArrayList<>(List.of(new Schema.Field("id", DataType.INTEGER, false),
                field("b", DataType.BOOLEAN), field("ti", DataType.TINYINT), field("si", DataType.SMALLINT),
                field("i", DataType.INTEGER), field("bi", DataType.BIGINT), field("de", DataType.decimal(10, 2)),
                field("f", DataType.FLOAT), field("d", DataType.DOUBLE), field("s", DataType.VARCHAR),
                field("bl", DataType.BLOB), field("dt", DataType.DATE), field("ts", DataType.TIMESTAMP),
                field("u", DataType.UUID)));
        if (dialect != JdbcDialect.SQLITE) {
            fields.add(field("h", DataType.HUGEINT));
            fields.add(field("dw", DataType.decimal(38, 6)));
        }
        return new Schema(fields);
    }

    /** Three rows of {@link #allTypes}: ordinary values, {@code NULL}s, and extremes. */
    static List<Object[]> allTypesRows(JdbcDialect dialect) {
        boolean wide = dialect != JdbcDialect.SQLITE;
        List<Object[]> rows = new ArrayList<>();
        rows.add(row(wide, 1, true, (byte) 1, (short) 2, 3, 4L, new BigDecimal("5.25"), 1.5f, 2.25, "x'y\\z",
                new byte[] {0, 1, (byte) 0xFF}, 19_723, 1_704_164_645_123_456L,
                UUID.fromString("123e4567-e89b-12d3-a456-426614174000"), new BigInteger("12345678901234567890"),
                new BigDecimal("12345678901234567890123456789012.123456")));
        rows.add(row(wide, 2, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null));
        rows.add(row(wide, 3, false, Byte.MIN_VALUE, Short.MAX_VALUE, Integer.MIN_VALUE, Long.MAX_VALUE,
                new BigDecimal("-99999999.99"), -0.25f, 1e300, "", new byte[0], -719_162, -1L,
                new UUID(0, 0), new BigInteger("-170141183460469231731687303715884105727"),
                new BigDecimal("-0.000001")));
        return rows;
    }

    private static Object[] row(boolean wide, Object... values) {
        if (wide) {
            return values;
        }
        Object[] narrow = new Object[values.length - 2];
        System.arraycopy(values, 0, narrow, 0, narrow.length);
        return narrow;
    }

    private static Schema.Field field(String name, DataType type) {
        return new Schema.Field(name, type, true);
    }
}
