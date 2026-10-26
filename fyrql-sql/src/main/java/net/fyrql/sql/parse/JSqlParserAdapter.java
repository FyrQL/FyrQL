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

package net.fyrql.sql.parse;

import net.fyrql.core.type.DataType;
import net.fyrql.sql.SqlException;
import net.fyrql.sql.ast.CreateTable.ColumnDefinition;
import net.fyrql.sql.ast.DropTable;
import net.fyrql.sql.ast.FromItem;
import net.fyrql.sql.ast.InsertInto;
import net.fyrql.sql.ast.OrderItem;
import net.fyrql.sql.Position;
import net.fyrql.sql.ast.Query;
import net.fyrql.sql.ast.QueryBody;
import net.fyrql.sql.ast.QueryBody.SetKind;
import net.fyrql.sql.ast.SelectItem;
import net.fyrql.sql.ast.SqlExpr;
import net.fyrql.sql.ast.SqlExpr.BinaryOp;
import net.fyrql.sql.ast.SqlExpr.LiteralKind;
import net.fyrql.sql.ast.SqlExpr.UnaryOp;
import net.fyrql.sql.ast.SqlPrinter;
import net.fyrql.sql.ast.WindowSpec;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.AllValue;
import net.sf.jsqlparser.expression.AnalyticExpression;
import net.sf.jsqlparser.expression.AnalyticType;
import net.sf.jsqlparser.expression.AnyComparisonExpression;
import net.sf.jsqlparser.expression.AnyType;
import net.sf.jsqlparser.expression.ArrayConstructor;
import net.sf.jsqlparser.expression.ArrayExpression;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.BooleanValue;
import net.sf.jsqlparser.expression.CaseExpression;
import net.sf.jsqlparser.expression.CastExpression;
import net.sf.jsqlparser.expression.DateTimeLiteralExpression;
import net.sf.jsqlparser.expression.DateUnitExpression;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExtractExpression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.IntervalExpression;
import net.sf.jsqlparser.expression.JdbcNamedParameter;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.JsonExpression;
import net.sf.jsqlparser.expression.LambdaExpression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.MapExpression;
import net.sf.jsqlparser.expression.NotExpression;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.PostgresNamedFunctionParameter;
import net.sf.jsqlparser.expression.RowGetExpression;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.StructType;
import net.sf.jsqlparser.expression.TimeKeyExpression;
import net.sf.jsqlparser.expression.TimezoneExpression;
import net.sf.jsqlparser.expression.TrimFunction;
import net.sf.jsqlparser.expression.WhenClause;
import net.sf.jsqlparser.expression.WindowDefinition;
import net.sf.jsqlparser.expression.WindowElement;
import net.sf.jsqlparser.expression.WindowOffset;
import net.sf.jsqlparser.expression.operators.arithmetic.Addition;
import net.sf.jsqlparser.expression.operators.arithmetic.Concat;
import net.sf.jsqlparser.expression.operators.arithmetic.Division;
import net.sf.jsqlparser.expression.operators.arithmetic.IntegerDivision;
import net.sf.jsqlparser.expression.operators.arithmetic.Modulo;
import net.sf.jsqlparser.expression.operators.arithmetic.Multiplication;
import net.sf.jsqlparser.expression.operators.arithmetic.Subtraction;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.GreaterThan;
import net.sf.jsqlparser.expression.operators.relational.GreaterThanEquals;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.IsBooleanExpression;
import net.sf.jsqlparser.expression.operators.relational.IsDistinctExpression;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.expression.operators.relational.LikeExpression;
import net.sf.jsqlparser.expression.operators.relational.MinorThan;
import net.sf.jsqlparser.expression.operators.relational.MinorThanEquals;
import net.sf.jsqlparser.expression.operators.relational.NamedExpressionList;
import net.sf.jsqlparser.expression.operators.relational.NotEqualsTo;
import net.sf.jsqlparser.expression.operators.relational.OldOracleJoinBinaryExpression;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.expression.operators.relational.RegExpMatchOperator;
import net.sf.jsqlparser.expression.operators.relational.RegExpMatchOperatorType;
import net.sf.jsqlparser.parser.ASTNodeAccess;
import net.sf.jsqlparser.parser.Token;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.drop.Drop;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.Distinct;
import net.sf.jsqlparser.statement.select.ExceptOp;
import net.sf.jsqlparser.statement.select.GroupByElement;
import net.sf.jsqlparser.statement.select.IntersectOp;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.LateralSubSelect;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.Offset;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.ParenthesedFromItem;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperation;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.TableFunction;
import net.sf.jsqlparser.statement.select.UnionOp;
import net.sf.jsqlparser.statement.select.Values;
import net.sf.jsqlparser.statement.select.WithItem;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Translates the syntax tree of JSqlParser into the syntax tree of FyrQL.
 * Every form outside the supported range is an error with its position,
 * never ignored: JSqlParser reads many dialects, and a clause it accepts
 * and FyrQL dropped would change the meaning of the query. Names are taken
 * from the tokens of the query text, since JSqlParser splits quoted names
 * that contain a dot.
 * <p>
 * Besides queries, it translates {@code CREATE TABLE}, {@code INSERT INTO}
 * and {@code DROP TABLE}, with the same care: a constraint, a default, a
 * clause of a conflict or a table option is an error, not dropped.
 */
final class JSqlParserAdapter {

    private final SourceText source;
    /** The parameters met so far, or {@code null} for a query that takes none. */
    private final List<SqlExpr.Parameter> parameters;
    /** The form of the parameters met so far, {@code "?"} or {@code "$"}, or {@code null} before the first. */
    private String parameterForm;

    /**
     * @param parameters whether the query may have parameters; if not, a
     *        {@code ?} is an error
     */
    JSqlParserAdapter(SourceText source, boolean parameters) {
        this.source = source;
        this.parameters = parameters ? new ArrayList<>() : null;
    }

    /** Returns the parameters met, in the order of the tree, each as often as it was met. */
    List<SqlExpr.Parameter> parameters() {
        return parameters == null ? List.of() : List.copyOf(parameters);
    }

    /**
     * Translates a statement.
     *
     * @param writes whether the statements that write are allowed, or only queries
     */
    net.fyrql.sql.ast.Statement statement(Statement statement, boolean writes) {
        if (statement instanceof Select select) {
            return query(select);
        }
        if (!writes) {
            throw new SqlException("Only queries are supported, not " + words(statement), position(statement));
        }
        return switch (statement) {
            case CreateTable c -> createTable(c);
            case Insert i -> insert(i);
            case Drop d when "TABLE".equalsIgnoreCase(d.getType()) -> drop(d);
            case Drop d -> throw new SqlException("Only queries, COPY, CREATE TABLE, INSERT INTO and DROP TABLE are"
                    + " supported, not DROP " + d.getType().toUpperCase(Locale.ROOT), position(d.getName()));
            default -> throw new SqlException("Only queries, COPY, CREATE TABLE, INSERT INTO and DROP TABLE are"
                    + " supported, not " + words(statement), position(statement));
        };
    }

    // ---- statements that write

    private net.fyrql.sql.ast.CreateTable createTable(CreateTable c) {
        Table table = c.getTable();
        Position position = position(table);
        reject(c.isUnlogged(), "UNLOGGED", table);
        if (c.getCreateOptionsStrings() != null && !c.getCreateOptionsStrings().isEmpty()) {
            throw unsupported(String.join(" ", c.getCreateOptionsStrings()).toUpperCase(Locale.ROOT) + " TABLE",
                    table);
        }
        reject(c.getTableOptionsStrings(), "table options", table);
        reject(c.getIndexes(), "constraints", table);
        reject(c.getLikeTable(), "CREATE TABLE ... LIKE", table);
        reject(c.getTrailingLikeTable(), "CREATE TABLE ... LIKE", table);
        reject(c.getOfType(), "CREATE TABLE ... OF", table);
        reject(c.getPartitioning(), "PARTITION BY", table);
        reject(c.getPartitionOf(), "PARTITION OF", table);
        reject(c.getPartitionBound(), "PARTITION OF", table);
        reject(c.getRowMovement(), "ROW MOVEMENT", table);
        reject(c.getSpannerInterleaveIn(), "INTERLEAVE IN", table);
        reject(c.getColumns(), "column names before AS", table);
        if (c.isOrReplace() && c.isIfNotExists()) {
            throw new SqlException("OR REPLACE and IF NOT EXISTS cannot be used together", position);
        }
        List<ColumnDefinition> columns = new ArrayList<>();
        Query query = null;
        if (c.getSelect() != null) {
            query = query(c.getSelect());
        } else if (c.getColumnDefinitions() == null || c.getColumnDefinitions().isEmpty()) {
            throw new SqlException("Syntax error: CREATE TABLE needs columns, or AS and a query", position);
        } else {
            for (net.sf.jsqlparser.statement.create.table.ColumnDefinition d : c.getColumnDefinitions()) {
                columns.add(column(d, table));
            }
        }
        return new net.fyrql.sql.ast.CreateTable(nameParts(table), columns, query, c.isOrReplace(),
                c.isIfNotExists(), position);
    }

    /** {@code name type [NOT NULL | NULL]}; any other part of a column definition is an error. */
    private ColumnDefinition column(net.sf.jsqlparser.statement.create.table.ColumnDefinition d, Table table) {
        Position position = position(table);
        String name = unquote(d.getColumnName(), table);
        reject(d.isWithOptions(), "WITH OPTIONS", table);
        reject(d.getForeignKeyReference(), "REFERENCES", table);
        DataType type;
        try {
            type = TypeNames.parse(d.getColDataType().toString());
        } catch (IllegalArgumentException e) {
            throw new SqlException(e.getMessage() + " (column " + name + ")", position);
        }
        boolean nullable = true;
        List<String> specs = d.getColumnSpecs() == null ? List.of() : d.getColumnSpecs();
        for (int i = 0; i < specs.size(); i++) {
            if (specs.get(i).equalsIgnoreCase("NOT") && i + 1 < specs.size()
                    && specs.get(i + 1).equalsIgnoreCase("NULL")) {
                nullable = false;
                i++;
            } else if (!specs.get(i).equalsIgnoreCase("NULL")) {
                throw new SqlException("Not supported: " + String.join(" ", specs.subList(i, specs.size()))
                        + " (column " + name + "); a column has a name, a type and NOT NULL", position);
            }
        }
        return new ColumnDefinition(name, type, nullable);
    }

