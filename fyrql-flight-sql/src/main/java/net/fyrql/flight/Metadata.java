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

package net.fyrql.flight;

import net.fyrql.FyrQL;
import net.fyrql.arrow.ArrowTypes;
import net.fyrql.arrow.BatchToArrow;
import net.fyrql.catalog.CatalogTables;
import net.fyrql.catalog.NamePattern;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import org.apache.arrow.flight.FlightProducer.ServerStreamListener;
import org.apache.arrow.flight.sql.FlightSqlColumnMetadata;
import org.apache.arrow.flight.sql.SqlInfoBuilder;
import org.apache.arrow.flight.sql.impl.FlightSql;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.WriteChannel;
import org.apache.arrow.vector.ipc.message.MessageSerializer;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.sql.DatabaseMetaData;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The metadata the server answers with: the Arrow schemas of results and
 * tables with the column metadata of Flight SQL, the catalog of the
 * engine, the types and what the server supports. A catalog of Flight SQL
 * is a source of the engine, as in the JDBC driver; a table of a source has
 * the type {@value #TABLE}, a view of the engine the type {@value #VIEW}.
 */
final class Metadata {

    /** The type of a table. */
    static final String TABLE = "TABLE";
    /** The type of a view. */
    static final String VIEW = "VIEW";

    private Metadata() {
    }

    /** Returns the Arrow schema of a result, with the type name of FyrQL of each column. */
    static org.apache.arrow.vector.types.pojo.Schema resultSchema(Schema schema) {
        return withColumnMetadata(schema, null);
    }

    /** Returns the Arrow schema of a table, with its names and the type name of FyrQL of each column. */
    static org.apache.arrow.vector.types.pojo.Schema tableSchema(Schema schema, TableRef table) {
        return withColumnMetadata(schema, table);
    }

    private static org.apache.arrow.vector.types.pojo.Schema withColumnMetadata(Schema schema, TableRef table) {
        org.apache.arrow.vector.types.pojo.Schema arrow = ArrowTypes.toArrow(schema);
        List<Field> fields = new ArrayList<>(schema.size());
        for (int c = 0; c < schema.size(); c++) {
            DataType type = schema.type(c);
            FlightSqlColumnMetadata.Builder b = new FlightSqlColumnMetadata.Builder().typeName(type.sqlName())
                    .isAutoIncrement(false).isCaseSensitive(type == DataType.VARCHAR).isReadOnly(true)
                    .isSearchable(true);
            if (type instanceof DataType.Decimal d) {
                b.precision(d.precision()).scale(d.scale());
            }
            if (table != null) {
                b.catalogName(table.source()).schemaName(table.schema()).tableName(table.table());
            }
            Field f = arrow.getFields().get(c);
            Map<String, String> metadata = new LinkedHashMap<>();
            if (f.getMetadata() != null) {
                metadata.putAll(f.getMetadata());
            }
            metadata.putAll(b.build().getMetadataMap());
            fields.add(new Field(f.getName(), new FieldType(f.isNullable(), f.getType(), f.getDictionary(), metadata),
                    f.getChildren()));
        }
        return new org.apache.arrow.vector.types.pojo.Schema(fields);
    }

    /** Returns the bytes of a schema as an IPC message, as Flight SQL sends schemas inside its messages. */
    static byte[] serialize(org.apache.arrow.vector.types.pojo.Schema schema) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            MessageSerializer.serialize(new WriteChannel(Channels.newChannel(out)), schema);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** Sends rows of Java values, as a batch of FyrQL holds them, as one batch of a fixed Arrow schema. */
    static void send(org.apache.arrow.vector.types.pojo.Schema schema, List<Object[]> rows, BufferAllocator allocator,
            ServerStreamListener listener) {
        try (VectorSchemaRoot root = VectorSchemaRoot.create(schema, allocator)) {
            if (!rows.isEmpty()) {
                BatchToArrow.fill(Batch.ofRows(ArrowTypes.toFyrQL(schema), rows), root);
            }
            listener.start(root);
            listener.putNext();
            listener.completed();
        }
    }

    /** Returns the rows of the sources, in the order of their registration. */
    static List<Object[]> catalogs(FyrQL engine) {
        List<Object[]> rows = new ArrayList<>();
        for (String source : engine.catalogTables().catalogs()) {
            rows.add(new Object[] {source});
        }
        return rows;
    }

    /** Returns the rows of the schemas of a source, or of all, whose names match a pattern. */
    static List<Object[]> schemas(FyrQL engine, FlightSql.CommandGetDbSchemas command) {
        List<Object[]> rows = new ArrayList<>();
        for (CatalogTables.SchemaInfo s : engine.catalogTables().schemas(catalog(command.hasCatalog(),
                command.getCatalog()), pattern(command.hasDbSchemaFilterPattern(),
                command.getDbSchemaFilterPattern()))) {
            rows.add(new Object[] {s.source(), s.schema()});
        }
        return rows;
    }

    /** Returns the rows of the tables a command asks for, with their schemas if it asks for them. */
    static List<Object[]> tables(FyrQL engine, FlightSql.CommandGetTables command) {
        List<Object[]> rows = new ArrayList<>();
        boolean all = command.getTableTypesCount() == 0;
        boolean tables = all || command.getTableTypesList().stream().anyMatch(TABLE::equalsIgnoreCase);
        boolean views = all || command.getTableTypesList().stream().anyMatch(VIEW::equalsIgnoreCase);
        if (!tables && !views) {
            return rows;
        }
        for (CatalogTables.TableInfo t : engine.catalogTables().tables(catalog(command.hasCatalog(),
                command.getCatalog()), pattern(command.hasDbSchemaFilterPattern(), command.getDbSchemaFilterPattern()),
                pattern(command.hasTableNameFilterPattern(), command.getTableNameFilterPattern()))) {
            if (t.view() ? !views : !tables) {
                continue;
            }
            TableRef ref = t.table();
            String type = t.view() ? VIEW : TABLE;
            if (command.getIncludeSchema()) {
                rows.add(new Object[] {ref.source(), ref.schema(), ref.table(), type,
                    serialize(tableSchema(engine.schema(ref), ref))});
            } else {
                rows.add(new Object[] {ref.source(), ref.schema(), ref.table(), type});
            }
        }
        return rows;
    }

    private static NamePattern catalog(boolean set, String name) {
        return set ? NamePattern.name(name) : NamePattern.ANY;
    }

    private static NamePattern pattern(boolean set, String pattern) {
        return set ? NamePattern.of(pattern) : NamePattern.ANY;
    }

    /** Returns the row of each type, or of the types with a code of {@link Types}, ordered by the code. */
    static List<Object[]> types(FlightSql.CommandGetXdbcTypeInfo command) {
        List<DataType> types = new ArrayList<>(List.of(DataType.Scalar.values()));
        types.add(new DataType.Decimal(DataType.MAX_DECIMAL_PRECISION, 0));
        types.add(DataType.listOf(DataType.INTEGER));
        types.add(DataType.struct(new DataType.Struct.Field("a", DataType.INTEGER)));
        types.add(new DataType.MapType(DataType.VARCHAR, DataType.INTEGER));
        List<Object[]> rows = new ArrayList<>();
        for (DataType type : types) {
            Object[] row = type(type);
            if (!command.hasDataType() || command.getDataType() == (Integer) row[1]) {
                rows.add(row);
            }
        }
        rows.sort(Comparator.comparingInt(r -> (Integer) r[1]));
        return rows;
    }

    private static Object[] type(DataType type) {
        String name = switch (type) {
            case DataType.Decimal d -> "DECIMAL";
            case DataType.ListType l -> "LIST";
            case DataType.Struct s -> "STRUCT";
            case DataType.MapType m -> "MAP";
            case DataType.Scalar s -> s.sqlName();
        };
        String prefix = null;
        String suffix = null;
        if (type == DataType.VARCHAR) {
            prefix = "'";
            suffix = "'";
        } else if (type == DataType.DATE || type == DataType.TIMESTAMP) {
            prefix = type.sqlName() + " '";
            suffix = "'";
        }
        boolean decimal = type instanceof DataType.Decimal;
        int scale = type == DataType.TIMESTAMP ? 6 : 0;
        int code = sqlType(type);
        return new Object[] {name, code, precision(type), prefix, suffix,
            decimal ? List.of("precision", "scale") : null, DatabaseMetaData.typeNullable,
            type == DataType.VARCHAR, type == DataType.VARCHAR ? DatabaseMetaData.typeSearchable
                : DatabaseMetaData.typePredBasic, type.isNumeric() ? Boolean.FALSE : null, false, false, null,
            decimal ? 0 : scale, decimal ? DataType.MAX_DECIMAL_PRECISION : scale, code, null,
            type.isNumeric() ? 10 : null, null};
    }

    /** Returns the code of {@link Types} of a type, as the JDBC driver of FyrQL reports it. */
    private static int sqlType(DataType type) {
        return switch (type) {
            case DataType.Decimal d -> Types.DECIMAL;
            case DataType.ListType l -> Types.ARRAY;
            case DataType.Struct s -> Types.STRUCT;
            case DataType.MapType m -> Types.OTHER;
            case DataType.Scalar s -> switch (s) {
                case BOOLEAN -> Types.BOOLEAN;
                case TINYINT -> Types.TINYINT;
                case SMALLINT -> Types.SMALLINT;
                case INTEGER -> Types.INTEGER;
                case BIGINT -> Types.BIGINT;
                case HUGEINT -> Types.NUMERIC;
                case FLOAT -> Types.REAL;
                case DOUBLE -> Types.DOUBLE;
                case VARCHAR -> Types.VARCHAR;
                case BLOB -> Types.VARBINARY;
                case DATE -> Types.DATE;
                case TIMESTAMP -> Types.TIMESTAMP;
                case UUID, INTERVAL -> Types.OTHER;
                case TIME -> Types.TIME;
                case TIMESTAMPTZ -> Types.TIMESTAMP_WITH_TIMEZONE;
            };
        };
    }

    /**
     * Returns the most digits or characters of a value, {@code null} for
     * the nested types; a {@code HUGEINT} has at most 38 digits in Arrow.
     */
    private static Integer precision(DataType type) {
        return switch (type) {
            case DataType.Decimal d -> d.precision();
            case DataType.Scalar s -> switch (s) {
                case BOOLEAN -> 1;
                case TINYINT -> 3;
                case SMALLINT -> 5;
                case INTEGER -> 10;
                case BIGINT -> 19;
                case HUGEINT -> DataType.MAX_DECIMAL_PRECISION;
                case FLOAT -> 7;
                case DOUBLE -> 15;
                case DATE -> 10;
                case TIMESTAMP -> 26;
                case UUID -> 36;
                case TIME -> 15;
                case TIMESTAMPTZ -> 32;
                case VARCHAR, BLOB, INTERVAL -> Integer.MAX_VALUE;
            };
            default -> null;
        };
    }

    /** Returns what the server tells a client about itself, as the JDBC driver of FyrQL does. */
    static SqlInfoBuilder sqlInfo(String version, String arrowVersion) {
        return new SqlInfoBuilder()
                .withFlightSqlServerName("FyrQL")
                .withFlightSqlServerVersion(version)
                .withFlightSqlServerArrowVersion(arrowVersion)
                .withFlightSqlServerReadOnly(false)
                .withFlightSqlServerSql(true)
                .withFlightSqlServerSubstrait(false)
                .withFlightSqlServerTransaction(FlightSql.SqlSupportedTransaction.SQL_SUPPORTED_TRANSACTION_NONE)
                .withFlightSqlServerCancel(true)
                .withFlightSqlServerBulkIngestion(false)
                .withFlightSqlServerStatementTimeout(0)
                .withSqlDdlCatalog(false)
                .withSqlDdlSchema(false)
                .withSqlDdlTable(true)
                .withSqlIdentifierCase(FlightSql.SqlSupportedCaseSensitivity.SQL_CASE_SENSITIVITY_CASE_INSENSITIVE)
                .withSqlQuotedIdentifierCase(
                        FlightSql.SqlSupportedCaseSensitivity.SQL_CASE_SENSITIVITY_CASE_INSENSITIVE)
                .withSqlIdentifierQuoteChar("\"")
                .withSqlSearchStringEscape("\\")
                .withSqlExtraNameCharacters("")
                .withSqlCatalogTerm("source")
                .withSqlSchemaTerm("schema")
                .withSqlProcedureTerm("procedure")
                .withSqlCatalogAtStart(true)
                .withSqlNullOrdering(FlightSql.SqlNullOrdering.SQL_NULLS_SORTED_AT_END)
                .withSqlNullPlusNullIsNull(true)
                .withSqlSupportsColumnAliasing(true)
                .withSqlSupportsTableCorrelationNames(true)
                .withSqlSupportsDifferentTableCorrelationNames(false)
                .withSqlSupportsExpressionsInOrderBy(true)
                .withSqlSupportsOrderByUnrelated(true)
                .withSqlSupportedGroupBy(FlightSql.SqlSupportedGroupBy.SQL_GROUP_BY_UNRELATED,
                        FlightSql.SqlSupportedGroupBy.SQL_GROUP_BY_BEYOND_SELECT)
                .withSqlSupportsLikeEscapeClause(false)
                .withSqlSupportsNonNullableColumns(false)
                .withSqlOuterJoinSupportLevel(FlightSql.SqlOuterJoinsSupportLevel.SQL_FULL_OUTER_JOINS)
                .withSqlSubQueriesSupported(FlightSql.SqlSupportedSubqueries.SQL_SUBQUERIES_IN_COMPARISONS,
                        FlightSql.SqlSupportedSubqueries.SQL_SUBQUERIES_IN_EXISTS,
                        FlightSql.SqlSupportedSubqueries.SQL_SUBQUERIES_IN_INS)
                .withSqlCorrelatedSubqueriesSupported(true)
                .withSqlSupportedUnions(FlightSql.SqlSupportedUnions.SQL_UNION,
                        FlightSql.SqlSupportedUnions.SQL_UNION_ALL)
                .withSqlSelectForUpdateSupported(false)
                .withSqlStoredProceduresSupported(false)
                .withSqlTransactionsSupported(false)
                .withSqlSavepointsSupported(false)
                .withSqlBatchUpdatesSupported(false)
                .withSqlNamedParametersSupported(false)
                .withSqlAllTablesAreSelectable(true)
                .withSqlSupportsConvert(new HashMap<>());
    }
}
