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

import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.stream.Location;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the {@code $metadata} document of an OData service, in the EDMX of
 * version 2 (and 1 and 3) or of version 4, into a {@link ServiceMetadata}.
 * Only what reading entity sets needs is kept: entity, complex and
 * enumeration types, type definitions, the entity sets of the entity
 * container, and annotations. Elements are recognized by their local
 * names, whatever version of the namespace of CSDL they are in, since
 * services of version 2 mix them; attributes in other namespaces, such as
 * those of SAP, are kept as they are. Navigation properties, associations,
 * functions, actions, singletons and terms are skipped.
 * <p>
 * The parser is safe against hostile documents: it refuses a document
 * type declaration and resolves no external entity.
 */
final class EdmxParser {

    /** The namespace of EDMX of version 4. */
    static final String EDMX_V4 = "http://docs.oasis-open.org/odata/ns/edmx";

    /** The namespace of EDMX of version 1 to 3. */
    static final String EDMX_V2 = "http://schemas.microsoft.com/ado/2007/06/edmx";

    /** The constant expressions and paths of annotations, as elements or attributes. */
    private static final Set<String> CONSTANTS = Set.of("Binary", "Bool", "Date", "DateTimeOffset", "Decimal",
            "Duration", "EnumMember", "Float", "Guid", "Int", "String", "TimeOfDay", "AnnotationPath",
            "ModelElementPath", "NavigationPropertyPath", "Path", "PropertyPath");

    /** The most expressions of an annotation one inside the other. */
    static final int MAX_DEPTH = 64;

    private final XMLStreamReader reader;
    private final String source;
    private final Map<String, String> aliases = new HashMap<>();
    private final Map<String, EntityType> entityTypes = new LinkedHashMap<>();
    private final Map<String, ComplexType> complexTypes = new LinkedHashMap<>();
    private final Map<String, EnumType> enumTypes = new LinkedHashMap<>();
    private final Map<String, TypeDefinition> typeDefinitions = new LinkedHashMap<>();
    private final List<Container> containers = new ArrayList<>();
    private final List<Targeted> targeted = new ArrayList<>();
    /** The expressions of annotations being read, one inside the other. */
    private int depth;

    /** An entity container as declared: its qualified name, whether it is the default, and its entity sets. */
    private record Container(String name, boolean isDefault, List<EntitySet> sets) {
    }

    /** An annotation given in an {@code Annotations} element, with the target as written. */
    private record Targeted(String target, Annotation annotation) {
    }

    private EdmxParser(XMLStreamReader reader, String source) {
        this.reader = reader;
        this.source = source;
    }