    private InsertInto insert(Insert i) {
        Table table = i.getTable();
        reject(i.getWithItemsList(), "WITH before INSERT", table);
        reject(i.getOracleHint(), "hints", table);
        reject(i.getModifierPriority(), "INSERT " + i.getModifierPriority(), table);
        reject(i.isModifierIgnore(), "INSERT IGNORE", table);
        reject(i.isOverwrite(), "INSERT OVERWRITE", table);
        reject(i.isOracleMultiInsert(), "INSERT ALL", table);
        reject(i.getPartitions(), "PARTITION", table);
        reject(i.isOverriding(), "OVERRIDING", table);
        reject(i.getRowAlias(), "an alias of the row", table);
        reject(table.getAlias(), "an alias of the table", table);
        reject(i.isUseSet(), "INSERT ... SET", table);
        reject(i.getSetUpdateSets(), "INSERT ... SET", table);
        reject(i.isUseDuplicate(), "ON DUPLICATE KEY UPDATE", table);
        reject(i.getDuplicateUpdateSets(), "ON DUPLICATE KEY UPDATE", table);
        reject(i.getDuplicateAction(), "ON DUPLICATE KEY UPDATE", table);
        reject(i.getConflictTarget(), "ON CONFLICT", table);
        reject(i.getConflictAction(), "ON CONFLICT", table);
        reject(i.getReturningClause(), "RETURNING", table);
        reject(i.getOutputClause(), "OUTPUT", table);
        if (i.isOnlyDefaultValues() || i.getSelect() == null) {
            throw unsupported("DEFAULT VALUES", table);
        }
        List<String> columns = new ArrayList<>();
        if (i.getColumns() != null) {
            for (Column column : i.getColumns()) {
                columns.add(simpleName(column));
            }
        }
        return new InsertInto(nameParts(table), columns, query(i.getSelect()), position(table));
    }

    private DropTable drop(Drop d) {
        Table table = d.getName();
        if (d.getNames() != null && d.getNames().size() > 1) {
            throw unsupported("DROP TABLE of more than one table", d.getNames().get(1));
        }
        if (d.getParameters() != null && !d.getParameters().isEmpty()) {
            throw unsupported(String.join(" ", d.getParameters()).toUpperCase(Locale.ROOT), table);
        }
        reject(d.getTypeToParameters(), "these options of DROP", table);
        reject(d.isUsingTemporary(), "DROP TEMPORARY", table);
        reject(d.isMaterialized(), "DROP MATERIALIZED", table);
        return new DropTable(nameParts(table), d.isIfExists(), position(table));
    }

    // ---- queries

    private Query query(Select s) {
        checkQuery(s);
        List<Query.CommonTable> with = new ArrayList<>();
        if (s.getWithItemsList() != null) {
            for (WithItem<?> item : s.getWithItemsList()) {
                with.add(commonTable(item, s));
            }
        }
        QueryBody body;
        if (s instanceof LateralSubSelect) {
            throw unsupported("LATERAL", s);
        } else if (s instanceof ParenthesedSelect p) {
            body = operand(p.getSelect());
        } else if (s instanceof PlainSelect p) {
            body = select(p);
        } else if (s instanceof SetOperationList l) {
            body = setOperations(l);
        } else if (s instanceof Values v) {
            reject(v.getAlias(), "an alias here", v);
            body = values(v);
        } else {
            throw unsupported(words(s), s);
        }
        List<OrderItem> orderBy = orderItems(s.getOrderByElements(), true);
        SqlExpr limit = null;
        Limit l = s.getLimit();
        if (l != null) {
            if (l.getOffset() != null) {
                throw unsupported("LIMIT with an offset before the count", l);
            }
            if (l.getByExpressions() != null && !l.getByExpressions().isEmpty()) {
                throw unsupported("LIMIT BY", l);
            }
            // LIMIT ALL and LIMIT NULL come as these values and mean no limit.
            if (l.getRowCount() != null && !(l.getRowCount() instanceof NullValue)
                    && !(l.getRowCount() instanceof AllValue)) {
                limit = expr(l.getRowCount());
            }
        }
        SqlExpr offset = null;
        Offset o = s.getOffset();
        if (o != null) {
            if (o.getOffsetParam() != null && !o.getOffsetParam().equalsIgnoreCase("ROWS")
                    && !o.getOffsetParam().equalsIgnoreCase("ROW")) {
                throw unsupported("OFFSET " + o.getOffsetParam(), s);
            }
            offset = expr(o.getOffset());
        }
        return new Query(with, body, orderBy, limit, offset);
    }

    /** Rejects the clauses of other dialects that a query may carry. */
    private void checkQuery(Select s) {
        reject(s.getFetch(), "FETCH", s);
        reject(s.getLimitBy(), "LIMIT BY", s);
        reject(s.getForMode(), "FOR UPDATE", s);
        reject(s.getForUpdateTables(), "FOR UPDATE", s);
        reject(s.getForClause(), "FOR", s);
        reject(s.getIsolation(), "WITH isolation", s);
        reject(s.getOption(), "OPTION", s);
        reject(s.getInterpolate(), "INTERPOLATE", s);
        reject(s.getMySqlProcedureAnalyse(), "PROCEDURE ANALYSE", s);
        reject(s.isOracleSiblings(), "ORDER SIBLINGS BY", s);
        reject(s.getPivot(), "PIVOT", s);
        reject(s.getUnPivot(), "UNPIVOT", s);
    }

    private Query.CommonTable commonTable(WithItem<?> item, Select owner) {
        Position position = position(item.getParenthesedStatement());
        if (!position.isKnown()) {
            position = position(owner);
        }
        if (item.isRecursive()) {
            throw new SqlException("WITH RECURSIVE is not supported", position);
        }
        if (item.getWithFunctionDeclaration() != null || item.getSearchClause() != null
                || item.getCycleClause() != null) {
            throw new SqlException("This form of WITH is not supported", position);
        }
        if (!(item.getParenthesedStatement() instanceof ParenthesedSelect select)) {
            throw new SqlException("Only queries are supported in WITH", position);
        }
        List<String> columns = new ArrayList<>();
        if (item.getWithItemList() != null) {
            for (net.sf.jsqlparser.statement.select.SelectItem<?> column : item.getWithItemList()) {
                columns.add(simpleName(column.getExpression()));
            }
        }
        Query.Materialized materialized = !item.isMaterialized() ? Query.Materialized.DEFAULT
                : item.isUsingNot() ? Query.Materialized.NEVER : Query.Materialized.ALWAYS;
        return new Query.CommonTable(aliasName(item.getAlias()), columns, parenthesized(select), materialized,
                position);
    }

    /** A query in parentheses, with the {@code ORDER BY} and {@code LIMIT} that may follow the parentheses. */
    private Query parenthesized(ParenthesedSelect p) {
        Query outer = query(p);
        if (outer.isBodyOnly() && outer.body() instanceof QueryBody.Parenthesized inner) {
            return inner.query();
        }
        return outer.isBodyOnly() ? Query.of(outer.body()) : outer;
    }

    /** {@code [NOT] EXISTS (query)}. */
    private SqlExpr exists(ExistsExpression x, boolean negated, Position position) {
        if (!(x.getRightExpression() instanceof ParenthesedSelect p)) {
            throw unsupported("EXISTS without a query", x);
        }
        return new SqlExpr.Exists(parenthesized(p), negated, position);
    }

    /** An operand of a set operation or the content of parentheses: a body, in parentheses if it is more. */
    private QueryBody operand(Select s) {
        Query q = s instanceof ParenthesedSelect p ? parenthesized(p) : query(s);
        return q.isBodyOnly() ? q.body() : new QueryBody.Parenthesized(q);
    }

    private QueryBody select(PlainSelect p) {
        reject(p.getIntoTables(), "SELECT INTO", p);
        reject(p.getMySqlSelectIntoClause(), "SELECT INTO", p);
        reject(p.getIntoTempTable(), "SELECT INTO", p);
        reject(p.getTop(), "TOP", p);
        reject(p.getFirst(), "FIRST", p);
        reject(p.getSkip(), "SKIP", p);
        reject(p.getLateralViews(), "LATERAL VIEW", p);
        reject(p.getOracleHierarchical(), "CONNECT BY", p);
        reject(p.getPreferringClause(), "PREFERRING", p);
        reject(p.getOracleHint(), "hints", p);
        reject(p.getKsqlWindow(), "WINDOW", p);
        reject(p.getSampleClause(), "SAMPLE", p);
        reject(p.getPreWhere(), "PREWHERE", p);
        reject(p.getOptimizeFor(), "OPTIMIZE FOR", p);
        reject(p.getForXmlPath(), "FOR XML", p);
        reject(p.getBigQuerySelectQualifier(), "SELECT AS", p);
        reject(p.getSettings(), "SETTINGS", p);
        reject(p.isUsingFinal(), "FINAL", p);
        reject(p.isUsingOnly(), "ONLY", p);
        reject(p.isUseWithNoLog(), "WITH NO LOG", p);
        reject(p.getMySqlHintStraightJoin(), "STRAIGHT_JOIN", p);
        reject(p.getMySqlSqlCalcFoundRows(), "SQL_CALC_FOUND_ROWS", p);
        reject(p.getMySqlSqlCacheFlag(), "SQL_CACHE", p);
        reject(p.isEmitChanges(), "EMIT CHANGES", p);
        boolean distinct = false;
        List<SqlExpr> distinctOn = new ArrayList<>();
        Distinct d = p.getDistinct();
        if (d != null) {
            reject(d.isUseUnique(), "UNIQUE", p);
            reject(d.isUseDistinctRow(), "DISTINCTROW", p);
            if (d.getOnSelectItems() != null && !d.getOnSelectItems().isEmpty()) {
                for (net.sf.jsqlparser.statement.select.SelectItem<?> on : d.getOnSelectItems()) {
                    reject(on.getAlias(), "an alias in DISTINCT ON", p);
                    distinctOn.add(expr(on.getExpression()));
                }
            } else {
                distinct = true;
            }
        }
        List<SelectItem> items = new ArrayList<>();
        for (net.sf.jsqlparser.statement.select.SelectItem<?> item : p.getSelectItems()) {
            items.add(selectItem(item));
        }
        FromItem from = null;
        if (p.getFromItem() != null) {
            from = joins(fromItem(p.getFromItem()), p.getJoins());
        } else if (p.getJoins() != null && !p.getJoins().isEmpty()) {
            throw new SqlException("Syntax error: JOIN without FROM", position(p));
        }
        return new QueryBody.Select(distinct, items, from, exprOrNull(p.getWhere()), groupBy(p.getGroupBy(), p),
                exprOrNull(p.getHaving()), windows(p), exprOrNull(p.getQualify()), distinctOn, position(p));
    }

