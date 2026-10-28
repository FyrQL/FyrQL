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

import net.fyrql.connector.BatchReader;
import net.fyrql.connector.Capabilities;
import net.fyrql.connector.Catalog;
import net.fyrql.connector.Connector;
import net.fyrql.connector.RemotePlan;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.connector.SourceCounters;
import net.fyrql.connector.Split;
import net.fyrql.connector.Writer;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.expr.Expressions;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.sql.render.BoundSql;
import net.fyrql.sql.render.Dialect;
import net.fyrql.sql.render.GenericDialect;
import net.fyrql.sql.render.SqlRenderer;

import javax.sql.DataSource;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The tables of a database over JDBC: H2, SQLite, PostgreSQL or MySQL with
 * a dialect of their own, any other database with the
 * {@linkplain JdbcDialect#GENERIC generic dialect}, which takes over only
 * filters on integers and dates, sends conditions on strings as hints and
 * binds its literals as parameters, see {@link GenericDialect}.
 * <p>
 * A scan becomes one statement in the dialect of the database, which
 * selects the columns the query needs and takes over what the database
 * evaluates exactly as FyrQL does: the filters the dialect
 * {@linkplain net.fyrql.sql.render.Dialect#supports supports}, the order
 * if the dialect orders all its keys alike, and the limit; or instead of
 * order and limit an aggregation the dialect
 * {@linkplain net.fyrql.sql.render.Dialect#supportsAggregation supports}.
 * What it does not take over the engine evaluates; the results are the
 * same either way. The statement is one flat {@code SELECT ... FROM table
 * WHERE ... ORDER BY ... LIMIT n}, or {@code SELECT groups, aggregates
 * FROM table WHERE ... GROUP BY groups}; {@link Builder#recordStatements}
 * keeps every statement sent, which shows what the database did. A part of
 * a plan that joins tables of the database only becomes one statement as
 * well, nested a {@code SELECT} per node, when the database evaluates all
 * of it as FyrQL does, see {@link #planRemote}.
 * <p>
 * With {@link Builder#splitRows} a scan of a large table is read in parts,
 * one statement per range of a key column, which the engine reads on
 * several threads at once, each over a connection of its own; see
 * {@link #splits(ScanPlan, int)}. On one thread, a scan stays one
 * statement. Each part is a transaction of its own, so the parts
 * may see different states of a table that changes while it is read.
 * <p>
 * The connector writes tables, see {@link #writer()}: it creates them in
 * the types of its dialect, appends rows through a prepared statement in
 * batches of {@link Builder#batchRows} and drops them, each statement of
 * FyrQL one transaction of the database.
 * <p>
 * Connections come from a URL or a data source, one per statement, closed
 * when its result is read; or the application shares one connection,
 * which it closes itself, e.g. for an in-memory database that lives as
 * long as its connection. The catalog lists the default schema of the
 * database as {@code main}, see {@link #catalog()}; it is read once and
 * kept until {@link #refresh()}.
 */
public final class JdbcConnector implements Connector {

    /** The rows a statement fetches at a time unless set. */
    public static final int DEFAULT_FETCH_SIZE = 1000;

    /** The rows of a batch unless set. */
    public static final int DEFAULT_BATCH_SIZE = 2048;

    /** The rows a write sends at a time unless set. */
    public static final int DEFAULT_BATCH_ROWS = 1000;

    /**
     * The most parts a scan is read in, see {@link Builder#splitRows}: the
     * database works for every part at once, and on one machine more
     * parts gain nothing.
     */
    public static final int MAX_SPLITS = 8;

    /** How the generic dialect keeps the first rows of a statement, see {@link Builder#limitStyle}. */
    public enum LimitStyle {
        /** By the driver, with {@code Statement.setMaxRows}; every database has it. */
        MAX_ROWS,
        /** {@code FETCH FIRST n ROWS ONLY}, as in SQL:2008, Oracle 12c, Db2 and Derby. */
        FETCH_FIRST,
        /** {@code LIMIT n}. */
        LIMIT,
        /** {@code SELECT TOP n}, as in SQL Server and Sybase. */
        TOP
    }

    /**
     * A type whose values the database of the generic dialect is trusted to
     * store, compare and order as FyrQL does, see {@link Builder#trust}.
     */
    public enum Trust {
        DECIMAL, DOUBLE, TIMESTAMP, BOOLEAN
    }

    private final String name;
    private final Connections connections;
    private final JdbcDialect dialect;
    private final Dialect sql;
    private final JdbcCatalog catalog;
    private final JdbcWriter writer;
    private final int fetchSize;
    private final int batchSize;
    private final Consumer<String> listener;
    private final List<String> statements;
    private final long splitRows;
    private final Map<String, String> splitColumns;

    /**
     * The state of a scan plan: its statement, and the scan it renders,
     * from which the statements of its splits are rendered.
     */
    private record ScanState(BoundSql sql, PlanNode.Scan scan) {
        @Override
        public String toString() {
            return sql.toString();
        }
    }

    private JdbcConnector(Builder b, Connections connections, JdbcDialect dialect, Dialect sql) {
        this.name = b.name;
        this.connections = connections;
        this.dialect = dialect;
        this.sql = sql;
        this.catalog = new JdbcCatalog(b.name, connections, dialect, sql, b.schemas, b.defaultSchema);
        this.fetchSize = b.fetchSize;
        this.batchSize = b.batchSize;
        this.listener = b.listener;
        this.statements = b.recordStatements ? new ArrayList<>() : null;
        this.writer = new JdbcWriter(b.name, connections, dialect, sql, catalog, b.batchRows, this::record);
        this.splitRows = b.splitRows;
        this.splitColumns = Map.copyOf(b.splitColumns);
    }

    /** Starts a connector with the name under which it will be registered. */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    @Override
    public String name() {
        return name;
    }

    /** Returns the dialect of the database. */
    public JdbcDialect dialect() {
        return dialect;
    }

    /**
     * Returns the dialect of the SQL the connector sends: the one of its
     * {@linkplain #dialect() database}, or for the generic dialect one with
     * the settings of the connector.
     * <p>
     * Internal; not covered by the compatibility promise.

     */
    public Dialect sql() {
        return sql;
    }

    /**
     * Returns the catalog: the default schema of the database as
     * {@code main}, the other schemas by their names, without the schemas of
     * the database itself; in each the tables and views.
     */
    @Override
    public Catalog catalog() {
        return catalog;
    }

    /** Forgets the schemas, tables and columns read so far, so that changes of the database become visible. */
    public void refresh() {
        catalog.refresh();
    }

    /**
     * Returns the writer, which creates tables in the types the dialect
     * writes, see {@link Dialect#typeName}, appends rows to tables and
     * drops them; the catalog reads the database again after each write.
     * A write is one transaction over a connection of its own; over a
     * shared connection its commit or rollback ends a transaction the
     * application began on it as well. Its statements are recorded as those
     * of the scans are.
     */
    @Override
    public Optional<Writer> writer() {
        return Optional.of(writer);
    }

    /**
     * Returns the statements sent to the database so far, in order, if
     * the connector records them; otherwise an empty list.
     */
    public List<String> statements() {
        if (statements == null) {
            return List.of();
        }
        synchronized (statements) {
            return List.copyOf(statements);
        }
    }

    /** Forgets the recorded statements. */
    public void clearStatements() {
        if (statements != null) {
            synchronized (statements) {
                statements.clear();
            }
        }
    }

    /**
     * Takes over the filters the dialect evaluates as FyrQL does, except
     * those on a column of instants that the database would compare in
     * the time zone of its session, see {@link JdbcTypes#comparesInstants};
     * the order if the dialect orders every
     * key so, and a limit; or the aggregation if the dialect groups and
     * aggregates as FyrQL does, none of its columns holds instants, and
     * every filter is taken over. Of a filter not taken over, the statement
     * carries the {@linkplain Dialect#hint hint} of the dialect, unless it
     * reads a column of instants. The state of the plan holds the
     * statement.
     */
    @Override
    public ScanPlan plan(TableRef table, ScanRequest asked) {
        // The database reads whole columns.
        ScanRequest request = asked.readsFields() ? asked.withWholeColumns() : asked;
        Schema tableSchema = catalog.schema(table);
        Schema output = request.output(tableSchema);
        List<Integer> columns = request.wholeColumns();
        BitSet zoned = zoned(table, columns);
        Predicate<Expression> filter = e -> sql.supports(e) && !Expressions.columns(e).intersects(zoned);
        Capabilities capabilities = new Capabilities(filter, true, keys -> sql.supportsOrder(keys, output),
                aggregation -> sql.supportsAggregation(aggregation, output)
                        && !reads(aggregation, zoned));
        ScanPlan plan = capabilities.plan(table, tableSchema, request, null);
        List<Expression> hints = new ArrayList<>();
        for (Expression f : request.filters()) {
            if (!filter.test(f) && !Expressions.columns(f).intersects(zoned)) {
                sql.hint(f).ifPresent(hints::add);
            }
        }
        // With every filter and the order taken over, the statement selects only the columns the engine needs.
        boolean leaveOut = request.readsFilterColumns() && !request.filters().isEmpty()
                && plan.acceptedFilters().size() == request.filters().size()
                && (request.order().isEmpty() || plan.orderAccepted()) && !plan.aggregationAccepted();
        PlanNode.Pushed pushed = new PlanNode.Pushed(plan.acceptedFilters(),
                plan.limitAccepted() ? request.limit() : OptionalLong.empty(),
                plan.orderAccepted() ? request.order() : List.of(),
                plan.aggregationAccepted() ? request.aggregation() : Optional.empty(), hints,
                leaveOut ? OptionalInt.of(request.needed()) : OptionalInt.empty());
        PlanNode.Scan scan = new PlanNode.Scan(table, tableSchema, columns, pushed);
        BoundSql statement = renderer().renderScanBound(scan, null);
        return new ScanPlan(plan.table(), leaveOut ? scan.schema() : plan.output(), plan.columns(),
                plan.acceptedFilters(), plan.limitAccepted(), plan.orderAccepted(), plan.aggregationAccepted(),
                new ScanState(statement, scan));
    }

    private SqlRenderer renderer() {
        return new SqlRenderer(sql, catalog::qualifiedName);
    }

    /**
     * Which of the columns of a table, in the order given, hold instants that
     * the database would compare in the time zone of its session.
     */
    private BitSet zoned(TableRef table, List<Integer> columns) {
        BitSet all = catalog.withTimeZone(table);
        if (JdbcTypes.comparesInstants(dialect)) {
            all.clear();
        }
        BitSet zoned = new BitSet();
        for (int i = 0; i < columns.size(); i++) {
            zoned.set(i, all.get(columns.get(i)));
        }
        return zoned;
    }

    /** Tells whether an aggregation groups by or aggregates one of the columns. */
    private static boolean reads(PlanNode.Aggregation aggregation, BitSet columns) {
        for (int g : aggregation.groupColumns()) {
            if (columns.get(g)) {
                return true;
            }
        }
        for (PlanNode.AggregateCall call : aggregation.calls()) {
            if (call.column() >= 0 && columns.get(call.column()) || call.filter() >= 0 && columns.get(call.filter())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns one split; or with {@link Builder#splitRows}, for a table of
     * more rows, one split per range of its key column, see
     * {@link Builder#splitColumn}, each its own statement. The ranges are of
     * equal width between the least and the greatest key, which a
     * statement asks for each time; their number is the rows of the table,
     * counted once, divided by {@code splitRows} and rounded up, at most
     * {@value #MAX_SPLITS}. The first
     * range is open towards smaller keys and the last towards greater
     * ones, so that a row added since is read all the same; if the column
     * may hold {@code NULL}, its rows are one more split. A scan the
     * database aggregates, orders or limits stays one split, as does a
     * scan over a shared connection, of a table without a key column, of
     * an empty table and of a table of one key.
     */
    @Override
    public List<Split> splits(ScanPlan plan) {
        return splits(plan, MAX_SPLITS);
    }

    /**
     * Returns the parts of a scan as {@link #splits(ScanPlan)} does, but at
     * most as many ranges as there are threads. A scan for one thread stays
     * one split, to which a statement and a connection per part would only
     * add their cost.
     */
    @Override
    public List<Split> splits(ScanPlan plan, int threads) {
        ScanState state = (ScanState) plan.state();
        List<Expression> ranges = ranges(plan, state.scan(), threads);
        if (ranges.isEmpty()) {
            return List.of(new Split(plan, 0, state.sql()));
        }
        SqlRenderer renderer = renderer();
        List<Split> splits = new ArrayList<>(ranges.size());
        for (Expression range : ranges) {
            splits.add(new Split(plan, splits.size(), renderer.renderScanBound(state.scan(), range)));
        }
        return splits;
    }

    /** The conditions of the parts a scan for some threads is read in, or none if it is read in one. */
    private List<Expression> ranges(ScanPlan plan, PlanNode.Scan scan, int threads) {
        if (splitRows == 0 || threads < 2 || !connections.owned() || plan.aggregationAccepted()
                || plan.orderAccepted() || plan.limitAccepted()) {
            return List.of();
        }
        TableRef table = scan.table();
        int column = splitColumn(table, scan.tableSchema());
        if (column < 0) {
            return List.of();
        }
        long rows = catalog.rowCount(table).orElse(0);
        long parts = Math.min(Math.min(MAX_SPLITS, threads), rows / splitRows + (rows % splitRows == 0 ? 0 : 1));
        if (parts < 2) {
            return List.of();
        }
        DataType type = scan.tableSchema().type(column);
        long[] bounds = bounds(table, scan.tableSchema().name(column), type);
        if (bounds == null || bounds[0] == bounds[1]) {
            return List.of();
        }
        BigInteger low = BigInteger.valueOf(bounds[0]);
        BigInteger width = BigInteger.valueOf(bounds[1]).subtract(low);
        List<Long> cuts = new ArrayList<>();
        for (long i = 1; i < parts; i++) {
            long cut = low.add(width.multiply(BigInteger.valueOf(i)).divide(BigInteger.valueOf(parts))).longValue();
            if (cut > (cuts.isEmpty() ? bounds[0] : cuts.get(cuts.size() - 1))) {
                cuts.add(cut);
            }
        }
        Expression.Column key = new Expression.Column(column, type);
        List<Expression> ranges = new ArrayList<>();
        for (int i = 0; i <= cuts.size(); i++) {
            Expression from = i == 0 ? null : compare(Expression.ComparisonOp.GE, key, cuts.get(i - 1));
            Expression to = i == cuts.size() ? null : compare(Expression.ComparisonOp.LT, key, cuts.get(i));
            ranges.add(from == null ? to : to == null ? from : new Expression.And(from, to));
        }
        if (scan.tableSchema().nullable(column)) {
            ranges.add(new Expression.IsNull(key, false));
        }
        for (Expression range : ranges) {
            if (!sql.supports(range)) {
                return List.of();
            }
        }
        return ranges;
    }

    private static Expression compare(Expression.ComparisonOp op, Expression.Column key, long value) {
        Object literal = switch ((DataType.Scalar) key.type()) {
            case TINYINT -> (byte) value;
            case SMALLINT -> (short) value;
            case INTEGER, DATE -> (int) value;
            default -> value;
        };
        return new Expression.Comparison(op, key, new Expression.Literal(key.type(), literal));
    }

    /**
     * The index of the column a table is split by: the one set for it with
     * {@link Builder#splitColumn}, or else the column of its primary key if
     * that is of one column and of a type ranges are taken of; or -1.
     *
     * @throws IllegalArgumentException if the column set for the table is
     *         not one of it or not of a type ranges are taken of
     */
    private int splitColumn(TableRef table, Schema schema) {
        String set = splitColumns.get(table.schema() + "." + table.table());
        if (set == null) {
            set = splitColumns.get(table.table());
        }
        if (set != null) {
            int column = schema.indexOf(set);
            if (column < 0) {
                throw new IllegalArgumentException("The JDBC source " + name + " splits " + table + " by " + set
                        + ", which is no column of it");
            }
            if (!rangesOf(table, column, schema.type(column))) {
                throw new IllegalArgumentException("The JDBC source " + name + " splits " + table + " by " + set
                        + " of type " + schema.type(column).sqlName() + "; it takes an integral type up to BIGINT, "
                        + "DATE or TIMESTAMP");
            }
            return column;
        }
        int column = catalog.singleKey(table).map(schema::indexOf).orElse(-1);
        return column >= 0 && rangesOf(table, column, schema.type(column)) ? column : -1;
    }

    /**
     * Tells whether ranges are taken of a column: one of an integral type
     * up to {@code BIGINT}, a {@code DATE} or a {@code TIMESTAMP} that holds
     * no instants, which the database would compare in the time zone of its
     * session.
     */
    private boolean rangesOf(TableRef table, int column, DataType type) {
        return (type == DataType.TINYINT || type == DataType.SMALLINT || type == DataType.INTEGER
                || type == DataType.BIGINT || type == DataType.DATE || type == DataType.TIMESTAMP)
                && !catalog.withTimeZone(table).get(column);
    }

    /** The least and the greatest value of a column, as {@code long}, or {@code null} if it has none. */
    private long[] bounds(TableRef table, String column, DataType type) {
        String c = "t." + sql.quoteIdentifier(column);
        String statement = "SELECT min(" + c + "), max(" + c + ") FROM " + catalog.qualifiedName(table) + " t";
        record(statement);
        try {
            Connection connection = connections.open();
            try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery(statement)) {
                ResultSetMetaData md = rs.getMetaData();
                ColumnReaders.ColumnReader min = ColumnReaders.of(dialect, type, md.getColumnType(1),
                        md.getColumnTypeName(1));
                ColumnReaders.ColumnReader max = ColumnReaders.of(dialect, type, md.getColumnType(2),
                        md.getColumnTypeName(2));
                if (!rs.next()) {
                    return null;
                }
                Object low = min.read(rs, 1);
                Object high = max.read(rs, 2);
                return low == null || high == null ? null
                        : new long[] {((Number) low).longValue(), ((Number) high).longValue()};
            } finally {
                connections.release(connection);
            }
        } catch (SQLException e) {
            throw new JdbcSourceException("The JDBC source " + name + " cannot read the range of " + column + " in "
                    + table + " from " + connections.describe() + ": " + e.getMessage(), e);
        }
    }

    /** Reads a split: its own statement, or else the one of its plan. */
    @Override
    public BatchReader open(Split split) {
        BoundSql statement = split.state() instanceof BoundSql own ? own : ((ScanState) split.plan().state()).sql();
        return read(statement, split.plan().output());
    }

    /**
     * Runs a part of a plan as one statement when the database evaluates
     * all of it as FyrQL does: inner, left, right and full joins the dialect
     * writes, on keys it compares exactly; the filters it
     * {@linkplain net.fyrql.sql.render.Dialect#supports supports};
     * projections that pick columns; the aggregations it supports; and at
     * the top a limit and a sort the dialect orders exactly. Nothing in it
     * may compare, group, order or aggregate a column of instants. A cross
     * join stays with the engine, which would otherwise receive the whole
     * product. The statement nests a {@code SELECT} per node, see
     * {@link SqlRenderer#render}; it is the state and the description of
     * the plan. A dialect that binds its literals, such as the generic one,
     * runs no such part.
     */
    @Override
    public Optional<RemotePlan> planRemote(PlanNode plan) {
        if (sql.bindsLiterals() || zoned(plan, true, true) == null) {
            return Optional.empty();
        }
        String statement;
        try {
            statement = renderer().render(plan);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return Optional.of(new RemotePlan(plan.schema(), statement, statement));
    }

    @Override
    public BatchReader openRemote(RemotePlan plan) {
        return read(BoundSql.of((String) plan.state()), plan.output());
    }

    /**
     * The columns of instants in the output of a node that the database
     * evaluates as FyrQL does, or {@code null} if it does not.
     *
     * @param top whether the node is the top of the part, where a limit may be
     * @param ordered whether the order of the node is the order of the part, where a sort may be
     */
    private BitSet zoned(PlanNode node, boolean top, boolean ordered) {
        return switch (node) {
            case PlanNode.Scan s -> s.pushed().isEmpty() && !s.readsFields() && s.table().source().equals(name)
                    ? zoned(s.table(), s.columns()) : null;
            case PlanNode.Filter f -> {
                BitSet in = zoned(f.input(), false, false);
                yield in != null && sql.supports(f.condition()) && !Expressions.columns(f.condition()).intersects(in)
                        ? in : null;
            }
            case PlanNode.Project p -> {
                BitSet in = zoned(p.input(), false, false);
                BitSet out = new BitSet();
                for (int i = 0; in != null && i < p.columns().size(); i++) {
                    if (!(p.columns().get(i).expression() instanceof Expression.Column c)) {
                        yield null;
                    }
                    out.set(i, in.get(c.index()));
                }
                yield in == null ? null : out;
            }
            case PlanNode.Join j -> {
                BitSet left = j.type() == PlanNode.JoinType.CROSS || !sql.supportsJoin(j.type()) ? null
                        : zoned(j.left(), false, false);
                BitSet right = left == null ? null : zoned(j.right(), false, false);
                if (right == null) {
                    yield null;
                }
                for (int i = 0; i < j.leftKeys().size(); i++) {
                    int l = j.leftKeys().get(i);
                    Expression key = new Expression.Column(l, j.left().schema().type(l));
                    if (left.get(l) || right.get(j.rightKeys().get(i))
                            || !sql.supports(new Expression.Comparison(Expression.ComparisonOp.EQ, key, key))) {
                        yield null;
                    }
                }
                int width = j.left().schema().size();
                if (j.condition() != null) {
                    BitSet both = (BitSet) left.clone();
                    for (int i = right.nextSetBit(0); i >= 0; i = right.nextSetBit(i + 1)) {
                        both.set(width + i);
                    }
                    if (!sql.supports(j.condition()) || Expressions.columns(j.condition()).intersects(both)) {
                        yield null;
                    }
                }
                BitSet out = (BitSet) left.clone();
                for (int i = right.nextSetBit(0); j.type().keepsRight() && i >= 0; i = right.nextSetBit(i + 1)) {
                    out.set(width + i);
                }
                yield out;
            }
            case PlanNode.Aggregate a -> {
                BitSet in = zoned(a.input(), false, false);
                PlanNode.Aggregation aggregation = new PlanNode.Aggregation(a.groupColumns(), a.aggregates());
                yield in != null && sql.supportsAggregation(aggregation, a.input().schema()) && !reads(aggregation, in)
                        ? new BitSet() : null;
            }
            case PlanNode.Sort s -> {
                BitSet in = ordered ? zoned(s.input(), false, false) : null;
                boolean exact = in != null && sql.supportsOrder(s.keys(), s.input().schema());
                for (int i = 0; exact && i < s.keys().size(); i++) {
                    exact = !in.get(s.keys().get(i).column());
                }
                yield exact ? in : null;
            }
            case PlanNode.Limit l -> top ? zoned(l.input(), false, ordered) : null;
            default -> null;
        };
    }

    /** Sends a statement and reads its result. */
    private BatchReader read(BoundSql statement, Schema output) {
        record(statement.toString());
        return JdbcReader.open(name, statement, connections, dialect, output, fetchSize, batchSize);
    }

    /**
     * Tells the listener about a statement about to be sent, records it,
     * and notes it for the scan the thread works for, see {@link SourceCounters}.
     */
    private void record(String sql) {
        SourceCounters.current().statement(sql);
        if (listener != null) {
            listener.accept(sql);
        }
        if (statements != null) {
            synchronized (statements) {
                statements.add(sql);
            }
        }
    }

    /** Collects the settings of a connector. */
    public static final class Builder {

        /** The property of the MySQL driver that names the time zone of the session. */
        private static final String MYSQL_TIME_ZONE = "connectionTimeZone";
        /** The start of the URLs of the driver of FyrQL. */
        private static final String FYRQL_URL = "jdbc:fyrql:";

        private final String name;
        private String url;
        private final Properties properties = new Properties();
        private DataSource dataSource;
        private Connection connection;
        private JdbcDialect dialect;
        private List<String> schemas;
        private String defaultSchema;
        private int fetchSize = DEFAULT_FETCH_SIZE;
        private int batchSize = DEFAULT_BATCH_SIZE;
        private int batchRows = DEFAULT_BATCH_ROWS;
        private boolean recordStatements;
        private Consumer<String> listener;
        private long splitRows;
        private final Map<String, String> splitColumns = new HashMap<>();
        private Boolean pushdownFilters;
        private String quote;
        private LimitStyle limitStyle;
        private Set<Trust> trusted;

        private Builder(String name) {
            this.name = Objects.requireNonNull(name, "name");
        }

        /**
         * Connects through a JDBC URL, one connection per statement.
         *
         * @throws IllegalArgumentException if it is a URL of the driver of
         *         FyrQL, whose engine would run inside this one
         */
        public Builder url(String url) {
            Objects.requireNonNull(url, "url");
            if (url.strip().regionMatches(true, 0, FYRQL_URL, 0, FYRQL_URL.length())) {
                throw new IllegalArgumentException("The JDBC source " + name + " has the URL " + FYRQL_URL
                        + " of FyrQL itself; its engine cannot be a source of another one");
            }
            this.url = url;
            return this;
        }

        /** Sets the user of a URL. */
        public Builder user(String user) {
            return property("user", user);
        }

        /** Sets the password of a URL. */
        public Builder password(String password) {
            return property("password", password);
        }

        /** Sets a property of the driver for a URL. */
        public Builder property(String key, String value) {
            properties.setProperty(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
            return this;
        }

        /** Connects through a data source, one connection per statement, e.g. from a pool. */
        public Builder dataSource(DataSource dataSource) {
            this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
            return this;
        }

        /**
         * Uses one connection for every statement, which the application
         * shares and closes; the connector does not change its settings,
         * so a PostgreSQL result is read at once unless the connection is
         * in a transaction.
         */
        public Builder connection(Connection connection) {
            this.connection = Objects.requireNonNull(connection, "connection");
            return this;
        }

        /**
         * Sets the dialect; by default it comes from the URL, or else from
         * the product name of the database, and is
         * {@link JdbcDialect#GENERIC} for a database without a dialect of
         * its own.
         */
        public Builder dialect(JdbcDialect dialect) {
            this.dialect = Objects.requireNonNull(dialect, "dialect");
            return this;
        }

        /** Lists only these schemas besides the default one; by default all but those of the database itself. */
        public Builder schemas(List<String> schemas) {
            this.schemas = List.copyOf(schemas);
            return this;
        }

        /** Sets the schema listed as {@code main}; by default the schema, or in MySQL the database, of a connection. */
        public Builder defaultSchema(String schema) {
            this.defaultSchema = Objects.requireNonNull(schema, "schema");
            return this;
        }

        /** Sets the rows a statement fetches at a time; {@value JdbcConnector#DEFAULT_FETCH_SIZE} by default. */
        public Builder fetchSize(int rows) {
            if (rows < 1) {
                throw new IllegalArgumentException("fetchSize: " + rows);
            }
            this.fetchSize = rows;
            return this;
        }

        /** Sets the rows of a batch; {@value JdbcConnector#DEFAULT_BATCH_SIZE} by default. */
        public Builder batchSize(int rows) {
            if (rows < 1) {
                throw new IllegalArgumentException("batchSize: " + rows);
            }
            this.batchSize = rows;
            return this;
        }

        /**
         * Sets the rows a write sends to the database at a time, with
         * {@code executeBatch}; {@value JdbcConnector#DEFAULT_BATCH_ROWS} by
         * default.
         */
        public Builder batchRows(int rows) {
            if (rows < 1) {
                throw new IllegalArgumentException("batchRows: " + rows);
            }
            this.batchRows = rows;
            return this;
        }

        /** Keeps every statement sent, see {@link JdbcConnector#statements()}; off by default. */
        public Builder recordStatements(boolean record) {
            this.recordStatements = record;
            return this;
        }

        /**
         * Reads a scan of a table of more rows than this in parts of about
         * as many rows, each one statement over a range of a key column, see
         * {@link #splitColumn}, which the engine reads on several threads at
         * once, each over a connection of its own; at most
         * {@value JdbcConnector#MAX_SPLITS} parts, and no more than threads
         * read them, so that on one thread a scan stays one statement. Each part is a transaction
         * of its own, so the parts may see different states of a table that
         * changes while it is read: rows may be missing or come twice. 0,
         * the default, reads every scan in one statement, as a shared
         * connection always does.
         */
        public Builder splitRows(long rows) {
            if (rows < 0) {
                throw new IllegalArgumentException("splitRows: " + rows);
            }
            this.splitRows = rows;
            return this;
        }

        /**
         * Splits a table by a column, see {@link #splitRows}, instead of the
         * column of its primary key, which is the default if the key is of
         * one column. The table is named as in the catalog, {@code orders}
         * or {@code main.orders} in the default schema, {@code sales.orders}
         * in another. The column must be of an integral type up to
         * {@code BIGINT}, a {@code DATE} or a {@code TIMESTAMP} without a
         * time zone. Ranges of equal width make parts of equal size only if
         * the values are spread evenly, and without an index on the column
         * the database reads the whole table for every part.
         */
        public Builder splitColumn(String table, String column) {
            Objects.requireNonNull(table, "table");
            Objects.requireNonNull(column, "column");
            if (table.isBlank() || column.isBlank()) {
                throw new IllegalArgumentException("splitColumn: a table and a column, not '" + table + "' and '"
                        + column + "'");
            }
            splitColumns.put(table.strip(), column.strip());
            return this;
        }

        /** Calls a listener with every statement before it is sent. */
        public Builder statementListener(Consumer<String> listener) {
            this.listener = Objects.requireNonNull(listener, "listener");
            return this;
        }

        /**
         * Lets the database of the generic dialect evaluate filters, the
         * default, or none at all; FyrQL then filters every row itself.
         */
        public Builder pushdownFilters(boolean filters) {
            this.pushdownFilters = filters;
            return this;
        }

        /**
         * Sets the quote of identifiers for the generic dialect: {@code "},
         * {@code `}, {@code [} for {@code [name]}, or empty for none; by
         * default the one the driver tells.
         */
        public Builder quote(String quote) {
            this.quote = Objects.requireNonNull(quote, "quote");
            return this;
        }

        /**
         * Sets how the generic dialect keeps the first rows; by default
         * with {@code Statement.setMaxRows}.
         */
        public Builder limitStyle(LimitStyle style) {
            this.limitStyle = Objects.requireNonNull(style, "style");
            return this;
        }

        /**
         * Sets the types the database of the generic dialect is trusted to
         * compare as FyrQL does, besides integers and dates; none by
         * default.
         */
        public Builder trust(Set<Trust> types) {
            this.trusted = types.isEmpty() ? Set.of() : EnumSet.copyOf(types);
            return this;
        }

        /**
         * Builds the connector.
         *
         * @throws IllegalArgumentException if not exactly one of URL, data
         *         source and connection is set, or an option of the generic
         *         dialect is set for another one
         */
        public JdbcConnector build() {
            int ways = (url != null ? 1 : 0) + (dataSource != null ? 1 : 0) + (connection != null ? 1 : 0);
            if (ways != 1) {
                throw new IllegalArgumentException("The JDBC source " + name
                        + " needs exactly one of a URL, a data source and a connection");
            }
            JdbcDialect d = dialect;
            if (d == null && url != null) {
                d = JdbcDialect.ofUrl(url).orElse(null);
            }
            Properties props = (Properties) properties.clone();
            if (url != null && url.regionMatches(true, 0, "jdbc:mysql:", 0, 11) && !props.containsKey(MYSQL_TIME_ZONE)
                    && !url.contains(MYSQL_TIME_ZONE + "=")) {
                // The driver reads an instant in the time zone of the session, not in the one of the JVM.
                props.setProperty(MYSQL_TIME_ZONE, "SERVER");
            }
            Connections connections = url != null ? Connections.url(url, props)
                    : dataSource != null ? Connections.dataSource(dataSource) : Connections.shared(connection);
            String told = null;
            if (d == null || d == JdbcDialect.GENERIC && quote == null) {
                String[] product = product(connections);
                if (d == null) {
                    d = JdbcDialect.ofProductName(product[0]).orElse(JdbcDialect.GENERIC);
                }
                told = product[1];
            }
            boolean generic = pushdownFilters != null || quote != null || limitStyle != null || trusted != null;
            if (d != JdbcDialect.GENERIC) {
                if (generic) {
                    throw new IllegalArgumentException("The JDBC source " + name + " has the dialect "
                            + d.sql().name() + "; pushdownFilters, quote, limitStyle and trust are options of the "
                            + "generic dialect only");
                }
                return new JdbcConnector(this, connections, d, d.sql());
            }
            Set<GenericDialect.Trust> types = EnumSet.noneOf(GenericDialect.Trust.class);
            for (Trust t : trusted != null ? trusted : Set.<Trust>of()) {
                types.add(GenericDialect.Trust.valueOf(t.name()));
            }
            GenericDialect.Settings settings = new GenericDialect.Settings(quote != null ? quote : quoteOf(told),
                    GenericDialect.LimitStyle.valueOf((limitStyle != null ? limitStyle : LimitStyle.MAX_ROWS).name()),
                    types, pushdownFilters == null || pushdownFilters);
            return new JdbcConnector(this, connections, d, GenericDialect.of(settings));
        }

        /** The quote of identifiers as the driver tells it, a blank for none. */
        private String quoteOf(String told) {
            String q = told == null ? "\"" : told.strip();
            if (!q.isEmpty() && !q.equals("\"") && !q.equals("`") && !q.equals("[")) {
                throw new IllegalArgumentException("The JDBC source " + name + " quotes identifiers with " + q
                        + ", which the generic dialect does not write; set the quote");
            }
            return q;
        }

        /** The product name of the database and its quote of identifiers, as the metadata tells them. */
        private String[] product(Connections connections) {
            try {
                Connection c = connections.open();
                try {
                    DatabaseMetaData md = c.getMetaData();
                    return new String[] {md.getDatabaseProductName(), md.getIdentifierQuoteString()};
                } finally {
                    connections.release(c);
                }
            } catch (SQLException e) {
                throw new JdbcSourceException("The JDBC source " + name + " cannot connect to "
                        + connections.describe() + " to tell its dialect: " + e.getMessage(), e);
            }
        }
    }
}
