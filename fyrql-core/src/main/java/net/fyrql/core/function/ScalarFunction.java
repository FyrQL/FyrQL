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

import net.fyrql.core.expr.Expression;
import net.fyrql.core.type.DataType;

import java.time.ZoneId;
import java.util.List;

/**
 * A scalar function of the catalog: a name, the rule for its result type
 * given the argument types, and the computation on Java values. A function
 * that returns {@code NULL} whenever an argument is {@code NULL} says so
 * through {@link #nullOnNullInput()}, and then never sees a {@code null}.
 */
public interface ScalarFunction {

    /** Returns the name, in lower case, as DuckDB spells it. */
    String name();

    /**
     * Returns the result type for the given argument types, or {@code null}
     * if the function does not accept them.
     */
    DataType resultType(List<DataType> arguments);

    /** Tells whether a {@code NULL} argument makes the result {@code NULL} without a call. */
    default boolean nullOnNullInput() {
        return true;
    }

    /**
     * Tells whether a call with constant arguments has its value when the
     * query is bound, so that it is a constant itself; false for a function
     * whose value comes later, such as a parameter of a prepared query.
     */
    default boolean foldable() {
        return true;
    }

    /**
     * Tells whether the function reads the values of its arguments; a
     * function that does not, such as {@code typeof}, is called with
     * {@code null}s, and its arguments are not evaluated, as DuckDB never
     * evaluates them either.
     */
    default boolean readsArguments() {
        return true;
    }

    /**
     * Returns the types the arguments are cast to before the call, for
     * argument types that {@link #resultType} accepts: the implicit casts of
     * DuckDB, e.g. every numeric argument of {@code sqrt} to {@code DOUBLE}.
     * By default the arguments keep their types.
     */
    default List<DataType> coerce(List<DataType> arguments) {
        return arguments;
    }

    /**
     * Returns the function for a call with these arguments: by default this
     * function. A function whose result type depends on the value of a
     * constant argument returns a variant for that value, as
     * {@code round(DECIMAL, n)} does for its places. Calls should be built
     * with the function this returns.
     *
     * @throws IllegalArgumentException if an argument must be a constant and is none
     */
    default ScalarFunction specialize(List<Expression> arguments) {
        return this;
    }

    /**
     * Returns the function for a call in a time zone: by default this
     * function. A function whose result depends on the time zone, such as
     * the parts of a {@code TIMESTAMP WITH TIME ZONE}, returns a variant that
     * holds the zone, so that the interpreter, generated code and the
     * folding of constants compute alike. A binder calls this for every call,
     * after {@link #specialize}, with the time zone of the session.
     */
    default ScalarFunction inZone(ZoneId zone) {
        return this;
    }

    /**
     * Computes the result for one row.
     *
     * @param argumentTypes the types of the arguments after {@link #coerce}
     * @param arguments the argument values of those types; {@code null} only
     *        if {@link #nullOnNullInput()} is false
     * @return the result, {@code null} for {@code NULL}
     */
    Object evaluate(List<DataType> argumentTypes, Object[] arguments);
}