    /** The {@code WINDOW} clause; JSqlParser keeps no position for its windows, so they have the one of the select. */
    private List<QueryBody.NamedWindow> windows(PlainSelect p) {
        List<QueryBody.NamedWindow> windows = new ArrayList<>();
        if (p.getWindowDefinitions() == null) {
            return windows;
        }
        Set<String> names = new HashSet<>();
        for (WindowDefinition d : p.getWindowDefinitions()) {
            String name = unquote(d.getWindowName(), p);
            if (!names.add(name.toLowerCase(Locale.ROOT))) {
                throw new SqlException("window \"" + name + "\" is already defined", position(p));
            }
            windows.add(new QueryBody.NamedWindow(name, windowSpec(d, p), position(p)));
        }
        return windows;
    }

    /**
     * {@code GROUP BY}: expressions, {@code ALL}, or grouping sets. Each item
     * stands for a list of sets: an expression for the set of itself,
     * {@code ROLLUP (a, b)} for (a, b), (a) and (), {@code CUBE (a, b)} for
     * all subsets, {@code GROUPING SETS (...)} for the sets of its items,
     * and {@code ()} for the set of all rows; the items combine as the cross
     * product of their sets, as in DuckDB, which also keeps a set as often
     * as it comes. A set indexes the expressions, one per text.
     */
    private QueryBody.GroupBy groupBy(GroupByElement g, PlainSelect owner) {
        if (g == null) {
            return null;
        }
        reject(g.isMysqlWithRollup(), "WITH ROLLUP", owner);
        reject(g.hasGroupBySortDirections(), "ASC and DESC in GROUP BY", owner);
        ExpressionList<?> list = g.getGroupByExpressionList();
        List<ExpressionList<Expression>> groupingSets = g.getGroupingSets() == null ? List.of()
                : g.getGroupingSets();
        if (list != null && list.size() == 1 && list.get(0) instanceof AllValue && groupingSets.isEmpty()) {
            return new QueryBody.GroupBy(true, List.of());
        }
        boolean plain = groupingSets.isEmpty() && list != null && !list.isEmpty();
        List<SqlExpr> expressions = new ArrayList<>();
        Map<String, Integer> texts = new HashMap<>();
        List<List<List<Integer>>> items = new ArrayList<>();
        for (Expression e : list == null ? List.<Expression>of() : list) {
            if (e instanceof AllValue) {
                throw unsupported("GROUP BY ALL with other items", owner);
            }
            List<List<Integer>> sets = rollupOrCube(e, expressions, texts);
            plain &= sets == null;
            items.add(sets != null ? sets : List.of(List.of(group(e, expressions, texts))));
        }
        if (!groupingSets.isEmpty()) {
            List<List<Integer>> union = new ArrayList<>();
            for (ExpressionList<?> set : groupingSets) {
                List<List<Integer>> nested = set.size() == 1 && !(set instanceof ParenthesedExpressionList<?>)
                        ? rollupOrCube(set.get(0), expressions, texts) : null;
                if (nested != null) {
                    union.addAll(nested);
                } else {
                    List<Integer> one = new ArrayList<>();
                    for (Expression e : set) {
                        one.add(group(e, expressions, texts));
                    }
                    union.add(one);
                }
            }
            items.add(union);
        }
        if (plain) {
            return new QueryBody.GroupBy(false, exprs(list));
        }
        // The cross product of the sets of the items; GROUP BY () is the one set of all rows.
        List<List<Integer>> sets = List.of(List.of());
        for (List<List<Integer>> item : items) {
            List<List<Integer>> product = new ArrayList<>();
            for (List<Integer> left : sets) {
                for (List<Integer> right : item) {
                    List<Integer> both = new ArrayList<>(left);
                    both.addAll(right);
                    product.add(both);
                }
            }
            if (product.size() > MAX_GROUPING_SETS) {
                throw unsupported("more than " + MAX_GROUPING_SETS + " grouping sets", owner);
            }
            sets = product;
        }
        return new QueryBody.GroupBy(false, expressions, sets);
    }

    private static final int MAX_GROUPING_SETS = 4096;

    /** The index of an expression to group by, the same for the same text. */
    private int group(Expression e, List<SqlExpr> expressions, Map<String, Integer> texts) {
        if (e instanceof Function f && f.getName() != null
                && List.of("ROLLUP", "CUBE", "GROUPING").contains(f.getName().toUpperCase(Locale.ROOT))) {
            throw unsupported(f.getName().toUpperCase(Locale.ROOT) + " here", f);
        }
        SqlExpr x = expr(e);
        return texts.computeIfAbsent(SqlPrinter.print(x), k -> {
            expressions.add(x);
            return expressions.size() - 1;
        });
    }

    /**
     * The sets of {@code ROLLUP (...)} or {@code CUBE (...)}, which JSqlParser
     * reads as function calls, or {@code null} for another expression; an
     * item in parentheses stands for its expressions together.
     */
    private List<List<Integer>> rollupOrCube(Expression e, List<SqlExpr> expressions, Map<String, Integer> texts) {
        if (!(e instanceof Function f) || f.getName() == null
                || !List.of("ROLLUP", "CUBE").contains(f.getName().toUpperCase(Locale.ROOT))) {
            return null;
        }
        checkFunction(f);
        if (f.getParameters() == null || f.getParameters().isEmpty()) {
            throw new SqlException("Syntax error: " + f.getName() + " needs items", position(f));
        }
        List<List<Integer>> parts = new ArrayList<>();
        for (Expression p : f.getParameters()) {
            List<Integer> part = new ArrayList<>();
            for (Expression x : p instanceof ParenthesedExpressionList<?> l ? l : List.of(p)) {
                part.add(group(x, expressions, texts));
            }
            parts.add(part);
        }
        List<List<Integer>> sets = new ArrayList<>();
        if (f.getName().equalsIgnoreCase("ROLLUP")) {
            for (int n = parts.size(); n >= 0; n--) {
                List<Integer> set = new ArrayList<>();
                parts.subList(0, n).forEach(set::addAll);
                sets.add(set);
            }
        } else {
            if (parts.size() > 12) {
                throw unsupported("CUBE with more than 12 items", f);
            }
            for (int mask = (1 << parts.size()) - 1; mask >= 0; mask--) {
                List<Integer> set = new ArrayList<>();
                for (int i = 0; i < parts.size(); i++) {
                    if ((mask & (1 << (parts.size() - 1 - i))) != 0) {
                        set.addAll(parts.get(i));
                    }
                }
                sets.add(set);
            }
        }
        return sets;
    }

    private QueryBody setOperations(SetOperationList l) {
        List<QueryBody> operands = new ArrayList<>();
        for (Select s : l.getSelects()) {
            operands.add(operand(s));
        }
        // INTERSECT binds more tightly than UNION and EXCEPT, which associate to the left.
        List<QueryBody> terms = new ArrayList<>();
        List<SetOperation> operators = new ArrayList<>();
        QueryBody term = operands.get(0);
        for (int i = 0; i < l.getOperations().size(); i++) {
            SetOperation op = l.getOperations().get(i);
            if (kind(op) == SetKind.INTERSECT) {
                term = new QueryBody.SetOperation(SetKind.INTERSECT, all(op), term, operands.get(i + 1));
            } else {
                terms.add(term);
                operators.add(op);
                term = operands.get(i + 1);
            }
        }
        terms.add(term);
        QueryBody result = terms.get(0);
        for (int i = 0; i < operators.size(); i++) {
            result = new QueryBody.SetOperation(kind(operators.get(i)), all(operators.get(i)), result,
                    terms.get(i + 1));
        }
        return result;
    }

    private SetKind kind(SetOperation op) {
        if (op instanceof UnionOp) {
            return SetKind.UNION;
        }
        if (op instanceof ExceptOp) {
            return SetKind.EXCEPT;
        }
        if (op instanceof IntersectOp) {
            return SetKind.INTERSECT;
        }
        throw unsupported(op.toString().strip(), op);
    }

    private boolean all(SetOperation op) {
        String modifier = op.getModifier() == null ? "" : op.getModifier().strip();
        if (modifier.isEmpty() || modifier.equalsIgnoreCase("DISTINCT")) {
            return false;
        }
        if (modifier.equalsIgnoreCase("ALL")) {
            return true;
        }
        throw unsupported(op.toString().strip(), op);
    }

    private QueryBody.Values values(Values v) {
        List<List<SqlExpr>> rows = new ArrayList<>();
        ExpressionList<?> list = v.getExpressions();
        if (list instanceof ParenthesedExpressionList<?> single) {
            rows.add(exprs(single));
        } else {
            for (Expression row : list) {
                if (!(row instanceof ParenthesedExpressionList<?> values)) {
                    throw new SqlException("Syntax error: a row of VALUES needs parentheses", position(row));
                }
                rows.add(exprs(values));
            }
        }
        int width = rows.get(0).size();
        for (List<SqlExpr> row : rows) {
            if (row.size() != width) {
                throw new SqlException("VALUES lists must all be the same length", position(v));
            }
        }
        return new QueryBody.Values(rows, position(v));
    }

