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

import net.fyrql.core.type.DataType;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * The string functions, counting in code points and with the edge cases
 * of DuckDB 1.5.6: {@code trim} without a second argument removes only
 * spaces; {@code left} and {@code right} with a negative count drop that
 * many characters from the other end; {@code lpad} and {@code rpad}
 * truncate to the length and fail on an empty padding; {@code split_part}
 * counts from one, from the end for a negative index, and gives the empty
 * string outside the range; {@code strpos} gives 0 when absent;
 * {@code concat} and {@code concat_ws} skip {@code NULL}s, {@code ||} does
 * not. A string argument is not cast implicitly, except that
 * {@code concat}, {@code concat_ws} and {@code ||} take any scalar type and
 * cast it to its text form.
 */
final class StringFunctions {

    private StringFunctions() {
    }

    static List<ScalarFunction> all() {
        return List.of(LENGTH, LENGTH.alias("len"), LOWER, UPPER, SUBSTRING, SUBSTRING.alias("substr"), TRIM, LTRIM,
                RTRIM, REPLACE, CONTAINS, PREFIX, PREFIX.alias("starts_with"), SUFFIX, SUFFIX.alias("ends_with"),
                STRPOS, STRPOS.alias("instr"), LEFT, RIGHT, REPEAT, REVERSE, LPAD, RPAD, SPLIT_PART, CONCAT,
                CONCAT_WS, CONCAT_OPERATOR);
    }

    private static final Function<List<DataType>, DataType> VARCHAR_TO_VARCHAR =
            types -> SimpleFunction.varchars(types, 1) ? DataType.VARCHAR : null;

    private static final Function<List<DataType>, DataType> TWO_VARCHARS_TO_BOOLEAN =
            types -> SimpleFunction.varchars(types, 2) ? DataType.BOOLEAN : null;

    /** A unary string function. */
    private static SimpleFunction unary(String name, Function<String, String> op) {
        return new SimpleFunction(name, VARCHAR_TO_VARCHAR) {
            @Override
            public Object evaluate(List<DataType> types, Object[] args) {
                return op.apply((String) args[0]);
            }
        };
    }

    /** A predicate of two strings. */
    private static SimpleFunction predicate(String name, BiFunction<String, String, Boolean> op) {
        return new SimpleFunction(name, TWO_VARCHARS_TO_BOOLEAN) {
            @Override
            public Object evaluate(List<DataType> types, Object[] args) {
                return op.apply((String) args[0], (String) args[1]);
            }
        };
    }

    /** A function of a string and an integral count, which is cast to {@code BIGINT}. */
    private static SimpleFunction withCount(String name, BiFunction<String, Long, String> op) {
        return new SimpleFunction(name, types -> SimpleFunction.varcharThenIntegral(types, 2) ? DataType.VARCHAR : null,
                SimpleFunction.from(1, DataType.BIGINT)) {
            @Override
            public Object evaluate(List<DataType> types, Object[] args) {
                return op.apply((String) args[0], (Long) args[1]);
            }
        };
    }

    /** {@code trim}, {@code ltrim} or {@code rtrim} with an optional set of characters, by default the space. */
    private static SimpleFunction trim(String name, boolean left, boolean right) {
        return new SimpleFunction(name, types -> SimpleFunction.varchars(types, 1) || SimpleFunction.varchars(types, 2)
                ? DataType.VARCHAR : null) {
            @Override
            public Object evaluate(List<DataType> types, Object[] args) {
                return Strings.trim((String) args[0], args.length > 1 ? (String) args[1] : " ", left, right);
            }
        };
    }

