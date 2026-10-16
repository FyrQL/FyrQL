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

package net.fyrql.core.function;

import net.fyrql.core.expr.Expression;
import net.fyrql.core.expr.Expressions;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.DataType.ListType;
import net.fyrql.core.type.DataType.MapType;
import net.fyrql.core.type.TypeRules;
import net.fyrql.core.value.ConversionException;
import net.fyrql.core.value.Order;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The functions over lists and maps, after DuckDB 1.5.6. Positions in a list
 * start at 1 and count from the end when negative; a position outside the
 * list gives {@code NULL}. Elements and keys are compared in the common type
 * of both sides, in the total order of {@link Order}, so that {@code NaN}
 * equals {@code NaN} and {@code -0.0} equals {@code 0.0}. The fields of
 * structs are {@link StructExtract} and {@link StructPack}, which the binder
 * builds for a name.
 */
final class NestedFunctions {

    private NestedFunctions() {
    }

    static List<ScalarFunction> all() {
        return List.of(LIST_VALUE, LIST_EXTRACT, LIST_EXTRACT.alias("list_element"), LIST_EXTRACT.alias("array_extract"),
                STRING_EXTRACT, STRING_EXTRACT.alias("list_element"), STRING_EXTRACT.alias("array_extract"),
                LENGTH, LENGTH.alias("len"), LENGTH.alias("array_length"), ARRAY_LENGTH, CONTAINS,
                CONTAINS.alias("list_has"), CONTAINS.alias("array_contains"), CONTAINS.alias("array_has"),
                sort("list_sort", false), sort("array_sort", false), sort("list_reverse_sort", true),
                sort("array_reverse_sort", true), MAP, MAP_EXTRACT, MAP_EXTRACT.alias("element_at"),
                MAP_EXTRACT_VALUE);
    }

    /** The common type of the element of a list and a value, or {@code null} if they have none. */
    private static DataType common(DataType element, DataType value) {
        try {
            return TypeRules.common(element, value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The common type of the elements of a list, {@code INTEGER} for none, or {@code null} if they have none. */
    private static DataType elementType(List<DataType> types) {
        if (types.isEmpty()) {
            return DataType.INTEGER;
        }
        try {
            return TypeRules.common(types);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * {@code list_value(a, b, ...)}, also written {@code [a, b, ...]}: a list
     * of the arguments in their common type. The empty list is a list of
     * {@code INTEGER}s, as DuckDB's driver reports the type of its elements.
     */
    static final SimpleFunction LIST_VALUE = new SimpleFunction("list_value", types -> {
        DataType element = elementType(types);
        return element == null ? null : DataType.listOf(element);
    }, types -> SimpleFunction.allTo(elementType(types)).apply(types)) {
        @Override
        public boolean nullOnNullInput() {
            return false;
        }

        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            return new ArrayList<>(Arrays.asList(args));
        }
    };

    /** The element at a position of a list, see the class description. */
    static Object element(List<?> list, long position) {
        int n = list.size();
        if (position > 0 && position <= n) {
            return list.get((int) position - 1);
        }
        if (position < 0 && position >= -n) {
            return list.get(n + (int) position);
        }
        return null;
    }

    /** {@code list_extract(list, position)}, also written {@code list[position]}. */
    static final SimpleFunction LIST_EXTRACT = new SimpleFunction("list_extract",
            types -> types.size() == 2 && types.get(0) instanceof ListType l && SimpleFunction.isInt64(types.get(1))
                    ? l.element() : null,
            SimpleFunction.from(1, DataType.BIGINT)) {
        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            return element((List<?>) args[0], (Long) args[1]);
        }
    };

    /** {@code list_extract(string, position)}: the character at the position, or an empty string outside. */
    static final SimpleFunction STRING_EXTRACT = new SimpleFunction("list_extract",
            types -> SimpleFunction.varcharThenIntegral(types, 2) ? DataType.VARCHAR : null,
            SimpleFunction.from(1, DataType.BIGINT)) {
        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            long position = (Long) args[1];
            return position == 0 ? "" : Strings.substring((String) args[0], position, 1);
        }
    };

    /** {@code length(list)}, also {@code len} and {@code array_length}: the number of elements. */
    static final SimpleFunction LENGTH = new SimpleFunction("length",
            types -> types.size() == 1 && types.get(0) instanceof ListType ? DataType.BIGINT : null) {
        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            return (long) ((List<?>) args[0]).size();
        }
    };

    /** {@code array_length(list, dimension)}, of which DuckDB has only the first dimension. */
    static final SimpleFunction ARRAY_LENGTH = new SimpleFunction("array_length",
            types -> types.size() == 2 && types.get(0) instanceof ListType && SimpleFunction.isInt64(types.get(1))
                    ? DataType.BIGINT : null,
            SimpleFunction.from(1, DataType.BIGINT)) {
        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            if ((Long) args[1] != 1) {
                throw new IllegalArgumentException(
                        "array_length for lists with dimensions other than 1 not implemented");
            }
            return (long) ((List<?>) args[0]).size();
        }
    };

