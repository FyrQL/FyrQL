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

package net.fyrql.sql.bind;

import net.fyrql.core.function.FunctionRegistry;
import net.fyrql.core.value.ZonedDates;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Set;

/**
 * What every part of the binding of one query shares: the scalar functions,
 * the moment that {@code current_date} and {@code now()} stand for, the
 * time zone of the session, and the types of the parameters.
 *
 * @param functions the scalar functions
 * @param functionNames the names of the scalar functions, in lower case
 * @param now the time of the query
 * @param zone the time zone of the session
 * @param parameters the types of the parameters, which the binding adds to
 */
record BindContext(FunctionRegistry functions, Set<String> functionNames, Instant now, ZoneId zone,
        Settings settings, ParameterTypes parameters) {
    BindContext {
        Objects.requireNonNull(functions, "functions");
        functionNames = Set.copyOf(functionNames);
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(parameters, "parameters");
    }

    /** Returns the value of a setting as {@code SHOW} shows it, by its name as FyrQL spells it. */
    String setting(String name) {
        return name.equals(Settings.TIME_ZONE) ? ZonedDates.name(zone) : settings.value(name);
    }
}
