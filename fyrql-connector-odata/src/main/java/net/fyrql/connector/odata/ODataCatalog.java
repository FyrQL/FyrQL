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

import net.fyrql.connector.Catalog;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The services of an OData source as schemas, and the entity sets of the
 * entity container of each as tables, in the order of the
 * {@code $metadata} document. The columns of a table are the structural
 * properties of the type of its entity set, those of its base types
 * first, with the types of {@link EdmTypes} as the profile of the source
 * changes them; a property that is no column there, such as an
 * {@code Edm.Stream}, is left out. The document of a
 * service is read when the service is first used and kept until
 * {@link #refresh()}, and so are the numbers of rows of its tables, if
 * the catalog has a way to count them.
 */
final class ODataCatalog implements Catalog {

    /** Reads the {@code $metadata} document of the service of a schema. */
    @FunctionalInterface
    interface MetadataLoader {
        ServiceMetadata load(String schema);
    }

    /**
     * A table: its entity set and entity type, the properties that are its
     * columns, in the order of the columns, its columns, the names of the
     * properties of its key, and the names of the properties whose columns
     * the profile gave another type.
     */
    record Table(TableRef ref, EntitySet set, EntityType type, List<Property> properties, Schema schema,
            List<String> key, Set<String> retyped) {
    }

    /** The document of a service, the tables made of it so far and their numbers of rows. */
    private record Service(ServiceMetadata metadata, Map<String, Table> tables,
            Map<String, OptionalLong> rowCounts) {
    }

    private final String source;
    private final List<String> schemas;
    private final MetadataLoader loader;
    private final ODataProfile profile;
    /** Counts the rows of a table, or {@code null} if the catalog tells no numbers. */
    private final Function<TableRef, OptionalLong> counter;
    private final Map<String, Service> services = new ConcurrentHashMap<>();

    /**
     * @param source the name of the source, for messages
     * @param schemas the schemas, one for each service
     */
    ODataCatalog(String source, List<String> schemas, MetadataLoader loader) {
        this(source, schemas, loader, null, ODataProfile.standard());
    }

    /**
     * @param source the name of the source, for messages
     * @param schemas the schemas, one for each service
     * @param counter counts the rows of a table, or {@code null} to tell no numbers
     * @param profile changes the types of the columns
     */
    ODataCatalog(String source, List<String> schemas, MetadataLoader loader,
            Function<TableRef, OptionalLong> counter, ODataProfile profile) {
        this.source = Objects.requireNonNull(source, "source");
        this.schemas = List.copyOf(schemas);
        this.loader = Objects.requireNonNull(loader, "loader");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.counter = counter;
    }

    @Override
    public List<String> schemas() {
        return schemas;
    }

    @Override
    public List<String> tables(String schema) {
        if (!schemas.contains(schema)) {
            return List.of();
        }
        List<String> tables = new ArrayList<>();
        for (EntitySet set : service(schema).metadata().entitySets()) {
            tables.add(set.name());
        }
        return tables;
    }

    @Override
    public Schema schema(TableRef table) {
        return table(table).schema();
    }

    /** Returns the document of the service of a schema, reading it if it is not known yet. */
    ServiceMetadata metadata(String schema) {
        if (!schemas.contains(schema)) {
            throw new IllegalArgumentException("No schema " + schema + " in the OData source " + source);
        }
        return service(schema).metadata();
    }

    /**
     * Returns a table.
     *
     * @throws IllegalArgumentException if there is no such table, or its
     *         type is not declared
     */
    Table table(TableRef ref) {
        if (!schemas.contains(ref.schema())) {
            throw new IllegalArgumentException("No table " + ref + " in the OData source " + source);
        }
        Service service = service(ref.schema());
        EntitySet set = service.metadata().entitySet(ref.table());
        if (set == null) {
            throw new IllegalArgumentException("No table " + ref + " in the OData source " + source);
        }
        return service.tables().computeIfAbsent(set.name(), n -> table(ref, set, service.metadata()));
    }

    private Table table(TableRef ref, EntitySet set, ServiceMetadata metadata) {
        EntityType type = metadata.entityTypes().get(set.entityType());
        if (type == null) {
            throw new IllegalArgumentException("The entity set " + set.name() + " of the table " + ref
                    + " has the type " + set.entityType() + ", which its $metadata does not declare");
        }
        List<Property> properties = new ArrayList<>();
        List<Schema.Field> fields = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Set<String> retyped = new HashSet<>();
        for (Property p : metadata.properties(type)) {
            DataType mapped = EdmTypes.of(p, metadata);
            DataType t = mapped == null ? null : profile.type(set, p, mapped);
            if (t != null && names.add(p.name())) {
                properties.add(p);
                fields.add(new Schema.Field(p.name(), t, p.nullable() || p.isCollection()));
                if (!t.equals(mapped)) {
                    retyped.add(p.name());
                }
            }
        }
        return new Table(ref, set, type, properties, new Schema(fields), metadata.key(type), Set.copyOf(retyped));
    }

    private Service service(String schema) {
        return services.computeIfAbsent(schema, s -> new Service(loader.load(s), new ConcurrentHashMap<>(),
                new ConcurrentHashMap<>()));
    }

    /**
     * Returns the number of rows of a table, counted once until
     * {@link #refresh()}, if the catalog has a way to count them and the
     * entity set can count its records.
     */
    @Override
    public OptionalLong rowCount(TableRef table) {
        if (counter == null) {
            return OptionalLong.empty();
        }
        Table t = table(table);
        return service(table.schema()).rowCounts().computeIfAbsent(t.set().name(), n -> counter.apply(table));
    }

    /** Forgets every document, so that the next use of a service reads it again. */
    void refresh() {
        services.clear();
    }

    /** Forgets the document of the service of a schema, so that its next use reads it again. */
    void refresh(String schema) {
        services.remove(schema);
    }
}
