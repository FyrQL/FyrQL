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

import net.fyrql.core.expr.Expression;
import net.fyrql.core.expr.Expressions;
import net.fyrql.core.function.FunctionRegistry;
import net.fyrql.core.type.DataType;
import net.fyrql.core.value.EdgeValues;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * Generates typed expressions over the columns of an input: arithmetic,
 * comparisons, logic, {@code CASE}, {@code COALESCE}, casts, {@code LIKE},
 * {@code IN}, {@code BETWEEN}, and calls of the scalar functions of the
 * catalog with the argument types they accept. The nodes of
 * {@link Expression} check their types, so a combination that does not
 * fit is thrown away and drawn again.
 * <p>
 * Some arguments are drawn from lists instead: the parts of the date
 * functions, the formats of {@code strftime} and {@code strptime}, and
 * the lengths of {@code repeat}, {@code lpad} and {@code rpad}, which
 * would otherwise build huge strings. The results of the transcendental
 * functions are rounded to six places, as the math libraries of the two
 * engines may differ in the last bit; {@code random} is left out.
 */
final class ExpressionGenerator {

    /** The scalar types of columns, literals and casts. */
    static final List<DataType> TYPES = List.of(DataType.BOOLEAN, DataType.TINYINT, DataType.SMALLINT,
            DataType.INTEGER, DataType.BIGINT, DataType.HUGEINT, DataType.FLOAT, DataType.DOUBLE,
            DataType.decimal(6, 2), DataType.decimal(10, 3), DataType.decimal(18, 4), DataType.decimal(38, 10),
            DataType.VARCHAR, DataType.DATE, DataType.TIMESTAMP);

    static final List<String> PARTS = List.of("year", "yr", "month", "mon", "day", "d", "decade", "century",
            "millennium", "microseconds", "us", "milliseconds", "ms", "second", "s", "minute", "m", "hour", "h",
            "epoch", "dow", "isodow", "week", "w", "doy", "quarter", "yearweek", "isoyear", "era");

    static final List<String> FORMATS = List.of("%Y-%m-%d", "%d/%m/%y", "%H:%M:%S", "%Y%m%d %H%M", "%a %b %-d %Y",
            "%A, %B %d", "%I:%M %p", "%j", "%S.%f", "%-m/%-d/%Y %-H:%-M", "%%Y %Y");

    static final List<String> PATTERNS = List.of("%", "a%", "%a", "%a%", "_", "__", "a_", "%b_%", "A%", "%\u00DF%",
            "1%", "%.%", "");

    private static final Set<String> TRANSCENDENTAL = Set.of("ln", "log", "log10", "log2", "exp", "power", "pow");
    /** Left out: {@code random}, and the functions that build or index lists. */
    private static final Set<String> LEFT_OUT = Set.of("random", "list_value", "list_extract", "list_element",
            "array_extract");

    /** The argument types each function accepts, up to three arguments, probed in the registry. */
    private static final Map<String, List<List<DataType>>> SIGNATURES = probe();

    private final SplittableRandom random;
    private final List<DataType> columns;

    /**
     * @param random the source of randomness
     * @param columns the types of the input columns, which {@link Expression.Column} refers to by position
     */
    ExpressionGenerator(SplittableRandom random, List<DataType> columns) {
        this.random = random;
        this.columns = List.copyOf(columns);
    }

    private static Map<String, List<List<DataType>>> probe() {
        FunctionRegistry registry = Expressions.functions();
        List<DataType> probes = List.of(DataType.BOOLEAN, DataType.TINYINT, DataType.SMALLINT, DataType.INTEGER,
                DataType.BIGINT, DataType.HUGEINT, DataType.FLOAT, DataType.DOUBLE, DataType.decimal(10, 3),
                DataType.VARCHAR, DataType.DATE, DataType.TIMESTAMP);
        Map<String, List<List<DataType>>> signatures = new LinkedHashMap<>();
        for (String name : registry.names()) {
            if (LEFT_OUT.contains(name)) {
                continue;
            }
            List<List<DataType>> accepted = new ArrayList<>();
            for (int arity = 0; arity <= 3; arity++) {
                int combinations = (int) Math.pow(probes.size(), arity);
                for (int n = 0; n < combinations; n++) {
                    List<DataType> types = new ArrayList<>();
                    for (int i = 0, rest = n; i < arity; i++, rest /= probes.size()) {
                        types.add(probes.get(rest % probes.size()));
                    }
                    try {
                        registry.resolve(name, types);
                        accepted.add(types);
                    } catch (IllegalArgumentException e) {
                        // Not a signature of the function.
                    }
                }
            }
            if (!accepted.isEmpty()) {
                signatures.put(name, accepted);
            }
        }
        return signatures;
    }

