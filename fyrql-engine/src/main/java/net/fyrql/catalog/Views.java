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

package net.fyrql.catalog;

import net.fyrql.core.plan.TableRef;
import net.fyrql.sql.bind.ViewDefinition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The views of an engine, which all its sessions share, in the order of
 * their creation: each in a schema of a source, as a table is, which it
 * hides if the source has a table of the same name there. The engine keeps
 * them until it closes; a source knows nothing of them.
 * <p>
 * Reads see a snapshot and never wait; changes are atomic, and a caller
 * that checks and then changes holds the lock of this object meanwhile.
 * <p>
 * Internal; not covered by the compatibility promise.
 */
public final class Views {

    private volatile Map<TableRef, ViewDefinition> views = Map.of();

    /** Returns the view of a table name, or nothing if there is none. */
    public Optional<ViewDefinition> get(TableRef table) {
        return Optional.ofNullable(views.get(table));
    }

    /** Returns all views in the order of their creation. */
    public Map<TableRef, ViewDefinition> all() {
        return views;
    }

    /** Tells whether there are no views. */
    public boolean isEmpty() {
        return views.isEmpty();
    }

    /** Returns the names of the views in a schema of a source, as the names are written exactly. */
    public List<String> names(String source, String schema) {
        Map<TableRef, ViewDefinition> all = views;
        if (all.isEmpty()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (TableRef view : all.keySet()) {
            if (view.source().equals(source) && view.schema().equals(schema)) {
                names.add(view.table());
            }
        }
        return names;
    }

    /** Adds a view, or replaces the one of the name in its place. */
    public synchronized void put(TableRef table, ViewDefinition view) {
        Map<TableRef, ViewDefinition> next = new LinkedHashMap<>(views);
        next.put(table, view);
        views = Collections.unmodifiableMap(next);
    }

    /** Removes a view; returns whether there was one of the name. */
    public synchronized boolean remove(TableRef table) {
        if (!views.containsKey(table)) {
            return false;
        }
        Map<TableRef, ViewDefinition> next = new LinkedHashMap<>(views);
        next.remove(table);
        views = Collections.unmodifiableMap(next);
        return true;
    }
}
