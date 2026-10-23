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
import net.fyrql.connector.Sink;
import net.fyrql.connector.Writer;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.Plans;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.Conversions;
import net.fyrql.core.value.Interval;
import net.fyrql.exec.CodegenMode;
import net.fyrql.exec.Executor;
import net.fyrql.sql.SqlException;
import net.fyrql.sql.render.DuckDbDialect;
import net.fyrql.sql.render.SqlRenderer;
import net.fyrql.test.DuckDbReference;
import net.fyrql.test.ResultComparison;
import net.fyrql.test.Rows;
import net.fyrql.test.WrittenFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The results of random plans, written with {@code COPY ... TO} into a CSV,
 * a JSON and a Parquet file: DuckDB reads each file and gets the rows of
 * the query, in their order if the plan sorts its output, and
 * {@code COPY ... FROM} loads each file into a table of the memory source
 * with the columns of the result, which then has the rows of the query; an
 * empty string comes back from a CSV file as {@code NULL}. The columns are
 * renamed {@code c0}, {@code c1} and so on, since a result may have two
 * columns of the same name. A plan that fails in FyrQL is left out, and so
 * is a Parquet file of a result with a {@code HUGEINT} of 39 digits, which
 * a {@code DECIMAL(38,0)} does not hold, or with a negative part of an
 * interval, which Parquet does not hold either, and the load of a CSV file of a
 * result with nested columns, which CSV does not hold; the report counts
 * them. The engine has only the memory source registered, the sources of
 * the formats come with their modules. No temporary file is left behind.
 */
class WriteRoundTripTest {

    private static final List<String> FORMATS = List.of("csv", "json", "parquet");

    private final SqlRenderer renderer = new SqlRenderer(DuckDbDialect.INSTANCE,
            t -> DuckDbDialect.INSTANCE.quoteIdentifier(t.table()));

    @TempDir
    Path dir;

