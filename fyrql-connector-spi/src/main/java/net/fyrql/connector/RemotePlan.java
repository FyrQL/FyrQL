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
