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

package net.fyrql.test;

import net.fyrql.FyrQL;
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.NamedExpression;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.Conversions;
import net.fyrql.core.value.DataGenerator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static net.fyrql.core.expr.Expressions.add;
import static net.fyrql.core.expr.Expressions.and;
import static net.fyrql.core.expr.Expressions.between;
import static net.fyrql.core.expr.Expressions.call;
import static net.fyrql.core.expr.Expressions.cast;
import static net.fyrql.core.expr.Expressions.col;
import static net.fyrql.core.expr.Expressions.div;
import static net.fyrql.core.expr.Expressions.eq;
import static net.fyrql.core.expr.Expressions.isDistinct;
import static net.fyrql.core.expr.Expressions.le;
import static net.fyrql.core.expr.Expressions.lit;
import static net.fyrql.core.expr.Expressions.lt;
import static net.fyrql.core.expr.Expressions.sub;
import static net.fyrql.core.expr.Expressions.tryCast;

/**
 * The date and timestamp semantics compared with DuckDB over generated
 * data with edge values, dates before year 1 and after 9999: the date
 * parts under every name, truncation, differences, the epoch functions,
 * construction, arithmetic, casts, and the formats of {@code strftime} and
 * {@code strptime}.
 */
class DateDifferentialTest {

    private static final Schema D = Schema.of(new Schema.Field("d1", DataType.DATE),
            new Schema.Field("d2", DataType.DATE), new Schema.Field("t1", DataType.TIMESTAMP),
            new Schema.Field("t2", DataType.TIMESTAMP), new Schema.Field("n", DataType.INTEGER),
            new Schema.Field("s", DataType.VARCHAR));

    /** Every name of a part except those whose type DuckDB makes depend on a constant name. */
    private static final List<String> PARTS = List.of("year", "yr", "y", "years", "yrs", "month", "mon", "months",
            "mons", "day", "days", "d", "dayofmonth", "decade", "dec", "decades", "decs", "century", "cent",
            "centuries", "c", "millennium", "mil", "millenniums", "millennia", "mils", "millenium", "microseconds",
            "microsecond", "us", "usec", "usecs", "usecond", "useconds", "milliseconds", "millisecond", "ms", "msec",
            "msecs", "msecond", "mseconds", "second", "sec", "seconds", "secs", "s", "minute", "min", "minutes",
            "mins", "m", "hour", "hr", "hours", "hrs", "h", "dow", "dayofweek", "weekday", "isodow", "week", "weeks",
            "w", "weekofyear", "doy", "dayofyear", "quarter", "quarters", "yearweek", "isoyear", "era", "YEAR",
            "Month");

    private static final List<String> FORMATS = List.of("%Y-%m-%d %H:%M:%S.%f", "%d/%m/%y %I:%M %p",
            "%A, %B %-d, %Y", "%a %b %j %-H:%-M:%-S", "%Y%m%d", "%-m/%-d/%Y %%", "%y %-j %-I%p");

    private static Differential differential;
    private static PlanNode.Scan d;

    @BeforeAll
    static void setUp() {
        Random random = new Random(12);
        List<Object[]> rows = DataGenerator.withSeed(11).generate(D, 300);
        List<String> pieces = List.of("2024-02-03", " 2024-2-3 ", "2024-02-30", "0002-02-03 (BC)", "24-02-03",
                "2024-02-03 04:05:06.1234567", "Sat Feb 03 2024", "1969-12-31T23:59:59", "epoch", "x", "",
                "2024-02-03 13 PM", "20240203", "12/31/99 12:00 AM", "Saturday, February 3, 2024");
        for (int i = 0; i < rows.size(); i++) {
            Object[] row = rows.get(i);
            row[4] = row[4] == null ? null : random.nextInt(-1000, 1001);
            row[5] = switch (i % 4) {
                case 0 -> row[0] == null ? null : Conversions.formatDate((Integer) row[0]);
                case 1 -> row[2] == null ? null : Conversions.formatTimestamp((Long) row[2]);
                case 2 -> pieces.get(random.nextInt(pieces.size()));
                default -> row[5];
            };
        }
        for (String[] r : new String[][] {{"-0015-05-01", "0001-01-01 (BC)", "-0150-03-01 10:00:00",
            "-1500-05-01 23:59:59.999999"}, {"0000-01-01", "10000-01-01", "0000-12-31 12:00:00",
                "12345-06-07 08:09:10.5"}, {"99999-12-31", "-1000-01-01", "10000-01-01 00:00:00",
                    "0001-01-01 (BC) 00:00:00"}}) {
            rows.add(new Object[] {Conversions.parseDate(r[0]), Conversions.parseDate(r[1]),
                Conversions.parseTimestamp(r[2]), Conversions.parseTimestamp(r[3]), 1, r[0]});
        }
        FyrQL engine = FyrQL.builder()
                .register(MemoryConnector.builder("mem").table("d", D, rows).batchSize(41).build())
                .batchSize(41)
                .build();
        differential = new Differential(engine);
        d = engine.scan(TableRef.of("mem", "d"));
    }

