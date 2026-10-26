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

import net.fyrql.connector.SourceStatistics;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.Plans;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.Casts;
import net.fyrql.exec.Codegen;
import net.fyrql.exec.Executor;
import net.fyrql.exec.OperatorStatistics;
import net.fyrql.exec.PipelinePlanner;
import net.fyrql.exec.QueryStatistics;
import net.fyrql.exec.SegmentPlanner;
import net.fyrql.sql.bind.BoundStatement;
import net.fyrql.sql.render.DuckDbDialect;
import net.fyrql.sql.render.SqlRenderer;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.ToDoubleFunction;
import java.util.regex.Pattern;

/**
 * Prints a plan for {@link FyrQL#explain}: one node per line, the inputs of
 * a node indented below it. A column is written as {@code #index}, the
 * position in the output of the node's input; the keys of a join name the
 * left input with {@code l} and the right one with {@code r}. A filter,
 * projection, join or aggregation whose expressions or keys the engine
 * compiles is marked {@code [codegen]}; a chain that runs as one compiled
 * pipeline, see {@link PipelinePlanner}, is marked
 * {@code [codegen pipeline]} at its top node and {@code [codegen]} at the
 * nodes below it. With more than one thread, the top node of a segment,
 * see {@link SegmentPlanner}, is marked {@code [parallel]}: it runs on
 * several threads if its scans have more than one split. A part of the
 * plan that a source runs is a line {@code Remote source [what it runs]},
 * with the part below it, each line after a {@code |}. A statement that
 * writes is a line of its own, see {@link #header}.
 * <p>
 * The plan a query ran can be printed with what each operator and scan
 * did, after {@code --} on the line of its node, see {@link #print(QueryStatistics,
 * Codegen, ToDoubleFunction, int)}.
 */
final class Explain {

    private static final SqlRenderer RENDERER = new SqlRenderer(DuckDbDialect.INSTANCE, Object::toString);
    private static final Pattern PLAIN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private Explain() {
    }

    /**
     * Returns the line of a statement that writes, above the plan of its
     * rows: {@code Copy to 'file' [format: csv, header: true]},
     * {@code Copy from 'file' into source.schema.t [format: csv]},
     * {@code Create table source.schema.t (a INTEGER NOT NULL, b VARCHAR)}
     * with {@code [or replace]} or {@code [if not exists]},
     * {@code Insert into source.schema.t} or
     * {@code Drop table [if exists] source.schema.t}, or the setting of
 * {@code SET} and {@code RESET}: {@code Set TimeZone = 'UTC'}, {@code Reset TimeZone}.
     */
    static String header(BoundStatement statement) {
        return switch (statement) {
            case BoundStatement.Copy c -> {
                StringBuilder sb = new StringBuilder("Copy to '").append(c.path().replace("'", "''"))
                        .append("' [format: ").append(c.format());
                c.options().forEach((k, v) -> sb.append(", ").append(k).append(": ").append(v));
                yield sb.append(']').toString();
            }
            case BoundStatement.CopyFrom c -> {
                StringBuilder sb = new StringBuilder("Copy from '").append(c.path().replace("'", "''"))
                        .append("' into ").append(c.table()).append(" [format: ").append(c.format());
                c.options().forEach((k, v) -> sb.append(", ").append(k).append(": ").append(v));
                yield sb.append(']').toString();
            }
            case BoundStatement.Create c -> "Create table " + c.table() + " ("
                    + String.join(", ", c.schema().fields().stream().map(Schema.Field::toString).toList()) + ")"
                    + switch (c.mode()) {
                        case CREATE -> "";
                        case OR_REPLACE -> " [or replace]";
                        case IF_NOT_EXISTS -> " [if not exists]";
                    };
            case BoundStatement.Insert i -> "Insert into " + i.table();
            case BoundStatement.Drop d -> d.table() == null
                    ? "Drop table if exists " + String.join(".", d.name()) + " [no such table]"
                    : "Drop table " + (d.ifExists() ? "if exists " : "") + d.table();
            case BoundStatement.Set s -> s.value() == null ? "Reset " + s.name()
                    : "Set " + s.name() + " = '" + s.value().replace("'", "''") + "'";
            case BoundStatement.Query q -> throw new IllegalArgumentException("A query has a plan, not a header");
            case BoundStatement.Explain e -> throw new IllegalArgumentException("EXPLAIN has no header");
        };
    }

