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

package net.fyrql.codegen;

import net.fyrql.core.type.DataType;
import net.fyrql.core.type.DataType.Decimal;
import net.fyrql.core.type.DataType.Scalar;

/**
 * How generated code holds a value of a type: a primitive where the vector
 * of the type has one, the unscaled {@code long} of a decimal of at most 18
 * digits, and otherwise the Java value of the interpreter as an
 * {@code Object}.
 */
enum Rep {
    BOOLEAN("boolean", "BooleanVector", "getBoolean", "false", "boolean[]", "Boolean", "booleanValue"),
    BYTE("byte", "TinyIntVector", "getByte", "(byte) 0", "byte[]", "Byte", "byteValue"),
    SHORT("short", "SmallIntVector", "getShort", "(short) 0", "short[]", "Short", "shortValue"),
    INT("int", "IntVector", "getInt", "0", "int[]", "Integer", "intValue"),
    LONG("long", "BigIntVector", "getLong", "0L", "long[]", "Long", "longValue"),
    FLOAT("float", "FloatVector", "getFloat", "0.0f", "float[]", "Float", "floatValue"),
    DOUBLE("double", "DoubleVector", "getDouble", "0.0", "double[]", "Double", "doubleValue"),
    DECIMAL("long", "DecimalVector", "getUnscaledLong", "0L", "long[]", null, null),
    OBJECT("Object", "Vector", "get", "null", null, null, null);

    /** The Java type of a value. */
    final String java;
    /** The class of the vector that holds values of the representation. */
    final String vector;
    /** The getter of the vector. */
    final String getter;
    /** The value of a variable that holds {@code NULL}. */
    final String zero;
    /** The array a result is collected in; {@code null} for {@link #OBJECT}, which uses a builder. */
    final String array;
    private final String boxed;
    private final String unboxer;

    Rep(String java, String vector, String getter, String zero, String array, String boxed, String unboxer) {
        this.java = java;
        this.vector = vector;
        this.getter = getter;
        this.zero = zero;
        this.array = array;
        this.boxed = boxed;
        this.unboxer = unboxer;
    }

    /** Tells whether generated code handles values of a type: every scalar type and every decimal. */
    static boolean flat(DataType type) {
        return type instanceof Scalar || type instanceof Decimal;
    }

    static Rep of(DataType type) {
        if (type instanceof Decimal d) {
            return d.fitsLong() ? DECIMAL : OBJECT;
        }
        return switch ((Scalar) type) {
            case BOOLEAN -> BOOLEAN;
            case TINYINT -> BYTE;
            case SMALLINT -> SHORT;
            case INTEGER, DATE -> INT;
            case BIGINT, TIMESTAMP -> LONG;
            case FLOAT -> FLOAT;
            case DOUBLE -> DOUBLE;
            default -> OBJECT;
        };
    }

    /** Tells whether the representation is one of the integral primitives. */
    boolean integral() {
        return this == BYTE || this == SHORT || this == INT || this == LONG;
    }

    /** Returns code that turns a value into the Java value of the interpreter. */
    String box(String value, DataType type) {
        return switch (this) {
            case DECIMAL -> "BigDecimal.valueOf(" + value + ", " + ((Decimal) type).scale() + ")";
            case OBJECT -> value;
            default -> boxed + ".valueOf(" + value + ")";
        };
    }

    /**
     * Returns code that turns a Java value of the interpreter, which is not
     * {@code null}, into a value of this representation, failing as the
     * builder of a vector of the type fails for a value of another class.
     * The code for a decimal refers to a field with the type, {@code typeField}.
     */
    String unbox(String object, String typeField) {
        return switch (this) {
            case DECIMAL -> "GeneratedSupport.unscaled(" + object + ", " + typeField + ")";
            case OBJECT -> object;
            default -> "((" + boxed + ") " + object + ")." + unboxer + "()";
        };
    }
}
