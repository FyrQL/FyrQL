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

import net.fyrql.core.expr.Expression;
import net.fyrql.core.expr.Expression.ArithmeticOp;
import net.fyrql.core.expr.Expression.ComparisonOp;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.DataType.Decimal;
import net.fyrql.core.type.DataType.Scalar;
import net.fyrql.core.type.TypeRules;
import net.fyrql.core.value.Casts;
import net.fyrql.core.vector.Vectors;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Generates the class body of a compiled projection or filter: one loop
 * over the rows of a batch, which computes the expression row by row with
 * a pair of variables per value, whether it is {@code NULL} and its value.
 * <p>
 * The code mirrors the {@link net.fyrql.core.expr.Evaluator} step by step:
 * the same operands are evaluated for the same rows, in the same order and
 * cast to the same types, and the operations call the same methods of
 * {@link net.fyrql.core.expr.ScalarOps}, {@link net.fyrql.core.value.Casts}
 * and the functions, so that results and errors are the same. Only which
 * error comes first may differ when several rows fail, since the
 * interpreter goes operand by operand and the generated code row by row.
 * <p>
 * Literals, types and functions are fields that the constructor reads from
 * an array of references, so that the source of an expression does not
 * depend on the values of its literals and a cached class serves all of
 * them.
 */
final class ExpressionCodegen {

    static final String PROJECTION = "GeneratedProjection";
    static final String FILTER = "GeneratedFilter";

    /** A value in generated code: an expression (a variable or a constant) for {@code NULL} and one for the value. */
    record Val(String isNull, String value, DataType type) {
        Rep rep() {
            return Rep.of(type);
        }
    }

    /** A generated class body and the references its constructor reads; the first is the source itself. */
    record Unit(String className, String source, Object[] references) {
    }

    /**
     * Where the columns that an expression reads come from: by default the
     * vectors of the batch {@code in} at the row {@code i}; in a pipeline
     * also the values that an earlier stage computed.
     */
    interface Access {

        /** Returns the value of a column at the current row, writing the code that reads it. */
        Val column(int index, DataType type);

        /**
         * Returns a column as its vector, its row and whether it is
         * {@code NULL}, writing the code that declares the vector, or
         * {@code null} if the column is not in a vector.
         */
        String[] vector(int index, DataType type);
    }

    /**
     * Code generated apart from the body, to be inlined or to become a
     * method of its own.
     *
     * @param text   the code
     * @param params the declarations of the variables from outside that the code reads, e.g. {@code int i}
     * @param result what the generator returned
     */
    record Part<T>(String text, List<String> params, T result) {
    }

    /** The code of the loop over the rows. */
    CodeWriter body = new CodeWriter(2);
    /** The declarations of the fields; a generator may add its own. */
    final List<String> fields = new ArrayList<>();
    private final List<String> inits = new ArrayList<>();
    private final List<Object> refs = new ArrayList<>();
    private final Map<DataType, String> typeFields = new HashMap<>();
    private final Map<Integer, String> columns = new TreeMap<>();
    private final Map<Integer, String> columnTypes = new HashMap<>();
    private final Set<String> locals = new HashSet<>();
    private int names;
    private List<String> params;
    private Set<String> paramNames;

    /** The vectors of the batch {@code in} at the row {@code i}. */
    final Access input = new Access() {
        @Override
        public Val column(int index, DataType type) {
            String cv = columnVar(index, type);
            Rep rep = Rep.of(type);
            String n = fresh("n");
            String v = fresh("v");
            body.line("boolean " + n + " = " + cv + ".isNull(i);");
            body.line(rep.java + " " + v + " = " + cv + "." + rep.getter + "(i);");
            return new Val(n, v, type);
        }

        @Override
        public String[] vector(int index, DataType type) {
            String cv = columnVar(index, type);
            return new String[] {cv, "i", cv + ".isNull(i)"};
        }
    };

    /** Where the expressions read their columns. */
    Access access = input;

    ExpressionCodegen() {
        refs.add(null);
    }

    /** Tells whether an expression can be compiled: all its values are of flat types, and it is not too big. */
    static boolean supports(Expression e, int maxNodes) {
        int[] nodes = {0};
        return flat(e, nodes, maxNodes);
    }

    /** Returns the number of nodes of an expression. */
    static int size(Expression e) {
        int nodes = 1;
        for (Expression child : e.children()) {
            nodes += size(child);
        }
        return nodes;
    }