    private List<OrderItem> orderItems(List<OrderByElement> elements, boolean allowAll) {
        List<OrderItem> items = new ArrayList<>();
        if (elements == null) {
            return items;
        }
        for (OrderByElement e : elements) {
            reject(e.getWithFill(), "WITH FILL", e.getExpression());
            reject(e.isMysqlWithRollup(), "WITH ROLLUP", e.getExpression());
            boolean nullsFirst = e.getNullOrdering() == OrderByElement.NullOrdering.NULLS_FIRST;
            if (e.getExpression() instanceof AllValue) {
                if (!allowAll) {
                    throw unsupported("ORDER BY ALL here", e.getExpression());
                }
                items.add(OrderItem.all(e.isAsc(), nullsFirst));
            } else {
                items.add(new OrderItem(expr(e.getExpression()), e.isAsc(), nullsFirst));
            }
        }
        return items;
    }

    // ---- SELECT list

    private SelectItem selectItem(net.sf.jsqlparser.statement.select.SelectItem<?> item) {
        Expression e = item.getExpression();
        if (e instanceof AllColumns all) {
            reject(item.getAlias(), "an alias for *", e);
            List<String> qualifier = all instanceof AllTableColumns t ? nameParts(t.getTable()) : List.of();
            List<String> exclude = new ArrayList<>();
            if (all.getExceptColumns() != null && !all.getExceptColumns().isEmpty()) {
                if (!"EXCLUDE".equalsIgnoreCase(all.getExceptKeyword())) {
                    throw unsupported("* " + all.getExceptKeyword(), e);
                }
                for (Column c : all.getExceptColumns()) {
                    exclude.add(simpleName(c));
                }
            }
            List<SelectItem.Replacement> replace = new ArrayList<>();
            if (all.getReplaceExpressions() != null) {
                for (net.sf.jsqlparser.statement.select.SelectItem<?> r : all.getReplaceExpressions()) {
                    if (r.getAlias() == null) {
                        throw new SqlException("Syntax error: REPLACE needs AS and a name", position(r));
                    }
                    replace.add(new SelectItem.Replacement(expr(r.getExpression()), aliasName(r.getAlias())));
                }
            }
            return new SelectItem.Star(qualifier, exclude, replace, position(e));
        }
        String alias = null;
        if (item.getAlias() != null) {
            reject(item.getAlias().getAliasColumns(), "column names in an alias here", e);
            alias = aliasName(item.getAlias());
        }
        return new SelectItem.ExpressionItem(expr(e), alias);
    }

    // ---- FROM

    /** Joins the items of a {@code FROM}: commas separate groups of explicit joins and are cross joins themselves. */
    private FromItem joins(FromItem first, List<Join> joins) {
        if (joins == null) {
            return first;
        }
        FromItem done = null;
        FromItem current = first;
        for (Join j : joins) {
            if (j.isSimple()) {
                reject(j.getOnExpressions(), "ON after a comma", j);
                reject(j.getUsingColumns(), "USING after a comma", j);
                done = done == null ? current : cross(done, current);
                current = fromItem(j.getFromItem());
            } else {
                current = join(current, j);
            }
        }
        return done == null ? current : cross(done, current);
    }

    private static FromItem cross(FromItem left, FromItem right) {
        return new FromItem.Join(FromItem.JoinKind.CROSS, left, right, null, List.of(), false, left.position());
    }

    private FromItem join(FromItem left, Join j) {
        reject(j.isSemi(), "SEMI JOIN", j);
        reject(j.isApply(), "APPLY", j);
        reject(j.isAsOf(), "ASOF JOIN", j);
        reject(j.isStraight(), "STRAIGHT_JOIN", j);
        reject(j.isWindowJoin(), "WITHIN", j);
        reject(j.isGlobal(), "GLOBAL JOIN", j);
        reject(j.isAny(), "ANY JOIN", j);
        reject(j.isAll(), "ALL JOIN", j);
        reject(j.isFetch(), "FETCH JOIN", j);
        reject(j.isArray(), "ARRAY JOIN", j);
        reject(j.getJoinHint(), "join hints", j);
        FromItem.JoinKind kind = j.isCross() ? FromItem.JoinKind.CROSS
                : j.isLeft() ? FromItem.JoinKind.LEFT
                : j.isRight() ? FromItem.JoinKind.RIGHT
                : j.isFull() ? FromItem.JoinKind.FULL
                : FromItem.JoinKind.INNER;
        Collection<Expression> ons = j.getOnExpressions();
        if (ons != null && ons.size() > 1) {
            throw unsupported("more than one ON", j);
        }
        SqlExpr on = ons == null || ons.isEmpty() ? null : expr(ons.iterator().next());
        List<String> using = new ArrayList<>();
        if (j.getUsingColumns() != null) {
            for (Column c : j.getUsingColumns()) {
                using.add(simpleName(c));
            }
        }
        int conditions = (on != null ? 1 : 0) + (using.isEmpty() ? 0 : 1) + (j.isNatural() ? 1 : 0);
        if (kind == FromItem.JoinKind.CROSS && conditions > 0) {
            throw new SqlException("Syntax error: CROSS JOIN takes no join condition", position(j));
        }
        if (kind != FromItem.JoinKind.CROSS && conditions != 1) {
            throw new SqlException(conditions == 0 ? "Syntax error: JOIN needs ON, USING or NATURAL"
                    : "Syntax error: JOIN takes only one of ON, USING and NATURAL", position(j));
        }
        return new FromItem.Join(kind, left, fromItem(j.getFromItem()), on, using, j.isNatural(), position(j));
    }

    private FromItem fromItem(net.sf.jsqlparser.statement.select.FromItem item) {
        Position position = position(item);
        if (item instanceof Table t) {
            reject(t.getPivot(), "PIVOT", t);
            reject(t.getUnPivot(), "UNPIVOT", t);
            reject(t.getSampleClause(), "TABLESAMPLE", t);
            reject(t.getTimeTravel(), "time travel", t);
            reject(t.getIndexHint(), "index hints", t);
            reject(t.getSqlServerHints(), "table hints", t);
            Token first = firstToken(t);
            if (first != null && first.image.startsWith("'")) {
                return new FromItem.FileName(stringValue(first.image, t), alias(t.getAlias(), t), position);
            }
            return new FromItem.TableName(nameParts(t), alias(t.getAlias(), t), position);
        }
        if (item instanceof TableFunction f) {
            // unnest and the series read the items before them anyway; LATERAL changes nothing there.
            boolean unnest = List.of("unnest", "range", "generate_series")
                    .contains(functionName(f.getFunction()).toLowerCase(Locale.ROOT));
            reject(unnest && "LATERAL".equalsIgnoreCase(f.getPrefix()) ? null : f.getPrefix(), "LATERAL", f);
            reject(f.isRowsFrom(), "ROWS FROM", f);
            reject(f.getWithClause(), "WITH ORDINALITY", f);
            reject(f.getPivot(), "PIVOT", f);
            reject(f.getUnPivot(), "UNPIVOT", f);
            reject(f.getSampleClause(), "TABLESAMPLE", f);
            return tableFunction(f.getFunction(), alias(f.getAlias(), f), position);
        }
        if (item instanceof LateralSubSelect) {
            throw unsupported("LATERAL", item);
        }
        if (item instanceof ParenthesedSelect p) {
            reject(p.getSampleClause(), "TABLESAMPLE", p);
            return new FromItem.Subquery(parenthesized(p), alias(p.getAlias(), p), position);
        }
        if (item instanceof Values v) {
            return new FromItem.Subquery(Query.of(values(v)), alias(v.getAlias(), v), position);
        }
        if (item instanceof ParenthesedFromItem p) {
            reject(p.getPivot(), "PIVOT", p);
            reject(p.getUnPivot(), "UNPIVOT", p);
            reject(p.getSampleClause(), "TABLESAMPLE", p);
            FromItem inner = joins(fromItem(p.getFromItem()), p.getJoins());
            return withAlias(inner, alias(p.getAlias(), p), p);
        }
        throw unsupported("this FROM item", item);
    }

    private FromItem withAlias(FromItem item, FromItem.Alias alias, Object node) {
        if (alias == null) {
            return item;
        }
        return switch (item) {
            case FromItem.TableName t when t.alias() == null -> new FromItem.TableName(t.name(), alias, t.position());
            case FromItem.FileName f when f.alias() == null -> new FromItem.FileName(f.path(), alias, f.position());
            case FromItem.Subquery s when s.alias() == null -> new FromItem.Subquery(s.query(), alias, s.position());
            case FromItem.TableFunctionCall f when f.alias() == null -> new FromItem.TableFunctionCall(f.name(),
                    f.arguments(), f.namedArguments(), alias, f.position());
            default -> throw unsupported("an alias for a join or an aliased item in parentheses", node);
        };
    }

    private FromItem tableFunction(Function f, FromItem.Alias alias, Position position) {
        checkFunction(f);
        reject(f.isDistinct(), "DISTINCT in a table function", f);
        reject(f.getOrderByElements(), "ORDER BY in a table function", f);
        reject(f.getNamedParameters(), "this form of arguments", f);
        List<SqlExpr> arguments = new ArrayList<>();
        Map<String, SqlExpr> named = new LinkedHashMap<>();
        if (f.getParameters() != null) {
            for (Expression p : f.getParameters()) {
                if (p instanceof EqualsTo eq && eq.getLeftExpression() instanceof Column c && nameParts(c).size() == 1) {
                    // A repeated name counts with its last value, as in DuckDB.
                    named.put(simpleName(c), expr(eq.getRightExpression()));
                } else {
                    arguments.add(expr(p));
                }
            }
        }
        return new FromItem.TableFunctionCall(functionName(f), arguments, named, alias, position);
    }