    /** Prints a plan that an engine with a number of threads runs. */
    static String print(PlanNode plan, Codegen codegen, ToDoubleFunction<PlanNode> estimate, int threads) {
        StringBuilder sb = new StringBuilder();
        Set<PlanNode> segments = threads > 1 ? SegmentPlanner.tops(plan) : Set.of();
        print(plan, codegen, estimate, segments, new IdentityHashMap<>(), 0, sb, null);
        return sb.toString();
    }

    /**
     * Prints the plan a query ran, as {@link #print(PlanNode, Codegen,
     * ToDoubleFunction, int)} does, with what each operator and scan did
     * after {@code --} on the line of its node: the rows and batches it
     * returned, the time it worked without the time of its inputs, summed
     * over its threads, and the threads; for a scan the splits, the row
     * groups and files read of all, the requests and the bytes it read;
     * the parts spilled to disk; and below a scan, each statement sent to
     * a database on a line after {@code >}. A node of a compiled pipeline
     * below its top has no numbers. Empty if no plan ran.
     */
    static String print(QueryStatistics statistics, Codegen codegen, ToDoubleFunction<PlanNode> estimate,
            int threads) {
        PlanNode plan = statistics.plan();
        if (plan == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        Set<PlanNode> segments = threads > 1 ? SegmentPlanner.tops(plan) : Set.of();
        print(plan, codegen, estimate, segments, new IdentityHashMap<>(), 0, sb,
                new Run(statistics, Plans.ids(plan)));
        return sb.toString();
    }

    /** What a query did, and the ids of the nodes of its plan, for the labels of its operators. */
    private record Run(QueryStatistics statistics, Map<PlanNode, Integer> ids) {
    }

    /**
     * Returns the first line of {@code EXPLAIN ANALYZE}: the time of the
     * query, its rows or those it wrote, the threads its operators worked on,
     * the compiled code it used and what it spilled to disk, such as
     * {@code Analyzed in 1.23 s: 4 rows, 12 threads, 5 compiled parts in 45.6 ms}.
     */
    static String analyzed(QueryStatistics statistics, long rows, boolean written) {
        StringBuilder sb = new StringBuilder("Analyzed in ").append(time(statistics.elapsed().toNanos()))
                .append(": ").append(count(rows, "row")).append(written ? " written" : "").append(", ")
                .append(count(statistics.threads(), "thread"));
        if (statistics.compiled() > 0) {
            sb.append(", ").append(count(statistics.compiled(), "compiled part")).append(" in ")
                    .append(time(statistics.compileNanos()));
        }
        if (statistics.spill().parts() > 0) {
            sb.append(", spilled ").append(bytes(statistics.spill().bytesWritten())).append(" in ")
                    .append(count(statistics.spill().parts(), "part"));
        }
        return sb.toString();
    }

    /** The numbers of a node after {@code --}, or "" if its operator did nothing it could count. */
    private static String numbers(PlanNode node, Run run) {
        String label = Executor.label(node, run.ids());
        OperatorStatistics op = run.statistics().operators().get(label);
        SourceStatistics source = run.statistics().sources().get(label);
        List<String> parts = new ArrayList<>();
        if (source != null) {
            if (source.splits() > 0) {
                parts.add(count(source.splits(), "split"));
            }
            if (source.rowGroupsRead() + source.rowGroupsSkipped() > 0) {
                parts.add(source.rowGroupsRead() + " of " + count(source.rowGroupsRead() + source.rowGroupsSkipped(),
                        "row group"));
            }
            if (source.filesRead() + source.filesSkipped() > 0) {
                parts.add(source.filesRead() + " of " + count(source.filesRead() + source.filesSkipped(), "file"));
            }
            if (source.requests() > 0) {
                parts.add(count(source.requests(), "request") + " in " + time(source.requestNanos()));
            }
            if (source.bytes() > 0) {
                parts.add(bytes(source.bytes()));
            }
        }
        if (op != null && op.spilledParts() > 0) {
            parts.add("spilled " + bytes(op.spilledBytes()) + " in " + count(op.spilledParts(), "part")
                    + ", read back " + bytes(op.readBack()));
        }
        if (op == null && parts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(" --");
        if (op != null) {
            sb.append(' ').append(count(op.rows(), "row")).append(", ").append(count(op.batches(), "batch"))
                    .append(", ").append(time(op.nanos())).append(", ").append(count(op.threads(), "thread"));
        }
        if (!parts.isEmpty()) {
            sb.append(op != null ? "; " : " ").append(String.join(", ", parts));
        }
        return sb.toString();
    }

    /** A number with a noun, such as {@code 1 row} or {@code 2 batches}. */
    private static String count(long n, String noun) {
        return n + " " + (n == 1 ? noun : noun.endsWith("ch") ? noun + "es" : noun + "s");
    }

    /** A time with three significant digits, such as {@code 450 us}, {@code 12.3 ms} or {@code 1.23 s}. */
    static String time(long nanos) {
        if (nanos < 1_000) {
            return nanos + " ns";
        }
        double value;
        String unit;
        if (nanos < 1_000_000) {
            value = nanos / 1e3;
            unit = "us";
        } else if (nanos < 1_000_000_000) {
            value = nanos / 1e6;
            unit = "ms";
        } else {
            value = nanos / 1e9;
            unit = "s";
        }
        return String.format(Locale.ROOT, value >= 100 ? "%.0f" : value >= 10 ? "%.1f" : "%.2f", value) + " "
                + unit;
    }

    /** Bytes in units of 1024, such as {@code 512 bytes}, {@code 12.5 KB} or {@code 203.5 MB}. */
    static String bytes(long bytes) {
        if (bytes < 1024) {
            return count(bytes, "byte");
        }
        double value = bytes / 1024.0;
        String unit = "KB";
        for (String next : new String[] {"MB", "GB", "TB"}) {
            if (value < 1024) {
                break;
            }
            value /= 1024;
            unit = next;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, unit);
    }

    /**
     * Prints a node and its inputs; the marks of the nodes of the pipelines
     * found so far, by node, tell the nodes inside a pipeline.
     *
     * @param run what the query did, or {@code null} for the plan alone
     */
    private static void print(PlanNode node, Codegen codegen, ToDoubleFunction<PlanNode> estimate,
            Set<PlanNode> segments, Map<PlanNode, String> marks, int depth, StringBuilder sb, Run run) {
        if (!marks.containsKey(node)) {
            PipelinePlanner.Pipeline pipeline = PipelinePlanner.plan(node, codegen, estimate);
            if (pipeline != null) {
                for (PlanNode stage : pipeline.spec().stages()) {
                    marks.put(stage, stage == node ? " [codegen pipeline]" : " [codegen]");
                }
            }
        }
        sb.append("  ".repeat(depth)).append(line(node));
        String mark = marks.get(node);
        if (mark != null) {
            sb.append(mark);
        } else if (compiled(node, codegen)) {
            sb.append(" [codegen]");
        }
        if (segments.contains(node)) {
            sb.append(" [parallel]");
        }
        if (run != null) {
            sb.append(numbers(node, run));
        }
        sb.append('\n');
        if (run != null) {
            SourceStatistics source = run.statistics().sources().get(Executor.label(node, run.ids()));
            if (source != null) {
                for (String statement : source.statements()) {
                    sb.append("  ".repeat(depth + 1)).append("> ").append(statement.replaceAll("\\s+", " "))
                            .append('\n');
                }
            }
        }
        for (PlanNode child : node.children()) {
            print(child, codegen, estimate, segments, marks, depth + 1, sb, run);
        }
        if (node instanceof PlanNode.Remote r) {
            remote(r.logical(), "  ".repeat(depth + 1) + "| ", 0, sb);
        }
    }

    /** The part of the plan that a source runs, each line after a prefix; nothing of it is compiled. */
    private static void remote(PlanNode node, String prefix, int depth, StringBuilder sb) {
        sb.append(prefix).append("  ".repeat(depth)).append(line(node)).append('\n');
        for (PlanNode child : node.children()) {
            remote(child, prefix, depth + 1, sb);
        }
    }

    /** Tells whether the engine compiles the expressions or keys of a node. */
    private static boolean compiled(PlanNode node, Codegen codegen) {
        return switch (node) {
            case PlanNode.Filter f -> codegen.compiles(f.condition());
            case PlanNode.Project p -> p.columns().stream().map(PlanNode.NamedExpression::expression)
                    .anyMatch(e -> !(e instanceof Expression.Column) && codegen.compiles(e));
            case PlanNode.Join j -> codegen.compilesKeys(j.leftKeys().stream().map(k -> j.left().schema().type(k))
                    .toList()) || j.condition() != null && codegen.compiles(j.condition());
            case PlanNode.Aggregate a -> codegen.compilesKeys(a.groupColumns().stream()
                    .map(c -> a.input().schema().type(c)).toList());
            default -> false;
        };
    }

    static String line(PlanNode node) {
        return switch (node) {
            case PlanNode.Scan s -> {
                List<String> columns = new ArrayList<>();
                Schema read = s.columnSchema();
                for (int i = 0; i < read.size(); i++) {
                    List<String> parts = new ArrayList<>();
                    parts.add(name(s.tableSchema().name(s.columns().get(i))));
                    for (String field : s.fields().get(i)) {
                        parts.add(name(field));
                    }
                    columns.add(String.join(".", parts) + " " + read.type(i).sqlName());
                }
                yield "Scan " + s.table() + " (" + String.join(", ", columns) + ")"
                        + (s.pushed().isEmpty() ? "" : " [pushed: " + pushed(s.pushed()) + "]");
            }
            case PlanNode.Remote r -> "Remote " + r.source() + " [" + r.description() + "]";
            case PlanNode.Filter f -> "Filter " + expression(f.condition());
            case PlanNode.Project p -> {
                List<String> columns = new ArrayList<>();
                for (PlanNode.NamedExpression c : p.columns()) {
                    columns.add(expression(c.expression()) + " AS " + name(c.name()));
                }
                yield "Project " + String.join(", ", columns);
            }
            case PlanNode.Join j -> {
                List<String> keys = new ArrayList<>();
                for (int i = 0; i < j.leftKeys().size(); i++) {
                    keys.add("l#" + j.leftKeys().get(i) + " = r#" + j.rightKeys().get(i));
                }
                if (j.condition() != null) {
                    int width = j.left().schema().size();
                    keys.add(RENDERER.expression(j.condition(), (IntFunction<String>) i -> i < width ? "l#" + i
                            : "r#" + (i - width)));
                }
                yield "Join " + j.type() + (keys.isEmpty() ? "" : " ON " + String.join(" AND ", keys));
            }
            case PlanNode.Aggregate a -> "Aggregate" + aggregation(a.groupColumns(), a.aggregates());
            case PlanNode.Sort s -> "Sort " + keys(s.keys());
            case PlanNode.Limit l -> "Limit " + l.count() + (l.offset() == 0 ? "" : " OFFSET " + l.offset());
            case PlanNode.Unnest u -> "Unnest " + expression(u.list()) + " AS " + name(u.name());
            case PlanNode.Window w -> "Window" + window(w);
            case PlanNode.Union u -> "Union ALL";
            case PlanNode.Values v -> {
                List<String> columns = new ArrayList<>();
                for (Schema.Field f : v.schema().fields()) {
                    columns.add(name(f.name()) + " " + f.type().sqlName());
                }
                yield "Values " + v.rows().size() + (v.rows().size() == 1 ? " row" : " rows") + " ("
                        + String.join(", ", columns) + ")";
            }
            case PlanNode.SetOperation s -> s.kind() + (s.all() ? " ALL" : "");
            case PlanNode.CommonTable t -> "CommonTable " + name(t.name()) + " #" + t.id();
            case PlanNode.CommonTableScan s -> {
                List<String> columns = new ArrayList<>();
                for (Schema.Field f : s.schema().fields()) {
                    columns.add(name(f.name()) + " " + f.type().sqlName());
                }
                yield "CommonTableScan " + name(s.name()) + " #" + s.id() + " (" + String.join(", ", columns) + ")";
            }
        };
    }

    /** The groups and calls of an aggregation, after a leading word. */
    private static String aggregation(List<Integer> groupColumns, List<PlanNode.AggregateCall> aggregates) {
        List<String> groups = new ArrayList<>();
        for (int c : groupColumns) {
            groups.add("#" + c);
        }
        List<String> calls = new ArrayList<>();
        for (PlanNode.AggregateCall call : aggregates) {
            calls.add(call(call));
        }
        return (groups.isEmpty() ? "" : " GROUP BY " + String.join(", ", groups))
                + (calls.isEmpty() ? "" : (groups.isEmpty() ? " " : ": ") + String.join(", ", calls));
    }

    /** What a connector took over of the plan above a scan, in the order it applies. */
    private static String pushed(PlanNode.Pushed pushed) {
        List<String> parts = new ArrayList<>();
        if (!pushed.filters().isEmpty()) {
            parts.add("WHERE " + expression(pushed.condition()));
        }
        pushed.aggregation().ifPresent(a -> parts.add(aggregation(a.groupColumns(), a.calls()).strip()));
        if (!pushed.order().isEmpty()) {
            parts.add("ORDER BY " + keys(pushed.order()));
        }
        pushed.limit().ifPresent(n -> parts.add("LIMIT " + n));
        return String.join(" ", parts);
    }

    /** The partition, the order and the calls of a window, after a leading word. */
    private static String window(PlanNode.Window w) {
        List<String> parts = new ArrayList<>();
        if (!w.partition().isEmpty()) {
            parts.add("PARTITION BY " + String.join(", ", w.partition().stream().map(c -> "#" + c).toList()));
        }
        if (!w.order().isEmpty()) {
            parts.add("ORDER BY " + keys(w.order()));
        }
        List<String> calls = new ArrayList<>();
        for (PlanNode.WindowCall call : w.calls()) {
            calls.add(call(call));
        }
        return " " + (parts.isEmpty() ? "" : String.join(" ", parts) + ": ") + String.join(", ", calls);
    }

    /** A call of a window: the function, the filter, and the frame if the function uses one that is not the default. */
    private static String call(PlanNode.WindowCall call) {
        StringBuilder sb = new StringBuilder();
        if (call.aggregate() != null) {
            sb.append(aggregate(call.aggregate()));
        } else {
            sb.append(call.function().sqlName()).append('(')
                    .append(String.join(", ", call.arguments().stream().map(c -> "#" + c).toList()))
                    .append(call.ignoreNulls() ? " IGNORE NULLS)" : ")");
        }
        if (call.filter() >= 0) {
            sb.append(" FILTER (WHERE #").append(call.filter()).append(')');
        }
        if (call.function().usesFrame() && !call.frame().equals(PlanNode.Frame.DEFAULT)) {
            sb.append(' ').append(call.frame().sql(b -> b.unit() == null
                    ? Casts.format(b.offset().value(), b.offset().type())
                    : "INTERVAL " + b.offset().value() + " " + b.unit()));
        }
        return sb.append(" AS ").append(name(call.name())).toString();
    }

    private static String call(PlanNode.AggregateCall call) {
        String filter = call.filter() < 0 ? "" : " FILTER (WHERE #" + call.filter() + ")";
        return aggregate(call) + filter + " AS " + name(call.name());
    }

    /** An aggregate call without its name. */
    private static String aggregate(PlanNode.AggregateCall call) {
        if (call.function() == PlanNode.AggregateFunction.COUNT_STAR) {
            return "count(*)";
        }
        StringBuilder sb = new StringBuilder(call.function().sqlName()).append('(');
        if (call.distinct()) {
            sb.append("DISTINCT ");
        }
        sb.append('#').append(call.column());
        if (call.function() == PlanNode.AggregateFunction.STRING_AGG) {
            sb.append(", ").append(call.separator() == null ? "NULL"
                    : DuckDbDialect.INSTANCE.literal(call.separator(), DataType.VARCHAR));
        }
        if (!call.orderBy().isEmpty()) {
            sb.append(" ORDER BY ").append(keys(call.orderBy()));
        }
        return sb.append(')').toString();
    }

    private static String keys(List<PlanNode.SortKey> keys) {
        List<String> result = new ArrayList<>();
        for (PlanNode.SortKey k : keys) {
            result.add("#" + k.column() + (k.ascending() ? "" : " DESC") + (k.nullsFirst() ? " NULLS FIRST" : ""));
        }
        return String.join(", ", result);
    }

    private static String expression(Expression e) {
        return RENDERER.expression(e, (String) null);
    }

    private static String name(String name) {
        return PLAIN.matcher(name).matches() ? name : DuckDbDialect.INSTANCE.quoteIdentifier(name);
    }
}
