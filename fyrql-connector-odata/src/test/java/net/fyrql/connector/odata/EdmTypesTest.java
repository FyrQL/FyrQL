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

package net.fyrql.connector.odata;

import net.fyrql.core.type.DataType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static net.fyrql.connector.odata.EdmxParserTest.parse;
import static net.fyrql.connector.odata.EdmxParserTest.v4;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The types in FyrQL of the properties of OData. */
class EdmTypesTest {

    private static final ServiceMetadata TYPES = parse(v4("<EntityType Name='T'>"
            + "<Property Name='Boolean' Type='Edm.Boolean'/>"
            + "<Property Name='SByte' Type='Edm.SByte'/>"
            + "<Property Name='Byte' Type='Edm.Byte'/>"
            + "<Property Name='Int16' Type='Edm.Int16'/>"
            + "<Property Name='Int32' Type='Edm.Int32'/>"
            + "<Property Name='Int64' Type='Edm.Int64'/>"
            + "<Property Name='Single' Type='Edm.Single'/>"
            + "<Property Name='Double' Type='Edm.Double'/>"
            + "<Property Name='Decimal' Type='Edm.Decimal' Precision='18' Scale='4'/>"
            + "<Property Name='DecimalWithoutScale' Type='Edm.Decimal' Precision='10'/>"
            + "<Property Name='DecimalOf38' Type='Edm.Decimal' Precision='38' Scale='38'/>"
            + "<Property Name='DecimalWithoutPrecision' Type='Edm.Decimal'/>"
            + "<Property Name='DecimalOf39' Type='Edm.Decimal' Precision='39'/>"
            + "<Property Name='DecimalVariable' Type='Edm.Decimal' Precision='10' Scale='variable'/>"
            + "<Property Name='DecimalFloating' Type='Edm.Decimal' Precision='34' Scale='floating'/>"
            + "<Property Name='DecimalScaleAbovePrecision' Type='Edm.Decimal' Precision='2' Scale='3'/>"
            + "<Property Name='String' Type='Edm.String' MaxLength='10'/>"
            + "<Property Name='Guid' Type='Edm.Guid'/>"
            + "<Property Name='Binary' Type='Edm.Binary'/>"
            + "<Property Name='DateTime' Type='Edm.DateTime'/>"
            + "<Property Name='DateTimeOffset' Type='Edm.DateTimeOffset' Precision='3'/>"
            + "<Property Name='Date' Type='Edm.Date'/>"
            + "<Property Name='Time' Type='Edm.Time'/>"
            + "<Property Name='TimeOfDay' Type='Edm.TimeOfDay'/>"
            + "<Property Name='Duration' Type='Edm.Duration'/>"
            + "<Property Name='Untyped' Type='Edm.Untyped'/>"
            + "<Property Name='Future' Type='Edm.Something'/>"
            + "<Property Name='Undeclared' Type='Other.Thing'/>"
            + "<Property Name='Enum' Type='M.Color'/>"
            + "<Property Name='Money' Type='M.Money'/>"
            + "<Property Name='MoneyOfOtherScale' Type='M.Money' Scale='0'/>"
            + "<Property Name='Strings' Type='Collection(Edm.String)'/>"
            + "<Property Name='Address' Type='M.Address'/>"
            + "<Property Name='Addresses' Type='Collection(M.Address)'/>"
            + "<Property Name='Tree' Type='M.Tree'/>"
            + "<Property Name='Stream' Type='Edm.Stream'/>"
            + "<Property Name='Point' Type='Edm.GeographyPoint'/>"
            + "<Property Name='Shape' Type='Edm.GeometryPolygon'/>"
            + "<Property Name='Points' Type='Collection(Edm.GeographyPoint)'/>"
            + "<Property Name='OnlyGeography' Type='M.Place'/>"
            + "</EntityType>"
            + "<EnumType Name='Color'><Member Name='Red'/></EnumType>"
            + "<TypeDefinition Name='Money' UnderlyingType='Edm.Decimal' Precision='12' Scale='2'/>"
            + "<ComplexType Name='Base'><Property Name='Street' Type='Edm.String'/></ComplexType>"
            + "<ComplexType Name='Address' BaseType='M.Base'><Property Name='City' Type='Edm.String'/>"
            + "<Property Name='Location' Type='Edm.GeographyPoint'/>"
            + "<Property Name='Zip' Type='Edm.Int32'/></ComplexType>"
            + "<ComplexType Name='Tree'><Property Name='Label' Type='Edm.String'/>"
            + "<Property Name='Children' Type='Collection(M.Tree)'/></ComplexType>"
            + "<ComplexType Name='Place'><Property Name='Where' Type='Edm.GeographyPoint'/></ComplexType>"));

