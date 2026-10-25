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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The scalar functions known by name. Several functions may share a name;
 * the first one that accepts the argument types wins.
 * <p>
 * Part of the API; covered by the compatibility promise.
 */
public final class FunctionRegistry {

    private final Map<String, List<ScalarFunction>> functions = new LinkedHashMap<>();

    /** Returns a registry with the built-in functions. */
    public static FunctionRegistry standard() {
        FunctionRegistry r = new FunctionRegistry();
        for (ScalarFunction f : BuiltinFunctions.all()) {
            r.register(f);
        }
        return r;
    }

    /** Adds a function; a later registration of the same name is tried after the earlier ones. */
    public void register(ScalarFunction function) {
        functions.computeIfAbsent(function.name().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(function);
    }

    /**
     * Returns the function with the name that accepts the argument types.
     *
     * @throws IllegalArgumentException if there is none
     */
    public ScalarFunction resolve(String name, List<DataType> arguments) {
        List<ScalarFunction> candidates = functions.get(name.toLowerCase(Locale.ROOT));
        if (candidates == null) {
            throw new IllegalArgumentException("Unknown function " + name);
        }
        for (ScalarFunction f : candidates) {
            if (f.resultType(arguments) != null) {
                return f;
            }
        }
        throw new IllegalArgumentException("No function " + name + " for arguments " + arguments);
    }

    /** Returns the names of all functions. */
    public List<String> names() {
        return List.copyOf(functions.keySet());
    }

    /**
     * Returns a new registry with the functions of this one, in the same
     * order; functions registered in either later are not in the other.
     */
    public FunctionRegistry copy() {
        FunctionRegistry r = new FunctionRegistry();
        for (Map.Entry<String, List<ScalarFunction>> e : functions.entrySet()) {
            r.functions.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        return r;
    }
}
