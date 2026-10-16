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

package net.fyrql.codegen;

import net.fyrql.codegen.ExpressionCodegen.Part;
import net.fyrql.codegen.ExpressionCodegen.Val;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.JoinType;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.exec.spi.PipelineSpec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Generates the class body of a {@link net.fyrql.exec.spi.CompiledPipeline}:
 * one loop over the rows of a batch of the source, in which the stages run
 * one after the other for each row. A filter skips the rest of the row
 * unless its condition is {@code TRUE}; a projection computes all its
 * columns, which the stages above read as variables; a column that only
 * passes an input column on reads it where it is. After the loop, a passed
 * column is the input vector, or its selected rows, and a computed column
 * is wrapped from the array it was collected in.
 * <p>
 * A join probes the {@link net.fyrql.exec.spi.JoinTable} of its build side
 * with the hash of its keys, which is the hash of the interpreter, bit for
 * bit, follows the chain of the slot and compares the keys; a row with a
 * {@code NULL} key has no candidates, and without keys every build row is
 * one. The condition, if any, runs over all candidates in build order; the
 * matches then go through the stages above one by one, in a loop that the
 * stages above are nested in. As in the hash join, a join that only asks
 * whether a row matches stops at the first candidate when it has no
 * condition, a {@code LEFT} or {@code SINGLE} join goes on once with
 * {@code NULL} for the columns of the build side when nothing matched, a
 * {@code SINGLE} join fails when more than one row matched, and a
 * {@code MARK} join is {@code NULL} where nothing matched but the build
 * side has rows and a key on either side is {@code NULL}. A column of the
 * build side that is only passed on is gathered from its rows after the
 * loop.
 * <p>
 * Every stage sees the rows the operator of its node would see and
 * evaluates its expressions with the code of {@link ExpressionCodegen}, so
 * results and errors are those of the interpreter. Only which error comes
 * first may differ when several rows fail, now also across the stages,
 * since the interpreter runs each stage over the whole batch. Before the
 * code of a stage, a local variable takes its index, which
 * {@code stage()} reports after an error.
 * <p>
 * HotSpot does not compile methods of more than 8,000 bytes of bytecode.
 * So that the loop stays below that, the code of a condition or a computed
 * column that is longer than {@link #SPLIT_CHARS} characters becomes a
 * method of its own, which takes the row and the variables it reads as
 * parameters and leaves a computed value in two fields.
 */
final class PipelineCodegen {

    static final String PIPELINE = "GeneratedPipeline";

    /** The length of the code of an expression, in characters, from which it becomes a method of its own. */
    static final int SPLIT_CHARS = 1000;

    /** The most parameter slots of such a method; with more, the code stays in the loop. */
    private static final int MAX_SLOTS = 200;

    /** The indentation of the body of the loop: method, {@code try}, {@code for}. */
    private static final int LOOP_LEVEL = 3;

    /** Where a column of the input of a stage comes from. */
    private sealed interface Ref permits Source, Build, Computed {
    }

    /** A column of the batch of the source, at the row {@code i}. */
    private record Source(int index) implements Ref {
    }

    /**
     * A column of the build side of a join, at the row in a variable;
     * {@code NULL} where the row is -1 if the join is nullable there.
     */
    private record Build(int probe, int index, String row, boolean nullable) implements Ref {
    }

    /** A value an earlier stage computed. */
    private record Computed(Val value) implements Ref {
    }

    private final ExpressionCodegen g = new ExpressionCodegen();
    private final List<String> methods = new ArrayList<>();
    /** The declarations before the loop of the tables and the buffers of the joins. */
    private final List<String> joinDeclarations = new ArrayList<>();
    /** The declarations before the loop of the vectors of the build sides, by name. */
    private final Map<String, String> buildVectors = new LinkedHashMap<>();
    /** Whether a stage may drop rows. */
    private boolean dropping;
    /** Whether a stage may give more than one row for a row. */
    private boolean expanding;
    /** The number of loops over matches that the stages above a join are nested in. */
    private int nested;

    private PipelineCodegen() {
    }

    /**
     * Tells whether a chain can be compiled: its conditions and computed
     * columns are of flat types and not too big each, at most
     * {@link JaninoCompiler#MAX_PIPELINE_NODES} nodes together; the keys of
     * a join are of flat types, the same on both sides, and a key that is
     * held as an {@code Object} is a column of the source or of a build
     * side; a column that is only passed on may be of any type.
     */
    static boolean supports(PipelineSpec spec) {
        int nodes = 0;
        // Whether a column of the input of a stage is in a vector, not computed.
        List<Boolean> inVector = new ArrayList<>();
        for (int k = 0; k < spec.input().size(); k++) {
            inVector.add(true);
        }
        for (PlanNode stage : spec.stages()) {
            switch (stage) {
                case PlanNode.Filter f -> {
                    if (!ExpressionCodegen.supports(f.condition(), JaninoCompiler.MAX_NODES)) {
                        return false;
                    }
                    nodes += ExpressionCodegen.size(f.condition());
                }
                case PlanNode.Project p -> {
                    List<Boolean> next = new ArrayList<>();
                    for (PlanNode.NamedExpression c : p.columns()) {
                        Expression e = c.expression();
                        nodes += ExpressionCodegen.size(e);
                        if (e instanceof Expression.Column column) {
                            next.add(inVector.get(column.index()));
                        } else if (ExpressionCodegen.supports(e, JaninoCompiler.MAX_NODES)) {
                            next.add(false);
                        } else {
                            return false;
                        }
                    }
                    inVector = next;
                }
                case PlanNode.Join j -> {
                    for (int x = 0; x < j.leftKeys().size(); x++) {
                        int lk = j.leftKeys().get(x);
                        DataType type = j.left().schema().type(lk);
                        if (!Rep.flat(type) || !type.equals(j.right().schema().type(j.rightKeys().get(x)))
                                || Rep.of(type) == Rep.OBJECT && !inVector.get(lk)) {
                            return false;
                        }
                    }
                    if (j.condition() != null) {
                        if (!ExpressionCodegen.supports(j.condition(), JaninoCompiler.MAX_NODES)) {
                            return false;
                        }
                        nodes += ExpressionCodegen.size(j.condition());
                    }
                    nodes += 1 + j.leftKeys().size();
                    if (j.type().keepsRight()) {
                        for (int c = 0; c < j.right().schema().size(); c++) {
                            inVector.add(true);
                        }
                    } else if (j.type() == JoinType.MARK) {
                        inVector.add(false);
                    }
                }
                default -> {
                    return false;
                }
            }
        }
        return nodes <= JaninoCompiler.MAX_PIPELINE_NODES;
    }

    /** Generates the pipeline of a chain that {@link #supports} accepts. */
    static ExpressionCodegen.Unit pipeline(PipelineSpec spec) {
        return new PipelineCodegen().generate(spec);
    }

    private ExpressionCodegen.Unit generate(PipelineSpec spec) {
        g.body = new CodeWriter(LOOP_LEVEL);
        Ref[] refs = new Ref[spec.input().size()];
        for (int k = 0; k < refs.length; k++) {
            refs[k] = new Source(k);
        }
        int probes = 0;
        for (int k = 0; k < spec.stages().size(); k++) {
            g.body.line("stage = " + k + ";");
            switch (spec.stages().get(k)) {
                case PlanNode.Filter f -> {
                    g.access = access(refs);
                    String pass = select("s" + k, f.condition());
                    g.body.open("if (!" + pass + ")").line("continue;").close();
                    dropping = true;
                }
                case PlanNode.Project p -> refs = project(k, p, refs);
                case PlanNode.Join j -> refs = probe(k, probes++, j, refs);
                default -> throw new IllegalArgumentException("Not a stage: " + spec.stages().get(k));
            }
        }

        Schema output = spec.output();
        String schema = g.objectField("Schema", output);
        List<String> declarations = new ArrayList<>();
        CodeWriter store = new CodeWriter(g.body.level());
        CodeWriter grow = new CodeWriter(g.body.level() + 1);
        CodeWriter finish = new CodeWriter(1);
        boolean selects = false;
        // The variable of the row of each build side whose columns are passed on.
        Map<Integer, String> gathered = new LinkedHashMap<>();
        for (int o = 0; o < refs.length; o++) {
            String column = "columns[" + o + "] = ";
            switch (refs[o]) {
                case Source s -> {
                    String vector = "in.column(" + s.index() + ")";
                    String selected = vector + ".select(selected)";
                    finish.line(column + (expanding ? selected : dropping ? "m == n ? " + vector + " : " + selected
                            : vector) + ";");
                    selects |= expanding || dropping;
                }
                case Build b -> {
                    gathered.put(b.probe(), b.row());
                    finish.line(column + "Vectors.gather(tables[" + b.probe() + "].batch().column(" + b.index()
                            + "), selected" + b.probe() + ");");
                }
                case Computed c -> computed(o, c.value(), output.type(o), declarations, store, grow, finish);
            }
        }
        if (selects) {
            store.line("rows[m] = i;");
            grow.line("rows = java.util.Arrays.copyOf(rows, cap);");
        }
        for (Map.Entry<Integer, String> e : gathered.entrySet()) {
            String rows = "rows" + e.getKey();
            store.line(rows + "[m] = " + e.getValue() + ";");
            grow.line(rows + " = java.util.Arrays.copyOf(" + rows + ", cap);");
        }
        store.line("m++;");
        if (expanding) {
            g.body.open("if (m == cap)").line("cap = cap * 2;").raw(grow.toString()).close();
        }
        g.body.raw(store.toString());
        for (int b = 0; b < nested; b++) {
            g.body.close();
        }

        g.fields.add("private int failed;");
        CodeWriter w = new CodeWriter(0);
        g.header(w, PIPELINE);
        w.open("public int stage()").line("return failed;").close();
        w.blank();
        w.open("public Batch consume(Batch in, JoinTable[] tables)");
        w.line("int n = in.rowCount();");
        g.columnDeclarations(w);
        for (String d : joinDeclarations) {
            w.line(d);
        }
        for (String d : buildVectors.values()) {
            w.line(d);
        }
        w.line("int cap = n;");
        for (String d : declarations) {
            w.line(d);
        }
        if (selects) {
            w.line("int[] rows = new int[cap];");
        }
        for (int p : gathered.keySet()) {
            w.line("int[] rows" + p + " = new int[cap];");
        }
        w.line("int m = 0;");
        w.line("int stage = 0;");
        w.open("try");
        w.open("for (int i = 0; i < n; i++)");
        w.raw(g.body.toString());
        w.close();
        w.reopen("catch (RuntimeException e)").line("failed = stage;").line("throw e;").close();
        w.open("if (m == 0)").line("return null;").close();
        if (selects) {
            w.line("int[] selected = m == rows.length ? rows : java.util.Arrays.copyOf(rows, m);");
        }
        for (int p : gathered.keySet()) {
            w.line("int[] selected" + p + " = m == rows" + p + ".length ? rows" + p + " : java.util.Arrays.copyOf(rows"
                    + p + ", m);");
        }
        w.line("Vector[] columns = new Vector[" + refs.length + "];");
        w.raw(finish.toString());
        w.line("return new Batch(" + schema + ", m, java.util.Arrays.asList(columns));");
        w.close();
        for (String method : methods) {
            w.blank();
            w.raw(method);
        }
        return g.unit(w, PIPELINE);
    }

    /** Writes the store of a computed column, the growth of its array, and the vector it becomes after the loop. */
    private void computed(int o, Val v, DataType type, List<String> declarations, CodeWriter store, CodeWriter grow,
            CodeWriter finish) {
        String column = "columns[" + o + "] = ";
        Rep rep = v.rep();
        String out = "out" + o;
        if (rep == Rep.OBJECT) {
            declarations.add("Vector.Builder " + out + " = Vectors.builder(" + g.typeField(type) + ", n);");
            if (v.isNull().equals("true")) {
                store.line(out + ".appendNull();");
            } else if (v.isNull().equals("false")) {
                store.line(out + ".append(" + v.value() + ");");
            } else {
                store.open("if (" + v.isNull() + ")").line(out + ".appendNull();").reopen("else")
                        .line(out + ".append(" + v.value() + ");").close();
            }
            finish.line(column + out + ".build();");
            return;
        }
        String nulls = "nulls" + o;
        declarations.add(rep.array + " " + out + " = new " + rep.array.replace("[]", "[cap]") + ";");
        declarations.add("boolean[] " + nulls + " = null;");
        grow.line(out + " = java.util.Arrays.copyOf(" + out + ", cap);");
        grow.open("if (" + nulls + " != null)").line(nulls + " = java.util.Arrays.copyOf(" + nulls + ", cap);")
                .close();
        if (v.isNull().equals("false")) {
            store.line(out + "[m] = " + v.value() + ";");
        } else {
            boolean always = v.isNull().equals("true");
            if (!always) {
                store.open("if (" + v.isNull() + ")");
            }
            store.open("if (" + nulls + " == null)").line(nulls + " = new boolean[cap];").close()
                    .line(nulls + "[m] = true;");
            if (!always) {
                store.reopen("else").line(out + "[m] = " + v.value() + ";").close();
            }
        }
        finish.open("if (m < cap)").line(out + " = java.util.Arrays.copyOf(" + out + ", m);")
                .open("if (" + nulls + " != null)").line(nulls + " = java.util.Arrays.copyOf(" + nulls + ", m);")
                .close().close();
        String typeField = g.typeField(type);
        finish.line(column + switch (rep) {
            case INT, LONG -> rep.vector + ".wrap(" + typeField + ", " + out + ", " + nulls + ")";
            case DECIMAL -> "DecimalVector.wrap(" + typeField + ", " + out + ", " + nulls + ")";
            default -> rep.vector + ".wrap(" + out + ", " + nulls + ")";
        } + ";");
    }

    /** The access of a stage to its input. */
    private ExpressionCodegen.Access access(Ref[] refs) {
        return new ExpressionCodegen.Access() {
            @Override
            public Val column(int index, DataType type) {
                return switch (refs[index]) {
                    case Source s -> g.input.column(s.index(), type);
                    case Build b -> {
                        String vector = buildVector(b.probe(), b.index(), type);
                        g.use("int", b.row());
                        Rep rep = Rep.of(type);
                        String n = g.fresh("n");
                        String v = g.fresh("v");
                        String isNull = vector + ".isNull(" + b.row() + ")";
                        String value = vector + "." + rep.getter + "(" + b.row() + ")";
                        if (b.nullable()) {
                            isNull = b.row() + " < 0 || " + isNull;
                            value = b.row() + " < 0 ? " + rep.zero + " : " + value;
                        }
                        g.body.line("boolean " + n + " = " + isNull + ";");
                        g.body.line(rep.java + " " + v + " = " + value + ";");
                        yield new Val(n, v, type);
                    }
                    case Computed c -> {
                        g.use(c.value());
                        yield c.value();
                    }
                };
            }

            @Override
            public String[] vector(int index, DataType type) {
                return switch (refs[index]) {
                    case Source s -> g.input.vector(s.index(), type);
                    case Build b -> {
                        String vector = buildVector(b.probe(), b.index(), type);
                        g.use("int", b.row());
                        String isNull = vector + ".isNull(" + b.row() + ")";
                        yield new String[] {vector, b.row(), b.nullable() ? "(" + b.row() + " < 0 || " + isNull + ")"
                                : isNull};
                    }
                    case Computed c -> null;
                };
            }
        };
    }

    /** Returns the variable of a column of a build side, which is declared before the loop. */
    private String buildVector(int probe, int index, DataType type) {
        String name = "b" + probe + "_" + index;
        String vector = ExpressionCodegen.bytes(type) ? "BytesVector" : Rep.of(type).vector;
        if (!buildVectors.containsKey(name)) {
            String column = "tables[" + probe + "].batch().column(" + index + ")";
            buildVectors.put(name, vector.equals("Vector") ? "Vector " + name + " = " + column + ";"
                    : vector + " " + name + " = (" + vector + ") " + column + ";");
            g.local(name);
        }
        g.use(vector, name);
        return name;
    }

    /** Writes a condition, inline or as a method of its own; returns the expression that tells whether it holds. */
    private String select(String name, Expression condition) {
        int level = g.body.level();
        Part<String> part = g.apart(level, () -> g.select(condition));
        if (split(part)) {
            methods.add(method("private boolean " + name, part, level, "return " + part.result() + ";"));
            return name + "(" + arguments(part) + ")";
        }
        g.body.raw(part.text());
        return part.result();
    }

    private Ref[] project(int k, PlanNode.Project p, Ref[] refs) {
        g.access = access(refs);
        Ref[] out = new Ref[p.columns().size()];
        for (int j = 0; j < out.length; j++) {
            Expression e = p.columns().get(j).expression();
            if (e instanceof Expression.Column c) {
                out[j] = refs[c.index()];
                continue;
            }
            int level = g.body.level();
            Part<Val> part = g.apart(level, () -> g.gen(e));
            Val r = part.result();
            if (!split(part)) {
                g.body.raw(part.text());
                out[j] = new Computed(r);
                continue;
            }
            String name = "s" + k + "_" + j;
            String java = r.rep().java;
            g.fields.add("private boolean " + name + "n;");
            g.fields.add("private " + java + " " + name + "v;");
            methods.add(method("private void " + name, part, level, name + "n = " + r.isNull() + ";",
                    name + "v = " + r.value() + ";"));
            g.body.line(name + "(" + arguments(part) + ");");
            String n = g.fresh("n");
            String v = g.fresh("v");
            g.body.line("boolean " + n + " = " + name + "n;");
            g.body.line(java + " " + v + " = " + name + "v;");
            out[j] = new Computed(new Val(n, v, r.type()));
        }
        return out;
    }

    /**
     * Writes the probe of a join, the {@code p}-th of the chain, at stage
     * {@code k}: its candidates, its matches, and what the stages above see
     * of them; returns the columns of its output.
     */
    private Ref[] probe(int k, int p, PlanNode.Join j, Ref[] refs) {
        CodeWriter b = g.body;
        JoinType type = j.type();
        String table = "t" + p;
        String count = "count" + p;
        joinDeclarations.add("JoinTable " + table + " = tables[" + p + "];");
        g.access = access(refs);
        boolean firstOnly = j.condition() == null && !type.keepsRight();
        int keys = j.leftKeys().size();
        String keyNull = "false";
        Function<String, String> candidate;
        if (keys > 0) {
            String candidates = "candidates" + p;
            String row = "r" + p;
            String hash = "h" + p;
            joinDeclarations.add("int[] " + candidates + " = new int[16];");
            List<String> nulls = new ArrayList<>();
            List<String> hashes = new ArrayList<>();
            List<String> equal = new ArrayList<>();
            for (int x = 0; x < keys; x++) {
                int lk = j.leftKeys().get(x);
                DataType t = j.left().schema().type(lk);
                String build = buildVector(p, j.rightKeys().get(x), t);
                if (Rep.of(t) == Rep.OBJECT) {
                    String[] v = g.access.vector(lk, t);
                    nulls.add(v[2]);
                    boolean bytes = ExpressionCodegen.bytes(t);
                    hashes.add(bytes ? v[0] + ".hash(" + v[1] + ")" : "Vectors.hash(" + v[0] + ", " + v[1] + ")");
                    equal.add(bytes ? "BytesVector.equal(" + v[0] + ", " + v[1] + ", " + build + ", " + row + ")"
                            : "Vectors.compare(" + v[0] + ", " + v[1] + ", " + build + ", " + row + ") == 0");
                } else {
                    Val v = g.access.column(lk, t);
                    nulls.add(v.isNull());
                    hashes.add(KeyCodegen.valueHash(t, v.value()));
                    equal.add(KeyCodegen.valueEqual(t, v.value(), build + "." + Rep.of(t).getter + "(" + row + ")"));
                }
            }
            keyNull = or(nulls);
            b.line("int " + count + " = 0;");
            if (!keyNull.equals("true")) {
                if (!keyNull.equals("false")) {
                    b.open("if (!(" + keyNull + "))");
                }
                b.line("int " + hash + " = 17;");
                for (String h : hashes) {
                    b.line(hash + " = " + hash + " * 31 + " + h + ";");
                }
                b.line(hash + " = " + hash + " ^ (" + hash + " >>> 16);");
                b.open("for (int " + row + " = " + table + ".first(" + hash + "); " + row + " >= 0; " + row + " = "
                        + table + ".next(" + row + "))");
                b.open("if (" + table + ".hash(" + row + ") == " + hash + " && " + String.join(" && ", equal) + ")");
                append(candidates, count, row);
                if (firstOnly) {
                    b.line("break;");
                }
                b.close().close();
                if (!keyNull.equals("false")) {
                    b.close();
                }
            }
            // The chain runs from the last row to the first, against the build order.
            candidate = q -> candidates + "[" + count + " - 1 - " + q + "]";
        } else {
            b.line("int " + count + " = " + (firstOnly ? "Math.min(1, " + table + ".rowCount())"
                    : table + ".rowCount()") + ";");
            candidate = q -> q;
        }

        String matches = count;
        Function<String, String> match = candidate;
        if (j.condition() != null) {
            String hits = "hits" + p;
            String found = "matches" + p;
            String x = "x" + p;
            String c = "cj" + p;
            joinDeclarations.add("int[] " + found + " = new int[16];");
            g.local(c);
            b.line("int " + hits + " = 0;");
            b.open("for (int " + x + " = 0; " + x + " < " + count + "; " + x + "++)");
            b.line("int " + c + " = " + candidate.apply(x) + ";");
            g.access = access(concat(refs, buildRefs(p, j, c, false)));
            String pass = select("s" + k, j.condition());
            b.open("if (" + pass + ")");
            append(found, hits, c);
            b.close().close();
            matches = hits;
            match = q -> found + "[" + q + "]";
        }

        String row = "j" + p;
        String q = "q" + p;
        g.local(row);
        Ref[] out = refs;
        switch (type) {
            case INNER, CROSS, RIGHT -> {
                b.open("for (int " + q + " = 0; " + q + " < " + matches + "; " + q + "++)");
                b.line("int " + row + " = " + match.apply(q) + ";");
                if (type == JoinType.RIGHT) {
                    b.line(table + ".markMatched(" + row + ");");
                }
                nested++;
                expanding = true;
                dropping = true;
            }
            case LEFT, FULL -> {
                b.open("for (int " + q + " = 0; " + q + " < Math.max(" + matches + ", 1); " + q + "++)");
                b.line("int " + row + " = " + matches + " == 0 ? -1 : " + match.apply(q) + ";");
                if (type == JoinType.FULL) {
                    b.open("if (" + row + " >= 0)").line(table + ".markMatched(" + row + ");").close();
                }
                nested++;
                expanding = true;
            }
            case SINGLE -> {
                b.open("if (" + matches + " > 1)").line("throw new IllegalStateException(JoinTable.MORE_THAN_ONE_ROW);")
                        .close();
                b.line("int " + row + " = " + matches + " == 0 ? -1 : " + match.apply("0") + ";");
            }
            case SEMI -> {
                b.open("if (" + matches + " == 0)").line("continue;").close();
                dropping = true;
            }
            case ANTI -> {
                b.open("if (" + matches + " > 0)").line("continue;").close();
                dropping = true;
            }
            case MARK -> {
                String n = g.fresh("n");
                String v = g.fresh("v");
                b.line("boolean " + n + " = " + (keys == 0 ? "false" : matches + " == 0 && " + table
                        + ".rowCount() > 0 && (" + table + ".hasNullKey() || " + keyNull + ")") + ";");
                b.line("boolean " + v + " = " + matches + " > 0;");
                out = concat(refs, new Ref[] {new Computed(new Val(n, v, DataType.BOOLEAN))});
            }
        }
        if (type.keepsRight()) {
            boolean nullable = type == JoinType.LEFT || type == JoinType.FULL || type == JoinType.SINGLE;
            out = concat(refs, buildRefs(p, j, row, nullable));
        }
        return out;
    }

    /** Appends a row to a buffer of rows with a count, growing it when it is full. */
    private void append(String buffer, String count, String row) {
        g.body.open("if (" + count + " == " + buffer + ".length)")
                .line(buffer + " = java.util.Arrays.copyOf(" + buffer + ", " + count + " * 2);").close()
                .line(buffer + "[" + count + "++] = " + row + ";");
    }

    /** The columns of the build side of a join at a row. */
    private static Ref[] buildRefs(int probe, PlanNode.Join j, String row, boolean nullable) {
        Ref[] refs = new Ref[j.right().schema().size()];
        for (int c = 0; c < refs.length; c++) {
            refs[c] = new Build(probe, c, row, nullable);
        }
        return refs;
    }

    private static Ref[] concat(Ref[] a, Ref[] b) {
        Ref[] all = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, all, a.length, b.length);
        return all;
    }

    /** The disjunction of flags, folding the constants {@code true} and {@code false}. */
    private static String or(List<String> flags) {
        List<String> terms = new ArrayList<>();
        for (String f : flags) {
            if (f.equals("true")) {
                return "true";
            }
            if (!f.equals("false")) {
                terms.add(f);
            }
        }
        return terms.isEmpty() ? "false" : String.join(" || ", terms);
    }

    /** Tells whether code becomes a method of its own: it is long, and its parameters fit. */
    private static boolean split(Part<?> part) {
        if (part.text().length() <= SPLIT_CHARS) {
            return false;
        }
        int slots = 1;
        for (String param : part.params()) {
            slots += param.startsWith("long ") || param.startsWith("double ") ? 2 : 1;
        }
        return slots <= MAX_SLOTS;
    }

    /**
     * A method of the code of a part generated at an indentation level,
     * with the variables it reads as parameters, ending with some lines.
     */
    private static String method(String signature, Part<?> part, int level, String... tail) {
        CodeWriter m = new CodeWriter(0);
        m.open(signature + "(" + String.join(", ", part.params()) + ")");
        m.raw(CodeWriter.reindent(part.text(), level, 1));
        for (String line : tail) {
            m.line(line);
        }
        m.close();
        return m.toString();
    }

    /** The arguments of a call of the method of a part: the variables it reads. */
    private static String arguments(Part<?> part) {
        List<String> names = new ArrayList<>();
        for (String param : part.params()) {
            names.add(param.substring(param.lastIndexOf(' ') + 1));
        }
        return String.join(", ", names);
    }
}
