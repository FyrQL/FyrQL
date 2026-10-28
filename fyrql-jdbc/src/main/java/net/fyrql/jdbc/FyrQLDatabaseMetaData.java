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

import net.fyrql.catalog.CatalogTables;
import net.fyrql.catalog.NamePattern;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.sql.parse.SqlParser;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.function.Supplier;

/**
 * The metadata of a connection. The catalogs are the sources of the engine
 * in the order of their registration, and each source has its schemas and
 * tables; the tables of the sources are of the type {@code TABLE}, the
 * views of the engine of the type {@code VIEW}, and the remarks of a table
 * give its number of rows if the source knows it. The names of the
 * sources are listed without touching a source; the schemas, tables and
 * columns read only the sources the catalog of the call matches, and an
 * error of a source fails only that call, with {@code 58030} unless it
 * tells a state of its own.
 * <p>
 * A catalog is a name, and the names of schemas, tables and columns are
 * patterns, see {@link NamePattern}: a name matches exactly, or else
 * ignoring case. There are no keys, indexes, procedures, functions,
 * user-defined types or privileges: those result sets are empty, with
 * the columns of the specification. Every method that asks returns a
 * value; nothing throws but a closed connection.
 */
final class FyrQLDatabaseMetaData implements DatabaseMetaData {

    /** The type of a table. */
    private static final String TABLE = "TABLE";
    /** The type of a view. */
    private static final String VIEW = "VIEW";

    private final FyrQLConnection connection;

    FyrQLDatabaseMetaData(FyrQLConnection connection) {
        this.connection = connection;
    }

    /** Reads rows from the catalogs of the sources under the lock of the connection. */
    private ResultSet read(Schema layout, Supplier<List<Object[]>> rows) throws SQLException {
        connection.checkOpen();
        Lock lock = connection.lock();
        lock.lock();
        try {
            return MetadataResultSets.of(layout, rows.get());
        } catch (RuntimeException e) {
            String state = SqlStates.state(e);
            throw SqlStates.create(e.getMessage() != null ? e.getMessage() : e.toString(),
                    state.equals(SqlStates.GENERAL) ? SqlStates.IO_ERROR : state, e);
        } finally {
            lock.unlock();
        }
    }

    private ResultSet empty(Schema layout) throws SQLException {
        connection.checkOpen();
        return MetadataResultSets.empty(layout);
    }

    /** The tables the session of the connection sees, with its temporary tables. */
    private CatalogTables tables() {
        return connection.session().catalogTables();
    }

    /**
     * Returns the tables of exact names, {@code null} for any, that have a
     * primary key, each with the columns of its key and their fields.
     */
    private List<Keyed> keyed(String catalog, String schema, String table) {
        List<Keyed> result = new ArrayList<>();
        CatalogTables all = tables();
        for (CatalogTables.TableInfo t : all.tables(NamePattern.name(catalog), NamePattern.name(schema),
                NamePattern.name(table))) {
            List<String> key = t.view() ? List.of() : all.primaryKey(t.table());
            if (key.isEmpty()) {
                continue;
            }
            List<CatalogTables.ColumnInfo> columns = all.columns(NamePattern.name(t.table().source()),
                    NamePattern.name(t.table().schema()), NamePattern.name(t.table().table()), NamePattern.ANY);
            List<CatalogTables.ColumnInfo> keyColumns = new ArrayList<>();
            for (String k : key) {
                columns.stream().filter(c -> c.field().name().equals(k)).findFirst().ifPresent(keyColumns::add);
            }
            result.add(new Keyed(t.table(), keyColumns));
        }
        return result;
    }

    /** A table with the columns of its primary key, in the order of the key. */
    private record Keyed(TableRef table, List<CatalogTables.ColumnInfo> key) {
        String keyName() {
            return table.table() + "_pkey";
        }
    }

    // ---------------------------------------------------------------- catalogs, schemas, tables, columns

    /** Returns the sources in the order of their registration, without touching one. */
    @Override
    public ResultSet getCatalogs() throws SQLException {
        connection.checkOpen();
        List<Object[]> rows = new ArrayList<>();
        for (String source : tables().catalogs()) {
            rows.add(new Object[] {source});
        }
        return MetadataResultSets.of(MetadataResultSets.CATALOGS, rows);
    }

    /** Returns the schemas of all sources, which reads the catalog of each. */
    @Override
    public ResultSet getSchemas() throws SQLException {
        return getSchemas(null, null);
    }

