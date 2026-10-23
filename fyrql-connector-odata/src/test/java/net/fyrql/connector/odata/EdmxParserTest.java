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

package net.fyrql.connector.odata;

import org.junit.jupiter.api.Test;

import javax.xml.namespace.QName;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parser of {@code $metadata} on the documents of the public sample
 * services Northwind (version 2 and 4), TripPin and GWSAMPLE_BASIC of SAP,
 * and on small documents for what those do not show.
 */
class EdmxParserTest {

    static final String SAP = "http://www.sap.com/Protocols/SAPData";

    /** Reads a document of the test resources. */
    static ServiceMetadata resource(String name) {
        try (InputStream in = EdmxParserTest.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("No resource " + name);
            }
            return EdmxParser.parse(in, name);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Reads a document given as text. */
    static ServiceMetadata parse(String xml) {
        return EdmxParser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "test");
    }

    /** Returns a document of version 4 with one schema {@code M} of the given content. */
    static String v4(String schema) {
        return "<?xml version='1.0' encoding='utf-8'?>"
                + "<edmx:Edmx Version='4.0' xmlns:edmx='http://docs.oasis-open.org/odata/ns/edmx'>"
                + "<edmx:DataServices><Schema Namespace='M' xmlns='http://docs.oasis-open.org/odata/ns/edm'>"
                + schema + "</Schema></edmx:DataServices></edmx:Edmx>";
    }

    private static List<String> names(List<?> elements) {
        List<String> names = new ArrayList<>();
        for (Object e : elements) {
            names.add(switch (e) {
                case EntitySet s -> s.name();
                case Property p -> p.name();
                case EnumType.Member m -> m.name();
                default -> throw new IllegalArgumentException(e.toString());
            });
        }
        return names;
    }

    private static Property property(List<Property> properties, String name) {
        for (Property p : properties) {
            if (p.name().equals(name)) {
                return p;
            }
        }
        throw new AssertionError("No property " + name + " in " + names(properties));
    }

    @Test
    void northwindVersion2() {
        ServiceMetadata m = resource("northwind-v2-metadata.xml");
        assertEquals(ODataVersion.V2, m.version());
        assertEquals("1.0", m.serviceVersion());
        assertEquals("ODataWeb.Northwind.Model.NorthwindEntities", m.container());
        assertEquals(26, m.entitySets().size());
        assertEquals(List.of("Categories", "CustomerDemographics", "Customers"), names(m.entitySets()).subList(0, 3));
        assertEquals("NorthwindModel.Order_Detail", m.entitySet("Order_Details").entityType());
        assertNull(m.entitySet("Nothing"));

        EntityType detail = m.entityTypes().get("NorthwindModel.Order_Detail");
        assertEquals(List.of("OrderID", "ProductID"), m.key(detail));
        assertEquals(List.of("OrderID", "ProductID", "UnitPrice", "Quantity", "Discount"),
                names(m.properties(detail)));
        Property price = property(detail.properties(), "UnitPrice");
        assertEquals("Edm.Decimal", price.type());
        assertFalse(price.nullable());
        assertEquals("19", price.precision());
        assertEquals("4", price.scale());

        EntityType category = m.entityTypes().get("NorthwindModel.Category");
        Property id = property(category.properties(), "CategoryID");
        assertEquals("Identity",
                id.attribute("http://schemas.microsoft.com/ado/2009/02/edm/annotation", "StoreGeneratedPattern"));
        Property name = property(category.properties(), "CategoryName");
        assertFalse(name.nullable());
        assertEquals("15", name.maxLength());
        assertTrue(name.attributes().isEmpty());
        Property description = property(category.properties(), "Description");
        assertTrue(description.nullable());
        assertEquals("Max", description.maxLength());
        assertEquals("Edm.Binary", property(category.properties(), "Picture").type());
        assertTrue(m.complexTypes().isEmpty());
        assertTrue(m.annotations().isEmpty());
    }

