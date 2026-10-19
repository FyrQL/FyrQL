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

package net.fyrql;

import net.fyrql.catalog.CatalogTables;
import net.fyrql.catalog.EngineCatalog;
import net.fyrql.catalog.SearchPath;
import net.fyrql.connector.BatchReader;
import net.fyrql.connector.Connector;
import net.fyrql.connector.ColumnPath;
import net.fyrql.connector.RemotePlan;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.function.FunctionRegistry;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.exec.Codegen;
import net.fyrql.exec.CodegenMode;
import net.fyrql.exec.Executor;
import net.fyrql.exec.MemoryBudget;
import net.fyrql.exec.Operator;
import net.fyrql.exec.QueryContext;
import net.fyrql.exec.Splits;
import net.fyrql.exec.TableSource;
import net.fyrql.optimizer.Optimizer;
import net.fyrql.exec.spi.CodegenStatistics;
import net.fyrql.sql.SqlException;
import net.fyrql.sql.bind.Binder;
import net.fyrql.sql.parse.SqlParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The engine: registered connectors, a catalog over them, and the
 * execution of queries. A query is SQL text, which {@link #plan(String)}
 * parses and binds into a {@link PlanNode}, or a plan built
 * programmatically, starting with {@link #scan}. The {@link #catalog()}
 * resolves names along the search path of the builder, or along one given
 * with a query, see {@link #plan(String, SearchPath)}, and binds table
 * functions such as {@code read_csv}; the scalar functions come from the
 * {@link FunctionRegistry} of the builder. With the module
 * {@code fyrql-codegen} on the class path, the engine compiles the
 * expressions, filters and keys of operators that process many rows; see
 * {@link Builder#codegen}.
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

    private final Map<String, Connector> connectors;
    private final EngineCatalog catalog;
    private final CatalogTables catalogTables;
    private final FunctionRegistry functions;
    private final Binder binder;
    private final Optimizer optimizer;
    private final Executor executor;
    private final Codegen codegen;

    private FyrQL(Map<String, Connector> connectors, SearchPath searchPath, FunctionRegistry functions,
            MemoryBudget budget, int batchSize, Codegen codegen, Optimizer.Options options, int threads,
            boolean preserveOrder) {
        this.connectors = Collections.unmodifiableMap(new LinkedHashMap<>(connectors));
        this.catalog = new EngineCatalog(this.connectors, searchPath);
        this.catalogTables = new CatalogTables(this.connectors);
        this.functions = functions;
        this.binder = new Binder(catalog, functions);
        this.optimizer = new Optimizer(this::connector, options);
        this.codegen = codegen;
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
        }, budget, batchSize, codegen, threads, preserveOrder);
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

    /** Returns the connector of a source. */
    public Connector connector(String source) {
        Connector c = connectors.get(source);
        if (c == null) {
            throw new IllegalArgumentException("No source " + source + "; registered: " + connectors.keySet());
        }
        return c;
    }

    /** Returns the names of the registered sources, in the order of their registration. */
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
     * for the time of this call.
     *
     * @throws SqlException if the query cannot be parsed or bound; the
     *         message names the position in the text
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
        EngineCatalog c = catalog.withSearchPath(searchPath);
        return (c == catalog ? binder : new Binder(c, functions)).bind(SqlParser.parse(sql));
    }

    /**
     * Runs a query; the caller closes the result.
     *
     * @throws SqlException if the query cannot be parsed or bound
     */
    public QueryResult sql(String sql) {
        return execute(plan(sql));
    }

    /**
     * Runs a query with names looked up along a search path, see
     * {@link #plan(String, SearchPath)}; the caller closes the result.
     *
     * @throws SqlException             if the query cannot be parsed or bound
     * @throws IllegalArgumentException if the default source of the path is not registered
     */
    public QueryResult sql(String sql, SearchPath searchPath) {
        return execute(plan(sql, searchPath));
    }

    /**
     * Runs a query and collects all rows as Java values.
     *
     * @throws SqlException if the query cannot be parsed or bound
     */
    public List<Object[]> rows(String sql) {
        return rows(plan(sql));
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
     *
     * @throws SqlException if the query cannot be parsed or bound
     */
    public String explain(String sql) {
        return explain(plan(sql));
    }

    /**
     * Returns the plan of a query with names looked up along a search path,
     * see {@link #plan(String, SearchPath)}, as text in the form of
     * {@link #explain(String)}.
     *
     * @throws SqlException             if the query cannot be parsed or bound
     * @throws IllegalArgumentException if the default source of the path is not registered
     */
    public String explain(String sql, SearchPath searchPath) {
        return explain(plan(sql, searchPath));
    }

    /** Returns a plan as the engine runs it, as text in the form of {@link #explain(String)}. */
    public String explain(PlanNode plan) {
        return Explain.print(optimize(plan), codegen, optimizer.estimator(), executor.threads());
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
     * {@link net.fyrql.exec.QueryCancelledException}.
     */
    public QueryResult execute(PlanNode plan, QueryContext context) {
        Operator op = executor.open(optimize(plan), optimizer.estimator(), context);
        return new QueryResult(op, context);
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
        return Splits.of(connector.splits(plan, threads), connector::open);
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

    @Override
    public void close() {
        for (Connector c : connectors.values()) {
            c.close();
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

        /** Sets the bytes a query may hold in joins, aggregations and sorts; unlimited by default. */
        public Builder memoryBudget(long bytes) {
            this.memoryBudget = bytes;
            return this;
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
         * Builds the engine.
         *
         * @throws IllegalArgumentException if the default source is not registered, or the system property
         *         {@value CodegenMode#PROPERTY}, {@value Optimizer.Options#PROPERTY} or
         *         {@value Executor#THREADS_PROPERTY} has an unknown value
         * @throws IllegalStateException    if code generation is {@code ALWAYS} without a compiler
         */
        public FyrQL build() {
            CodegenMode mode = codegenMode != null ? codegenMode : CodegenMode.fromSystemProperty();
            Codegen codegen = Codegen.of(mode, codegenThreshold, mode == CodegenMode.OFF ? null
                    : Codegen.loadCompiler());
            Optimizer.Options options = pushdown == null ? Optimizer.Options.fromSystemProperty()
                    : pushdown ? Optimizer.Options.DEFAULT : Optimizer.Options.NO_PUSHDOWN;
            String source = defaultSource != null ? defaultSource
                    : connectors.size() == 1 ? connectors.keySet().iterator().next() : null;
            return new FyrQL(connectors, new SearchPath(source, defaultSchema),
                    functions != null ? functions : FunctionRegistry.standard(), new MemoryBudget(memoryBudget),
                    batchSize, codegen, options, threads > 0 ? threads : Executor.threadsFromSystemProperty(),
                    preserveOrder);
        }
    }
}