    @Override
    public ResultSet getSchemas(String catalog, String schemaPattern) throws SQLException {
        return read(MetadataResultSets.SCHEMAS, () -> {
            List<Object[]> rows = new ArrayList<>();
            for (CatalogTables.SchemaInfo s : tables().schemas(NamePattern.name(catalog),
                    NamePattern.of(schemaPattern))) {
                rows.add(new Object[] {s.schema(), s.source()});
            }
            return rows;
        });
    }

    @Override
    public ResultSet getTableTypes() throws SQLException {
        connection.checkOpen();
        return MetadataResultSets.of(MetadataResultSets.TABLE_TYPES, List.<Object[]>of(new Object[] {TABLE},
                new Object[] {VIEW}));
    }

    /** Returns the tables and views; a list of types without {@code TABLE} and {@code VIEW} has none. */
    @Override
    public ResultSet getTables(String catalog, String schemaPattern, String tableNamePattern, String[] types)
            throws SQLException {
        boolean tables = types == null || Arrays.stream(types).anyMatch(TABLE::equalsIgnoreCase);
        boolean views = types == null || Arrays.stream(types).anyMatch(VIEW::equalsIgnoreCase);
        if (!tables && !views) {
            return empty(MetadataResultSets.TABLES);
        }
        return read(MetadataResultSets.TABLES, () -> {
            List<Object[]> rows = new ArrayList<>();
            for (CatalogTables.TableInfo t : tables().tables(NamePattern.name(catalog),
                    NamePattern.of(schemaPattern), NamePattern.of(tableNamePattern))) {
                if (t.view() ? !views : !tables) {
                    continue;
                }
                String remarks = null;
                if (t.rowCount().isPresent()) {
                    long n = t.rowCount().getAsLong();
                    remarks = n + (n == 1 ? " row" : " rows");
                }
                rows.add(new Object[] {t.table().source(), t.table().schema(), t.table().table(),
                    t.view() ? VIEW : TABLE, remarks, null, null, null, null, null});
            }
            return rows;
        });
    }

    @Override
    public ResultSet getColumns(String catalog, String schemaPattern, String tableNamePattern,
            String columnNamePattern) throws SQLException {
        return read(MetadataResultSets.COLUMNS, () -> {
            List<Object[]> rows = new ArrayList<>();
            for (CatalogTables.ColumnInfo c : tables().columns(NamePattern.name(catalog),
                    NamePattern.of(schemaPattern), NamePattern.of(tableNamePattern),
                    NamePattern.of(columnNamePattern))) {
                DataType type = c.field().type();
                boolean nullable = c.field().nullable();
                rows.add(new Object[] {c.table().source(), c.table().schema(), c.table().table(), c.field().name(),
                    JdbcTypes.sqlType(type), type.sqlName(), columnSize(type), null, decimalDigits(type),
                    radix(type), nullable ? columnNullable : columnNoNulls, null, null, null, null,
                    octetLength(type), c.ordinal(), nullable ? "YES" : "NO", null, null, null, null, "NO",
                    "NO"});
            }
            return rows;
        });
    }

    /** Returns the precision of a type, {@code null} for nested types. */
    private static Integer columnSize(DataType type) {
        return type.isNested() ? null : JdbcTypes.precision(type);
    }

    /** Returns the digits after the point, or of the fraction of a second; {@code null} where there are none. */
    private static Integer decimalDigits(DataType type) {
        if (type instanceof DataType.Decimal || type == DataType.TIMESTAMP || type == DataType.TIME
                || type == DataType.TIMESTAMPTZ) {
            return JdbcTypes.scale(type);
        }
        return type.isIntegral() || type == DataType.DATE ? 0 : null;
    }

    private static Integer radix(DataType type) {
        return type.isNumeric() ? 10 : null;
    }

    /** Returns the most bytes of a value of text or bytes, {@code null} for other types. */
    private static Integer octetLength(DataType type) {
        return type == DataType.VARCHAR || type == DataType.BLOB ? JdbcTypes.UNBOUNDED : null;
    }

