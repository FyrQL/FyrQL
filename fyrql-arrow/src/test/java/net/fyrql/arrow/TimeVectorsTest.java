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

package net.fyrql.arrow;

import net.fyrql.core.type.DataType;
import net.fyrql.core.value.Conversions;
import net.fyrql.core.value.Interval;
import net.fyrql.core.vector.Batch;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.DurationVector;
import org.apache.arrow.vector.IntervalDayVector;
import org.apache.arrow.vector.IntervalMonthDayNanoVector;
import org.apache.arrow.vector.IntervalYearVector;
import org.apache.arrow.vector.TimeMilliVector;
import org.apache.arrow.vector.TimeNanoVector;
import org.apache.arrow.vector.TimeStampMilliTZVector;
import org.apache.arrow.vector.TimeStampNanoTZVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.IntervalUnit;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Times of day, instants, intervals and durations of every unit as Arrow writes them, read into FyrQL. */
class TimeVectorsTest {

    private static Field field(String name, ArrowType type) {
        return new Field(name, FieldType.nullable(type), null);
    }

    @Test
    void everyUnitReadsAsTheTypeOfFyrQL() {
        List<Field> fields = List.of(field("tms", new ArrowType.Time(TimeUnit.MILLISECOND, 32)),
                field("tns", new ArrowType.Time(TimeUnit.NANOSECOND, 64)),
                field("zms", new ArrowType.Timestamp(TimeUnit.MILLISECOND, "Europe/Berlin")),
                field("zns", new ArrowType.Timestamp(TimeUnit.NANOSECOND, "UTC")),
                field("iy", new ArrowType.Interval(IntervalUnit.YEAR_MONTH)),
                field("id", new ArrowType.Interval(IntervalUnit.DAY_TIME)),
                field("imdn", new ArrowType.Interval(IntervalUnit.MONTH_DAY_NANO)),
                field("ds", new ArrowType.Duration(TimeUnit.SECOND)),
                field("dns", new ArrowType.Duration(TimeUnit.NANOSECOND)));
        try (BufferAllocator allocator = new RootAllocator();
                VectorSchemaRoot root = VectorSchemaRoot.create(new org.apache.arrow.vector.types.pojo.Schema(fields),
                        allocator)) {
            root.allocateNew();
            ((TimeMilliVector) root.getVector("tms")).setSafe(0, 45_296_123);
            ((TimeNanoVector) root.getVector("tns")).setSafe(0, 45_296_123_456_789L);
            ((TimeStampMilliTZVector) root.getVector("zms")).setSafe(0, 1_719_835_200_123L);
            ((TimeStampNanoTZVector) root.getVector("zns")).setSafe(0, 1_719_835_200_123_456_789L);
            ((IntervalYearVector) root.getVector("iy")).setSafe(0, -14);
            ((IntervalDayVector) root.getVector("id")).setSafe(0, 3, -1_500);
            ((IntervalMonthDayNanoVector) root.getVector("imdn")).setSafe(0, 1, -2, 3_000_000_999L);
            ((DurationVector) root.getVector("ds")).setSafe(0, 90_000);
            ((DurationVector) root.getVector("dns")).setSafe(0, -1_999L);
            for (int c = 0; c < fields.size(); c++) {
                root.getVector(c).setNull(1);
            }
            root.setRowCount(2);
            Batch batch = ArrowToBatch.batch(root);
            assertEquals(List.of(DataType.TIME, DataType.TIME, DataType.TIMESTAMPTZ, DataType.TIMESTAMPTZ,
                    DataType.INTERVAL, DataType.INTERVAL, DataType.INTERVAL, DataType.INTERVAL, DataType.INTERVAL),
                    batch.schema().types());
            long instant = Conversions.parseTimestampTz("2024-07-01 12:00:00Z", ZoneOffset.UTC);
            assertEquals(Arrays.asList(Conversions.parseTime("12:34:56.123"), Conversions.parseTime("12:34:56.123456"),
                    instant + 123_000, instant + 123_456, Interval.of(-14, 0, 0), Interval.of(0, 3, -1_500_000),
                    Interval.of(1, -2, 3_000_000), Interval.parse("25 hours"), Interval.ofMicros(-2)),
                    Arrays.asList(batch.row(0)));
            assertEquals(Arrays.asList(new Object[fields.size()]), Arrays.asList(batch.row(1)));
        }
    }
}