    @Test
    void northwindVersion4() {
        ServiceMetadata m = resource("northwind-v4-metadata.xml");
        assertEquals(ODataVersion.V4, m.version());
        assertEquals("4.0", m.serviceVersion());
        assertEquals("ODataWebV4.Northwind.Model.NorthwindEntities", m.container());
        assertEquals(26, m.entitySets().size());
        EntityType order = m.entityTypes().get(m.entitySet("Orders").entityType());
        assertEquals("Edm.DateTimeOffset", property(order.properties(), "OrderDate").type());
        Property price = property(m.entityTypes().get("NorthwindModel.Order_Detail").properties(), "UnitPrice");
        assertNull(price.precision());
        assertNull(price.scale());
        // The annotation of the container is kept by its target.
        List<Annotation> container = m.annotations().get("ODataWebV4.Northwind.Model.NorthwindEntities");
        assertEquals(List.of(new Annotation("Com.Microsoft.OData.Service.Conventions.V1.UrlConventions", null,
                new Annotation.Constant("String", "KeyAsSegment"))), container);
    }

    @Test
    void tripPin() {
        ServiceMetadata m = resource("trippin-metadata.xml");
        assertEquals(ODataVersion.V4, m.version());
        assertEquals("Trippin.Container", m.container());
        // The singleton Me is no entity set.
        assertEquals(List.of("People", "Airlines", "Airports"), names(m.entitySets()));

        EntityType person = m.entityTypes().get("Trippin.Person");
        assertEquals(List.of("UserName"), person.key());
        assertEquals(List.of("UserName", "FirstName", "LastName", "MiddleName", "Gender", "Age", "Emails",
                "AddressInfo", "HomeAddress", "FavoriteFeature", "Features"), names(person.properties()));
        assertEquals("Collection(Edm.String)", property(person.properties(), "Emails").type());
        Property addresses = property(person.properties(), "AddressInfo");
        assertTrue(addresses.isCollection());
        assertEquals("Trippin.Location", addresses.elementType());
        assertEquals("Trippin.PersonGender", property(person.properties(), "Gender").type());

        // Inheritance: the properties of the base types come first, the key is inherited.
        EntityType flight = m.entityTypes().get("Trippin.Flight");
        assertEquals("Trippin.PublicTransportation", flight.baseType());
        assertTrue(flight.key().isEmpty());
        assertEquals(List.of("PlanItemId"), m.key(flight));
        assertEquals(List.of("PlanItemId", "ConfirmationCode", "StartsAt", "EndsAt", "Duration", "SeatNumber",
                "FlightNumber"), names(m.properties(flight)));
        ComplexType airportLocation = m.complexTypes().get("Trippin.AirportLocation");
        assertEquals(List.of("Address", "City", "Loc"), names(m.properties(airportLocation)));

        EnumType gender = m.enumTypes().get("Trippin.PersonGender");
        assertEquals("Edm.Int32", gender.underlyingType());
        assertFalse(gender.flags());
        assertEquals(List.of("Male", "Female", "Unknown"), names(gender.members()));
        assertEquals("1", gender.members().get(1).value());

        assertEquals(List.of(new Annotation("Org.OData.Core.V1.OptimisticConcurrency", null,
                new Annotation.Collection(List.of(new Annotation.Constant("PropertyPath", "Name"))))),
                m.entitySet("Airlines").annotations());
        assertTrue(m.entitySet("People").annotations().isEmpty());
    }