    /**
     * Returns a row for each scalar type, for {@code DECIMAL} and for the
     * nested types {@code LIST}, {@code STRUCT} and {@code MAP}, ordered by
     * the code of {@link Types}.
     */
    @Override
    public ResultSet getTypeInfo() throws SQLException {
        connection.checkOpen();
        List<Object[]> rows = new ArrayList<>();
        for (DataType.Scalar s : DataType.Scalar.values()) {
            rows.add(typeInfo(s.sqlName(), s));
        }
        rows.add(typeInfo("DECIMAL", new DataType.Decimal(DataType.MAX_DECIMAL_PRECISION, 0)));
        rows.add(typeInfo("LIST", new DataType.ListType(DataType.INTEGER)));
        rows.add(typeInfo("STRUCT", DataType.struct(new DataType.Struct.Field("a", DataType.INTEGER))));
        rows.add(typeInfo("MAP", new DataType.MapType(DataType.VARCHAR, DataType.INTEGER)));
        rows.sort(Comparator.comparingInt(r -> (Integer) r[1]));
        return MetadataResultSets.of(MetadataResultSets.TYPE_INFO, rows);
    }

    private static Object[] typeInfo(String name, DataType type) {
        String prefix = null;
        String suffix = null;
        if (type == DataType.VARCHAR) {
            prefix = "'";
            suffix = "'";
        } else if (type == DataType.DATE || type == DataType.TIMESTAMP || type == DataType.TIME
                || type == DataType.INTERVAL) {
            prefix = type.sqlName() + " '";
            suffix = "'";
        } else if (type == DataType.TIMESTAMPTZ) {
            prefix = "TIMESTAMPTZ '";
            suffix = "'";
        }
        boolean decimal = type instanceof DataType.Decimal;
        short maxScale = (short) (decimal ? DataType.MAX_DECIMAL_PRECISION : JdbcTypes.scale(type));
        return new Object[] {name, JdbcTypes.sqlType(type), type.isNested() ? null : JdbcTypes.precision(type),
            prefix, suffix, decimal ? "precision,scale" : null, (short) typeNullable,
            JdbcTypes.caseSensitive(type), (short) (type == DataType.VARCHAR ? typeSearchable : typePredBasic),
            false, false, false, null, (short) (decimal ? 0 : JdbcTypes.scale(type)), maxScale, null, null,
            radix(type)};
    }

    // ---------------------------------------------------------------- what there is not: empty result sets

    @Override
    public ResultSet getProcedures(String catalog, String schemaPattern, String procedureNamePattern)
            throws SQLException {
        return empty(MetadataResultSets.PROCEDURES);
    }

    @Override
    public ResultSet getProcedureColumns(String catalog, String schemaPattern, String procedureNamePattern,
            String columnNamePattern) throws SQLException {
        return empty(MetadataResultSets.PROCEDURE_COLUMNS);
    }

    @Override
    public ResultSet getColumnPrivileges(String catalog, String schema, String table, String columnNamePattern)
            throws SQLException {
        return empty(MetadataResultSets.COLUMN_PRIVILEGES);
    }

    @Override
    public ResultSet getTablePrivileges(String catalog, String schemaPattern, String tableNamePattern)
            throws SQLException {
        return empty(MetadataResultSets.TABLE_PRIVILEGES);
    }

    /** Returns the columns of the primary key, which name a row as long as the session lasts. */
    @Override
    public ResultSet getBestRowIdentifier(String catalog, String schema, String table, int scope, boolean nullable)
            throws SQLException {
        return read(MetadataResultSets.BEST_ROW_IDENTIFIER, () -> {
            List<Object[]> rows = new ArrayList<>();
            for (Keyed k : keyed(catalog, schema, table)) {
                for (CatalogTables.ColumnInfo c : k.key()) {
                    DataType type = c.field().type();
                    rows.add(new Object[] {(short) bestRowSession, c.field().name(), JdbcTypes.sqlType(type),
                        type.sqlName(), columnSize(type), null, decimalDigits(type) == null ? null
                                : (short) (int) decimalDigits(type), (short) bestRowNotPseudo});
                }
            }
            return rows;
        });
    }

    @Override
    public ResultSet getVersionColumns(String catalog, String schema, String table) throws SQLException {
        return empty(MetadataResultSets.VERSION_COLUMNS);
    }

