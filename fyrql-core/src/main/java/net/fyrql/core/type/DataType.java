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

package net.fyrql.core.type;

import net.fyrql.core.value.Interval;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The data types of FyrQL, named and behaving as in DuckDB: the scalar types,
 * {@code DECIMAL(p,s)}, and the nested types {@code STRUCT}, {@code LIST} and
 * {@code MAP}, arbitrarily deep. Every type is nullable.
 * <p>
 * A value of a type, outside a vector, is a Java object: {@code Boolean},
 * {@code Byte}, {@code Short}, {@code Integer}, {@code Long},
 * {@code BigInteger} for {@code HUGEINT}, {@code Float}, {@code Double},
 * {@code BigDecimal} with exactly the scale of its type, {@code String},
 * {@code byte[]} for {@code BLOB}, {@code Integer} days since 1970-01-01 for
 * {@code DATE}, {@code Long} microseconds since 1970-01-01 00:00:00 for
 * {@code TIMESTAMP}, {@code java.util.UUID}, {@code Long} microseconds since
 * midnight for {@code TIME} (0 to 86,400,000,000, the last one being
 * {@code 24:00:00}), {@code Long} microseconds since 1970-01-01 00:00:00 UTC
 * for {@code TIMESTAMP WITH TIME ZONE}, shown in the time zone of the
 * session, and {@link Interval} for {@code INTERVAL}; a {@code STRUCT} is a
 * {@code List} of its field values in order, a {@code LIST} a {@code List},
 * a {@code MAP} a {@code Map} that keeps its insertion order. {@code NULL} is
 * {@code null}.
 * <p>
 * This is the form of the values in vectors, batches, connectors and
 * functions. The rows that {@code rows()} of the engine returns, and the
 * values of parameters, have the dates and times of {@code java.time}
 * instead; {@link Values#toJava} and {@link Values#fromJava} convert between
 * the two.
 */
public sealed interface DataType permits DataType.Scalar, DataType.Decimal, DataType.Struct, DataType.ListType,
        DataType.MapType {

    DataType BOOLEAN = Scalar.BOOLEAN;
    DataType TINYINT = Scalar.TINYINT;
    DataType SMALLINT = Scalar.SMALLINT;
    DataType INTEGER = Scalar.INTEGER;
    DataType BIGINT = Scalar.BIGINT;
    DataType HUGEINT = Scalar.HUGEINT;
    DataType FLOAT = Scalar.FLOAT;
    DataType DOUBLE = Scalar.DOUBLE;
    DataType VARCHAR = Scalar.VARCHAR;
    DataType BLOB = Scalar.BLOB;
    DataType DATE = Scalar.DATE;
    DataType TIMESTAMP = Scalar.TIMESTAMP;
    DataType UUID = Scalar.UUID;
    DataType TIME = Scalar.TIME;
    DataType TIMESTAMPTZ = Scalar.TIMESTAMPTZ;
    DataType INTERVAL = Scalar.INTERVAL;

    /** The largest precision of a {@code DECIMAL}. */
    int MAX_DECIMAL_PRECISION = 38;

    /** Returns the name of the type as DuckDB spells it, e.g. {@code DECIMAL(10,2)} or {@code INTEGER[]}. */
    String sqlName();

    /** Returns the Java class of the values of this type. */
    Class<?> javaClass();

    /** Tells whether a value belongs to this type; {@code null} always does. */
    boolean accepts(Object value);

    // No default methods here: a class initializes the superinterfaces that declare default methods first, so
    // touching Scalar before DataType would run the initializer of the constants above while Scalar is still
    // being initialized and leave them null.

    /** Tells whether this is an integral type, {@code TINYINT} to {@code HUGEINT}. */
    boolean isIntegral();

    /** Tells whether this is {@code FLOAT} or {@code DOUBLE}. */
    boolean isFloatingPoint();

    /** Tells whether this is an integral, floating-point or decimal type. */
    boolean isNumeric();

    /** Tells whether this is a {@code STRUCT}, {@code LIST} or {@code MAP}. */
    boolean isNested();

    /** Returns a {@code DECIMAL(precision, scale)}. */
    static Decimal decimal(int precision, int scale) {
        return new Decimal(precision, scale);
    }

    /** Returns a {@code LIST} of the element type. */
    static ListType listOf(DataType element) {
        return new ListType(element);
    }

    /** Returns a {@code MAP} from the key type to the value type. */
    static MapType mapOf(DataType key, DataType value) {
        return new MapType(key, value);
    }

    /** Returns a {@code STRUCT} with the given fields. */
    static Struct struct(Struct.Field... fields) {
        return new Struct(List.of(fields));
    }

    /** The scalar types without parameters. */
    enum Scalar implements DataType {
        BOOLEAN(Boolean.class),
        TINYINT(Byte.class),
        SMALLINT(Short.class),
        INTEGER(Integer.class),
        BIGINT(Long.class),
        HUGEINT(BigInteger.class),
        FLOAT(Float.class),
        DOUBLE(Double.class),
        VARCHAR(String.class),
        BLOB(byte[].class),
        DATE(Integer.class),
        TIMESTAMP(Long.class),
        UUID(java.util.UUID.class),
        TIME(Long.class),
        TIMESTAMPTZ(Long.class),
        INTERVAL(Interval.class);

        private final Class<?> javaClass;

        Scalar(Class<?> javaClass) {
            this.javaClass = javaClass;
        }

        /** Returns the name, {@code TIMESTAMP WITH TIME ZONE} for {@code TIMESTAMPTZ} as DuckDB spells it. */
        @Override
        public String sqlName() {
            return this == TIMESTAMPTZ ? "TIMESTAMP WITH TIME ZONE" : name();
        }

        @Override
        public Class<?> javaClass() {
            return javaClass;
        }

        @Override
        public boolean accepts(Object value) {
            if (value == null) {
                return true;
            }
            if (!javaClass.isInstance(value)) {
                return false;
            }
            return this != HUGEINT || Values.inHugeIntRange((BigInteger) value);
        }

        @Override
        public boolean isIntegral() {
            return this == TINYINT || this == SMALLINT || this == INTEGER || this == BIGINT || this == HUGEINT;
        }

        @Override
        public boolean isFloatingPoint() {
            return this == FLOAT || this == DOUBLE;
        }

        @Override
        public boolean isNumeric() {
            return isIntegral() || isFloatingPoint();
        }

        @Override
        public boolean isNested() {
            return false;
        }

        @Override
        public String toString() {
            return sqlName();
        }
    }

    /**
     * {@code DECIMAL(precision, scale)}: a value is a {@code BigDecimal} with
     * exactly this scale and at most this many digits.
     */
    record Decimal(int precision, int scale) implements DataType {
        public Decimal {
            if (precision < 1 || precision > MAX_DECIMAL_PRECISION) {
                throw new IllegalArgumentException("DECIMAL precision must be 1 to " + MAX_DECIMAL_PRECISION
                        + ": " + precision);
            }
            if (scale < 0 || scale > precision) {
                throw new IllegalArgumentException("DECIMAL scale must be 0 to the precision " + precision + ": "
                        + scale);
            }
        }

        @Override
        public String sqlName() {
            return "DECIMAL(" + precision + "," + scale + ")";
        }

        @Override
        public Class<?> javaClass() {
            return BigDecimal.class;
        }

        @Override
        public boolean accepts(Object value) {
            return value == null || value instanceof BigDecimal d && d.scale() == scale && d.precision() <= precision;
        }

        /** The largest unscaled value of each precision, at its index. */
        private static final BigInteger[] MAX_UNSCALED = new BigInteger[MAX_DECIMAL_PRECISION + 1];

        static {
            for (int p = 1; p <= MAX_DECIMAL_PRECISION; p++) {
                MAX_UNSCALED[p] = BigInteger.TEN.pow(p).subtract(BigInteger.ONE);
            }
        }

        /** Returns the largest unscaled value: {@code 10^precision - 1}. */
        public BigInteger maxUnscaled() {
            return MAX_UNSCALED[precision];
        }

        /** Tells whether the unscaled values fit into a {@code long}, i.e. the precision is at most 18. */
        public boolean fitsLong() {
            return precision <= 18;
        }

        @Override
        public boolean isIntegral() {
            return false;
        }

        @Override
        public boolean isFloatingPoint() {
            return false;
        }

        @Override
        public boolean isNumeric() {
            return true;
        }

        @Override
        public boolean isNested() {
            return false;
        }

        @Override
        public String toString() {
            return sqlName();
        }
    }

    /** {@code STRUCT(name type, ...)}: a value is a {@code List} of the field values in order. */
    record Struct(List<Field> fields) implements DataType {

        /** A field of a {@code STRUCT}. */
        public record Field(String name, DataType type) {
            public Field {
                Objects.requireNonNull(name, "name");
                Objects.requireNonNull(type, "type");
            }

            /** Returns the field as a type name writes it, the name in double quotes where DuckDB quotes it. */
            @Override
            public String toString() {
                return Identifiers.quoteIfNeeded(name) + " " + type.sqlName();
            }
        }

        public Struct {
            fields = List.copyOf(fields);
            if (fields.isEmpty()) {
                throw new IllegalArgumentException("A STRUCT needs at least one field");
            }
        }

        /** Returns the index of the field with the name, or -1. */
        public int indexOf(String name) {
            for (int i = 0; i < fields.size(); i++) {
                if (fields.get(i).name().equals(name)) {
                    return i;
                }
            }
            return -1;
        }

        /**
         * Returns the index of the field a name selects as DuckDB matches
         * names, without regard to case; a field of exactly that name wins
         * over others that differ in case only.
         *
         * @return the index, or -1 if no field or several have the name
         */
        public int find(String name) {
            int exact = indexOf(name);
            if (exact >= 0) {
                return exact;
            }
            int found = -1;
            for (int i = 0; i < fields.size(); i++) {
                if (fields.get(i).name().equalsIgnoreCase(name)) {
                    if (found >= 0) {
                        return -1;
                    }
                    found = i;
                }
            }
            return found;
        }

        @Override
        public String sqlName() {
            StringBuilder b = new StringBuilder("STRUCT(");
            for (int i = 0; i < fields.size(); i++) {
                b.append(i > 0 ? ", " : "").append(fields.get(i));
            }
            return b.append(')').toString();
        }

        @Override
        public Class<?> javaClass() {
            return List.class;
        }

        @Override
        public boolean accepts(Object value) {
            if (value == null) {
                return true;
            }
            if (!(value instanceof List<?> values) || values.size() != fields.size()) {
                return false;
            }
            for (int i = 0; i < fields.size(); i++) {
                if (!fields.get(i).type().accepts(values.get(i))) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public boolean isIntegral() {
            return false;
        }

        @Override
        public boolean isFloatingPoint() {
            return false;
        }

        @Override
        public boolean isNumeric() {
            return false;
        }

        @Override
        public boolean isNested() {
            return true;
        }

        @Override
        public String toString() {
            return sqlName();
        }
    }

    /** {@code LIST} of an element type, spelled {@code element[]} in DuckDB; a value is a {@code List}. */
    record ListType(DataType element) implements DataType {
        public ListType {
            Objects.requireNonNull(element, "element");
        }

        @Override
        public String sqlName() {
            return element.sqlName() + "[]";
        }

        @Override
        public Class<?> javaClass() {
            return List.class;
        }

        @Override
        public boolean accepts(Object value) {
            if (value == null) {
                return true;
            }
            if (!(value instanceof List<?> values)) {
                return false;
            }
            for (Object v : values) {
                if (!element.accepts(v)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public boolean isIntegral() {
            return false;
        }

        @Override
        public boolean isFloatingPoint() {
            return false;
        }

        @Override
        public boolean isNumeric() {
            return false;
        }

        @Override
        public boolean isNested() {
            return true;
        }

        @Override
        public String toString() {
            return sqlName();
        }
    }

    /** {@code MAP(key, value)}; a value is a {@code Map} in insertion order whose keys are never {@code null}. */
    record MapType(DataType key, DataType value) implements DataType {
        public MapType {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
        }

        @Override
        public String sqlName() {
            return "MAP(" + key.sqlName() + ", " + value.sqlName() + ")";
        }

        @Override
        public Class<?> javaClass() {
            return Map.class;
        }

        @Override
        public boolean accepts(Object v) {
            if (v == null) {
                return true;
            }
            if (!(v instanceof Map<?, ?> map)) {
                return false;
            }
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getKey() == null || !key.accepts(e.getKey()) || !value.accepts(e.getValue())) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public boolean isIntegral() {
            return false;
        }

        @Override
        public boolean isFloatingPoint() {
            return false;
        }

        @Override
        public boolean isNumeric() {
            return false;
        }

        @Override
        public boolean isNested() {
            return true;
        }

        @Override
        public String toString() {
            return sqlName();
        }
    }
}
