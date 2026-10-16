/*
 * Copyright 2026 the FyrQL authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fyrql.connector.jdbc;

import net.fyrql.connector.BatchReader;
import net.fyrql.connector.Capabilities;
import net.fyrql.connector.Catalog;
import net.fyrql.connector.Connector;
import net.fyrql.connector.RemotePlan;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.connector.Split;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.expr.Expressions;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.Schema;
import net.fyrql.sql.render.Dialect;
import net.fyrql.sql.render.SqlRenderer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The tables of a database over JDBC: H2, SQLite, PostgreSQL or MySQL.
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

    private final String name;
    private final Connections connections;
    private final JdbcDialect dialect;
    private final JdbcCatalog catalog;
    private final int fetchSize;
    private final int batchSize;
    private final Consumer<String> listener;
    private final List<String> statements;

    private JdbcConnector(Builder b, Connections connections, JdbcDialect dialect) {
        this.name = b.name;
        this.connections = connections;
        this.dialect = dialect;
        this.catalog = new JdbcCatalog(b.name, connections, dialect, b.schemas, b.defaultSchema);
        this.fetchSize = b.fetchSize;
        this.batchSize = b.batchSize;
        this.listener = b.listener;
        this.statements = b.recordStatements ? new ArrayList<>() : null;
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
     * those on a column of instants, which the database would compare in
     * the time zone of its session; the order if the dialect orders every
     * key so, and a limit; or the aggregation if the dialect groups and
     * aggregates as FyrQL does, none of its columns holds instants, and
     * every filter is taken over. The state of the plan is the statement.
     */
    @Override
    public ScanPlan plan(TableRef table, ScanRequest asked) {
        // The database reads whole columns.
        ScanRequest request = asked.readsFields() ? asked.withWholeColumns() : asked;
        Schema tableSchema = catalog.schema(table);
        Schema output = request.output(tableSchema);
        List<Integer> columns = request.wholeColumns();
        BitSet zoned = zoned(table, columns);
        Predicate<Expression> filter = e -> dialect.sql().supports(e) && !Expressions.columns(e).intersects(zoned);
        Capabilities capabilities = new Capabilities(filter, true, keys -> dialect.sql().supportsOrder(keys, output),
                aggregation -> dialect.sql().supportsAggregation(aggregation, output)
                        && !reads(aggregation, zoned));
        ScanPlan plan = capabilities.plan(table, tableSchema, request, null);
        PlanNode.Pushed pushed = new PlanNode.Pushed(plan.acceptedFilters(),
                plan.limitAccepted() ? request.limit() : OptionalLong.empty(),
                plan.orderAccepted() ? request.order() : List.of(),
                plan.aggregationAccepted() ? request.aggregation() : Optional.empty());
        PlanNode.Scan scan = new PlanNode.Scan(table, tableSchema, columns, pushed);
        String sql = new SqlRenderer(dialect.sql(), catalog::qualifiedName).renderScan(scan);
        return new ScanPlan(plan.table(), plan.output(), plan.columns(), plan.acceptedFilters(), plan.limitAccepted(),
                plan.orderAccepted(), plan.aggregationAccepted(), sql);
    }

    /** Which of the columns of a table, in the order given, hold instants. */
    private BitSet zoned(TableRef table, List<Integer> columns) {
        BitSet all = catalog.withTimeZone(table);
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
            if (call.column() >= 0 && columns.get(call.column())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<Split> splits(ScanPlan plan) {
        return List.of(new Split(plan, 0, null));
    }

    @Override
    public BatchReader open(Split split) {
        return read((String) split.plan().state(), split.plan().output());
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
     * the plan.
     */
    @Override
    public Optional<RemotePlan> planRemote(PlanNode plan) {
        if (zoned(plan, true, true) == null) {
            return Optional.empty();
        }
        String sql;
        try {
            sql = new SqlRenderer(dialect.sql(), catalog::qualifiedName).render(plan);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return Optional.of(new RemotePlan(plan.schema(), sql, sql));
    }

    @Override
    public BatchReader openRemote(RemotePlan plan) {
        return read((String) plan.state(), plan.output());
    }

    /**
     * The columns of instants in the output of a node that the database
     * evaluates as FyrQL does, or {@code null} if it does not.
     *
     * @param top whether the node is the top of the part, where a limit may be
     * @param ordered whether the order of the node is the order of the part, where a sort may be
     */
    private BitSet zoned(PlanNode node, boolean top, boolean ordered) {
        Dialect sql = dialect.sql();
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
    private BatchReader read(String sql, Schema output) {
        if (listener != null) {
            listener.accept(sql);
        }
        if (statements != null) {
            synchronized (statements) {
                statements.add(sql);
            }
        }
        return JdbcReader.open(name, sql, connections, dialect, output, fetchSize, batchSize);
    }

    /** Collects the settings of a connector. */
    public static final class Builder {

        /** The property of the MySQL driver that names the time zone of the session. */
        private static final String MYSQL_TIME_ZONE = "connectionTimeZone";

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
        private boolean recordStatements;
        private Consumer<String> listener;

        private Builder(String name) {
            this.name = Objects.requireNonNull(name, "name");
        }

        /** Connects through a JDBC URL, one connection per statement. */
        public Builder url(String url) {
            this.url = Objects.requireNonNull(url, "url");
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

        /** Sets the dialect; by default it comes from the URL, or else from the product name of the database. */
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

        /** Keeps every statement sent, see {@link JdbcConnector#statements()}; off by default. */
        public Builder recordStatements(boolean record) {
            this.recordStatements = record;
            return this;
        }

        /** Calls a listener with every statement before it is sent. */
        public Builder statementListener(Consumer<String> listener) {
            this.listener = Objects.requireNonNull(listener, "listener");
            return this;
        }

        /**
         * Builds the connector.
         *
         * @throws IllegalArgumentException if not exactly one of URL, data
         *         source and connection is set, or the dialect is not set
         *         and cannot be told
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
            if (d == null) {
                d = productDialect(connections);
            }
            return new JdbcConnector(this, connections, d);
        }

        private JdbcDialect productDialect(Connections connections) {
            String product;
            try {
                Connection c = connections.open();
                try {
                    product = c.getMetaData().getDatabaseProductName();
                } finally {
                    connections.release(c);
                }
            } catch (SQLException e) {
                throw new JdbcSourceException("The JDBC source " + name + " cannot connect to "
                        + connections.describe() + " to tell its dialect: " + e.getMessage(), e);
            }
            return JdbcDialect.ofProductName(product).orElseThrow(() -> new IllegalArgumentException(
                    "The JDBC source " + name + " connects to " + product + ", which has no dialect; known are "
                            + "H2, SQLite, PostgreSQL and MySQL"));
        }
    }
}
