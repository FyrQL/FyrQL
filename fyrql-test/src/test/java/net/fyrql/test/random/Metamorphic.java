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

package net.fyrql.test.random;

import net.fyrql.FyrQL;
import net.fyrql.QueryResult;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.ConversionException;
import net.fyrql.core.value.Interval;
import net.fyrql.core.vector.Batch;
import net.fyrql.exec.QueryException;
import net.fyrql.sql.SqlException;
import net.fyrql.sql.ast.FromItem;
import net.fyrql.sql.Position;
import net.fyrql.sql.ast.Query;
import net.fyrql.sql.ast.QueryBody;
import net.fyrql.sql.ast.SelectItem;
import net.fyrql.sql.ast.SqlExpr;
import net.fyrql.sql.ast.SqlPrinter;
import net.fyrql.test.ResultComparison;
import net.fyrql.test.Rows;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The metamorphic checks after SQLancer, with FyrQL as its own oracle: a
 * query and a form of it that must give the same rows run in the same
 * engine. Ternary logic partitioning (TLP) splits a query by a condition
 * {@code p} into the parts where {@code p} is true, false and
 * {@code NULL}, whose rows together are those of the query: in
 * {@code WHERE}, with {@code DISTINCT} and {@code UNION}, in
 * {@code HAVING}, or in {@code WHERE} under aggregates that combine (see
 * {@link SqlGenerator.Kind}). NoREC counts the rows of a {@code FROM} that a
 * condition selects twice: as a filter, which the optimizer pushes down
 * into sources, splits and compiles, and as an expression of a
 * {@code CASE} over every row. The check of views runs a query directly and
 * through a view of it, which binds it again where it is read.
 * <p>
 * A check is skipped, and counted apart, where a query gives more than
 * {@link #MAX_ROWS} rows, such as a cross join of four tables, whose rows
 * the checks would hold several times over.
 * <p>
 * A check is skipped where either query fails as a query may, with an
 * {@link SqlException}, a {@link QueryException} or a
 * {@link ConversionException}: the engine evaluates a condition for every
 * row, without short circuit, so a part may fail on a value the query never
 * meets, and a join over the budget fails as it does not spill. Any other
 * error is a crash. A difference or a crash not on the list of
 * {@link #KNOWN} ones is unexplained; the first few are shrunk for the
 * report.
 * <p>
 * Intervals are equal by their normalized form, so that {@code DISTINCT},
 * {@code UNION} and grouping take {@code 1 month} and {@code 30 days} as
 * one value and keep either of them, whichever comes first; outside the
 * form {@link SqlGenerator.Kind#WHERE}, the rows are compared with their
 * intervals normalized.
 */
final class Metamorphic {

    /** A difference that is known and not fixed yet: a name, what it is, and how to recognize its queries. */
    record Known(String name, String description, Predicate<String> matches) {
    }

    /** The known differences, none at present. */
    static final List<Known> KNOWN = List.of();

    /**
     * How a check ended; a query over the budget of the engine, or with more
     * than {@link #MAX_ROWS} rows, is skipped too, but counted apart.
     */
    enum Result {
        SAME, SKIPPED, OUT_OF_BUDGET, TOO_LARGE, DIFFERS, CRASHES
    }

    /** The most rows a query of a check may give. */
    static final int MAX_ROWS = 1_000_000;

    /**
     * The end of a check: how it ended, the queries it ran, what differs or
     * the error, and whether the query gave no rows, which makes it weak.
     */
    record Check(Result result, List<String> sql, String report, boolean empty) {
    }

    private static final Position P = Position.UNKNOWN;
    /** The name of the view of the check of views, in the first schema of the first source of the engine. */
    static final String VIEW = "metamorphic_view";
    private static final int SHRUNK = Integer.getInteger("fyrql.random.shrunk", 5);
    /** The share of skipped checks, in a hundred, above which the report warns. */
    private static final int SKIPPED_WARNING = 25;

    private final String title;
    private final FyrQL engine;
    private final Map<String, Map<Result, Integer>> results = new TreeMap<>();
    private final Map<String, Integer> empty = new TreeMap<>();
    private final Map<String, Integer> errors = new TreeMap<>();
    private final Map<String, String> examples = new TreeMap<>();
    private final Map<String, Integer> known = new TreeMap<>();
    private final List<String> unexplained = new ArrayList<>();
    private int queries;
    private int checks;
    private int skipped;
    /** The qualified name of the view of the check of views, once it is known. */
    private String view;

    /**
     * @param title names the source and the configuration in the report
     * @param engine runs the queries
     */
    Metamorphic(String title, FyrQL engine) {
        this.title = title;
        this.engine = engine;
    }

    /**
     * The first 50 rows of each table, for the samples of the {@link SqlGenerator}, in the form of vectors, of
     * which it writes literals.
     */
    static Map<String, List<Object[]>> samples(FyrQL engine, Map<String, Schema> tables) {
        Map<String, List<Object[]>> rows = new LinkedHashMap<>();
        for (Map.Entry<String, Schema> table : tables.entrySet()) {
            rows.put(table.getKey(), Rows.internal(table.getValue(),
                    engine.rows("SELECT * FROM " + table.getKey() + " LIMIT 50")));
        }
        return rows;
    }

    // ------------------------------------------------------------ the forms

    private static SqlExpr and(SqlExpr left, SqlExpr right) {
        return left == null ? right : new SqlExpr.Binary(SqlExpr.BinaryOp.AND, left, right, P);
    }

    private static SqlExpr.FunctionCall call(String name, SqlExpr argument) {
        return new SqlExpr.FunctionCall(name, List.of(argument), false, false, List.of(), P);
    }

    private static SqlExpr number(int n) {
        return new SqlExpr.Literal(SqlExpr.LiteralKind.NUMBER, Integer.toString(n), P);
    }

    /** The three conditions of a partition: {@code p}, {@code NOT p} and {@code p IS NULL}. */
    private static List<SqlExpr> parts(SqlExpr p) {
        return List.of(p, new SqlExpr.Unary(SqlExpr.UnaryOp.NOT, p, P), new SqlExpr.IsNull(p, false, P));
    }

    /** A {@code SELECT} with another {@code WHERE} and {@code HAVING}. */
    private static QueryBody.Select with(QueryBody.Select s, SqlExpr where, SqlExpr having) {
        return new QueryBody.Select(s.distinct(), s.items(), s.from(), where, s.groupBy(), having, s.windows(),
                s.qualify(), s.distinctOn(), s.position());
    }

    /** A {@code SELECT} over an inner join whose condition takes a part of a partition as well. */
    private static QueryBody.Select on(QueryBody.Select s, SqlExpr part) {
        FromItem.Join j = (FromItem.Join) s.from();
        FromItem join = new FromItem.Join(j.kind(), j.left(), j.right(), and(j.on(), part), j.using(), j.natural(),
                j.position());
        return new QueryBody.Select(s.distinct(), s.items(), join, s.where(), s.groupBy(), s.having(), s.windows(),
                s.qualify(), s.distinctOn(), s.position());
    }

    /** The union of three bodies, without duplicates or with them. */
    private static QueryBody union(List<QueryBody.Select> parts, boolean all) {
        QueryBody body = parts.get(0);
        for (int i = 1; i < parts.size(); i++) {
            body = new QueryBody.SetOperation(QueryBody.SetKind.UNION, all, body, parts.get(i));
        }
        return body;
    }

    /**
     * The query of a partition and its partitioned form, as SQL.
     *
     * @param schema the columns of the query, whose types the aggregates of {@link SqlGenerator.Kind#AGGREGATE}
     *        are cast back to
     */
    static List<String> tlpQueries(SqlGenerator.Partition p, Schema schema) {
        QueryBody.Select base = p.base();
        List<QueryBody.Select> parts = new ArrayList<>();
        for (SqlExpr part : parts(p.predicate())) {
            parts.add(switch (p.kind()) {
                case HAVING -> with(base, base.where(), and(base.having(), part));
                case JOIN_ON -> on(base, part);
                default -> with(base, and(base.where(), part), base.having());
            });
        }
        QueryBody partitioned = switch (p.kind()) {
            case WHERE, HAVING, JOIN_ON, WINDOW -> union(parts, true);
            case DISTINCT -> union(parts, false);
            case AGGREGATE -> combined(base, union(parts, true), schema);
        };
        return List.of(SqlPrinter.print(p.query()), SqlPrinter.print(new Query(p.with(), partitioned, List.of(), null,
                null)));
    }

    /**
     * Aggregates the aggregates of the parts again by the groups: counts and
     * sums are added up, the others aggregated as they were, each cast to
     * its type in the query.
     */
    private static QueryBody combined(QueryBody.Select base, QueryBody parts, Schema schema) {
        List<SelectItem> items = new ArrayList<>();
        List<SqlExpr> groups = new ArrayList<>();
        long named = base.items().stream().filter(item -> ((SelectItem.ExpressionItem) item).alias().startsWith("g"))
                .count();
        QueryBody.GroupBy by = base.groupBy();
        if (by == null ? named > 0 : !by.all() && by.expressions().size() != named) {
            // A shrunk query that left out a group: its aggregates do not combine by the groups that are left.
            throw new IllegalArgumentException("Not every group is in the select list");
        }
        for (int i = 0; i < base.items().size(); i++) {
            SelectItem.ExpressionItem item = (SelectItem.ExpressionItem) base.items().get(i);
            SqlExpr name = new SqlExpr.Identifier(List.of(item.alias()), P);
            if (item.alias().startsWith("g")) {
                items.add(new SelectItem.ExpressionItem(name, item.alias()));
                groups.add(name);
                continue;
            }
            String f = ((SqlExpr.FunctionCall) item.expression()).name();
            String again = switch (f) {
                case "count", "sum" -> "sum";
                case "min", "max", "bool_and", "bool_or" -> f;
                default -> throw new IllegalArgumentException("Not an aggregate that combines: " + f);
            };
            items.add(new SelectItem.ExpressionItem(new SqlExpr.Cast(call(again, name), schema.type(i), false, P),
                    item.alias()));
        }
        FromItem from = new FromItem.Subquery(Query.of(parts), new FromItem.Alias("u", List.of()), P);
        return new QueryBody.Select(false, items, from, null, groups.isEmpty() ? null
                : new QueryBody.GroupBy(false, groups), null, P);
    }

    /**
     * The two queries of NoREC over the {@code FROM} of a partition and its
     * condition over rows: a count of the rows it selects, and a sum of 1 for
     * each row where it holds. The {@code WHERE} of the partition stays out,
     * as it often selects no rows together with the condition, and its
     * subqueries cannot go into a {@code CASE}.
     */
    static List<String> norecQueries(SqlGenerator.Partition p) {
        SqlExpr condition = p.rows();
        SelectItem count = new SelectItem.ExpressionItem(new SqlExpr.FunctionCall("count", List.of(), false, true,
                List.of(), P), null);
        SqlExpr ones = new SqlExpr.Case(null, List.of(new SqlExpr.When(condition, number(1))), number(0), P);
        SelectItem sum = new SelectItem.ExpressionItem(new SqlExpr.FunctionCall("coalesce", List.of(call("sum", ones),
                number(0)), false, false, List.of(), P), null);
        QueryBody.Select filtered = new QueryBody.Select(false, List.of(count), p.base().from(), condition, null, null,
                P);
        QueryBody.Select evaluated = new QueryBody.Select(false, List.of(sum), p.base().from(), null, null, null, P);
        return List.of(SqlPrinter.print(new Query(p.with(), filtered, List.of(), null, null)),
                SqlPrinter.print(new Query(p.with(), evaluated, List.of(), null, null)));
    }

    /**
     * The queries of the check of views: the query of a partition without
     * its order and limit, a view of it with the columns {@code c1},
     * {@code c2} and so on, and a query of all rows of the view.
     *
     * @param columns the number of columns of the query
     * @param view the qualified name of the view
     */
    static List<String> viewQueries(SqlGenerator.Partition p, int columns, String view) {
        String query = SqlPrinter.print(new Query(p.with(), p.base(), List.of(), null, null));
        List<String> names = new ArrayList<>();
        for (int i = 1; i <= columns; i++) {
            names.add("c" + i);
        }
        return List.of(query, "CREATE OR REPLACE VIEW " + view + " (" + String.join(", ", names) + ") AS " + query,
                "SELECT * FROM " + view);
    }

    // -------------------------------------------------------------- running

    /**
     * The rows of a query with their columns, or the error it failed with;
     * with the relative tolerance of its doubles, see
     * {@link ResultComparison#tolerance}, as threads combine the moments of
     * the standard deviations and variances in an order that differs from
     * run to run.
     */
    private record Run(Schema schema, List<Object[]> rows, Throwable error, double tolerance) {
        Run(Schema schema, List<Object[]> rows, Throwable error) {
            this(schema, rows, error, 0);
        }
    }

    /** The end of a query that gives more than {@link #MAX_ROWS} rows. */
    @SuppressWarnings("serial")
    private static final class TooLarge extends RuntimeException {
        TooLarge() {
            super("more than " + MAX_ROWS + " rows", null, false, false);
        }
    }

    /**
     * Runs a query and collects its rows as the vectors hold them, which
     * {@link ResultComparison} renders as it renders the values of
     * {@code rows()}; it stops at more than {@link #MAX_ROWS} rows.
     */
    private Run run(String sql) {
        PlanNode plan = null;
        try {
            plan = engine.plan(sql);
            try (QueryResult result = engine.execute(plan)) {
                List<Object[]> rows = new ArrayList<>();
                for (Batch b = result.nextBatch(); b != null; b = result.nextBatch()) {
                    if (rows.size() + b.rowCount() > MAX_ROWS) {
                        return new Run(plan.schema(), null, new TooLarge());
                    }
                    rows.addAll(b.rows());
                }
                return new Run(plan.schema(), rows, null, ResultComparison.tolerance(plan));
            }
        } catch (RuntimeException e) {
            if (plan != null && BudgetFailures.unexpected(plan, e)) {
                // A crash rather than a query out of the budget.
                return new Run(null, null, new IllegalStateException("A join with keys did not spill: "
                        + e.getMessage(), e));
            }
            return new Run(null, null, e);
        } catch (StackOverflowError e) {
            return new Run(null, null, e);
        }
    }

    /**
     * A check that ends in an error: skipped where a query may fail so, with
     * the class and the start of the message without values and numbers as
     * the first line of the report and the whole message as the second, else
     * a crash. A database that rejects a statement is a crash too: what the
     * engine sends must be valid SQL of its dialect.
     */
    private static Check failed(List<String> sql, Throwable e) {
        String message = e.getMessage() == null ? "" : e.getMessage().lines().findFirst().orElse("");
        if (e instanceof TooLarge) {
            return new Check(Result.TOO_LARGE, sql, message, false);
        }
        if (e instanceof QueryException && message.startsWith("Memory budget")) {
            return new Check(Result.OUT_OF_BUDGET, sql, message, false);
        }
        String kind = e.getClass().getSimpleName() + ": " + message.replaceAll("'[^']*'", "'?'")
                .replaceAll("[0-9]+(\\.[0-9]+)?", "n");
        boolean expected = e instanceof SqlException || e instanceof QueryException || e instanceof ConversionException;
        if (expected && !message.startsWith("The JDBC source ")) {
            return new Check(Result.SKIPPED, sql, (kind.length() > 90 ? kind.substring(0, 90) + "..." : kind) + "\n"
                    + message, false);
        }
        return new Check(Result.CRASHES, sql, e.toString(), false);
    }

    /** Runs the query of a partition and its partitioned form and compares their rows as multisets. */
    Check tlp(SqlGenerator.Partition p) {
        String query = SqlPrinter.print(p.query());
        Run base = run(query);
        if (base.error() != null) {
            return failed(List.of(query), base.error());
        }
        List<String> sql = tlpQueries(p, base.schema());
        Run partitioned = run(sql.get(1));
        if (partitioned.error() != null) {
            return failed(sql, partitioned.error());
        }
        if (!partitioned.schema().types().equals(base.schema().types())) {
            return new Check(Result.DIFFERS, sql, "types: " + base.schema().types() + " against "
                    + partitioned.schema().types(), false);
        }
        boolean exact = p.kind() == SqlGenerator.Kind.WHERE || p.kind() == SqlGenerator.Kind.JOIN_ON
                || p.kind() == SqlGenerator.Kind.WINDOW;
        ResultComparison.Outcome same = ResultComparison.compare(base.schema(), exact ? base.rows()
                : normalized(base.rows()), exact ? partitioned.rows() : normalized(partitioned.rows()), false, 0,
                Math.max(base.tolerance(), partitioned.tolerance()));
        return new Check(same.same() ? Result.SAME : Result.DIFFERS, sql, same.report(), base.rows().isEmpty());
    }

    /** The rows with each interval in its normalized form, where its months fit. */
    private static List<Object[]> normalized(List<Object[]> rows) {
        List<Object[]> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            Object[] copy = row.clone();
            for (int c = 0; c < copy.length; c++) {
                if (copy[c] instanceof Interval i) {
                    long months = Interval.normalizedMonths(i.months(), i.days(), i.micros());
                    if (months == (int) months) {
                        copy[c] = Interval.of((int) months, (int) Interval.normalizedDays(i.days(), i.micros()),
                                Interval.normalizedMicros(i.micros()));
                    }
                }
            }
            result.add(copy);
        }
        return result;
    }

    /**
     * Runs the query of a partition and the query of all rows of a view of
     * it, and compares their rows as multisets. A view that cannot be
     * created, or whose query fails where it is read, differs: the query
     * itself ran.
     */
    Check view(SqlGenerator.Partition p) {
        String query = SqlPrinter.print(new Query(p.with(), p.base(), List.of(), null, null));
        Run base = run(query);
        if (base.error() != null) {
            return failed(List.of(query), base.error());
        }
        List<String> sql = viewQueries(p, base.schema().size(), viewName());
        try {
            engine.update(sql.get(1));
        } catch (RuntimeException e) {
            return new Check(Result.DIFFERS, sql, "the view cannot be created: " + e, false);
        }
        try {
            Run viewed = run(sql.get(2));
            if (viewed.error() != null) {
                return viewed.error() instanceof SqlException e && e.reason().startsWith("In view ")
                        ? new Check(Result.DIFFERS, sql, "the view fails where it is read: " + e, false)
                        : failed(sql, viewed.error());
            }
            if (!viewed.schema().types().equals(base.schema().types())) {
                return new Check(Result.DIFFERS, sql, "types: " + base.schema().types() + " against "
                        + viewed.schema().types(), false);
            }
            ResultComparison.Outcome same = ResultComparison.compare(base.schema(), normalized(base.rows()),
                    normalized(viewed.rows()), false, 0, Math.max(base.tolerance(), viewed.tolerance()));
            return new Check(same.same() ? Result.SAME : Result.DIFFERS, sql, same.report(), base.rows().isEmpty());
        } finally {
            engine.update("DROP VIEW IF EXISTS " + viewName());
        }
    }

    /** Returns the qualified name of the view of the check of views. */
    private String viewName() {
        if (view == null) {
            String source = engine.sources().get(0);
            String schema = engine.connector(source).catalog().schemas().get(0);
            view = quoted(source) + "." + quoted(schema) + "." + VIEW;
        }
        return view;
    }

    private static String quoted(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    /** Runs the two queries of NoREC over a partition and compares their counts. */
    Check norec(SqlGenerator.Partition p) {
        List<String> sql = norecQueries(p);
        Run filtered = run(sql.get(0));
        if (filtered.error() != null) {
            return failed(sql, filtered.error());
        }
        Run evaluated = run(sql.get(1));
        if (evaluated.error() != null) {
            return failed(sql, evaluated.error());
        }
        String count = String.valueOf(filtered.rows().get(0)[0]);
        String sum = String.valueOf(evaluated.rows().get(0)[0]);
        return new Check(count.equals(sum) ? Result.SAME : Result.DIFFERS, sql, "count " + count + ", sum " + sum,
                count.equals("0"));
    }

    // ------------------------------------------------------- recording

    /** Runs the checks of a query and records how they ended. */
    void check(int query, SqlGenerator.Partition p) {
        queries++;
        record(query, "TLP " + p.kind(), p, tlp(p), this::tlp);
        record(query, "NoREC", p, norec(p), this::norec);
        record(query, "VIEW", p, view(p), this::view);
    }

    private void record(int query, String form, SqlGenerator.Partition p, Check c,
            Function<SqlGenerator.Partition, Check> again) {
        checks++;
        results.computeIfAbsent(form, f -> new EnumMap<>(Result.class)).merge(c.result(), 1, Integer::sum);
        switch (c.result()) {
            case SAME -> {
                if (c.empty()) {
                    empty.merge(form, 1, Integer::sum);
                }
            }
            case OUT_OF_BUDGET, TOO_LARGE -> {
            }
            case SKIPPED -> {
                skipped++;
                String kind = c.report().lines().findFirst().orElse("");
                errors.merge(kind, 1, Integer::sum);
                examples.putIfAbsent(kind, c.report().substring(kind.length()).strip());
            }
            case DIFFERS, CRASHES -> {
                String text = String.join("\n", c.sql());
                for (Known k : KNOWN) {
                    if (k.matches().test(text)) {
                        known.merge(k.name(), 1, Integer::sum);
                        return;
                    }
                }
                String head = "query " + query + " (" + form + "): " + c.result() + "\n" + text.indent(2)
                        + c.report().indent(2);
                unexplained.add(unexplained.size() < SHRUNK ? head + shrink(p, c, again).indent(2) : head);
            }
        }
    }

    /**
     * Shrinks a partition as long as its check ends the same way: the
     * condition first, then the query; the candidates come from the
     * {@link QueryShrinker}, and keep the {@code DISTINCT} of their form.
     */
    private static String shrink(SqlGenerator.Partition start, Check c,
            Function<SqlGenerator.Partition, Check> check) {
        SqlGenerator.Partition small = Minimizer.minimize(start, Metamorphic::candidates,
                p -> check.apply(p).result() == c.result(), 400);
        Check again = check.apply(small);
        return "shrunk: " + again.result() + "\n" + String.join("\n", again.sql()).indent(2) + again.report().indent(2);
    }

    private static List<SqlGenerator.Partition> candidates(SqlGenerator.Partition p) {
        List<SqlGenerator.Partition> result = new ArrayList<>();
        boolean one = p.rows() == p.predicate();
        for (SqlExpr e : QueryShrinker.expression(p.predicate())) {
            result.add(new SqlGenerator.Partition(p.kind(), p.with(), p.base(), e, one ? e : p.rows()));
        }
        if (!one) {
            for (SqlExpr e : QueryShrinker.expression(p.rows())) {
                result.add(new SqlGenerator.Partition(p.kind(), p.with(), p.base(), p.predicate(), e));
            }
        }
        for (Query q : QueryShrinker.query(p.query())) {
            if (q.body() instanceof QueryBody.Select s && q.orderBy().isEmpty() && q.limit() == null
                    && q.offset() == null && (p.kind() != SqlGenerator.Kind.DISTINCT || s.distinct())
                    && (p.kind() != SqlGenerator.Kind.JOIN_ON || s.from() instanceof FromItem.Join j
                            && j.kind() == FromItem.JoinKind.INNER && j.on() != null)) {
                result.add(new SqlGenerator.Partition(p.kind(), q.with(), s, p.predicate(), p.rows()));
            }
        }
        return result;
    }

    /** The differences and crashes that are not known. */
    List<String> unexplained() {
        return unexplained;
    }

    /**
     * The report: the checks by form, how they ended and how many of the
     * same ones were over no rows, the skipped ones by error, the known
     * differences, and the unexplained ones. Where more than a quarter of the
     * checks are skipped for errors, not counting those over the budget, the
     * queries are not good enough, and the report says so.
     */
    String report() {
        StringBuilder sb = new StringBuilder();
        sb.append(title).append(": ").append(queries).append(" queries, seed ").append(RandomRun.SEED).append(", ")
                .append(checks).append(" checks\n");
        results.forEach((form, r) -> sb.append("  ").append(form).append(": ").append(r).append(", over no rows: ")
                .append(empty.getOrDefault(form, 0)).append('\n'));
        int share = checks == 0 ? 0 : skipped * 100 / checks;
        sb.append("  skipped: ").append(skipped).append(" (").append(share).append(" %)")
                .append(share > SKIPPED_WARNING ? ", more than " + SKIPPED_WARNING + " %" : "").append('\n');
        errors.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(8)
                .forEach(e -> {
                    String example = examples.get(e.getKey());
                    sb.append("    ").append(e.getValue()).append(" x ").append(e.getKey()).append('\n')
                            .append("      e.g. ").append(example.length() > 300 ? example.substring(0, 300) + "..."
                                    : example).append('\n');
                });
        sb.append("  known: ").append(known).append('\n');
        sb.append("  unexplained: ").append(unexplained.size()).append('\n');
        for (String u : unexplained) {
            sb.append('\n').append(u.indent(2));
        }
        return sb.toString();
    }
}
