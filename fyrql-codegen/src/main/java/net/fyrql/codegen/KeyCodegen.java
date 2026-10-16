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

import java.util.List;

/**
 * Generates the class body of {@link net.fyrql.exec.spi.KeyFunctions} for
 * keys of given types: the hash of
 * {@link net.fyrql.exec.spi.InterpretedKeys}, bit for bit, and the same
 * equality, with the typed getters of the vectors instead of a switch over
 * the vector classes per value. The hash and the equality of single values
 * serve the joins of a pipeline as well.
 */
final class KeyCodegen {

    static final String KEYS = "GeneratedKeys";

    private KeyCodegen() {
    }

    /** Tells whether keys of the types can be compiled: all are flat. */
    static boolean supports(List<DataType> types) {
        return !types.isEmpty() && types.stream().allMatch(Rep::flat);
    }

    static ExpressionCodegen.Unit keys(List<DataType> types) {
        CodeWriter w = new CodeWriter(0);
        w.open("public " + KEYS + "(Object[] refs)").close();
        w.blank();
        w.open("public void hash(Batch b, int[] columns, int[] out)");
        w.line("int n = b.rowCount();");
        for (int k = 0; k < types.size(); k++) {
            String vector = vector(types.get(k));
            w.line(vector + " k" + k + " = (" + vector + ") b.column(columns[" + k + "]);");
        }
        w.open("for (int i = 0; i < n; i++)");
        w.line("int h = 17;");
        for (int k = 0; k < types.size(); k++) {
            w.line("h = h * 31 + " + hash(types.get(k), "k" + k, "i") + ";");
        }
        w.line("out[i] = h ^ (h >>> 16);");
        w.close();
        w.close();
        w.blank();
        w.open("public boolean equal(Batch a, int ra, int[] ca, Batch b, int rb, int[] cb)");
        for (int k = 0; k < types.size(); k++) {
            DataType t = types.get(k);
            String vector = vector(t);
            String x = "x" + k;
            String y = "y" + k;
            w.line(vector + " " + x + " = (" + vector + ") a.column(ca[" + k + "]);");
            w.line(vector + " " + y + " = (" + vector + ") b.column(cb[" + k + "]);");
            w.open("if (" + x + ".isNull(ra) || " + y + ".isNull(rb))")
                    .open("if (" + x + ".isNull(ra) != " + y + ".isNull(rb))").line("return false;").close()
                    .reopen("else if (!(" + equal(t, x, "ra", y, "rb") + "))").line("return false;").close();
        }
        w.line("return true;");
        w.close();
        String source = w.toString();
        return new ExpressionCodegen.Unit(KEYS, source, new Object[] {source});
    }

    private static String vector(DataType type) {
        if (type == DataType.VARCHAR || type == DataType.BLOB) {
            return "BytesVector";
        }
        if (type == DataType.HUGEINT) {
            return "HugeIntVector";
        }
        if (type == DataType.UUID) {
            return "UuidVector";
        }
        return Rep.of(type).vector;
    }

    /**
     * The hash of the value of a vector at a row, 0 for {@code NULL}, as
     * {@link net.fyrql.core.vector.Vectors#hash(net.fyrql.core.vector.Vector, int)}
     * computes it.
     */
    private static String hash(DataType type, String v, String row) {
        Rep rep = Rep.of(type);
        String value;
        if (rep != Rep.OBJECT) {
            value = valueHash(type, v + "." + rep.getter + "(" + row + ")");
        } else if (type == DataType.VARCHAR || type == DataType.BLOB) {
            value = v + ".hash(" + row + ")";
        } else if (type == DataType.HUGEINT) {
            value = "Long.hashCode(" + v + ".getHigh(" + row + ")) * 31 + Long.hashCode(" + v + ".getLow(" + row
                    + "))";
        } else if (type == DataType.UUID) {
            value = "Long.hashCode(" + v + ".getMost(" + row + ")) * 31 + Long.hashCode(" + v + ".getLeast(" + row
                    + "))";
        } else {
            // Vectors.hash gives 0 for NULL itself.
            return "Vectors.hash(" + v + ", " + row + ")";
        }
        return "(" + v + ".isNull(" + row + ") ? 0 : " + value + ")";
    }

    /**
     * The hash of a value that is not {@code NULL} and not held as an
     * {@code Object}, as {@code Vectors.hash} computes it for its vector.
     */
    static String valueHash(DataType type, String value) {
        return switch (Rep.of(type)) {
            case BOOLEAN -> "(" + value + " ? 1231 : 1237)";
            case BYTE, SHORT, INT -> value;
            case LONG -> "Long.hashCode(" + value + ")";
            case FLOAT, DOUBLE -> "Vectors.hash(" + value + ")";
            case DECIMAL -> "Vectors.hashDecimal(" + value + ", " + ((Decimal) type).scale() + ")";
            case OBJECT -> throw new IllegalArgumentException("A value held as an Object: " + type);
        };
    }

    /**
     * The equality of two values that are not {@code NULL} and not held as
     * an {@code Object}, as a comparison by {@code Vectors.compare} is zero.
     */
    static String valueEqual(DataType type, String a, String b) {
        return switch (Rep.of(type)) {
            case BOOLEAN, BYTE, SHORT, INT, LONG, DECIMAL -> a + " == " + b;
            case FLOAT, DOUBLE -> "Vectors.compare(" + a + ", " + b + ") == 0";
            case OBJECT -> throw new IllegalArgumentException("A value held as an Object: " + type);
        };
    }

    /** The equality of the values of two vectors at two rows, neither {@code NULL}. */
    private static String equal(DataType type, String x, String rx, String y, String ry) {
        Rep rep = Rep.of(type);
        if (rep != Rep.OBJECT) {
            return valueEqual(type, x + "." + rep.getter + "(" + rx + ")", y + "." + rep.getter + "(" + ry + ")");
        }
        if (type == DataType.VARCHAR || type == DataType.BLOB) {
            return "BytesVector.equal(" + x + ", " + rx + ", " + y + ", " + ry + ")";
        }
        if (type == DataType.HUGEINT) {
            return x + ".getHigh(" + rx + ") == " + y + ".getHigh(" + ry + ") && " + x + ".getLow(" + rx + ") == "
                    + y + ".getLow(" + ry + ")";
        }
        if (type == DataType.UUID) {
            return x + ".getMost(" + rx + ") == " + y + ".getMost(" + ry + ") && " + x + ".getLeast(" + rx
                    + ") == " + y + ".getLeast(" + ry + ")";
        }
        return "Vectors.compare(" + x + ", " + rx + ", " + y + ", " + ry + ") == 0";
    }
}