    @Test
    void gatewaySample() {
        ServiceMetadata m = resource("gwsample-basic-metadata.xml");
        assertEquals(ODataVersion.V2, m.version());
        assertEquals("2.0", m.serviceVersion());
        assertEquals("GWSAMPLE_BASIC.GWSAMPLE_BASIC_Entities", m.container());
        assertEquals(16, m.entitySets().size());
        assertEquals(List.of("BusinessPartnerSet", "ProductSet", "SalesOrderSet", "SalesOrderLineItemSet",
                "ContactSet"), names(m.entitySets()).subList(0, 5));

        EntitySet sex = m.entitySet("VH_SexSet");
        assertEquals("false", sex.attribute(SAP, "pageable"));
        assertEquals("1", sex.attribute(SAP, "content-version"));
        assertNull(m.entitySet("ProductSet").attribute(SAP, "pageable"));
        assertEquals("false", m.entitySet("SalesOrderSet").attribute(SAP, "updatable"));

        EntityType partner = m.entityTypes().get("GWSAMPLE_BASIC.BusinessPartner");
        assertEquals("1", partner.attribute(SAP, "content-version"));
        Property address = property(partner.properties(), "Address");
        assertEquals("GWSAMPLE_BASIC.CT_Address", address.type());
        assertFalse(address.nullable());
        Property web = property(partner.properties(), "WebAddress");
        assertEquals("false", web.attribute(SAP, "filterable"));
        assertEquals("false", web.attribute(SAP, "sortable"));
        assertEquals("url", web.attribute(SAP, "semantics"));
        assertEquals("Bus. Part. ID", property(partner.properties(), "BusinessPartnerID").attribute(SAP, "label"));
        Property created = property(partner.properties(), "CreatedAt");
        assertEquals("Edm.DateTime", created.type());
        assertEquals("7", created.precision());

        EntityType product = m.entityTypes().get("GWSAMPLE_BASIC.Product");
        Property weight = property(product.properties(), "WeightMeasure");
        assertEquals("13", weight.precision());
        assertEquals("3", weight.scale());
        assertEquals("WeightUnit", weight.attribute(SAP, "unit"));
        assertEquals("Edm.Byte", property(product.properties(), "TaxTarifCode").type());

        assertEquals(List.of("City", "PostalCode", "Street", "Building", "Country", "AddressType"),
                names(m.complexTypes().get("GWSAMPLE_BASIC.CT_Address").properties()));
        assertEquals(List.of("ContactGuid"), m.entityTypes().get("GWSAMPLE_BASIC.Contact").key());
    }