    static final SimpleFunction LENGTH = new SimpleFunction("length",
            types -> SimpleFunction.varchars(types, 1) ? DataType.BIGINT : null) {
        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            return (long) Strings.length((String) args[0]);
        }
    };

    static final SimpleFunction LOWER = unary("lower", Strings::lower);

    static final SimpleFunction UPPER = unary("upper", Strings::upper);

    static final SimpleFunction REVERSE = unary("reverse", Strings::reverse);

    /** {@code substring(s, start)} and {@code substring(s, start, length)}, see {@link Strings#substring}. */
    static final SimpleFunction SUBSTRING = new SimpleFunction("substring",
            types -> SimpleFunction.varcharThenIntegral(types, 2) || SimpleFunction.varcharThenIntegral(types, 3)
                    ? DataType.VARCHAR : null,
            SimpleFunction.from(1, DataType.BIGINT)) {
        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            long start = (Long) args[1];
            return args.length > 2 ? Strings.substring((String) args[0], start, (Long) args[2])
                    : Strings.substring((String) args[0], start);
        }
    };

    static final SimpleFunction TRIM = trim("trim", true, true);

    static final SimpleFunction LTRIM = trim("ltrim", true, false);

    static final SimpleFunction RTRIM = trim("rtrim", false, true);

    /** {@code replace(s, from, to)}; an empty {@code from} changes nothing. */
    static final SimpleFunction REPLACE = new SimpleFunction("replace",
            types -> SimpleFunction.varchars(types, 3) ? DataType.VARCHAR : null) {
        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            String from = (String) args[1];
            return from.isEmpty() ? args[0] : ((String) args[0]).replace(from, (String) args[2]);
        }
    };

    static final SimpleFunction CONTAINS = predicate("contains", String::contains);

    static final SimpleFunction PREFIX = predicate("prefix", String::startsWith);

    static final SimpleFunction SUFFIX = predicate("suffix", String::endsWith);

    /** {@code strpos(s, sub)}: the 1-based position in code points, 0 if absent. */
    static final SimpleFunction STRPOS = new SimpleFunction("strpos",
            types -> SimpleFunction.varchars(types, 2) ? DataType.BIGINT : null) {
        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            return Strings.position((String) args[0], (String) args[1]);
        }
    };

    static final SimpleFunction LEFT = withCount("left", Strings::left);

    static final SimpleFunction RIGHT = withCount("right", Strings::right);

    static final SimpleFunction REPEAT = withCount("repeat", Strings::repeat);

    /** {@code lpad(s, length, padding)} and {@code rpad}; the length is cast to {@code INTEGER}. */
    private static SimpleFunction pad(String name, boolean left) {
        return new SimpleFunction(name, types -> types.size() == 3 && types.get(0) == DataType.VARCHAR
                && SimpleFunction.isInt32(types.get(1)) && types.get(2) == DataType.VARCHAR ? DataType.VARCHAR : null,
                types -> List.of(DataType.VARCHAR, DataType.INTEGER, DataType.VARCHAR)) {
            @Override
            public Object evaluate(List<DataType> types, Object[] args) {
                return Strings.pad((String) args[0], (Integer) args[1], (String) args[2], left, name());
            }
        };
    }

    static final SimpleFunction LPAD = pad("lpad", true);

    static final SimpleFunction RPAD = pad("rpad", false);

    /** {@code split_part(s, separator, index)}, 1-based, see {@link Strings#splitPart}. */
    static final SimpleFunction SPLIT_PART = new SimpleFunction("split_part", types -> types.size() == 3
            && types.get(0) == DataType.VARCHAR && types.get(1) == DataType.VARCHAR
            && SimpleFunction.isInt64(types.get(2)) ? DataType.VARCHAR : null, SimpleFunction.from(2, DataType.BIGINT)) {
        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            return Strings.splitPart((String) args[0], (String) args[1], (Long) args[2]);
        }
    };

    /** Tells whether every type is scalar, i.e. has a text form that concatenates. */
    private static boolean allScalar(List<DataType> types) {
        for (DataType t : types) {
            if (t.isNested()) {
                return false;
            }
        }
        return true;
    }

    /** {@code concat(a, b, ...)} of any scalar types, each cast to its text form; {@code NULL} counts as empty. */
    static final SimpleFunction CONCAT = new SimpleFunction("concat",
            types -> !types.isEmpty() && allScalar(types) ? DataType.VARCHAR : null,
            SimpleFunction.allTo(DataType.VARCHAR)) {
        @Override
        public boolean nullOnNullInput() {
            return false;
        }

        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            StringBuilder b = new StringBuilder();
            for (Object arg : args) {
                if (arg != null) {
                    b.append((String) arg);
                }
            }
            return b.toString();
        }
    };

    /**
     * {@code concat_ws(separator, a, b, ...)}: the text forms of the
     * arguments that are not {@code NULL}, joined by the separator;
     * {@code NULL} if the separator is.
     */
    static final SimpleFunction CONCAT_WS = new SimpleFunction("concat_ws",
            types -> types.size() >= 2 && types.get(0) == DataType.VARCHAR && allScalar(types) ? DataType.VARCHAR : null,
            SimpleFunction.allTo(DataType.VARCHAR)) {
        @Override
        public boolean nullOnNullInput() {
            return false;
        }

        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            if (args[0] == null) {
                return null;
            }
            StringBuilder b = new StringBuilder();
            boolean first = true;
            for (int i = 1; i < args.length; i++) {
                if (args[i] != null) {
                    b.append(first ? "" : (String) args[0]).append((String) args[i]);
                    first = false;
                }
            }
            return b.toString();
        }
    };

    /** {@code a || b} of two scalar values in their text forms; {@code NULL} if either is. */
    static final SimpleFunction CONCAT_OPERATOR = new SimpleFunction("||",
            types -> types.size() == 2 && allScalar(types) ? DataType.VARCHAR : null,
            SimpleFunction.allTo(DataType.VARCHAR)) {
        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            return (String) args[0] + (String) args[1];
        }
    };
}
