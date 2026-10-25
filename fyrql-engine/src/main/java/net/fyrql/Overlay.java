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

package net.fyrql;

import net.fyrql.connector.Connector;
import net.fyrql.core.function.FunctionRegistry;

import java.util.Map;
import java.util.Objects;

/**
 * Sessions with sources and functions of their own, for a server that
 * shows its clients more than the engine has, as the PostgreSQL wire
 * protocol server shows the catalogs of PostgreSQL. The engine, its other
 * sessions and the clients of other servers do not see them. The sources
 * of such a session come after those of the engine; it reads and writes
 * them as it reads and writes those, with the budget and the threads of
 * the engine. The engine does not close them.
 * <p>
 * Internal; not covered by the compatibility promise.
 */
public final class Overlay {

    private Overlay() {
    }

    /**
     * Starts a session with the settings of the builder of the engine, as
     * {@link FyrQL#newSession()} does, that also sees the given sources and
     * binds calls with the given functions.
     *
     * @param sources the sources of the session by their names, in their order
     * @param functions the functions of the session, e.g. a
     *        {@linkplain FunctionRegistry#copy() copy} of those of the engine
     *        with more
     * @throws IllegalArgumentException if a source has the name of a source of the engine
     */
    public static Session newSession(FyrQL engine, Map<String, Connector> sources, FunctionRegistry functions) {
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(functions, "functions");
        for (String name : sources.keySet()) {
            if (engine.sources().contains(name)) {
                throw new IllegalArgumentException("The engine has a source " + name + " already");
            }
        }
        return engine.newSession(sources, functions);
    }
}