    @Test
    void aliasesAreReplacedAndTargetedAnnotationsPlaced() {
        ServiceMetadata m = parse("<?xml version='1.0' encoding='utf-8'?>"
                + "<edmx:Edmx Version='4.01' xmlns:edmx='http://docs.oasis-open.org/odata/ns/edmx'>"
                + "<edmx:Reference Uri='https://oasis-tcs.github.io/odata-vocabularies/vocabularies/"
                + "Org.OData.Capabilities.V1.xml'>"
                + "<edmx:Include Namespace='Org.OData.Capabilities.V1' Alias='Capabilities'/></edmx:Reference>"
                + "<edmx:DataServices>"
                + "<Schema Namespace='com.example.Model' Alias='self' xmlns='http://docs.oasis-open.org/odata/ns/edm'>"
                + "<ComplexType Name='Address'><Property Name='City' Type='Edm.String'/></ComplexType>"
                + "<TypeDefinition Name='Amount' UnderlyingType='Edm.Decimal' Precision='10' Scale='2'/>"
                + "<EntityType Name='Item'><Key><PropertyRef Name='Id'/></Key>"
                + "<Property Name='Id' Type='Edm.Int64' Nullable='false'/>"
                + "<Property Name='Home' Type='self.Address'/>"
                + "<Property Name='Others' Type='Collection(self.Address)'/>"
                + "<Property Name='Price' Type='self.Amount'>"
                + "<Annotation Term='Capabilities.Unknown' Bool='true'/></Property>"
                + "<NavigationProperty Name='Parent' Type='self.Item'/></EntityType>"
                + "<EntityContainer Name='Container'><EntitySet Name='Items' EntityType='self.Item'>"
                + "<NavigationPropertyBinding Path='Parent' Target='Items'/></EntitySet></EntityContainer>"
                + "<Annotations Target='self.Container/Items'>"
                + "<Annotation Term='Capabilities.FilterRestrictions'><Record>"
                + "<PropertyValue Property='Filterable' Bool='true'/>"
                + "<PropertyValue Property='NonFilterableProperties'>"
                + "<Collection><PropertyPath>Home</PropertyPath><PropertyPath>Others</PropertyPath></Collection>"
                + "</PropertyValue></Record></Annotation>"
                + "<Annotation Term='Capabilities.SearchRestrictions' Qualifier='q'><Record>"
                + "<PropertyValue Property='UnsupportedExpressions'>"
                + "<EnumMember>Capabilities.SearchExpressions/AND Capabilities.SearchExpressions/OR</EnumMember>"
                + "</PropertyValue></Record></Annotation>"
                + "<Annotation Term='Capabilities.Computed'/></Annotations>"
                + "<Annotations Target='self.Item/Id'><Annotation Term='Core.Computed'>"
                + "<If><Bool>true</Bool><Bool>true</Bool><Bool>false</Bool></If></Annotation></Annotations>"
                + "<Annotations Target='self.Address'><Annotation Term='Capabilities.X' String=' a b '/>"
                + "</Annotations>"
                + "<Annotations Target='self.Container'><Annotation Term='Capabilities.Y'><Null/></Annotation>"
                + "</Annotations>"
                + "</Schema></edmx:DataServices></edmx:Edmx>");
        assertEquals("4.01", m.serviceVersion());
        assertEquals("com.example.Model.Container", m.container());
        EntitySet items = m.entitySet("Items");
        assertEquals("com.example.Model.Item", items.entityType());
        EntityType item = m.entityTypes().get("com.example.Model.Item");
        assertEquals(List.of("Id", "Home", "Others", "Price"), names(item.properties()));
        assertEquals("com.example.Model.Address", property(item.properties(), "Home").type());
        assertEquals("Collection(com.example.Model.Address)", property(item.properties(), "Others").type());
        assertEquals("com.example.Model.Amount", property(item.properties(), "Price").type());
        assertEquals(List.of(new Annotation("Org.OData.Capabilities.V1.Unknown", null,
                new Annotation.Constant("Bool", "true"))), property(item.properties(), "Price").annotations());
        // Core is no alias of this document, so the term stays as written.
        assertEquals(List.of(new Annotation("Core.Computed", null, new Annotation.Expression("If"))),
                property(item.properties(), "Id").annotations());
        TypeDefinition amount = m.typeDefinitions().get("com.example.Model.Amount");
        assertEquals("Edm.Decimal", amount.underlyingType());
        assertEquals("10", amount.precision());

        List<Annotation> annotations = items.annotations();
        assertEquals(3, annotations.size());
        Annotation filter = annotations.get(0);
        assertEquals("Org.OData.Capabilities.V1.FilterRestrictions", filter.term());
        Annotation.Record record = (Annotation.Record) filter.value();
        assertEquals(new Annotation.Constant("Bool", "true"), record.properties().get("Filterable"));
        assertEquals(new Annotation.Collection(List.of(new Annotation.Constant("PropertyPath", "Home"),
                new Annotation.Constant("PropertyPath", "Others"))),
                record.properties().get("NonFilterableProperties"));
        Annotation search = annotations.get(1);
        assertEquals("q", search.qualifier());
        assertEquals(new Annotation.Constant("EnumMember", "Org.OData.Capabilities.V1.SearchExpressions/AND"
                + " Org.OData.Capabilities.V1.SearchExpressions/OR"),
                ((Annotation.Record) search.value()).properties().get("UnsupportedExpressions"));
        assertEquals(new Annotation("Org.OData.Capabilities.V1.Computed", null, null), annotations.get(2));
        // A string keeps its spaces.
        assertEquals(List.of(new Annotation("Org.OData.Capabilities.V1.X", null,
                new Annotation.Constant("String", " a b "))),
                m.complexTypes().get("com.example.Model.Address").annotations());
        assertEquals(Map.of("com.example.Model.Container", List.of(new Annotation("Org.OData.Capabilities.V1.Y",
                null, new Annotation.Constant("Null", "")))), m.annotations());
    }