    private <T> T pick(List<T> list) {
        return list.get(random.nextInt(list.size()));
    }

    private boolean chance(int percent) {
        return random.nextInt(100) < percent;
    }

    /** A random scalar type. */
    DataType type() {
        return pick(TYPES);
    }

    /** An expression of any type, at most {@code depth} levels deep. */
    Expression any(int depth) {
        for (int attempt = 0; attempt < 30; attempt++) {
            try {
                return anyOnce(depth);
            } catch (IllegalArgumentException e) {
                // The types do not fit together; draw again.
            }
        }
        return leaf();
    }

    /** A condition, at most {@code depth} levels deep. */
    Expression condition(int depth) {
        for (int attempt = 0; attempt < 30; attempt++) {
            try {
                Expression e = conditionOnce(depth);
                if (e.type() == DataType.BOOLEAN) {
                    return e;
                }
            } catch (IllegalArgumentException e) {
                // The types do not fit together; draw again.
            }
        }
        return Expressions.isNull(leaf());
    }

    /**
     * An expression of the type, or else of a type close to it: a column, a
     * literal, or a deeper expression. There are no literals of nested
     * types, which the SQL front end does not read yet.
     */
    Expression like(DataType type, int depth) {
        boolean plain = plain(type);
        int choice = random.nextInt(10);
        if (choice < 4 && plain) {
            return literal(type);
        }
        if (choice < 7) {
            List<Integer> same = new ArrayList<>();
            for (int i = 0; i < columns.size(); i++) {
                if (columns.get(i).equals(type)) {
                    same.add(i);
                }
            }
            if (!same.isEmpty()) {
                int i = pick(same);
                return Expressions.col(i, columns.get(i));
            }
        }
        for (int attempt = 0; attempt < 5; attempt++) {
            Expression e = any(depth);
            if (e.type().equals(type) || chance(30)) {
                return e;
            }
        }
        return plain ? literal(type) : any(depth);
    }

    /** Tells whether a type is scalar, a decimal included. */
    static boolean plain(DataType type) {
        return type instanceof DataType.Scalar || type instanceof DataType.Decimal;
    }

    /** A column, or a literal. */
    Expression leaf() {
        if (!columns.isEmpty() && chance(75)) {
            int i = random.nextInt(columns.size());
            return Expressions.col(i, columns.get(i));
        }
        return literal(type());
    }

