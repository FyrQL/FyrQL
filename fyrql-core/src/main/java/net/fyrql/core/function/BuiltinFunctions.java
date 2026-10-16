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

import java.util.ArrayList;
import java.util.List;

/**
 * The built-in scalar functions, named and behaving as in DuckDB 1.5.6:
 * {@link NumericFunctions}, {@link StringFunctions}, {@link DateFunctions},
 * {@link GeneralFunctions} and {@link NestedFunctions}. {@code coalesce},
 * {@code if}, {@code nullif}, {@code ifnull} and {@code mod} are not
 * functions of the registry but forms of the expression tree that a binder
 * produces.
 */
final class BuiltinFunctions {

    private BuiltinFunctions() {
    }

    static List<ScalarFunction> all() {
        List<ScalarFunction> all = new ArrayList<>();
        all.addAll(NumericFunctions.all());
        all.addAll(StringFunctions.all());
        all.addAll(DateFunctions.all());
        all.addAll(GeneralFunctions.all());
        all.addAll(NestedFunctions.all());
        return all;
    }
}
