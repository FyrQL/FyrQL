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

package net.fyrql.core.type;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DataTypeTest {

    /**
     * The constants of {@link DataType} must be set whichever class is
     * initialized first; a fresh class loader makes the order independent of
     * the tests that ran before.
     */
    @Test
    void constantsSurviveEitherInitializationOrder() throws Exception {
        URL classes = DataType.class.getProtectionDomain().getCodeSource().getLocation();
        for (String first : new String[] {"net.fyrql.core.type.DataType$Scalar", "net.fyrql.core.type.DataType"}) {
            try (URLClassLoader loader = new URLClassLoader(new URL[] {classes}, ClassLoader.getPlatformClassLoader())) {
                Class.forName(first, true, loader);
                Class<?> dataType = Class.forName("net.fyrql.core.type.DataType", true, loader);
                for (String name : new String[] {"BOOLEAN", "INTEGER", "VARCHAR", "UUID"}) {
                    assertNotNull(dataType.getField(name).get(null), first + " first leaves DataType." + name + " null");
                }
            }
        }
    }

    @Test
    void classification() {
        assertTrue(DataType.INTEGER.isIntegral() && DataType.INTEGER.isNumeric());
        assertTrue(DataType.DOUBLE.isFloatingPoint() && DataType.DOUBLE.isNumeric());
        assertTrue(DataType.decimal(10, 2).isNumeric());
        assertFalse(DataType.decimal(10, 2).isIntegral() || DataType.decimal(10, 2).isNested());
        assertFalse(DataType.VARCHAR.isNumeric() || DataType.DATE.isNumeric());
        assertTrue(DataType.listOf(DataType.INTEGER).isNested());
        assertFalse(DataType.listOf(DataType.INTEGER).isNumeric());
        assertTrue(DataType.mapOf(DataType.VARCHAR, DataType.INTEGER).isNested());
        assertTrue(DataType.struct(new DataType.Struct.Field("a", DataType.INTEGER)).isNested());
        assertEquals("STRUCT(a INTEGER)", DataType.struct(new DataType.Struct.Field("a", DataType.INTEGER)).sqlName());
    }

    @Test
    void fieldNamesAreQuotedAsDuckDbQuotesThem() {
        DataType s = DataType.struct(new DataType.Struct.Field("a_1", DataType.INTEGER),
                new DataType.Struct.Field("name", DataType.VARCHAR), new DataType.Struct.Field("Name2", DataType.DATE),
                new DataType.Struct.Field("x y", DataType.INTEGER), new DataType.Struct.Field("q\"r", DataType.INTEGER),
                new DataType.Struct.Field("1a", DataType.INTEGER), new DataType.Struct.Field("Select", DataType.INTEGER));
        assertEquals("STRUCT(a_1 INTEGER, \"name\" VARCHAR, Name2 DATE, \"x y\" INTEGER, \"q\"\"r\" INTEGER,"
                + " \"1a\" INTEGER, \"Select\" INTEGER)", s.sqlName());
    }

    @Test
    void aFieldIsFoundInAnyCaseAndExactlyFirst() {
        DataType.Struct s = DataType.struct(new DataType.Struct.Field("a", DataType.INTEGER),
                new DataType.Struct.Field("Ab", DataType.INTEGER), new DataType.Struct.Field("AB", DataType.INTEGER));
        assertEquals(0, s.find("A"));
        assertEquals(1, s.find("Ab"));
        assertEquals(2, s.find("AB"));
        assertEquals(-1, s.find("ab"), "two fields have the name in another case");
        assertEquals(-1, s.find("c"));
    }
}