    private FromItem.Alias alias(Alias alias, Object node) {
        if (alias == null) {
            return null;
        }
        List<String> columns = new ArrayList<>();
        if (alias.getAliasColumns() != null) {
            for (Alias.AliasColumn column : alias.getAliasColumns()) {
                reject(column.colDataType, "types in an alias", node);
                columns.add(unquote(column.name, node));
            }
        }
        return new FromItem.Alias(aliasName(alias), columns);
    }

    // ---- expressions

    private SqlExpr exprOrNull(Expression e) {
        return e == null ? null : expr(e);
    }

    private List<SqlExpr> exprs(List<? extends Expression> list) {
        List<SqlExpr> result = new ArrayList<>(list.size());
        for (Expression e : list) {
            result.add(expr(e));
        }
        return result;
    }

    private SqlExpr expr(Expression e) {
        Position position = position(e);
        if (e instanceof Column c) {
            reject(c.getOldOracleJoinSyntax() != 0, "(+)", c);
            SqlExpr name = new SqlExpr.Identifier(nameParts(c), position);
            ArrayConstructor index = c.getArrayConstructor();
            if (index == null) {
                return name;
            }
            if (index.getExpressions() == null || index.getExpressions().size() != 1) {
                throw unsupported("this form of indexing", c);
            }
            return index(name, index.getExpressions().get(0), position);
        }
        if (e instanceof ArrayExpression a) {
            if (a.getIndexExpression() == null) {
                throw unsupported("slices", a);
            }
            return index(expr(a.getObjExpression()), a.getIndexExpression(), position);
        }
        if (e instanceof RowGetExpression r) {
            SqlExpr name = new SqlExpr.Literal(LiteralKind.STRING, unquote(r.getColumnName(), r), position);
            return new SqlExpr.Index(expr(r.getExpression()), name, position);
        }
        if (e instanceof ArrayConstructor a) {
            reject(a.getDataType(), "a typed array", a);
            return new SqlExpr.FunctionCall("list_value", a.getExpressions() == null ? List.of()
                    : exprs(a.getExpressions()), position);
        }
        if (e instanceof StructType s) {
            return struct(s, position);
        }
        if (e instanceof MapExpression m) {
            List<SqlExpr> keys = new ArrayList<>();
            List<SqlExpr> values = new ArrayList<>();
            for (Map.Entry<Expression, Expression> entry : m.getEntries()) {
                keys.add(expr(entry.getKey()));
                values.add(expr(entry.getValue()));
            }
            return new SqlExpr.FunctionCall("map", List.of(new SqlExpr.FunctionCall("list_value", keys, position),
                    new SqlExpr.FunctionCall("list_value", values, position)), position);
        }
        if (e instanceof PostgresNamedFunctionParameter) {
            throw unsupported("named arguments", e);
        }
        if (e instanceof DateUnitExpression d) {
            // JSqlParser reads a bare day, year, hour and so on as a unit; in an expression it is a column name.
            Token token = firstToken(d);
            String name = token != null ? source.originalText(token) : d.getType().name().toLowerCase(Locale.ROOT);
            return new SqlExpr.Identifier(List.of(name), position);
        }
        if (e instanceof LongValue || e instanceof DoubleValue) {
            Token token = firstToken(e);
            return new SqlExpr.Literal(LiteralKind.NUMBER, token != null ? token.image : e.toString(), position);
        }
        if (e instanceof StringValue s) {
            if (s.getPrefix() != null && s.getPrefix().equalsIgnoreCase("E") && "'".equals(s.getQuoteStr())) {
                return new SqlExpr.Literal(LiteralKind.STRING, escapeString(s.getValue(), position), position);
            }
            reject(s.getPrefix(), "a string with a prefix", s);
            Token token = firstToken(s);
            return new SqlExpr.Literal(LiteralKind.STRING, stringValue(token != null ? token.image
                    : s.getQuoteStr() + s.getValue() + s.getQuoteStr(), s), position);
        }
        if (e instanceof NullValue) {
            return new SqlExpr.Literal(LiteralKind.NULL, "NULL", position);
        }
        if (e instanceof BooleanValue b) {
            return new SqlExpr.Literal(LiteralKind.BOOLEAN, b.getValue() ? "true" : "false", position);
        }
        if (e instanceof DateTimeLiteralExpression d) {
            LiteralKind kind = switch (d.getType()) {
                case DATE -> LiteralKind.DATE;
                case TIMESTAMP -> LiteralKind.TIMESTAMP;
                case TIME -> LiteralKind.TIME;
                case TIMESTAMPTZ -> LiteralKind.TIMESTAMPTZ;
                default -> throw unsupported(d.getType() + " literals", d);
            };
            return new SqlExpr.Literal(kind, stringValue(d.getValue(), d), position);
        }
        if (e instanceof SignedExpression s) {
            UnaryOp op = switch (s.getSign()) {
                case '-' -> UnaryOp.MINUS;
                case '+' -> UnaryOp.PLUS;
                default -> throw unsupported("the operator " + s.getSign(), s);
            };
            return new SqlExpr.Unary(op, expr(s.getExpression()), position);
        }
        if (e instanceof NotExpression n) {
            reject(n.isExclamationMark(), "the operator !", n);
            if (n.getExpression() instanceof ExistsExpression x) {
                return exists(x, !x.isNot(), position);
            }
            return new SqlExpr.Unary(UnaryOp.NOT, expr(n.getExpression()), position);
        }
        if (e instanceof ParenthesedSelect p) {
            return new SqlExpr.ScalarSubquery(parenthesized(p), position);
        }
        if (e instanceof ExistsExpression x) {
            return exists(x, x.isNot(), position);
        }
        if (e instanceof AnyComparisonExpression) {
            throw unsupported("ANY and ALL outside a comparison", e);
        }
        if (e instanceof ParenthesedExpressionList<?> l) {
            // An expression in parentheses is a list of one.
            if (l.size() == 1) {
                return expr(l.get(0));
            }
            throw unsupported("row values", e);
        }
        if (e instanceof Between b) {
            reject(b.isUsingSymmetric(), "BETWEEN SYMMETRIC", b);
            reject(b.isUsingAsymmetric(), "BETWEEN ASYMMETRIC", b);
            reject(b.getOldOracleJoinSyntax() != 0, "(+)", b);
            return new SqlExpr.Between(expr(b.getLeftExpression()), expr(b.getBetweenExpressionStart()),
                    expr(b.getBetweenExpressionEnd()), b.isNot(), position);
        }
        if (e instanceof InExpression in) {
            reject(in.isGlobal(), "GLOBAL IN", in);
            reject(in.getOldOracleJoinSyntax() != 0, "(+)", in);
            Expression right = in.getRightExpression();
            SqlExpr value = rowOrExpr(in.getLeftExpression());
            if (right instanceof ParenthesedSelect p) {
                return new SqlExpr.InQuery(value, parenthesized(p), in.isNot(), position);
            }
            if (!(right instanceof ParenthesedExpressionList<?> list) || list.isEmpty()) {
                throw unsupported("IN without a list of values", right);
            }
            List<SqlExpr> values = new ArrayList<>();
            if (value instanceof SqlExpr.Row && list.size() > 1 && list.stream().noneMatch(
                    v -> v instanceof ParenthesedExpressionList<?>)) {
                // JSqlParser reads the one row of (a, b) IN ((1, 2)) as the list of its values.
                values.add(rowOrExpr(list));
            } else {
                for (Expression v : list) {
                    values.add(rowOrExpr(v));
                }
            }
            return new SqlExpr.InList(value, values, in.isNot(), position);
        }
        if (e instanceof LikeExpression l) {
            if (l.getLikeKeyWord() != LikeExpression.KeyWord.LIKE && l.getLikeKeyWord() != LikeExpression.KeyWord.ILIKE) {
                throw unsupported(String.valueOf(l.getLikeKeyWord()), l);
            }
            reject(l.getEscape(), "ESCAPE", l);
            reject(l.isUseBinary(), "LIKE BINARY", l);
            reject(l.getOldOracleJoinSyntax() != 0, "(+)", l);
            return new SqlExpr.Like(expr(l.getLeftExpression()), expr(l.getRightExpression()),
                    l.getLikeKeyWord() == LikeExpression.KeyWord.ILIKE, l.isNot(), position);
        }
        if (e instanceof IsDistinctExpression d) {
            return new SqlExpr.IsDistinct(expr(d.getLeftExpression()), expr(d.getRightExpression()), d.isNot(),
                    position);
        }
        if (e instanceof IsNullExpression n) {
            reject(n.getOldOracleJoinSyntax() != 0, "(+)", n);
            return new SqlExpr.IsNull(expr(n.getLeftExpression()), n.isNot(), position);
        }
        if (e instanceof IsBooleanExpression b) {
            // x IS TRUE is x IS NOT DISTINCT FROM TRUE, never NULL.
            SqlExpr value = new SqlExpr.Literal(LiteralKind.BOOLEAN, b.isTrue() ? "true" : "false", position);
            return new SqlExpr.IsDistinct(expr(b.getLeftExpression()), value, !b.isNot(), position);
        }
        if (e instanceof BinaryExpression b) {
            return binary(b, position);
        }
        if (e instanceof CaseExpression c) {
            List<SqlExpr.When> whens = new ArrayList<>();
            for (WhenClause w : c.getWhenClauses()) {
                whens.add(new SqlExpr.When(expr(w.getWhenExpression()), expr(w.getThenExpression())));
            }
            return new SqlExpr.Case(exprOrNull(c.getSwitchExpression()), whens, exprOrNull(c.getElseExpression()),
                    position);
        }
        if (e instanceof CastExpression c) {
            return cast(c, position);
        }
        if (e instanceof AnalyticExpression a) {
            return window(a, position);
        }
        if (e instanceof TrimFunction t) {
            String name = t.getTrimSpecification() == null ? "trim" : switch (t.getTrimSpecification()) {
                case BOTH -> "trim";
                case LEADING -> "ltrim";
                case TRAILING -> "rtrim";
            };
            Expression string = t.isUsingFromKeyword() ? t.getFromExpression() : t.getExpression();
            Expression characters = t.isUsingFromKeyword() ? t.getExpression() : t.getFromExpression();
            List<SqlExpr> arguments = new ArrayList<>();
            arguments.add(expr(string));
            if (characters != null) {
                arguments.add(expr(characters));
            }
            return new SqlExpr.FunctionCall(name, arguments, position);
        }
        if (e instanceof ExtractExpression x) {
            SqlExpr part = new SqlExpr.Literal(LiteralKind.STRING, x.getName(), position);
            return new SqlExpr.FunctionCall("date_part", List.of(part, expr(x.getExpression())), position);
        }
        if (e instanceof TimeKeyExpression t) {
            String name = t.getStringValue().strip();
            if (name.endsWith("()")) {
                name = name.substring(0, name.length() - 2).strip();
            }
            if (!name.matches("[A-Za-z_]+")) {
                throw unsupported(t.getStringValue(), t);
            }
            return new SqlExpr.FunctionCall(name.toLowerCase(Locale.ROOT), List.of(), position);
        }
        if (e instanceof Function f) {
            return functionCall(f, position);
        }
        if (e instanceof JdbcParameter p) {
            return parameter(p, position);
        }
        if (e instanceof JdbcNamedParameter) {
            throw unsupported("named parameters", e);
        }
        if (e instanceof IntervalExpression iv) {
            return interval(iv, position);
        }
        if (e instanceof TimezoneExpression tz) {
            // x AT TIME ZONE a AT TIME ZONE b is timezone(b, timezone(a, x)).
            SqlExpr result = expr(tz.getLeftExpression());
            for (Expression zone : tz.getTimezoneExpressions()) {
                result = new SqlExpr.FunctionCall("timezone", List.of(expr(zone), result), position);
            }
            return result;
        }
        if (e instanceof LambdaExpression) {
            throw unsupported("lambda functions", e);
        }
        if (e instanceof AllColumns) {
            throw unsupported("* here", e);
        }
        throw unsupported("the expression " + e, e);
    }

