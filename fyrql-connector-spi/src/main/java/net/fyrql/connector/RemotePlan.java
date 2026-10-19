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

package net.fyrql.connector;

import net.fyrql.core.type.Schema;

import java.util.Objects;

/**
 * What a source runs of a part of a plan that it has taken over as a
 * whole, see {@link Connector#planRemote}.
 *
 * @param output the columns of the delivered batches: those of the part
 *        of the plan, with the same types
 * @param description what the source runs, for the plan the engine shows,
 *        e.g. the statement of a database
 * @param state whatever the connector needs to remember to run it
 */
public record RemotePlan(Schema output, String description, Object state) {

    public RemotePlan {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(description, "description");
    }
}
