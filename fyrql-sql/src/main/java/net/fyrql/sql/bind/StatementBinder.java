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
import net.fyrql.core.value.ZonedDates;
import net.fyrql.sql.SqlException;
import net.fyrql.sql.ast.AlterTable;
import net.fyrql.sql.ast.CopyFrom;
import net.fyrql.sql.ast.CopyTo;
import net.fyrql.sql.ast.CreateSchema;
import net.fyrql.sql.ast.CreateTable;
import net.fyrql.sql.ast.CreateView;
import net.fyrql.sql.ast.Delete;
import net.fyrql.sql.ast.DropTable;
import net.fyrql.sql.ast.DropView;
import net.fyrql.sql.ast.Explain;
import net.fyrql.sql.ast.ResetVariable;
import net.fyrql.sql.ast.SetVariable;
import net.fyrql.sql.ast.ShowVariable;
import net.fyrql.sql.ast.FromItem;
import net.fyrql.sql.ast.InsertInto;
import net.fyrql.sql.ast.Merge;
import net.fyrql.sql.Position;
import net.fyrql.sql.ast.Query;
import net.fyrql.sql.ast.QueryBody;
import net.fyrql.sql.ast.SelectItem;
import net.fyrql.sql.ast.SqlExpr;
import net.fyrql.sql.ast.Statement;
import net.fyrql.sql.ast.Update;

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
 * <li>{@code COPY ... FROM} reads the file with the columns of the table,
 * or those of its column list, in their order, through
 * {@link CatalogView#fileTable}, and brings them to the table as
 * {@code INSERT INTO} does. A format whose files hold their own columns,
 * such as Parquet, gives those, which are cast to the columns by
 * position.</li>
 * <li>{@code CREATE TABLE ... AS} takes the names and types of the columns
 * of the query, which must be unique ignoring case.</li>
 * <li>{@code INSERT INTO} brings the rows to the columns of the table: the
 * column list, by default all columns in order, says where each value
 * goes; a column outside the list gets {@code NULL}; a value of another
 * type is cast to the type of its column where a cast is allowed. A bare
 * {@code NULL} or a parameter in {@code VALUES} takes the type of its
 * column.</li>
 * <li>{@code CREATE VIEW} binds its query once, so that an error shows at
 * once, and keeps the query with the tables its names resolve to, see
 * {@link ViewDefinition}; the names of its columns must be unique ignoring
 * case. A view that would read itself, also through other views, is an
 * error. A view is no table to write, create over or drop.</li>
 * <li>{@code UPDATE} and {@code DELETE} find their rows with a query over
 * the table and the items of {@code FROM} or {@code USING}: it gives the
 * key columns of each row to change, the primary key or else all columns,
 * and for {@code UPDATE} the new values, cast to the types of their
 * columns as {@code INSERT} casts. A bare {@code NULL} or parameter in
 * {@code SET} takes the type of its column. Without {@code FROM},
 * {@code USING} and subqueries, the statement also comes as expressions
 * over the columns of the table, which a source may run as a whole.</li>
 * <li>{@code MERGE} joins the table and the source, each with a column
 * that marks its rows: {@code INNER} for branches of matched rows only,
 * {@code LEFT} with {@code NOT MATCHED BY SOURCE}, {@code RIGHT} with
 * {@code NOT MATCHED}, {@code FULL} with both. For each pair it gives the
 * first branch that holds and the values of every column after the
 * branch; a column that an update does not set keeps its value. An update
 * or insert without a list takes the columns of the source by position,
 * {@code INSERT BY NAME} by name, and a column outside a list of
 * {@code INSERT} gets {@code NULL}.</li>
 * </ul>
 */
final class StatementBinder {

    /** The marks of the rows of the table and of the source in {@code MERGE}, and the number of the branch. */
    private static final String TARGET_MARK = "$target";
    private static final String SOURCE_MARK = "$source";
    private static final String BRANCH = "$branch";

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
            case CopyFrom c -> copyFrom(c);
            case CreateTable c -> create(c);
            case InsertInto i -> insert(i);
            case Update u -> update(u);
            case Delete d -> delete(d);
            case Merge m -> merge(m);
            case CreateSchema c -> createSchema(c);
            case AlterTable a -> addColumn(a);
            case DropTable d -> drop(d);
            case CreateView v -> createView(v);
            case DropView d -> dropView(d);
            case SetVariable s -> set(s);
            case ResetVariable r -> new BoundStatement.Set(setting(r.name(), r.position(), true), null);
            case Explain e -> new BoundStatement.Explain(e.analyze(), statement(e.statement()));
            case ShowVariable s -> show(s);
        };
    }

    /**
     * The name of a setting as FyrQL spells it, see {@link Settings#NAMES}.
     *
     * @param all whether {@code ALL} may stand for every setting
     */
    private static String setting(String name, Position position, boolean all) {
        if (all && name.equalsIgnoreCase(Settings.ALL)) {
            return Settings.ALL;
        }
        String setting = Settings.name(name);
        if (setting == null) {
            throw new SqlException("unrecognized configuration parameter \"" + name + "\"", position);
        }
        return setting;
    }

    /** {@code SET}, with the value checked and written as {@code SHOW} shows it. */
    private BoundStatement set(SetVariable s) {
        String name = setting(s.name(), s.position(), false);
        String value = switch (name) {
            case Settings.TIME_ZONE -> zoneName(s.value(), s.position());
            case Settings.SEARCH_PATH -> ExpressionBinder.check(
                    () -> Settings.searchPathText(Settings.searchPath(s.value())), s.position(), null);
            default -> ExpressionBinder.check(() -> Settings.timeoutText(Settings.timeout(s.value())),
                    s.position(), null);
        };
        return new BoundStatement.Set(name, value);
    }

    /** {@code SHOW}: a row of the value, or for {@code ALL} a row of the name and value of each setting. */
    private BoundStatement show(ShowVariable s) {
        String name = setting(s.name(), s.position(), true);
        if (name.equals(Settings.ALL)) {
            List<Object[]> rows = new ArrayList<>();
            for (String n : Settings.NAMES) {
                rows.add(new Object[] {n, context.setting(n)});
            }
            return new BoundStatement.Query(new PlanNode.Values(Schema.of(new Schema.Field("name", DataType.VARCHAR),
                    new Schema.Field("setting", DataType.VARCHAR)), rows));
        }
        return new BoundStatement.Query(new PlanNode.Values(Schema.of(new Schema.Field(name, DataType.VARCHAR)),
                List.<Object[]>of(new Object[] {context.setting(name)})));
    }

    /** The name of a time zone as the session shows it. */
    private static String zoneName(String value, Position position) {
        try {
            return ZonedDates.name(ZonedDates.zone(value));
        } catch (net.fyrql.core.value.ConversionException e) {
            throw new SqlException(e.getMessage(), position);
        }
    }

    private BoundStatement copy(CopyTo c) {
        PlanNode input = new QueryBinder(catalog, context).query(c.query());
        Map<String, String> options = new LinkedHashMap<>(c.options());
        String format = format(c.path(), options, c.position());
        withPosition(() -> {
            catalog.checkFileWrite(c.path());
            return null;
        }, c.position());
        return new BoundStatement.Copy(input, c.path(), format, options);
    }

    private BoundStatement copyFrom(CopyFrom c) {
        String name = String.join(".", c.name());
        TableRef table = withPosition(() -> catalog.resolve(c.name()), c.position());
        if (catalog.view(table).isPresent()) {
            throw new SqlException("Cannot copy into view " + name + ": a view has no rows of its own",
                    c.position());
        }
        Schema target = catalog.schema(table);
        int[] columns = columns(target, c.columns(), name, "COPY", c.position());
        Map<String, String> options = new LinkedHashMap<>(c.options());
        String format = format(c.path(), options, c.pathPosition());
        List<Schema.Field> fields = new ArrayList<>();
        for (int column : columns) {
            fields.add(new Schema.Field(target.name(column), target.type(column), true));
        }
        TableRef file = withPosition(() -> catalog.fileTable(format, c.path(), new Schema(fields), options),
                c.pathPosition());
        Schema schema = catalog.schema(file);
        if (schema.size() != columns.length) {
            throw new SqlException("The file " + c.path() + " has " + schema.size() + " columns, but the COPY into "
                    + name + " reads " + columns.length, c.pathPosition());
        }
        for (int k = 0; k < columns.length; k++) {
            DataType type = target.type(columns[k]);
            if (!schema.type(k).equals(type) && !Casts.allowed(schema.type(k), type)) {
                throw new SqlException("Cannot copy column " + schema.name(k) + " of type " + schema.type(k).sqlName()
                        + " of the file " + c.path() + " into column " + target.name(columns[k]) + " of type "
                        + type.sqlName(), c.pathPosition());
            }
        }
        PlanNode input = aligned(PlanNode.Scan.all(file, schema), new BitSet(), target, columns, c.position());
        return new BoundStatement.CopyFrom(table, input, c.path(), format, options);
    }

    /**
     * Returns the format of a file: the option {@code format}, which leaves
     * the options, or else the one of the extension of the file, in lower
     * case. A text format gets the option {@code compression} from an
     * extension {@code .gz} or {@code .zst} if the option is not given.
     */
    private static String format(String path, Map<String, String> options, Position position) {
        String format = options.remove("format");
        if (format == null) {
            format = CatalogView.fileFunction(path).map(f -> f.substring("read_".length()))
                    .orElseThrow(() -> new SqlException("No format for the file " + path + ": end its name in"
                            + " .csv, .json, .parquet or .xlsx, or give one, as in (FORMAT csv)", position));
        }
        format = format.toLowerCase(Locale.ROOT);
        String lower = path.toLowerCase(Locale.ROOT);
        if ((format.equals("csv") || format.equals("json")) && !options.containsKey("compression")) {
            if (lower.endsWith(".gz")) {
                options.put("compression", "gzip");
            } else if (lower.endsWith(".zst")) {
                options.put("compression", "zstd");
            }
        }
        return format;
    }

    private BoundStatement create(CreateTable c) {
        TableRef table = withPosition(() -> c.temporary() ? catalog.resolveTemporary(c.name())
                : catalog.resolveNew(c.name()), c.position());
        if (catalog.view(table).isPresent()) {
            throw new SqlException("Cannot create table " + String.join(".", c.name()) + ": there is a view of"
                    + " that name", c.position());
        }
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
        List<String> primaryKey = new ArrayList<>();
        for (String name : c.primaryKey()) {
            int k = column(new Schema(fields), name, String.join(".", c.name()), c.position());
            Schema.Field f = fields.get(k);
            if (primaryKey.contains(f.name())) {
                throw new SqlException("column \"" + name + "\" appears twice in primary key constraint",
                        c.position());
            }
            if (f.type() instanceof DataType.Struct || f.type() instanceof DataType.ListType
                    || f.type() instanceof DataType.MapType) {
                throw new SqlException("Invalid type for PRIMARY KEY column " + f.name() + ": "
                        + f.type().sqlName(), c.position());
            }
            primaryKey.add(f.name());
            fields.set(k, new Schema.Field(f.name(), f.type(), false));
        }
        return new BoundStatement.Create(table, new Schema(fields), input, mode, primaryKey);
    }

    private BoundStatement insert(InsertInto i) {
        String name = String.join(".", i.name());
        TableRef table = withPosition(() -> catalog.resolve(i.name()), i.position());
        if (catalog.view(table).isPresent()) {
            throw new SqlException("Cannot insert into view " + name + ": a view has no rows of its own",
                    i.position());
        }
        Schema target = catalog.schema(table);
        int[] columns = columns(target, i.columns(), name, "INSERT", i.position());
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
        return new BoundStatement.Insert(table, aligned(input, rows.untypedNulls(), target, columns, i.position()));
    }

    private BoundStatement update(Update u) {
        TableRef table = changed(u.name(), "update", u.position());
        Schema target = catalog.schema(table);
        int[] columns = new int[u.assignments().size()];
        List<SqlExpr> values = new ArrayList<>();
        BitSet seen = new BitSet();
        for (int k = 0; k < columns.length; k++) {
            Update.Assignment a = u.assignments().get(k);
            int c = updateColumn(target, a);
            if (seen.get(c)) {
                throw new SqlException("Multiple assignments to same column \"" + a.column() + "\"", a.position());
            }
            seen.set(c);
            columns[k] = c;
            noAggregates(a.value(), "SET");
            boolean untyped = a.value() instanceof SqlExpr.Parameter
                    || a.value() instanceof SqlExpr.Literal l && l.kind() == SqlExpr.LiteralKind.NULL;
            values.add(untyped ? new SqlExpr.Cast(a.value(), target.type(c), false, a.value().position())
                    : a.value());
        }
        if (u.where() != null) {
            noAggregates(u.where(), "WHERE");
        }
        int[] keys = keys(table, target);
        List<SelectItem> items = keyItems(table, u.alias(), target, keys);
        for (int k = 0; k < columns.length; k++) {
            items.add(new SelectItem.ExpressionItem(values.get(k), target.name(columns[k])));
        }
        PlanNode keyed = new QueryBinder(catalog, context).query(Query.of(new QueryBody.Select(false, items,
                from(u.name(), u.alias(), u.from(), u.position()), u.where(), null, null, u.position())));
        List<PlanNode.NamedExpression> projection = new ArrayList<>();
        for (int i = 0; i < keyed.schema().size(); i++) {
            Expression e = new Expression.Column(i, keyed.schema().type(i));
            projection.add(new PlanNode.NamedExpression(keyed.schema().name(i),
                    i < keys.length ? e : assigned(e, target, columns[i - keys.length], u.position())));
        }
        keyed = new PlanNode.Project(keyed, projection);
        List<Expression> bound = null;
        Expression condition = null;
        if (u.from() == null && values.stream().noneMatch(SqlTrees::containsSubquery)
                && (u.where() == null || !SqlTrees.containsSubquery(u.where()))) {
            List<Expression> all = new QueryBinder(catalog, context).overTable(table, u.alias(), values, u.where());
            bound = new ArrayList<>();
            for (int k = 0; k < columns.length; k++) {
                bound.add(assigned(all.get(k), target, columns[k], u.position()));
            }
            condition = u.where() == null ? null : all.get(columns.length);
        }
        return new BoundStatement.Update(table, columns, bound, condition, keys, keyed);
    }

    private BoundStatement delete(Delete d) {
        TableRef table = changed(d.name(), "delete from", d.position());
        Schema target = catalog.schema(table);
        if (d.where() != null) {
            noAggregates(d.where(), "WHERE");
        }
        int[] keys = keys(table, target);
        PlanNode keyed = new QueryBinder(catalog, context).query(Query.of(new QueryBody.Select(false,
                keyItems(table, d.alias(), target, keys), from(d.name(), d.alias(), d.using(), d.position()),
                d.where(), null, null, d.position())));
        boolean simple = d.using() == null && (d.where() == null || !SqlTrees.containsSubquery(d.where()));
        Expression condition = simple && d.where() != null
                ? new QueryBinder(catalog, context).overTable(table, d.alias(), List.of(), d.where()).get(0) : null;
        return new BoundStatement.Delete(table, condition, simple, keys, keyed);
    }

    private BoundStatement merge(Merge m) {
        TableRef table = changed(m.name(), "merge into", m.position());
        Schema target = catalog.schema(table);
        int[] keys = keys(table, target);
        String targetName = m.alias() != null ? m.alias() : table.table();
        String sourceName = sourceName(m.source());
        Position p = m.position();
        Schema source = new QueryBinder(catalog, context).query(Query.of(new QueryBody.Select(false,
                List.of(new SelectItem.Star(List.of(), List.of(), List.of(), p)), m.source(), null, null, null, p)))
                .schema();
        String name = String.join(".", m.name());

        SqlExpr targetMark = new SqlExpr.Identifier(List.of(targetName, TARGET_MARK), p);
        SqlExpr sourceMark = new SqlExpr.Identifier(List.of(sourceName, SOURCE_MARK), p);
        List<BoundStatement.MergeAction> actions = new ArrayList<>();
        List<SqlExpr> conditions = new ArrayList<>();
        List<List<SqlExpr>> values = new ArrayList<>();
        BitSet updated = new BitSet();
        boolean bySource = false;
        boolean notMatched = false;
        for (Merge.When w : m.whens()) {
            SqlExpr match = switch (w.match()) {
                case MATCHED -> and(targetMark, sourceMark, p);
                case NOT_MATCHED -> and(sourceMark, new SqlExpr.IsNull(targetMark, false, p), p);
                case NOT_MATCHED_BY_SOURCE -> and(targetMark, new SqlExpr.IsNull(sourceMark, false, p), p);
            };
            bySource |= w.match() == Merge.Match.NOT_MATCHED_BY_SOURCE;
            notMatched |= w.match() == Merge.Match.NOT_MATCHED;
            if (w.condition() != null) {
                noAggregates(w.condition(), "WHEN");
            }
            conditions.add(w.condition() == null ? match : and(match, w.condition(), p));
            List<SqlExpr> row = null;
            switch (w.action()) {
                case Merge.UpdateAction u -> {
                    actions.add(BoundStatement.MergeAction.UPDATE);
                    row = new ArrayList<>();
                    for (int j = 0; j < target.size(); j++) {
                        row.add(new SqlExpr.Identifier(List.of(targetName, target.name(j)), w.position()));
                    }
                    if (u.assignments() == null) {
                        checkWidth(source, target, "UPDATE", name, w.position());
                        for (int j = 0; j < target.size(); j++) {
                            row.set(j, new SqlExpr.Identifier(List.of(sourceName, source.name(j)), w.position()));
                            updated.set(j);
                        }
                    } else {
                        BitSet seen = new BitSet();
                        for (Update.Assignment a : u.assignments()) {
                            int c = updateColumn(target, a);
                            if (seen.get(c)) {
                                throw new SqlException("Multiple assignments to same column \"" + a.column() + "\"",
                                        a.position());
                            }
                            seen.set(c);
                            noAggregates(a.value(), "SET");
                            row.set(c, a.value());
                            updated.set(c);
                        }
                    }
                }
                case Merge.InsertAction i -> {
                    actions.add(BoundStatement.MergeAction.INSERT);
                    row = new ArrayList<>();
                    for (int j = 0; j < target.size(); j++) {
                        row.add(new SqlExpr.Literal(SqlExpr.LiteralKind.NULL, "NULL", w.position()));
                    }
                    if (i.byName()) {
                        for (int k = 0; k < source.size(); k++) {
                            int c = target.indexOf(source.name(k));
                            for (int j = 0; c < 0 && j < target.size(); j++) {
                                c = target.name(j).equalsIgnoreCase(source.name(k)) ? j : -1;
                            }
                            if (c >= 0) {
                                row.set(c, new SqlExpr.Identifier(List.of(sourceName, source.name(k)), w.position()));
                            }
                        }
                    } else if (i.values() == null) {
                        checkWidth(source, target, "INSERT", name, w.position());
                        for (int j = 0; j < target.size(); j++) {
                            row.set(j, new SqlExpr.Identifier(List.of(sourceName, source.name(j)), w.position()));
                        }
                    } else {
                        int[] columns = columns(target, i.columns(), name, "INSERT", w.position());
                        if (columns.length != i.values().size()) {
                            throw new SqlException(i.columns().isEmpty()
                                    ? "Table " + name + " has " + columns.length + " columns but "
                                            + i.values().size() + " values were supplied"
                                    : "Column name/value mismatch for insert on " + name + ": expected "
                                            + columns.length + " columns but " + i.values().size()
                                            + " values were supplied", w.position());
                        }
                        for (int k = 0; k < columns.length; k++) {
                            noAggregates(i.values().get(k), "VALUES");
                            row.set(columns[k], i.values().get(k));
                        }
                    }
                }
                case Merge.DeleteAction d -> actions.add(BoundStatement.MergeAction.DELETE);
                case Merge.NothingAction n -> actions.add(BoundStatement.MergeAction.NOTHING);
            }
            values.add(row);
        }

        // $branch, the key columns, then each column after the branch that holds first.
        List<SelectItem> items = new ArrayList<>();
        List<SqlExpr.When> branch = new ArrayList<>();
        for (int b = 0; b < conditions.size(); b++) {
            branch.add(new SqlExpr.When(conditions.get(b), new SqlExpr.Literal(SqlExpr.LiteralKind.NUMBER,
                    String.valueOf(b + 1), p)));
        }
        items.add(new SelectItem.ExpressionItem(new SqlExpr.Case(null, branch,
                new SqlExpr.Literal(SqlExpr.LiteralKind.NUMBER, "0", p), p), BRANCH));
        items.addAll(keyItems(table, targetName, target, keys));
        for (int j = 0; j < target.size(); j++) {
            List<SqlExpr.When> whens = new ArrayList<>();
            for (int b = 0; b < conditions.size(); b++) {
                SqlExpr v = values.get(b) == null ? new SqlExpr.Literal(SqlExpr.LiteralKind.NULL, "NULL", p)
                        : values.get(b).get(j);
                whens.add(new SqlExpr.When(conditions.get(b), new SqlExpr.Cast(v, target.type(j), false,
                        v.position())));
            }
            items.add(new SelectItem.ExpressionItem(new SqlExpr.Case(null, whens, null, p), target.name(j)));
        }
        FromItem.JoinKind kind = bySource && notMatched ? FromItem.JoinKind.FULL
                : bySource ? FromItem.JoinKind.LEFT : notMatched ? FromItem.JoinKind.RIGHT : FromItem.JoinKind.INNER;
        FromItem marked = new FromItem.Join(kind,
                marked(new FromItem.TableName(m.name(), null, p), TARGET_MARK, targetName, p),
                marked(m.source(), SOURCE_MARK, sourceName, p), m.on(), m.using(), false, p);
        Query pairs = Query.of(new QueryBody.Select(false, items, marked, null, null, null, p));
        List<SqlExpr> acting = new ArrayList<>();
        for (int b = 0; b < actions.size(); b++) {
            if (actions.get(b) != BoundStatement.MergeAction.NOTHING) {
                acting.add(new SqlExpr.Literal(SqlExpr.LiteralKind.NUMBER, String.valueOf(b + 1), p));
            }
        }
        SqlExpr acts = acting.isEmpty() ? new SqlExpr.Literal(SqlExpr.LiteralKind.BOOLEAN, "false", p)
                : new SqlExpr.InList(new SqlExpr.Identifier(List.of(BRANCH), p), acting, false, p);
        PlanNode changes = new QueryBinder(catalog, context).query(Query.of(new QueryBody.Select(false,
                List.of(new SelectItem.Star(List.of(), List.of(), List.of(), p)),
                new FromItem.Subquery(pairs, new FromItem.Alias("merge_changes", List.of()), p), acts, null, null,
                p)));
        List<PlanNode.NamedExpression> projection = new ArrayList<>();
        for (int i = 0; i < changes.schema().size(); i++) {
            Expression e = new Expression.Column(i, changes.schema().type(i));
            projection.add(new PlanNode.NamedExpression(changes.schema().name(i), i <= keys.length ? e
                    : assigned(e, target, i - 1 - keys.length, p)));
        }
        return new BoundStatement.Merge(table, actions, keys, updated.stream().toArray(),
                new PlanNode.Project(changes, projection));
    }

    /** An item of {@code FROM} with all its columns and a mark, as {@code (SELECT *, true AS mark FROM item) name}. */
    private static FromItem marked(FromItem item, String mark, String name, Position p) {
        List<SelectItem> items = List.of(new SelectItem.Star(List.of(), List.of(), List.of(), p),
                new SelectItem.ExpressionItem(new SqlExpr.Literal(SqlExpr.LiteralKind.BOOLEAN, "true", p), mark));
        return new FromItem.Subquery(Query.of(new QueryBody.Select(false, items, item, null, null, null, p)),
                new FromItem.Alias(name, List.of()), p);
    }

    /** The name by which the columns of the source of {@code MERGE} are qualified. */
    private static String sourceName(FromItem source) {
        return switch (source) {
            case FromItem.TableName t -> t.alias() != null ? t.alias().name() : t.name().get(t.name().size() - 1);
            case FromItem.FileName f -> f.alias() != null ? f.alias().name() : QueryBinder.fileAlias(f.path());
            case FromItem.Subquery s -> s.alias() != null ? s.alias().name() : "unnamed_subquery";
            case FromItem.TableFunctionCall f -> f.alias() != null ? f.alias().name() : SqlTrees.key(f.name());
            case FromItem.Join j -> throw new SqlException("Not supported: a join as the source of MERGE; put it in"
                    + " a subquery", j.position());
        };
    }

    private static SqlExpr and(SqlExpr a, SqlExpr b, Position p) {
        return new SqlExpr.Binary(SqlExpr.BinaryOp.AND, a, b, p);
    }

    /** Fails if the source of an update or insert without a list has not as many columns as the table. */
    private static void checkWidth(Schema source, Schema target, String action, String name, Position position) {
        if (source.size() != target.size()) {
            throw new SqlException("Column count mismatch: " + action + " without a list in MERGE gives "
                    + source.size() + " columns of the source to the " + target.size() + " columns of " + name,
                    position);
        }
    }

    /** Returns the column of the table that an assignment of {@code SET} sets. */
    private static int updateColumn(Schema target, Update.Assignment a) {
        int c = target.indexOf(a.column());
        for (int j = 0; c < 0 && j < target.size(); j++) {
            c = target.name(j).equalsIgnoreCase(a.column()) ? j : -1;
        }
        if (c < 0) {
            throw new SqlException("Referenced update column " + a.column() + " not found in table!", a.position());
        }
        return c;
    }

    private BoundStatement createSchema(CreateSchema c) {
        List<String> place = withPosition(() -> catalog.resolveNewSchema(c.name()), c.position());
        return new BoundStatement.CreateSchema(place.get(0), place.get(1), c.ifNotExists());
    }

    /**
     * {@code ALTER TABLE ... ADD COLUMN}: the value for the rows the table
     * has is a constant, cast to the type of the column as {@code INSERT}
     * casts.
     */
    private BoundStatement addColumn(AlterTable a) {
        TableRef table = changed(a.name(), "alter", a.position());
        DataType type = a.column().type();
        Object value = null;
        if (a.defaultValue() != null) {
            SqlTrees.noParameter(a.defaultValue(), "in DEFAULT");
            ExpressionBinder binder = new ExpressionBinder(context, Scope.none(), Map.of(),
                    "DEFAULT cannot contain aggregates", null);
            ExpressionBinder.Bound bound = binder.bound(a.defaultValue());
            Expression e = bound.isNull() ? new Expression.Literal(type, null)
                    : assigned(bound.expression(), Schema.of(new Schema.Field(a.column().name(), type)), 0,
                            a.position());
            value = Literals.evaluate(e, a.defaultValue().position());
        }
        return new BoundStatement.AddColumn(table, new Schema.Field(a.column().name(), type, true), value,
                a.ifNotExists());
    }

    /** Resolves the table of {@code UPDATE} or {@code DELETE}, which must not be a view. */
    private TableRef changed(List<String> name, String verb, Position position) {
        TableRef table = withPosition(() -> catalog.resolve(name), position);
        if (catalog.view(table).isPresent()) {
            throw new SqlException("Cannot " + verb + " view " + String.join(".", name)
                    + ": a view has no rows of its own", position);
        }
        return table;
    }

    /** Rejects aggregates, window functions and {@code unnest} in a clause of {@code UPDATE} or {@code DELETE}. */
    private static void noAggregates(SqlExpr e, String clause) {
        if (SqlTrees.containsAggregate(e)) {
            throw new SqlException(clause + " clause cannot contain aggregates!", e.position());
        }
        if (SqlTrees.window(e) != null) {
            throw new SqlException(clause + " clause cannot contain window functions!", e.position());
        }
        if (SqlTrees.containsUnnest(e)) {
            throw new SqlException(clause + " clause cannot contain UNNEST", e.position());
        }
    }

    /** Returns the positions of the key columns of a table: its primary key, or else all its columns. */
    private int[] keys(TableRef table, Schema schema) {
        List<String> primaryKey = catalog.primaryKey(table);
        if (primaryKey.isEmpty()) {
            int[] all = new int[schema.size()];
            Arrays.setAll(all, c -> c);
            return all;
        }
        int[] keys = new int[primaryKey.size()];
        for (int k = 0; k < keys.length; k++) {
            keys[k] = column(schema, primaryKey.get(k), table.toString(), Position.UNKNOWN);
        }
        return keys;
    }

    /** The key columns of the table of {@code UPDATE} or {@code DELETE} as items of a select list. */
    private static List<SelectItem> keyItems(TableRef table, String alias, Schema schema, int[] keys) {
        String qualifier = alias != null ? alias : table.table();
        List<SelectItem> items = new ArrayList<>();
        for (int k : keys) {
            items.add(new SelectItem.ExpressionItem(new SqlExpr.Identifier(List.of(qualifier, schema.name(k)),
                    Position.UNKNOWN), schema.name(k)));
        }
        return items;
    }

    /** The table of {@code UPDATE} or {@code DELETE}, cross joined with the items of {@code FROM} or {@code USING}. */
    private static FromItem from(List<String> name, String alias, FromItem others, Position position) {
        FromItem table = new FromItem.TableName(name, alias == null ? null : new FromItem.Alias(alias, List.of()),
                position);
        return others == null ? table
                : new FromItem.Join(FromItem.JoinKind.CROSS, table, others, null, List.of(), false, position);
    }

    /** A value for a column of {@code SET}, cast to the type of the column as {@code INSERT} casts. */
    private static Expression assigned(Expression e, Schema target, int column, Position position) {
        DataType type = target.type(column);
        if (e.type().equals(type)) {
            return e;
        }
        if (!Casts.allowed(e.type(), type)) {
            throw new SqlException("Cannot assign a value of type " + e.type().sqlName() + " to column "
                    + target.name(column) + " of type " + type.sqlName() + " - an explicit cast is required",
                    position);
        }
        return new Expression.Cast(e, type);
    }

    /**
     * Returns the columns of a table that a statement writes: those of its
     * column list, or all of them in order for an empty list.
     *
     * @param statement {@code INSERT} or {@code COPY}, for the messages
     * @throws SqlException if a column is not in the table or given twice,
     *         or a column outside the list is {@code NOT NULL}
     */
    private static int[] columns(Schema target, List<String> names, String table, String statement,
            Position position) {
        int[] columns = new int[names.isEmpty() ? target.size() : names.size()];
        if (names.isEmpty()) {
            Arrays.setAll(columns, c -> c);
            return columns;
        }
        BitSet seen = new BitSet();
        for (int k = 0; k < columns.length; k++) {
            columns[k] = column(target, names.get(k), table, position);
            if (seen.get(columns[k])) {
                throw new SqlException("Column " + target.name(columns[k]) + " is given more than once in the "
                        + statement + " into " + table, position);
            }
            seen.set(columns[k]);
        }
        for (int j = 0; j < target.size(); j++) {
            if (!seen.get(j) && !target.nullable(j)) {
                throw new SqlException("NOT NULL constraint failed: " + table + "." + target.name(j) + ", which the "
                        + statement + " gives no value", position);
            }
        }
        return columns;
    }

    /**
     * Brings rows to the columns of a table: column {@code k} of the input
     * goes to column {@code columns[k]} of the table, as it is if its type
     * is that of the column, else cast or, if it is a bare {@code NULL}, as
     * a {@code NULL} of that type; a column outside the list gets
     * {@code NULL}. Returns the input itself if it fits the table as it is.
     *
     * @param untypedNulls the columns of the input that are bare {@code NULL}s
     * @throws SqlException if a type cannot be cast to that of its column
     */
    private static PlanNode aligned(PlanNode input, BitSet untypedNulls, Schema target, int[] columns,
            Position position) {
        Schema schema = input.schema();
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
                e = new Expression.Literal(type, null);
            } else if (schema.type(k).equals(type)) {
                e = new Expression.Column(k, type);
            } else if (untypedNulls.get(k)) {
                e = new Expression.Literal(type, null);
            } else if (Casts.allowed(schema.type(k), type)) {
                e = new Expression.Cast(new Expression.Column(k, schema.type(k)), type);
            } else {
                throw new SqlException("Cannot insert a value of type " + schema.type(k).sqlName() + " into column "
                        + target.name(j) + " of type " + type.sqlName() + " - an explicit cast is required",
                        position);
            }
            same &= e instanceof Expression.Column c && c.index() == j;
            projection.add(new PlanNode.NamedExpression(target.name(j), e));
        }
        return same ? input : new PlanNode.Project(input, projection);
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
        Optional<TableRef> table = d.ifExists() ? withPosition(() -> catalog.find(d.name()), d.position())
                : Optional.of(withPosition(() -> catalog.resolve(d.name()), d.position()));
        if (table.isPresent() && catalog.view(table.get()).isPresent()) {
            throw new SqlException(String.join(".", d.name()) + " is a view; drop it with DROP VIEW", d.position());
        }
        return new BoundStatement.Drop(table.orElse(null), d.name(), d.ifExists());
    }

    private BoundStatement createView(CreateView c) {
        String name = String.join(".", c.name());
        TableRef view = withPosition(() -> catalog.resolveView(c.name()), c.position());
        ViewCatalogs.Recording recording = new ViewCatalogs.Recording(catalog);
        PlanNode plan = QueryBinder.rename(new QueryBinder(recording, context).query(c.query()), c.columns(), name,
                c.position());
        List<Schema.Field> fields = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Schema.Field f : plan.schema().fields()) {
            if (!names.add(f.name().toLowerCase(Locale.ROOT))) {
                throw new SqlException("Column with name " + f.name() + " appears more than once in view " + name
                        + "; give the columns names of their own", c.position());
            }
            fields.add(new Schema.Field(f.name(), f.type(), true));
        }
        for (TableRef read : recording.names.values()) {
            if (catalog.temporary(read)) {
                throw new SqlException("View " + name + " cannot read the temporary table " + read.table()
                        + ", which only this session has", c.position());
            }
        }
        ViewDefinition definition = new ViewDefinition(c.query(), c.text(), new Schema(fields), recording.names,
                recording.paths, recording.postgresNames());
        checkNotCircular(view, definition, name, c.position());
        BoundStatement.CreateMode mode = c.orReplace() ? BoundStatement.CreateMode.OR_REPLACE
                : c.ifNotExists() ? BoundStatement.CreateMode.IF_NOT_EXISTS : BoundStatement.CreateMode.CREATE;
        return new BoundStatement.CreateView(view, definition, mode);
    }

    /**
     * Fails if a view would read itself: if a table its query reads is the
     * view, or a view whose query reads it, and so on; as with
     * {@code CREATE OR REPLACE VIEW v AS SELECT * FROM v}.
     */
    private void checkNotCircular(TableRef view, ViewDefinition definition, String name, Position position) {
        Set<TableRef> seen = new HashSet<>();
        List<ViewDefinition> pending = new ArrayList<>(List.of(definition));
        while (!pending.isEmpty()) {
            for (TableRef table : pending.remove(pending.size() - 1).names().values()) {
                if (table.equals(view)) {
                    throw new SqlException("View " + name + " would read itself", position);
                }
                if (seen.add(table)) {
                    catalog.view(table).ifPresent(pending::add);
                }
            }
        }
    }

    private BoundStatement dropView(DropView d) {
        Optional<TableRef> view = withPosition(() -> catalog.find(d.name()), d.position());
        if (view.isEmpty() && !d.ifExists()) {
            throw new SqlException("View with name " + String.join(".", d.name()) + " does not exist",
                    d.position());
        }
        if (view.isPresent() && catalog.view(view.get()).isEmpty()) {
            throw new SqlException(String.join(".", d.name()) + " is a table, not a view; drop it with DROP TABLE",
                    d.position());
        }
        return new BoundStatement.DropView(view.orElse(null), d.name(), d.ifExists());
    }

    /** Asks the catalog, whose errors have no position, and gives an error the position of the name. */
    private static <T> T withPosition(Supplier<T> lookup, Position position) {
        try {
            return lookup.get();
        } catch (SqlException e) {
            throw new SqlException(e.reason(), position, e.getCause());
        }
    }
}