    private static boolean flat(Expression e, int[] nodes, int maxNodes) {
        if (++nodes[0] > maxNodes || !Rep.flat(e.type())) {
            return false;
        }
        if (e instanceof Expression.Call c) {
            for (DataType t : c.function().coerce(c.argumentTypes())) {
                if (!Rep.flat(t)) {
                    return false;
                }
            }
        }
        for (Expression child : e.children()) {
            if (!flat(child, nodes, maxNodes)) {
                return false;
            }
        }
        return true;
    }

    /** Generates a {@link net.fyrql.exec.spi.CompiledProjection} of an expression. */
    static Unit projection(Expression e) {
        ExpressionCodegen g = new ExpressionCodegen();
        Val result = g.gen(e);
        Rep rep = result.rep();
        String type = g.typeField(e.type());
        CodeWriter w = new CodeWriter(0);
        g.header(w, PROJECTION);
        w.open("public Vector evaluate(Batch in)");
        w.line("int n = in.rowCount();");
        g.columnDeclarations(w);
        if (rep == Rep.OBJECT) {
            w.line("Vector.Builder out = Vectors.builder(" + type + ", n);");
        } else {
            w.line(rep.array + " out = new " + rep.array.replace("[]", "[n]") + ";");
            w.line("boolean[] nulls = null;");
        }
        w.open("for (int i = 0; i < n; i++)");
        StringBuilder loop = new StringBuilder(g.body.toString());
        CodeWriter store = new CodeWriter(2);
        if (rep == Rep.OBJECT) {
            store.open("if (" + result.isNull() + ")").line("out.appendNull();").reopen("else")
                    .line("out.append(" + result.value() + ");").close();
        } else {
            store.open("if (" + result.isNull() + ")").open("if (nulls == null)").line("nulls = new boolean[n];")
                    .close().line("nulls[i] = true;").reopen("else").line("out[i] = " + result.value() + ";").close();
        }
        w.raw(loop.append(store).toString());
        w.close();
        w.line(switch (rep) {
            case OBJECT -> "return out.build();";
            case INT, LONG -> "return " + rep.vector + ".wrap(" + type + ", out, nulls);";
            case DECIMAL -> "return DecimalVector.wrap(" + type + ", out, nulls);";
            default -> "return " + rep.vector + ".wrap(out, nulls);";
        });
        w.close();
        return g.unit(w, PROJECTION);
    }

    /** Generates a {@link net.fyrql.exec.spi.CompiledFilter} of a condition. */
    static Unit filter(Expression condition) {
        ExpressionCodegen g = new ExpressionCodegen();
        String pass = g.select(condition);
        CodeWriter w = new CodeWriter(0);
        g.header(w, FILTER);
        w.open("public int[] select(Batch in)");
        w.line("int n = in.rowCount();");
        g.columnDeclarations(w);
        w.line("int[] rows = new int[n];");
        w.line("int m = 0;");
        w.open("for (int i = 0; i < n; i++)");
        CodeWriter keep = new CodeWriter(2);
        keep.open("if (" + pass + ")").line("rows[m++] = i;").close();
        w.raw(g.body.toString() + keep);
        w.close();
        w.line("return m == n ? rows : java.util.Arrays.copyOf(rows, m);");
        w.close();
        return g.unit(w, FILTER);
    }

    void header(CodeWriter w, String className) {
        w.line("private final String source;");
        for (String f : fields) {
            w.line(f);
        }
        w.blank();
        w.open("public " + className + "(Object[] refs)");
        w.line("source = (String) refs[0];");
        for (String init : inits) {
            w.line(init);
        }
        w.close();
        w.blank();
        w.open("public String source()");
        w.line("return source;");
        w.close();
        w.blank();
    }

    void columnDeclarations(CodeWriter w) {
        for (String declaration : columns.values()) {
            w.line(declaration);
        }
    }

    Unit unit(CodeWriter w, String className) {
        String source = w.toString();
        Object[] references = refs.toArray();
        references[0] = source;
        return new Unit(className, source, references);
    }

    // ---- references

    private String field(String javaType, String init, Object value) {
        String name = "f" + (refs.size() - 1);
        int k = refs.size();
        refs.add(value);
        fields.add("private final " + javaType + " " + name + ";");
        inits.add(name + " = " + init.replace("#", "refs[" + k + "]") + ";");
        return name;
    }

