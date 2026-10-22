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

package net.fyrql.exec;

import jdk.jfr.Category;
import jdk.jfr.DataAmount;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * The event of Java Flight Recorder of a part that an operator spilled to
 * disk, see {@link SpillFile}: from its first batch until it is finished.
 * Only touched where the module {@code jdk.jfr} is there.
 */
@Name("net.fyrql.Spill")
@Label("Spill")
@Category("FyrQL")
@Description("A part an operator spilled to disk, from its first batch until it was finished")
@StackTrace(false)
final class SpillEvent extends Event {

    @Label("Operator")
    @Description("The label of the operator, such as Sort#1")
    String operator;

    @Label("What")
    @Description("What the part holds, such as run or partition")
    String what;

    @Label("Rows")
    long rows;

    @Label("Batches")
    long batches;

    @Label("Bytes")
    @DataAmount
    long bytes;

    /** Starts an event, or returns {@code null} if none is recorded. */
    static SpillEvent start() {
        SpillEvent event = new SpillEvent();
        if (!event.isEnabled()) {
            return null;
        }
        event.begin();
        return event;
    }

    /** Ends the event and commits it, if its duration is recorded. */
    void done(String operator, String what, long rows, long batches, long bytes) {
        end();
        if (shouldCommit()) {
            this.operator = operator;
            this.what = what;
            this.rows = rows;
            this.batches = batches;
            this.bytes = bytes;
            commit();
        }
    }
}