    @Test
    void version2WithAnnotationsOfVersion4() {
        // Services of SAP put annotations in the namespace of version 4 into documents of version 2.
        ServiceMetadata m = parse("<?xml version='1.0' encoding='utf-8'?>"
                + "<edmx:Edmx Version='1.0' xmlns:edmx='http://schemas.microsoft.com/ado/2007/06/edmx'"
                + " xmlns:m='http://schemas.microsoft.com/ado/2007/08/dataservices/metadata'"
                + " xmlns:sap='http://www.sap.com/Protocols/SAPData'>"
                + "<edmx:Reference Uri='x' xmlns:edmx='http://docs.oasis-open.org/odata/ns/edmx'>"
                + "<edmx:Include Namespace='com.sap.vocabularies.Common.v1' Alias='Common'/></edmx:Reference>"
                + "<edmx:DataServices m:DataServiceVersion='2.0'>"
                + "<Schema Namespace='Z_SRV' xmlns='http://schemas.microsoft.com/ado/2008/09/edm'>"
                + "<EntityType Name='Order'><Key><PropertyRef Name='Id'/></Key>"
                + "<Property Name='Id' Type='Edm.String' Nullable='false' sap:filterable='true'/></EntityType>"
                + "<EntityContainer Name='Other'><EntitySet Name='Wrong' EntityType='Z_SRV.Order'/></EntityContainer>"
                + "<EntityContainer Name='Z_SRV_Entities' m:IsDefaultEntityContainer='true'>"
                + "<EntitySet Name='Orders' EntityType='Z_SRV.Order' sap:pageable='false'/>"
                + "<EntitySet Name='Orders' EntityType='Z_SRV.Order'/></EntityContainer>"
                + "<Annotations Target='Z_SRV.Z_SRV_Entities/Orders' xmlns='http://docs.oasis-open.org/odata/ns/edm'>"
                + "<Annotation Term='Common.Label' String='Orders'/></Annotations>"
                + "<Annotations Target='Z_SRV.Order/Id' xmlns='http://docs.oasis-open.org/odata/ns/edm'>"
                + "<Annotation Term='Common.Text' Path='Name'/></Annotations>"
                + "</Schema></edmx:DataServices></edmx:Edmx>");
        assertEquals(ODataVersion.V2, m.version());
        assertEquals("2.0", m.serviceVersion());
        // The default container is used, also if it is not the first; a set of the same name is kept once.
        assertEquals("Z_SRV.Z_SRV_Entities", m.container());
        assertEquals(List.of("Orders"), names(m.entitySets()));
        EntitySet orders = m.entitySet("Orders");
        assertEquals("false", orders.attribute(SAP, "pageable"));
        assertEquals(Map.of(new QName(SAP, "pageable"), "false"), orders.attributes());
        assertEquals(List.of(new Annotation("com.sap.vocabularies.Common.v1.Label", null,
                new Annotation.Constant("String", "Orders"))), orders.annotations());
        Property id = m.entityTypes().get("Z_SRV.Order").properties().get(0);
        assertEquals("true", id.attribute(SAP, "filterable"));
        assertEquals(List.of(new Annotation("com.sap.vocabularies.Common.v1.Text", null,
                new Annotation.Constant("Path", "Name"))), id.annotations());
    }

