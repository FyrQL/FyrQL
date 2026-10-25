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

package net.fyrql.pgwire;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static net.fyrql.pgwire.TestServer.query;
import static net.fyrql.pgwire.TestServer.strings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The catalogs as pgjdbc reads them through its DatabaseMetaData, and in the forms of DBeaver and Metabase. */
class CatalogQueriesTest {

    private static TestServer server;

    @BeforeAll
    static void start() throws Exception {
        server = new TestServer();
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    private static List<String> column(ResultSet rs, String name) throws SQLException {
        List<String> values = new ArrayList<>();
        while (rs.next()) {
            values.add(rs.getString(name));
        }
        rs.close();
        return values;
    }

    @Test
    void theMetadataOfPgjdbc() throws SQLException {
        Connection c = server.connect("shop", "");
        DatabaseMetaData md = c.getMetaData();
        assertTrue(column(md.getCatalogs(), "TABLE_CAT").containsAll(List.of("mem", "shop")));
        List<String> schemas = column(md.getSchemas(), "TABLE_SCHEM");
        assertTrue(schemas.containsAll(List.of("main", "sales", "pg_catalog", "information_schema")),
                schemas.toString());
        assertEquals(List.of("customers"), column(md.getTables(null, "main", "%", new String[] {"TABLE"}),
                "TABLE_NAME"));
        assertEquals(List.of("orders"), column(md.getTables(null, "sales", "ord%", null), "TABLE_NAME"));
        try (ResultSet rs = md.getColumns(null, "sales", "orders", "%")) {
            List<String> columns = new ArrayList<>();
            while (rs.next()) {
                columns.add(rs.getString("COLUMN_NAME") + " " + rs.getString("TYPE_NAME") + " "
                        + rs.getInt("DATA_TYPE") + " " + rs.getInt("ORDINAL_POSITION"));
            }
            assertEquals(List.of("id int4 " + Types.INTEGER + " 1", "customer int4 " + Types.INTEGER + " 2",
                    "amount numeric " + Types.NUMERIC + " 3"), columns);
        }
        assertFalse(md.getPrimaryKeys(null, "main", "customers").next());
        assertFalse(md.getImportedKeys(null, "sales", "orders").next());
        assertFalse(md.getExportedKeys(null, "main", "customers").next());
        assertFalse(md.getIndexInfo(null, "main", "customers", false, true).next());
        assertTrue(md.getTypeInfo().next());
        md.getFunctions(null, "pg_catalog", "lower").close();
        md.getProcedures(null, null, "%").close();
        md.getTableTypes().close();
        assertFalse(md.getSQLKeywords().isEmpty());
        md.getUDTs(null, null, "%", null).close();
        md.getColumnPrivileges(null, "main", "customers", "%").close();
        md.getTablePrivileges(null, "main", "customers").close();
        md.getBestRowIdentifier(null, "main", "customers", DatabaseMetaData.bestRowSession, true).close();
        md.getVersionColumns(null, "main", "customers").close();
        try (ResultSet rs = c.createStatement().executeQuery("SELECT * FROM sales.orders")) {
            assertEquals("int4", rs.getMetaData().getColumnTypeName(1));
            assertEquals(Types.NUMERIC, rs.getMetaData().getColumnType(3));
        }
    }

    @Test
    void theFormsOfDbeaverAndMetabase() throws SQLException {
        Connection c = server.connect("shop", "");
        // The namespaces with their descriptions, as DBeaver reads them.
        List<List<String>> namespaces = query(c, "SELECT n.oid,n.*,d.description FROM pg_catalog.pg_namespace n"
                + " LEFT OUTER JOIN pg_catalog.pg_description d ON d.objoid=n.oid AND d.objsubid=0"
                + " AND d.classoid='pg_namespace'::regclass ORDER BY nspname");
        assertTrue(namespaces.stream().anyMatch(r -> r.get(2).equals("sales")), namespaces.toString());
        // The tables with their partitions and sizes.
        List<List<String>> classes = query(c, ("select c.oid,c.*,d.description,pg_catalog.pg_get_expr("
                + "c.relpartbound, c.oid) as partition_expr, pg_catalog.pg_get_partkeydef(c.oid) as partition_key from"
                + " pg_catalog.pg_class c left outer join pg_catalog.pg_description d on d.objoid=c.oid and"
                + " d.objsubid=0 and d.classoid='pg_class'::regclass where c.relnamespace=$1 and c.relkind not in"
                + " ('i','I','c')").replace("$1", "(SELECT oid FROM pg_catalog.pg_namespace WHERE nspname = 'sales')"));
        assertEquals(1, classes.size(), classes.toString());
        // The columns, with their types and defaults.
        List<List<String>> attributes = query(c, "select c.relname,a.*,pg_catalog.pg_get_expr(ad.adbin, ad.adrelid,"
                + " true) as def_value,dsc.description,dep.objid from pg_catalog.pg_attribute a inner join"
                + " pg_catalog.pg_class c on (a.attrelid=c.oid) left outer join pg_catalog.pg_attrdef ad on"
                + " (a.attrelid=ad.adrelid and a.attnum = ad.adnum) left outer join pg_catalog.pg_description dsc"
                + " on (c.oid=dsc.objoid and a.attnum = dsc.objsubid) left outer join pg_depend dep on"
                + " dep.refobjid = a.attrelid AND dep.deptype = 'i' and dep.refobjsubid = a.attnum and"
                + " dep.classid = dep.refclassid where NOT attisdropped AND c.relname = 'orders' order by a.attnum");
        assertEquals(List.of("orders", "orders", "orders"), attributes.stream().map(r -> r.get(0)).toList());
        // The tables, views and materialized views that Metabase syncs.
        List<List<String>> tables = query(c, "SELECT schemaname AS schema, tablename AS name, 'TABLE' AS type FROM"
                + " pg_catalog.pg_tables WHERE schemaname !~ '^pg_' AND schemaname <> 'information_schema' AND"
                + " has_table_privilege(quote_ident(schemaname) || '.' || quote_ident(tablename), 'SELECT') UNION"
                + " SELECT schemaname, viewname, 'VIEW' FROM pg_catalog.pg_views WHERE has_schema_privilege("
                + "schemaname, 'USAGE') UNION SELECT schemaname, matviewname, 'MATERIALIZED_VIEW' FROM"
                + " pg_catalog.pg_matviews ORDER BY 1, 2");
        assertEquals(List.of(List.of("main", "customers", "TABLE"), List.of("sales", "orders", "TABLE")), tables);
        List<List<String>> columns = query(c, "SELECT c.column_name AS name, c.udt_name AS database_type,"
                + " c.ordinal_position - 1 AS database_position, c.table_schema AS table_schema, c.table_name AS"
                + " table_name, pk.column_name IS NOT NULL AS pk, COL_DESCRIPTION(CAST(CAST(FORMAT('%I.%I',"
                + " CAST(c.table_schema AS TEXT), CAST(c.table_name AS TEXT)) AS REGCLASS) AS OID),"
                + " c.ordinal_position) AS field_comment FROM \"information_schema\".\"columns\" AS c LEFT JOIN"
                + " (SELECT tc.table_schema, tc.table_name, kc.column_name FROM"
                + " \"information_schema\".\"table_constraints\" AS tc INNER JOIN"
                + " \"information_schema\".\"key_column_usage\" AS kc ON (tc.constraint_name = kc.constraint_name)"
                + " AND (tc.table_schema = kc.table_schema) AND (tc.table_name = kc.table_name) WHERE"
                + " tc.constraint_type = 'PRIMARY KEY') AS pk ON (c.table_schema = pk.table_schema) AND"
                + " (c.table_name = pk.table_name) AND (c.column_name = pk.column_name) WHERE c.table_schema !~"
                + " '^information_schema|catalog_history|pg_' AND c.table_schema IN ('sales') ORDER BY"
                + " table_schema ASC, table_name ASC, database_position ASC");
        assertEquals(List.of(Arrays.asList("id", "int4", "0", "sales", "orders", "f", null),
                Arrays.asList("customer", "int4", "1", "sales", "orders", "f", null),
                Arrays.asList("amount", "numeric", "2", "sales", "orders", "f", null)), columns);
        // The data of Metabase names every column with its schema and table.
        assertEquals(List.of(List.of("11", "7.25"), List.of("10", "5.50")), query(c, "SELECT"
                + " \"sales\".\"orders\".\"id\" AS \"id\", \"sales\".\"orders\".\"amount\" AS \"amount\" FROM"
                + " \"sales\".\"orders\" WHERE \"sales\".\"orders\".\"customer\" = 2 OR"
                + " \"sales\".\"orders\".\"amount\" > 5 ORDER BY \"sales\".\"orders\".\"id\" DESC LIMIT 2"));
        assertEquals("16384", TestServer.value(c, "SELECT CAST(16384 AS OID)"));
        assertTrue(strings(c.createStatement().executeQuery("SELECT * FROM pg_catalog.pg_type WHERE typname"
                + " = 'int4'")).size() == 1);
    }
}
