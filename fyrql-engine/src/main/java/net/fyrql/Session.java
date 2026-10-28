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
import net.fyrql.catalog.DatabasePath;
import net.fyrql.catalog.EngineCatalog;
import net.fyrql.catalog.SearchPath;
import net.fyrql.connector.Connector;
import net.fyrql.core.function.FunctionRegistry;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.value.ZonedDates;
import net.fyrql.exec.QueryContext;
import net.fyrql.sql.SqlException;
import net.fyrql.sql.Position;
import net.fyrql.sql.ast.ResetVariable;
import net.fyrql.sql.ast.SetVariable;
import net.fyrql.sql.bind.Binder;
import net.fyrql.sql.bind.BoundStatement;
import net.fyrql.sql.bind.Settings;
import net.fyrql.sql.parse.SqlParser;

import java.time.Duration;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A session of an engine: the settings that {@code SET}, {@code RESET},
 * {@code SHOW}, {@code USE} and {@code current_setting} read and change,
 * and the methods of the engine that parse, bind and run statements with
 * them. The settings are
 * <ul>
 * <li>{@code TimeZone}, the time zone in which values of
 * {@code TIMESTAMP WITH TIME ZONE} are shown and their calendar computes,
 * see {@link #timeZone()};</li>
 * <li>{@code search_path}, the default source and schema, in which names
 * of fewer than three parts are looked up first, see {@link #searchPath()};
 * {@code USE source[.schema]} sets it, and a value of one part names a
 * source, or else a schema of the default source;</li>
 * <li>{@code statement_timeout}, the timeout of a statement that asks for
 * none, see {@link #queryTimeout()}.</li>
 * </ul>
 * A session starts with the settings of the builder of the engine, see
 * {@link FyrQL.Builder#timeZone}, {@link FyrQL.Builder#defaultSource},
 * {@link FyrQL.Builder#defaultSchema} and {@link FyrQL.Builder#queryTimeout},
 * and {@code RESET} returns to them. The memory budget, the threads, code
 * generation, pushdown and the access to files belong to the engine.
 * <p>
 * The engine has a session of its own, see {@link FyrQL#session()}, in
 * which its methods without a session run; {@link FyrQL#newSession()}
 * starts another one, e.g. one per connection of a client. A session holds
 * nothing to close, and it may be used from several threads; a change of
 * a setting applies to the statements bound after it.
 *
 * <pre>{@code
 * Session session = engine.newSession();
 * session.update("SET TimeZone = 'Europe/Berlin'");
 * List<Object[]> rows = session.rows("SELECT now()");
 * OffsetDateTime now = (OffsetDateTime) rows.get(0)[0]; // with the offset of Berlin
 * }</pre>
 */
public final class Session {

    private final FyrQL engine;
    private volatile ZoneId zone;
    /** The timeout of a statement that asks for none, {@code null} for none. */
    private volatile Duration timeout;
    private volatile Names names;
    /** The sources that only this session sees, after those of the engine; see {@link Overlay}. */
    private final Map<String, Connector> overlay;
    private final FunctionRegistry functions;
    private final FyrQL.Execution execution;

    /** The catalog of the search path of the session and the binder over it. */
    private record Names(EngineCatalog catalog, Binder binder) {
    }

    /**
     * The session of an engine.
     *
     * @param catalog the catalog of the engine, which adds the time zone of
     *        this session to the options of files
     */
    Session(FyrQL engine, ZoneId zone, Duration timeout, EngineCatalog catalog) {
        this.engine = engine;
        this.zone = zone;
        this.timeout = timeout;
        Connector temporary = engine.temporaryTables();
        this.overlay = temporary == null ? Map.of() : Map.of(FyrQL.TEMPORARY, temporary);
        this.functions = engine.functions();
        this.execution = temporary == null ? engine.execution() : engine.execution(this::connector);
        this.names = names(temporary == null ? catalog : catalog.withTemporary(FyrQL.TEMPORARY, temporary));
    }

    /** A session of an engine with a search path, see {@link FyrQL#newSession()}. */
    Session(FyrQL engine, ZoneId zone, Duration timeout, SearchPath searchPath) {
        this(engine, zone, timeout, searchPath, Map.of(), engine.functions());
    }

    /**
     * A session of an engine with a search path, more sources and its own
     * functions, see {@link Overlay#newSession}.
     */
    Session(FyrQL engine, ZoneId zone, Duration timeout, SearchPath searchPath, Map<String, Connector> overlay,
            FunctionRegistry functions) {
        this.engine = engine;
        this.zone = zone;
        this.timeout = timeout;
        Connector temporary = overlay.containsKey(FyrQL.TEMPORARY) ? null : engine.temporaryTables();
        Map<String, Connector> all = new LinkedHashMap<>(overlay);
        if (temporary != null) {
            all.put(FyrQL.TEMPORARY, temporary);
        }
        this.overlay = Collections.unmodifiableMap(all);
        this.functions = functions;
        this.execution = this.overlay.isEmpty() ? engine.execution() : engine.execution(this::connector);
        EngineCatalog catalog = engine.catalog().withSession(searchPath, this::timeZone);
        catalog = overlay.isEmpty() ? catalog : catalog.withSources(overlay);
        this.names = names(temporary == null ? catalog : catalog.withTemporary(FyrQL.TEMPORARY, temporary));
    }

    private Names names(EngineCatalog catalog) {
        SearchPath path = catalog.searchPath();
        return new Names(catalog, new Binder(catalog, functions, this::timeZone, name -> value(name, path)));
    }

    /**
     * Returns the connector of a source of the engine or of this session.
     *
     * @throws IllegalArgumentException if there is no such source
     */
    Connector connector(String source) {
        Connector c = overlay.get(source);
        return c != null ? c : engine.connector(source);
    }

    /** Returns the optimizer and the executor over the sources of this session. */
    FyrQL.Execution execution() {
        return execution;
    }

    /**
     * Returns the sources, schemas, tables and columns that the session
     * sees, for the metadata of tools: those of the engine, see
     * {@link FyrQL#catalogTables()}, and the temporary tables of the
     * session in the source {@code temp}, once there are some.
     */
    public CatalogTables catalogTables() {
        return names.catalog().catalogTablesOfSession();
    }

    /** Returns the engine of the session. */
    public FyrQL engine() {
        return engine;
    }

    /**
     * Returns the time zone, in which values of {@code TIMESTAMP WITH TIME ZONE}
     * are shown and their calendar computes: that of the builder of the
     * engine, until {@code SET TimeZone} changes it.
     */
    public ZoneId timeZone() {
        return zone;
    }

    /**
     * Returns the search path, along which names of fewer than three parts
     * are looked up: that of the builder of the engine, until
     * {@code SET search_path}, {@code USE} or {@link #use(SearchPath)}
     * changes it.
     */
    public SearchPath searchPath() {
        return names.catalog().searchPath();
    }

    /**
     * Returns the timeout of a statement that asks for none: that of the
     * builder of the engine, until {@code SET statement_timeout} changes it;
     * nothing for none. It is at most {@link FyrQL#maxQueryTimeout()} when a
     * statement runs.
     */
    public Optional<Duration> queryTimeout() {
        return Optional.ofNullable(timeout);
    }

    /** Returns the timeout of a statement that asks for none, {@code null} for none. */
    Duration timeout() {
        return timeout;
    }

    /** Returns the catalog of the session, which adds its time zone to the options of files. */
    EngineCatalog catalog() {
        return names.catalog();
    }

    /**
     * Returns the value of a setting as {@code SHOW} shows it, e.g.
     * {@code Europe/Berlin}, {@code memory.main} or {@code 30s}.
     *
     * @param name the name in any case, see {@link Settings#NAMES}
     * @throws IllegalArgumentException if there is no setting of the name
     */
    public String setting(String name) {
        String setting = Settings.name(name);
        if (setting == null) {
            throw new IllegalArgumentException("unrecognized configuration parameter \"" + name + "\"");
        }
        return value(setting, searchPath());
    }

    /** Returns the settings by name as {@code SHOW ALL} shows them, in its order. */
    public Map<String, String> settings() {
        Map<String, String> settings = new LinkedHashMap<>();
        for (String name : Settings.NAMES) {
            settings.put(name, setting(name));
        }
        return settings;
    }

    /** Returns the value of a setting as SHOW shows it, with a search path. */
    private String value(String name, SearchPath path) {
        return switch (name) {
            case Settings.TIME_ZONE -> ZonedDates.name(zone);
            case Settings.SEARCH_PATH -> path.defaultSource() == null ? ""
                    : Settings.searchPathText(List.of(path.defaultSource(), path.defaultSchema()));
            default -> Settings.timeoutText(timeout == null ? Duration.ZERO : timeout);
        };
    }

    /**
     * Sets a setting as {@code SET name = 'value'} does.
     *
     * @throws SqlException if there is no setting of the name, or the value does not fit it
     */
    public void set(String name, String value) {
        apply((BoundStatement.Set) binder().bind(new SetVariable(name, value, Position.UNKNOWN)));
    }

    /**
     * Returns a setting to the value of the builder of the engine, as
     * {@code RESET name} does; {@code ALL} resets every setting.
     *
     * @throws SqlException if there is no setting of the name
     */
    public void reset(String name) {
        apply((BoundStatement.Set) binder().bind(new ResetVariable(name, Position.UNKNOWN)));
    }

    /**
     * Sets the search path, along which names of fewer than three parts are
     * looked up; the table functions of its default source come first.
     *
     * @throws IllegalArgumentException if the default source of the path is not registered
     */
    public void use(SearchPath searchPath) {
        Objects.requireNonNull(searchPath, "searchPath");
        Names current = names;
        if (!searchPath.equals(current.catalog().searchPath())) {
            names = names(current.catalog().withSearchPath(searchPath));
        }
    }

    /**
     * Resolves names as in PostgreSQL along the path of a database, or as in
     * FyrQL for {@code null}, see {@link Overlay#use}. The search path goes
     * to the database and the schema that new tables of one part go to, if
     * the database has one, so that the table functions of the database
     * come first.
     *
     * @throws IllegalArgumentException if the database or a catalog of the path is not registered
     */
    void use(DatabasePath path) {
        EngineCatalog catalog = names.catalog().withDatabasePath(path);
        if (path != null) {
            Optional<String> schema = catalog.creationSchema();
            if (schema.isPresent()) {
                catalog = catalog.withSearchPath(new SearchPath(path.database(), schema.get()));
            }
        }
        names = names(catalog);
    }

    /**
     * Applies {@code SET} or {@code RESET} of a setting.
     *
     * @throws SqlException if the source or schema of a search path does not exist
     */
    void apply(BoundStatement.Set setting) {
        String value = setting.value();
        switch (setting.name()) {
            case Settings.ALL -> {
                for (String name : Settings.NAMES) {
                    apply(new BoundStatement.Set(name, null));
                }
            }
            case Settings.TIME_ZONE -> zone = value == null ? engine.initialZone() : ZonedDates.zone(value);
            case Settings.SEARCH_PATH -> use(value == null ? engine.catalog().searchPath() : searchPath(value));
            case Settings.STATEMENT_TIMEOUT -> {
                Duration t = value == null ? engine.queryTimeout().orElse(null) : Settings.timeout(value);
                timeout = t == null || t.isZero() ? null : t;
            }
            default -> throw new IllegalArgumentException("No setting " + setting.name());
        }
    }

    /**
     * Returns the search path of a value of {@code search_path}: the source
     * and the schema match exactly, or else ignoring case if that is unique.
     * A value of one part is a source, with the schema {@code main}, or else
     * a schema of the default source, as in {@code USE} of DuckDB and as
     * pgjdbc sets a schema; an empty value is no default source.
     */
    private SearchPath searchPath(String value) {
        List<String> parts = Settings.searchPath(value);
        if (parts.isEmpty()) {
            return new SearchPath(null, TableRef.MAIN);
        }
        List<String> sources = engine.sources();
        String source = match(sources, parts.get(0));
        if (parts.size() == 1) {
            if (source != null) {
                return new SearchPath(source, TableRef.MAIN);
            }
            String current = searchPath().defaultSource();
            List<String> schemas = current == null ? List.of() : connector(current).catalog().schemas();
            String schema = match(schemas, parts.get(0));
            if (schema != null) {
                return new SearchPath(current, schema);
            }
            throw new SqlException("No source or schema " + parts.get(0) + "; the sources are "
                    + String.join(", ", sources) + (current == null ? ""
                    : ", the schemas of " + current + " are " + String.join(", ", schemas)), Position.UNKNOWN);
        }
        if (source == null) {
            throw new SqlException("No source " + parts.get(0) + "; there are " + String.join(", ", sources),
                    Position.UNKNOWN);
        }
        List<String> schemas = connector(source).catalog().schemas();
        String schema = match(schemas, parts.get(1));
        if (schema == null) {
            throw new SqlException("No schema " + parts.get(1) + " in the source " + source + "; there are "
                    + String.join(", ", schemas), Position.UNKNOWN);
        }
        return new SearchPath(source, schema);
    }

    /**
     * Returns the name that matches exactly, or else the only one that
     * matches ignoring case; {@code null} if there is none or more than one.
     */
    private static String match(List<String> names, String name) {
        if (names.contains(name)) {
            return name;
        }
        String match = null;
        for (String n : names) {
            if (n.equalsIgnoreCase(name)) {
                if (match != null) {
                    return null;
                }
                match = n;
            }
        }
        return match;
    }

    /** Returns the binder of the session. */
    Binder binder() {
        return names.binder();
    }

    /**
     * Returns a binder of the session with another search path.
     *
     * @throws IllegalArgumentException if the default source of the path is not registered
     */
    Binder binder(SearchPath searchPath) {
        Names current = names;
        if (searchPath.equals(current.catalog().searchPath())) {
            return current.binder();
        }
        return new Binder(current.catalog().withSearchPath(searchPath), functions, this::timeZone,
                name -> value(name, searchPath));
    }

    /**
     * Parses and binds a query in this session, see {@link FyrQL#plan(String)}.
     *
     * @throws SqlException if the query cannot be parsed or bound, or is no query
     */
    public PlanNode plan(String sql) {
        return binder().bind(SqlParser.parse(sql));
    }

    /**
     * Parses and binds a statement in this session, see {@link FyrQL#statement(String)}.
     *
     * @throws SqlException if the statement cannot be parsed or bound
     */
    public SqlStatement statement(String sql) {
        return new SqlStatement(bind(sql), sql);
    }

    /** Parses and binds a statement in this session. */
    BoundStatement bind(String sql) {
        return binder().bind(SqlParser.parseStatement(sql));
    }

    /**
     * Parses and binds a statement in this session with names looked up
     * along another search path, e.g. the one a prepared query kept; a
     * table to create goes to the default source and schema of the path if
     * its name does not say otherwise.
     *
     * @throws SqlException             if the statement cannot be parsed or bound
     * @throws IllegalArgumentException if the default source of the path is not registered
     */
    public SqlStatement statement(String sql, SearchPath searchPath) {
        return new SqlStatement(binder(searchPath).bind(SqlParser.parseStatement(sql)), sql);
    }

    /**
     * Prepares a query with parameters in this session, see
     * {@link FyrQL#prepare(String)}; it keeps the search path of the session
     * at this call.
     *
     * @throws SqlException if the query cannot be parsed or bound, or a parameter has two types
     */
    public PreparedQuery prepare(String sql) {
        return engine.prepare(sql, searchPath(), binder());
    }

    /**
     * Binds a prepared query with values for its parameters in this session,
     * see {@link FyrQL#plan(PreparedQuery, List)}.
     *
     * @throws IllegalArgumentException if the values do not fit the parameters
     * @throws SqlException             if the query does not bind any more, or is no query
     */
    public PlanNode plan(PreparedQuery query, List<?> values) {
        return FyrQL.conform(binder(query.searchPath()).bind(SqlParser.parse(FyrQL.render(query, values))),
                query.schema());
    }

    /**
     * Binds a prepared statement with values for its parameters in this
     * session, see {@link FyrQL#statement(PreparedQuery, List)}.
     *
     * @throws IllegalArgumentException if the values do not fit the parameters
     * @throws SqlException             if the statement does not bind any more
     */
    public SqlStatement statement(PreparedQuery query, List<?> values) {
        return new SqlStatement(bind(query, values), null);
    }

    /** Binds a prepared statement with values for its parameters in this session. */
    private BoundStatement bind(PreparedQuery query, List<?> values) {
        BoundStatement statement = binder(query.searchPath()).bind(SqlParser.parseStatement(
                FyrQL.render(query, values)));
        return statement instanceof BoundStatement.Query q
                ? new BoundStatement.Query(FyrQL.conform(q.plan(), query.schema())) : statement;
    }

    /**
     * Runs a prepared statement with values for its parameters in this
     * session; the caller closes the result.
     *
     * @throws IllegalArgumentException if the values do not fit the parameters
     * @throws SqlException             if the statement does not bind any more, or a write fails
     */
    public QueryResult execute(PreparedQuery query, List<?> values) {
        return engine.execute(bind(query, values), new QueryContext(), null, this);
    }

    /**
     * Runs a prepared statement with values for its parameters in this
     * session, in a context of the caller; the caller closes the result.
     *
     * @throws IllegalArgumentException if the values do not fit the parameters
     * @throws SqlException             if the statement does not bind any more, or a write fails
     */
    public QueryResult execute(PreparedQuery query, List<?> values, QueryContext context) {
        return engine.execute(bind(query, values), context, null, this);
    }

    /**
     * Runs a prepared query with values for its parameters in this session
     * and collects all rows as Java values.
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
     * in this session and returns the number of rows written.
     *
     * @throws IllegalArgumentException if the values do not fit the parameters
     * @throws SqlException             if the statement does not bind any more, is a query, or fails
     */
    public long update(PreparedQuery query, List<?> values) {
        return engine.update(bind(query, values), new QueryContext(), null, this);
    }

    /**
     * Runs a statement in this session, see {@link FyrQL#sql(String)}; the
     * caller closes the result.
     *
     * @throws SqlException if the statement cannot be parsed or bound, or a write fails
     */
    public QueryResult sql(String sql) {
        return engine.execute(bind(sql), new QueryContext(), sql, this);
    }

    /**
     * Runs a statement in this session and collects all rows as Java values.
     *
     * @throws SqlException if the statement cannot be parsed or bound, or a write fails
     */
    public List<Object[]> rows(String sql) {
        try (QueryResult result = sql(sql)) {
            return result.rows();
        }
    }

    /**
     * Runs a statement that writes, or one of the settings, in this session
     * and returns the number of rows written, see {@link FyrQL#update(String)}.
     *
     * @throws SqlException if the statement cannot be parsed or bound, is a query, or fails
     */
    public long update(String sql) {
        return engine.update(bind(sql), new QueryContext(), sql, this);
    }

    /**
     * Runs a bound statement in this session, see
     * {@link FyrQL#execute(SqlStatement, QueryContext)}; {@code SET}
     * changes this session. The caller closes the result.
     *
     * @throws SqlException if the write fails
     */
    public QueryResult execute(SqlStatement statement, QueryContext context) {
        return engine.execute(statement.bound(), context, statement.sql(), this);
    }

    /**
     * Runs a bound statement that writes in this session, see
     * {@link FyrQL#update(SqlStatement, QueryContext)}.
     *
     * @throws SqlException if the statement is a query or {@code EXPLAIN}, or the write fails
     */
    public long update(SqlStatement statement, QueryContext context) {
        return engine.update(statement.bound(), context, statement.sql(), this);
    }

    /**
     * Runs a plan with the timeout of this session, see
     * {@link FyrQL#execute(PlanNode, QueryContext)}; the caller closes the result.
     */
    public QueryResult execute(PlanNode plan, QueryContext context) {
        return engine.execute(plan, context, null, this);
    }

    /**
     * Returns the plan of a statement bound in this session, see {@link FyrQL#explain(String)}.
     *
     * @throws SqlException if the statement cannot be parsed or bound
     */
    public String explain(String sql) {
        return engine.explain(bind(sql), this);
    }

    /**
     * Runs a statement in this session and returns its plan with what each
     * operator did, see {@link FyrQL#explainAnalyze(String)}.
     *
     * @throws SqlException if the statement cannot be parsed or bound, or fails
     */
    public String explainAnalyze(String sql) {
        return engine.explainAnalyze(bind(sql), new QueryContext(), this);
    }

    /**
     * Runs a bound statement in this session in a context of the caller and
     * returns its plan with what each operator did, see
     * {@link FyrQL#explainAnalyze(SqlStatement, QueryContext)}.
     *
     * @throws SqlException if the statement fails
     */
    public String explainAnalyze(SqlStatement statement, QueryContext context) {
        return engine.explainAnalyze(statement.bound(), context, this);
    }
}