    String objectField(String javaType, Object value) {
        return field(javaType, "(" + javaType + ") #", value);
    }

    String typeField(DataType type) {
        String name = typeFields.get(type);
        if (name == null) {
            name = objectField(type instanceof Decimal ? "DataType.Decimal" : "DataType", type);
            typeFields.put(type, name);
        }
        return name;
    }

    private String columnVar(int index, DataType type) {
        String name = "c" + index;
        if (!columns.containsKey(index)) {
            Rep rep = Rep.of(type);
            String vector = bytes(type) ? "BytesVector" : rep.vector;
            columns.put(index, vector.equals("Vector") ? "Vector " + name + " = in.column(" + index + ");"
                    : vector + " " + name + " = (" + vector + ") in.column(" + index + ");");
            columnTypes.put(index, vector);
            locals.add(name);
        }
        use("int", "i");
        use(columnTypes.get(index), name);
        return name;
    }

    /** Declares a name as a local variable, which code generated {@link #apart} takes as a parameter. */
    void local(String name) {
        locals.add(name);
    }

    /**
     * Records that code generated {@link #apart} reads a variable from
     * outside, unless the name is a field or a constant.
     */
    void use(String javaType, String name) {
        if (params != null && (locals.contains(name) || name.equals("i")) && paramNames.add(name)) {
            params.add(javaType + " " + name);
        }
    }

    /** Records that code generated {@link #apart} reads a value from outside. */
    void use(Val v) {
        use("boolean", v.isNull());
        use(v.rep().java, v.value());
    }

    /**
     * Generates code apart from the body, at an indentation level, and
     * records the variables from outside it reads; the body is unchanged.
     */
    <T> Part<T> apart(int level, Supplier<T> generator) {
        CodeWriter savedBody = body;
        List<String> savedParams = params;
        Set<String> savedNames = paramNames;
        body = new CodeWriter(level);
        params = new ArrayList<>();
        paramNames = new HashSet<>();
        try {
            T result = generator.get();
            return new Part<>(body.toString(), params, result);
        } finally {
            body = savedBody;
            params = savedParams;
            paramNames = savedNames;
        }
    }

    static boolean bytes(DataType type) {
        return type == DataType.VARCHAR || type == DataType.BLOB;
    }

    // ---- values

    /**
     * Returns a fresh name for a local variable: the prefix and a number,
     * numbered in the order of the requests, so that the same expression
     * always gives the same source.
     */
    String fresh(String prefix) {
        String name = prefix + names++;
        locals.add(name);
        return name;
    }

    private Val declare(DataType type) {
        Rep rep = Rep.of(type);
        String n = fresh("n");
        String v = fresh("v");
        body.line("boolean " + n + " = false;");
        body.line(rep.java + " " + v + " = " + rep.zero + ";");
        return new Val(n, v, type);
    }

    /** The disjunction of flags, folding the constants {@code true} and {@code false}. */
    private static String or(String... flags) {
        StringBuilder sb = new StringBuilder();
        for (String f : flags) {
            if (f.equals("true")) {
                return "true";
            }
            if (!f.equals("false")) {
                sb.append(sb.isEmpty() ? "" : " || ").append(f);
            }
        }
        return sb.isEmpty() ? "false" : sb.toString();
    }

    /** Copies a value into the variables of another. */
    private void set(Val target, Val source) {
        if (source.isNull().equals("true")) {
            body.line(target.isNull() + " = true;");
            return;
        }
        if (!source.isNull().equals("false")) {
            body.line(target.isNull() + " = " + source.isNull() + ";");
        }
        body.line(target.value() + " = " + source.value() + ";");
    }

    Val gen(Expression e) {
        return switch (e) {
            case Expression.Column c -> column(c);
            case Expression.Literal l -> literal(l);
            case Expression.Arithmetic a -> arithmetic(a);
            case Expression.Negate n -> negate(n);
            case Expression.Comparison c -> comparison(c);
            case Expression.And a -> and(a);
            case Expression.Or o -> or(o);
            case Expression.Not n -> not(n);
            case Expression.IsNull n -> isNull(n);
            case Expression.Between b -> between(b);
            case Expression.In i -> in(i);
            case Expression.Case c -> caseWhen(c);
            case Expression.If i -> caseWhen(new Expression.Case(List.of(new Expression.When(i.condition(),
                    i.whenTrue())), i.whenFalse()));
            case Expression.Coalesce c -> coalesce(c);
            case Expression.Cast c -> castOf(c.operand(), c.target(), c.safe());
            case Expression.Like l -> like(l);
            case Expression.IsDistinct d -> isDistinct(d);
            case Expression.Call c -> call(c);
        };
    }

