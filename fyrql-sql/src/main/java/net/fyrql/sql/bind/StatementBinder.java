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

package net.fyrql.sql.bind;

import net.fyrql.core.expr.Expression;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.Casts;
import net.fyrql.sql.SqlException;
import net.fyrql.sql.ast.CopyTo;
import net.fyrql.sql.ast.CreateTable;
import net.fyrql.sql.ast.DropTable;
import net.fyrql.sql.ast.InsertInto;
import net.fyrql.sql.ast.Position;
import net.fyrql.sql.ast.Query;
import net.fyrql.sql.ast.Statement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Binds a statement: a query as {@link QueryBinder} does, and the
 * statements that write with the plan of their rows.
 * <ul>
 * <li>{@code COPY} takes its format from {@code FORMAT} or else from the
 * extension of the file, as {@link CatalogView#fileFunction} does.</li>
 * <li>{@code CREATE TABLE ... AS} takes the names and types of the columns
 * of the query, which must be unique ignoring case.</li>
 * <li>{@code INSERT INTO} brings the rows to the columns of the table: the
 * column list, by default all columns in order, says where each value
 * goes; a column outside the list gets {@code NULL}; a value of another
 * type is cast to the type of its column where a cast is allowed. A bare
 * {@code NULL} or a parameter in {@code VALUES} takes the type of its
 * column.</li>
 * </ul>
 */
final class StatementBinder {

    private final CatalogView catalog;
    private final BindContext context;

    StatementBinder(CatalogView catalog, BindContext context) {
        this.catalog = catalog;
        this.context = context;
    }

    BoundStatement statement(Statement statement) {
        return switch (statement) {
            case Query q -> new BoundStatement.Query(new QueryBinder(catalog, context).query(q));
            case CopyTo c -> copy(c);
            case CreateTable c -> create(c);
            case InsertInto i -> insert(i);
            case DropTable d -> drop(d);
        };
    }

    private BoundStatement copy(CopyTo c) {
        PlanNode input = new QueryBinder(catalog, context).query(c.query());
        Map<String, String> options = new LinkedHashMap<>(c.options());
        String format = options.remove("format");
        if (format == null) {
            format = CatalogView.fileFunction(c.path()).map(f -> f.substring("read_".length()))
                    .orElseThrow(() -> new SqlException("No format for the file " + c.path() + ": end its name in"
                            + " .csv, .json or .parquet, or give one, as in (FORMAT csv)", c.position()));
        }
        format = format.toLowerCase(Locale.ROOT);
        String lower = c.path().toLowerCase(Locale.ROOT);
        if ((format.equals("csv") || format.equals("json")) && !options.containsKey("compression")) {
            if (lower.endsWith(".gz")) {
                options.put("compression", "gzip");
            } else if (lower.endsWith(".zst")) {
                options.put("compression", "zstd");
            }
        }
        return new BoundStatement.Copy(input, c.path(), format, options);
    }

    private BoundStatement create(CreateTable c) {
        TableRef table = withPosition(() -> catalog.resolveNew(c.name()), c.position());
        BoundStatement.CreateMode mode = c.orReplace() ? BoundStatement.CreateMode.OR_REPLACE
                : c.ifNotExists() ? BoundStatement.CreateMode.IF_NOT_EXISTS : BoundStatement.CreateMode.CREATE;
        List<Schema.Field> fields = new ArrayList<>();
        PlanNode input = null;
        if (c.query() != null) {
            input = new QueryBinder(catalog, context).query(c.query());
            for (Schema.Field f : input.schema().fields()) {
                fields.add(new Schema.Field(f.name(), f.type(), true));
            }
        } else {
            for (CreateTable.ColumnDefinition d : c.columns()) {
                fields.add(new Schema.Field(d.name(), d.type(), d.nullable()));
            }
        }
        Set<String> names = new HashSet<>();
        for (Schema.Field f : fields) {
            if (!names.add(f.name().toLowerCase(Locale.ROOT))) {
                throw new SqlException("Column with name " + f.name() + " appears more than once in table "
                        + String.join(".", c.name()), c.position());
            }
        }
        return new BoundStatement.Create(table, new Schema(fields), input, mode);
    }

    private BoundStatement insert(InsertInto i) {
        String name = String.join(".", i.name());
        TableRef table = withPosition(() -> catalog.resolve(i.name()), i.position());
        Schema target = catalog.schema(table);
        int[] columns = new int[i.columns().isEmpty() ? target.size() : i.columns().size()];
        if (i.columns().isEmpty()) {
            Arrays.setAll(columns, c -> c);
        } else {
            BitSet seen = new BitSet();
            for (int k = 0; k < columns.length; k++) {
                columns[k] = column(target, i.columns().get(k), name, i.position());
                if (seen.get(columns[k])) {
                    throw new SqlException("Column " + target.name(columns[k]) + " is given more than once in the"
                            + " INSERT into " + name, i.position());
                }
                seen.set(columns[k]);
            }
        }
        List<DataType> types = Arrays.stream(columns).mapToObj(target::type).toList();
        QueryBinder.Rows rows = new QueryBinder(catalog, context).insertRows(i.query(), types);
        PlanNode input = rows.plan();
        Schema schema = input.schema();
        if (schema.size() != columns.length) {
            throw new SqlException(i.columns().isEmpty()
                    ? "Table " + name + " has " + columns.length + " columns but " + schema.size()
                            + " values were supplied"
                    : "Column name/value mismatch for insert on " + name + ": expected " + columns.length
                            + " columns but " + schema.size() + " values were supplied", i.position());
        }
        int[] source = new int[target.size()];
        Arrays.fill(source, -1);
        for (int k = 0; k < columns.length; k++) {
            source[columns[k]] = k;
        }
        boolean same = columns.length == target.size();
        List<PlanNode.NamedExpression> projection = new ArrayList<>();
        for (int j = 0; j < target.size(); j++) {
            DataType type = target.type(j);
            int k = source[j];
            Expression e;
            if (k < 0) {
                if (!target.nullable(j)) {
                    throw new SqlException("NOT NULL constraint failed: " + name + "." + target.name(j)
                            + ", which the INSERT gives no value", i.position());
                }
                e = new Expression.Literal(type, null);
            } else if (schema.type(k).equals(type)) {
                e = new Expression.Column(k, type);
            } else if (rows.untypedNulls().get(k)) {
                e = new Expression.Literal(type, null);
            } else if (Casts.allowed(schema.type(k), type)) {
                e = new Expression.Cast(new Expression.Column(k, schema.type(k)), type);
            } else {
                throw new SqlException("Cannot insert a value of type " + schema.type(k).sqlName() + " into column "
                        + target.name(j) + " of type " + type.sqlName() + " - an explicit cast is required",
                        i.position());
            }
            same &= e instanceof Expression.Column c && c.index() == j;
            projection.add(new PlanNode.NamedExpression(target.name(j), e));
        }
        return new BoundStatement.Insert(table, same ? input : new PlanNode.Project(input, projection));
    }

    /** Returns the column of a name: the one of exactly that name, or else the one that matches ignoring case. */
    private static int column(Schema schema, String column, String table, Position position) {
        int found = -1;
        for (int c = 0; c < schema.size(); c++) {
            if (schema.name(c).equals(column)) {
                return c;
            }
            if (schema.name(c).equalsIgnoreCase(column)) {
                if (found >= 0) {
                    throw new SqlException("Column name " + column + " is ambiguous in table " + table, position);
                }
                found = c;
            }
        }
        if (found < 0) {
            throw new SqlException("Table " + table + " does not have a column with name \"" + column + "\"",
                    position);
        }
        return found;
    }

    private BoundStatement drop(DropTable d) {
        if (!d.ifExists()) {
            return new BoundStatement.Drop(withPosition(() -> catalog.resolve(d.name()), d.position()), d.name(),
                    false);
        }
        Optional<TableRef> table = withPosition(() -> catalog.find(d.name()), d.position());
        return new BoundStatement.Drop(table.orElse(null), d.name(), true);
    }

    /** Asks the catalog, whose errors have no position, and gives an error the position of the name. */
    private static <T> T withPosition(Supplier<T> lookup, Position position) {
        try {
            return lookup.get();
        } catch (SqlException e) {
            throw new SqlException(e.reason(), position);
        }
    }
}
