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

package net.fyrql.sql.bind;

import net.fyrql.core.expr.Expression;
import net.fyrql.core.function.ScalarFunction;
import net.fyrql.core.type.DataType;
import net.fyrql.sql.SqlException;
import net.fyrql.sql.ast.SqlExpr;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The types of the parameters of a query while it is bound. A query with
 * parameters is bound twice. The first time, a parameter is typed like a
 * bare {@code NULL}: by the first context that gives a {@code NULL} a
 * type, and is a value of that type from then on. The second time, every
 * parameter has its type from the start. Either way, the value of a typed
 * parameter is no constant, see {@link Placeholder}, so that the binder
 * folds nothing over it and the query binds to the types it binds to with
 * any value.
 */
final class ParameterTypes {

    private final boolean allowed;
    private final boolean fixed;
    private final Map<Integer, DataType> types = new TreeMap<>();

    private ParameterTypes(boolean allowed, boolean fixed, Map<Integer, DataType> types) {
        this.allowed = allowed;
        this.fixed = fixed;
        this.types.putAll(types);
    }

    /** For a query without parameters. */
    static ParameterTypes none() {
        return new ParameterTypes(false, false, Map.of());
    }

    /** For the first binding of a query with parameters, which finds their types. */
    static ParameterTypes inferred() {
        return new ParameterTypes(true, false, Map.of());
    }

    /** For the second binding of a query with parameters, with their types by number. */
    static ParameterTypes fixed(Map<Integer, DataType> types) {
        return new ParameterTypes(true, true, types);
    }

    /**
     * Checks that a parameter may be bound here and returns its type if it
     * has one from the start.
     *
     * @return the type, or {@code null} while the binding finds the types
     * @throws SqlException if the query takes no parameters
     */
    DataType check(SqlExpr.Parameter p) {
        if (!allowed) {
            throw new SqlException("Not supported: parameters outside a prepared query", p.position());
        }
        return fixed ? types.get(p.index()) : null;
    }

    /**
     * Gives a parameter a type and returns it as a value of that type. A
     * parameter bound twice, as in a common table that a query reads twice,
     * must take the same type both times.
     *
     * @throws SqlException if the parameter has another type already
     */
    Expression typed(SqlExpr.Parameter p, DataType type) {
        DataType known = types.putIfAbsent(p.index(), type);
        if (known != null && !known.equals(type)) {
            throw new SqlException("Parameter " + p.index() + " would be a " + known.sqlName() + " in one place and a "
                    + type.sqlName() + " in another; write CAST(? AS type)", p.position());
        }
        return placeholder(p, type);
    }

    /** Returns a parameter as a value of a type without giving it the type, for a trial. */
    static Expression placeholder(SqlExpr.Parameter p, DataType type) {
        return new Expression.Call(new Placeholder(p.index(), type), List.of());
    }

    /** Returns the type of a parameter, or {@code null} if it has none yet. */
    DataType type(int index) {
        return types.get(index);
    }

    /** Returns the types found so far, by number. */
    Map<Integer, DataType> types() {
        return Map.copyOf(types);
    }

    /**
     * A parameter as a value: a call without arguments of a function that
     * is not {@linkplain ScalarFunction#foldable() foldable}. It has no
     * value while the query is bound; where the binder evaluates the
     * values of {@code VALUES}, it is a {@code NULL}. A plan with it is not
     * run.
     *
     * @param index the number of the parameter
     * @param type its type
     */
    record Placeholder(int index, DataType type) implements ScalarFunction {
        Placeholder {
            Objects.requireNonNull(type, "type");
        }

        @Override
        public String name() {
            return "$" + index;
        }

        @Override
        public DataType resultType(List<DataType> arguments) {
            return arguments.isEmpty() ? type : null;
        }

        @Override
        public boolean nullOnNullInput() {
            return false;
        }

        @Override
        public boolean foldable() {
            return false;
        }

        @Override
        public Object evaluate(List<DataType> argumentTypes, Object[] arguments) {
            return null;
        }
    }
}
