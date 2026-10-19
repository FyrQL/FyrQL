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

import net.fyrql.core.function.FunctionRegistry;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;

/**
 * What every part of the binding of one query shares: the scalar functions,
 * the moment that {@code current_date} and {@code now()} stand for, and the
 * types of the parameters.
 *
 * @param functions the scalar functions
 * @param functionNames the names of the scalar functions, in lower case
 * @param now the time of the query
 * @param parameters the types of the parameters, which the binding adds to
 */
record BindContext(FunctionRegistry functions, Set<String> functionNames, LocalDateTime now,
        ParameterTypes parameters) {
    BindContext {
        Objects.requireNonNull(functions, "functions");
        functionNames = Set.copyOf(functionNames);
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(parameters, "parameters");
    }
}
