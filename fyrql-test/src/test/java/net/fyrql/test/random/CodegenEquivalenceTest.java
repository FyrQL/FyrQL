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

import net.fyrql.codegen.JaninoCompiler;
import net.fyrql.core.expr.Evaluator;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.ConversionException;
import net.fyrql.core.value.Keys;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.Vector;
import net.fyrql.exec.CodegenStatistics;
import net.fyrql.exec.compiled.CompiledFilter;
import net.fyrql.exec.compiled.CompiledProjection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Random expressions and conditions over the {@linkplain RandomTables random
 * tables}, evaluated by the interpreter and by compiled code, batch by batch
 * with 16 rows and with all rows in one batch: the results must be the same
 * bit for bit, and where the interpreter fails, the compiled code must fail
 * with the same kind of error. The messages may differ, since the
 * interpreter goes operand by operand and the compiled code row by row, so
 * that another row may fail first; for the same reason, an arithmetic error
 * on one side may meet a failed conversion on the other. The test counts
 * those cases.
 * <p>
 * An expression the compiler does not take on by its kind, such as one with
 * a value of a nested type like {@code map()}, is left to the interpreter
 * in the engine as well; the test skips it and counts it. Any other
 * expression that is not compiled is a difference.
 * <p>
 * The number of expressions and the seed come from
 * {@code -Dfyrql.random.queries} and {@code -Dfyrql.random.seed}.
 */
class CodegenEquivalenceTest {

    @Test
    void compiledCodeAgreesWithTheInterpreter() {
        Map<String, List<Object[]>> tables = RandomTables.rows(RandomRun.SEED);
        JaninoCompiler compiler = new JaninoCompiler();
        List<String> failures = new ArrayList<>();
        int[] messages = {0, 0};
        int[] checked = {0};
        int skipped = 0;
        for (int q = 0; q < RandomRun.QUERIES; q++) {
            SplittableRandom random = RandomRun.random(q);
            String table = q % 3 == 0 ? "e" : q % 3 == 1 ? "p" : "q";
            Schema schema = RandomTables.SCHEMAS.get(table);
            List<Batch> small = split(schema, tables.get(table), 16);
            List<Batch> whole = split(schema, tables.get(table), Integer.MAX_VALUE);
            ExpressionGenerator g = new ExpressionGenerator(random, schema.types());
            Expression e = g.any(1 + random.nextInt(4));
            Expression condition = g.condition(1 + random.nextInt(4));
            if (!compiler.supports(e) || !compiler.supports(condition)) {
                skipped++;
                continue;
            }
            CompiledProjection p = compiler.projection(e, schema);
            CompiledFilter f = compiler.filter(condition, schema);
            if (p == null || f == null) {
                failures.add("query " + q + ": not compiled: " + (p == null ? e : condition));
                continue;
            }
            for (List<Batch> batches : List.of(small, whole)) {
                for (Batch b : batches) {
                    check(failures, messages, "query " + q + " projection " + e, p::source,
                            () -> values(Evaluator.eval(e, b)), () -> values(p.evaluate(b)));
                    check(failures, messages, "query " + q + " filter " + condition, f::source,
                            () -> Arrays.toString(Evaluator.select(condition, b)), () -> Arrays.toString(f.select(b)));
                    checked[0] += 2;
                }
            }
        }
        CodegenStatistics s = compiler.statistics();
        System.out.println("CodegenEquivalenceTest: " + RandomRun.QUERIES + " expressions and conditions, "
                + checked[0] + " batches checked, " + skipped + " skipped as not supported, " + messages[0]
                + " errors with another message, " + messages[1] + " with another error of a value; " + s);
        assertEquals(0, s.failures(), "compilations failed");
        assertTrue(failures.isEmpty(), () -> failures.size() + " differences, the first:\n"
                + String.join("\n\n", failures.subList(0, Math.min(3, failures.size()))));
    }

    private static List<Batch> split(Schema schema, List<Object[]> rows, int size) {
        List<Batch> batches = new ArrayList<>();
        for (int from = 0; from < rows.size(); from += size) {
            batches.add(Batch.ofRows(schema, rows.subList(from, (int) Math.min(rows.size(), (long) from + size))));
        }
        return batches;
    }

    /** The values of a vector with its type, doubles by their bits. */
    private static String values(Vector v) {
        StringBuilder sb = new StringBuilder(v.type().sqlName()).append(':');
        for (int i = 0; i < v.size(); i++) {
            Object value = v.get(i);
            Object shown = value instanceof Double d ? (Object) Double.doubleToLongBits(d)
                    : value instanceof Float x ? (Object) Float.floatToIntBits(x)
                    : value instanceof byte[] bytes ? Arrays.toString(bytes) : Keys.canonical(value);
            sb.append(v.isNull(i) ? "NULL" : shown).append('|');
        }
        return sb.toString();
    }

    private static void check(List<String> failures, int[] messages, String what, Supplier<String> source,
            Supplier<String> interpreted, Supplier<String> compiled) {
        String expected;
        String actual;
        RuntimeException expectedError = null;
        RuntimeException actualError = null;
        try {
            expected = interpreted.get();
        } catch (RuntimeException e) {
            expectedError = e;
            expected = "error " + e.getClass().getName();
        }
        try {
            actual = compiled.get();
        } catch (RuntimeException e) {
            actualError = e;
            actual = "error " + e.getClass().getName();
        }
        if (!expected.equals(actual) && valueError(expectedError) && valueError(actualError)) {
            messages[1]++;
        } else if (!expected.equals(actual)) {
            failures.add(what + "\n  interpreted: " + (expectedError != null ? expectedError : expected)
                    + "\n  compiled:    " + (actualError != null ? actualError : actual) + "\n" + source.get());
        } else if (expectedError != null && !String.valueOf(expectedError.getMessage())
                .equals(String.valueOf(actualError.getMessage()))) {
            messages[0]++;
        }
    }

    /** Tells whether an error is one of a value: an arithmetic error or a failed conversion. */
    private static boolean valueError(RuntimeException e) {
        return e instanceof ArithmeticException || e instanceof ConversionException;
    }
}