    /** Returns the columns of the primary keys of the sources that know them, ordered by the name of the column. */
    @Override
    public ResultSet getPrimaryKeys(String catalog, String schema, String table) throws SQLException {
        return read(MetadataResultSets.PRIMARY_KEYS, () -> {
            List<Object[]> rows = new ArrayList<>();
            for (Keyed k : keyed(catalog, schema, table)) {
                for (int i = 0; i < k.key().size(); i++) {
                    rows.add(new Object[] {k.table().source(), k.table().schema(), k.table().table(),
                        k.key().get(i).field().name(), (short) (i + 1), k.keyName()});
                }
            }
            rows.sort(Comparator.comparing(r -> (String) r[3]));
            return rows;
        });
    }

    @Override
    public ResultSet getImportedKeys(String catalog, String schema, String table) throws SQLException {
        return empty(MetadataResultSets.KEYS);
    }

    @Override
    public ResultSet getExportedKeys(String catalog, String schema, String table) throws SQLException {
        return empty(MetadataResultSets.KEYS);
    }

    @Override
    public ResultSet getCrossReference(String parentCatalog, String parentSchema, String parentTable,
            String foreignCatalog, String foreignSchema, String foreignTable) throws SQLException {
        return empty(MetadataResultSets.KEYS);
    }

    /** Returns the primary keys as unique indexes, the only indexes the driver knows. */
    @Override
    public ResultSet getIndexInfo(String catalog, String schema, String table, boolean unique, boolean approximate)
            throws SQLException {
        return read(MetadataResultSets.INDEX_INFO, () -> {
            List<Object[]> rows = new ArrayList<>();
            for (Keyed k : keyed(catalog, schema, table)) {
                for (int i = 0; i < k.key().size(); i++) {
                    rows.add(new Object[] {k.table().source(), k.table().schema(), k.table().table(), false, null,
                        k.keyName(), (short) tableIndexOther, (short) (i + 1), k.key().get(i).field().name(), "A",
                        null, null, null});
                }
            }
            return rows;
        });
    }

    @Override
    public ResultSet getUDTs(String catalog, String schemaPattern, String typeNamePattern, int[] types)
            throws SQLException {
        return empty(MetadataResultSets.UDTS);
    }

    @Override
    public ResultSet getSuperTypes(String catalog, String schemaPattern, String typeNamePattern)
            throws SQLException {
        return empty(MetadataResultSets.SUPER_TYPES);
    }

    @Override
    public ResultSet getSuperTables(String catalog, String schemaPattern, String tableNamePattern)
            throws SQLException {
        return empty(MetadataResultSets.SUPER_TABLES);
    }

    @Override
    public ResultSet getAttributes(String catalog, String schemaPattern, String typeNamePattern,
            String attributeNamePattern) throws SQLException {
        return empty(MetadataResultSets.ATTRIBUTES);
    }

    @Override
    public ResultSet getClientInfoProperties() throws SQLException {
        return empty(MetadataResultSets.CLIENT_INFO_PROPERTIES);
    }

    @Override
    public ResultSet getFunctions(String catalog, String schemaPattern, String functionNamePattern)
            throws SQLException {
        return empty(MetadataResultSets.FUNCTIONS);
    }

    @Override
    public ResultSet getFunctionColumns(String catalog, String schemaPattern, String functionNamePattern,
            String columnNamePattern) throws SQLException {
        return empty(MetadataResultSets.FUNCTION_COLUMNS);
    }

    @Override
    public ResultSet getPseudoColumns(String catalog, String schemaPattern, String tableNamePattern,
            String columnNamePattern) throws SQLException {
        return empty(MetadataResultSets.PSEUDO_COLUMNS);
    }

    // ---------------------------------------------------------------- the product and the driver

    @Override
    public Connection getConnection() {
        return connection;
    }

    /** Returns the URL of the connection; {@code jdbc:fyrql:} for one over an engine of the application. */
    @Override
    public String getURL() {
        return connection.url();
    }

    /** Returns an empty name: the engine has no users. */
    @Override
    public String getUserName() {
        return "";
    }

    /** Returns whether the connection is read-only, see {@link Connection#setReadOnly}. */
    @Override
    public boolean isReadOnly() {
        return connection.readOnly();
    }

    @Override
    public String getDatabaseProductName() {
        return "FyrQL";
    }

    @Override
    public String getDatabaseProductVersion() {
        return FyrQLDriver.VERSION;
    }

    @Override
    public int getDatabaseMajorVersion() {
        return FyrQLDriver.MAJOR_VERSION;
    }

    @Override
    public int getDatabaseMinorVersion() {
        return FyrQLDriver.MINOR_VERSION;
    }

    @Override
    public String getDriverName() {
        return "FyrQL JDBC Driver";
    }