    @AfterAll
    static void tearDown() {
        differential.close();
    }

    private static Expression c(int index) {
        return col(D, index);
    }

    private static PlanNode project(PlanNode input, List<Expression> expressions) {
        List<NamedExpression> columns = new ArrayList<>();
        for (int i = 0; i < expressions.size(); i++) {
            columns.add(new NamedExpression("x" + i, expressions.get(i)));
        }
        return new PlanNode.Project(input, columns);
    }

    /** The rows whose timestamps lie between the years 1 and 9999, where every format reads back. */
    private static PlanNode commonEra() {
        return new PlanNode.Filter(d, and(between(call("year", c(2)), lit(1L), lit(9999L)),
                between(call("year", c(0)), lit(1L), lit(9999L))));
    }

    @Test
    void partFunctions() {
        List<Expression> e = new ArrayList<>();
        for (String f : List.of("year", "month", "day", "dayofmonth", "hour", "minute", "second", "millisecond",
                "microsecond", "dayofweek", "isodow", "dayofyear", "week", "weekofyear", "quarter", "yearweek",
                "isoyear", "decade", "century", "millennium", "era", "last_day", "monthname", "dayname", "epoch",
                "epoch_ms", "epoch_us")) {
            e.add(call(f, c(0)));
            e.add(call(f, c(2)));
        }
        differential.check(project(d, e));
    }

    @Test
    void datePartUnderEveryName() {
        List<Expression> e = new ArrayList<>();
        for (String part : PARTS) {
            e.add(call("date_part", lit(part), c(0)));
            e.add(call("datepart", lit(part), c(2)));
        }
        differential.check(project(d, e));
    }

    @Test
    void dateTruncUnderEveryName() {
        List<Expression> e = new ArrayList<>();
        for (String part : PARTS) {
            if (!part.equals("era")) {
                e.add(call("date_trunc", lit(part), c(0)));
                e.add(call("datetrunc", lit(part), c(2)));
            }
        }
        e.add(call("date_trunc", lit("epoch"), c(2)));
        e.add(call("date_trunc", lit("julian"), c(2)));
        differential.check(project(d, e));
    }

    @Test
    void dateDiffUnderEveryName() {
        List<Expression> e = new ArrayList<>();
        for (String part : PARTS) {
            if (!part.equals("era")) {
                e.add(call("date_diff", lit(part), c(0), c(1)));
                e.add(call("datediff", lit(part), c(2), c(3)));
                e.add(call("date_diff", lit(part), c(0), c(3)));
            }
        }
        e.add(call("date_diff", lit("epoch"), c(2), c(3)));
        e.add(call("date_diff", lit("julian"), c(0), c(1)));
        differential.check(project(d, e));
    }

    @Test
    void errorsAgree() {
        differential.check(project(d, List.of(call("date_part", lit("timezone"), c(1)))));
        differential.check(project(d, List.of(call("date_trunc", lit("era"), c(2)))));
        differential.check(project(d, List.of(call("date_diff", lit("era"), c(0), c(1)))));
        differential.check(project(d, List.of(call("date_part", lit("foo"), c(2)))));
        differential.check(project(d, List.of(call("epoch_ns", c(2)))));
        differential.check(project(d, List.of(call("strftime", c(0), lit("%Q")))));
        differential.check(project(d, List.of(call("strptime", c(5), lit("%Y-%m-%d")))));
        differential.check(project(d, List.of(call("make_date", call("year", c(0)), lit(2L), call("day", c(0))))));
        differential.check(project(d, List.of(add(c(0), lit(Integer.MAX_VALUE)))));
    }

    @Test
    void epochAndConstruction() {
        PlanNode modern = new PlanNode.Filter(d, between(call("year", c(2)), lit(1700L), lit(2200L)));
        Expression seconds = div(cast(call("microsecond", c(2)), DataType.DOUBLE), lit(1_000_000.0));
        differential.check(project(modern, List.of(call("epoch_ns", c(2)), call("epoch_ns", c(0)),
                call("epoch_ms", call("epoch_ms", c(2))), call("make_timestamp", call("epoch_us", c(2))),
                call("make_date", c(4)), call("make_date", call("year", c(0)), call("month", c(0)), call("day", c(0))),
                call("make_timestamp", call("year", c(2)), call("month", c(2)), call("day", c(2)),
                        call("hour", c(2)), call("minute", c(2)), seconds),
                call("make_timestamp", lit(2020), lit(1), lit(2), cast(lit(3), DataType.BIGINT),
                        call("minute", c(2)), div(cast(c(4), DataType.DOUBLE), lit(1000.0))))));
    }