    private Val column(Expression.Column c) {
        return access.column(c.index(), c.type());
    }

    private Val literal(Expression.Literal l) {
        Rep rep = Rep.of(l.type());
        if (l.value() == null) {
            return new Val("true", rep.zero, l.type());
        }
        String name = switch (rep) {
            case OBJECT -> objectField("Object", l.value());
            case DECIMAL -> field("long", "((Long) #).longValue()",
                    ((BigDecimal) l.value()).setScale(((Decimal) l.type()).scale()).unscaledValue().longValueExact());
            default -> field(rep.java, rep.unbox("#", null), l.value());
        };
        return new Val("false", name, l.type());
    }

    /**
     * Generates an expression cast to a type. A literal whose cast is more
     * than a Java conversion is cast here, once, into a literal of the type,
     * as the interpreter casts it once per batch; if that fails, the cast
     * stays in the generated code, so that it fails only when a row
     * reaches it.
     */
    private Val castOf(Expression e, DataType to, boolean safe) {
        DataType from = e.type();
        if (e instanceof Expression.Literal l && !from.equals(to) && !widening(from, to)) {
            Expression.Literal folded = null;
            try {
                folded = new Expression.Literal(to, safe ? Casts.tryCast(l.value(), from, to)
                        : Casts.cast(l.value(), from, to));
            } catch (RuntimeException failed) {
                // The cast fails for the rows that reach it.
            }
            if (folded != null) {
                return gen(folded);
            }
        }
        return cast(gen(e), from, to, safe);
    }

    /** Casts as {@link net.fyrql.core.expr.Evaluator#cast} or {@code tryCast} does. */
    private Val cast(Val v, DataType from, DataType to, boolean safe) {
        if (from.equals(to)) {
            return v;
        }
        Rep tr = Rep.of(to);
        if (widening(from, to)) {
            String w = fresh("v");
            body.line(tr.java + " " + w + " = (" + tr.java + ") " + v.value() + ";");
            return new Val(v.isNull(), w, to);
        }
        Val r = declare(to);
        String o = fresh("o");
        body.open("if (" + v.isNull() + ")").line(r.isNull() + " = true;").reopen("else");
        if (safe) {
            body.open("try");
        }
        body.line("Object " + o + " = Casts.cast(" + v.rep().box(v.value(), from) + ", " + typeField(from) + ", "
                + typeField(to) + ");");
        body.open("if (" + o + " == null)").line(r.isNull() + " = true;").reopen("else")
                .line(r.value() + " = " + tr.unbox(o, typeFieldIfDecimal(to)) + ";").close();
        if (safe) {
            body.reopen("catch (ArithmeticException " + fresh("e") + ")").line(r.isNull() + " = true;");
            body.reopen("catch (ConversionException " + fresh("e") + ")").line(r.isNull() + " = true;");
            body.close();
        }
        body.close();
        return r;
    }

    private String typeFieldIfDecimal(DataType type) {
        return type instanceof Decimal ? typeField(type) : null;
    }

    /** A cast whose result is the Java conversion of the value, as {@link net.fyrql.core.value.Casts} computes it. */
    private static boolean widening(DataType from, DataType to) {
        int f = integralRank(from);
        int t = integralRank(to);
        if (f > 0) {
            return t >= f || to == DataType.FLOAT || to == DataType.DOUBLE;
        }
        if (from == DataType.FLOAT) {
            return to == DataType.DOUBLE;
        }
        return from instanceof Decimal x && to instanceof Decimal y && x.fitsLong() && y.fitsLong()
                && x.scale() == y.scale() && y.precision() >= x.precision();
    }

    private static int integralRank(DataType type) {
        if (!(type instanceof Scalar s)) {
            return 0;
        }
        return switch (s) {
            case TINYINT -> 1;
            case SMALLINT -> 2;
            case INTEGER -> 3;
            case BIGINT -> 4;
            default -> 0;
        };
    }

