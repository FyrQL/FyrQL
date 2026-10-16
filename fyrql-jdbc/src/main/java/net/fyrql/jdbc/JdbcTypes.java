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

package net.fyrql.jdbc;

import net.fyrql.core.type.DataType;

import java.sql.Types;

/**
 * How the types of FyrQL appear in JDBC: the code of {@link Types}, the
 * class of {@code getObject}, the precision, scale and display size. The
 * name of a type is always {@link DataType#sqlName()}. {@code HUGEINT} is a
 * {@code NUMERIC} read as {@code BigDecimal}, {@code FLOAT} a {@code REAL}
 * (the {@code FLOAT} of JDBC has double precision), {@code UUID} and
 * {@code MAP} are {@code OTHER}.
 */
final class JdbcTypes {

    /** The precision and display size of a type without a bound on its length. */
    static final int UNBOUNDED = Integer.MAX_VALUE;

    private JdbcTypes() {
    }

    /** Returns the code of {@link Types} of a type. */
    static int sqlType(DataType type) {
        return switch (type) {
            case DataType.Decimal d -> Types.DECIMAL;
            case DataType.ListType l -> Types.ARRAY;
            case DataType.Struct s -> Types.STRUCT;
            case DataType.MapType m -> Types.OTHER;
            case DataType.Scalar s -> switch (s) {
                case BOOLEAN -> Types.BOOLEAN;
                case TINYINT -> Types.TINYINT;
                case SMALLINT -> Types.SMALLINT;
                case INTEGER -> Types.INTEGER;
                case BIGINT -> Types.BIGINT;
                case HUGEINT -> Types.NUMERIC;
                case FLOAT -> Types.REAL;
                case DOUBLE -> Types.DOUBLE;
                case VARCHAR -> Types.VARCHAR;
                case BLOB -> Types.VARBINARY;
                case DATE -> Types.DATE;
                case TIMESTAMP -> Types.TIMESTAMP;
                case UUID -> Types.OTHER;
            };
        };
    }

    /** Returns the class of the values {@code getObject} returns for a type. */
    static Class<?> javaClass(DataType type) {
        return switch (type) {
            case DataType.Decimal d -> java.math.BigDecimal.class;
            case DataType.ListType l -> java.sql.Array.class;
            case DataType.Struct s -> java.sql.Struct.class;
            case DataType.MapType m -> java.util.Map.class;
            case DataType.Scalar s -> switch (s) {
                case HUGEINT -> java.math.BigDecimal.class;
                case DATE -> java.sql.Date.class;
                case TIMESTAMP -> java.sql.Timestamp.class;
                default -> s.javaClass();
            };
        };
    }

    /** Returns the precision: the digits of a number, the characters of a date or time, else unbounded. */
    static int precision(DataType type) {
        return switch (type) {
            case DataType.Decimal d -> d.precision();
            case DataType.Scalar s -> switch (s) {
                case BOOLEAN -> 1;
                case TINYINT -> 3;
                case SMALLINT -> 5;
                case INTEGER -> 10;
                case BIGINT -> 19;
                case HUGEINT -> 39;
                case FLOAT -> 7;
                case DOUBLE -> 15;
                case DATE -> 10;
                case TIMESTAMP -> 26;
                case UUID -> 36;
                case VARCHAR, BLOB -> UNBOUNDED;
            };
            default -> UNBOUNDED;
        };
    }

    /** Returns the scale: the digits after the point of a decimal, the fraction of a second of a timestamp. */
    static int scale(DataType type) {
        if (type instanceof DataType.Decimal d) {
            return d.scale();
        }
        return type == DataType.TIMESTAMP ? 6 : 0;
    }

    /** Returns the most characters of the text of a value. */
    static int displaySize(DataType type) {
        return switch (type) {
            case DataType.Decimal d -> d.precision() + (d.scale() > 0 ? 2 : 1);
            case DataType.Scalar s -> switch (s) {
                case BOOLEAN -> 5;
                case TINYINT -> 4;
                case SMALLINT -> 6;
                case INTEGER -> 11;
                case BIGINT -> 20;
                case HUGEINT -> 40;
                case FLOAT -> 15;
                case DOUBLE -> 25;
                case DATE -> 10;
                case TIMESTAMP -> 26;
                case UUID -> 36;
                case VARCHAR, BLOB -> UNBOUNDED;
            };
            default -> UNBOUNDED;
        };
    }

    /** Tells whether the values of a type can be negative. */
    static boolean signed(DataType type) {
        return type.isNumeric();
    }

    /** Tells whether the case of the values matters: for text. */
    static boolean caseSensitive(DataType type) {
        return type == DataType.VARCHAR;
    }
}
