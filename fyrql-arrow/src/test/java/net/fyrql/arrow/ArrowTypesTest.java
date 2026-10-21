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
import net.fyrql.core.type.Schema;
import net.fyrql.jdbc.AllTypesTable;
import org.apache.arrow.vector.complex.MapVector;
import org.apache.arrow.vector.extension.UuidType;
import org.apache.arrow.vector.types.DateUnit;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.IntervalUnit;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.UnionMode;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.DictionaryEncoding;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The types of FyrQL as Arrow types and back. */
class ArrowTypesTest {

    private static Field field(String name, ArrowType type, Field... children) {
        return new Field(name, FieldType.nullable(type), List.of(children));
    }

    @Test
    void everyTypeOfFyrQLGoesToArrowAndBack() {
        Schema schema = AllTypesTable.SCHEMA.concat(Schema.of(
                new Schema.Field("d0", DataType.decimal(38, 0), false),
                new Schema.Field("d1", DataType.decimal(1, 1), true),
                new Schema.Field("mm", DataType.mapOf(AllTypesTable.AB, DataType.mapOf(DataType.UUID,
                        DataType.HUGEINT)), true)));
        org.apache.arrow.vector.types.pojo.Schema arrow = ArrowTypes.toArrow(schema);
        assertEquals(schema, ArrowTypes.toFyrQL(arrow));
        assertFalse(arrow.findField("d0").isNullable());
    }

