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

package net.fyrql.connector;

import jdk.jfr.Category;
import jdk.jfr.DataAmount;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * The event of Java Flight Recorder of a {@link SourceRequest}; only
 * touched where the module {@code jdk.jfr} is there.
 */
@Name("net.fyrql.SourceRequest")
@Label("Source Request")
@Category("FyrQL")
@Description("A request of a connector to a remote store or service, from sending it until its answer came")
@StackTrace(false)
final class SourceRequestEvent extends Event {

    @Label("Source")
    @Description("The name of the source")
    String source;

    @Label("Kind")
    @Description("What was asked, such as GET or query")
    String kind;

    @Label("Target")
    @Description("What was asked for, such as a URL or a statement, at most 1000 characters")
    String target;

    @Label("Status")
    @Description("The status of the answer, 0 if it has none")
    int status;

    @Label("Bytes")
    @Description("The bytes of the answer as it announces them, 0 if not known")
    @DataAmount
    long bytes;

    /** Starts an event, or returns {@code null} if none is recorded. */
    static SourceRequestEvent start() {
        SourceRequestEvent event = new SourceRequestEvent();
        if (!event.isEnabled()) {
            return null;
        }
        event.begin();
        return event;
    }

    /** Ends the event and commits it, if its duration is recorded. */
    void done(String source, String kind, String target, int status, long bytes) {
        end();
        if (shouldCommit()) {
            this.source = source;
            this.kind = kind;
            this.target = target == null || target.length() <= 1000 ? target : target.substring(0, 1000);
            this.status = status;
            this.bytes = bytes;
            commit();
        }
    }
}