    /**
     * A {@code ?}, numbered as JSqlParser numbers it, from 1 in the order of
     * the text, or a {@code $n} of PostgreSQL and DuckDB with its own
     * number, which may stand more than once. One statement uses one of the
     * two forms; {@code ?1} is not supported.
     */
    private SqlExpr parameter(JdbcParameter p, Position position) {
        if (parameters == null) {
            throw unsupported("parameters outside a prepared query", p);
        }
        String form = p.getParameterCharacter();
        boolean dollar = "$".equals(form) && p.isUseFixedIndex();
        if (!dollar && (p.isUseFixedIndex() || !"?".equals(form)) || p.getIndex() == null) {
            throw unsupported("numbered parameters", p);
        }
        if (dollar && p.getIndex() < 1) {
            throw new SqlException("There is no parameter $" + p.getIndex(), position);
        }
        if (parameterForm != null && !parameterForm.equals(form)) {
            throw unsupported("parameters ? and $n in one statement", p);
        }
        parameterForm = form;
        SqlExpr.Parameter parameter = new SqlExpr.Parameter(p.getIndex(), position);
        parameters.add(parameter);
        return parameter;
    }

    /**
     * {@code INTERVAL '90' DAY}, {@code INTERVAL 1 YEAR} or
     * {@code INTERVAL '1 day 02:03:04'}: a number and a unit, or the text of
     * an interval, which the binder reads. A qualifier of two fields, such
     * as {@code DAY TO SECOND}, is not supported; a precision such as
     * {@code DAY (3)} is ignored.
     */
    @SuppressWarnings("deprecation")
    private SqlExpr interval(IntervalExpression iv, Position position) {
        String value;
        Expression x = iv.getExpression();
        if (x instanceof StringValue s) {
            value = s.getValue();
        } else if (x instanceof LongValue l) {
            value = l.getStringValue();
        } else if (x == null && iv.getParameter() != null) {
            String p = iv.getParameter().strip();
            value = p.length() >= 2 && p.startsWith("'") && p.endsWith("'") ? stringValue(p, iv) : p;
        } else {
            throw unsupported("INTERVAL with an expression", iv);
        }
        String unit;
        if (iv.getIntervalQualifier() != null) {
            reject(iv.getIntervalQualifier().getTrailingField(), "an interval with two fields", iv);
            unit = iv.getIntervalQualifier().getLeadingField();
        } else {
            unit = iv.getIntervalType();
        }
        if (unit == null) {
            // The text of an interval: INTERVAL '1 day 02:03:04'.
            return new SqlExpr.Interval(value, null, position);
        }
        unit = unit.strip().toUpperCase(Locale.ROOT);
        if (!unit.matches("[A-Z]+")) {
            throw unsupported("the interval unit " + unit, iv);
        }
        return new SqlExpr.Interval(value.strip(), unit, position);
    }

    /** {@code base[index]}; a range {@code base[a:b]} is a slice, which is not supported. */
    private SqlExpr index(SqlExpr base, Expression index, Position position) {
        if (index instanceof JsonExpression) {
            throw unsupported("slices", index);
        }
        return new SqlExpr.Index(base, expr(index), position);
    }

    /** DuckDB's {@code {'a': x, b: y}}, whose names are strings or identifiers. */
    private SqlExpr struct(StructType s, Position position) {
        if (s.getDialect() != StructType.Dialect.DUCKDB || s.getArguments() == null || s.getArguments().isEmpty()
                || s.getParameters() != null) {
            throw unsupported("this form of STRUCT", s);
        }
        List<String> names = new ArrayList<>();
        List<SqlExpr> values = new ArrayList<>();
        for (net.sf.jsqlparser.statement.select.SelectItem<?> item : s.getArguments()) {
            if (item.getAlias() == null) {
                throw new SqlException("Syntax error: a field of a struct needs a name", position(item));
            }
            String name = item.getAlias().getName();
            names.add(name.startsWith("'") ? stringValue(name, s) : unquote(name, s));
            values.add(expr(item.getExpression()));
        }
        return new SqlExpr.StructValue(names, values, position);
    }

    /**
     * {@code struct_pack(a := x, y)}: a struct of named arguments, where a
     * column takes its own name.
     */
    private SqlExpr structPack(Function f, Position position) {
        reject(f.isDistinct(), "DISTINCT in struct_pack", f);
        reject(f.getOrderByElements(), "ORDER BY in struct_pack", f);
        if (f.getParameters() == null || f.getParameters().isEmpty()) {
            throw new SqlException("Can't pack nothing into a struct", position);
        }
        List<String> names = new ArrayList<>();
        List<SqlExpr> values = new ArrayList<>();
        for (Expression p : f.getParameters()) {
            if (p instanceof PostgresNamedFunctionParameter n) {
                names.add(unquote(n.getName(), n));
                values.add(expr(n.getExpression()));
            } else if (p instanceof Column c && c.getArrayConstructor() == null) {
                SqlExpr.Identifier name = (SqlExpr.Identifier) expr(c);
                names.add(name.name());
                values.add(name);
            } else {
                throw new SqlException("Need named argument for struct pack, e.g. STRUCT_PACK(a := b)", position(p));
            }
        }
        return new SqlExpr.StructValue(names, values, position);
    }

    /** An expression, or a row of two values or more in parentheses, which only {@code IN} takes. */
    private SqlExpr rowOrExpr(Expression e) {
        if (e instanceof ParenthesedExpressionList<?> l && l.size() > 1) {
            return new SqlExpr.Row(exprs(l), position(e));
        }
        return expr(e);
    }

    private SqlExpr binary(BinaryExpression b, Position position) {
        if (b instanceof OldOracleJoinBinaryExpression o) {
            reject(o.getOldOracleJoinSyntax() != 0, "(+)", b);
            reject(o.getOraclePriorPosition() != 0, "PRIOR", b);
        }
        String symbol = b.getStringExpression().strip().toUpperCase(Locale.ROOT);
        BinaryOp op;
        if (b instanceof Addition) {
            op = BinaryOp.ADD;
        } else if (b instanceof Subtraction) {
            op = BinaryOp.SUBTRACT;
        } else if (b instanceof Multiplication) {
            op = BinaryOp.MULTIPLY;
        } else if (b instanceof Division) {
            op = BinaryOp.DIVIDE;
        } else if (b instanceof IntegerDivision) {
            op = BinaryOp.INT_DIVIDE;
        } else if (b instanceof Modulo) {
            op = BinaryOp.MODULO;
        } else if (b instanceof Concat) {
            op = BinaryOp.CONCAT;
        } else if (b instanceof EqualsTo && symbol.equals("=")) {
            op = BinaryOp.EQUAL;
        } else if (b instanceof NotEqualsTo && (symbol.equals("<>") || symbol.equals("!="))) {
            op = BinaryOp.NOT_EQUAL;
        } else if (b instanceof MinorThan) {
            op = BinaryOp.LESS;
        } else if (b instanceof MinorThanEquals) {
            op = BinaryOp.LESS_EQUAL;
        } else if (b instanceof GreaterThan) {
            op = BinaryOp.GREATER;
        } else if (b instanceof GreaterThanEquals) {
            op = BinaryOp.GREATER_EQUAL;
        } else if (b instanceof AndExpression && symbol.equals("AND")) {
            op = BinaryOp.AND;
        } else if (b instanceof OrExpression && symbol.equals("OR")) {
            op = BinaryOp.OR;
        } else if (b instanceof RegExpMatchOperator r
                && (r.getOperatorType() == RegExpMatchOperatorType.MATCH_CASESENSITIVE
                        || r.getOperatorType() == RegExpMatchOperatorType.NOT_MATCH_CASESENSITIVE)) {
            // As in DuckDB, ~ matches the whole string, unlike in PostgreSQL.
            SqlExpr match = new SqlExpr.FunctionCall("regexp_full_match",
                    List.of(expr(b.getLeftExpression()), expr(b.getRightExpression())), position);
            return r.getOperatorType() == RegExpMatchOperatorType.MATCH_CASESENSITIVE ? match
                    : new SqlExpr.Unary(UnaryOp.NOT, match, position);
        } else {
            throw unsupported("the operator " + b.getStringExpression().strip(), b);
        }
        if (b.getRightExpression() instanceof AnyComparisonExpression any) {
            if (op.ordinal() < BinaryOp.EQUAL.ordinal() || op.ordinal() > BinaryOp.GREATER_EQUAL.ordinal()) {
                throw unsupported("ANY and ALL with " + op.symbol(), any);
            }
            Query query = any.getSelect() instanceof ParenthesedSelect p ? parenthesized(p) : query(any.getSelect());
            return new SqlExpr.Quantified(op, expr(b.getLeftExpression()), query, any.getAnyType() == AnyType.ALL,
                    position);
        }
        return new SqlExpr.Binary(op, expr(b.getLeftExpression()), expr(b.getRightExpression()), position);
    }