    @Test
    void theScalarTypesAreTheUsualArrowTypes() {
        Map<DataType, ArrowType> expected = Map.ofEntries(
                Map.entry(DataType.BOOLEAN, ArrowType.Bool.INSTANCE),
                Map.entry(DataType.TINYINT, new ArrowType.Int(8, true)),
                Map.entry(DataType.SMALLINT, new ArrowType.Int(16, true)),
                Map.entry(DataType.INTEGER, new ArrowType.Int(32, true)),
                Map.entry(DataType.BIGINT, new ArrowType.Int(64, true)),
                Map.entry(DataType.HUGEINT, new ArrowType.Decimal(38, 0, 128)),
                Map.entry(DataType.FLOAT, new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE)),
                Map.entry(DataType.DOUBLE, new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)),
                Map.entry(DataType.decimal(12, 3), new ArrowType.Decimal(12, 3, 128)),
                Map.entry(DataType.VARCHAR, ArrowType.Utf8.INSTANCE),
                Map.entry(DataType.BLOB, ArrowType.Binary.INSTANCE),
                Map.entry(DataType.DATE, new ArrowType.Date(DateUnit.DAY)),
                Map.entry(DataType.TIMESTAMP, new ArrowType.Timestamp(TimeUnit.MICROSECOND, null)),
                Map.entry(DataType.UUID, UuidType.INSTANCE));
        expected.forEach((type, arrow) -> assertEquals(arrow, ArrowTypes.toArrow("c", type, true).getType(),
                type.sqlName()));
        assertEquals(Map.of(ArrowTypes.TYPE_METADATA, "HUGEINT"),
                ArrowTypes.toArrow("c", DataType.HUGEINT, true).getMetadata());
        assertTrue(ArrowTypes.toArrow("c", DataType.decimal(38, 0), true).getMetadata().isEmpty());
    }

    @Test
    void theNestedTypesNameTheirChildrenAsArrowDoes() {
        Field list = ArrowTypes.toArrow("l", DataType.listOf(DataType.INTEGER), true);
        assertEquals(ArrowType.List.INSTANCE, list.getType());
        assertEquals("item", list.getChildren().get(0).getName());
        assertTrue(list.getChildren().get(0).isNullable());
        Field map = ArrowTypes.toArrow("m", DataType.mapOf(DataType.VARCHAR, DataType.DOUBLE), false);
        assertEquals(new ArrowType.Map(false), map.getType());
        assertFalse(map.isNullable());
        Field entries = map.getChildren().get(0);
        assertEquals(MapVector.DATA_VECTOR_NAME, entries.getName());
        assertFalse(entries.isNullable());
        assertEquals(List.of(MapVector.KEY_NAME, MapVector.VALUE_NAME),
                entries.getChildren().stream().map(Field::getName).toList());
        assertFalse(entries.getChildren().get(0).isNullable());
        assertTrue(entries.getChildren().get(1).isNullable());
        Field struct = ArrowTypes.toArrow("s", AllTypesTable.XYZ, true);
        assertEquals(List.of("x", "y", "z"), struct.getChildren().stream().map(Field::getName).toList());
    }

    @Test
    void otherArrowTypesBecomeTheNearestTypeOfFyrQL() {
        Map<Field, DataType> expected = Map.ofEntries(
                Map.entry(field("u8", new ArrowType.Int(8, false)), DataType.SMALLINT),
                Map.entry(field("u16", new ArrowType.Int(16, false)), DataType.INTEGER),
                Map.entry(field("u32", new ArrowType.Int(32, false)), DataType.BIGINT),
                Map.entry(field("u64", new ArrowType.Int(64, false)), DataType.HUGEINT),
                Map.entry(field("null", ArrowType.Null.INSTANCE), DataType.INTEGER),
                Map.entry(field("half", new ArrowType.FloatingPoint(FloatingPointPrecision.HALF)), DataType.FLOAT),
                Map.entry(field("d0", new ArrowType.Decimal(38, 0, 128)), DataType.decimal(38, 0)),
                Map.entry(field("d256", new ArrowType.Decimal(38, 5, 256)), DataType.decimal(38, 5)),
                Map.entry(field("lu", ArrowType.LargeUtf8.INSTANCE), DataType.VARCHAR),
                Map.entry(field("uv", ArrowType.Utf8View.INSTANCE), DataType.VARCHAR),
                Map.entry(field("lb", ArrowType.LargeBinary.INSTANCE), DataType.BLOB),
                Map.entry(field("bv", ArrowType.BinaryView.INSTANCE), DataType.BLOB),
                Map.entry(field("fsb", new ArrowType.FixedSizeBinary(16)), DataType.BLOB),
                Map.entry(field("dm", new ArrowType.Date(DateUnit.MILLISECOND)), DataType.DATE),
                Map.entry(field("ts", new ArrowType.Timestamp(TimeUnit.SECOND, null)), DataType.TIMESTAMP),
                Map.entry(field("tms", new ArrowType.Timestamp(TimeUnit.MILLISECOND, null)), DataType.TIMESTAMP),
                Map.entry(field("tns", new ArrowType.Timestamp(TimeUnit.NANOSECOND, null)), DataType.TIMESTAMP),
                Map.entry(field("fsl", new ArrowType.FixedSizeList(3), field("e", ArrowType.Utf8.INSTANCE)),
                        DataType.listOf(DataType.VARCHAR)));
        expected.forEach((f, type) -> assertEquals(type, ArrowTypes.toFyrQL(f), f.getName()));
    }

    @Test
    void aFixedSizeBinaryMarkedAsUuidIsAUuid() {
        Field marked = new Field("u", new FieldType(true, new ArrowType.FixedSizeBinary(16), null,
                Map.of("ARROW:extension:name", "arrow.uuid", "ARROW:extension:metadata", "")), null);
        assertEquals(DataType.UUID, ArrowTypes.toFyrQL(marked));
        Field wrongWidth = new Field("u", new FieldType(true, new ArrowType.FixedSizeBinary(8), null,
                Map.of("ARROW:extension:name", "arrow.uuid")), null);
        assertEquals(DataType.BLOB, ArrowTypes.toFyrQL(wrongWidth));
    }

    @Test
    void theHugeIntMarkNeedsDecimal38() {
        Field marked = new Field("h", new FieldType(true, new ArrowType.Decimal(20, 0, 128), null,
                Map.of(ArrowTypes.TYPE_METADATA, "HUGEINT")), null);
        assertEquals(DataType.decimal(20, 0), ArrowTypes.toFyrQL(marked));
    }

    @Test
    void timesInstantsIntervalsAndDurationsOfEveryUnit() {
        for (TimeUnit unit : TimeUnit.values()) {
            assertEquals(DataType.TIMESTAMPTZ, ArrowTypes.toFyrQL(field("tz", new ArrowType.Timestamp(unit,
                    "Europe/Berlin"))));
            assertEquals(DataType.TIME, ArrowTypes.toFyrQL(field("t", new ArrowType.Time(unit,
                    unit == TimeUnit.SECOND || unit == TimeUnit.MILLISECOND ? 32 : 64))));
            assertEquals(DataType.INTERVAL, ArrowTypes.toFyrQL(field("du", new ArrowType.Duration(unit))));
        }
        for (IntervalUnit unit : IntervalUnit.values()) {
            assertEquals(DataType.INTERVAL, ArrowTypes.toFyrQL(field("i", new ArrowType.Interval(unit))));
        }
    }

    @Test
    void typesWithoutATypeInFyrQLFailWithTheNameOfTheField() {
        Map<Field, String> expected = Map.ofEntries(
                Map.entry(field("un", new ArrowType.Union(UnionMode.Sparse, new int[] {0}),
                        field("a", new ArrowType.Int(32, true))),
                        "Field un: the Arrow type Union(Sparse, [0]) has no type in FyrQL"),
                Map.entry(field("ll", ArrowType.LargeList.INSTANCE, field("item", ArrowType.Utf8.INSTANCE)),
                        "Field ll: the Arrow type LargeList has no type in FyrQL"),
                Map.entry(field("d", new ArrowType.Decimal(39, 0, 256)),
                        "Field d: the precision 39 is more than 38"),
                Map.entry(field("ds", new ArrowType.Decimal(10, -2, 128)),
                        "Field ds: the scale -2 is not between 0 and the precision 10"),
                Map.entry(field("s", ArrowType.Struct.INSTANCE),
                        "Field s: a struct without fields has no type in FyrQL"),
                Map.entry(field("n", ArrowType.Struct.INSTANCE, field("a", ArrowType.Utf8.INSTANCE),
                        field("l", ArrowType.List.INSTANCE, field("item", ArrowType.LargeList.INSTANCE,
                                field("item", ArrowType.Utf8.INSTANCE)))),
                        "Field n.l.item: the Arrow type LargeList has no type in FyrQL"),
                Map.entry(new Field("dict", new FieldType(true, new ArrowType.Int(32, true),
                        new DictionaryEncoding(7, false, null)), null),
                        "Field dict: a dictionary-encoded field must be decoded first"));
        expected.forEach((f, message) -> {
            ArrowTypeException e = assertThrows(ArrowTypeException.class, () -> ArrowTypes.toFyrQL(f), message);
            assertEquals(message, e.getMessage());
        });
        ArrowTypeException e = assertThrows(ArrowTypeException.class, () -> ArrowTypes.toFyrQL(
                field("n", ArrowType.Struct.INSTANCE, field("x", ArrowType.LargeList.INSTANCE,
                        field("item", ArrowType.Utf8.INSTANCE)))));
        assertEquals("n.x", e.field());
    }

    @Test
    void anArrowSchemaKeepsTheNamesAndNullability() {
        org.apache.arrow.vector.types.pojo.Schema arrow = new org.apache.arrow.vector.types.pojo.Schema(List.of(
                new Field("a", FieldType.notNullable(new ArrowType.Int(64, true)), null),
                new Field("B c", FieldType.nullable(ArrowType.Utf8.INSTANCE), null)));
        Schema schema = ArrowTypes.toFyrQL(arrow);
        assertEquals(Schema.of(new Schema.Field("a", DataType.BIGINT, false),
                new Schema.Field("B c", DataType.VARCHAR, true)), schema);
        assertNull(ArrowTypes.toArrow(schema).findField("a").getDictionary());
    }
}
