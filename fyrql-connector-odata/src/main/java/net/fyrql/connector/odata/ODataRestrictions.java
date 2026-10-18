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

import java.util.HashSet;
import java.util.Set;

/**
 * What a query of an entity set may ask of the service, as its
 * {@code $metadata} says: in version 4 by the terms of the vocabulary
 * {@code Org.OData.Capabilities.V1} on the entity set without a qualifier,
 * {@code CountRestrictions}, {@code FilterRestrictions},
 * {@code SortRestrictions} and {@code TopSupported}; in version 2 by the
 * attributes of SAP, {@code sap:countable} and {@code sap:pageable} on the
 * entity set and {@code sap:filterable} and {@code sap:sortable} on a
 * property. Everything that is not restricted is allowed.
 *
 * @param countable whether the service counts the records of the entity set
 * @param filterable whether the service filters the entity set at all
 * @param nonFilterable the paths of the properties the service does not filter by
 * @param sortable whether the service sorts the entity set at all
 * @param nonSortable the paths of the properties the service does not sort by
 * @param ascendingOnly the paths of the properties the service sorts by only ascending
 * @param descendingOnly the paths of the properties the service sorts by only descending
 * @param top whether the service takes {@code $top}
 */
record ODataRestrictions(boolean countable, boolean filterable, Set<String> nonFilterable, boolean sortable,
        Set<String> nonSortable, Set<String> ascendingOnly, Set<String> descendingOnly, boolean top) {

    /** The namespace of the attributes of SAP, such as {@code sap:countable}. */
    static final String SAP = "http://www.sap.com/Protocols/SAPData";

    private static final String CAPABILITIES = "Org.OData.Capabilities.V1.";

    ODataRestrictions {
        nonFilterable = Set.copyOf(nonFilterable);
        nonSortable = Set.copyOf(nonSortable);
        ascendingOnly = Set.copyOf(ascendingOnly);
        descendingOnly = Set.copyOf(descendingOnly);
    }

    /** Returns the restrictions of an entity set. */
    static ODataRestrictions of(EntitySet set) {
        boolean countable = !isFalse(set.attribute(SAP, "countable"));
        boolean filterable = true;
        boolean sortable = true;
        boolean top = !isFalse(set.attribute(SAP, "pageable"));
        Set<String> nonFilterable = new HashSet<>();
        Set<String> nonSortable = new HashSet<>();
        Set<String> ascendingOnly = new HashSet<>();
        Set<String> descendingOnly = new HashSet<>();
        for (Annotation a : set.annotations()) {
            if (a.qualifier() != null || !a.term().startsWith(CAPABILITIES)) {
                continue;
            }
            switch (a.term().substring(CAPABILITIES.length())) {
                case "CountRestrictions" -> countable &= !isFalse(property(a, "Countable"));
                case "FilterRestrictions" -> {
                    filterable &= !isFalse(property(a, "Filterable"));
                    paths(a, "NonFilterableProperties", nonFilterable);
                }
                case "SortRestrictions" -> {
                    sortable &= !isFalse(property(a, "Sortable"));
                    paths(a, "NonSortableProperties", nonSortable);
                    paths(a, "AscendingOnlyProperties", ascendingOnly);
                    paths(a, "DescendingOnlyProperties", descendingOnly);
                }
                case "TopSupported" -> top &= !(a.value() instanceof Annotation.Constant c && isFalse(c.text()));
                default -> {
                    // Not a restriction of reading.
                }
            }
        }
        return new ODataRestrictions(countable, filterable, nonFilterable, sortable, nonSortable, ascendingOnly,
                descendingOnly, top);
    }

    /** Tells whether the service filters by a path into a property, which needs its property to be filterable. */
    boolean filterable(String path, Property property) {
        return filterable && !isFalse(property.attribute(SAP, "filterable")) && !restricted(path, nonFilterable);
    }

    /** Tells whether the service sorts ascending by a path into a property. */
    boolean ascending(String path, Property property) {
        return sortable(path, property) && !restricted(path, descendingOnly);
    }

    /** Tells whether the service sorts descending by a path into a property. */
    boolean descending(String path, Property property) {
        return sortable(path, property) && !restricted(path, ascendingOnly);
    }

    private boolean sortable(String path, Property property) {
        return sortable && !isFalse(property.attribute(SAP, "sortable")) && !restricted(path, nonSortable);
    }

    /** Tells whether a path or one that leads to it is in a set. */
    private static boolean restricted(String path, Set<String> paths) {
        for (String p : paths) {
            if (path.equals(p) || path.startsWith(p + "/")) {
                return true;
            }
        }
        return false;
    }

    /** Returns the text of a constant property of the record of an annotation, or {@code null}. */
    private static String property(Annotation a, String name) {
        return a.value() instanceof Annotation.Record r && r.properties().get(name) instanceof Annotation.Constant c
                ? c.text() : null;
    }

    /** Adds the paths of a collection property of the record of an annotation. */
    private static void paths(Annotation a, String name, Set<String> paths) {
        if (a.value() instanceof Annotation.Record r && r.properties().get(name) instanceof Annotation.Collection c) {
            for (Annotation.Value v : c.items()) {
                if (v instanceof Annotation.Constant constant) {
                    paths.add(constant.text().strip());
                }
            }
        }
    }

    private static boolean isFalse(String text) {
        return text != null && text.strip().equalsIgnoreCase("false");
    }
}
