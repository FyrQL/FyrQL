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

package net.fyrql.jdbc;

import java.sql.Date;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.TimeZone;

/**
 * The milliseconds of {@code java.sql.Date.valueOf} for the days a result
 * set read last, so that reading a {@code DATE} creates only the
 * {@code Date}: the constructor behind {@code Date.valueOf} works out the
 * day in the calendar and the default time zone on every call. A day is
 * kept in one of {@value #SLOTS} slots by its number. The cache starts over
 * when the default time zone has other rules than at the last
 * {@link #check}, which the result set calls once per batch.
 */
final class DateCache {

    private static final int SLOTS = 1024;

    /** The key of an empty slot; a day is an {@code int}, so no day is this. */
    private static final long EMPTY = Long.MIN_VALUE;

    private final long[] days = new long[SLOTS];
    private final long[] millis = new long[SLOTS];
    private TimeZone zone;

    DateCache() {
        check();
    }

    /** Starts over if the rules of the default time zone changed since the last check. */
    void check() {
        TimeZone now = TimeZone.getDefault();
        if (zone == null || !zone.hasSameRules(now)) {
            Arrays.fill(days, EMPTY);
        }
        zone = now;
    }

    /** Returns a new {@code Date} equal to {@code Date.valueOf(LocalDate.ofEpochDay(day))}. */
    Date date(int day) {
        int slot = day & (SLOTS - 1);
        if (days[slot] != day) {
            millis[slot] = Date.valueOf(LocalDate.ofEpochDay(day)).getTime();
            days[slot] = day;
        }
        return new Date(millis[slot]);
    }
}