    @Override
    public String getDriverVersion() {
        return FyrQLDriver.VERSION;
    }

    @Override
    public int getDriverMajorVersion() {
        return FyrQLDriver.MAJOR_VERSION;
    }

    @Override
    public int getDriverMinorVersion() {
        return FyrQLDriver.MINOR_VERSION;
    }

    @Override
    public int getJDBCMajorVersion() {
        return 4;
    }

    @Override
    public int getJDBCMinorVersion() {
        return 2;
    }

    @Override
    public int getSQLStateType() {
        return sqlStateSQL;
    }

    @Override
    public boolean usesLocalFiles() {
        return false;
    }

    @Override
    public boolean usesLocalFilePerTable() {
        return false;
    }

    @Override
    public boolean allProceduresAreCallable() {
        return false;
    }

    @Override
    public boolean allTablesAreSelectable() {
        return true;
    }

    // ---------------------------------------------------------------- names

    @Override
    public String getCatalogTerm() {
        return "source";
    }

    @Override
    public String getSchemaTerm() {
        return "schema";
    }

    @Override
    public String getProcedureTerm() {
        return "procedure";
    }

    @Override
    public String getCatalogSeparator() {
        return ".";
    }

    @Override
    public boolean isCatalogAtStart() {
        return true;
    }

    @Override
    public String getIdentifierQuoteString() {
        return "\"";
    }

    @Override
    public String getSearchStringEscape() {
        return "\\";
    }

    @Override
    public String getExtraNameCharacters() {
        return "";
    }

    /**
     * The words that the parser does not take as names beyond those that SQL:2003 reserves, so that tools put them
     * in quotes where they are names, as a source called {@code csv}.
     */
    @Override
    public String getSQLKeywords() {
        return String.join(",", SqlParser.reservedWords());
    }

    @Override
    public String getNumericFunctions() {
        return "";
    }

    @Override
    public String getStringFunctions() {
        return "";
    }

    @Override
    public String getSystemFunctions() {
        return "";
    }

    @Override
    public String getTimeDateFunctions() {
        return "";
    }

    /** Names are case-insensitive, quoted or not, and keep the case they are written in. */
    @Override
    public boolean supportsMixedCaseIdentifiers() {
        return false;
    }

    @Override
    public boolean storesUpperCaseIdentifiers() {
        return false;
    }

    @Override
    public boolean storesLowerCaseIdentifiers() {
        return false;
    }

    @Override
    public boolean storesMixedCaseIdentifiers() {
        return true;
    }

    @Override
    public boolean supportsMixedCaseQuotedIdentifiers() {
        return false;
    }

    @Override
    public boolean storesUpperCaseQuotedIdentifiers() {
        return false;
    }

    @Override
    public boolean storesLowerCaseQuotedIdentifiers() {
        return false;
    }

    @Override
    public boolean storesMixedCaseQuotedIdentifiers() {
        return true;
    }

    /** A query names a table with its source and schema: {@code source.schema.table}. */
    @Override
    public boolean supportsSchemasInDataManipulation() {
        return true;
    }

    @Override
    public boolean supportsSchemasInProcedureCalls() {
        return false;
    }

    @Override
    public boolean supportsSchemasInTableDefinitions() {
        return false;
    }

    @Override
    public boolean supportsSchemasInIndexDefinitions() {
        return false;
    }

    @Override
    public boolean supportsSchemasInPrivilegeDefinitions() {
        return false;
    }

    @Override
    public boolean supportsCatalogsInDataManipulation() {
        return true;
    }

    @Override
    public boolean supportsCatalogsInProcedureCalls() {
        return false;
    }

    @Override
    public boolean supportsCatalogsInTableDefinitions() {
        return false;
    }

    @Override
    public boolean supportsCatalogsInIndexDefinitions() {
        return false;
    }

    @Override
    public boolean supportsCatalogsInPrivilegeDefinitions() {
        return false;
    }

    // ---------------------------------------------------------------- queries

    /** {@code NULL} sorts last in both directions, as in DuckDB. */
    @Override
    public boolean nullsAreSortedHigh() {
        return false;
    }

    @Override
    public boolean nullsAreSortedLow() {
        return false;
    }

    @Override
    public boolean nullsAreSortedAtStart() {
        return false;
    }

    @Override
    public boolean nullsAreSortedAtEnd() {
        return true;
    }

