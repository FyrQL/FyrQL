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

package net.fyrql.spring;

import net.fyrql.connector.BatchReader;
import net.fyrql.connector.Catalog;
import net.fyrql.connector.Connector;
import net.fyrql.connector.ConnectorFactory;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.connector.Split;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.Schema;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The connectors of the type {@code probe}, which have no tables, keep
 * their options and count how many are open; with the option
 * {@code failing}, their catalog fails.
 */
public final class ProbeConnectorFactory implements ConnectorFactory {

    /** The options of the connectors by their names. */
    private static final Map<String, Map<String, String>> OPTIONS = new ConcurrentHashMap<>();

    /** The names of the connectors that are open. */
    private static final Map<String, Boolean> OPEN = new ConcurrentHashMap<>();

    /** Forgets the connectors of earlier tests. */
    static void reset() {
        OPTIONS.clear();
        OPEN.clear();
    }

    /** Returns the options of the connector of a name. */
    static Map<String, String> options(String name) {
        return OPTIONS.get(name);
    }

    /** Returns the names of the connectors that are open. */
    static List<String> open() {
        return OPEN.keySet().stream().sorted().toList();
    }

    @Override
    public String type() {
        return "probe";
    }

    @Override
    public Connector create(String name, Map<String, String> options, Path directory) {
        OPTIONS.put(name, Map.copyOf(options));
        OPEN.put(name, true);
        boolean failing = Boolean.parseBoolean(options.get("failing"));
        Catalog catalog = new Catalog() {
            @Override
            public List<String> schemas() {
                if (failing) {
                    throw new IllegalStateException("The probe " + name + " is down");
                }
                return List.of("main");
            }

            @Override
            public List<String> tables(String schema) {
                return List.of();
            }

            @Override
            public Schema schema(TableRef table) {
                throw new IllegalArgumentException("No table " + table);
            }
        };
        return new Connector() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Catalog catalog() {
                return catalog;
            }

            @Override
            public ScanPlan plan(TableRef table, ScanRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<Split> splits(ScanPlan plan) {
                throw new UnsupportedOperationException();
            }

            @Override
            public BatchReader open(Split split) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void close() {
                OPEN.remove(name);
            }
        };
    }
}
