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

package net.fyrql.test.random;

import net.fyrql.FyrQL;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.ConversionException;
import net.fyrql.exec.CodegenMode;
import net.fyrql.exec.QueryException;
import net.fyrql.sql.SqlException;
import net.fyrql.sql.render.DuckDbDialect;
import net.fyrql.sql.render.SqlRenderer;
import net.fyrql.test.DuckDbReference;
import net.fyrql.test.Normalizer;
import net.fyrql.test.ResultComparison;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * FyrQL and DuckDB over the same {@linkplain RandomTables random tables}:
 * runs a query in both and tells how the results relate. DuckDB always
 * runs SQL text; FyrQL runs a plan, either the plan the text was rendered
 * from or the plan it binds from the text. FyrQL runs every plan twice,
 * interpreted and with all expressions and keys compiled from the first
 * row on; the two must agree exactly, or the outcome is
 * {@link Outcome.Kind#MODES_DIFFER}.
 */
final class TwoEngines implements AutoCloseable {

    /** The start of the report of rows that differ where a second run of DuckDB agrees with FyrQL. */
    static final String UNSTABLE = "DuckDB agrees on a second run; the first run differs:\n";

    private final FyrQL engine;
    private final FyrQL compiled;
    private final DuckDbReference duck = new DuckDbReference();
    private final SqlRenderer renderer = new SqlRenderer(DuckDbDialect.INSTANCE,
            t -> DuckDbDialect.INSTANCE.quoteIdentifier(t.table()));

    TwoEngines(long seed) {
        engine = RandomTables.engine(RandomTables.rows(seed));
        compiled = RandomTables.engine(RandomTables.rows(seed), CodegenMode.ALWAYS);
        RandomTables.load(engine, duck);
    }

    FyrQL engine() {
        return engine;
    }

    /** Describes what the compiler has done, for the report. */
    String codegen() {
        return "compiled code: " + compiled.codegenStatistics();
    }

    /** Returns the version of DuckDB, such as {@code v1.5.6}. */
    String duckVersion() {
        try (Statement s = duck.connection().createStatement(); ResultSet rs = s.executeQuery("SELECT version()")) {
            rs.next();
            return rs.getString(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Renders a plan as SQL over the plain table names, which both engines resolve. */
    String render(PlanNode plan) {
        return renderer.render(plan);
    }

    /**
     * Runs a plan in FyrQL and its rendering in DuckDB; if the results
     * differ, also FyrQL's binding of the rendering.
     */
    Outcome plan(PlanNode plan, boolean ordered, int ulps) {
        String sql = render(plan);
        Outcome outcome = compare(sql, () -> plan, ordered, ulps);
        return outcome.kind().differs() ? outcome.withBound(sql(sql, ordered, ulps).kind()) : outcome;
    }

    /** Runs SQL text in both engines; FyrQL parses and binds it. */
    Outcome sql(String sql, boolean ordered, int ulps) {
        return compare(sql, () -> engine.plan(sql), ordered, ulps);
    }

    private Outcome compare(String sql, Supplier<PlanNode> fyrql, boolean ordered, int ulps) {
        Schema schema = null;
        List<Object[]> actual = null;
        String fyrqlError = null;
        boolean crash = false;
        PlanNode plan = null;
        try {
            plan = fyrql.get();
            schema = plan.schema();
            actual = engine.rows(plan);
        } catch (RuntimeException | StackOverflowError e) {
            fyrqlError = e.getClass().getSimpleName() + ": " + e.getMessage();
            crash = !(e instanceof QueryException || e instanceof ConversionException || e instanceof SqlException);
        }
        if (plan != null) {
            Outcome modes = modes(sql, plan, actual, fyrqlError);
            if (modes != null) {
                return modes;
            }
        }
        PlanNode optimized = null;
        if (plan != null) {
            try {
                optimized = engine.optimize(plan);
            } catch (RuntimeException e) {
                // The optimizer itself failed; the error names no node.
            }
        }
        Outcome outcome = compare(sql, schema, actual, fyrqlError, ordered, ulps).withPlan(plan, optimized);
        if (crash) {
            return new Outcome(Outcome.Kind.FYRQL_CRASHES, sql, fyrqlError, outcome.duckError(), "")
                    .withPlan(plan, optimized);
        }
        if (outcome.kind() == Outcome.Kind.FYRQL_FAILS || outcome.kind() == Outcome.Kind.DUCKDB_FAILS) {
            duck.execute("PRAGMA disable_optimizer");
            try {
                return outcome.withUnoptimized(compare(sql, schema, actual, fyrqlError, ordered, ulps).kind());
            } finally {
                duck.execute("PRAGMA enable_optimizer");
            }
        }
        return outcome;
    }

    /**
     * Runs a plan with compiled code and compares it with the interpreted
     * run: both fail, or both give the same rows in the same order, bit for
     * bit up to the sign of zero and the kind of NaN. Returns
     * {@code null} if they agree.
     */
    private Outcome modes(String sql, PlanNode plan, List<Object[]> interpreted, String interpretedError) {
        List<Object[]> rows = null;
        String error = null;
        boolean crash = false;
        try {
            rows = compiled.rows(plan);
        } catch (RuntimeException | StackOverflowError e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
            crash = !(e instanceof QueryException || e instanceof ConversionException || e instanceof SqlException);
        }
        String report;
        if (crash) {
            report = "compiled code crashes: " + error;
        } else if ((error == null) != (interpretedError == null)) {
            report = "interpreted: " + (interpretedError == null ? "runs" : interpretedError) + "\ncompiled: "
                    + (error == null ? "runs" : error);
        } else if (error != null) {
            return null;
        } else {
            ResultComparison.Outcome same = ResultComparison.compare(plan.schema(), interpreted, rows, true, 0);
            if (same.same()) {
                return null;
            }
            report = "interpreted (expected) against compiled:\n" + same.report();
        }
        return new Outcome(Outcome.Kind.MODES_DIFFER, sql, null, null, report).withPlan(plan, null);
    }

    private Outcome compare(String sql, Schema schema, List<Object[]> actual, String fyrqlError, boolean ordered,
            int ulps) {
        DuckResult expected;
        try {
            expected = duck(sql, schema);
        } catch (SQLException e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage().strip();
            return new Outcome(fyrqlError != null ? Outcome.Kind.BOTH_FAIL : Outcome.Kind.DUCKDB_FAILS, sql, fyrqlError,
                    message, "");
        }
        if (fyrqlError != null) {
            return new Outcome(Outcome.Kind.FYRQL_FAILS, sql, fyrqlError, null, "");
        }
        List<String> types = schema.types().stream().map(DataType::sqlName).toList();
        if (!types.equals(expected.types())) {
            return new Outcome(Outcome.Kind.TYPES_DIFFER, sql, null, null,
                    "types: DuckDB " + expected.types() + ", FyrQL " + types);
        }
        ResultComparison.Outcome rows = ResultComparison.compare(schema, expected.rows(), actual, ordered, ulps);
        if (rows.same()) {
            return new Outcome(Outcome.Kind.SAME, sql, null, null, "");
        }
        // DuckDB may give other rows on another run of the same query.
        try {
            if (ResultComparison.compare(schema, duck(sql, schema).rows(), actual, ordered, ulps).same()) {
                return new Outcome(Outcome.Kind.ROWS_DIFFER, sql, null, null, UNSTABLE + rows.report());
            }
        } catch (SQLException e) {
            // The difference stays as the first run shows it.
        }
        return new Outcome(Outcome.Kind.ROWS_DIFFER, sql, null, null, rows.report());
    }

    /** The column types and rows of DuckDB; the rows are FyrQL values where FyrQL's types agree. */
    private record DuckResult(List<String> types, List<Object[]> rows) {
    }

    private DuckResult duck(String sql, Schema schema) throws SQLException {
        try (Statement s = duck.connection().createStatement(); ResultSet rs = s.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            List<String> types = new ArrayList<>();
            for (int c = 1; c <= n; c++) {
                types.add(md.getColumnTypeName(c));
            }
            boolean typed = schema != null && schema.types().stream().map(DataType::sqlName).toList().equals(types);
            List<Object[]> rows = new ArrayList<>();
            while (rs.next()) {
                Object[] row = new Object[n];
                for (int c = 0; c < n; c++) {
                    if (typed) {
                        DataType type = schema.type(c);
                        // A java.sql.Timestamp loses the era of a year before 1 AD, a LocalDateTime keeps it.
                        Object value = type == DataType.TIMESTAMP ? rs.getObject(c + 1, LocalDateTime.class)
                                : rs.getObject(c + 1);
                        row[c] = Normalizer.normalize(value, type);
                    } else {
                        row[c] = rs.getObject(c + 1);
                    }
                }
                rows.add(row);
            }
            return new DuckResult(types, rows);
        }
    }

    @Override
    public void close() {
        duck.close();
        engine.close();
        compiled.close();
    }
}
