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