    private Val arithmetic(Expression.Arithmetic a) {
        if (a.left().type() == DataType.DATE || a.right().type() == DataType.DATE) {
            return dateArithmetic(a);
        }
        DataType resultType = a.type();
        ArithmeticOp op = a.op();
        DataType operandType;
        DataType leftType;
        DataType rightType;
        if (op == ArithmeticOp.DIVIDE || resultType.isFloatingPoint()
                || op == ArithmeticOp.REMAINDER && resultType instanceof Decimal) {
            operandType = resultType;
            leftType = resultType;
            rightType = resultType;
        } else if (op == ArithmeticOp.MULTIPLY && resultType instanceof Decimal) {
            operandType = resultType;
            leftType = TypeRules.asDecimal(a.left().type());
            rightType = TypeRules.asDecimal(a.right().type());
        } else {
            operandType = TypeRules.numeric(a.left().type(), a.right().type());
            leftType = operandType;
            rightType = operandType;
        }
        Val l = castOf(a.left(), leftType, false);
        Val r = castOf(a.right(), rightType, false);
        Val res = declare(resultType);
        String opRef = "Expression.ArithmeticOp." + op.name();
        Rep rep = Rep.of(operandType);
        body.open("if (" + or(l.isNull(), r.isNull()) + ")").line(res.isNull() + " = true;");
        boolean byZeroIsNull = op == ArithmeticOp.INT_DIVIDE || op == ArithmeticOp.REMAINDER;
        if (rep.integral()) {
            if (byZeroIsNull) {
                body.reopen("else if (ScalarOps.nullDivisor(" + opRef + ", (long) " + r.value() + "))")
                        .line(res.isNull() + " = true;");
            }
            body.reopen("else")
                    .line(res.value() + " = (" + res.rep().java + ") ScalarOps.integral(" + opRef + ", " + l.value()
                            + ", " + r.value() + ", " + typeField(resultType) + ");");
        } else if (rep == Rep.FLOAT || rep == Rep.DOUBLE) {
            if (op == ArithmeticOp.INT_DIVIDE) {
                body.reopen("else if (ScalarOps.nullDivisor(" + opRef + ", (double) " + r.value() + "))")
                        .line(res.isNull() + " = true;");
            }
            body.reopen("else")
                    .line(res.value() + " = (" + res.rep().java + ") ScalarOps.floating(" + opRef + ", " + l.value()
                            + ", " + r.value() + ");");
        } else if (resultType instanceof Decimal rt && rt.fitsLong() && leftType instanceof Decimal lt
                && lt.fitsLong() && rightType instanceof Decimal rgt && rgt.fitsLong()
                && (op == ArithmeticOp.MULTIPLY ? lt.scale() + rgt.scale() == rt.scale()
                        : lt.scale() == rt.scale() && rgt.scale() == rt.scale())) {
            if (byZeroIsNull) {
                body.reopen("else if (ScalarOps.nullDivisor(" + opRef + ", " + r.value() + "))")
                        .line(res.isNull() + " = true;");
            }
            body.reopen("else")
                    .line(res.value() + " = ScalarOps.decimal(" + opRef + ", " + l.value() + ", " + r.value() + ", "
                            + typeField(resultType) + ");");
        } else {
            String o = fresh("o");
            body.reopen("else")
                    .line("Object " + o + " = ScalarOps.arithmetic(" + opRef + ", " + l.rep().box(l.value(), leftType)
                            + ", " + r.rep().box(r.value(), rightType) + ", " + typeField(operandType) + ", "
                            + typeField(resultType) + ");")
                    .open("if (" + o + " == null)").line(res.isNull() + " = true;").reopen("else")
                    .line(res.value() + " = " + res.rep().unbox(o, typeFieldIfDecimal(resultType)) + ";").close();
        }
        body.close();
        return res;
    }

    private Val dateArithmetic(Expression.Arithmetic a) {
        Val l = gen(a.left());
        Val r = gen(a.right());
        Val res = declare(a.type());
        body.open("if (" + or(l.isNull(), r.isNull()) + ")").line(res.isNull() + " = true;").reopen("else");
        if (a.left().type() == DataType.DATE && a.right().type() == DataType.DATE) {
            body.line(res.value() + " = (long) " + l.value() + " - (long) " + r.value() + ";");
        } else {
            body.line(res.value() + " = ScalarOps.shiftDate(Expression.ArithmeticOp." + a.op().name() + ", (long) "
                    + l.value() + ", (long) " + r.value() + ");");
        }
        body.close();
        return res;
    }

