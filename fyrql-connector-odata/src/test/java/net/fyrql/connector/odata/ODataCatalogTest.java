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

import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The services of an OData source as schemas, their entity sets as tables. */
class ODataCatalogTest {

    private static final Map<String, String> DOCUMENTS = Map.of("northwind", "northwind-v2-metadata.xml",
            "northwind4", "northwind-v4-metadata.xml", "trippin", "trippin-metadata.xml",
            "gateway", "gwsample-basic-metadata.xml");

    /** The number of times the document of each schema was read. */
    private final Map<String, AtomicInteger> loads = new ConcurrentHashMap<>();

    private ODataCatalog catalog(String... schemas) {
        return new ODataCatalog("erp", List.of(schemas), schema -> {
            loads.computeIfAbsent(schema, s -> new AtomicInteger()).incrementAndGet();
            return EdmxParserTest.resource(DOCUMENTS.get(schema));
        });
    }

    private static TableRef ref(String schema, String table) {
        return new TableRef("erp", schema, table);
    }

    private static String columns(Schema schema) {
        StringBuilder b = new StringBuilder();
        for (Schema.Field f : schema.fields()) {
            b.append(b.isEmpty() ? "" : ", ").append(f.name()).append(' ').append(f.type().sqlName())
                    .append(f.nullable() ? "" : " NOT NULL");
        }
        return b.toString();
    }

    @Test
    void schemasAndTablesInTheOrderOfTheDocuments() {
        ODataCatalog catalog = catalog("trippin", "northwind");
        assertEquals(List.of("trippin", "northwind"), catalog.schemas());
        assertEquals(List.of("People", "Airlines", "Airports"), catalog.tables("trippin"));
        assertEquals(26, catalog.tables("northwind").size());
        assertEquals(List.of(), catalog.tables("main"));
        assertEquals(OptionalLong.empty(), catalog.rowCount(ref("trippin", "People")));
    }

    @Test
    void columnsOfVersion2() {
        ODataCatalog catalog = catalog("northwind", "gateway");
        assertEquals("OrderID INTEGER NOT NULL, ProductID INTEGER NOT NULL, UnitPrice DECIMAL(19,4) NOT NULL,"
                + " Quantity SMALLINT NOT NULL, Discount FLOAT NOT NULL",
                columns(catalog.schema(ref("northwind", "Order_Details"))));
        assertEquals("CategoryID INTEGER NOT NULL, CategoryName VARCHAR NOT NULL, Description VARCHAR,"
                + " Picture BLOB", columns(catalog.schema(ref("northwind", "Categories"))));

        Schema contacts = catalog.schema(ref("gateway", "ContactSet"));
        assertEquals(List.of("Address", "ContactGuid", "BusinessPartnerID", "Title", "FirstName", "MiddleName",
                "LastName", "Nickname", "Initials", "Sex", "PhoneNumber", "FaxNumber", "EmailAddress", "Language",
                "DateOfBirth"), contacts.fields().stream().map(Schema.Field::name).toList());
        Schema.Field address = contacts.fields().get(0);
        assertEquals(6, ((DataType.Struct) address.type()).fields().size());
        assertTrue(!address.nullable());
        assertEquals(DataType.UUID, contacts.type(1));
        assertEquals(DataType.TIMESTAMP, contacts.type(14));
        Schema products = catalog.schema(ref("gateway", "ProductSet"));
        assertEquals(DataType.SMALLINT, products.type(products.indexOf("TaxTarifCode")));
        assertEquals(DataType.decimal(16, 3), products.type(products.indexOf("Price")));
        ODataCatalog.Table table = catalog.table(ref("gateway", "ProductSet"));
        assertEquals(List.of("ProductID"), table.key());
        assertEquals("GWSAMPLE_BASIC.Product", table.type().name());
        assertEquals(products.size(), table.properties().size());
    }

    @Test
    void columnsOfVersion4() {
        ODataCatalog catalog = catalog("trippin", "northwind4");
        Schema people = catalog.schema(ref("trippin", "People"));
        assertEquals(List.of("UserName", "FirstName", "LastName", "MiddleName", "Gender", "Age", "Emails",
                "AddressInfo", "HomeAddress", "FavoriteFeature", "Features"),
                people.fields().stream().map(Schema.Field::name).toList());
        DataType location = DataType.struct(new DataType.Struct.Field("Address", DataType.VARCHAR),
                new DataType.Struct.Field("City", DataType.struct(
                        new DataType.Struct.Field("Name", DataType.VARCHAR),
                        new DataType.Struct.Field("CountryRegion", DataType.VARCHAR),
                        new DataType.Struct.Field("Region", DataType.VARCHAR))));
        assertEquals(List.of(DataType.VARCHAR, DataType.VARCHAR, DataType.VARCHAR, DataType.VARCHAR,
                DataType.VARCHAR, DataType.BIGINT, DataType.listOf(DataType.VARCHAR), DataType.listOf(location),
                location, DataType.VARCHAR, DataType.listOf(DataType.VARCHAR)), people.types());
        assertEquals(List.of(false, false, true, true, false, true, true, true, true, false, true),
                people.fields().stream().map(Schema.Field::nullable).toList());
        // The location of an airport derives from that of a person and adds a point, which is no field.
        Schema airports = catalog.schema(ref("trippin", "Airports"));
        assertEquals(location, airports.type(airports.indexOf("Location")));

        assertEquals("OrderID INTEGER NOT NULL, ProductID INTEGER NOT NULL, UnitPrice DOUBLE NOT NULL,"
                + " Quantity SMALLINT NOT NULL, Discount FLOAT NOT NULL",
                columns(catalog.schema(ref("northwind4", "Order_Details"))));
        Schema orders = catalog.schema(ref("northwind4", "Orders"));
        assertEquals(DataType.TIMESTAMP, orders.type(orders.indexOf("OrderDate")));
    }

    @Test
    void unknownTables() {
        ODataCatalog catalog = catalog("trippin");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> catalog.schema(ref("trippin", "Me")));
        assertEquals("No table erp.trippin.Me in the OData source erp", e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> catalog.schema(ref("other", "People")));
        assertEquals("No table erp.other.People in the OData source erp", e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> catalog.metadata("other"));
        assertEquals("No schema other in the OData source erp", e.getMessage());
    }

    @Test
    void entitySetOfAnUndeclaredType() {
        ServiceMetadata m = EdmxParserTest.parse(EdmxParserTest.v4("<EntityContainer Name='C'>"
                + "<EntitySet Name='Things' EntityType='M.Thing'/></EntityContainer>"));
        ODataCatalog catalog = new ODataCatalog("erp", List.of("main"), schema -> m);
        assertEquals(List.of("Things"), catalog.tables("main"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> catalog.schema(ref("main", "Things")));
        assertEquals("The entity set Things of the table erp.main.Things has the type M.Thing, which its"
                + " $metadata does not declare", e.getMessage());
    }

    @Test
    void documentsAreKeptUntilRefreshed() {
        ODataCatalog catalog = catalog("trippin", "northwind");
        ServiceMetadata first = catalog.metadata("trippin");
        catalog.tables("trippin");
        catalog.schema(ref("trippin", "People"));
        catalog.tables("northwind");
        assertSame(first, catalog.metadata("trippin"));
        assertEquals(1, loads.get("trippin").get());
        catalog.refresh("trippin");
        catalog.tables("trippin");
        catalog.tables("northwind");
        assertEquals(2, loads.get("trippin").get());
        assertEquals(1, loads.get("northwind").get());
        catalog.refresh();
        catalog.tables("northwind");
        assertEquals(2, loads.get("northwind").get());
    }
}