    private SqlExpr cast(CastExpression c, Position position) {
        boolean safe = false;
        if (c.keyword != null) {
            String keyword = c.keyword.strip().toUpperCase(Locale.ROOT);
            if (keyword.equals("TRY_CAST")) {
                safe = true;
            } else if (!keyword.equals("CAST")) {
                throw unsupported(keyword, c);
            }
        }
        reject(c.getFormat(), "CAST with FORMAT", c);
        reject(c.getColumnDefinitions(), "CAST to a row type", c);
        if (c.getColDataType() == null) {
            throw unsupported("this CAST", c);
        }
        DataType type;
        try {
            type = TypeNames.parse(c.getColDataType().toString());
        } catch (IllegalArgumentException ex) {
            throw new SqlException(ex.getMessage(), position);
        }
        return new SqlExpr.Cast(expr(c.getLeftExpression()), type, safe, position);
    }

    /**
     * {@code f(args) [FILTER (WHERE c)] OVER window}. JSqlParser keeps at
     * most three arguments here, as expression, offset and default value,
     * and drops arguments of other forms, such as those of
     * {@code position(a IN b)}, which are therefore not supported.
     * {@code IGNORE NULLS} may stand inside or after the parentheses;
     * {@code RESPECT NULLS} is the default. JSqlParser gives the
     * {@code IGNORE NULLS} after a last argument that is a call to that
     * call, as in {@code lag(a, 1, f(b) IGNORE NULLS)}; it belongs to the
     * window function.
     */
    private SqlExpr window(AnalyticExpression a, Position position) {
        boolean filterOnly = a.getType() == AnalyticType.FILTER_ONLY;
        if (!filterOnly && a.getType() != AnalyticType.OVER) {
            throw unsupported("WITHIN GROUP", a);
        }
        reject(a.getKeep(), "KEEP", a);
        reject(a.isUnique(), "UNIQUE in a function", a);
        reject(a.getHavingClause(), "HAVING in a function", a);
        reject(a.getLimit(), "LIMIT in a function", a);
        reject(a.getOnOverflowTruncate(), "ON OVERFLOW", a);
        reject(a.getKeywordArguments(), "this form of arguments", a);
        Token first = nameToken(firstToken(a), a);
        String name = first != null ? unquote(first.image, a) : unquote(a.getName(), a);
        boolean star = false;
        boolean ignoreNulls = a.isIgnoreNulls();
        List<SqlExpr> arguments = new ArrayList<>();
        Expression x = a.getExpression();
        Expression lastArgument = a.getDefaultValue() != null ? a.getDefaultValue()
                : a.getOffset() != null ? a.getOffset() : x;
        while (lastArgument instanceof ExpressionList<?> l && l.size() == 1) {
            lastArgument = l.get(0);
        }
        if (lastArgument instanceof Function f && f.getNullHandling() != null) {
            ignoreNulls |= f.getNullHandling() == Function.NullHandling.IGNORE_NULLS;
            f.setNullHandling(null);
            f.setIgnoreNullsOutside(false);
        }
        if (x instanceof AllColumns && !(x instanceof AllTableColumns) && a.getOffset() == null) {
            star = true;
        } else if (x != null) {
            for (Expression p : new Expression[] {x, a.getOffset(), a.getDefaultValue()}) {
                if (p instanceof PostgresNamedFunctionParameter) {
                    throw unsupported("named arguments", a);
                }
                if (p != null) {
                    arguments.add(expr(p));
                }
            }
        } else if (first != null && !(first.next != null && "(".equals(first.next.image)
                && first.next.next != null && ")".equals(first.next.next.image))) {
            throw unsupported("this form of arguments of " + name, a);
        }
        SqlExpr.FunctionCall call = new SqlExpr.FunctionCall(name, arguments, a.isDistinct(), star,
                orderItems(a.getFuncOrderBy(), false), position);
        if (filterOnly) {
            if (ignoreNulls) {
                throw unsupported("IGNORE NULLS without OVER", a);
            }
            return call.withFilter(expr(a.getFilterExpression()));
        }
        WindowSpec over = a.getWindowName() != null ? WindowSpec.named(unquote(a.getWindowName(), a))
                : windowSpec(a.getWindowDefinition(), a);
        return new SqlExpr.Window(call, over, ignoreNulls, exprOrNull(a.getFilterExpression()), position);
    }

    private WindowSpec windowSpec(WindowDefinition d, Object node) {
        List<? extends Expression> partition = d.getPartitionBy();
        // PARTITION BY (x, y) is a row of the columns, which partitions as the columns do.
        if (partition.size() == 1 && partition.get(0) instanceof ParenthesedExpressionList<?> row) {
            partition = row;
        }
        List<SqlExpr> partitionBy = exprs(partition);
        List<OrderItem> orderBy = orderItems(d.getOrderByElements(), false);
        WindowElement element = d.getWindowElement();
        return new WindowSpec(null, partitionBy, orderBy, element == null ? null : frame(element, node));
    }

    /**
     * {@code unit BETWEEN start AND end [EXCLUDE ...]}, or {@code unit start}
     * with {@code CURRENT ROW} as its end; the order of the bounds is
     * checked as DuckDB's parser checks it.
     */
    private WindowSpec.Frame frame(WindowElement element, Object node) {
        WindowSpec.FrameUnit unit = switch (element.getType()) {
            case ROWS -> WindowSpec.FrameUnit.ROWS;
            case RANGE -> WindowSpec.FrameUnit.RANGE;
            case GROUPS -> WindowSpec.FrameUnit.GROUPS;
        };
        WindowSpec.Bound start;
        WindowSpec.Bound end;
        if (element.getRange() != null) {
            start = bound(element.getRange().getStart(), node);
            end = bound(element.getRange().getEnd(), node);
        } else {
            start = bound(element.getOffset(), node);
            end = WindowSpec.Bound.of(WindowSpec.BoundKind.CURRENT_ROW);
        }
        WindowSpec.BoundKind s = start.kind();
        WindowSpec.BoundKind e = end.kind();
        String error = s == WindowSpec.BoundKind.UNBOUNDED_FOLLOWING ? "frame start cannot be UNBOUNDED FOLLOWING"
                : e == WindowSpec.BoundKind.UNBOUNDED_PRECEDING ? "frame end cannot be UNBOUNDED PRECEDING"
                : s == WindowSpec.BoundKind.CURRENT_ROW && e == WindowSpec.BoundKind.PRECEDING
                        ? "frame starting from current row cannot have preceding rows"
                : s == WindowSpec.BoundKind.FOLLOWING && e == WindowSpec.BoundKind.CURRENT_ROW
                        ? "frame starting from following row cannot end with current row"
                : s == WindowSpec.BoundKind.FOLLOWING && e == WindowSpec.BoundKind.PRECEDING
                        ? "frame starting from following row cannot have preceding rows"
                : null;
        if (error != null) {
            throw new SqlException(error, position(node));
        }
        WindowSpec.Exclusion exclusion = element.getExclusion() == null ? WindowSpec.Exclusion.NO_OTHERS
                : switch (element.getExclusion()) {
                    case CURRENT_ROW -> WindowSpec.Exclusion.CURRENT_ROW;
                    case GROUP -> WindowSpec.Exclusion.GROUP;
                    case TIES -> WindowSpec.Exclusion.TIES;
                    case NO_OTHERS -> WindowSpec.Exclusion.NO_OTHERS;
                };
        return new WindowSpec.Frame(unit, start, end, exclusion);
    }

    private WindowSpec.Bound bound(WindowOffset offset, Object node) {
        if (offset.getExpression() == null) {
            return WindowSpec.Bound.of(switch (offset.getType()) {
                case PRECEDING -> WindowSpec.BoundKind.UNBOUNDED_PRECEDING;
                case FOLLOWING -> WindowSpec.BoundKind.UNBOUNDED_FOLLOWING;
                case CURRENT -> WindowSpec.BoundKind.CURRENT_ROW;
                case EXPR -> throw unsupported("this frame", node);
            });
        }
        return new WindowSpec.Bound(switch (offset.getType()) {
            case PRECEDING -> WindowSpec.BoundKind.PRECEDING;
            case FOLLOWING -> WindowSpec.BoundKind.FOLLOWING;
            case CURRENT, EXPR -> throw unsupported("this frame", node);
        }, expr(offset.getExpression()));
    }

    private SqlExpr functionCall(Function f, Position position) {
        checkFunction(f);
        String name = functionName(f);
        if (name.equalsIgnoreCase("struct_pack")) {
            return structPack(f, position);
        }
        if (f.getParameters() != null && f.getParameters().stream()
                .anyMatch(p -> p instanceof PostgresNamedFunctionParameter)) {
            throw unsupported("named arguments", f);
        }
        NamedExpressionList<?> named = f.getNamedParameters();
        if (named != null) {
            return specialForm(name, named, f, position);
        }
        // JSqlParser marks f(ALL x) as all columns; ALL is the default quantifier, as in DuckDB.
        boolean star = false;
        List<SqlExpr> arguments = new ArrayList<>();
        if (f.getParameters() != null) {
            ExpressionList<?> parameters = f.getParameters();
            for (Expression p : parameters) {
                if (p instanceof AllColumns && !(p instanceof AllTableColumns) && parameters.size() == 1) {
                    star = true;
                } else {
                    arguments.add(expr(p));
                }
            }
        }
        return new SqlExpr.FunctionCall(name, arguments, f.isDistinct(), star,
                orderItems(f.getOrderByElements(), false), position);
    }

