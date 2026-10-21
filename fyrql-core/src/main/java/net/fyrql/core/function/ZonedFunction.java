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

package net.fyrql.core.function;

import net.fyrql.core.type.DataType;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * A function whose result may depend on the time zone of the session: its
 * body gets the zone, UTC until {@link #inZone} fixes another one. Two
 * functions with the same name, body and zone are equal, so that the same
 * call bound twice, e.g. in the select list and in {@code GROUP BY}, is the
 * same expression.
 */
class ZonedFunction extends SimpleFunction {

    /** The computation of a zoned function. */
    interface Body {
        Object apply(List<DataType> types, Object[] args, ZoneId zone);
    }

    private final Function<List<DataType>, DataType> resultType;
    private final UnaryOperator<List<DataType>> coerce;
    private final Body body;
    private final ZoneId zone;

    ZonedFunction(String name, Function<List<DataType>, DataType> resultType, Body body) {
        this(name, resultType, UnaryOperator.identity(), body, ZoneOffset.UTC);
    }

    ZonedFunction(String name, Function<List<DataType>, DataType> resultType, UnaryOperator<List<DataType>> coerce,
            Body body) {
        this(name, resultType, coerce, body, ZoneOffset.UTC);
    }

    private ZonedFunction(String name, Function<List<DataType>, DataType> resultType,
            UnaryOperator<List<DataType>> coerce, Body body, ZoneId zone) {
        super(name, resultType, coerce);
        this.resultType = resultType;
        this.coerce = coerce;
        this.body = body;
        this.zone = zone;
    }

    /** Returns the time zone the function computes in. */
    ZoneId zone() {
        return zone;
    }

    @Override
    public Object evaluate(List<DataType> argumentTypes, Object[] arguments) {
        return body.apply(argumentTypes, arguments, zone);
    }

    @Override
    public ScalarFunction inZone(ZoneId newZone) {
        return newZone.equals(zone) ? this : new ZonedFunction(name(), resultType, coerce, body, newZone);
    }

    @Override
    ScalarFunction alias(String aliasName) {
        return new ZonedFunction(aliasName, resultType, coerce, body, zone);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ZonedFunction f && f.body == body && f.name().equals(name()) && f.zone.equals(zone);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name(), System.identityHashCode(body), zone);
    }
}
