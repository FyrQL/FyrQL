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

package net.fyrql;

import net.fyrql.catalog.CatalogTables;
import net.fyrql.catalog.EngineCatalog;
import net.fyrql.catalog.Views;
import net.fyrql.catalog.SearchPath;
import net.fyrql.connector.BatchReader;
import net.fyrql.connector.Connector;
import net.fyrql.connector.ColumnPath;
import net.fyrql.connector.ConnectorFactory;
import net.fyrql.connector.FileAccess;
import net.fyrql.connector.FileAccessPolicy;
import net.fyrql.connector.FileReaderProvider;
import net.fyrql.connector.FileStore;
import net.fyrql.connector.FileStores;
import net.fyrql.connector.FileWriterProvider;
import net.fyrql.connector.RemotePlan;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.connector.SourceCounters;
import net.fyrql.connector.Split;
import net.fyrql.connector.TableFunctionProvider;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.function.FunctionRegistry;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.exec.Codegen;
import net.fyrql.exec.CodegenMode;
import net.fyrql.exec.Executor;
import net.fyrql.exec.MemoryBudget;
import net.fyrql.exec.Operator;
import net.fyrql.exec.QueryContext;
import net.fyrql.exec.QueryStatistics;
import net.fyrql.exec.SpillDirectory;
import net.fyrql.exec.SpillStatistics;
import net.fyrql.exec.Splits;
import net.fyrql.exec.TableSource;
import net.fyrql.optimizer.Optimizer;
import net.fyrql.exec.CodegenStatistics;
import net.fyrql.sql.SqlException;
import net.fyrql.sql.Position;
import net.fyrql.sql.ast.CreateView;
import net.fyrql.sql.ast.SqlExpr;
import net.fyrql.sql.bind.Binder;
import net.fyrql.sql.bind.BoundStatement;
import net.fyrql.sql.bind.ViewDefinition;
import net.fyrql.sql.parse.Parameters;
import net.fyrql.sql.parse.SqlParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Function;

/**
 * The engine: registered connectors, a catalog over them, and the
 * execution of queries. A query is SQL text, which {@link #plan(String)}
 * parses and binds into a {@link PlanNode}, or a plan built
 * programmatically, starting with {@link #scan}. Names resolve along the
 * search path of the builder, or along that of a session, see
 * {@link Session#use(SearchPath)}, and so do table functions such as
 * {@code read_csv}; the scalar functions come from the
 * {@link FunctionRegistry} of the builder. A query with parameters
 * {@code ?} is prepared once with {@link #prepare(String)} and run with
 * values for them with {@link #execute(PreparedQuery, List)}. With the module
 * {@code fyrql-codegen} on the class path, the engine compiles the
 * expressions, filters and keys of operators that process many rows; see
 * {@link Builder#codegen}.
 * <p>
 * The settings that {@code SET} changes, the time zone, the search path
 * and the timeout of a statement, belong to a {@link Session}: the methods
 * of the engine run in its own session, see {@link #session()}, and
 * {@link #newSession()} starts another one, e.g. one per client.
 * <p>
 * Besides queries, {@link #sql(String)} runs the statements that write:
 * {@code COPY ... TO} a file, and {@code CREATE TABLE [AS]},
 * {@code INSERT INTO}, {@code COPY ... FROM} a file and {@code DROP TABLE}
 * in a source that writes tables. Such a statement runs to its end before {@code sql} returns, and
 * its result is one row with the column {@code Count}, the number of rows
 * written, as in DuckDB; {@link #update(String)} returns the number
 * alone. Each statement is a transaction of its own in the source it
 * writes, as far as the source allows.
 * <p>
 * {@code CREATE VIEW} and {@code DROP VIEW} define views in a schema of any
 * source, which every session and client of the engine sees as a table of
 * that schema, until the engine closes; {@link Builder#view(String, List,
 * String)} defines them as the engine is built.
 * <p>
 * The formats of files come with their modules: with
 * {@code fyrql-connector-csv}, {@code fyrql-connector-json} or
 * {@code fyrql-connector-parquet} on the class path, the engine reads and
 * writes the format without a registered source of it, see
 * {@link Builder#implicitSources(boolean)}. Which files statements in SQL
 * may read and write, see {@link Builder#fileAccess(FileAccess)}; how long
 * a query may run, see {@link Builder#queryTimeout(Duration)}.
 *
 * <pre>{@code
 * try (FyrQL engine = FyrQL.builder().register(memory).register(csv).build()) {
 *     try (QueryResult result = engine.sql("SELECT region, sum(amount) FROM orders GROUP BY ALL")) {
 *         for (Object[] row : result.rows()) { ... }
 *     }
 * }
 * }</pre>
 */
public final class FyrQL implements AutoCloseable {

    /** Whether the module {@code jdk.jfr} is there, as it may not be in a runtime made with jlink. */
    private static final boolean RECORDABLE = ModuleLayer.boot().findModule("jdk.jfr").isPresent();

    private final Map<String, Connector> connectors;
    private final EngineCatalog catalog;
    private final CatalogTables catalogTables;
    private final FunctionRegistry functions;
    private final Optimizer optimizer;
    private final Executor executor;
    private final Execution execution;
    private final Codegen codegen;
    private final Writes writes;
    /** The directory to which operators spill, {@code null} if they do not. */
    private final SpillDirectory spill;
    /** The timeouts of the queries. */
    private final Timeouts timeouts;
    /** The time zone every session starts with, to which {@code RESET TimeZone} returns. */
    private final ZoneId initialZone;
    /** The session of the engine, in which its methods without a session run. */
    private final Session session;

    private FyrQL(Map<String, Connector> connectors, SearchPath searchPath, FunctionRegistry functions,
            MemoryBudget budget, int batchSize, Codegen codegen, Optimizer.Options options, int threads,
            boolean preserveOrder, SpillDirectory spill, ZoneId zone, FileAccessPolicy files, Timeouts timeouts) {
        this.connectors = Collections.unmodifiableMap(new LinkedHashMap<>(connectors));
        this.spill = spill;
        this.timeouts = timeouts;
        Views views = new Views();
        this.catalog = new EngineCatalog(this.connectors, searchPath, this::timeZone, files, views);
        this.catalogTables = catalog.catalogTables();
        this.functions = functions;
        this.initialZone = zone;
        this.optimizer = new Optimizer(this::connector, options);
        this.codegen = codegen;
        this.writes = new Writes(this);
        this.executor = new Executor(tables(this::connector), budget, batchSize, codegen, threads, preserveOrder,
                spill);
        this.execution = new Execution(optimizer, executor);
        this.session = new Session(this, zone, timeouts.queryTimeout(), catalog);
    }

    /**
     * The optimizer and the executor over the sources of the engine, or over
     * those of a session that has more, see {@link Overlay}.
     */
    record Execution(Optimizer optimizer, Executor executor) {
    }

    /** Returns the optimizer and the executor over the sources of the engine. */
    Execution execution() {
        return execution;
    }

    /**
     * Returns an optimizer and an executor over other connectors, with the
     * options, the budget and the threads of the engine.
     */
    Execution execution(Function<String, Connector> connectors) {
        return new Execution(optimizer.withConnectors(connectors), executor.withSource(tables(connectors)));
    }

