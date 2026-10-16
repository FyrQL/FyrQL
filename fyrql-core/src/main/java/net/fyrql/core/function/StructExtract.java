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

import java.util.List;
import java.util.Objects;

/**
 * {@code struct_extract(s, 'name')}: the field of a struct, which the
 * binder picks by its name, a constant, and fixes here by its position. The
 * call takes the struct and the name as a {@code VARCHAR} literal, so that
 * its text is the call DuckDB knows; it accepts only a struct whose field at
 * the position has exactly the name.
 *
 * @param index the position of the field
 * @param field the name of the field as the struct declares it
 */
public record StructExtract(int index, String field) implements ScalarFunction {

    public StructExtract {
        if (index < 0) {
            throw new IllegalArgumentException("A field position cannot be negative: " + index);
        }
        Objects.requireNonNull(field, "field");
    }

    @Override
    public String name() {
        return "struct_extract";
    }

    @Override
    public DataType resultType(List<DataType> arguments) {
        if (arguments.size() != 2 || arguments.get(1) != DataType.VARCHAR
                || !(arguments.get(0) instanceof DataType.Struct s) || index >= s.fields().size()
                || !s.fields().get(index).name().equals(field)) {
            return null;
        }
        return s.fields().get(index).type();
    }

    @Override
    public Object evaluate(List<DataType> argumentTypes, Object[] arguments) {
        return ((List<?>) arguments[0]).get(index);
    }
}
