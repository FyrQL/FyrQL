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

package net.fyrql.sql.bind;

import net.fyrql.core.function.FunctionRegistry;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;

/**
 * What every part of the binding of one query shares: the scalar functions
 * and the moment that {@code current_date} and {@code now()} stand for.
 *
 * @param functions the scalar functions
 * @param functionNames the names of the scalar functions, in lower case
 * @param now the time of the query
 */
record BindContext(FunctionRegistry functions, Set<String> functionNames, LocalDateTime now) {
    BindContext {
        Objects.requireNonNull(functions, "functions");
        functionNames = Set.copyOf(functionNames);
        Objects.requireNonNull(now, "now");
    }
}
