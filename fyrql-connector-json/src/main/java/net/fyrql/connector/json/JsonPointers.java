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

package net.fyrql.connector.json;

import com.fasterxml.jackson.core.JsonPointer;

/**
 * JSON pointers as in RFC 6901, which say where in a JSON document its
 * records are: the empty string for the whole document, otherwise a
 * {@code /} before each key or index, such as {@code /data} or
 * {@code /result/items}, with {@code ~0} for {@code ~} and {@code ~1} for
 * {@code /} in a key.
 */
public final class JsonPointers {

    private JsonPointers() {
    }

    /**
     * Checks a JSON pointer.
     *
     * @return the pointer
     * @throws IllegalArgumentException if it is not one
     */
    public static String check(String pointer) {
        try {
            JsonPointer.compile(pointer);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid JSON pointer \"" + pointer + "\": " + e.getMessage(), e);
        }
        return pointer;
    }
}
