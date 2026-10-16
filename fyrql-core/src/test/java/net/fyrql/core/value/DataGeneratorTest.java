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

package net.fyrql.core.value;

import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.type.Values;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DataGeneratorTest {

    /** Every type of the concept, including nested ones. */
    public static final List<DataType> ALL_TYPES = allTypes();

    static List<DataType> allTypes() {
        List<DataType> types = new ArrayList<>(List.of(DataType.Scalar.values()));
        types.add(DataType.decimal(10, 2));
        types.add(DataType.decimal(38, 10));
        types.add(DataType.struct(new DataType.Struct.Field("a", DataType.INTEGER),
                new DataType.Struct.Field("b", DataType.listOf(DataType.VARCHAR))));
        types.add(DataType.listOf(DataType.decimal(5, 1)));
        types.add(DataType.mapOf(DataType.VARCHAR, DataType.INTEGER));
        return types;
    }

    @Test
    void everyValueBelongsToItsType() {
        Schema schema = new Schema(ALL_TYPES.stream().map(t -> new Schema.Field(t.sqlName(), t)).toList());
        List<Object[]> rows = DataGenerator.withSeed(7).generate(schema, 200);
        assertEquals(200, rows.size());
        for (Object[] row : rows) {
            for (int c = 0; c < schema.size(); c++) {
                assertTrue(schema.type(c).accepts(row[c]), schema.type(c) + " rejects " + Values.describe(row[c]));
            }
        }
    }

    @Test
    void edgeValuesComeFirstAndNullOnlyWhereAllowed() {
        Schema schema = Schema.of(new Schema.Field("n", DataType.INTEGER, true),
                new Schema.Field("m", DataType.INTEGER, false));
        List<Object[]> rows = DataGenerator.withSeed(1).generate(schema, 500);
        List<Object> edges = EdgeValues.of(DataType.INTEGER);
        List<Object> first = new ArrayList<>();
        for (int r = 0; r <= edges.size(); r++) {
            first.add(rows.get(r)[0]);
        }
        assertTrue(first.containsAll(edges) && first.contains(null), "the first rows hold every edge value and NULL");
        for (Object[] row : rows) {
            assertTrue(row[1] != null, "no NULL in a NOT NULL column");
        }
    }

    @Test
    void deterministic() {
        Schema schema = Schema.of(DataType.VARCHAR, DataType.DOUBLE, DataType.listOf(DataType.INTEGER));
        List<Object[]> a = DataGenerator.withSeed(42).generate(schema, 100);
        List<Object[]> b = DataGenerator.withSeed(42).generate(schema, 100);
        for (int r = 0; r < a.size(); r++) {
            for (int c = 0; c < schema.size(); c++) {
                assertTrue(Keys.equalObjects(Keys.canonical(a.get(r)[c]), Keys.canonical(b.get(r)[c])));
            }
        }
    }

    @Test
    void keys() {
        assertTrue(Keys.equalObjects(Keys.canonical(-0.0), Keys.canonical(0.0)));
        assertTrue(Keys.equalObjects(Keys.canonical(Double.NaN), Keys.canonical(Double.longBitsToDouble(0x7ff8000000000001L))));
        assertTrue(Keys.equalObjects(new byte[] {1}, new byte[] {1}));
        assertEquals(Keys.hash(new byte[] {1}), Keys.hash(new byte[] {1}));
        assertTrue(Keys.equalObjects(List.of(1, "a"), List.of(1, "a")));
    }

    @Test
    void mapsCompareInOrder() {
        // As in DuckDB: MAP {1: 2, 3: 4} = MAP {3: 4, 1: 2} is false, MAP {1: NULL} = MAP {1: NULL} is true.
        Map<Object, Object> a = new LinkedHashMap<>();
        a.put(new byte[] {1}, "x");
        a.put(new byte[] {2}, null);
        Map<Object, Object> same = new LinkedHashMap<>();
        same.put(new byte[] {1}, "x");
        same.put(new byte[] {2}, null);
        assertTrue(Keys.equalObjects(a, same));
        assertEquals(Keys.hash(a), Keys.hash(same));
        Map<Object, Object> reordered = new LinkedHashMap<>();
        reordered.put(new byte[] {2}, null);
        reordered.put(new byte[] {1}, "x");
        assertFalse(Keys.equalObjects(a, reordered));
        Map<Object, Object> otherValue = new LinkedHashMap<>();
        otherValue.put(new byte[] {1}, "y");
        otherValue.put(new byte[] {2}, null);
        assertFalse(Keys.equalObjects(a, otherValue));
        Map<Object, Object> otherKey = new LinkedHashMap<>();
        otherKey.put(new byte[] {1}, "x");
        otherKey.put(new byte[] {3}, null);
        assertFalse(Keys.equalObjects(a, otherKey));
        assertTrue(Keys.equalObjects(new LinkedList<>(List.of(1, 2)), List.of(1, 2)));
    }
}