    /** A literal of a scalar type: mostly a small value, sometimes {@code NULL} or an edge value. */
    Expression literal(DataType type) {
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                if (chance(8)) {
                    return Expressions.nullOf(type);
                }
                if (chance(8)) {
                    return Expressions.lit(type, pick(EdgeValues.of(type)));
                }
                return Expressions.lit(type, RandomTables.smallValue(type, random));
            } catch (IllegalArgumentException e) {
                // A small value that does not fit a narrow decimal; draw again.
            }
        }
        return Expressions.nullOf(type);
    }

    private Expression anyOnce(int depth) {
        if (depth <= 0 || chance(25)) {
            return leaf();
        }
        int d = depth - 1;
        return switch (random.nextInt(12)) {
            case 0, 1 -> arithmetic(d);
            case 2 -> Expressions.neg(any(d));
            case 3 -> chance(30) ? Expressions.tryCast(any(d), type()) : Expressions.cast(any(d), type());
            case 4, 5, 6 -> call(d);
            case 7 -> caseWhen(d);
            case 8 -> {
                Expression first = any(d);
                yield chance(50) ? Expressions.coalesce(first, like(first.type(), d))
                        : Expressions.iff(condition(d), first, like(first.type(), d));
            }
            case 9 -> condition(d);
            default -> leaf();
        };
    }

    private Expression arithmetic(int depth) {
        Expression left = any(depth);
        Expression right = like(left.type(), depth);
        return switch (random.nextInt(6)) {
            case 0 -> Expressions.add(left, right);
            case 1 -> Expressions.sub(left, right);
            case 2 -> Expressions.mul(left, right);
            case 3 -> Expressions.div(left, right);
            case 4 -> Expressions.intDiv(left, right);
            default -> Expressions.rem(left, right);
        };
    }

    private Expression caseWhen(int depth) {
        Expression first = any(depth);
        List<Expression.When> whens = new ArrayList<>();
        whens.add(Expressions.when(condition(depth), first));
        for (int i = random.nextInt(2); i > 0; i--) {
            whens.add(Expressions.when(condition(depth), like(first.type(), depth)));
        }
        boolean nullOtherwise = chance(20) && plain(first.type());
        return Expressions.caseWhen(whens, nullOtherwise ? Expressions.nullOf(first.type()) : like(first.type(), depth));
    }

    private Expression conditionOnce(int depth) {
        if (depth <= 0) {
            Expression left = leaf();
            return Expressions.cmp(pick(List.of(Expression.ComparisonOp.values())), left, like(left.type(), 0));
        }
        int d = depth - 1;
        return switch (random.nextInt(14)) {
            case 0, 1, 2 -> {
                Expression left = any(d);
                yield Expressions.cmp(pick(List.of(Expression.ComparisonOp.values())), left, like(left.type(), d));
            }
            case 3 -> Expressions.and(condition(d), condition(d));
            case 4 -> Expressions.or(condition(d), condition(d));
            case 5 -> Expressions.not(condition(d));
            case 6 -> chance(50) ? Expressions.isNull(any(d)) : Expressions.isNotNull(any(d));
            case 7 -> {
                Expression value = any(d);
                yield Expressions.between(value, like(value.type(), d), like(value.type(), d));
            }
            case 8 -> {
                Expression value = any(d);
                List<Expression> list = new ArrayList<>();
                for (int i = random.nextInt(1, 4); i > 0; i--) {
                    list.add(like(value.type(), d));
                }
                yield Expressions.in(value, list.toArray(Expression[]::new));
            }
            case 9 -> {
                Expression value = like(DataType.VARCHAR, d);
                Expression pattern = chance(80) ? Expressions.lit(pick(PATTERNS)) : like(DataType.VARCHAR, d);
                yield chance(70) ? Expressions.like(value, pattern) : Expressions.ilike(value, pattern);
            }
            case 10 -> {
                Expression left = any(d);
                yield chance(50) ? Expressions.isDistinct(left, like(left.type(), d))
                        : Expressions.isNotDistinct(left, like(left.type(), d));
            }
            case 11 -> like(DataType.BOOLEAN, d);
            default -> call(d);
        };
    }

    /** A call of a function of the catalog. */
    Expression call(int depth) {
        List<String> names = new ArrayList<>(SIGNATURES.keySet());
        String name = pick(names);
        List<DataType> signature = pick(SIGNATURES.get(name));
        List<Expression> arguments = new ArrayList<>();
        for (DataType t : signature) {
            arguments.add(like(t, depth));
        }
        switch (name) {
            case "date_part", "datepart", "date_trunc", "datetrunc", "date_diff", "datediff" ->
                    arguments.set(0, Expressions.lit(pick(PARTS)));
            case "strftime" -> arguments.set(signature.get(0) == DataType.VARCHAR ? 0 : 1,
                    Expressions.lit(pick(FORMATS)));
            case "strptime", "try_strptime" -> {
                String format = pick(FORMATS);
                arguments.set(1, Expressions.lit(format));
                if (chance(60)) {
                    Expression time = like(chance(50) ? DataType.DATE : DataType.TIMESTAMP, depth);
                    arguments.set(0, Expressions.call("strftime", time, Expressions.lit(format)));
                }
            }
            case "repeat" -> arguments.set(1, Expressions.lit(random.nextInt(-1, 4)));
            case "lpad", "rpad" -> {
                arguments.set(1, Expressions.lit(random.nextInt(-1, 9)));
                arguments.set(2, Expressions.lit(pick(List.of("", "x", "ab", "\u00DF"))));
            }
            case "round" -> {
                if (arguments.size() > 1) {
                    arguments.set(1, Expressions.lit(random.nextInt(-2, 5)));
                }
            }
            default -> {
            }
        }
        if ((name.equals("date_part") || name.equals("datepart"))
                && ((Expression.Literal) arguments.get(0)).value().equals("epoch")) {
            // A constant epoch makes date_part a DOUBLE in DuckDB, which the binder writes as epoch().
            return Expressions.call("epoch", arguments.get(1));
        }
        Expression call = Expressions.call(name, arguments.toArray(Expression[]::new));
        if (TRANSCENDENTAL.contains(name)) {
            call = Expressions.call("round", call, Expressions.lit(6));
        }
        return call;
    }
}