    /** Returns the tables of the executor, read through the connectors of their sources. */
    private TableSource tables(Function<String, Connector> connectors) {
        return new TableSource() {
            @Override
            public BatchReader open(PlanNode.Scan scan) {
                return FyrQL.this.open(scan, QueryContext.NONE, connectors);
            }

            @Override
            public BatchReader open(PlanNode.Scan scan, QueryContext context) {
                return FyrQL.this.open(scan, context, connectors);
            }

            @Override
            public Splits splits(PlanNode.Scan scan, QueryContext context) {
                return FyrQL.this.splits(scan, 1, connectors);
            }

            @Override
            public Splits splits(PlanNode.Scan scan, int threads, QueryContext context) {
                return FyrQL.this.splits(scan, threads, connectors);
            }

            @Override
            public BatchReader open(PlanNode.Remote remote) {
                return FyrQL.this.open(remote, connectors);
            }
        };
    }

    /**
     * Returns the catalog: names along the search path of the builder,
     * columns, and table functions, in the time zone of the session of the
     * engine.
     * <p>
     * Internal; not covered by the compatibility promise. Applications list
     * the tables with {@link #catalogTables()}.
     */
    public EngineCatalog catalog() {
        return catalog;
    }

    /** Returns the sources, schemas, tables and columns as lists, for the metadata of tools. */
    public CatalogTables catalogTables() {
        return catalogTables;
    }

    /** Returns the scalar functions that queries in SQL can call. */
    public FunctionRegistry functions() {
        return functions;
    }

    /** Returns the mode of code generation, {@link CodegenMode#OFF} if there is no compiler. */
    public CodegenMode codegen() {
        return codegen.compiler() == null ? CodegenMode.OFF : codegen.mode();
    }

    /** Returns what the compiler has done so far; the compiler and its statistics are shared by all engines. */
    public CodegenStatistics codegenStatistics() {
        return codegen.statistics();
    }

    /**
     * Returns what the queries of the engine have spilled to disk since it
     * was built, and read back; per query, see {@link QueryResult#spill()}.
     */
    public SpillStatistics spillStatistics() {
        return spill != null ? spill.statistics() : SpillStatistics.NONE;
    }

    /**
     * Returns the directory under which operators spill to disk when the
     * memory budget is exhausted, or nothing if they fail instead.
     */
    public Optional<Path> spillDirectory() {
        return spill != null ? Optional.of(spill.parent()) : Optional.empty();
    }

    /** Returns what statements in SQL may do with files, see {@link Builder#fileAccess(FileAccess)}. */
    public FileAccess fileAccess() {
        return catalog.fileAccessPolicy().access();
    }

    /**
     * Returns the locations under which statements in SQL may read and
     * write files, resolved, or none for anywhere; see
     * {@link Builder#allowedLocations(List)}.
     */
    public List<String> allowedLocations() {
        return catalog.fileAccessPolicy().locations();
    }

    /** Returns the timeout of a query that asks for none, see {@link Builder#queryTimeout(Duration)}. */
    public Optional<Duration> queryTimeout() {
        return Optional.ofNullable(timeouts.queryTimeout());
    }

    /** Returns the upper bound of the timeouts of queries, see {@link Builder#maxQueryTimeout(Duration)}. */
    public Optional<Duration> maxQueryTimeout() {
        return Optional.ofNullable(timeouts.maxQueryTimeout());
    }

    /** Returns the timeouts of the queries, for the tests. */
    Timeouts timeouts() {
        return timeouts;
    }

    /** Tells whether scans are negotiated with their connectors, so that a source takes over what it can. */
    public boolean pushdown() {
        return optimizer.options().pushdown();
    }

    /** Returns the most threads on which one query runs at a time. */
    public int threads() {
        return executor.threads();
    }

    /** Tells whether rows that no sort orders come in the order in which their sources deliver them. */
    public boolean preserveOrder() {
        return executor.preserveOrder();
    }