    @Test
    void arithmeticComparisonsAndCasts() {
        differential.check(project(d, List.of(add(c(0), c(4)), add(c(4), c(0)), sub(c(0), c(4)),
                add(c(0), lit((byte) 3)), sub(c(0), c(1)), lt(c(0), c(2)), eq(c(0), cast(c(2), DataType.DATE)),
                le(c(2), c(1)), between(c(0), c(2), c(3)), cast(c(0), DataType.VARCHAR), cast(c(2), DataType.VARCHAR),
                cast(c(2), DataType.DATE), tryCast(c(5), DataType.DATE), tryCast(c(5), DataType.TIMESTAMP),
                call("greatest", c(0), c(2)), call("least", c(1), c(0)))));
        differential.check(project(d, List.of(cast(c(0), DataType.TIMESTAMP))));
    }

    /** The grammar of dates and timestamps in strings, on the edges DuckDB draws. */
    @Test
    void stringsToDatesAndTimestamps() {
        List<String> strings = List.of("2024-02-03", "2024-02-03 ", " 2024-02-03", "2024-2-3", "2024-02-03\t",
                "2024-02-03T10:00", "2024-02-03 10:00 ", "2024-02-03 1:2:3", "2024-02-03 10:00:00Z",
                "2024-02-03 10:00:00 Z", "2024-02-03 10:00:00 utc", "2024-02-03 10:00:00 GMT",
                "2024-02-03 10:00:00+0200", "2024-02-03 10:00:00+2", "2024-02-03 10:00:00 +02", "2024-02-03 10",
                "2024-02-03 10:5", "2024-02-03 10:00:00.", "2024-02-03 10:00:", "2024-02-03 13:", "2024-02-03 13:x",
                "2024-02-03 10:00:00.5 UTC", "2024-02-03 10:00:00-05:30", "2024-02-03 10:00:00+02:00:30",
                "2024-02-03 10:00:00+25", "2024-02-03 10:00:00 America/New_York", "2024-02-03 100:00:00",
                "2024-02-03 24:00:00", "2024-02-03 24:00:01", "2024-02-03 10:60:00", "2024-02-03 10:00:60",
                "2024-02-03 (BC) 10:00", "2024-02-03 (BC)T10:00", "2024-02-03 (BC) ", "2024-02-03(BC)",
                "2024-02-03 (BC)", "2024-02-03 10:00:00 (BC)", "2024-02-03 xyz", "2024-02-03xyz",
                "2024-02-03 10:00:00Zx", "2024-02-03 10:00:00 UTCx", "2024-02-03 10:00:00+02 x",
                "2024-02-03T10:00:00Z", "2024-02-03 10:00:00 +02:00", "2024-02-03 10:00:00   ", "2024/02/03 10:00",
                "2024 02 03 10:00", "2024-02-03 10:00:00.1234567891", "2024-02-03 10:00 UTC", "2024-02-03 10:00.5",
                "2024-02-03 10:00:00 UTC  ", "2024-02-03 10:00Z", "2024-02-03 10:00+01", "0000-02-03 (BC)",
                "-0005-02-03", "10000-01-01 10:00", "epoch", "2024-13-01", "2024-02-30 10:00", "");
        List<Object[]> rows = new ArrayList<>();
        for (String s : strings) {
            rows.add(new Object[] {s});
        }
        PlanNode values = new PlanNode.Values(Schema.of(DataType.VARCHAR), rows);
        Expression s = col(values.schema(), 0);
        differential.check(project(values, List.of(s, tryCast(s, DataType.TIMESTAMP), tryCast(s, DataType.DATE))));
    }

    @Test
    void strftime() {
        List<Expression> e = new ArrayList<>();
        for (String format : FORMATS) {
            e.add(call("strftime", c(0), lit(format)));
            e.add(call("strftime", c(2), lit(format)));
        }
        e.add(call("strftime", lit("%Y|%y|%-y|%j"), c(3)));
        differential.check(project(d, e));
    }

    @Test
    void strptime() {
        List<Expression> e = new ArrayList<>();
        e.add(c(5));
        for (String format : FORMATS) {
            e.add(call("try_strptime", call("strftime", c(2), lit(format)), lit(format)));
            e.add(call("try_strptime", c(5), lit(format)));
        }
        for (String format : List.of("%Y-%m-%d", "%Y-%m-%d %H:%M:%S.%f", "%y-%m-%d", "%a %b %d %Y",
                "%A, %B %-d, %Y", "%m/%d/%y %I:%M %p", "%Y%m%d", "%Y-%m-%dT%H:%M:%S", "%Y-%m-%d (BC)")) {
            e.add(call("try_strptime", c(5), lit(format)));
        }
        // DuckDB reads the string 'epoch' in any format: strptime as 1970-01-01, try_strptime as 1900-01-01.
        PlanNode input = new PlanNode.Filter(commonEra(), isDistinct(c(5), lit("epoch")));
        differential.check(project(input, e));
        differential.check(project(input, List.of(call("strptime", call("strftime", c(2), lit(FORMATS.get(0))),
                lit(FORMATS.get(0))))));
    }
}
