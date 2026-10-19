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

package net.fyrql.core.function;

import net.fyrql.core.type.DataType;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * A function with its result-type rule and its coercion rule given as
 * lambdas; the evaluation is the subclass.
 */
abstract class SimpleFunction implements ScalarFunction {

    private final String name;
    private final Function<List<DataType>, DataType> resultType;
    private final UnaryOperator<List<DataType>> coerce;

    SimpleFunction(String name, Function<List<DataType>, DataType> resultType) {
        this(name, resultType, UnaryOperator.identity());
    }

    SimpleFunction(String name, Function<List<DataType>, DataType> resultType, UnaryOperator<List<DataType>> coerce) {
        this.name = name;
        this.resultType = resultType;
        this.coerce = coerce;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public DataType resultType(List<DataType> arguments) {
        return resultType.apply(arguments);
    }

    @Override
    public List<DataType> coerce(List<DataType> arguments) {
        return coerce.apply(arguments);
    }

    @Override
    public String toString() {
        return name;
    }

    /** Returns the same function under another name, as DuckDB offers aliases. */
    ScalarFunction alias(String aliasName) {
        SimpleFunction self = this;
        return new SimpleFunction(aliasName, resultType, coerce) {
            @Override
            public boolean nullOnNullInput() {
                return self.nullOnNullInput();
            }

            @Override
            public Object evaluate(List<DataType> argumentTypes, Object[] arguments) {
                return self.evaluate(argumentTypes, arguments);
            }
        };
    }

    /** A coercion that casts every argument to one type. */
    static UnaryOperator<List<DataType>> allTo(DataType type) {
        return types -> {
            List<DataType> result = new ArrayList<>();
            for (int i = 0; i < types.size(); i++) {
                result.add(type);
            }
            return result;
        };
    }

    /** A coercion that casts the arguments from an index on to one type. */
    static UnaryOperator<List<DataType>> from(int index, DataType type) {
        return types -> {
            List<DataType> result = new ArrayList<>(types);
            for (int i = index; i < types.size(); i++) {
                result.set(i, type);
            }
            return result;
        };
    }

    /** Tells whether the types are exactly the given count of {@code VARCHAR}. */
    static boolean varchars(List<DataType> types, int count) {
        if (types.size() != count) {
            return false;
        }
        for (DataType t : types) {
            if (t != DataType.VARCHAR) {
                return false;
            }
        }
        return true;
    }

    /**
     * Tells whether the types are {@code VARCHAR} followed by integral types
     * up to {@code BIGINT}, for the given count; DuckDB does not narrow a
     * {@code HUGEINT} to a {@code BIGINT} count implicitly.
     */
    static boolean varcharThenIntegral(List<DataType> types, int count) {
        if (types.size() != count || types.get(0) != DataType.VARCHAR) {
            return false;
        }
        for (DataType t : types.subList(1, count)) {
            if (!isInt64(t)) {
                return false;
            }
        }
        return true;
    }

    /** Tells whether a type is integral and fits a {@code BIGINT}, i.e. is not {@code HUGEINT}. */
    static boolean isInt64(DataType type) {
        return type.isIntegral() && type != DataType.HUGEINT;
    }

    /** Tells whether a type is integral and fits an {@code INTEGER}. */
    static boolean isInt32(DataType type) {
        return type == DataType.TINYINT || type == DataType.SMALLINT || type == DataType.INTEGER;
    }
}