    @Override
    public boolean nullPlusNonNullIsNull() {
        return true;
    }

    @Override
    public boolean supportsColumnAliasing() {
        return true;
    }

    @Override
    public boolean supportsConvert() {
        return false;
    }

    @Override
    public boolean supportsConvert(int fromType, int toType) {
        return false;
    }

    @Override
    public boolean supportsTableCorrelationNames() {
        return true;
    }

    @Override
    public boolean supportsDifferentTableCorrelationNames() {
        return false;
    }

    @Override
    public boolean supportsExpressionsInOrderBy() {
        return true;
    }

    @Override
    public boolean supportsOrderByUnrelated() {
        return true;
    }

    @Override
    public boolean supportsGroupBy() {
        return true;
    }

    @Override
    public boolean supportsGroupByUnrelated() {
        return true;
    }

    @Override
    public boolean supportsGroupByBeyondSelect() {
        return true;
    }

    /** {@code LIKE} has no {@code ESCAPE} clause. */
    @Override
    public boolean supportsLikeEscapeClause() {
        return false;
    }

    @Override
    public boolean supportsMultipleResultSets() {
        return false;
    }

    @Override
    public boolean supportsMultipleTransactions() {
        return false;
    }

    @Override
    public boolean supportsNonNullableColumns() {
        return false;
    }

    @Override
    public boolean supportsMinimumSQLGrammar() {
        return true;
    }

    @Override
    public boolean supportsCoreSQLGrammar() {
        return false;
    }

    @Override
    public boolean supportsExtendedSQLGrammar() {
        return false;
    }

    @Override
    public boolean supportsANSI92EntryLevelSQL() {
        return false;
    }

    @Override
    public boolean supportsANSI92IntermediateSQL() {
        return false;
    }

    @Override
    public boolean supportsANSI92FullSQL() {
        return false;
    }

    @Override
    public boolean supportsIntegrityEnhancementFacility() {
        return false;
    }

    @Override
    public boolean supportsOuterJoins() {
        return true;
    }

    @Override
    public boolean supportsFullOuterJoins() {
        return true;
    }

    @Override
    public boolean supportsLimitedOuterJoins() {
        return true;
    }

    @Override
    public boolean supportsSubqueriesInComparisons() {
        return true;
    }

    @Override
    public boolean supportsSubqueriesInExists() {
        return true;
    }

    @Override
    public boolean supportsSubqueriesInIns() {
        return true;
    }

    /** {@code ANY} and {@code ALL} are not supported. */
    @Override
    public boolean supportsSubqueriesInQuantifieds() {
        return false;
    }

    @Override
    public boolean supportsCorrelatedSubqueries() {
        return true;
    }

    @Override
    public boolean supportsUnion() {
        return true;
    }

    @Override
    public boolean supportsUnionAll() {
        return true;
    }

    // ---------------------------------------------------------------- what a read-only engine does not do

    @Override
    public boolean supportsAlterTableWithAddColumn() {
        return false;
    }

    @Override
    public boolean supportsAlterTableWithDropColumn() {
        return false;
    }

    @Override
    public boolean supportsPositionedDelete() {
        return false;
    }

    @Override
    public boolean supportsPositionedUpdate() {
        return false;
    }

    @Override
    public boolean supportsSelectForUpdate() {
        return false;
    }

    @Override
    public boolean supportsStoredProcedures() {
        return false;
    }

    @Override
    public boolean supportsStoredFunctionsUsingCallSyntax() {
        return false;
    }

    @Override
    public boolean supportsBatchUpdates() {
        return true;
    }

    @Override
    public boolean supportsSavepoints() {
        return false;
    }

    @Override
    public boolean supportsNamedParameters() {
        return false;
    }

    @Override
    public boolean supportsMultipleOpenResults() {
        return false;
    }

    @Override
    public boolean supportsGetGeneratedKeys() {
        return false;
    }

    @Override
    public boolean generatedKeyAlwaysReturned() {
        return false;
    }

    @Override
    public boolean supportsStatementPooling() {
        return false;
    }

    @Override
    public boolean locatorsUpdateCopy() {
        return false;
    }

    @Override
    public RowIdLifetime getRowIdLifetime() {
        return RowIdLifetime.ROWID_UNSUPPORTED;
    }

    // ---------------------------------------------------------------- transactions

    @Override
    public boolean supportsTransactions() {
        return false;
    }