    @Test
    void versions() {
        for (String v2 : List.of("v2", "V2", "2", "2.0", " v2 ")) {
            assertEquals(ODataVersion.V2, ODataVersion.parse(v2), v2);
        }
        for (String v4 : List.of("v4", "4", "4.0", "4.01", "V4.01")) {
            assertEquals(ODataVersion.V4, ODataVersion.parse(v4), v4);
        }
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ODataVersion.parse("v3"));
        assertEquals("Unknown OData version v3; known are v2 and v4", e.getMessage());
        assertEquals("/d/results", ODataVersion.V2.recordsPointer());
        assertEquals("@odata.nextLink", ODataVersion.V4.nextLinkKey());
        assertEquals("__count", ODataVersion.V2.countKey());
    }

    @Test
    void annotationsNestAtMost64Expressions() {
        String type = "<EntityType Name='T'><Key><PropertyRef Name='a'/></Key><Property Name='a' Type='Edm.Int32'/>"
                + "</EntityType>";
        int allowed = EdmxParser.MAX_DEPTH;
        ServiceMetadata m = parse(v4(type + "<Annotations Target='M.T'><Annotation Term='M.X'>"
                + "<Collection>".repeat(allowed) + "</Collection>".repeat(allowed) + "</Annotation></Annotations>"));
        assertEquals(1, m.entityTypes().size());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> parse(v4(type
                + "<Annotations Target='M.T'><Annotation Term='M.X'>" + "<Collection>".repeat(allowed + 1)
                + "</Collection>".repeat(allowed + 1) + "</Annotation></Annotations>")));
        assertTrue(e.getMessage().endsWith(": an annotation nests more than 64 expressions"), e.getMessage());
    }

    @Test
    void documentWithoutContainer() {
        ServiceMetadata m = parse(v4("<EntityType Name='T'><Key><PropertyRef Name='a'/></Key>"
                + "<Property Name='a' Type='Edm.Int32'/></EntityType>"));
        assertNull(m.container());
        assertTrue(m.entitySets().isEmpty());
        assertEquals(1, m.entityTypes().size());
    }

    @Test
    void baseTypesThatAreMissingOrCircular() {
        ServiceMetadata m = parse(v4("<EntityType Name='A' BaseType='M.B'/><EntityType Name='B' BaseType='M.A'/>"
                + "<EntityType Name='C' BaseType='M.Nothing'/>"
                + "<ComplexType Name='X' BaseType='M.X'/>"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> m.properties(m.entityTypes().get("M.A")));
        assertEquals("The entity type M.A derives from itself through M.A", e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> m.key(m.entityTypes().get("M.C")));
        assertEquals("The base type M.Nothing of the entity type M.C is not declared", e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> m.properties(m.complexTypes().get("M.X")));
        assertEquals("The complex type M.X derives from itself through M.X", e.getMessage());
    }

    @Test
    void documentTypeDeclarationsAreRefused() {
        String external = "<?xml version='1.0'?><!DOCTYPE edmx:Edmx [<!ENTITY x SYSTEM 'file:///etc/passwd'>]>"
                + "<edmx:Edmx Version='4.0' xmlns:edmx='http://docs.oasis-open.org/odata/ns/edmx'>"
                + "<edmx:DataServices><Schema Namespace='&x;' xmlns='http://docs.oasis-open.org/odata/ns/edm'/>"
                + "</edmx:DataServices></edmx:Edmx>";
        String refused = "The $metadata of test is not valid at line 1: a document type declaration is not allowed";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> parse(external));
        assertEquals(refused, e.getMessage());
        String laughs = "<?xml version='1.0'?><!DOCTYPE r [<!ENTITY a 'aaaaaaaaaa'>"
                + "<!ENTITY b '&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;'><!ENTITY c '&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;'>]>"
                + "<edmx:Edmx Version='4.0' xmlns:edmx='http://docs.oasis-open.org/odata/ns/edmx'>&c;</edmx:Edmx>";
        e = assertThrows(IllegalArgumentException.class, () -> parse(laughs));
        assertEquals(refused, e.getMessage());
        String external2 = "<?xml version='1.0'?><!DOCTYPE r SYSTEM 'http://127.0.0.1:1/x.dtd'>"
                + "<edmx:Edmx Version='4.0' xmlns:edmx='http://docs.oasis-open.org/odata/ns/edmx'/>";
        e = assertThrows(IllegalArgumentException.class, () -> parse(external2));
        assertEquals(refused, e.getMessage());
    }

    @Test
    void documentsThatAreNoMetadata() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> parse("<html><body>Login</body></html>"));
        assertEquals("The $metadata of test is not valid at line 1: the root element is html, not an edmx:Edmx"
                + " of OData version 2 or 4", e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> parse("{\"d\": []}"));
        assertTrue(e.getMessage().startsWith("The $metadata of test is not well-formed XML: "), e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> parse(v4("<EntityType Name='T'>")));
        assertTrue(e.getMessage().startsWith("The $metadata of test is not well-formed XML: "), e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> parse(""));
        assertTrue(e.getMessage().startsWith("The $metadata of test is not well-formed XML: "), e.getMessage());
        assertFalse(e.getMessage().contains("\n"), e.getMessage());
    }

    @Test
    void missingAttributesAndUnknownVersions() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> parse(v4("\n<EntityType>\n<Property Name='a' Type='Edm.Int32'/></EntityType>")));
        assertEquals("The $metadata of test is not valid at line 2: the element EntityType has no attribute Name",
                e.getMessage());
        e = assertThrows(IllegalArgumentException.class,
                () -> parse(v4("<EntityType Name='T'><Property Name='a'/></EntityType>")));
        assertTrue(e.getMessage().endsWith(": the element Property has no attribute Type"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> parse("<?xml version='1.0'?>"
                + "<edmx:Edmx Version='1.0' xmlns:edmx='http://schemas.microsoft.com/ado/2007/06/edmx'"
                + " xmlns:m='http://schemas.microsoft.com/ado/2007/08/dataservices/metadata'>"
                + "<edmx:DataServices m:DataServiceVersion='4.0'/></edmx:Edmx>"));
        assertEquals("The $metadata of test has the DataServiceVersion 4.0, of which 1.0, 2.0 and 3.0 are"
                + " supported", e.getMessage());
    }
}
