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

package net.fyrql.connector.memory;

import net.fyrql.core.type.DataType;
import net.fyrql.core.type.DataType.Decimal;
import net.fyrql.core.type.Schema;
import net.fyrql.core.type.Values;
import net.fyrql.core.value.Conversions;
import net.fyrql.core.value.Interval;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Maps Java classes to FyrQL types and Java objects to FyrQL values.
 * <ul>
 * <li>{@code boolean}, {@code byte}, {@code short}, {@code int},
 * {@code long}, {@code float}, {@code double} and their boxes map to the
 * matching scalar types; {@code BigInteger} to {@code HUGEINT};
 * {@code BigDecimal} to {@code DECIMAL(38,10)} unless a column type says
 * otherwise; {@code String} to {@code VARCHAR}; {@code byte[]} to
 * {@code BLOB}; {@code LocalDate} to {@code DATE}; {@code LocalTime} to
 * {@code TIME}; {@code LocalDateTime} to {@code TIMESTAMP};
 * {@code Instant}, {@code OffsetDateTime} and {@code ZonedDateTime} to
 * {@code TIMESTAMP WITH TIME ZONE}, as their instants; {@code Duration},
 * {@code Period} and {@code Interval} to {@code INTERVAL}; {@code UUID} to
 * {@code UUID}. A column of type {@code TIMESTAMP} takes an instant as its
 * timestamp in UTC.</li>
 * <li>A record or a class with public getters maps to a {@code STRUCT}, a
 * {@code List} or array to a {@code LIST}, a {@code Map} to a
 * {@code MAP}, with the element types taken from the generic signature.</li>
 * <li>A primitive column is {@code NOT NULL}; everything else is nullable.</li>
 * </ul>
 */
final class JavaTypes {

    /** The type of a {@code BigDecimal} column without an explicit type. */
    public static final Decimal DEFAULT_DECIMAL = DataType.decimal(38, 10);

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    private JavaTypes() {
    }

    /** A column of a class: its name, FyrQL type, and how to read it from an object. */
    public record Property(String name, DataType type, boolean nullable, MethodHandle getter) {

        /** Reads the value of the property from an object, as a FyrQL value. */
        public Object read(Object object) {
            try {
                return toValue(getter.invoke(object), type);
            } catch (Throwable t) {
                throw new IllegalStateException("Cannot read " + name + " of " + object.getClass().getName(), t);
            }
        }
    }

    /**
     * Returns the properties of a class: the components of a record, or the
     * public getters ({@code getX}, {@code isX}) and public fields of a
     * class, in declaration order.
     *
     * @param overrides column types that replace the derived ones, by name
     */
    public static List<Property> properties(Class<?> type, Map<String, DataType> overrides) {
        List<Property> properties = new ArrayList<>();
        try {
            if (type.isRecord()) {
                for (RecordComponent c : type.getRecordComponents()) {
                    DataType t = overrides.getOrDefault(c.getName(), toDataType(c.getGenericType(), 0));
                    properties.add(new Property(c.getName(), t, !c.getType().isPrimitive(), handle(c.getAccessor())));
                }
                return properties;
            }
            for (Method m : type.getMethods()) {
                String name = propertyName(m);
                if (name != null) {
                    DataType t = overrides.getOrDefault(name, toDataType(m.getGenericReturnType(), 0));
                    properties.add(new Property(name, t, !m.getReturnType().isPrimitive(), handle(m)));
                }
            }
            for (Field f : type.getFields()) {
                if (!Modifier.isStatic(f.getModifiers())) {
                    DataType t = overrides.getOrDefault(f.getName(), toDataType(f.getGenericType(), 0));
                    properties.add(new Property(f.getName(), t, !f.getType().isPrimitive(),
                            LOOKUP.unreflectGetter(f)));
                }
            }
        } catch (IllegalAccessException | RuntimeException e) {
            throw new IllegalArgumentException("Cannot access the properties of " + type.getName(), e);
        }
        if (properties.isEmpty()) {
            throw new IllegalArgumentException("No record components, getters or public fields in " + type.getName());
        }
        return properties;
    }

    /** A handle on an accessor, also of a class that is not public, e.g. a record nested in a test. */
    private static MethodHandle handle(Method m) throws IllegalAccessException {
        try {
            return LOOKUP.unreflect(m);
        } catch (IllegalAccessException e) {
            m.setAccessible(true);
            return LOOKUP.unreflect(m);
        }
    }

    private static String propertyName(Method m) {
        if (Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 0
                || m.getDeclaringClass() == Object.class || m.getReturnType() == void.class) {
            return null;
        }
        String n = m.getName();
        if (n.startsWith("get") && n.length() > 3) {
            return Character.toLowerCase(n.charAt(3)) + n.substring(4);
        }
        if (n.startsWith("is") && n.length() > 2 && (m.getReturnType() == boolean.class
                || m.getReturnType() == Boolean.class)) {
            return Character.toLowerCase(n.charAt(2)) + n.substring(3);
        }
        return null;
    }

    /** Returns the schema of a class. */
    public static Schema schema(Class<?> type, Map<String, DataType> overrides) {
        List<Schema.Field> fields = new ArrayList<>();
        for (Property p : properties(type, overrides)) {
            fields.add(new Schema.Field(p.name(), p.type(), p.nullable()));
        }
        return new Schema(fields);
    }