    /**
     * Reads a {@code $metadata} document; the stream is not closed.
     *
     * @param source names the document in messages, such as its URL
     * @throws IllegalArgumentException if the document is not well-formed,
     *         not EDMX, has a document type declaration, or lacks a required
     *         attribute
     */
    static ServiceMetadata parse(InputStream in, String source) {
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        XMLStreamReader reader = null;
        try {
            reader = factory.createXMLStreamReader(in);
            return new EdmxParser(reader, source).document();
        } catch (XMLStreamException e) {
            throw new IllegalArgumentException("The $metadata of " + source + " is not well-formed XML: "
                    + e.getMessage().replaceAll("\\s+", " ").strip(), e);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (XMLStreamException e) {
                    // The document has been read or has failed already.
                }
            }
        }
    }

    private ServiceMetadata document() throws XMLStreamException {
        while (true) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                break;
            }
            if (event == XMLStreamConstants.DTD) {
                throw error("a document type declaration is not allowed");
            }
            if (event == XMLStreamConstants.END_DOCUMENT) {
                throw error("the document has no element");
            }
        }
        String namespace = reader.getNamespaceURI();
        if (!reader.getLocalName().equals("Edmx") || !(EDMX_V4.equals(namespace) || EDMX_V2.equals(namespace))) {
            throw error("the root element is " + new QName(namespace, reader.getLocalName())
                    + ", not an edmx:Edmx of OData version 2 or 4");
        }
        ODataVersion version = EDMX_V4.equals(namespace) ? ODataVersion.V4 : ODataVersion.V2;
        String serviceVersion = version == ODataVersion.V4 ? attribute("Version") : null;
        while (nextChild()) {
            switch (reader.getLocalName()) {
                case "Reference" -> reference();
                case "DataServices" -> {
                    if (version == ODataVersion.V2) {
                        serviceVersion = foreignAttribute("DataServiceVersion");
                    }
                    while (nextChild()) {
                        if (reader.getLocalName().equals("Schema")) {
                            schema();
                        } else {
                            skip();
                        }
                    }
                }
                default -> skip();
            }
        }
        if (version == ODataVersion.V2) {
            if (serviceVersion == null) {
                serviceVersion = "1.0";
            } else if (!serviceVersion.matches("[123]\\.0")) {
                throw new IllegalArgumentException("The $metadata of " + source + " has the DataServiceVersion "
                        + serviceVersion + ", of which 1.0, 2.0 and 3.0 are supported");
            }
        } else if (serviceVersion == null) {
            serviceVersion = "4.0";
        }
        return resolve(version, serviceVersion);
    }

    private void reference() throws XMLStreamException {
        while (nextChild()) {
            if (reader.getLocalName().equals("Include")) {
                String namespace = required("Namespace");
                String alias = attribute("Alias");
                if (alias != null) {
                    aliases.putIfAbsent(alias, namespace);
                }
            }
            skip();
        }
    }

    private void schema() throws XMLStreamException {
        String namespace = required("Namespace");
        String alias = attribute("Alias");
        if (alias != null) {
            aliases.putIfAbsent(alias, namespace);
        }
        while (nextChild()) {
            switch (reader.getLocalName()) {
                case "EntityType" -> entityType(namespace);
                case "ComplexType" -> complexType(namespace);
                case "EnumType" -> enumType(namespace);
                case "TypeDefinition" -> typeDefinition(namespace);
                case "EntityContainer" -> container(namespace);
                case "Annotations" -> annotations();
                default -> skip();
            }
        }
    }

    private void entityType(String namespace) throws XMLStreamException {
        String name = namespace + "." + required("Name");
        String baseType = attribute("BaseType");
        Map<QName, String> attributes = foreignAttributes();
        List<String> key = new ArrayList<>();
        List<Property> properties = new ArrayList<>();
        List<Annotation> annotations = new ArrayList<>();
        while (nextChild()) {
            switch (reader.getLocalName()) {
                case "Key" -> {
                    while (nextChild()) {
                        if (reader.getLocalName().equals("PropertyRef")) {
                            key.add(required("Name"));
                        }
                        skip();
                    }
                }
                case "Property" -> properties.add(property());
                case "Annotation" -> annotations.add(annotation(null));
                default -> skip();
            }
        }
        entityTypes.putIfAbsent(name, new EntityType(name, baseType, key, properties, attributes, annotations));
    }

    private void complexType(String namespace) throws XMLStreamException {
        String name = namespace + "." + required("Name");
        String baseType = attribute("BaseType");
        Map<QName, String> attributes = foreignAttributes();
        List<Property> properties = new ArrayList<>();
        List<Annotation> annotations = new ArrayList<>();
        while (nextChild()) {
            switch (reader.getLocalName()) {
                case "Property" -> properties.add(property());
                case "Annotation" -> annotations.add(annotation(null));
                default -> skip();
            }
        }
        complexTypes.putIfAbsent(name, new ComplexType(name, baseType, properties, attributes, annotations));
    }

    private Property property() throws XMLStreamException {
        String name = required("Name");
        String type = required("Type");
        boolean nullable = !"false".equals(attribute("Nullable"));
        String maxLength = attribute("MaxLength");
        String precision = attribute("Precision");
        String scale = attribute("Scale");
        Map<QName, String> attributes = foreignAttributes();
        List<Annotation> annotations = new ArrayList<>();
        while (nextChild()) {
            if (reader.getLocalName().equals("Annotation")) {
                annotations.add(annotation(null));
            } else {
                skip();
            }
        }
        return new Property(name, type, nullable, maxLength, precision, scale, attributes, annotations);
    }

    private void enumType(String namespace) throws XMLStreamException {
        String name = namespace + "." + required("Name");
        String underlying = attribute("UnderlyingType");
        boolean flags = "true".equals(attribute("IsFlags"));
        List<EnumType.Member> members = new ArrayList<>();
        while (nextChild()) {
            if (reader.getLocalName().equals("Member")) {
                members.add(new EnumType.Member(required("Name"), attribute("Value")));
            }
            skip();
        }
        enumTypes.putIfAbsent(name, new EnumType(name, underlying == null ? "Edm.Int32" : underlying, flags,
                members));
    }

    private void typeDefinition(String namespace) throws XMLStreamException {
        String name = namespace + "." + required("Name");
        String underlying = required("UnderlyingType");
        String maxLength = attribute("MaxLength");
        String precision = attribute("Precision");
        String scale = attribute("Scale");
        List<Annotation> annotations = new ArrayList<>();
        while (nextChild()) {
            if (reader.getLocalName().equals("Annotation")) {
                annotations.add(annotation(null));
            } else {
                skip();
            }
        }
        typeDefinitions.putIfAbsent(name, new TypeDefinition(name, underlying, maxLength, precision, scale,
                annotations));
    }

    private void container(String namespace) throws XMLStreamException {
        String name = namespace + "." + required("Name");
        boolean isDefault = "true".equals(foreignAttribute("IsDefaultEntityContainer"));
        List<EntitySet> sets = new ArrayList<>();
        while (nextChild()) {
            if (reader.getLocalName().equals("EntitySet")) {
                String set = required("Name");
                String type = required("EntityType");
                Map<QName, String> attributes = foreignAttributes();
                List<Annotation> annotations = new ArrayList<>();
                while (nextChild()) {
                    if (reader.getLocalName().equals("Annotation")) {
                        annotations.add(annotation(null));
                    } else {
                        skip();
                    }
                }
                sets.add(new EntitySet(set, type, attributes, annotations));
            } else {
                skip();
            }
        }
        containers.add(new Container(name, isDefault, sets));
    }

    private void annotations() throws XMLStreamException {
        String target = required("Target");
        String qualifier = attribute("Qualifier");
        while (nextChild()) {
            if (reader.getLocalName().equals("Annotation")) {
                targeted.add(new Targeted(target, annotation(qualifier)));
            } else {
                skip();
            }
        }
    }

    /** Reads an {@code Annotation} element; the qualifier applies if the element has none. */
    private Annotation annotation(String qualifier) throws XMLStreamException {
        String term = required("Term");
        String own = attribute("Qualifier");
        Annotation.Value value = constantAttribute();
        while (nextChild()) {
            if (value == null && !reader.getLocalName().equals("Annotation")) {
                value = expression();
            } else {
                skip();
            }
        }
        return new Annotation(term, own != null ? own : qualifier, value);
    }

    /** Returns the value of an annotation or property value given as an attribute, or {@code null}. */
    private Annotation.Constant constantAttribute() {
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            String ns = reader.getAttributeNamespace(i);
            String local = reader.getAttributeLocalName(i);
            if ((ns == null || ns.isEmpty()) && CONSTANTS.contains(local)) {
                return new Annotation.Constant(local, reader.getAttributeValue(i));
            }
        }
        return null;
    }

    /**
     * Reads the expression at the current element, up to its end; one
     * nested deeper than {@link #MAX_DEPTH} expressions is an error rather
     * than a reason to run out of stack.
     */
    private Annotation.Value expression() throws XMLStreamException {
        if (depth == MAX_DEPTH) {
            throw error("an annotation nests more than " + MAX_DEPTH + " expressions");
        }
        depth++;
        try {
            return nestedExpression();
        } finally {
            depth--;
        }
    }

    private Annotation.Value nestedExpression() throws XMLStreamException {
        String kind = reader.getLocalName();
        if (CONSTANTS.contains(kind)) {
            String text = reader.getElementText();
            return new Annotation.Constant(kind, kind.equals("String") ? text : text.strip());
        }
        switch (kind) {
            case "Null" -> {
                skip();
                return new Annotation.Constant("Null", "");
            }
            case "Record" -> {
                String type = attribute("Type");
                Map<String, Annotation.Value> properties = new LinkedHashMap<>();
                while (nextChild()) {
                    if (reader.getLocalName().equals("PropertyValue")) {
                        String property = required("Property");
                        Annotation.Value value = constantAttribute();
                        while (nextChild()) {
                            if (value == null && !reader.getLocalName().equals("Annotation")) {
                                value = expression();
                            } else {
                                skip();
                            }
                        }
                        if (value != null) {
                            properties.putIfAbsent(property, value);
                        }
                    } else {
                        skip();
                    }
                }
                return new Annotation.Record(type, properties);
            }
            case "Collection" -> {
                List<Annotation.Value> items = new ArrayList<>();
                while (nextChild()) {
                    items.add(expression());
                }
                return new Annotation.Collection(items);
            }
            default -> {
                skip();
                return new Annotation.Expression(kind);
            }
        }
    }

    /** Builds the result, with every name qualified by its namespace and the targeted annotations in place. */
    private ServiceMetadata resolve(ODataVersion version, String serviceVersion) {
        Map<String, List<Annotation>> byTarget = new LinkedHashMap<>();
        for (Targeted t : targeted) {
            byTarget.computeIfAbsent(target(t.target()), k -> new ArrayList<>()).add(resolved(t.annotation()));
        }

        Map<String, EntityType> entities = new LinkedHashMap<>();
        for (EntityType t : entityTypes.values()) {
            entities.put(t.name(), new EntityType(t.name(), qualified(t.baseType()), t.key(),
                    properties(t.name(), t.properties(), byTarget), t.attributes(),
                    annotations(t.annotations(), byTarget.remove(t.name()))));
        }
        Map<String, ComplexType> complexes = new LinkedHashMap<>();
        for (ComplexType t : complexTypes.values()) {
            complexes.put(t.name(), new ComplexType(t.name(), qualified(t.baseType()),
                    properties(t.name(), t.properties(), byTarget), t.attributes(),
                    annotations(t.annotations(), byTarget.remove(t.name()))));
        }
        Map<String, EnumType> enums = new LinkedHashMap<>();
        for (EnumType t : enumTypes.values()) {
            enums.put(t.name(), new EnumType(t.name(), qualified(t.underlyingType()), t.flags(), t.members()));
        }
        Map<String, TypeDefinition> definitions = new LinkedHashMap<>();
        for (TypeDefinition t : typeDefinitions.values()) {
            definitions.put(t.name(), new TypeDefinition(t.name(), qualified(t.underlyingType()), t.maxLength(),
                    t.precision(), t.scale(), annotations(t.annotations(), byTarget.remove(t.name()))));
        }

        Container container = null;
        for (Container c : containers) {
            if (c.isDefault()) {
                container = c;
                break;
            }
        }
        if (container == null && !containers.isEmpty()) {
            container = containers.get(0);
        }
        List<EntitySet> sets = new ArrayList<>();
        if (container != null) {
            List<String> names = new ArrayList<>();
            for (EntitySet s : container.sets()) {
                if (!names.contains(s.name())) {
                    names.add(s.name());
                    sets.add(new EntitySet(s.name(), qualified(s.entityType()), s.attributes(),
                            annotations(s.annotations(), byTarget.remove(container.name() + "/" + s.name()))));
                }
            }
        }
        return new ServiceMetadata(version, serviceVersion, container == null ? null : container.name(), sets,
                entities, complexes, enums, definitions, byTarget);
    }

    private List<Property> properties(String type, List<Property> properties, Map<String, List<Annotation>> byTarget) {
        List<Property> resolved = new ArrayList<>();
        for (Property p : properties) {
            resolved.add(new Property(p.name(), qualified(p.type()), p.nullable(), p.maxLength(), p.precision(),
                    p.scale(), p.attributes(), annotations(p.annotations(), byTarget.remove(type + "/" + p.name()))));
        }
        return resolved;
    }

    /** Returns the annotations of an element and those that target it, with their names qualified. */
    private List<Annotation> annotations(List<Annotation> own, List<Annotation> targeting) {
        List<Annotation> all = new ArrayList<>();
        for (Annotation a : own) {
            all.add(resolved(a));
        }
        if (targeting != null) {
            all.addAll(targeting);
        }
        return all;
    }

    private Annotation resolved(Annotation a) {
        return new Annotation(qualified(a.term()), a.qualifier(), value(a.value()));
    }

    private Annotation.Value value(Annotation.Value v) {
        if (v instanceof Annotation.Constant c && c.kind().equals("EnumMember")) {
            StringBuilder text = new StringBuilder();
            for (String member : c.text().strip().split("\\s+")) {
                int slash = member.indexOf('/');
                if (!text.isEmpty()) {
                    text.append(' ');
                }
                text.append(slash < 0 ? member : qualified(member.substring(0, slash)) + member.substring(slash));
            }
            return new Annotation.Constant(c.kind(), text.toString());
        }
        if (v instanceof Annotation.Record r) {
            Map<String, Annotation.Value> properties = new LinkedHashMap<>();
            r.properties().forEach((k, x) -> properties.put(k, value(x)));
            return new Annotation.Record(qualified(r.type()), properties);
        }
        if (v instanceof Annotation.Collection c) {
            List<Annotation.Value> items = new ArrayList<>();
            for (Annotation.Value x : c.items()) {
                items.add(value(x));
            }
            return new Annotation.Collection(items);
        }
        return v;
    }

    /** Qualifies the first segment of a target, as in {@code Alias.Container/Set}. */
    private String target(String target) {
        int slash = target.indexOf('/');
        return slash < 0 ? qualified(target) : qualified(target.substring(0, slash)) + target.substring(slash);
    }

    /**
     * Replaces the alias of a qualified name by its namespace, also inside
     * {@code Collection(...)}; a name without an alias stays as it is.
     */
    private String qualified(String name) {
        if (name == null) {
            return null;
        }
        if (name.startsWith("Collection(") && name.endsWith(")")) {
            return "Collection(" + qualified(name.substring("Collection(".length(), name.length() - 1)) + ")";
        }
        int paren = name.indexOf('(');
        if (paren >= 0) {
            return qualified(name.substring(0, paren)) + name.substring(paren);
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return name;
        }
        String namespace = aliases.get(name.substring(0, dot));
        return namespace == null ? name : namespace + name.substring(dot);
    }

    /** Moves to the next child of the current element and tells whether there is one. */
    private boolean nextChild() throws XMLStreamException {
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                return true;
            }
            if (event == XMLStreamConstants.END_ELEMENT) {
                return false;
            }
        }
        throw error("the document ends early");
    }

    /** Skips the rest of the current element, up to its end. */
    private void skip() throws XMLStreamException {
        int depth = 1;
        while (depth > 0) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
    }

    /** Returns an attribute without a namespace, or {@code null}. */
    private String attribute(String name) {
        String value = reader.getAttributeValue(XMLConstants.NULL_NS_URI, name);
        return value == null ? reader.getAttributeValue("", name) : value;
    }

    private String required(String name) {
        String value = attribute(name);
        if (value == null) {
            throw error("the element " + reader.getLocalName() + " has no attribute " + name);
        }
        return value;
    }

    /** Returns an attribute of a name in any namespace, or {@code null}. */
    private String foreignAttribute(String name) {
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            if (reader.getAttributeLocalName(i).equals(name)) {
                return reader.getAttributeValue(i);
            }
        }
        return null;
    }

    /** Returns the attributes of the current element that are in a namespace. */
    private Map<QName, String> foreignAttributes() {
        Map<QName, String> attributes = new LinkedHashMap<>();
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            String ns = reader.getAttributeNamespace(i);
            if (ns != null && !ns.isEmpty()) {
                attributes.put(new QName(ns, reader.getAttributeLocalName(i)), reader.getAttributeValue(i));
            }
        }
        return attributes;
    }

    private IllegalArgumentException error(String what) {
        Location location = reader.getLocation();
        String where = location == null || location.getLineNumber() < 0 ? ""
                : " at line " + location.getLineNumber();
        return new IllegalArgumentException("The $metadata of " + source + " is not valid" + where + ": " + what);
    }
}