    /**
     * {@code list_contains(list, value)}, also {@code list_has},
     * {@code array_contains} and {@code array_has}: whether an element equals
     * the value; {@code NULL} elements equal nothing.
     */
    static final SimpleFunction CONTAINS = new SimpleFunction("list_contains",
            types -> types.size() == 2 && types.get(0) instanceof ListType l && common(l.element(), types.get(1)) != null
                    ? DataType.BOOLEAN : null,
            types -> {
                DataType t = common(((ListType) types.get(0)).element(), types.get(1));
                return List.of(DataType.listOf(t), t);
            }) {
        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            DataType t = types.get(1);
            for (Object e : (List<?>) args[0]) {
                if (e != null && Order.compare(e, args[1], t) == 0) {
                    return true;
                }
            }
            return false;
        }
    };

    /**
     * {@code list_sort(list [, order [, nulls]])}: the elements in order,
     * stably; {@code order} is {@code 'ASC'} (or {@code 'ASCENDING'},
     * {@code 'DEFAULT'}) or {@code 'DESC'} (or {@code 'DESCENDING'}) in any
     * case, and {@code nulls} {@code 'NULLS FIRST'} or {@code 'NULLS LAST'}
     * (also with an underscore, or {@code 'DEFAULT'}). Both must be
     * constants; {@code NULL}s come last by default. The reverse sort takes
     * only the order of the {@code NULL}s and sorts descending.
     */
    private static ScalarFunction sort(String name, boolean reverse) {
        int maxArguments = reverse ? 2 : 3;
        return new SimpleFunction(name, types -> {
            if (types.isEmpty() || types.size() > maxArguments || !(types.get(0) instanceof ListType)) {
                return null;
            }
            for (DataType t : types.subList(1, types.size())) {
                if (t != DataType.VARCHAR) {
                    return null;
                }
            }
            return types.get(0);
        }) {
            @Override
            public ScalarFunction specialize(List<Expression> arguments) {
                for (int i = 1; i < arguments.size(); i++) {
                    Object value;
                    try {
                        value = Expressions.constantValue(arguments.get(i));
                    } catch (RuntimeException e) {
                        throw new IllegalArgumentException("Sorting order must be a constant", e);
                    }
                    boolean order = i == 1 && !reverse;
                    if (value != null && (order ? descending((String) value) : nullsFirst((String) value)) == null) {
                        throw new IllegalArgumentException("Unrecognized " + (order ? "sort order" : "order of NULLs")
                                + " \"" + value + "\"");
                    }
                }
                return this;
            }

            @Override
            public Object evaluate(List<DataType> types, Object[] args) {
                boolean descending = reverse || args.length > 1 && descending((String) args[1]);
                int nullsArgument = reverse ? 1 : 2;
                boolean nullsFirst = args.length > nullsArgument && nullsFirst((String) args[nullsArgument]);
                DataType element = ((ListType) types.get(0)).element();
                List<Object> values = new ArrayList<>();
                int nulls = 0;
                for (Object v : (List<?>) args[0]) {
                    if (v == null) {
                        nulls++;
                    } else {
                        values.add(v);
                    }
                }
                Comparator<Object> order = (a, b) -> Order.compare(a, b, element);
                values.sort(descending ? order.reversed() : order);
                List<Object> result = new ArrayList<>();
                for (int i = 0; nullsFirst && i < nulls; i++) {
                    result.add(null);
                }
                result.addAll(values);
                for (int i = 0; !nullsFirst && i < nulls; i++) {
                    result.add(null);
                }
                return result;
            }
        };
    }

    /** Whether an order of {@code list_sort} is descending, or {@code null} if it is no order. */
    private static Boolean descending(String order) {
        return switch (order.toUpperCase(Locale.ROOT)) {
            case "ASC", "ASCENDING", "DEFAULT" -> false;
            case "DESC", "DESCENDING" -> true;
            default -> null;
        };
    }

    /** Whether an order of {@code NULL}s of {@code list_sort} puts them first, or {@code null} if it is none. */
    private static Boolean nullsFirst(String order) {
        return switch (order.toUpperCase(Locale.ROOT)) {
            case "NULLS FIRST", "NULLS_FIRST" -> true;
            case "NULLS LAST", "NULLS_LAST", "DEFAULT" -> false;
            default -> null;
        };
    }

    /**
     * {@code map(keys, values)}, also written {@code MAP {k: v, ...}}: a map of
     * two lists of the same length, whose keys are neither {@code NULL} nor
     * equal; {@code map()} is the empty map, of {@code INTEGER}s as for the
     * empty list.
     */
    static final SimpleFunction MAP = new SimpleFunction("map",
            types -> types.isEmpty() ? DataType.mapOf(DataType.INTEGER, DataType.INTEGER)
                    : types.size() == 2 && types.get(0) instanceof ListType k && types.get(1) instanceof ListType v
                    ? DataType.mapOf(k.element(), v.element()) : null) {
        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            if (args.length == 0) {
                return new LinkedHashMap<>();
            }
            List<?> keys = (List<?>) args[0];
            List<?> values = (List<?>) args[1];
            if (keys.size() != values.size()) {
                throw new ConversionException("The map key list does not align with the map value list.");
            }
            DataType keyType = ((ListType) types.get(0)).element();
            Map<Object, Object> map = new LinkedHashMap<>();
            for (int i = 0; i < keys.size(); i++) {
                Object key = keys.get(i);
                if (key == null) {
                    throw new ConversionException("Map keys can not be NULL.");
                }
                for (Object k : map.keySet()) {
                    if (Order.compare(k, key, keyType) == 0) {
                        throw new ConversionException("Map keys must be unique.");
                    }
                }
                map.put(key, values.get(i));
            }
            return map;
        }
    };

    /** Tells whether a map has a key of the given key type, and puts its value into {@code value[0]}. */
    private static boolean findKey(Map<?, ?> map, Object key, DataType keyType, Object[] value) {
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (Order.compare(e.getKey(), key, keyType) == 0) {
                value[0] = e.getValue();
                return true;
            }
        }
        return false;
    }

    /** The result type of a lookup in a map with a key, or {@code null} if the key type does not fit. */
    private static DataType lookup(List<DataType> types, boolean asList) {
        if (types.size() != 2 || !(types.get(0) instanceof MapType m) || common(m.key(), types.get(1)) == null) {
            return null;
        }
        return asList ? DataType.listOf(m.value()) : m.value();
    }

    /** Casts the map and the key of a lookup to the common type of the keys. */
    private static List<DataType> lookupTypes(List<DataType> types) {
        MapType m = (MapType) types.get(0);
        DataType key = common(m.key(), types.get(1));
        return List.of(DataType.mapOf(key, m.value()), key);
    }

    /**
     * {@code map_extract(map, key)}, also {@code element_at}: in DuckDB 1.5.6
     * a list of the value of the key, empty if the map does not have it or
     * the key is {@code NULL}.
     */
    static final SimpleFunction MAP_EXTRACT = new SimpleFunction("map_extract", types -> lookup(types, true),
            NestedFunctions::lookupTypes) {
        @Override
        public boolean nullOnNullInput() {
            return false;
        }

        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            if (args[0] == null) {
                return null;
            }
            List<Object> result = new ArrayList<>();
            Object[] value = new Object[1];
            if (args[1] != null && findKey((Map<?, ?>) args[0], args[1], types.get(1), value)) {
                result.add(value[0]);
            }
            return result;
        }
    };

    /** {@code map_extract_value(map, key)}, also written {@code map[key]}: the value of the key, or {@code NULL}. */
    static final SimpleFunction MAP_EXTRACT_VALUE = new SimpleFunction("map_extract_value",
            types -> lookup(types, false), NestedFunctions::lookupTypes) {
        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            Object[] value = new Object[1];
            return findKey((Map<?, ?>) args[0], args[1], types.get(1), value) ? value[0] : null;
        }
    };
}