    /**
     * Rewrites a plan into the plan the engine runs: scans read only the
     * columns in use and, with {@link #pushdown()}, carry what their
     * connectors take over of the filters, aggregation, sort and limit
     * above them. {@link #execute} and {@link #explain} do this themselves.
     */
    public PlanNode optimize(PlanNode plan) {
        return optimizer.optimize(plan);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the time zone of the session of the engine, in which values of
     * {@code TIMESTAMP WITH TIME ZONE} are shown and their calendar computes:
     * that of the builder, until {@code SET TimeZone} through a method of the
     * engine changes it; see {@link Session#timeZone()}.
     */
    public ZoneId timeZone() {
        return session.timeZone();
    }

    /** Returns the time zone of the builder, with which every session starts. */
    ZoneId initialZone() {
        return initialZone;
    }

    /**
     * Returns the session of the engine, in which its methods without a
     * session run, such as {@link #sql(String)} and {@link #update(String)}:
     * {@code SET} through them changes this session.
     */
    public Session session() {
        return session;
    }

    /**
     * Starts a session with the settings of the builder, e.g. for a client;
     * {@code SET} in it changes only it. A session needs no closing.
     */
    public Session newSession() {
        return new Session(this, initialZone, timeouts.queryTimeout(), catalog.searchPath());
    }

    /** Starts a session with more sources and its own functions, see {@link Overlay#newSession}. */
    Session newSession(Map<String, Connector> overlay, FunctionRegistry functions) {
        return new Session(this, initialZone, timeouts.queryTimeout(), catalog.searchPath(), overlay, functions);
    }

    /** Returns the connector of a source. */
    public Connector connector(String source) {
        Connector c = connectors.get(source);
        if (c == null) {
            throw new IllegalArgumentException("No source " + source + "; registered: " + connectors.keySet());
        }
        return c;
    }

    /**
     * Returns the names of the sources: the registered ones in the order of
     * their registration, then the implicit ones, see
     * {@link Builder#implicitSources(boolean)}.
     */
    public List<String> sources() {
        return List.copyOf(connectors.keySet());
    }

    /** Returns the columns of a table, or of a view as they were when it was created. */
    public Schema schema(TableRef table) {
        Optional<ViewDefinition> view = catalog.view(table);
        return view.isPresent() ? view.get().schema() : connector(table.source()).catalog().schema(table);
    }

    /** Returns a scan of all columns of a table. */
    public PlanNode.Scan scan(TableRef table) {
        return PlanNode.Scan.all(table, schema(table));
    }

    /** Returns a scan of the named columns of a table, in that order. */
    public PlanNode.Scan scan(TableRef table, List<String> columns) {
        Schema schema = schema(table);
        List<Integer> indexes = new ArrayList<>();
        for (String name : columns) {
            int i = schema.indexOf(name);
            if (i < 0) {
                throw new IllegalArgumentException("No column " + name + " in " + table + " " + schema);
            }
            indexes.add(i);
        }
        return new PlanNode.Scan(table, schema, indexes);
    }

    /**
     * Parses and binds a query; {@code current_date} and {@code now()} stand
     * for the time of this call. A statement that writes has no plan of its
     * own, see {@link #statement(String)}.
     *
     * @throws SqlException if the query cannot be parsed or bound, or is no
     *         query; the message names the position in the text
     */
    public PlanNode plan(String sql) {
        return session.plan(sql);
    }

    /**
     * Parses and binds a statement: a query, or a statement that writes,
     * with the plan of the rows it writes. Whether a table to create
     * exists, or one to write or drop, is checked when the statement runs.
     *
     * @throws SqlException if the statement cannot be parsed or bound; the
     *         message names the position in the text
     */
    public SqlStatement statement(String sql) {
        return session.statement(sql);
    }

    /**
     * Parses and binds a query with parameters {@code ?}, or {@code $1},
     * {@code $2} and so on, each of which may stand more than once, without
     * running it: the types of the parameters and the columns of the result. A
     * parameter takes the type that its context gives a bare {@code NULL}:
     * the type of the other side of a comparison or of arithmetic, of the
     * other values of {@code IN}, {@code BETWEEN}, {@code CASE} and
     * {@code COALESCE}, {@code BOOLEAN} as a condition, the type of
     * {@code CAST(? AS type)}, and as an argument of a function the first
     * type it takes of {@code BIGINT}, {@code DOUBLE}, {@code INTEGER} and
     * so on, {@code VARCHAR} last; in {@code VALUES} of an {@code INSERT},
     * the type of the column its value goes to. A parameter without such a
     * context, as in {@code SELECT ?}, is a {@code VARCHAR}, as a parameter
     * without a type is a {@code text} in PostgreSQL; a parameter by itself
     * in {@code LIMIT} or {@code OFFSET} is a {@code BIGINT}. A parameter in
     * another place where a constant is needed, such as {@code ORDER BY ?},
     * is an error, as is one of a type with a {@code MAP}, which has no
     * literal. A statement that writes is prepared as well; its result has
     * the column {@code Count}.
     *
     * @throws SqlException if the query cannot be parsed or bound, or a
     *         parameter has two types or one with a {@code MAP}
     */
    public PreparedQuery prepare(String sql) {
        return session.prepare(sql);
    }

    /** Prepares a query with a binder of a session along a search path. */
    PreparedQuery prepare(String sql, SearchPath searchPath, Binder binder) {
        SqlParser.ParameterizedStatement parsed = SqlParser.parseStatementWithParameters(sql);
        Binder.PreparedStatement bound = binder.prepare(parsed.statement(), parsed.parameters());
        List<DataType> types = bound.parameterTypes();
        for (int i = 0; i < types.size(); i++) {
            if (Parameters.hasMap(types.get(i))) {
                SqlExpr.Parameter p = parsed.parameters().get(i);
                throw new SqlException("Not supported: parameter " + p.index() + " of the type "
                        + types.get(i).sqlName() + ", which has no literal; cast it from a type without a MAP",
                        p.position());
            }
        }
        BoundStatement statement = bound.statement();
        return new PreparedQuery(sql, searchPath, statement.schema(), types,
                !(statement instanceof BoundStatement.Query || statement instanceof BoundStatement.Explain));
    }

    /**
     * Binds a prepared query with values for its parameters: the query
     * text with a literal of each value in place of its parameter, typed
     * as the parameter, bound anew. The result has the columns of the
     * prepared query, also where a {@code NULL} would make a column a bare
     * {@code NULL} of another type.
     *
     * @param values one value per parameter, a Java value of its type as
     *        {@link QueryResult#rows()} gives them, {@code null} for {@code NULL};
     *        a {@code TIMESTAMP WITH TIME ZONE} may also be an
     *        {@link java.time.Instant} or {@link java.time.ZonedDateTime}, an
     *        {@code INTERVAL} a {@link Duration} or {@link java.time.Period},
     *        and every value may be in the form of a vector, see
     *        {@link net.fyrql.core.type.Values#fromJava}
     * @throws IllegalArgumentException if the number of values differs from
     *         that of the parameters, or a value is none of its type
     * @throws SqlException if the query does not bind any more, e.g. after
     *         a change of a source, or is no query
     */
    public PlanNode plan(PreparedQuery query, List<?> values) {
        return session.plan(query, values);
    }

    /**
     * Binds a prepared statement with values for its parameters, as
     * {@link #plan(PreparedQuery, List)} binds a query; a query comes with
     * the columns of the prepared query.
     *
     * @throws IllegalArgumentException if the values do not fit the parameters
     * @throws SqlException             if the statement does not bind any more
     */
    public SqlStatement statement(PreparedQuery query, List<?> values) {
        return session.statement(query, values);
    }

    /** Returns the text of a prepared statement with a literal of each value in place of its parameter. */
    static String render(PreparedQuery query, List<?> values) {
        List<DataType> types = query.parameterTypes();
        if (values.size() != types.size()) {
            throw new IllegalArgumentException("The query has " + types.size() + " parameters, not " + values.size()
                    + " values");
        }
        String[] literals = new String[types.size()];
        for (int i = 0; i < literals.length; i++) {
            try {
                literals[i] = Parameters.literal(values.get(i), types.get(i));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Parameter " + (i + 1) + ": " + e.getMessage(), e);
            }
        }
        return Parameters.parse(query.sql()).render(literals);
    }

    /**
     * Gives the plan of a prepared query with values the names and types of
     * the columns of the prepared query. The types differ only where a
     * {@code NULL} folds an expression into a bare {@code NULL}, typed
     * {@code INTEGER}.
     */
    static PlanNode conform(PlanNode plan, Schema schema) {
        Schema actual = plan.schema();
        if (actual.size() != schema.size()) {
            throw new IllegalStateException("The query has the columns " + actual + ", not " + schema);
        }
        boolean same = true;
        for (int i = 0; i < schema.size() && same; i++) {
            same = actual.name(i).equals(schema.name(i)) && actual.type(i).equals(schema.type(i));
        }
        if (same) {
            return plan;
        }
        List<PlanNode.NamedExpression> columns = new ArrayList<>();
        for (int i = 0; i < schema.size(); i++) {
            Expression column = new Expression.Column(i, actual.type(i));
            if (!actual.type(i).equals(schema.type(i))) {
                column = new Expression.Cast(column, schema.type(i));
            }
            columns.add(new PlanNode.NamedExpression(schema.name(i), column));
        }
        return new PlanNode.Project(plan, columns);
    }

    /**
     * Runs a prepared statement with values for its parameters, see
     * {@link #statement(PreparedQuery, List)}; the caller closes the result.
     *
     * @throws IllegalArgumentException if the values do not fit the parameters
     * @throws SqlException             if the statement does not bind any more, or a write fails
     */
    public QueryResult execute(PreparedQuery query, List<?> values) {
        return session.execute(query, values);
    }

    /**
     * Runs a prepared query with values for its parameters, for a query
     * that a context of the caller cancels, see
     * {@link #execute(PlanNode, QueryContext)}.
     *
     * @throws IllegalArgumentException if the values do not fit the parameters
     * @throws SqlException             if the statement does not bind any more, or a write fails
     */
    public QueryResult execute(PreparedQuery query, List<?> values, QueryContext context) {
        return session.execute(query, values, context);
    }

    /**
     * Runs a prepared query with values for its parameters and collects all
     * rows as Java values.
     *
     * @throws IllegalArgumentException if the values do not fit the parameters
     * @throws SqlException             if the query does not bind any more
     */
    public List<Object[]> rows(PreparedQuery query, List<?> values) {
        try (QueryResult result = execute(query, values)) {
            return result.rows();
        }
    }

    /**
     * Runs a prepared statement that writes with values for its parameters
     * and returns the number of rows written.
     *
     * @throws IllegalArgumentException if the values do not fit the parameters
     * @throws SqlException             if the statement does not bind any more, is a query, or fails
     */
    public long update(PreparedQuery query, List<?> values) {
        return session.update(query, values);
    }

    /**
     * Runs a statement; the caller closes the result. A statement that
     * writes has run to its end when this returns.
     *
     * @throws SqlException if the statement cannot be parsed or bound, or a write fails
     */
    public QueryResult sql(String sql) {
        return session.sql(sql);
    }

    /**
     * Runs a statement and collects all rows as Java values.
     *
     * @throws SqlException if the statement cannot be parsed or bound, or a write fails
     */
    public List<Object[]> rows(String sql) {
        try (QueryResult result = sql(sql)) {
            return result.rows();
        }
    }

    /**
     * Runs a statement that writes and returns the number of rows written:
     * by {@code COPY}, {@code CREATE TABLE ... AS} and {@code INSERT}, and
     * 0 for {@code CREATE TABLE} with columns and {@code DROP TABLE}.
     *
     * @throws SqlException if the statement cannot be parsed or bound, is a query, or fails
     */
    public long update(String sql) {
        return session.update(sql);
    }

    /**
     * Runs a bound statement that writes, to its end, and returns the
     * number of rows written; a context of the caller cancels it, which
     * abandons what was written, and so does the timeout of the query.
     *
     * @throws SqlException if the statement is a query or {@code EXPLAIN}, or the write fails
     */
    public long update(SqlStatement statement, QueryContext context) {
        return update(statement.bound(), context, statement.sql(), session);
    }

    /**
     * Runs a statement that writes in a session, see
     * {@link #update(SqlStatement, QueryContext)}, as an event of Java
     * Flight Recorder, if one is recorded; {@code SET} changes the session.
     *
     * @param sql the text of the statement, or {@code null} for its line in {@link #explain(String)}
     */
    long update(BoundStatement statement, QueryContext context, String sql, Session session) {
        if (statement instanceof BoundStatement.Query || statement instanceof BoundStatement.Explain) {
            throw new SqlException((statement instanceof BoundStatement.Query ? "A query" : "EXPLAIN")
                    + " returns rows, not a number of rows written; run it with sql", Position.UNKNOWN);
        }
        QueryEvent event = RECORDABLE ? QueryEvent.start() : null;
        ScheduledFuture<?> timeout = timeouts.start(context, session.timeout());
        long count = 0;
        Throwable failure = null;
        try {
            count = writes.run(statement, context, session);
            return count;
        } catch (RuntimeException | Error e) {
            failure = e;
            throw e;
        } finally {
            Timeouts.stop(timeout);
            if (event != null) {
                event.done(sql != null ? sql : Explain.header(statement), context, count, failure);
            }
        }
    }

    /**
     * Runs a bound statement: a query as {@link #execute(PlanNode, QueryContext)}
     * does, a statement that writes to its end, with a result of one row
     * of the column {@code Count}, and {@code EXPLAIN} with a result of
     * one row per line of the plan in the column {@code plan}, see
     * {@link #explain(SqlStatement)} and
     * {@link #explainAnalyze(SqlStatement, QueryContext)}. The caller
     * closes the result.
     *
     * @throws SqlException if the write fails
     */
    public QueryResult execute(SqlStatement statement, QueryContext context) {
        return execute(statement.bound(), context, statement.sql(), session);
    }

    /**
     * Runs a bound statement in a session, see {@link #execute(SqlStatement, QueryContext)}.
     *
     * @param sql the text of the statement for its event of Java Flight Recorder, or {@code null}
     */
    QueryResult execute(BoundStatement statement, QueryContext context, String sql, Session session) {
        if (statement instanceof BoundStatement.Query q) {
            return execute(q.plan(), context, sql, session);
        }
        if (statement instanceof BoundStatement.Explain e) {
            String plan = e.analyze() ? explainAnalyze(e.statement(), context, session)
                    : explain(e.statement(), session);
            List<Object[]> lines = new ArrayList<>();
            for (String line : plan.split("\n")) {
                lines.add(new Object[] {line});
            }
            return result(new PlanNode.Values(BoundStatement.PLAN, lines), context, session);
        }
        long count = update(statement, context, sql, session);
        return result(new PlanNode.Values(BoundStatement.COUNT, List.<Object[]>of(new Object[] {count})), context,
                session);
    }

    /** The result of a statement that has run, whose operators count nothing for the context of the statement. */
    private QueryResult result(PlanNode.Values rows, QueryContext context, Session session) {
        return new QueryResult(open(rows, QueryContext.NONE, session), context, null, session.timeZone());
    }

    /**
     * Returns the plan of a query as text, one node per line with its
     * inputs indented below it; a column is written as {@code #index}, its
     * position in the input of the node. The plan is the one the engine
     * runs, see {@link #optimize}: a scan whose connector takes over parts
     * of the plan above it shows them as {@code [pushed: ...]}, a part of the
     * plan that a source runs as a whole is a line {@code Remote} with what
     * the source runs, e.g. its statement, and the part below it, each line
     * after a {@code |}, and a node
     * whose expressions or keys the engine compiles once enough rows pass
     * is marked {@code [codegen]}; a chain of filters and projections that
     * runs as one compiled pipeline is marked {@code [codegen pipeline]} at
     * its top and {@code [codegen]} below. With more than one thread, the
     * top of a chain of filters, projections, unnests and joins (down the
     * side they probe) over a scan, or of a union of such chains without
     * joins, is marked {@code [parallel]}: the chain runs on several threads
     * if the scan has more than one split.
     * <p>
     * A statement that writes is a line of what it writes, with the plan
     * of the rows below it, indented.
     *
     * @throws SqlException if the statement cannot be parsed or bound
     */
    public String explain(String sql) {
        return session.explain(sql);
    }

    /** Returns a plan as the engine runs it, as text in the form of {@link #explain(String)}. */
    public String explain(PlanNode plan) {
        return explain(plan, session);
    }

    /** Returns a plan as a session runs it. */
    String explain(PlanNode plan, Session session) {
        Execution x = session.execution();
        return Explain.print(x.optimizer().optimize(plan), codegen, x.optimizer().estimator(), executor.threads());
    }

    /**
     * Returns a statement as the engine runs it, as text in the form of
     * {@link #explain(String)}; for {@code EXPLAIN}, that of its statement.
     */
    public String explain(SqlStatement statement) {
        return explain(statement.bound(), session);
    }

    /** Returns a bound statement as a session runs it, see {@link #explain(SqlStatement)}. */
    String explain(BoundStatement statement, Session session) {
        if (statement instanceof BoundStatement.Explain e) {
            return explain(e.statement(), session);
        }
        if (statement instanceof BoundStatement.Query q) {
            return explain(q.plan(), session);
        }
        StringBuilder sb = new StringBuilder(Explain.header(statement)).append('\n');
        PlanNode input = switch (statement) {
            case BoundStatement.Copy c -> c.input();
            case BoundStatement.CopyFrom c -> c.input();
            case BoundStatement.Create c -> c.input();
            case BoundStatement.Insert i -> i.input();
            default -> null;
        };
        if (input != null) {
            for (String line : explain(input, session).split("\n")) {
                sb.append("  ").append(line).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Runs a statement and returns its plan with what each operator and scan
     * did, as text: a first line such as
     * {@code Analyzed in 1.23 s: 4 rows, 12 threads}, then the plan the
     * query ran, see {@link #explain(QueryStatistics)}. The rows of a
     * query are read and dropped; a statement that writes writes, and its
     * line comes before the plan of its rows, as in {@link #explain(String)}.
     *
     * @throws SqlException if the statement cannot be parsed or bound, or fails
     */
    public String explainAnalyze(String sql) {
        return session.explainAnalyze(sql);
    }

    /** Runs a plan and returns it with what each operator did, see {@link #explainAnalyze(String)}. */
    public String explainAnalyze(PlanNode plan) {
        return explainAnalyze(new BoundStatement.Query(plan), new QueryContext(), session);
    }

    /**
     * Runs a statement in a context of the caller, which cancels it and
     * gives it its timeout, see {@link #execute(PlanNode, QueryContext)},
     * and returns its plan with what each operator did, see
     * {@link #explainAnalyze(String)}; for {@code EXPLAIN}, its statement.
     *
     * @throws SqlException if the statement fails
     */
    public String explainAnalyze(SqlStatement statement, QueryContext context) {
        return explainAnalyze(statement.bound(), context, session);
    }

    /** Runs a statement in a session and returns its plan with what each operator did. */
    String explainAnalyze(BoundStatement statement, QueryContext context, Session session) {
        BoundStatement run = statement instanceof BoundStatement.Explain e ? e.statement() : statement;
        if (run instanceof BoundStatement.Query q) {
            long rows = 0;
            try (QueryResult result = execute(q.plan(), context, null, session)) {
                for (Batch b = result.nextBatch(); b != null; b = result.nextBatch()) {
                    rows += b.rowCount();
                }
            }
            QueryStatistics statistics = context.statistics();
            return Explain.analyzed(statistics, rows, false) + "\n" + explain(statistics, session);
        }
        long written = update(run, context, null, session);
        context.finished();
        QueryStatistics statistics = context.statistics();
        StringBuilder sb = new StringBuilder(Explain.analyzed(statistics, written, true)).append('\n')
                .append(Explain.header(run)).append('\n');
        if (statistics.plan() != null) {
            for (String line : explain(statistics, session).split("\n")) {
                sb.append("  ").append(line).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Returns the plan a query ran with what each of its operators and scans
     * did, as text: each line of the plan as in {@link #explain(String)}
     * followed by {@code --} and the rows and batches the operator
     * returned, the time it worked without the time of its inputs, summed
     * over the threads it worked on, and those threads; for a scan the
     * splits, the row groups and files it read of all, the requests to its
     * source and the bytes it read, and below it, after {@code >}, each
     * statement sent to a database; and what an operator spilled to disk.
     * A node of a compiled pipeline below its top has no numbers. Empty if
     * the query opened no plan.
     *
     * @param statistics what a query did, see {@link QueryResult#statistics()}
     */
    public String explain(QueryStatistics statistics) {
        return explain(statistics, session);
    }

    /** Returns the plan a query of a session ran with what each of its operators did. */
    String explain(QueryStatistics statistics, Session session) {
        return Explain.print(statistics, codegen, session.execution().optimizer().estimator(), executor.threads());
    }

    /**
     * Runs a plan, after {@link #optimize}; the caller closes the result,
     * which {@link QueryResult#cancel()} cancels from another thread.
     */
    public QueryResult execute(PlanNode plan) {
        return execute(plan, new QueryContext());
    }

    /**
     * Runs a plan as {@link #execute(PlanNode)} does, for a query that a
     * context of the caller cancels, also before the result is there;
     * once the context is canceled, reading the result fails with a
     * {@link net.fyrql.exec.QueryCancelledException}. The timeout of the
     * query, see {@link QueryContext#withTimeout} and
     * {@link Builder#queryTimeout(Duration)}, counts from the creation of
     * the context until the result is closed; once it has passed, the
     * context is canceled with {@link QueryContext.Reason#TIMEOUT}.
     */
    public QueryResult execute(PlanNode plan, QueryContext context) {
        return execute(plan, context, null, session);
    }

    /**
     * Runs a plan with the timeout of a session, see
     * {@link #execute(PlanNode, QueryContext)}; while Java Flight Recorder
     * records, the run is an event {@code net.fyrql.Query} until the result
     * is closed.
     *
     * @param sql the text of the statement for the event, or {@code null} for the first line of the plan
     */
    QueryResult execute(PlanNode plan, QueryContext context, String sql, Session session) {
        QueryEvent event = RECORDABLE ? QueryEvent.start() : null;
        ScheduledFuture<?> timeout = timeouts.start(context, session.timeout());
        try {
            return new QueryResult(open(plan, context, session), context, timeout, session.timeZone(), event, sql);
        } catch (RuntimeException | Error e) {
            Timeouts.stop(timeout);
            if (event != null) {
                event.done(sql, context, 0, e);
            }
            throw e;
        }
    }

    /**
     * Opens the operator of a plan, after {@link #optimize}; the optimized
     * plan is noted in the context, if it is the first one of the query, see
     * {@link QueryContext#plan(PlanNode)}.
     */
    Operator open(PlanNode plan, QueryContext context, Session session) {
        Execution x = session.execution();
        PlanNode optimized = x.optimizer().optimize(plan);
        context.plan(optimized);
        return x.executor().open(optimized, x.optimizer().estimator(), context);
    }

    /** Runs a plan and collects all rows as Java values. */
    public List<Object[]> rows(PlanNode plan) {
        try (QueryResult result = execute(plan)) {
            return result.rows();
        }
    }

    /** Runs a plan and collects all batches. */
    public List<Batch> batches(PlanNode plan) {
        try (QueryResult result = execute(plan)) {
            List<Batch> batches = new ArrayList<>();
            for (Batch b = result.nextBatch(); b != null; b = result.nextBatch()) {
                batches.add(b);
            }
            return batches;
        }
    }

    /**
     * Opens the reader of a scan, which reads its splits one after the
     * other on one thread, see {@link Splits#sequential}.
     */
    private BatchReader open(PlanNode.Scan scan, QueryContext context, Function<String, Connector> connectors) {
        return Splits.sequential(splits(scan, 1, connectors), context);
    }

    /**
     * Returns the splits of a scan: the connector plans it once more, with
     * the filters it took over and the hints, the same filters as when the
     * scan was negotiated, which must give what the scan carries as taken
     * over; for at most {@code threads} threads that read them at a time.
     */
    private Splits splits(PlanNode.Scan scan, int threads, Function<String, Connector> connectors) {
        Connector connector = connectors.apply(scan.table().source());
        PlanNode.Pushed pushed = scan.pushed();
        List<Expression> filters = new ArrayList<>(pushed.filters());
        filters.addAll(pushed.hints());
        ScanRequest request = new ScanRequest(ColumnPath.of(scan), filters, pushed.limit(), pushed.order(),
                pushed.aggregation(), pushed.delivered().orElse(scan.columns().size()));
        ScanPlan plan = connector.plan(scan.table(), request);
        if (!plan.columns().equals(request.columns())) {
            throw new IllegalStateException("The connector " + connector.name() + " reads " + plan.columns()
                    + " when opening a scan of " + request.columns());
        }
        if (!plan.acceptedFilters().equals(pushed.filters()) || plan.limitAccepted() != pushed.limit().isPresent()
                || plan.orderAccepted() != !pushed.order().isEmpty()
                || plan.aggregationAccepted() != pushed.aggregation().isPresent()) {
            throw new IllegalStateException("The connector " + connector.name() + " accepts "
                    + plan.acceptedFilters().size() + " of " + pushed.filters().size() + " filters, limit "
                    + plan.limitAccepted() + ", order " + plan.orderAccepted() + ", aggregation "
                    + plan.aggregationAccepted() + " when opening a scan that carries " + pushed);
        }
        if (!plan.output().types().equals(scan.schema().types())) {
            throw new IllegalStateException("The connector " + connector.name() + " plans " + plan.output()
                    + " for a scan of " + scan.schema());
        }
        List<Split> splits = connector.splits(plan, threads);
        SourceCounters.current().splits(splits.size());
        return Splits.of(splits, connector::open);
    }

    /**
     * Opens the reader of a part of the plan that a source runs: the
     * connector plans it once more, which must give the columns of the
     * part.
     */
    private BatchReader open(PlanNode.Remote remote, Function<String, Connector> connectors) {
        Connector connector = connectors.apply(remote.source());
        RemotePlan plan = connector.planRemote(remote.logical()).orElseThrow(() -> new IllegalStateException(
                "The connector " + connector.name() + " declines a part of a plan it accepted before: "
                        + remote.description()));
        if (!plan.output().types().equals(remote.schema().types())) {
            throw new IllegalStateException("The connector " + connector.name() + " plans " + plan.output()
                    + " for a part of a plan of " + remote.schema());
        }
        return connector.openRemote(plan);
    }

    /**
     * Creates a view of the builder in the session of the engine, as
     * {@code CREATE VIEW} does.
     *
     * @throws SqlException if the name or the query is wrong, the query does
     *         not bind, or there is a view or table of the name; the message
     *         names the view, and the position is in its query
     */
    private void createView(Builder.ViewSpec view) {
        try {
            String sql = view.sql().strip();
            while (sql.endsWith(";")) {
                sql = sql.substring(0, sql.length() - 1).strip();
            }
            CreateView create = new CreateView(SqlParser.parseName(view.name()), view.columns(),
                    SqlParser.parse(sql), sql, false, false, Position.UNKNOWN);
            update(session.binder().bind(create), new QueryContext(), null, session);
        } catch (SqlException e) {
            throw new SqlException("View " + view.name() + ": " + e.reason(), e.position(), e);
        }
    }

    /** Releases what the engine holds besides its sources, after a failure to build it. */
    private void abandon(Throwable failure) {
        try {
            if (spill != null) {
                spill.close();
            }
            timeouts.close();
        } catch (RuntimeException e) {
            failure.addSuppressed(e);
        }
    }

    /**
     * Closes the sources, deletes the directory to which operators spilled
     * and stops the thread of the timeouts.
     */
    @Override
    public void close() {
        try {
            for (Connector c : connectors.values()) {
                c.close();
            }
        } finally {
            try {
                if (spill != null) {
                    spill.close();
                }
            } finally {
                timeouts.close();
            }
        }
    }

    /** Collects the sources and settings of an engine. */
    public static final class Builder {
        private final Map<String, Connector> connectors = new LinkedHashMap<>();
        private long memoryBudget = Long.MAX_VALUE;
        private int batchSize = Executor.DEFAULT_BATCH_SIZE;
        private String defaultSource;
        private String defaultSchema = TableRef.MAIN;
        private FunctionRegistry functions;
        private CodegenMode codegenMode;
        private long codegenThreshold = Codegen.DEFAULT_THRESHOLD;
        private Boolean pushdown;
        private int threads;
        private boolean preserveOrder = true;
        private boolean spill = true;
        private Path spillDirectory;
        private boolean implicitSources = true;
        private FileAccess fileAccess = FileAccess.READ_WRITE;
        private List<String> allowedLocations = List.of();
        private Duration queryTimeout;
        private Duration maxQueryTimeout;
        private ClassLoader classLoader;
        private ZoneId timeZone = ZoneId.systemDefault();

        private final List<ViewSpec> views = new ArrayList<>();

        private Builder() {
        }

        /** A view of the builder, see {@link #view(String, List, String)}. */
        private record ViewSpec(String name, List<String> columns, String sql) {
        }

        /**
         * Defines a view, as {@code CREATE VIEW name AS sql} does, which the
         * engine creates when it is built, see {@link #view(String, List, String)}.
         */
        public Builder view(String name, String sql) {
            return view(name, List.of(), sql);
        }

        /**
         * Defines a view, as {@code CREATE VIEW name (columns) AS sql} does,
         * which the engine creates when it is built, after the views defined
         * before it, which its query may read. The engine keeps it until it
         * closes, as it keeps the views of {@code CREATE VIEW}.
         *
         * @param name the name of the view, of one to three parts with dots
         *        between, each as in SQL, unquoted or in double quotes; it
         *        resolves as the name of a new table does
         * @param columns the names of its columns, or none for the names its
         *        query gives them
         * @param sql its query
         * @see #build()
         */
        public Builder view(String name, List<String> columns, String sql) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(sql, "sql");
            views.add(new ViewSpec(name, List.copyOf(columns), sql));
            return this;
        }

        /** Registers a source under the connector's name. */
        public Builder register(Connector connector) {
            Objects.requireNonNull(connector, "connector");
            if (connectors.putIfAbsent(connector.name(), connector) != null) {
                throw new IllegalArgumentException("A source " + connector.name() + " is already registered");
            }
            return this;
        }

        /**
         * Sets the bytes the queries of the engine may hold in joins,
         * aggregations and sorts; unlimited by default. Above it,
         * aggregations, sorts and windows spill to disk, see
         * {@link #spill(boolean)}, and the other operators fail.
         */
        public Builder memoryBudget(long bytes) {
            this.memoryBudget = bytes;
            return this;
        }

        /**
         * Sets whether aggregations, sorts and windows spill to disk when
         * the memory budget is exhausted, instead of failing; on by default.
         * Without a budget, nothing spills.
         */
        public Builder spill(boolean enabled) {
            this.spill = enabled;
            return this;
        }

        /**
         * Sets the directory under which the engine creates its directory
         * for spilling, named {@code fyrql-spill-} and a random suffix,
         * when an operator spills first; it is deleted when the engine
         * closes. By default the temporary directory of the JVM, the system
         * property {@code java.io.tmpdir}; a directory that does not exist
         * is created.
         *
         * @throws IllegalArgumentException if the path exists and is no directory that can be written
         */
        public Builder spillDirectory(Path directory) {
            Objects.requireNonNull(directory, "directory");
            if (Files.exists(directory) && !(Files.isDirectory(directory) && Files.isWritable(directory))) {
                throw new IllegalArgumentException("The spill directory " + directory
                        + " is no directory that can be written");
            }
            this.spillDirectory = directory;
            return this;
        }

        /**
         * Sets what statements in SQL may do with the files and objects they
         * name: {@code FROM 'orders.csv'}, table functions such as
         * {@code read_parquet} and {@code iceberg_scan}, {@code COPY ... TO}
         * and {@code COPY ... FROM}; {@code READ_WRITE} by default, as for an
         * application that runs its own statements. An engine that runs
         * statements of others, such as behind a server, takes
         * {@code NONE} or {@code READ} with {@link #allowedLocations}. The
         * tables of the registered sources are not affected.
         *
         * @see FileAccessPolicy
         */
        public Builder fileAccess(FileAccess access) {
            this.fileAccess = Objects.requireNonNull(access, "access");
            return this;
        }

        /**
         * Sets the directories and prefixes, such as {@code s3://lake/},
         * under which statements in SQL may read and, with
         * {@code READ_WRITE}, write files; none by default, which allows
         * every location. A relative directory is resolved against the
         * working directory, and symbolic links are followed; see
         * {@link FileAccessPolicy}.
         */
        public Builder allowedLocations(List<String> locations) {
            this.allowedLocations = List.copyOf(locations);
            return this;
        }

        /**
         * Sets how long a query that asks for no timeout of its own may run,
         * from the creation of its {@link QueryContext} until its result is
         * closed, so reading the result counts; none by default. A query
         * asks for its own with {@link QueryContext#withTimeout(Duration)},
         * as the JDBC driver does for {@code Statement.setQueryTimeout}.
         * Once the timeout has passed, the query is canceled and fails with
         * {@link QueryContext.Reason#TIMEOUT}.
         *
         * @param timeout the timeout, {@code null} or zero for none
         * @throws IllegalArgumentException if the timeout is negative
         */
        public Builder queryTimeout(Duration timeout) {
            this.queryTimeout = timeout(timeout, "queryTimeout");
            return this;
        }

        /**
         * Sets how long any query may run, also one that asks for a longer
         * timeout of its own; none by default. A server that runs the
         * queries of others sets it so that no client holds the engine
         * without end.
         *
         * @param timeout the upper bound, {@code null} or zero for none
         * @throws IllegalArgumentException if the timeout is negative
         * @see #queryTimeout(Duration)
         */
        public Builder maxQueryTimeout(Duration timeout) {
            this.maxQueryTimeout = timeout(timeout, "maxQueryTimeout");
            return this;
        }

        private static Duration timeout(Duration timeout, String name) {
            if (timeout != null && timeout.isNegative()) {
                throw new IllegalArgumentException(name + ": " + timeout);
            }
            return timeout == null || timeout.isZero() ? null : timeout;
        }

        /** Sets the number of rows per batch. */
        public Builder batchSize(int batchSize) {
            if (batchSize < 1) {
                throw new IllegalArgumentException("batchSize: " + batchSize);
            }
            this.batchSize = batchSize;
            return this;
        }

        /**
         * Sets the source in which names of fewer than three parts are looked
         * up first; by default the only source, if exactly one is registered.
         */
        public Builder defaultSource(String source) {
            this.defaultSource = Objects.requireNonNull(source, "source");
            return this;
        }

        /** Sets the schema of the default source in which one-part names are looked up first; {@code main} by default. */
        public Builder defaultSchema(String schema) {
            this.defaultSchema = Objects.requireNonNull(schema, "schema");
            return this;
        }

        /**
         * Sets the scalar functions of SQL queries; by default
         * {@link FunctionRegistry#standard()}. An application adds its own
         * functions to a registry of the built-in ones.
         */
        public Builder functions(FunctionRegistry registry) {
            this.functions = Objects.requireNonNull(registry, "registry");
            return this;
        }

        /**
         * Sets when expressions, filters and keys are compiled: never
         * ({@code OFF}), after the threshold of rows in an operator
         * ({@code AUTO}), or from the first row on ({@code ALWAYS}). The
         * compiler comes from the module {@code fyrql-codegen}; without it,
         * {@code AUTO} interprets everything and {@code ALWAYS} is an error.
         * A chain of filters and projections runs as one compiled pipeline
         * in {@code ALWAYS}, and in {@code AUTO} if the estimated rows of
         * the input of the chain reach the threshold; a table whose catalog
         * does not know its rows counts as 1,000 rows.
         * The default is the system property {@value CodegenMode#PROPERTY},
         * or {@code AUTO}.
         */
        public Builder codegen(CodegenMode mode) {
            this.codegenMode = Objects.requireNonNull(mode, "mode");
            return this;
        }

        /**
         * Sets the rows an operator processes before it compiles in
         * {@code AUTO}, and the estimated rows from which a chain runs as a
         * compiled pipeline; 10,000 by default.
         */
        public Builder codegenThreshold(long rows) {
            if (rows < 0) {
                throw new IllegalArgumentException("codegenThreshold: " + rows);
            }
            this.codegenThreshold = rows;
            return this;
        }

        /**
         * Sets whether scans are negotiated with their connectors, so that
         * a source evaluates what it can of the filters, aggregation, sort
         * and limit above a scan, and of the joins of its own tables; on by
         * default, or as the system property
         * {@value Optimizer.Options#PROPERTY} says. Off, every source only
         * reads columns and the engine does the rest; the results are the
         * same, which is what the tests of a connector check.
         */
        public Builder pushdown(boolean enabled) {
            this.pushdown = enabled;
            return this;
        }

        /**
         * Sets the most threads on which one query runs at a time; by
         * default the system property {@value Executor#THREADS_PROPERTY},
         * or the number of processors. With 1, a query runs on the thread
         * that reads its result.
         */
        public Builder threads(int threads) {
            if (threads < 1) {
                throw new IllegalArgumentException("threads: " + threads);
            }
            this.threads = threads;
            return this;
        }

        /**
         * Sets whether rows that no sort orders come in the order in which
         * their sources deliver them, as on one thread, also under a
         * {@code LIMIT}; on by default. Off, the rows of parts of a scan
         * that run in parallel come as they are done.
         */
        public Builder preserveOrder(boolean enabled) {
            this.preserveOrder = enabled;
            return this;
        }

        /**
         * Sets whether the engine adds a source for the formats of files
         * that no registered source reads and writes; on by default. Each
         * {@link ConnectorFactory} on the class path with
         * {@link ConnectorFactory#fileFormats() formats of files}, such as
         * that of {@code fyrql-connector-parquet}, then becomes a source
         * without options, named after its type, unless registered sources
         * read and write all its formats or one has that name. A factory
         * without formats that is {@link ConnectorFactory#implicit() implicit},
         * such as that of {@code fyrql-connector-iceberg}, becomes a source
         * unless registered sources offer all its table functions. The engine
         * closes these sources; they follow the registered ones in
         * {@link #sources()} and are never the default source by themselves.
         */
        public Builder implicitSources(boolean enabled) {
            this.implicitSources = enabled;
            return this;
        }

        /**
         * Sets the time zone of the session, in which values of
         * {@code TIMESTAMP WITH TIME ZONE} are shown, compared with local
         * dates and timestamps, and computed in the calendar; by default the
         * time zone of the JVM, as in DuckDB. Every session starts with it,
         * {@code SET TimeZone} changes it for a session, see {@link Session},
         * {@code RESET TimeZone} returns to this one.
         */
        public Builder timeZone(ZoneId zone) {
            this.timeZone = Objects.requireNonNull(zone, "zone");
            return this;
        }

        /**
         * Sets the class loader in which the engine looks for the factories
         * of the implicit sources; by default the one that loaded the
         * engine.
         *
         * @see #implicitSources(boolean)
         */
        public Builder classLoader(ClassLoader loader) {
            this.classLoader = Objects.requireNonNull(loader, "loader");
            return this;
        }

        /**
         * Builds the engine.
         *
         * @throws IllegalArgumentException if the default source is not registered, there are allowed
         *         locations with the file access {@code NONE} or one is invalid, the query timeout is above
         *         its upper bound, or the system property
         *         {@value CodegenMode#PROPERTY}, {@value Optimizer.Options#PROPERTY} or
         *         {@value Executor#THREADS_PROPERTY} has an unknown value
         * @throws IllegalStateException    if code generation is {@code ALWAYS} without a compiler, or an
         *         implicit source cannot be created
         * @throws SqlException             if a view cannot be created; the message names the view, and the
         *         position is in its query
         */
        public FyrQL build() {
            CodegenMode mode = codegenMode != null ? codegenMode : CodegenMode.fromSystemProperty();
            Codegen codegen = Codegen.of(mode, codegenThreshold, mode == CodegenMode.OFF ? null
                    : Codegen.loadCompiler());
            Optimizer.Options options = pushdown == null ? Optimizer.Options.fromSystemProperty()
                    : pushdown ? Optimizer.Options.DEFAULT : Optimizer.Options.NO_PUSHDOWN;
            int engineThreads = threads > 0 ? threads : Executor.threadsFromSystemProperty();
            FileAccessPolicy files = FileAccessPolicy.of(fileAccess, allowedLocations, Path.of(""));
            Timeouts timeouts = new Timeouts(queryTimeout, maxQueryTimeout);
            String source = defaultSource != null ? defaultSource
                    : connectors.size() == 1 ? connectors.keySet().iterator().next() : null;
            // A copy, so that the builder can build again with the sources registered so far.
            Map<String, Connector> sources = new LinkedHashMap<>(connectors);
            List<Connector> implicit = implicitSources ? addImplicitSources(sources) : List.of();
            try {
                FileStores stores = fileStores(sources, files);
                for (Connector c : sources.values()) {
                    c.fileStores(stores);
                }
                FyrQL engine = new FyrQL(sources, new SearchPath(source, defaultSchema),
                        functions != null ? functions : FunctionRegistry.standard(),
                        new MemoryBudget(memoryBudget), batchSize, codegen, options, engineThreads, preserveOrder,
                        !spill ? null : spillDirectory != null ? new SpillDirectory(spillDirectory)
                                : SpillDirectory.temporary(),
                        timeZone, files, timeouts);
                try {
                    for (ViewSpec v : views) {
                        engine.createView(v);
                    }
                } catch (RuntimeException | Error e) {
                    engine.abandon(e);
                    throw e;
                }
                return engine;
            } catch (RuntimeException | Error e) {
                close(implicit, e);
                throw e;
            }
        }

        /**
         * Returns the stores of files the sources offer, by their schemes.
         *
         * @throws IllegalStateException if two sources offer a store of the same scheme
         */
        private static FileStores fileStores(Map<String, Connector> sources, FileAccessPolicy policy) {
            Map<String, FileStore> stores = new LinkedHashMap<>();
            Map<String, String> owners = new HashMap<>();
            for (Connector c : sources.values()) {
                Optional<FileStore> store = c.fileStore();
                if (store.isPresent()) {
                    String scheme = store.get().scheme().toLowerCase(Locale.ROOT);
                    String other = owners.putIfAbsent(scheme, c.name());
                    if (other != null) {
                        throw new IllegalStateException("Two sources handle " + scheme + ":// locations: " + other
                                + " and " + c.name());
                    }
                    stores.put(scheme, store.get());
                }
            }
            return new FileStores(stores, Path.of(""), policy);
        }

        /** Adds the implicit sources to the sources and returns them. */
        private List<Connector> addImplicitSources(Map<String, Connector> sources) {
            Set<String> written = new HashSet<>();
            Set<String> read = new HashSet<>();
            Set<String> functions = new HashSet<>();
            for (Connector c : sources.values()) {
                if (c instanceof TableFunctionProvider p) {
                    functions.addAll(p.tableFunctions());
                }
                if (c instanceof FileWriterProvider p) {
                    for (String f : p.fileFormats()) {
                        written.add(f.toLowerCase(Locale.ROOT));
                    }
                }
                if (c instanceof FileReaderProvider p) {
                    for (String f : p.fileFormats()) {
                        read.add(f.toLowerCase(Locale.ROOT));
                    }
                }
            }
            List<ConnectorFactory> factories = new ArrayList<>();
            for (ConnectorFactory f : ServiceLoader.load(ConnectorFactory.class,
                    classLoader != null ? classLoader : FyrQL.class.getClassLoader())) {
                if (f.implicit()) {
                    factories.add(f);
                }
            }
            factories.sort(Comparator.comparing(ConnectorFactory::type));
            List<Connector> added = new ArrayList<>();
            for (ConnectorFactory f : factories) {
                List<String> formats = f.fileFormats().stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
                if (sources.containsKey(f.type())
                        || !formats.isEmpty() && written.containsAll(formats) && read.containsAll(formats)) {
                    continue;
                }
                Connector c;
                try {
                    c = f.create(f.type(), Map.of(), Path.of(""));
                } catch (RuntimeException e) {
                    IllegalStateException failure = new IllegalStateException(
                            "Cannot create the implicit source " + f.type() + ": " + e.getMessage(), e);
                    close(added, failure);
                    throw failure;
                }
                if (formats.isEmpty() && c instanceof TableFunctionProvider p
                        && functions.containsAll(p.tableFunctions())) {
                    c.close();
                    continue;
                }
                sources.put(f.type(), c);
                added.add(c);
                if (c instanceof TableFunctionProvider p) {
                    functions.addAll(p.tableFunctions());
                }
                written.addAll(formats);
                read.addAll(formats);
            }
            return added;
        }

        private static void close(List<Connector> connectors, Throwable failure) {
            for (Connector c : connectors) {
                try {
                    c.close();
                } catch (RuntimeException e) {
                    failure.addSuppressed(e);
                }
            }
        }
    }
}
