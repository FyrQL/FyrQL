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

package net.fyrql.connector.memory;

import net.fyrql.connector.ConfigOptions;
import net.fyrql.connector.ConnectorFactory;

import java.nio.file.Path;
import java.util.Map;

/**
 * Creates memory sources of the type {@code memory} from options:
 * {@code batchSize} and {@code splitRows}. Such a source starts without
 * tables; SQL creates them with {@code CREATE TABLE}, and they live as long
 * as the engine.
 * <p>
 * Internal; not covered by the compatibility promise.
 */
public final class MemoryConnectorFactory implements ConnectorFactory {

    @Override
    public String type() {
        return "memory";
    }

    @Override
    public MemoryConnector create(String name, Map<String, String> options, Path directory) {
        ConfigOptions o = new ConfigOptions("the memory source " + name, options, directory);
        MemoryConnector.Builder b = MemoryConnector.builder(name);
        o.integer("batchSize").ifPresent(b::batchSize);
        o.integer("splitRows").ifPresent(b::splitRows);
        o.rejectUnknown();
        return b.build();
    }
}