    @Test
    void duckDbAndCopyFromReadTheRowsOfRandomQueriesFromTheirFiles() throws IOException {
        int checked = 0;
        int failed = 0;
        int tooLarge = 0;
        int nestedInCsv = 0;
        long rowsWritten = 0;
        List<String> unexplained = new ArrayList<>();
        try (FyrQL engine = RandomTables.engine(RandomTables.rows(RandomRun.SEED), CodegenMode.OFF,
                Executor.threadsFromSystemProperty());
                DuckDbReference duck = RandomTables.duck()) {
            for (int i = 0; i < RandomRun.QUERIES; i++) {
                PlanNode plan = renamed(new PlanGenerator(RandomRun.random(i)).plan(3));
                boolean ordered = Plans.ordered(plan);
                String sql = renderer.render(plan);
                Schema schema;
                List<Object[]> rows;
                try (QueryResult r = engine.sql(sql)) {
                    schema = r.schema();
                    // In the form of vectors, which asWritten and the readers take.
                    rows = Rows.internal(schema, r.rows());
                } catch (RuntimeException | StackOverflowError e) {
                    failed++;
                    continue;
                }
                for (String format : FORMATS) {
                    Path file = dir.resolve("result." + format);
                    long count;
                    try {
                        count = engine.update("COPY (" + sql + ") TO '" + WrittenFiles.path(file) + "'");
                    } catch (SqlException e) {
                        if (format.equals("parquet") && (e.getMessage().contains(" has more than 38 digits; a Parquet"
                                + " file holds a HUGEINT as a DECIMAL(38,0)")
                                || e.getMessage().startsWith("Parquet files do not support negative intervals"))) {
                            tooLarge++;
                            continue;
                        }
                        unexplained.add("query " + i + " to " + format + ": " + e.getMessage() + "\n  sql: " + sql);
                        continue;
                    } catch (RuntimeException e) {
                        unexplained.add("query " + i + " to " + format + ": " + e + "\n  sql: " + sql);
                        continue;
                    }
                    if (count != rows.size()) {
                        unexplained.add("query " + i + " to " + format + ": " + count + " rows written, the query"
                                + " gives " + rows.size() + "\n  sql: " + sql);
                        continue;
                    }
                    String report;
                    List<Object[]> written = asWritten(format, schema, rows);
                    try {
                        ResultComparison.Outcome same = WrittenFiles.duckDb(duck, file, format, schema, written)
                                .compare(ordered);
                        report = same.same() ? null : same.report();
                    } catch (RuntimeException e) {
                        report = e.getMessage() + (e.getCause() == null ? "" : ": " + e.getCause().getMessage());
                    }
                    if (report != null) {
                        unexplained.add("query " + i + " to " + format + ":\n" + report.indent(2) + "  sql: " + sql);
                        continue;
                    }
                    if (format.equals("csv") && schema.fields().stream().anyMatch(f -> f.type().isNested())) {
                        nestedInCsv++;
                    } else {
                        report = loaded(engine, file, format, schema, written);
                        if (report != null) {
                            unexplained.add("query " + i + " from " + format + ":\n" + report.indent(2) + "  sql: "
                                    + sql);
                            continue;
                        }
                    }
                    rowsWritten += count;
                }
                checked++;
            }
        }
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of(), files.map(f -> f.getFileName().toString()).filter(f -> f.endsWith(".tmp"))
                    .toList(), "temporary files");
        }
        String report = "Random results written: " + RandomRun.QUERIES + " queries, seed " + RandomRun.SEED + ", "
                + checked + " results in " + FORMATS.size() + " formats with " + rowsWritten + " rows read back, "
                + failed + " failed in FyrQL, " + tooLarge + " not in Parquet with a HUGEINT of 39 digits or a"
                + " negative interval, "
                + nestedInCsv + " not loaded from CSV with nested columns\n"
                + "  unexplained: " + unexplained.size() + "\n" + String.join("\n", unexplained.stream()
                        .limit(20).toList());
        System.out.println(report);
        assertTrue(unexplained.isEmpty(), report);
        assertTrue(checked > RandomRun.QUERIES / 2, checked + " of " + RandomRun.QUERIES + " results checked");
    }

    /**
     * Loads a file with {@code COPY ... FROM} into a new table of the memory
     * source with the columns of the result, all of them nullable, and
     * compares its rows with the ones of the result in any order; the table
     * is dropped again.
     *
     * @return what differs, or {@code null} if the table has the rows of the result
     */
    private static String loaded(FyrQL engine, Path file, String format, Schema schema, List<Object[]> rows) {
        List<Schema.Field> fields = new ArrayList<>();
        for (Schema.Field f : schema.fields()) {
            fields.add(new Schema.Field(f.name(), f.type(), true));
        }
        String back = RandomTables.SOURCE + ".back";
        Writer writer = engine.connector(RandomTables.SOURCE).writer().orElseThrow();
        try (Sink sink = writer.create(TableRef.of(RandomTables.SOURCE, "back"), new Schema(fields))) {
            sink.commit();
        }
        try {
            long count = engine.update("COPY " + back + " FROM '" + WrittenFiles.path(file) + "'");
            if (count != rows.size()) {
                return count + " rows loaded, the query gives " + rows.size();
            }
            List<Object[]> expected = format.equals("csv") ? emptyAsNull(rows) : rows;
            ResultComparison.Outcome same = ResultComparison.compare(schema, expected,
                    engine.rows("SELECT * FROM " + back), false);
            return same.same() ? null : same.report();
        } catch (RuntimeException e) {
            return e.toString();
        } finally {
            engine.update("DROP TABLE " + back);
        }
    }

    /**
     * Returns the rows as a file of the format holds them. Text: an instant
     * whose offset in the time zone has seconds, a local mean time before
     * the standard time of the zone, is written with the offset without its
     * seconds, as DuckDB writes it, and reads back that much off. Parquet:
     * an interval has whole milliseconds in 32 bits, as DuckDB writes it.
     */
    private static List<Object[]> asWritten(String format, Schema schema, List<Object[]> rows) {
        List<Object[]> out = new ArrayList<>();
        for (Object[] row : rows) {
            Object[] e = row.clone();
            for (int c = 0; c < e.length; c++) {
                e[c] = asWritten(format.equals("parquet"), schema.type(c), e[c]);
            }
            out.add(e);
        }
        return out;
    }

    private static Object asWritten(boolean parquet, DataType type, Object value) {
        if (value == null) {
            return null;
        }
        return switch (type) {
            case DataType.ListType l -> ((List<?>) value).stream().map(v -> asWritten(parquet, l.element(), v))
                    .toList();
            case DataType.Struct s -> {
                List<Object> fields = new ArrayList<>();
                for (int i = 0; i < s.fields().size(); i++) {
                    fields.add(asWritten(parquet, s.fields().get(i).type(), ((List<?>) value).get(i)));
                }
                yield fields;
            }
            case DataType.MapType m -> {
                Map<Object, Object> entries = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                    entries.put(asWritten(parquet, m.key(), e.getKey()), asWritten(parquet, m.value(), e.getValue()));
                }
                yield entries;
            }
            default -> {
                if (parquet) {
                    yield value instanceof Interval i ? Interval.of(i.months(), i.days(),
                            Integer.toUnsignedLong((int) (i.micros() / 1000)) * 1000) : value;
                }
                yield type == DataType.TIMESTAMPTZ ? Conversions.parseTimestampTz(Conversions.formatTimestampTz(
                        (Long) value, RandomTables.ZONE), RandomTables.ZONE) : value;
            }
        };
    }

    /** Returns the rows with an empty string as {@code NULL}, as FyrQL reads them from a CSV file. */
    private static List<Object[]> emptyAsNull(List<Object[]> rows) {
        List<Object[]> out = new ArrayList<>();
        for (Object[] row : rows) {
            Object[] e = row.clone();
            for (int c = 0; c < e.length; c++) {
                if ("".equals(e[c])) {
                    e[c] = null;
                }
            }
            out.add(e);
        }
        return out;
    }

    /** Returns the plan with its columns named {@code c0}, {@code c1} and so on. */
    private static PlanNode renamed(PlanNode plan) {
        List<PlanNode.NamedExpression> columns = new ArrayList<>();
        for (int c = 0; c < plan.schema().size(); c++) {
            columns.add(new PlanNode.NamedExpression("c" + c, new Expression.Column(c, plan.schema().type(c))));
        }
        return new PlanNode.Project(plan, columns);
    }
}