    private Val negate(Expression.Negate n) {
        Val v = gen(n.operand());
        DataType t = n.type();
        Rep rep = Rep.of(t);
        Val res = declare(t);
        body.open("if (" + v.isNull() + ")").line(res.isNull() + " = true;").reopen("else");
        body.line(res.value() + " = " + switch (rep) {
            case BYTE, SHORT, INT, LONG -> "(" + rep.java + ") ScalarOps.negate((long) " + v.value() + ", "
                    + typeField(t) + ")";
            case FLOAT, DOUBLE, DECIMAL -> "-" + v.value();
            default -> "ScalarOps.negate(" + v.value() + ", " + typeField(t) + ")";
        } + ";");
        body.close();
        return res;
    }

    /** Code that tells whether two values of a type, neither {@code NULL}, satisfy a comparison. */
    private String compare(ComparisonOp op, String a, String b, DataType type) {
        String sym = switch (op) {
            case EQ -> "==";
            case NE -> "!=";
            case LT -> "<";
            case LE -> "<=";
            case GT -> ">";
            case GE -> ">=";
        };
        return switch (Rep.of(type)) {
            case BYTE, SHORT, INT, LONG, DECIMAL -> a + " " + sym + " " + b;
            case BOOLEAN -> "Boolean.compare(" + a + ", " + b + ") " + sym + " 0";
            case FLOAT, DOUBLE -> "Vectors.compare(" + a + ", " + b + ") " + sym + " 0";
            case OBJECT -> "Order.compare(" + a + ", " + b + ", " + typeField(type) + ") " + sym + " 0";
        };
    }

    private Val comparison(Expression.Comparison c) {
        DataType t = TypeRules.common(c.left().type(), c.right().type());
        String[] x = bytesOperand(c.left(), t);
        String[] y = bytesOperand(c.right(), t);
        if (x != null && y != null) {
            // Strings as bytes, without making Java strings, as the vectors compare them.
            String n = fresh("n");
            String v = fresh("v");
            body.line("boolean " + n + " = " + or(x[2], y[2]) + ";");
            body.line("boolean " + v + " = !" + n + " && " + compare(c.op(), "BytesVector.compare(" + x[0] + ", "
                    + x[1] + ", " + y[0] + ", " + y[1] + ")", "0", DataType.INTEGER) + ";");
            return new Val(n, v, DataType.BOOLEAN);
        }
        Val l = castOf(c.left(), t, false);
        Val r = castOf(c.right(), t, false);
        Val res = declare(DataType.BOOLEAN);
        body.open("if (" + or(l.isNull(), r.isNull()) + ")").line(res.isNull() + " = true;").reopen("else")
                .line(res.value() + " = " + compare(c.op(), l.value(), r.value(), t) + ";").close();
        return res;
    }

    /**
     * A string column or a string literal of the type of a comparison, as a
     * vector, a row and whether it is {@code NULL}; {@code null} for any
     * other operand.
     */
    private String[] bytesOperand(Expression e, DataType type) {
        if (!bytes(type) || !e.type().equals(type)) {
            return null;
        }
        if (e instanceof Expression.Column c) {
            return access.vector(c.index(), c.type());
        }
        if (e instanceof Expression.Literal l && l.value() != null) {
            return new String[] {objectField("BytesVector", Vectors.of(type, l.value())), "0", "false"};
        }
        return null;
    }

    private Val and(Expression.And a) {
        Val l = gen(a.left());
        Val res = declare(DataType.BOOLEAN);
        body.open("if (!" + l.isNull() + " && !" + l.value() + ")").line(res.value() + " = false;").reopen("else");
        Val r = gen(a.right());
        body.open("if (!" + r.isNull() + " && !" + r.value() + ")").line(res.value() + " = false;")
                .reopen("else if (" + or(l.isNull(), r.isNull()) + ")").line(res.isNull() + " = true;")
                .reopen("else").line(res.value() + " = true;").close();
        body.close();
        return res;
    }

    private Val or(Expression.Or o) {
        Val l = gen(o.left());
        Val res = declare(DataType.BOOLEAN);
        body.open("if (!" + l.isNull() + " && " + l.value() + ")").line(res.value() + " = true;").reopen("else");
        Val r = gen(o.right());
        body.open("if (!" + r.isNull() + " && " + r.value() + ")").line(res.value() + " = true;")
                .reopen("else if (" + or(l.isNull(), r.isNull()) + ")").line(res.isNull() + " = true;")
                .reopen("else").line(res.value() + " = false;").close();
        body.close();
        return res;
    }

