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

package net.fyrql.sql.ast;

import java.util.Objects;

/**
 * {@code RESET name}: a setting of the session back to the value it started
 * with; {@code RESET ALL} every setting.
 *
 * @param name the name of the setting, as written, or {@code ALL}
 * @param position the position of the statement
 */
public record ResetVariable(String name, Position position) implements Statement {

    public ResetVariable {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(position, "position");
    }
}
