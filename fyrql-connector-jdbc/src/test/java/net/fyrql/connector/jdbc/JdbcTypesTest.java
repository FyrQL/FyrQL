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

package net.fyrql.connector.jdbc;

import net.fyrql.core.type.DataType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.util.UUID;

import static net.fyrql.connector.jdbc.JdbcDialect.H2;
import static net.fyrql.connector.jdbc.JdbcDialect.MYSQL;
import static net.fyrql.connector.jdbc.JdbcDialect.POSTGRESQL;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcTypesTest {

    @Test
    void theJdbcTypesMapToFyrQLTypes() {
        assertEquals(DataType.BOOLEAN, JdbcTypes.type(H2, Types.BOOLEAN, "BOOLEAN", 1, 0));
        assertEquals(DataType.BOOLEAN, JdbcTypes.type(POSTGRESQL, Types.BIT, "bool", 1, 0));
        assertEquals(DataType.BOOLEAN, JdbcTypes.type(MYSQL, Types.BIT, "BIT", 1, 0));
        assertEquals(DataType.BLOB, JdbcTypes.type(MYSQL, Types.BIT, "BIT", 8, 0));
        assertEquals(DataType.TINYINT, JdbcTypes.type(H2, Types.TINYINT, "TINYINT", 8, 0));
        assertEquals(DataType.SMALLINT, JdbcTypes.type(POSTGRESQL, Types.SMALLINT, "int2", 5, 0));
        assertEquals(DataType.INTEGER, JdbcTypes.type(POSTGRESQL, Types.INTEGER, "int4", 10, 0));
        assertEquals(DataType.BIGINT, JdbcTypes.type(POSTGRESQL, Types.BIGINT, "int8", 19, 0));
        assertEquals(DataType.FLOAT, JdbcTypes.type(H2, Types.REAL, "REAL", 24, 0));
        assertEquals(DataType.DOUBLE, JdbcTypes.type(H2, Types.DOUBLE, "DOUBLE PRECISION", 53, 0));
        assertEquals(DataType.DOUBLE, JdbcTypes.type(H2, Types.FLOAT, "FLOAT", 53, 0));
        assertEquals(DataType.VARCHAR, JdbcTypes.type(POSTGRESQL, Types.VARCHAR, "text", Integer.MAX_VALUE, 0));
        assertEquals(DataType.VARCHAR, JdbcTypes.type(H2, Types.CLOB, "CHARACTER LARGE OBJECT", 100, 0));
        assertEquals(DataType.BLOB, JdbcTypes.type(POSTGRESQL, Types.BINARY, "bytea", 0, 0));
        assertEquals(DataType.DATE, JdbcTypes.type(H2, Types.DATE, "DATE", 10, 0));
        assertEquals(DataType.TIMESTAMP, JdbcTypes.type(H2, Types.TIMESTAMP, "TIMESTAMP", 26, 6));
        assertEquals(DataType.TIMESTAMP, JdbcTypes.type(POSTGRESQL, Types.TIMESTAMP, "timestamptz", 35, 6));
        assertEquals(DataType.TIMESTAMP,
                JdbcTypes.type(H2, Types.TIMESTAMP_WITH_TIMEZONE, "TIMESTAMP WITH TIME ZONE", 35, 6));
        assertEquals(DataType.UUID, JdbcTypes.type(H2, Types.BINARY, "UUID", 16, 0));
        assertEquals(DataType.UUID, JdbcTypes.type(POSTGRESQL, Types.OTHER, "uuid", 2147483647, 0));
        // What FyrQL has no type for is read as a string.
        assertEquals(DataType.VARCHAR, JdbcTypes.type(H2, Types.TIME, "TIME", 8, 0));
        assertEquals(DataType.VARCHAR, JdbcTypes.type(POSTGRESQL, Types.OTHER, "jsonb", 0, 0));
        assertEquals(DataType.VARCHAR, JdbcTypes.type(POSTGRESQL, Types.ARRAY, "_int4", 10, 0));
    }

    @Test
    void aColumnOfInstantsIsToldByItsType() {
        assertTrue(JdbcTypes.withTimeZone(POSTGRESQL, Types.TIMESTAMP, "timestamptz"));
        assertTrue(JdbcTypes.withTimeZone(H2, Types.TIMESTAMP_WITH_TIMEZONE, "TIMESTAMP WITH TIME ZONE"));
        assertTrue(JdbcTypes.withTimeZone(MYSQL, Types.TIMESTAMP, "TIMESTAMP"));
        assertFalse(JdbcTypes.withTimeZone(MYSQL, Types.TIMESTAMP, "DATETIME"));
        assertFalse(JdbcTypes.withTimeZone(POSTGRESQL, Types.TIMESTAMP, "timestamp"));
        assertFalse(JdbcTypes.withTimeZone(H2, Types.TIMESTAMP, "TIMESTAMP"));
    }

    @Test
    void decimalsKeepTheirPrecisionUpTo38Digits() {
        assertEquals(DataType.decimal(10, 2), JdbcTypes.type(H2, Types.NUMERIC, "NUMERIC", 10, 2));
        assertEquals(DataType.decimal(38, 0), JdbcTypes.type(H2, Types.DECIMAL, "DECIMAL", 38, 0));
        assertEquals(DataType.HUGEINT, JdbcTypes.type(H2, Types.NUMERIC, "NUMERIC", 39, 0));
        assertEquals(DataType.VARCHAR, JdbcTypes.type(H2, Types.NUMERIC, "NUMERIC", 39, 2));
        assertEquals(DataType.VARCHAR, JdbcTypes.type(H2, Types.NUMERIC, "NUMERIC", 100000, 0));
        assertEquals(DataType.DOUBLE, JdbcTypes.type(POSTGRESQL, Types.NUMERIC, "numeric", 0, 0));
        assertEquals(DataType.DOUBLE, JdbcTypes.type(POSTGRESQL, Types.NUMERIC, "numeric", 131089, 0));
    }

    @Test
    void anUnsignedIntegerOfMySqlTakesTheNextWiderType() {
        assertEquals(DataType.SMALLINT, JdbcTypes.type(MYSQL, Types.TINYINT, "TINYINT UNSIGNED", 3, 0));
        assertEquals(DataType.INTEGER, JdbcTypes.type(MYSQL, Types.SMALLINT, "SMALLINT UNSIGNED", 5, 0));
        assertEquals(DataType.BIGINT, JdbcTypes.type(MYSQL, Types.INTEGER, "INT UNSIGNED", 10, 0));
        assertEquals(DataType.BIGINT, JdbcTypes.type(MYSQL, Types.INTEGER, "MEDIUMINT UNSIGNED", 8, 0));
        assertEquals(DataType.HUGEINT, JdbcTypes.type(MYSQL, Types.BIGINT, "BIGINT UNSIGNED", 20, 0));
        assertEquals(DataType.decimal(10, 2), JdbcTypes.type(MYSQL, Types.DECIMAL, "DECIMAL UNSIGNED", 10, 2));
    }

    @Test
    void aColumnOfSqliteHasTheTypeItWasDeclaredWith() {
        assertEquals(DataType.INTEGER, JdbcTypes.sqlite("INT4"));
        assertEquals(DataType.BIGINT, JdbcTypes.sqlite("INTEGER"));
        assertEquals(DataType.BIGINT, JdbcTypes.sqlite("int"));
        assertEquals(DataType.TINYINT, JdbcTypes.sqlite("TINYINT"));
        assertEquals(DataType.BOOLEAN, JdbcTypes.sqlite("BOOLEAN"));
        assertEquals(DataType.decimal(10, 2), JdbcTypes.sqlite("DECIMAL(10,2)"));
        assertEquals(DataType.DOUBLE, JdbcTypes.sqlite("NUMERIC"));
        assertEquals(DataType.DOUBLE, JdbcTypes.sqlite("REAL"));
        assertEquals(DataType.FLOAT, JdbcTypes.sqlite("FLOAT"));
        assertEquals(DataType.VARCHAR, JdbcTypes.sqlite("VARCHAR(20)"));
        assertEquals(DataType.DATE, JdbcTypes.sqlite("DATE"));
        assertEquals(DataType.TIMESTAMP, JdbcTypes.sqlite("DATETIME"));
        assertEquals(DataType.UUID, JdbcTypes.sqlite("UUID"));
        assertEquals(DataType.BLOB, JdbcTypes.sqlite("BLOB"));
        // Names FyrQL does not know go by the affinity SQLite gives them.
        assertEquals(DataType.BIGINT, JdbcTypes.sqlite("UNSIGNED BIG INT"));
        assertEquals(DataType.VARCHAR, JdbcTypes.sqlite("NATIVE CHARACTER(70)"));
        assertEquals(DataType.VARCHAR, JdbcTypes.sqlite("CLOB"));
        assertEquals(DataType.BLOB, JdbcTypes.sqlite("BLOBBY"));
        assertEquals(DataType.DOUBLE, JdbcTypes.sqlite("DOUBLE WIDE"));
        // As in SQLite, "INT" comes first: the name contains it in "POINT".
        assertEquals(DataType.BIGINT, JdbcTypes.sqlite("FLOATING POINT"));
        assertEquals(DataType.VARCHAR, JdbcTypes.sqlite(""));
        assertEquals(DataType.VARCHAR, JdbcTypes.sqlite("JSON"));
    }

    @Test
    void aValueOfSqliteIsReadByItsStorageClass() {
        assertEquals(true, ColumnReaders.sqliteValue(1, DataType.BOOLEAN));
        assertEquals(false, ColumnReaders.sqliteValue(0L, DataType.BOOLEAN));
        assertEquals((byte) -5, ColumnReaders.sqliteValue(-5, DataType.TINYINT));
        assertEquals(7, ColumnReaders.sqliteValue(7L, DataType.INTEGER));
        assertEquals(BigInteger.TEN, ColumnReaders.sqliteValue(10, DataType.HUGEINT));
        assertEquals(new BigDecimal("1.50"), ColumnReaders.sqliteValue(1.5, DataType.decimal(10, 2)));
        assertEquals(new BigDecimal("3.00"), ColumnReaders.sqliteValue(3, DataType.decimal(10, 2)));
        assertEquals(0.1f, ColumnReaders.sqliteValue(0.1, DataType.FLOAT));
        assertEquals(2.0, ColumnReaders.sqliteValue(2, DataType.DOUBLE));
        assertEquals("12", ColumnReaders.sqliteValue(12, DataType.VARCHAR));
        assertEquals(1, ColumnReaders.sqliteValue("1970-01-02", DataType.DATE));
        assertEquals(1_000_000L, ColumnReaders.sqliteValue("1970-01-01 00:00:01", DataType.TIMESTAMP));
        UUID u = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        assertEquals(u, ColumnReaders.sqliteValue(u.toString(), DataType.UUID));
        assertArrayEquals("ab".getBytes(StandardCharsets.UTF_8),
                (byte[]) ColumnReaders.sqliteValue("ab", DataType.BLOB));

        assertThrows(IllegalArgumentException.class, () -> ColumnReaders.sqliteValue("x", DataType.INTEGER));
        assertThrows(IllegalArgumentException.class, () -> ColumnReaders.sqliteValue(1.5, DataType.BIGINT));
        assertThrows(ArithmeticException.class, () -> ColumnReaders.sqliteValue(300, DataType.TINYINT));
        assertThrows(IllegalArgumentException.class, () -> ColumnReaders.sqliteValue(5, DataType.DATE));
    }
}
