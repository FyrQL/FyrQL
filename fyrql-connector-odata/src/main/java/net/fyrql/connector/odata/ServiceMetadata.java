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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * What the {@code $metadata} document of a service says about reading it:
 * the entity sets of its entity container and the types they use. Names of
 * types and terms are qualified by their namespace, also where the
 * document names them by an alias. Annotations given in an
 * {@code Annotations} element are added to the entity set, type or
 * property they target; those of other targets, such as the container,
 * are kept by target.
 *
 * @param version the version of the protocol
 * @param serviceVersion the version as the document gives it: the
 *        {@code DataServiceVersion} of version 2, {@code 1.0} if it is
 *        missing, or the {@code Version} of version 4, such as {@code 4.01}
 * @param container the qualified name of the entity container, or
 *        {@code null} if there is none
 * @param entitySets the entity sets of the container in order
 * @param entityTypes the entity types by qualified name
 * @param complexTypes the complex types by qualified name
 * @param enumTypes the enumeration types by qualified name
 * @param typeDefinitions the type definitions by qualified name
 * @param annotations the annotations of other targets, by target
 */
public record ServiceMetadata(ODataVersion version, String serviceVersion, String container,
        List<EntitySet> entitySets, Map<String, EntityType> entityTypes, Map<String, ComplexType> complexTypes,
        Map<String, EnumType> enumTypes, Map<String, TypeDefinition> typeDefinitions,
        Map<String, List<Annotation>> annotations) {

    public ServiceMetadata {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(serviceVersion, "serviceVersion");
        entitySets = List.copyOf(entitySets);
        entityTypes = unmodifiable(entityTypes);
        complexTypes = unmodifiable(complexTypes);
        enumTypes = unmodifiable(enumTypes);
        typeDefinitions = unmodifiable(typeDefinitions);
        Map<String, List<Annotation>> copy = new LinkedHashMap<>();
        annotations.forEach((target, list) -> copy.put(target, List.copyOf(list)));
        annotations = Collections.unmodifiableMap(copy);
    }

    private static <T> Map<String, T> unmodifiable(Map<String, T> map) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }

    /** Returns the entity set of a name, or {@code null}. */
    public EntitySet entitySet(String name) {
        for (EntitySet s : entitySets) {
            if (s.name().equals(name)) {
                return s;
            }
        }
        return null;
    }

    /**
     * Returns the structural properties of an entity type, those of its
     * base types first.
     *
     * @throws IllegalArgumentException if a base type is missing or the types derive from each other
     */
    public List<Property> properties(EntityType type) {
        List<Property> all = new ArrayList<>();
        for (EntityType t : chain(type, EntityType::name, EntityType::baseType, entityTypes, "entity")) {
            all.addAll(t.properties());
        }
        return all;
    }

    /**
     * Returns the names of the properties of the key of an entity type,
     * which it or one of its base types declares.
     *
     * @throws IllegalArgumentException if a base type is missing or the types derive from each other
     */
    public List<String> key(EntityType type) {
        List<EntityType> chain = chain(type, EntityType::name, EntityType::baseType, entityTypes, "entity");
        for (int i = chain.size() - 1; i >= 0; i--) {
            if (!chain.get(i).key().isEmpty()) {
                return chain.get(i).key();
            }
        }
        return List.of();
    }

    /**
     * Returns the structural properties of a complex type, those of its
     * base types first.
     *
     * @throws IllegalArgumentException if a base type is missing or the types derive from each other
     */
    public List<Property> properties(ComplexType type) {
        List<Property> all = new ArrayList<>();
        for (ComplexType t : chain(type, ComplexType::name, ComplexType::baseType, complexTypes, "complex")) {
            all.addAll(t.properties());
        }
        return all;
    }

    /** Returns a type and its base types, the root first. */
    private static <T> List<T> chain(T type, Function<T, String> name, Function<T, String> base, Map<String, T> types,
            String kind) {
        List<T> chain = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        T t = type;
        while (true) {
            if (!seen.add(name.apply(t))) {
                throw new IllegalArgumentException("The " + kind + " type " + name.apply(type)
                        + " derives from itself through " + name.apply(t));
            }
            chain.add(0, t);
            String b = base.apply(t);
            if (b == null) {
                return chain;
            }
            T next = types.get(b);
            if (next == null) {
                throw new IllegalArgumentException("The base type " + b + " of the " + kind + " type "
                        + name.apply(t) + " is not declared");
            }
            t = next;
        }
    }
}
