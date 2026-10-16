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

package net.fyrql.sql.ast;

import java.util.List;
import java.util.Objects;

/** One item of a {@code SELECT} list: {@code *} with its modifiers, or an expression with an optional alias. */
public sealed interface SelectItem {

    /**
     * {@code [qualifier.]* [EXCLUDE (names)] [REPLACE (expr AS name, ...)]};
     * the qualifier is empty for a plain {@code *}.
     */
    record Star(List<String> qualifier, List<String> exclude, List<Replacement> replace, Position position)
            implements SelectItem {
        public Star {
            qualifier = List.copyOf(qualifier);
            exclude = List.copyOf(exclude);
            replace = List.copyOf(replace);
            Objects.requireNonNull(position, "position");
        }
    }

    /** {@code expression AS name} in the {@code REPLACE} of a {@link Star}. */
    record Replacement(SqlExpr expression, String name) {
        public Replacement {
            Objects.requireNonNull(expression, "expression");
            Objects.requireNonNull(name, "name");
        }
    }

    /** {@code expression [AS alias]}; the alias is {@code null} when absent. */
    record ExpressionItem(SqlExpr expression, String alias) implements SelectItem {
        public ExpressionItem {
            Objects.requireNonNull(expression, "expression");
        }
    }
}
