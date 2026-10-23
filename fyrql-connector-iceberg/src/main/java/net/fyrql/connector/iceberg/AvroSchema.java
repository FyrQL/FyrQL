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

package net.fyrql.connector.iceberg;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The schema of an Avro file, as far as reading needs it, with the field
 * ids Iceberg puts on fields ({@code field-id}), elements
 * ({@code element-id}) and map keys and values ({@code key-id},
 * {@code value-id}). Named types may be used again by their name.
 */
sealed interface AvroSchema {

    /** The primitive types. */
    enum Kind {
        NULL, BOOLEAN, INT, LONG, FLOAT, DOUBLE, BYTES, STRING
    }

    /** A primitive type with its logical type, or {@code null}. */
    record Primitive(Kind kind, String logicalType) implements AvroSchema {
    }

    /** A record, read as a map from the names of its fields to their values. */
    record Record(String name, List<Field> fields) implements AvroSchema {
    }

    /** A field of a record, with its Iceberg field id or {@code -1}. */
    record Field(String name, AvroSchema schema, int fieldId) {
    }

    /** An enum, read as the name of its symbol. */
    record Enum(String name, List<String> symbols) implements AvroSchema {
    }

    /**
     * An array, read as a list; with the logical type {@code map}, an
     * Iceberg map with keys other than strings as an array of records
     * {@code key} and {@code value}.
     */
    record Array(AvroSchema items, int elementId, String logicalType) implements AvroSchema {
    }

    /** A map with string keys, read as a map. */
    record MapType(AvroSchema values, int keyId, int valueId) implements AvroSchema {
    }

    /** A union, read as the value of its branch. */
    record Union(List<AvroSchema> branches) implements AvroSchema {
    }

    /** A fixed number of bytes. */
    record Fixed(String name, int size, String logicalType) implements AvroSchema {
    }

    /**
     * Parses the JSON of a schema.
     *
     * @throws IOException if the text is not a schema
     */
    static AvroSchema parse(String json) throws IOException {
        try {
            return new Parser().schema(Json.parse(json), "");
        } catch (ClassCastException | NullPointerException e) {
            throw new IOException("Invalid Avro schema: " + e, e);
        }
    }

    /**
     * Parses the tree of a schema, remembering named types by their full
     * and their short name. A record that names itself in its fields is an
     * error: Iceberg writes none, and reading one could nest without end.
     */
    final class Parser {
        private final Map<String, AvroSchema> named = new HashMap<>();
        /** The records whose fields are being parsed. */
        private final Set<AvroSchema> open = Collections.newSetFromMap(new IdentityHashMap<>());

        private AvroSchema schema(Object node, String namespace) throws IOException {
            if (node instanceof String name) {
                return reference(name, namespace);
            }
            if (node instanceof List<?> branches) {
                List<AvroSchema> list = new ArrayList<>();
                for (Object b : branches) {
                    list.add(schema(b, namespace));
                }
                return new Union(list);
            }
            Map<?, ?> map = (Map<?, ?>) node;
            String type = (String) map.get("type");
            String logical = (String) map.get("logicalType");
            String ns = map.get("namespace") instanceof String s ? s : namespace;
            return switch (type) {
                case "record", "error" -> {
                    String name = (String) map.get("name");
                    List<Field> fields = new ArrayList<>();
                    Record record = new Record(name, fields);
                    name(name, ns, record);
                    open.add(record);
                    for (Object f : (List<?>) map.get("fields")) {
                        Map<?, ?> field = (Map<?, ?>) f;
                        fields.add(new Field((String) field.get("name"), schema(field.get("type"), ns),
                                id(field.get("field-id"))));
                    }
                    open.remove(record);
                    yield record;
                }
                case "enum" -> {
                    List<String> symbols = new ArrayList<>();
                    for (Object s : (List<?>) map.get("symbols")) {
                        symbols.add((String) s);
                    }
                    Enum e = new Enum((String) map.get("name"), symbols);
                    name(e.name(), ns, e);
                    yield e;
                }
                case "array" -> new Array(schema(map.get("items"), ns), id(map.get("element-id")), logical);
                case "map" -> new MapType(schema(map.get("values"), ns), id(map.get("key-id")),
                        id(map.get("value-id")));
                case "fixed" -> {
                    Fixed f = new Fixed((String) map.get("name"), ((Number) map.get("size")).intValue(), logical);
                    name(f.name(), ns, f);
                    yield f;
                }
                default -> {
                    AvroSchema s = reference(type, ns);
                    yield s instanceof Primitive p ? new Primitive(p.kind(), logical) : s;
                }
            };
        }

        private void name(String name, String namespace, AvroSchema schema) {
            named.put(name, schema);
            if (!namespace.isEmpty() && name.indexOf('.') < 0) {
                named.put(namespace + "." + name, schema);
            }
        }

        private AvroSchema reference(String name, String namespace) throws IOException {
            switch (name) {
                case "null":
                    return new Primitive(Kind.NULL, null);
                case "boolean":
                    return new Primitive(Kind.BOOLEAN, null);
                case "int":
                    return new Primitive(Kind.INT, null);
                case "long":
                    return new Primitive(Kind.LONG, null);
                case "float":
                    return new Primitive(Kind.FLOAT, null);
                case "double":
                    return new Primitive(Kind.DOUBLE, null);
                case "bytes":
                    return new Primitive(Kind.BYTES, null);
                case "string":
                    return new Primitive(Kind.STRING, null);
                default:
                    break;
            }
            AvroSchema s = named.get(namespace.isEmpty() ? name : namespace + "." + name);
            if (s == null) {
                s = named.get(name);
            }
            if (s == null) {
                throw new IOException("Unknown Avro type " + name);
            }
            if (open.contains(s)) {
                throw new IOException("The Avro record " + name + " contains itself, which is not supported");
            }
            return s;
        }

        private static int id(Object id) {
            return id instanceof Number n ? n.intValue() : id instanceof String s ? Integer.parseInt(s) : -1;
        }
    }
}