    private Val not(Expression.Not n) {
        Val v = gen(n.operand());
        String r = fresh("v");
        body.line("boolean " + r + " = !" + v.value() + ";");
        return new Val(v.isNull(), r, DataType.BOOLEAN);
    }

    private Val isNull(Expression.IsNull n) {
        Val v = gen(n.operand());
        String r = fresh("v");
        body.line("boolean " + r + " = " + (n.negated() ? "!" : "") + v.isNull() + ";");
        return new Val("false", r, DataType.BOOLEAN);
    }

    private Val between(Expression.Between b) {
        DataType t = TypeRules.common(List.of(b.value().type(), b.low().type(), b.high().type()));
        Val v = castOf(b.value(), t, false);
        Val lo = castOf(b.low(), t, false);
        Val hi = castOf(b.high(), t, false);
        String geNull = fresh("n");
        String ge = fresh("v");
        String leNull = fresh("n");
        String le = fresh("v");
        body.line("boolean " + geNull + " = " + or(v.isNull(), lo.isNull()) + ";");
        body.line("boolean " + ge + " = !" + geNull + " && " + compare(ComparisonOp.GE, v.value(), lo.value(), t)
                + ";");
        body.line("boolean " + leNull + " = " + or(v.isNull(), hi.isNull()) + ";");
        body.line("boolean " + le + " = !" + leNull + " && " + compare(ComparisonOp.LE, v.value(), hi.value(), t)
                + ";");
        Val res = declare(DataType.BOOLEAN);
        body.open("if (!" + geNull + " && !" + ge + " || !" + leNull + " && !" + le + ")")
                .line(res.value() + " = false;").reopen("else if (" + geNull + " || " + leNull + ")")
                .line(res.isNull() + " = true;").reopen("else").line(res.value() + " = true;").close();
        return res;
    }

    private Val in(Expression.In in) {
        Val v = gen(in.value());
        String matched = fresh("v");
        String sawNull = fresh("v");
        body.line("boolean " + matched + " = false;");
        body.line("boolean " + sawNull + " = false;");
        for (Expression element : in.list()) {
            // As the interpreter: every element for every row, after the value is cast to the common type.
            DataType t = TypeRules.common(in.value().type(), element.type());
            Val l = cast(v, in.value().type(), t, false);
            Val r = castOf(element, t, false);
            body.open("if (!" + matched + " && !" + v.isNull() + ")")
                    .open("if (" + r.isNull() + ")").line(sawNull + " = true;")
                    .reopen("else if (" + compare(ComparisonOp.EQ, l.value(), r.value(), t) + ")")
                    .line(matched + " = true;").close()
                    .close();
        }
        String n = fresh("n");
        body.line("boolean " + n + " = " + v.isNull() + " || !" + matched + " && " + sawNull + ";");
        return new Val(n, matched, DataType.BOOLEAN);
    }

    private Val caseWhen(Expression.Case c) {
        DataType t = c.type();
        Val res = declare(t);
        int open = 0;
        for (Expression.When w : c.whens()) {
            Val cond = gen(w.condition());
            body.open("if (!" + cond.isNull() + " && " + cond.value() + ")");
            set(res, castOf(w.result(), t, false));
            body.reopen("else");
            open++;
        }
        set(res, castOf(c.otherwise(), t, false));
        for (int i = 0; i < open; i++) {
            body.close();
        }
        return res;
    }

    private Val coalesce(Expression.Coalesce c) {
        DataType t = c.type();
        Val res = declare(t);
        int open = 0;
        for (Expression operand : c.operands()) {
            Val o = castOf(operand, t, false);
            body.open("if (!" + o.isNull() + ")").line(res.value() + " = " + o.value() + ";").reopen("else");
            open++;
        }
        body.line(res.isNull() + " = true;");
        for (int i = 0; i < open; i++) {
            body.close();
        }
        return res;
    }

    private Val like(Expression.Like l) {
        Val s = gen(l.string());
        Val p = gen(l.pattern());
        Val res = declare(DataType.BOOLEAN);
        String str = "(String) " + s.value();
        String pat = "(String) " + p.value();
        body.open("if (" + or(s.isNull(), p.isNull()) + ")").line(res.isNull() + " = true;").reopen("else")
                .line(res.value() + " = " + (l.caseInsensitive()
                        ? "Strings.like(Strings.lower(" + str + "), Strings.lower(" + pat + "));"
                        : "Strings.like(" + str + ", " + pat + ");"))
                .close();
        return res;
    }