    private static Map<String, DataType> types() {
        Map<String, DataType> types = new LinkedHashMap<>();
        for (Property p : TYPES.entityTypes().get("M.T").properties()) {
            types.put(p.name(), EdmTypes.of(p, TYPES));
        }
        return types;
    }

    @Test
    void primitiveTypes() {
        Map<String, DataType> types = types();
        assertEquals(DataType.BOOLEAN, types.get("Boolean"));
        assertEquals(DataType.TINYINT, types.get("SByte"));
        assertEquals(DataType.SMALLINT, types.get("Byte"));
        assertEquals(DataType.SMALLINT, types.get("Int16"));
        assertEquals(DataType.INTEGER, types.get("Int32"));
        assertEquals(DataType.BIGINT, types.get("Int64"));
        assertEquals(DataType.FLOAT, types.get("Single"));
        assertEquals(DataType.DOUBLE, types.get("Double"));
        assertEquals(DataType.VARCHAR, types.get("String"));
        assertEquals(DataType.UUID, types.get("Guid"));
        assertEquals(DataType.BLOB, types.get("Binary"));
        assertEquals(DataType.TIMESTAMP, types.get("DateTime"));
        assertEquals(DataType.TIMESTAMP, types.get("DateTimeOffset"));
        assertEquals(DataType.DATE, types.get("Date"));
    }

    @Test
    void decimals() {
        Map<String, DataType> types = types();
        assertEquals(DataType.decimal(18, 4), types.get("Decimal"));
        assertEquals(DataType.decimal(10, 0), types.get("DecimalWithoutScale"));
        assertEquals(DataType.decimal(38, 38), types.get("DecimalOf38"));
        assertEquals(DataType.DOUBLE, types.get("DecimalWithoutPrecision"));
        assertEquals(DataType.DOUBLE, types.get("DecimalOf39"));
        assertEquals(DataType.DOUBLE, types.get("DecimalVariable"));
        assertEquals(DataType.DOUBLE, types.get("DecimalFloating"));
        assertEquals(DataType.DOUBLE, types.get("DecimalScaleAbovePrecision"));
        assertEquals(DataType.decimal(12, 2), types.get("Money"));
        assertEquals(DataType.decimal(12, 0), types.get("MoneyOfOtherScale"));
        assertEquals(DataType.DOUBLE, EdmTypes.decimal("ten", "2"));
        assertEquals(DataType.DOUBLE, EdmTypes.decimal("0", null));
        assertEquals(DataType.decimal(5, 2), EdmTypes.decimal(" 5 ", " 2"));
    }

    @Test
    void typesWithoutTypeInFyrQLAreText() {
        Map<String, DataType> types = types();
        for (String name : List.of("Time", "TimeOfDay", "Duration", "Untyped", "Future", "Undeclared", "Enum")) {
            assertEquals(DataType.VARCHAR, types.get(name), name);
        }
    }

    @Test
    void complexTypesAndCollections() {
        Map<String, DataType> types = types();
        assertEquals(DataType.listOf(DataType.VARCHAR), types.get("Strings"));
        DataType address = DataType.struct(new DataType.Struct.Field("Street", DataType.VARCHAR),
                new DataType.Struct.Field("City", DataType.VARCHAR),
                new DataType.Struct.Field("Zip", DataType.INTEGER));
        assertEquals(address, types.get("Address"));
        assertEquals(DataType.listOf(address), types.get("Addresses"));
        // A complex type that contains itself is the JSON text of the inner values.
        assertEquals(DataType.struct(new DataType.Struct.Field("Label", DataType.VARCHAR),
                new DataType.Struct.Field("Children", DataType.listOf(DataType.VARCHAR))), types.get("Tree"));
    }

    @Test
    void streamsAndGeographyAreNoColumns() {
        Map<String, DataType> types = types();
        for (String name : List.of("Stream", "Point", "Shape", "Points", "OnlyGeography")) {
            assertNull(types.get(name), name);
        }
    }

    @Test
    void sampleServices() {
        ServiceMetadata trippin = EdmxParserTest.resource("trippin-metadata.xml");
        Map<String, DataType> types = new LinkedHashMap<>();
        for (Property p : trippin.properties(trippin.entityTypes().get("Trippin.Trip"))) {
            types.put(p.name(), EdmTypes.of(p, trippin));
        }
        assertEquals(List.of(DataType.INTEGER, DataType.UUID, DataType.VARCHAR, DataType.FLOAT, DataType.VARCHAR,
                DataType.listOf(DataType.VARCHAR), DataType.TIMESTAMP, DataType.TIMESTAMP),
                List.copyOf(types.values()));
        Property duration = trippin.entityTypes().get("Trippin.PlanItem").properties().get(4);
        assertEquals("Duration", duration.name());
        assertEquals(DataType.VARCHAR, EdmTypes.of(duration, trippin));
    }
}
