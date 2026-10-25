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
        all.addAll(RegexFunctions.all());
        all.addAll(DateFunctions.all());
        all.addAll(GeneralFunctions.all());
        all.addAll(NestedFunctions.all());
        return all;
    }
}