    private Val isDistinct(Expression.IsDistinct d) {
        DataType t = TypeRules.common(d.left().type(), d.right().type());
        Val l = castOf(d.left(), t, false);
        Val r = castOf(d.right(), t, false);
        String v = fresh("v");
        body.line("boolean " + v + ";");
        body.open("if (" + or(l.isNull(), r.isNull()) + ")")
                .line(v + " = " + l.isNull() + " != " + r.isNull() + ";").reopen("else")
                .line(v + " = !(" + compare(ComparisonOp.EQ, l.value(), r.value(), t) + ");").close();
        String res = fresh("v");
        body.line("boolean " + res + " = " + v + " != " + d.negated() + ";");
        return new Val("false", res, DataType.BOOLEAN);
    }

    private Val call(Expression.Call c) {
        List<DataType> types = c.function().coerce(c.argumentTypes());
        String function = objectField("ScalarFunction", c.function());
        String typeList = objectField("java.util.List", types);
        Val res = declare(c.type());
        String o = fresh("o");
        if (!c.function().readsArguments()) {
            body.line("Object " + o + " = " + function + ".evaluate(" + typeList + ", new Object[" + types.size()
                    + "]);");
        } else {
            List<Val> args = new ArrayList<>();
            for (int i = 0; i < c.arguments().size(); i++) {
                Expression a = c.arguments().get(i);
                args.add(cast(gen(a), a.type(), types.get(i), false));
            }
            String values = fresh("a");
            StringBuilder array = new StringBuilder("Object[] " + values + " = new Object[] {");
            List<String> nulls = new ArrayList<>();
            for (int i = 0; i < args.size(); i++) {
                Val a = args.get(i);
                array.append(i > 0 ? ", " : "").append(a.isNull()).append(" ? null : ")
                        .append(a.rep().box(a.value(), a.type()));
                nulls.add(a.isNull());
            }
            body.line(array.append("};").toString());
            body.line("Object " + o + ";");
            if (c.function().nullOnNullInput()) {
                body.open("if (" + or(nulls.toArray(new String[0])) + ")").line(o + " = null;").reopen("else");
                body.line(o + " = " + function + ".evaluate(" + typeList + ", " + values + ");");
                body.close();
            } else {
                body.line(o + " = " + function + ".evaluate(" + typeList + ", " + values + ");");
            }
        }
        body.open("if (" + o + " == null)").line(res.isNull() + " = true;").reopen("else")
                .line(res.value() + " = " + res.rep().unbox(o, typeFieldIfDecimal(c.type())) + ";").close();
        return res;
    }

    // ---- filters

    /**
     * Generates the selection of {@link net.fyrql.core.expr.Evaluator#select}:
     * returns a variable that is {@code true} where the condition is
     * {@code TRUE}, evaluating the right operand of {@code AND} only where
     * the left one is {@code TRUE}, the right operand of {@code OR} only
     * where the left one is not, and {@code BETWEEN} as its lower
     * comparison followed by its upper one.
     */
    String select(Expression condition) {
        switch (condition) {
            case Expression.And a -> {
                String pass = fresh("p");
                body.line("boolean " + pass + " = false;");
                String left = select(a.left());
                body.open("if (" + left + ")");
                body.line(pass + " = " + select(a.right()) + ";");
                body.close();
                return pass;
            }
            case Expression.Or o -> {
                String pass = fresh("p");
                body.line("boolean " + pass + " = true;");
                String left = select(o.left());
                body.open("if (!" + left + ")");
                body.line(pass + " = " + select(o.right()) + ";");
                body.close();
                return pass;
            }
            case Expression.Between b -> {
                DataType t = TypeRules.common(List.of(b.value().type(), b.low().type(), b.high().type()));
                Expression value = as(b.value(), t);
                return select(new Expression.And(new Expression.Comparison(ComparisonOp.GE, value, as(b.low(), t)),
                        new Expression.Comparison(ComparisonOp.LE, value, as(b.high(), t))));
            }
            default -> {
                Val v = gen(condition);
                String pass = fresh("p");
                body.line("boolean " + pass + " = !" + v.isNull() + " && " + v.value() + ";");
                return pass;
            }
        }
    }

    private static Expression as(Expression e, DataType type) {
        return e.type().equals(type) ? e : new Expression.Cast(e, type);
    }
}