    @Override
    public int getDefaultTransactionIsolation() {
        return Connection.TRANSACTION_NONE;
    }

    @Override
    public boolean supportsTransactionIsolationLevel(int level) {
        return level == Connection.TRANSACTION_NONE;
    }

    @Override
    public boolean supportsDataDefinitionAndDataManipulationTransactions() {
        return false;
    }

    @Override
    public boolean supportsDataManipulationTransactionsOnly() {
        return false;
    }

    @Override
    public boolean dataDefinitionCausesTransactionCommit() {
        return false;
    }

    @Override
    public boolean dataDefinitionIgnoredInTransactions() {
        return false;
    }

    @Override
    public boolean supportsOpenCursorsAcrossCommit() {
        return false;
    }

    @Override
    public boolean supportsOpenCursorsAcrossRollback() {
        return false;
    }

    /** Commits and rollbacks do nothing, so statements stay open. */
    @Override
    public boolean supportsOpenStatementsAcrossCommit() {
        return true;
    }

    @Override
    public boolean supportsOpenStatementsAcrossRollback() {
        return true;
    }

    @Override
    public boolean autoCommitFailureClosesAllResultSets() {
        return false;
    }

    // ---------------------------------------------------------------- result sets

    @Override
    public boolean supportsResultSetType(int type) {
        return type == ResultSet.TYPE_FORWARD_ONLY;
    }

    @Override
    public boolean supportsResultSetConcurrency(int type, int concurrency) {
        return type == ResultSet.TYPE_FORWARD_ONLY && concurrency == ResultSet.CONCUR_READ_ONLY;
    }

    @Override
    public boolean supportsResultSetHoldability(int holdability) {
        return holdability == ResultSet.HOLD_CURSORS_OVER_COMMIT || holdability == ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public int getResultSetHoldability() {
        return ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public boolean ownUpdatesAreVisible(int type) {
        return false;
    }

    @Override
    public boolean ownDeletesAreVisible(int type) {
        return false;
    }

    @Override
    public boolean ownInsertsAreVisible(int type) {
        return false;
    }

    @Override
    public boolean othersUpdatesAreVisible(int type) {
        return false;
    }

    @Override
    public boolean othersDeletesAreVisible(int type) {
        return false;
    }

    @Override
    public boolean othersInsertsAreVisible(int type) {
        return false;
    }

    @Override
    public boolean updatesAreDetected(int type) {
        return false;
    }

    @Override
    public boolean deletesAreDetected(int type) {
        return false;
    }

    @Override
    public boolean insertsAreDetected(int type) {
        return false;
    }

    // ---------------------------------------------------------------- limits: none known

    @Override
    public int getMaxBinaryLiteralLength() {
        return 0;
    }

    @Override
    public int getMaxCharLiteralLength() {
        return 0;
    }

    @Override
    public int getMaxColumnNameLength() {
        return 0;
    }

    @Override
    public int getMaxColumnsInGroupBy() {
        return 0;
    }

    @Override
    public int getMaxColumnsInIndex() {
        return 0;
    }

    @Override
    public int getMaxColumnsInOrderBy() {
        return 0;
    }

    @Override
    public int getMaxColumnsInSelect() {
        return 0;
    }

    @Override
    public int getMaxColumnsInTable() {
        return 0;
    }

    @Override
    public int getMaxConnections() {
        return 0;
    }

    @Override
    public int getMaxCursorNameLength() {
        return 0;
    }

    @Override
    public int getMaxIndexLength() {
        return 0;
    }

    @Override
    public int getMaxSchemaNameLength() {
        return 0;
    }

    @Override
    public int getMaxProcedureNameLength() {
        return 0;
    }

    @Override
    public int getMaxCatalogNameLength() {
        return 0;
    }

    @Override
    public int getMaxRowSize() {
        return 0;
    }

    @Override
    public boolean doesMaxRowSizeIncludeBlobs() {
        return false;
    }

    @Override
    public int getMaxStatementLength() {
        return 0;
    }

    @Override
    public int getMaxStatements() {
        return 0;
    }

    @Override
    public int getMaxTableNameLength() {
        return 0;
    }

    @Override
    public int getMaxTablesInSelect() {
        return 0;
    }

    @Override
    public int getMaxUserNameLength() {
        return 0;
    }

    // ---------------------------------------------------------------- wrapping

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("The metadata does not wrap a " + iface.getName(), SqlStates.GENERAL);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
}
