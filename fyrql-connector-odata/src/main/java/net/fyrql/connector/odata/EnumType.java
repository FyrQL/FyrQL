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

package net.fyrql.connector.odata;

import java.util.List;
import java.util.Objects;

/**
 * An enumeration type of OData 4, whose values a service writes as the
 * names of its members.
 *
 * @param name the qualified name
 * @param underlyingType the type of the values of the members, {@code Edm.Int32} by default
 * @param flags whether a value may combine several members
 * @param members the members in order
 */
public record EnumType(String name, String underlyingType, boolean flags, List<EnumType.Member> members) {

    public EnumType {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(underlyingType, "underlyingType");
        members = List.copyOf(members);
    }

    /**
     * A member: its name and its value as written, or {@code null} if the
     * document leaves it to the position of the member.
     */
    public record Member(String name, String value) {
        public Member {
            Objects.requireNonNull(name, "name");
        }
    }
}
