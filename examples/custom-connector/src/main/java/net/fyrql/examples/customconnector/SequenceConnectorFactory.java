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

package net.fyrql.examples.customconnector;

import net.fyrql.connector.ConfigOptions;
import net.fyrql.connector.Connector;
import net.fyrql.connector.ConnectorFactory;

import java.nio.file.Path;
import java.util.Map;

/**
 * Creates the sources of the type {@code sequence} of a configuration file. The engine finds it through
 * {@code META-INF/services/net.fyrql.connector.ConnectorFactory}, so that a file can name the type:
 *
 * <pre>
 * sources:
 *   - name: numbers
 *     type: sequence
 *     max: 100000
 * </pre>
 */
public final class SequenceConnectorFactory implements ConnectorFactory {

    @Override
    public String type() {
        return "sequence";
    }

    @Override
    public Connector create(String name, Map<String, String> options, Path directory) {
        ConfigOptions config = new ConfigOptions("the sequence source " + name, options, directory);
        long max = config.get("max", "a whole number of at least 1", Long::parseLong).orElse(1000L);
        int batchSize = config.integer("batchSize").orElse(1024);
        // A misspelled option is an error that lists the known ones, not silently ignored.
        config.rejectUnknown();
        if (max < 1) {
            throw new IllegalArgumentException("The option max of the sequence source " + name
                    + " must be at least 1, not " + max);
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("The option batchSize of the sequence source " + name
                    + " must be at least 1, not " + batchSize);
        }
        return new SequenceConnector(name, max, batchSize);
    }
}
