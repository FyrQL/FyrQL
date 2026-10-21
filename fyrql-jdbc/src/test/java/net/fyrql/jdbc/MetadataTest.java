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

package net.fyrql.jdbc;

import net.fyrql.FyrQL;
import net.fyrql.connector.BatchReader;
import net.fyrql.connector.Catalog;
import net.fyrql.connector.Connector;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.connector.Split;
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetadataTest {

    /**
     * A source with fixed schemas and tables of one column {@code id},
     * which counts how often its catalog is read and fails if it is given
     * an error; it cannot be scanned.
     */
    private static final class Stub implements Connector {
        private final String name;
        private final Map<String, List<String>> tables;
        private final RuntimeException failure;
        final AtomicInteger catalogs = new AtomicInteger();

        Stub(String name, Map<String, List<String>> tables, RuntimeException failure) {
            this.name = name;
            this.tables = tables;
            this.failure = failure;
        }

        Stub(String name, String schema, String... tables) {
            this(name, Map.of(schema, List.of(tables)), null);
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Catalog catalog() {
            catalogs.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            return new Catalog() {
                @Override
                public List<String> schemas() {
                    return List.copyOf(tables.keySet());
                }

                @Override
                public List<String> tables(String schema) {
                    return tables.getOrDefault(schema, List.of());
                }

                @Override
                public Schema schema(TableRef table) {
                    return Schema.of(new Schema.Field("id", DataType.INTEGER));
                }
            };
        }

        @Override
        public ScanPlan plan(TableRef table, ScanRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Split> splits(ScanPlan plan) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BatchReader open(Split split) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * The columns of each result set as the JDBC specification lists them,
     * written out apart from the driver: a name, and a type unless it is
     * text.
     */
    private static final Map<String, String> SPECIFICATION = new LinkedHashMap<>();

    static {
        SPECIFICATION.put("getCatalogs", "TABLE_CAT");
        SPECIFICATION.put("getSchemas", "TABLE_SCHEM TABLE_CATALOG");
        SPECIFICATION.put("getTableTypes", "TABLE_TYPE");
        SPECIFICATION.put("getTables", "TABLE_CAT TABLE_SCHEM TABLE_NAME TABLE_TYPE REMARKS TYPE_CAT TYPE_SCHEM"
                + " TYPE_NAME SELF_REFERENCING_COL_NAME REF_GENERATION");
        SPECIFICATION.put("getColumns", "TABLE_CAT TABLE_SCHEM TABLE_NAME COLUMN_NAME DATA_TYPE:int TYPE_NAME"
                + " COLUMN_SIZE:int BUFFER_LENGTH:int DECIMAL_DIGITS:int NUM_PREC_RADIX:int NULLABLE:int REMARKS"
                + " COLUMN_DEF SQL_DATA_TYPE:int SQL_DATETIME_SUB:int CHAR_OCTET_LENGTH:int ORDINAL_POSITION:int"
                + " IS_NULLABLE SCOPE_CATALOG SCOPE_SCHEMA SCOPE_TABLE SOURCE_DATA_TYPE:short IS_AUTOINCREMENT"
                + " IS_GENERATEDCOLUMN");
        SPECIFICATION.put("getColumnPrivileges", "TABLE_CAT TABLE_SCHEM TABLE_NAME COLUMN_NAME GRANTOR GRANTEE"
                + " PRIVILEGE IS_GRANTABLE");
        SPECIFICATION.put("getTablePrivileges", "TABLE_CAT TABLE_SCHEM TABLE_NAME GRANTOR GRANTEE PRIVILEGE"
                + " IS_GRANTABLE");
        SPECIFICATION.put("getBestRowIdentifier", "SCOPE:short COLUMN_NAME DATA_TYPE:int TYPE_NAME COLUMN_SIZE:int"
                + " BUFFER_LENGTH:int DECIMAL_DIGITS:short PSEUDO_COLUMN:short");
        SPECIFICATION.put("getVersionColumns", "SCOPE:short COLUMN_NAME DATA_TYPE:int TYPE_NAME COLUMN_SIZE:int"
                + " BUFFER_LENGTH:int DECIMAL_DIGITS:short PSEUDO_COLUMN:short");
        SPECIFICATION.put("getPrimaryKeys", "TABLE_CAT TABLE_SCHEM TABLE_NAME COLUMN_NAME KEY_SEQ:short PK_NAME");
        String keys = "PKTABLE_CAT PKTABLE_SCHEM PKTABLE_NAME PKCOLUMN_NAME FKTABLE_CAT FKTABLE_SCHEM FKTABLE_NAME"
                + " FKCOLUMN_NAME KEY_SEQ:short UPDATE_RULE:short DELETE_RULE:short FK_NAME PK_NAME"
                + " DEFERRABILITY:short";
        SPECIFICATION.put("getImportedKeys", keys);
        SPECIFICATION.put("getExportedKeys", keys);
        SPECIFICATION.put("getCrossReference", keys);
        SPECIFICATION.put("getTypeInfo", "TYPE_NAME DATA_TYPE:int PRECISION:int LITERAL_PREFIX LITERAL_SUFFIX"
                + " CREATE_PARAMS NULLABLE:short CASE_SENSITIVE:boolean SEARCHABLE:short UNSIGNED_ATTRIBUTE:boolean"
                + " FIXED_PREC_SCALE:boolean AUTO_INCREMENT:boolean LOCAL_TYPE_NAME MINIMUM_SCALE:short"
                + " MAXIMUM_SCALE:short SQL_DATA_TYPE:int SQL_DATETIME_SUB:int NUM_PREC_RADIX:int");
        SPECIFICATION.put("getIndexInfo", "TABLE_CAT TABLE_SCHEM TABLE_NAME NON_UNIQUE:boolean INDEX_QUALIFIER"
                + " INDEX_NAME TYPE:short ORDINAL_POSITION:short COLUMN_NAME ASC_OR_DESC CARDINALITY:long PAGES:long"
                + " FILTER_CONDITION");
        SPECIFICATION.put("getUDTs", "TYPE_CAT TYPE_SCHEM TYPE_NAME CLASS_NAME DATA_TYPE:int REMARKS"
                + " BASE_TYPE:short");
        SPECIFICATION.put("getSuperTypes", "TYPE_CAT TYPE_SCHEM TYPE_NAME SUPERTYPE_CAT SUPERTYPE_SCHEM"
                + " SUPERTYPE_NAME");
        SPECIFICATION.put("getSuperTables", "TABLE_CAT TABLE_SCHEM TABLE_NAME SUPERTABLE_NAME");
        SPECIFICATION.put("getAttributes", "TYPE_CAT TYPE_SCHEM TYPE_NAME ATTR_NAME DATA_TYPE:int ATTR_TYPE_NAME"
                + " ATTR_SIZE:int DECIMAL_DIGITS:int NUM_PREC_RADIX:int NULLABLE:int REMARKS ATTR_DEF"
                + " SQL_DATA_TYPE:int SQL_DATETIME_SUB:int CHAR_OCTET_LENGTH:int ORDINAL_POSITION:int IS_NULLABLE"
                + " SCOPE_CATALOG SCOPE_SCHEMA SCOPE_TABLE SOURCE_DATA_TYPE:short");
        SPECIFICATION.put("getProcedures", "PROCEDURE_CAT PROCEDURE_SCHEM PROCEDURE_NAME RESERVED1 RESERVED2"
                + " RESERVED3 REMARKS PROCEDURE_TYPE:short SPECIFIC_NAME");
        SPECIFICATION.put("getProcedureColumns", "PROCEDURE_CAT PROCEDURE_SCHEM PROCEDURE_NAME COLUMN_NAME"
                + " COLUMN_TYPE:short DATA_TYPE:int TYPE_NAME PRECISION:int LENGTH:int SCALE:short RADIX:short"
                + " NULLABLE:short REMARKS COLUMN_DEF SQL_DATA_TYPE:int SQL_DATETIME_SUB:int CHAR_OCTET_LENGTH:int"
                + " ORDINAL_POSITION:int IS_NULLABLE SPECIFIC_NAME");
        SPECIFICATION.put("getClientInfoProperties", "NAME MAX_LEN:int DEFAULT_VALUE DESCRIPTION");
        SPECIFICATION.put("getFunctions", "FUNCTION_CAT FUNCTION_SCHEM FUNCTION_NAME REMARKS FUNCTION_TYPE:short"
                + " SPECIFIC_NAME");
        SPECIFICATION.put("getFunctionColumns", "FUNCTION_CAT FUNCTION_SCHEM FUNCTION_NAME COLUMN_NAME"
                + " COLUMN_TYPE:short DATA_TYPE:int TYPE_NAME PRECISION:int LENGTH:int SCALE:short RADIX:short"
                + " NULLABLE:short REMARKS CHAR_OCTET_LENGTH:int ORDINAL_POSITION:int IS_NULLABLE SPECIFIC_NAME");
        SPECIFICATION.put("getPseudoColumns", "TABLE_CAT TABLE_SCHEM TABLE_NAME COLUMN_NAME DATA_TYPE:int"
                + " COLUMN_SIZE:int DECIMAL_DIGITS:int NUM_PREC_RADIX:int COLUMN_USAGE REMARKS CHAR_OCTET_LENGTH:int"
                + " IS_NULLABLE");
    }

    /** Returns a result set of each method of the specification, with arguments that match everything. */
    private static Map<String, ResultSet> resultSets(DatabaseMetaData md) throws SQLException {
        Map<String, ResultSet> r = new LinkedHashMap<>();
        r.put("getCatalogs", md.getCatalogs());
        r.put("getSchemas", md.getSchemas());
        r.put("getTableTypes", md.getTableTypes());
        r.put("getTables", md.getTables(null, null, null, null));
        r.put("getColumns", md.getColumns(null, null, null, null));
        r.put("getColumnPrivileges", md.getColumnPrivileges(null, null, "t", null));
        r.put("getTablePrivileges", md.getTablePrivileges(null, null, null));
        r.put("getBestRowIdentifier", md.getBestRowIdentifier(null, null, "t", DatabaseMetaData.bestRowSession,
                true));
        r.put("getVersionColumns", md.getVersionColumns(null, null, "t"));
        r.put("getPrimaryKeys", md.getPrimaryKeys(null, null, "t"));
        r.put("getImportedKeys", md.getImportedKeys(null, null, "t"));
        r.put("getExportedKeys", md.getExportedKeys(null, null, "t"));
        r.put("getCrossReference", md.getCrossReference(null, null, "t", null, null, "t"));
        r.put("getTypeInfo", md.getTypeInfo());
        r.put("getIndexInfo", md.getIndexInfo(null, null, "t", false, true));
        r.put("getUDTs", md.getUDTs(null, null, null, null));
        r.put("getSuperTypes", md.getSuperTypes(null, null, null));
        r.put("getSuperTables", md.getSuperTables(null, null, null));
        r.put("getAttributes", md.getAttributes(null, null, null, null));
        r.put("getProcedures", md.getProcedures(null, null, null));
        r.put("getProcedureColumns", md.getProcedureColumns(null, null, null, null));
        r.put("getClientInfoProperties", md.getClientInfoProperties());
        r.put("getFunctions", md.getFunctions(null, null, null));
        r.put("getFunctionColumns", md.getFunctionColumns(null, null, null, null));
        r.put("getPseudoColumns", md.getPseudoColumns(null, null, null, null));
        return r;
    }

    /** Returns the columns of a result set in the notation of {@link #SPECIFICATION}. */
    private static String layout(ResultSetMetaData md) throws SQLException {
        List<String> columns = new ArrayList<>();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            String type = switch (md.getColumnType(i)) {
                case Types.VARCHAR -> "";
                case Types.INTEGER -> ":int";
                case Types.SMALLINT -> ":short";
                case Types.BIGINT -> ":long";
                case Types.BOOLEAN -> ":boolean";
                default -> ":" + md.getColumnTypeName(i);
            };
            columns.add(md.getColumnLabel(i) + type);
        }
        return String.join(" ", columns);
    }

    private static Connection connect(Connector... sources) {
        // The given sources alone, without those of the formats of files.
        FyrQL.Builder builder = FyrQL.builder().implicitSources(false);
        for (Connector c : sources) {
            builder.register(c);
        }
        return FyrQLDriver.connect(builder.build(), true);
    }

    /** Returns the values of one column of a result set as text; closes the result set. */
    private static List<String> column(ResultSet rs, String label) throws SQLException {
        List<String> values = new ArrayList<>();
        try (rs) {
            while (rs.next()) {
                values.add(rs.getString(label));
            }
        }
        return values;
    }

    /** Returns the tables of a result set of {@code getTables} as {@code source.schema.table}. */
    private static List<String> tables(ResultSet rs) throws SQLException {
        List<String> values = new ArrayList<>();
        try (rs) {
            while (rs.next()) {
                values.add(rs.getString("TABLE_CAT") + "." + rs.getString("TABLE_SCHEM") + "."
                        + rs.getString("TABLE_NAME"));
            }
        }
        return values;
    }

    @Test
    void everyResultSetHasTheColumnsOfTheSpecification() throws SQLException {
        try (Connection c = connect(new ConnectionTest.CountingConnector("shop"))) {
            Map<String, ResultSet> resultSets = resultSets(c.getMetaData());
            assertEquals(SPECIFICATION.keySet(), resultSets.keySet());
            for (Map.Entry<String, ResultSet> e : resultSets.entrySet()) {
                try (ResultSet rs = e.getValue()) {
                    assertEquals(SPECIFICATION.get(e.getKey()), layout(rs.getMetaData()), e.getKey());
                    assertNull(rs.getStatement(), "a result set of metadata has no statement");
                    assertEquals(ResultSet.TYPE_FORWARD_ONLY, rs.getType());
                }
            }
        }
    }

    @Test
    void theCatalogsAreTheSourcesInTheOrderOfTheirRegistration() throws SQLException {
        Stub zeta = new Stub("zeta", "main", "t");
        Stub alpha = new Stub("alpha", Map.of(), new IllegalStateException("alpha is down"));
        try (Connection c = connect(zeta, alpha, new Stub("mid", "main", "t"))) {
            DatabaseMetaData md = c.getMetaData();
            assertEquals(List.of("zeta", "alpha", "mid"), column(md.getCatalogs(), "TABLE_CAT"));
            assertEquals(0, zeta.catalogs.get() + alpha.catalogs.get(), "no source is touched");
            assertEquals("source", md.getCatalogTerm());
            assertEquals(List.of("TABLE"), column(md.getTableTypes(), "TABLE_TYPE"));
        }
    }

    @Test
    void schemasAndTablesAreListedByPatterns() throws SQLException {
        Map<String, List<String>> schemas = new LinkedHashMap<>();
        schemas.put("main", List.of("t1", "tx", "t_x", "tax", "T2"));
        schemas.put("Sales", List.of("orders"));
        try (Connection c = connect(new Stub("pg", schemas, null), new Stub("mem", "main", "t1"))) {
            DatabaseMetaData md = c.getMetaData();
            try (ResultSet rs = md.getSchemas()) {
                List<String> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(rs.getString(2) + "." + rs.getString(1));
                }
                assertEquals(List.of("pg.main", "pg.Sales", "mem.main"), rows);
            }
            assertEquals(List.of("main", "Sales"), column(md.getSchemas("PG", null), "TABLE_SCHEM"),
                    "a catalog matches ignoring case if not exactly");
            assertEquals(List.of("Sales"), column(md.getSchemas(null, "sal%"), "TABLE_SCHEM"));
            assertEquals(List.of(), column(md.getSchemas("", null), "TABLE_SCHEM"), "every schema has a source");
            assertEquals(List.of(), column(md.getSchemas("p%", null), "TABLE_SCHEM"), "a catalog is a name");

            assertEquals(List.of("pg.main.t1", "pg.main.tx", "pg.main.t_x", "pg.main.tax", "pg.main.T2",
                    "pg.Sales.orders", "mem.main.t1"), tables(md.getTables(null, null, "%", null)));
            assertEquals(List.of("pg.main.t1", "pg.main.tx", "mem.main.t1"), tables(md.getTables(null, null, "t_",
                    null)), "T2 matches t_ only ignoring case, and pg.main has exact matches");
            assertEquals(List.of("pg.main.T2"), tables(md.getTables("pg", "main", "t2", null)));
            assertEquals(List.of("pg.main.t_x"), tables(md.getTables("pg", null, "t\\_x", null)));
            assertEquals(List.of("pg.main.t_x", "pg.main.tax"), tables(md.getTables("pg", null, "t_x", null)));
            assertEquals(List.of("pg.Sales.orders"), tables(md.getTables(null, "SALES", null, null)));
            assertEquals(List.of(), tables(md.getTables(null, null, null, new String[] {"VIEW"})));
            assertEquals(7, tables(md.getTables(null, null, null, new String[] {"VIEW", "table", null})).size());
            assertEquals(List.of(), tables(md.getTables("", null, null, null)));
        }
    }

    @Test
    void columnsDescribeEachTypeAsTheResultSetsDo() throws SQLException {
        try (Connection c = connect(AllTypesTable.connector("all"), new ConnectionTest.CountingConnector("shop"))) {
            DatabaseMetaData md = c.getMetaData();
            ResultSetMetaData query;
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT * FROM \"all\".main.t")) {
                query = rs.getMetaData();
                try (ResultSet columns = md.getColumns("all", "main", "t", null)) {
                    for (int i = 1; i <= query.getColumnCount(); i++) {
                        assertTrue(columns.next());
                        String name = query.getColumnLabel(i);
                        assertEquals("all", columns.getString("TABLE_CAT"));
                        assertEquals("main", columns.getString("TABLE_SCHEM"));
                        assertEquals("t", columns.getString("TABLE_NAME"));
                        assertEquals(name, columns.getString("COLUMN_NAME"));
                        assertEquals(i, columns.getInt("ORDINAL_POSITION"));
                        assertEquals(query.getColumnType(i), columns.getInt("DATA_TYPE"), name);
                        assertEquals(query.getColumnTypeName(i), columns.getString("TYPE_NAME"), name);
                        assertEquals(DatabaseMetaData.columnNullable, columns.getInt("NULLABLE"), name);
                        assertEquals("YES", columns.getString("IS_NULLABLE"));
                        assertEquals("NO", columns.getString("IS_AUTOINCREMENT"));
                        assertEquals("NO", columns.getString("IS_GENERATEDCOLUMN"));
                        assertNull(columns.getObject("SOURCE_DATA_TYPE"));
                    }
                    assertFalse(columns.next());
                }
            }
            List<String> sizes = new ArrayList<>();
            try (ResultSet columns = md.getColumns("all", null, null, null)) {
                while (columns.next()) {
                    sizes.add(columns.getString("COLUMN_NAME") + " " + columns.getString("COLUMN_SIZE") + " "
                            + columns.getString("DECIMAL_DIGITS") + " " + columns.getString("NUM_PREC_RADIX") + " "
                            + columns.getString("CHAR_OCTET_LENGTH"));
                }
            }
            String unbounded = String.valueOf(Integer.MAX_VALUE);
            assertEquals(List.of("b 1 null null null", "ti 3 0 10 null", "si 5 0 10 null", "i 10 0 10 null",
                    "bi 19 0 10 null", "hi 39 0 10 null", "f 7 null 10 null", "d 15 null 10 null",
                    "dec 38 10 10 null", "v " + unbounded + " null null " + unbounded,
                    "bl " + unbounded + " null null " + unbounded, "dt 10 0 null null", "ts 26 6 null null",
                    "u 36 null null null", "tm 15 6 null null", "tz 32 6 null null",
                    "iv " + unbounded + " null null null",
                    "li null null null null", "ld null null null null",
                    "lb null null null null", "sa null null null null", "st null null null null",
                    "m null null null null", "ll null null null null"), sizes);

            try (ResultSet columns = md.getColumns("shop", "main", "t", "I%")) {
                assertTrue(columns.next());
                assertEquals("id", columns.getString("COLUMN_NAME"));
                assertEquals(Types.INTEGER, columns.getInt("DATA_TYPE"));
                assertEquals(DatabaseMetaData.columnNoNulls, columns.getInt("NULLABLE"), "a primitive is NOT NULL");
                assertEquals("NO", columns.getString("IS_NULLABLE"));
                assertFalse(columns.next());
            }
            try (ResultSet t = md.getTables("shop", null, "t", null)) {
                assertTrue(t.next());
                assertEquals("2 rows", t.getString("REMARKS"), "the remarks give the number of rows");
                assertEquals("TABLE", t.getString("TABLE_TYPE"));
            }
        }
    }

    @Test
    void theTypesAreListedByTheirCode() throws SQLException {
        try (Connection c = connect()) {
            List<String> rows = new ArrayList<>();
            int previous = Integer.MIN_VALUE;
            try (ResultSet rs = c.getMetaData().getTypeInfo()) {
                while (rs.next()) {
                    int code = rs.getInt("DATA_TYPE");
                    assertTrue(code >= previous, "ordered by DATA_TYPE");
                    previous = code;
                    rows.add(rs.getString("TYPE_NAME") + " " + code + " " + rs.getString("PRECISION") + " "
                            + rs.getString("CREATE_PARAMS") + " " + rs.getShort("MINIMUM_SCALE") + "-"
                            + rs.getShort("MAXIMUM_SCALE") + " " + rs.getBoolean("CASE_SENSITIVE") + " "
                            + rs.getShort("SEARCHABLE") + " " + rs.getString("LITERAL_PREFIX"));
                    assertEquals(DatabaseMetaData.typeNullable, rs.getShort("NULLABLE"));
                }
            }
            assertEquals(List.of(
                    "TINYINT -6 3 null 0-0 false 2 null",
                    "BIGINT -5 19 null 0-0 false 2 null",
                    "BLOB -3 2147483647 null 0-0 false 2 null",
                    "HUGEINT 2 39 null 0-0 false 2 null",
                    "DECIMAL 3 38 precision,scale 0-38 false 2 null",
                    "INTEGER 4 10 null 0-0 false 2 null",
                    "SMALLINT 5 5 null 0-0 false 2 null",
                    "FLOAT 7 7 null 0-0 false 2 null",
                    "DOUBLE 8 15 null 0-0 false 2 null",
                    "VARCHAR 12 2147483647 null 0-0 true 3 '",
                    "BOOLEAN 16 1 null 0-0 false 2 null",
                    "DATE 91 10 null 0-0 false 2 DATE '",
                    "TIME 92 15 null 6-6 false 2 TIME '",
                    "TIMESTAMP 93 26 null 6-6 false 2 TIMESTAMP '",
                    "UUID 1111 36 null 0-0 false 2 null",
                    "INTERVAL 1111 2147483647 null 0-0 false 2 INTERVAL '",
                    "MAP 1111 null null 0-0 false 2 null",
                    "STRUCT 2002 null null 0-0 false 2 null",
                    "LIST 2003 null null 0-0 false 2 null",
                    "TIMESTAMP WITH TIME ZONE 2014 32 null 6-6 false 2 TIMESTAMPTZ '"), rows);
        }
    }

    @Test
    void aFailingSourceFailsOnlyTheCallsThatReadIt() throws SQLException {
        Stub broken = new Stub("broken", Map.of(), new IllegalStateException("The database of broken is down"));
        try (Connection c = connect(new Stub("ok", "main", "t"), broken)) {
            DatabaseMetaData md = c.getMetaData();
            SQLException e = assertThrows(SQLException.class, () -> md.getTables(null, null, null, null));
            assertEquals("58030", e.getSQLState());
            assertEquals("The database of broken is down", e.getMessage());
            assertTrue(e.getCause() instanceof IllegalStateException);
            e = assertThrows(SQLException.class, () -> md.getColumns("broken", null, null, null));
            assertEquals("58030", e.getSQLState());
            assertEquals(List.of("ok.main.t"), tables(md.getTables("ok", null, null, null)));
            assertEquals(List.of("ok", "broken"), column(md.getCatalogs(), "TABLE_CAT"));
            assertEquals(List.of("id"), column(md.getColumns("ok", "main", "t", null), "COLUMN_NAME"));
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT 1")) {
                assertTrue(rs.next(), "the connection still runs queries");
            }
        }
    }

    @Test
    void theNamesOfTheMetadataNameTheTablesOfQueries() throws SQLException {
        try (Connection c = connect(new ConnectionTest.CountingConnector("shop"))) {
            DatabaseMetaData md = c.getMetaData();
            String quote = md.getIdentifierQuoteString();
            String separator = md.getCatalogSeparator();
            try (ResultSet t = md.getTables(null, null, null, null)) {
                assertTrue(t.next());
                String name = quote + t.getString("TABLE_CAT") + quote + separator + quote
                        + t.getString("TABLE_SCHEM") + quote + "." + quote + t.getString("TABLE_NAME") + quote;
                assertEquals("\"shop\".\"main\".\"t\"", name);
                try (Statement s = c.createStatement()) {
                    assertEquals(List.of("1 a", "2 b"), StatementTest.rows(s.executeQuery("SELECT * FROM " + name)));
                }
            }
        }
    }

    @Test
    void theProductAndItsAbilitiesAreDescribed() throws SQLException {
        try (Connection c = connect()) {
            DatabaseMetaData md = c.getMetaData();
            assertSame(c, md.getConnection());
            assertEquals("jdbc:fyrql:", md.getURL());
            assertEquals("FyrQL", md.getDatabaseProductName());
            assertEquals("FyrQL JDBC Driver", md.getDriverName());
            assertEquals(FyrQLDriver.VERSION, md.getDriverVersion());
            assertEquals(FyrQLDriver.VERSION, md.getDatabaseProductVersion());
            assertEquals(new FyrQLDriver().getMajorVersion(), md.getDriverMajorVersion());
            assertEquals(4, md.getJDBCMajorVersion());
            assertEquals(2, md.getJDBCMinorVersion());
            assertFalse(md.isReadOnly());
            assertTrue(md.supportsBatchUpdates());
            assertEquals("\\", md.getSearchStringEscape());
            assertTrue(md.isCatalogAtStart());
            assertTrue(md.supportsCatalogsInDataManipulation());
            assertTrue(md.supportsSchemasInDataManipulation());
            assertFalse(md.supportsTransactions());
            assertEquals(Connection.TRANSACTION_NONE, md.getDefaultTransactionIsolation());
            assertTrue(md.supportsResultSetType(ResultSet.TYPE_FORWARD_ONLY));
            assertFalse(md.supportsResultSetType(ResultSet.TYPE_SCROLL_INSENSITIVE));
            assertTrue(md.supportsResultSetConcurrency(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY));
            assertFalse(md.supportsResultSetConcurrency(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE));
            assertTrue(md.nullsAreSortedAtEnd());
            assertFalse(md.nullsAreSortedHigh());
            assertTrue(md.storesMixedCaseIdentifiers());
            assertEquals(0, md.getMaxColumnsInSelect());
            assertTrue(md.isWrapperFor(DatabaseMetaData.class));
            c.close();
            SQLException e = assertThrows(SQLException.class, md::getCatalogs);
            assertEquals("08003", e.getSQLState());
            assertEquals("08003", assertThrows(SQLException.class, () -> md.getTables(null, null, null, null))
                    .getSQLState());
            assertEquals("FyrQL", md.getDatabaseProductName(), "what does not read the sources still answers");
        }
    }

    @Test
    void theMetadataOfAnOrdinarySourceIsComplete() throws SQLException {
        MemoryConnector memory = MemoryConnector.builder("mem").table("u", Schema.of(
                new Schema.Field("k", DataType.VARCHAR, false), new Schema.Field("n", DataType.decimal(9, 2))),
                List.of()).build();
        try (Connection c = connect(memory)) {
            DatabaseMetaData md = c.getMetaData();
            try (ResultSet rs = md.getColumns(null, null, "U", "%")) {
                assertTrue(rs.next());
                assertEquals("k", rs.getString("COLUMN_NAME"));
                assertEquals(DatabaseMetaData.columnNoNulls, rs.getInt("NULLABLE"));
                assertTrue(rs.next());
                assertEquals("n", rs.getString("COLUMN_NAME"));
                assertEquals("DECIMAL(9,2)", rs.getString("TYPE_NAME"));
                assertEquals(9, rs.getInt("COLUMN_SIZE"));
                assertEquals(2, rs.getShort("DECIMAL_DIGITS"));
                assertEquals(2, rs.getInt("ORDINAL_POSITION"));
                assertFalse(rs.next());
            }
            try (ResultSet rs = md.getTables(null, null, "u", new String[] {"TABLE"})) {
                assertTrue(rs.next());
                assertEquals("0 rows", rs.getString("REMARKS"));
            }
        }
    }
}