    /** Maps a Java type to a FyrQL type. */
    public static DataType toDataType(Type type, int depth) {
        if (depth > 8) {
            throw new IllegalArgumentException("Nested too deeply: " + type);
        }
        if (type instanceof ParameterizedType p) {
            Class<?> raw = (Class<?>) p.getRawType();
            Type[] args = p.getActualTypeArguments();
            if (Collection.class.isAssignableFrom(raw)) {
                return DataType.listOf(toDataType(args[0], depth + 1));
            }
            if (Map.class.isAssignableFrom(raw)) {
                return DataType.mapOf(toDataType(args[0], depth + 1), toDataType(args[1], depth + 1));
            }
            return toDataType(raw, depth);
        }
        if (!(type instanceof Class<?> c)) {
            throw new IllegalArgumentException("Cannot map the type " + type);
        }
        if (c == boolean.class || c == Boolean.class) {
            return DataType.BOOLEAN;
        }
        if (c == byte.class || c == Byte.class) {
            return DataType.TINYINT;
        }
        if (c == short.class || c == Short.class) {
            return DataType.SMALLINT;
        }
        if (c == int.class || c == Integer.class) {
            return DataType.INTEGER;
        }
        if (c == long.class || c == Long.class) {
            return DataType.BIGINT;
        }
        if (c == BigInteger.class) {
            return DataType.HUGEINT;
        }
        if (c == float.class || c == Float.class) {
            return DataType.FLOAT;
        }
        if (c == double.class || c == Double.class) {
            return DataType.DOUBLE;
        }
        if (c == BigDecimal.class) {
            return DEFAULT_DECIMAL;
        }
        if (c == String.class || c == char.class || c == Character.class) {
            return DataType.VARCHAR;
        }
        if (c == byte[].class) {
            return DataType.BLOB;
        }
        if (c == LocalDate.class) {
            return DataType.DATE;
        }
        if (c == LocalTime.class) {
            return DataType.TIME;
        }
        if (c == LocalDateTime.class) {
            return DataType.TIMESTAMP;
        }
        if (c == Instant.class || c == OffsetDateTime.class || c == ZonedDateTime.class) {
            return DataType.TIMESTAMPTZ;
        }
        if (c == Duration.class || c == Period.class || c == Interval.class) {
            return DataType.INTERVAL;
        }
        if (c == UUID.class) {
            return DataType.UUID;
        }
        if (c.isArray()) {
            return DataType.listOf(toDataType(c.getComponentType(), depth + 1));
        }
        if (Collection.class.isAssignableFrom(c) || Map.class.isAssignableFrom(c)) {
            throw new IllegalArgumentException("A raw " + c.getSimpleName() + " has no element type");
        }
        if (c.isEnum()) {
            return DataType.VARCHAR;
        }
        List<DataType.Struct.Field> fields = new ArrayList<>();
        for (Property p : properties(c, Map.of())) {
            fields.add(new DataType.Struct.Field(p.name(), p.type()));
        }
        return new DataType.Struct(fields);
    }

    /** The instant of an {@code Instant}, {@code OffsetDateTime} or {@code ZonedDateTime}, or {@code null}. */
    private static Instant instant(Object v) {
        return switch (v) {
            case Instant i -> i;
            case OffsetDateTime o -> o.toInstant();
            case ZonedDateTime z -> z.toInstant();
            default -> null;
        };
    }

    /** Converts a Java object to the FyrQL value of a type. */
    public static Object toValue(Object v, DataType type) {
        if (v == null) {
            return null;
        }
        if (type instanceof Decimal d) {
            return Values.toDecimal((BigDecimal) v, d);
        }
        if (type instanceof DataType.Struct s) {
            List<Property> properties = properties(v.getClass(), Map.of());
            List<Object> values = new ArrayList<>(s.fields().size());
            for (int i = 0; i < s.fields().size(); i++) {
                values.add(properties.get(i).read(v));
            }
            return values;
        }
        if (type instanceof DataType.ListType l) {
            List<Object> values = new ArrayList<>();
            if (v.getClass().isArray()) {
                int n = java.lang.reflect.Array.getLength(v);
                for (int i = 0; i < n; i++) {
                    values.add(toValue(java.lang.reflect.Array.get(v, i), l.element()));
                }
            } else {
                for (Object e : (Collection<?>) v) {
                    values.add(toValue(e, l.element()));
                }
            }
            return values;
        }
        if (type instanceof DataType.MapType m) {
            Map<Object, Object> values = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                values.put(toValue(e.getKey(), m.key()), toValue(e.getValue(), m.value()));
            }
            return values;
        }
        return switch ((DataType.Scalar) type) {
            case DATE -> v instanceof LocalDate d ? Math.toIntExact(d.toEpochDay()) : v;
            case TIME -> v instanceof LocalTime t ? t.toNanoOfDay() / 1000 : v;
            case TIMESTAMP -> v instanceof LocalDateTime dt ? Conversions.toMicros(dt)
                    : instant(v) instanceof Instant i ? Conversions.toMicros(LocalDateTime.ofInstant(i, ZoneOffset.UTC))
                    : v;
            case TIMESTAMPTZ -> instant(v) instanceof Instant i
                    ? Conversions.toMicros(LocalDateTime.ofInstant(i, ZoneOffset.UTC)) : v;
            case INTERVAL -> switch (v) {
                case Duration d -> Interval.from(d);
                case Period p -> Interval.from(p);
                default -> v;
            };
            case VARCHAR -> v instanceof String s ? s : v.toString();
            default -> v;
        };
    }
}
