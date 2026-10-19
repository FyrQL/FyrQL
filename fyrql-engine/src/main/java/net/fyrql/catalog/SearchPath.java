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

import java.util.Objects;

/**
 * Where names of fewer than three parts are looked up first: a source and a
 * schema in it. Without a default source, such names are looked up in all
 * sources and must be unique.
 *
 * @param defaultSource the default source, or {@code null} for none
 * @param defaultSchema the default schema, {@code main} unless set
 */
public record SearchPath(String defaultSource, String defaultSchema) {

    public SearchPath {
        Objects.requireNonNull(defaultSchema, "defaultSchema");
    }

    /** A search path without a default source and with the schema {@code main}. */
    public static SearchPath none() {
        return new SearchPath(null, TableRef.MAIN);
    }
}
