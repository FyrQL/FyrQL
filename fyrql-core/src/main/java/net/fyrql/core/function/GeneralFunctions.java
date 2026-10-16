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
import net.fyrql.core.type.TypeRules;
import net.fyrql.core.value.Order;

import java.util.List;

/**
 * Functions over values of any type: {@code greatest} and {@code least},
 * which skip {@code NULL}s and compare in the common type of their
 * arguments, and {@code typeof}, which names the type of its argument.
 */
final class GeneralFunctions {

    private GeneralFunctions() {
    }

    static List<ScalarFunction> all() {
        return List.of(GREATEST, LEAST, TYPEOF);
    }

    /** The common type of the arguments, or {@code null} if they have none. */
    private static DataType commonOrNull(List<DataType> types) {
        if (types.isEmpty()) {
            return null;
        }
        try {
            return TypeRules.common(types);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** {@code greatest} or {@code least}: the extreme among the arguments that are not {@code NULL}. */
    private static SimpleFunction extreme(String name, int direction) {
        return new SimpleFunction(name, GeneralFunctions::commonOrNull,
                types -> SimpleFunction.allTo(commonOrNull(types)).apply(types)) {
            @Override
            public boolean nullOnNullInput() {
                return false;
            }

            @Override
            public Object evaluate(List<DataType> types, Object[] args) {
                DataType t = types.get(0);
                Object result = null;
                for (Object v : args) {
                    if (v == null) {
                        continue;
                    }
                    // An equal value does not replace the earlier one, so greatest(-0.0, 0.0) is -0.0 as in DuckDB.
                    if (result == null || Integer.signum(Order.compare(v, result, t)) == direction) {
                        result = v;
                    }
                }
                return result;
            }
        };
    }

    static final SimpleFunction GREATEST = extreme("greatest", 1);

    static final SimpleFunction LEAST = extreme("least", -1);

    /** {@code typeof(x)}: the name of the type of the argument, also for {@code NULL}. */
    static final SimpleFunction TYPEOF = new SimpleFunction("typeof",
            types -> types.size() == 1 ? DataType.VARCHAR : null) {
        @Override
        public boolean nullOnNullInput() {
            return false;
        }

        @Override
        public boolean readsArguments() {
            return false;
        }

        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            return types.get(0).sqlName();
        }
    };
}
