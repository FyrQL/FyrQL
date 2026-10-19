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

import net.fyrql.connector.Catalog;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The schemas, tables and columns of a database, as its JDBC metadata
 * gives them, read when first asked for and kept until
 * {@link #refresh()}. The default schema of the source, {@code PUBLIC} in
 * H2, {@code public} in PostgreSQL, the database of the URL in MySQL, is
 * listed as {@code main}, so that {@code source.table} names a table of
 * it; the other schemas keep their names, except the schemas of the
 * database itself, such as {@code information_schema}, which are not
 * listed. Tables and views are listed by name, system tables are not. A schema of
 * another name than the default that is called {@code main} is hidden by
 * the default. The number of rows of a table is counted with
 * {@code count(*)} when first asked for and kept as well, and so is its
 * primary key.
 */
final class JdbcCatalog implements Catalog {

    private final String source;
    private final Connections connections;
    private final JdbcDialect dialect;
    private final List<String> configuredSchemas;
    private final String configuredDefault;

    private volatile Schemas schemas;
    private final Map<String, List<String>> tables = new ConcurrentHashMap<>();
    private final Map<List<String>, Columns> columns = new ConcurrentHashMap<>();
    private final Map<List<String>, Long> rowCounts = new ConcurrentHashMap<>();
    private final Map<List<String>, Optional<String>> primaryKeys = new ConcurrentHashMap<>();

    /** The schemas as listed, and the name in the database of the one listed as {@code main}. */
    private record Schemas(List<String> listed, String defaultSchema) {
    }

    /** The columns of a table, and which of them hold instants, see {@link JdbcTypes#withTimeZone}. */
    private record Columns(Schema schema, BitSet withTimeZone) {
    }

    /**
     * @param schemas the schemas to list besides the default, or {@code null} for all
     * @param defaultSchema the schema listed as {@code main}, or {@code null} for the one of the connection
     */
    JdbcCatalog(String source, Connections connections, JdbcDialect dialect, List<String> schemas,
            String defaultSchema) {
        this.source = source;
        this.connections = connections;
        this.dialect = dialect;
        this.configuredSchemas = schemas == null ? null : List.copyOf(schemas);
        this.configuredDefault = defaultSchema;
    }

    @Override
    public List<String> schemas() {
        return schemaList().listed();
    }

    @Override
    public List<String> tables(String schema) {
        String real = databaseSchema(schema);
        if (real == null) {
            return List.of();
        }
        return tables.computeIfAbsent(real, this::readTables);
    }

    @Override
    public Schema schema(TableRef table) {
        return columns(table).schema();
    }

    @Override
    public OptionalLong rowCount(TableRef table) {
        columns(table);
        String real = databaseSchema(table.schema());
        return OptionalLong.of(rowCounts.computeIfAbsent(List.of(real, table.table()),
                k -> withConnection(c -> count(c, table), "count the rows of " + table)));
    }

    private long count(Connection c, TableRef table) throws SQLException {
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT count(*) FROM " + qualifiedName(table))) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /**
     * Returns the name of the column of the primary key of a table, or
     * empty if the table has no primary key or one of several columns.
     */
    Optional<String> primaryKey(TableRef table) {
        columns(table);
        String real = databaseSchema(table.schema());
        return primaryKeys.computeIfAbsent(List.of(real, table.table()), k -> withConnection(
                c -> readPrimaryKey(c, k.get(0), k.get(1)), "read the primary key of " + table));
    }

    private Optional<String> readPrimaryKey(Connection c, String schema, String table) throws SQLException {
        DatabaseMetaData md = c.getMetaData();
        List<String> names = new ArrayList<>();
        try (ResultSet rs = dialect == JdbcDialect.SQLITE ? md.getPrimaryKeys(null, null, table)
                : dialect.catalogsAsSchemas() ? md.getPrimaryKeys(schema, null, table)
                : md.getPrimaryKeys(null, schema, table)) {
            while (rs.next()) {
                names.add(rs.getString("COLUMN_NAME"));
            }
        }
        return names.size() == 1 ? Optional.of(names.get(0)) : Optional.empty();
    }

    /**
     * Returns the columns of a table that hold instants, which the database
     * compares in the time zone of its session, see
     * {@link JdbcTypes#withTimeZone}.
     */
    BitSet withTimeZone(TableRef table) {
        return (BitSet) columns(table).withTimeZone().clone();
    }

    private Columns columns(TableRef table) {
        if (!tables(table.schema()).contains(table.table())) {
            throw new IllegalArgumentException("No table " + table + " in the JDBC source " + source);
        }
        String real = databaseSchema(table.schema());
        return columns.computeIfAbsent(List.of(real, table.table()), k -> readColumns(k.get(0), k.get(1)));
    }

    /** Forgets what was read, so that the next question reads the metadata again. */
    void refresh() {
        schemas = null;
        tables.clear();
        columns.clear();
        rowCounts.clear();
        primaryKeys.clear();
    }

    /** Returns the name in the database of a listed schema, or {@code null} if it is not listed. */
    String databaseSchema(String schema) {
        Schemas s = schemaList();
        if (schema.equals(TableRef.MAIN) && s.defaultSchema() != null) {
            return s.defaultSchema();
        }
        return s.listed().contains(schema) ? schema : null;
    }

    /** Returns the name of a table in the SQL of the database, quoted. */
    String qualifiedName(TableRef table) {
        String real = databaseSchema(table.schema());
        if (real == null) {
            throw new IllegalArgumentException("No schema " + table.schema() + " in the JDBC source " + source);
        }
        return dialect.sql().quoteIdentifier(real) + "." + dialect.sql().quoteIdentifier(table.table());
    }

    private Schemas schemaList() {
        Schemas s = schemas;
        if (s == null) {
            s = withConnection(this::readSchemas, "list the schemas");
            schemas = s;
        }
        return s;
    }

    private Schemas readSchemas(Connection c) throws SQLException {
        String defaultSchema = configuredDefault;
        if (defaultSchema == null) {
            defaultSchema = switch (dialect) {
                case SQLITE -> TableRef.MAIN;
                case MYSQL -> c.getCatalog();
                default -> c.getSchema();
            };
        }
        List<String> all;
        if (configuredSchemas != null) {
            all = configuredSchemas;
        } else if (dialect == JdbcDialect.SQLITE) {
            all = List.of(TableRef.MAIN);
        } else {
            all = new ArrayList<>();
            DatabaseMetaData md = c.getMetaData();
            try (ResultSet rs = dialect.catalogsAsSchemas() ? md.getCatalogs() : md.getSchemas()) {
                while (rs.next()) {
                    String name = rs.getString(dialect.catalogsAsSchemas() ? "TABLE_CAT" : "TABLE_SCHEM");
                    if (name != null && !dialect.isSystemSchema(name)) {
                        all.add(name);
                    }
                }
            }
        }
        List<String> listed = new ArrayList<>();
        if (defaultSchema != null) {
            listed.add(TableRef.MAIN);
        }
        for (String name : all) {
            if (!name.equals(defaultSchema) && !name.equals(TableRef.MAIN) && !listed.contains(name)) {
                listed.add(name);
            }
        }
        return new Schemas(List.copyOf(listed), defaultSchema);
    }

    private List<String> readTables(String schema) {
        return withConnection(c -> {
            List<String> names = new ArrayList<>();
            if (dialect == JdbcDialect.SQLITE) {
                try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                        "SELECT name FROM sqlite_schema WHERE type IN ('table', 'view') AND name NOT LIKE 'sqlite\\_%' "
                                + "ESCAPE '\\' ORDER BY name")) {
                    while (rs.next()) {
                        names.add(rs.getString(1));
                    }
                }
                return List.copyOf(names);
            }
            DatabaseMetaData md = c.getMetaData();
            try (ResultSet rs = dialect.catalogsAsSchemas() ? md.getTables(schema, null, "%", null)
                    : md.getTables(null, pattern(md, schema), "%", null)) {
                while (rs.next()) {
                    String type = String.valueOf(rs.getString("TABLE_TYPE")).toUpperCase(Locale.ROOT);
                    if ((type.contains("TABLE") || type.contains("VIEW")) && !type.startsWith("SYSTEM")) {
                        names.add(rs.getString("TABLE_NAME"));
                    }
                }
            }
            names.sort(null);
            return List.copyOf(names);
        }, "list the tables of " + schema);
    }

    private Columns readColumns(String schema, String table) {
        return withConnection(c -> {
            List<Schema.Field> fields = new ArrayList<>();
            if (dialect == JdbcDialect.SQLITE) {
                try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT name, type, \"notnull\" "
                        + "FROM pragma_table_info(" + dialect.sql().literal(table, DataType.VARCHAR) + ") ORDER BY cid")) {
                    while (rs.next()) {
                        fields.add(new Schema.Field(rs.getString(1), JdbcTypes.sqlite(rs.getString(2)),
                                rs.getInt(3) == 0));
                    }
                }
                return new Columns(new Schema(fields), new BitSet());
            }
            DatabaseMetaData md = c.getMetaData();
            TreeMap<Integer, Schema.Field> byPosition = new TreeMap<>();
            TreeMap<Integer, Boolean> zoned = new TreeMap<>();
            try (ResultSet rs = dialect.catalogsAsSchemas()
                    ? md.getColumns(schema, null, pattern(md, table), "%")
                    : md.getColumns(null, pattern(md, schema), pattern(md, table), "%")) {
                while (rs.next()) {
                    int jdbcType = rs.getInt("DATA_TYPE");
                    String typeName = rs.getString("TYPE_NAME");
                    int size = rs.getInt("COLUMN_SIZE");
                    int digits = rs.getInt("DECIMAL_DIGITS");
                    boolean nullable = rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls;
                    DataType type = JdbcTypes.type(dialect, jdbcType, typeName, size, digits);
                    int position = rs.getInt("ORDINAL_POSITION");
                    byPosition.put(position, new Schema.Field(rs.getString("COLUMN_NAME"), type, nullable));
                    zoned.put(position, JdbcTypes.withTimeZone(dialect, jdbcType, typeName));
                }
            }
            BitSet withTimeZone = new BitSet();
            int column = 0;
            for (boolean z : zoned.values()) {
                withTimeZone.set(column++, z);
            }
            return new Columns(new Schema(new ArrayList<>(byPosition.values())), withTimeZone);
        }, "read the columns of " + schema + "." + table);
    }

    /** A name as a pattern of the metadata, with its wildcards escaped. */
    private static String pattern(DatabaseMetaData md, String name) throws SQLException {
        String escape = md.getSearchStringEscape();
        if (escape == null || escape.isEmpty()) {
            return name;
        }
        return name.replace(escape, escape + escape).replace("_", escape + "_").replace("%", escape + "%");
    }

    @FunctionalInterface
    private interface MetadataRead<T> {
        T read(Connection c) throws SQLException;
    }

    private <T> T withConnection(MetadataRead<T> read, String what) {
        try {
            Connection c = connections.open();
            try {
                return read.read(c);
            } finally {
                connections.release(c);
            }
        } catch (SQLException e) {
            throw new JdbcSourceException("The JDBC source " + source + " cannot " + what + " of "
                    + connections.describe() + ": " + e.getMessage(), e);
        }
    }
}