    /** {@code POSITION(a IN b)} and {@code SUBSTRING(s FROM a FOR b)}, as calls of the functions. */
    private SqlExpr specialForm(String name, NamedExpressionList<?> named, Function f, Position position) {
        List<String> keywords = named.getNames().stream().map(k -> k.strip().toUpperCase(Locale.ROOT)).toList();
        List<SqlExpr> arguments = exprs(named);
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.equals("position") && keywords.equals(List.of("", "IN"))) {
            return new SqlExpr.FunctionCall("strpos", List.of(arguments.get(1), arguments.get(0)), position);
        }
        if (lower.equals("substring") || lower.equals("substr")) {
            if (keywords.equals(List.of("", "FROM", "FOR")) || keywords.equals(List.of("", "FROM"))) {
                return new SqlExpr.FunctionCall(name, arguments, position);
            }
            if (keywords.equals(List.of("", "FOR"))) {
                SqlExpr one = new SqlExpr.Literal(LiteralKind.NUMBER, "1", position);
                return new SqlExpr.FunctionCall(name, List.of(arguments.get(0), one, arguments.get(1)), position);
            }
        }
        throw unsupported("this form of arguments of " + name, f);
    }

    /** Rejects the parts of a function call that other dialects add. */
    private void checkFunction(Function f) {
        if (f.getMultipartName() != null && f.getMultipartName().size() > 2) {
            throw unsupported("qualified function names", f);
        }
        nameToken(firstToken(f), f);
        reject(f.getKeep(), "KEEP", f);
        reject(f.getNullHandling(), "IGNORE NULLS and RESPECT NULLS", f);
        reject(f.isIgnoreNullsOutside(), "IGNORE NULLS", f);
        reject(f.getHavingClause(), "HAVING in a function", f);
        reject(f.getLimit(), "LIMIT in a function", f);
        reject(f.isUnique(), "UNIQUE in a function", f);
        reject(f.isEscaped(), "{fn ...}", f);
        reject(f.getAttribute(), "field access on a function result", f);
        reject(f.getChainedParameters(), "chained arguments", f);
        reject(f.getOnOverflowTruncate(), "ON OVERFLOW", f);
        reject(f.getExtraKeyword(), "this form of arguments", f);
        reject(f.getKeywordArguments(), "this form of arguments", f);
    }

    private String functionName(Function f) {
        Token first = nameToken(firstToken(f), f);
        return first != null ? unquote(first.image, f) : unquote(f.getName(), f);
    }

    /**
     * Returns the token of the name of a function, after a qualifier
     * {@code main.}, the schema that DuckDB keeps its functions in; another
     * qualifier is not supported.
     *
     * @param first the first token of the call, or {@code null}
     */
    private Token nameToken(Token first, Object node) {
        if (first == null || first.next == null || !".".equals(first.next.image)) {
            return first;
        }
        String qualifier = unquote(first.image, node).toLowerCase(Locale.ROOT);
        if (!qualifier.equals("main") || first.next.next == null) {
            throw unsupported("qualified function names", node);
        }
        return first.next.next;
    }

    // ---- names and tokens

    /** Returns the parts of a dotted name, read from the tokens of the query text; a trailing {@code .*} is no part. */
    private List<String> nameParts(ASTNodeAccess node) {
        Token token = firstToken(node);
        if (token == null) {
            String name = node instanceof Column c ? c.getFullyQualifiedName() : ((Table) node).getFullyQualifiedName();
            List<String> parts = new ArrayList<>();
            for (String part : name.split("\\.")) {
                parts.add(unquote(part, node));
            }
            return parts;
        }
        List<String> parts = new ArrayList<>();
        parts.add(unquote(token.image, node));
        while (token.next != null && ".".equals(token.next.image) && token.next.next != null
                && !"*".equals(token.next.next.image)) {
            token = token.next.next;
            parts.add(unquote(token.image, node));
        }
        return parts;
    }

    /** Returns the name of a column that must have one part. */
    private String simpleName(Expression e) {
        if (!(e instanceof Column c)) {
            throw new SqlException("Syntax error: expected a name", position(e));
        }
        List<String> parts = nameParts(c);
        if (parts.size() != 1) {
            throw new SqlException("Syntax error: expected a name without a qualifier", position(c));
        }
        return parts.get(0);
    }

    private String aliasName(Alias alias) {
        return unquote(alias.getName(), alias);
    }

    /** Returns a name without its double quotes; other quotes, which JSqlParser accepts for names, are errors. */
    private String unquote(String image, Object node) {
        if (image.length() >= 2 && image.startsWith("\"") && image.endsWith("\"")) {
            return image.substring(1, image.length() - 1).replace("\"\"", "\"");
        }
        if (image.startsWith("`") || image.startsWith("[") || image.startsWith("'")) {
            throw new SqlException("Syntax error at or near \"" + image + "\"", position(node));
        }
        return image;
    }

    /** Returns the value of a string literal in single quotes or dollar quotes. */
    private String stringValue(String image, Object node) {
        if (image.length() >= 2 && image.startsWith("'") && image.endsWith("'")) {
            return image.substring(1, image.length() - 1).replace("''", "'");
        }
        if (image.length() >= 4 && image.startsWith("$$") && image.endsWith("$$")) {
            return image.substring(2, image.length() - 2);
        }
        throw unsupported("the string " + image, node);
    }

    /**
     * Returns the value of a string {@code E'...'}, given its text between
     * the quotes: a doubled quote is one quote, and a backslash starts an
     * escape as in PostgreSQL: before {@code b}, {@code f}, {@code n},
     * {@code r} and {@code t} the control character, before up to three
     * octal digits their character, before {@code x} with one or two hex
     * digits, {@code u} with four and {@code U} with eight the character of
     * that code, and before any other character that character, as in two
     * backslashes for one. The lexer of JSqlParser does not take a
     * backslash before a quote; a quote is written as two.
     */
    static String escapeString(String text, Position position) {
        StringBuilder b = new StringBuilder(text.length());
        int n = text.length();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\'' && i + 1 < n && text.charAt(i + 1) == '\'') {
                b.append('\'');
                i += 2;
                continue;
            }
            if (c != '\\' || i + 1 == n) {
                b.append(c);
                i++;
                continue;
            }
            char x = text.charAt(i + 1);
            i += 2;
            switch (x) {
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'n' -> b.append('\n');
                case 'r' -> b.append('\r');
                case 't' -> b.append('\t');
                case 'x', 'u', 'U' -> {
                    int max = x == 'x' ? 2 : x == 'u' ? 4 : 8;
                    int end = i;
                    while (end < n && end - i < max && Character.digit(text.charAt(end), 16) >= 0) {
                        end++;
                    }
                    if (end == i || x != 'x' && end - i < max) {
                        throw new SqlException("Invalid Unicode escape: \\" + x + text.substring(i, end), position);
                    }
                    int code = Integer.parseInt(text, i, end, 16);
                    if (!Character.isValidCodePoint(code) || code == 0) {
                        throw new SqlException("Invalid Unicode escape value: \\" + x + text.substring(i, end),
                                position);
                    }
                    b.appendCodePoint(code);
                    i = end;
                }
                default -> {
                    if (x >= '0' && x <= '7') {
                        int end = i;
                        while (end < n && end - i < 2 && text.charAt(end) >= '0' && text.charAt(end) <= '7') {
                            end++;
                        }
                        int code = Integer.parseInt(text, i - 1, end, 8);
                        if (code == 0) {
                            throw new SqlException("Invalid escape: a string cannot hold the character 0", position);
                        }
                        b.append((char) code);
                        i = end;
                    } else {
                        b.append(x);
                    }
                }
            }
        }
        return b.toString();
    }

    private static Token firstToken(Object node) {
        if (node instanceof ASTNodeAccess a && a.getASTNode() != null) {
            return a.getASTNode().jjtGetFirstToken();
        }
        return null;
    }

    private Position position(Object node) {
        Token token = firstToken(node);
        if (token != null) {
            return source.position(token);
        }
        return switch (node) {
            case BinaryExpression b -> position(b.getLeftExpression());
            case Between b -> position(b.getLeftExpression());
            case IsNullExpression n -> position(n.getLeftExpression());
            case IsBooleanExpression b -> position(b.getLeftExpression());
            case NotExpression n -> position(n.getExpression());
            case SignedExpression s -> position(s.getExpression());
            case CastExpression c -> position(c.getLeftExpression());
            case ParenthesedSelect p -> position(p.getSelect());
            // Without a node of their own, these start where their query does.
            case ExistsExpression x -> position(x.getRightExpression());
            case AnyComparisonExpression a -> position(a.getSelect());
            case null, default -> Position.UNKNOWN;
        };
    }

    // ---- errors

    private SqlException unsupported(String what, Object node) {
        return new SqlException("Not supported: " + what, position(node));
    }

    /** Returns the name of a kind of node as words, e.g. {@code CREATE TABLE} for {@code CreateTable}. */
    private static String words(Object node) {
        return node.getClass().getSimpleName().replaceAll("([a-z])([A-Z])", "$1 $2").toUpperCase(Locale.ROOT);
    }

    /**
     * Rejects a clause that is present: not {@code null}, not {@code false},
     * not an empty collection, map or string.
     */
    private void reject(Object value, String what, Object node) {
        boolean present = value != null && !Boolean.FALSE.equals(value)
                && !(value instanceof Collection<?> c && c.isEmpty())
                && !(value instanceof Map<?, ?> m && m.isEmpty())
                && !(value instanceof String s && s.isBlank());
        if (present) {
            throw unsupported(what, node);
        }
    }
}
