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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * {@code struct_pack(a := x, b := y)}, also written {@code {'a': x, 'b': y}}:
 * a struct whose fields have the names and take the types and values of the
 * arguments. A {@code NULL} argument is a {@code NULL} field, not a
 * {@code NULL} struct.
 *
 * @param names the names of the fields, at least one, no two equal without regard to case
 */
public record StructPack(List<String> names) implements ScalarFunction {

    public StructPack {
        names = List.copyOf(names);
        if (names.isEmpty()) {
            throw new IllegalArgumentException("Can't pack nothing into a struct");
        }
        Set<String> seen = new HashSet<>();
        for (String name : names) {
            if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Duplicate struct entry name \"" + name + "\"");
            }
        }
    }

    @Override
    public String name() {
        return "struct_pack";
    }

    @Override
    public DataType resultType(List<DataType> arguments) {
        if (arguments.size() != names.size()) {
            return null;
        }
        List<DataType.Struct.Field> fields = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            fields.add(new DataType.Struct.Field(names.get(i), arguments.get(i)));
        }
        return new DataType.Struct(fields);
    }

    @Override
    public boolean nullOnNullInput() {
        return false;
    }

    @Override
    public Object evaluate(List<DataType> argumentTypes, Object[] arguments) {
        return new ArrayList<>(Arrays.asList(arguments));
    }
}
