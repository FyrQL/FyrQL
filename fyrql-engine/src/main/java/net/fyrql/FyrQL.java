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
import net.fyrql.core.value.ZonedDates;
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
import net.fyrql.exec.spi.CodegenStatistics;
import net.fyrql.sql.SqlException;
import net.fyrql.sql.ast.Position;
import net.fyrql.sql.ast.SqlExpr;
import net.fyrql.sql.bind.Binder;
import net.fyrql.sql.bind.BoundStatement;
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

/**
 * The engine: registered connectors, a catalog over them, and the
 * execution of queries. A query is SQL text, which {@link #plan(String)}
 * parses and binds into a {@link PlanNode}, or a plan built
 * programmatically, starting with {@link #scan}. The {@link #catalog()}
 * resolves names along the search path of the builder, or along one given
 * with a query, see {@link #plan(String, SearchPath)}, and binds table
 * functions such as {@code read_csv}; the scalar functions come from the
 * {@link FunctionRegistry} of the builder. A query with parameters
 * {@code ?} is prepared once with {@link #prepare(String)} and run with
 * values for them with {@link #execute(PreparedQuery, List)}. With the module
 * {@code fyrql-codegen} on the class path, the engine compiles the
 * expressions, filters and keys of operators that process many rows; see
 * {@link Builder#codegen}.
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
    private final Binder binder;
    private final Optimizer optimizer;
    private final Executor executor;
    private final Codegen codegen;
    private final Writes writes;
    /** The directory to which operators spill, {@code null} if they do not. */
    private final SpillDirectory spill;
    /** The timeouts of the queries. */
    private final Timeouts timeouts;
    /** The time zone the session starts with, to which {@code RESET TimeZone} returns. */
    private final ZoneId initialZone;
    private volatile ZoneId zone;

    private FyrQL(Map<String, Connector> connectors, SearchPath searchPath, FunctionRegistry functions,
            MemoryBudget budget, int batchSize, Codegen codegen, Optimizer.Options options, int threads,
            boolean preserveOrder, SpillDirectory spill, ZoneId zone, FileAccessPolicy files, Timeouts timeouts) {
        this.connectors = Collections.unmodifiableMap(new LinkedHashMap<>(connectors));
        this.spill = spill;
        this.timeouts = timeouts;
        this.catalog = new EngineCatalog(this.connectors, searchPath, this::timeZone, files);
        this.catalogTables = new CatalogTables(this.connectors);
        this.functions = functions;
        this.initialZone = zone;
        this.zone = zone;
        this.binder = new Binder(catalog, functions, this::timeZone);
        this.optimizer = new Optimizer(this::connector, options);
        this.codegen = codegen;
        this.writes = new Writes(this);
        this.executor = new Executor(new TableSource() {
            @Override
            public BatchReader open(PlanNode.Scan scan) {
                return FyrQL.this.open(scan, QueryContext.NONE);
            }

            @Override
            public BatchReader open(PlanNode.Scan scan, QueryContext context) {
                return FyrQL.this.open(scan, context);
            }

            @Override
            public Splits splits(PlanNode.Scan scan, QueryContext context) {
                return FyrQL.this.splits(scan, 1);
            }

            @Override
            public Splits splits(PlanNode.Scan scan, int threads, QueryContext context) {
                return FyrQL.this.splits(scan, threads);
            }

            @Override
            public BatchReader open(PlanNode.Remote remote) {
                return FyrQL.this.open(remote);
            }
        }, budget, batchSize, codegen, threads, preserveOrder, spill);
    }

    /** Returns the catalog: names along the search path, columns, and table functions. */
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
     * Returns the time zone of the session, in which values of
     * {@code TIMESTAMP WITH TIME ZONE} are shown and their calendar computes:
     * that of the builder, until {@code SET TimeZone} changes it. The engine
     * is one session; every connection of the JDBC driver has an engine of
     * its own.
     */
    public ZoneId timeZone() {
        return zone;
    }

    /** Applies {@code SET} or {@code RESET} of a setting, the time zone. */
    void apply(BoundStatement.Set setting) {
        zone = setting.value() == null ? initialZone : ZonedDates.zone(setting.value());
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

    /** Returns the columns of a table. */
    public Schema schema(TableRef table) {
        return connector(table.source()).catalog().schema(table);
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
        return binder.bind(SqlParser.parse(sql));
    }

    /**
     * Parses and binds a query with names looked up along another search
     * path than that of the builder, e.g. the catalog and schema a client
     * has chosen; the table functions of its default source come first.
     *
     * @throws SqlException             if the query cannot be parsed or bound
     * @throws IllegalArgumentException if the default source of the path is not registered
     */
    public PlanNode plan(String sql, SearchPath searchPath) {
        return binder(searchPath).bind(SqlParser.parse(sql));
    }

    private Binder binder(SearchPath searchPath) {
        EngineCatalog c = catalog.withSearchPath(searchPath);
        return c == catalog ? binder : new Binder(c, functions, this::timeZone);
    }

    /**
     * Parses and binds a statement: a query, or a statement that writes,
     * with the plan of the rows it writes. Whether a table to create
     * exists, or one to write or drop, is checked when the statement runs.
     *
     * @throws SqlException if the statement cannot be parsed or bound; the
     *         message names the position in the text
     */
    public BoundStatement statement(String sql) {
        return binder.bind(SqlParser.parseStatement(sql));
    }

    /**
     * Parses and binds a statement with names looked up along a search
     * path, see {@link #plan(String, SearchPath)}; a table to create goes
     * to the default source and schema of the path if its name does not
     * say otherwise.
     *
     * @throws SqlException             if the statement cannot be parsed or bound
     * @throws IllegalArgumentException if the default source of the path is not registered
     */
    public BoundStatement statement(String sql, SearchPath searchPath) {
        return binder(searchPath).bind(SqlParser.parseStatement(sql));
    }

    /**
     * Parses and binds a query with parameters {@code ?}, without running
     * it: the types of the parameters and the columns of the result. A
     * parameter takes the type that its context gives a bare {@code NULL}:
     * the type of the other side of a comparison or of arithmetic, of the
     * other values of {@code IN}, {@code BETWEEN}, {@code CASE} and
     * {@code COALESCE}, {@code BOOLEAN} as a condition, the type of
     * {@code CAST(? AS type)}, and as an argument of a function the first
     * type it takes of {@code BIGINT}, {@code DOUBLE}, {@code INTEGER} and
     * so on, {@code VARCHAR} last; in {@code VALUES} of an {@code INSERT},
     * the type of the column its value goes to. A parameter without such a
     * context, as in {@code SELECT ?}, is an error, as is a parameter where
     * a constant is needed, such as in {@code LIMIT}, or one of a type with
     * a {@code MAP}, which has no literal. A statement that writes is
     * prepared as well; its result has the column {@code Count}.
     *
     * @throws SqlException if the query cannot be parsed or bound, or a
     *         parameter has no type or one with a {@code MAP}
     */
    public PreparedQuery prepare(String sql) {
        return prepare(sql, catalog.searchPath());
    }

    /**
     * Prepares a query, see {@link #prepare(String)}, with names looked up
     * along a search path, see {@link #plan(String, SearchPath)}.
     *
     * @throws SqlException             if the query cannot be parsed or bound, or a parameter has no type
     *                                  or one with a {@code MAP}
     * @throws IllegalArgumentException if the default source of the path is not registered
     */
    public PreparedQuery prepare(String sql, SearchPath searchPath) {
        SqlParser.ParameterizedStatement parsed = SqlParser.parseStatementWithParameters(sql);
        Binder.PreparedStatement bound = binder(searchPath).prepare(parsed.statement(), parsed.parameters());
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
     *        {@link QueryResult#rows()} gives them, {@code null} for {@code NULL}
     * @throws IllegalArgumentException if the number of values differs from
     *         that of the parameters, or a value is none of its type
     * @throws SqlException if the query does not bind any more, e.g. after
     *         a change of a source, or is no query
     */
    public PlanNode plan(PreparedQuery query, List<?> values) {
        return conform(plan(render(query, values), query.searchPath()), query.schema());
    }

    /**
     * Binds a prepared statement with values for its parameters, as
     * {@link #plan(PreparedQuery, List)} binds a query; a query comes with
     * the columns of the prepared query.
     *
     * @throws IllegalArgumentException if the values do not fit the parameters
     * @throws SqlException             if the statement does not bind any more
     */
    public BoundStatement statement(PreparedQuery query, List<?> values) {
        BoundStatement statement = statement(render(query, values), query.searchPath());
        return statement instanceof BoundStatement.Query q
                ? new BoundStatement.Query(conform(q.plan(), query.schema())) : statement;
    }

    /** Returns the text of a prepared statement with a literal of each value in place of its parameter. */
    private static String render(PreparedQuery query, List<?> values) {
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
    private static PlanNode conform(PlanNode plan, Schema schema) {
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
        return execute(statement(query, values), new QueryContext());
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
        return execute(statement(query, values), context);
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
        return update(statement(query, values), new QueryContext());
    }

    /**
     * Runs a statement; the caller closes the result. A statement that
     * writes has run to its end when this returns.
     *
     * @throws SqlException if the statement cannot be parsed or bound, or a write fails
     */
    public QueryResult sql(String sql) {
        return execute(statement(sql), new QueryContext(), sql);
    }

    /**
     * Runs a statement with names looked up along a search path, see
     * {@link #statement(String, SearchPath)}; the caller closes the result.
     *
     * @throws SqlException             if the statement cannot be parsed or bound, or a write fails
     * @throws IllegalArgumentException if the default source of the path is not registered
     */
    public QueryResult sql(String sql, SearchPath searchPath) {
        return execute(statement(sql, searchPath), new QueryContext(), sql);
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
        return update(statement(sql), new QueryContext(), sql);
    }

    /**
     * Runs a statement that writes with names looked up along a search
     * path, see {@link #statement(String, SearchPath)}, and returns the
     * number of rows written.
     *
     * @throws SqlException             if the statement cannot be parsed or bound, is a query, or fails
     * @throws IllegalArgumentException if the default source of the path is not registered
     */
    public long update(String sql, SearchPath searchPath) {
        return update(statement(sql, searchPath), new QueryContext(), sql);
    }

    /**
     * Runs a bound statement that writes, to its end, and returns the
     * number of rows written; a context of the caller cancels it, which
     * abandons what was written, and so does the timeout of the query.
     *
     * @throws SqlException if the statement is a query or {@code EXPLAIN}, or the write fails
     */
    public long update(BoundStatement statement, QueryContext context) {
        return update(statement, context, null);
    }

    /**
     * Runs a statement that writes, see {@link #update(BoundStatement, QueryContext)},
     * as an event of Java Flight Recorder, if one is recorded.
     *
     * @param sql the text of the statement, or {@code null} for its line in {@link #explain(String)}
     */
    private long update(BoundStatement statement, QueryContext context, String sql) {
        if (statement instanceof BoundStatement.Query || statement instanceof BoundStatement.Explain) {
            throw new SqlException((statement instanceof BoundStatement.Query ? "A query" : "EXPLAIN")
                    + " returns rows, not a number of rows written; run it with sql", Position.UNKNOWN);
        }
        QueryEvent event = RECORDABLE ? QueryEvent.start() : null;
        ScheduledFuture<?> timeout = timeouts.start(context);
        long count = 0;
        Throwable failure = null;
        try {
            count = writes.run(statement, context);
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
     * {@link #explain(BoundStatement)} and
     * {@link #explainAnalyze(BoundStatement, QueryContext)}. The caller
     * closes the result.
     *
     * @throws SqlException if the write fails
     */
    public QueryResult execute(BoundStatement statement, QueryContext context) {
        return execute(statement, context, null);
    }

    /**
     * Runs a bound statement, see {@link #execute(BoundStatement, QueryContext)}.
     *
     * @param sql the text of the statement for its event of Java Flight Recorder, or {@code null}
     */
    private QueryResult execute(BoundStatement statement, QueryContext context, String sql) {
        if (statement instanceof BoundStatement.Query q) {
            return execute(q.plan(), context, sql);
        }
        if (statement instanceof BoundStatement.Explain e) {
            String plan = e.analyze() ? explainAnalyze(e.statement(), context) : explain(e.statement());
            List<Object[]> lines = new ArrayList<>();
            for (String line : plan.split("\n")) {
                lines.add(new Object[] {line});
            }
            return result(new PlanNode.Values(BoundStatement.PLAN, lines), context);
        }
        long count = update(statement, context, sql);
        return result(new PlanNode.Values(BoundStatement.COUNT, List.<Object[]>of(new Object[] {count})), context);
    }

    /** The result of a statement that has run, whose operators count nothing for the context of the statement. */
    private QueryResult result(PlanNode.Values rows, QueryContext context) {
        return new QueryResult(open(rows, QueryContext.NONE), context, null);
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
        return explain(statement(sql));
    }

    /**
     * Returns the plan of a query with names looked up along a search path,
     * see {@link #plan(String, SearchPath)}, as text in the form of
     * {@link #explain(String)}.
     *
     * @throws SqlException             if the statement cannot be parsed or bound
     * @throws IllegalArgumentException if the default source of the path is not registered
     */
    public String explain(String sql, SearchPath searchPath) {
        return explain(statement(sql, searchPath));
    }

    /** Returns a plan as the engine runs it, as text in the form of {@link #explain(String)}. */
    public String explain(PlanNode plan) {
        return Explain.print(optimize(plan), codegen, optimizer.estimator(), executor.threads());
    }

    /**
     * Returns a statement as the engine runs it, as text in the form of
     * {@link #explain(String)}; for {@code EXPLAIN}, that of its statement.
     */
    public String explain(BoundStatement statement) {
        if (statement instanceof BoundStatement.Explain e) {
            return explain(e.statement());
        }
        if (statement instanceof BoundStatement.Query q) {
            return explain(q.plan());
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
            for (String line : explain(input).split("\n")) {
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
        return explainAnalyze(statement(sql), new QueryContext());
    }

    /**
     * Runs a statement with names looked up along a search path, see
     * {@link #statement(String, SearchPath)}, and returns its plan with what
     * each operator did, see {@link #explainAnalyze(String)}.
     *
     * @throws SqlException             if the statement cannot be parsed or bound, or fails
     * @throws IllegalArgumentException if the default source of the path is not registered
     */
    public String explainAnalyze(String sql, SearchPath searchPath) {
        return explainAnalyze(statement(sql, searchPath), new QueryContext());
    }

    /** Runs a plan and returns it with what each operator did, see {@link #explainAnalyze(String)}. */
    public String explainAnalyze(PlanNode plan) {
        return explainAnalyze(new BoundStatement.Query(plan), new QueryContext());
    }

    /**
     * Runs a statement in a context of the caller, which cancels it and
     * gives it its timeout, see {@link #execute(PlanNode, QueryContext)},
     * and returns its plan with what each operator did, see
     * {@link #explainAnalyze(String)}; for {@code EXPLAIN}, its statement.
     *
     * @throws SqlException if the statement fails
     */
    public String explainAnalyze(BoundStatement statement, QueryContext context) {
        BoundStatement run = statement instanceof BoundStatement.Explain e ? e.statement() : statement;
        if (run instanceof BoundStatement.Query q) {
            long rows = 0;
            try (QueryResult result = execute(q.plan(), context)) {
                for (Batch b = result.nextBatch(); b != null; b = result.nextBatch()) {
                    rows += b.rowCount();
                }
            }
            QueryStatistics statistics = context.statistics();
            return Explain.analyzed(statistics, rows, false) + "\n" + explain(statistics);
        }
        long written = update(run, context);
        context.finished();
        QueryStatistics statistics = context.statistics();
        StringBuilder sb = new StringBuilder(Explain.analyzed(statistics, written, true)).append('\n')
                .append(Explain.header(run)).append('\n');
        if (statistics.plan() != null) {
            for (String line : explain(statistics).split("\n")) {
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
        return Explain.print(statistics, codegen, optimizer.estimator(), executor.threads());
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
        return execute(plan, context, null);
    }

    /**
     * Runs a plan, see {@link #execute(PlanNode, QueryContext)}; while Java
     * Flight Recorder records, the run is an event {@code net.fyrql.Query}
     * until the result is closed.
     *
     * @param sql the text of the statement for the event, or {@code null} for the first line of the plan
     */
    private QueryResult execute(PlanNode plan, QueryContext context, String sql) {
        QueryEvent event = RECORDABLE ? QueryEvent.start() : null;
        ScheduledFuture<?> timeout = timeouts.start(context);
        try {
            return new QueryResult(open(plan, context), context, timeout, event, sql);
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
    Operator open(PlanNode plan, QueryContext context) {
        PlanNode optimized = optimize(plan);
        context.plan(optimized);
        return executor.open(optimized, optimizer.estimator(), context);
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
    private BatchReader open(PlanNode.Scan scan, QueryContext context) {
        return Splits.sequential(splits(scan, 1), context);
    }

    /**
     * Returns the splits of a scan: the connector plans it once more, with
     * the filters it took over and the hints, the same filters as when the
     * scan was negotiated, which must give what the scan carries as taken
     * over; for at most {@code threads} threads that read them at a time.
     */
    private Splits splits(PlanNode.Scan scan, int threads) {
        Connector connector = connector(scan.table().source());
        PlanNode.Pushed pushed = scan.pushed();
        List<Expression> filters = new ArrayList<>(pushed.filters());
        filters.addAll(pushed.hints());
        ScanRequest request = new ScanRequest(ColumnPath.of(scan), filters, pushed.limit(), pushed.order(),
                pushed.aggregation());
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
    private BatchReader open(PlanNode.Remote remote) {
        Connector connector = connector(remote.source());
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

        private Builder() {
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
         * time zone of the JVM, as in DuckDB. {@code SET TimeZone} changes
         * it for the engine, {@code RESET TimeZone} returns to this one.
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
                return new FyrQL(sources, new SearchPath(source, defaultSchema),
                        functions != null ? functions : FunctionRegistry.standard(),
                        new MemoryBudget(memoryBudget), batchSize, codegen, options, engineThreads, preserveOrder,
                        !spill ? null : spillDirectory != null ? new SpillDirectory(spillDirectory)
                                : SpillDirectory.temporary(),
                        timeZone, files, timeouts);
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
