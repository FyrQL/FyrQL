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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The scalar functions known by name. Several functions may share a name;
 * the first one that accepts the argument types wins.
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
}
