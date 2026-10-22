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

package net.fyrql;

import net.fyrql.core.plan.PlanNode;
import net.fyrql.exec.QueryContext;
import jdk.jfr.Category;
import jdk.jfr.DataAmount;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * The event of Java Flight Recorder of a query or a statement that writes:
 * from the start of its run until its result is closed, or until it has
 * written. Only touched where the module {@code jdk.jfr} is there.
 */
@Name("net.fyrql.Query")
@Label("Query")
@Category("FyrQL")
@Description("A query or a statement that writes, from the start of its run until its result was closed")
@StackTrace(false)
final class QueryEvent extends Event {

    /** The most characters of the text of a statement in an event. */
    private static final int MAX_TEXT = 1000;

    @Label("SQL")
    @Description("The text of the statement, at most 1000 characters, or the first line of its plan")
    String sql;

    @Label("Rows")
    @Description("The rows of the result that were read, or the rows written")
    long rows;

    @Label("Outcome")
    @Description("ok, failed, cancelled or timeout")
    String outcome;

    @Label("Error")
    @Description("The message of the failure, if it failed")
    String error;

    @Label("Spilled")
    @Description("The bytes the operators of the query spilled to disk")
    @DataAmount
    long spilledBytes;

    /** Starts an event, or returns {@code null} if none is recorded. */
    static QueryEvent start() {
        QueryEvent event = new QueryEvent();
        if (!event.isEnabled()) {
            return null;
        }
        event.begin();
        return event;
    }

    /**
     * Ends the event and commits it, if its duration is recorded.
     *
     * @param text    the text of the statement, or {@code null} for the first line of the plan it ran
     * @param failure the failure of the query, or {@code null}
     */
    void done(String text, QueryContext context, long rows, Throwable failure) {
        end();
        if (shouldCommit()) {
            PlanNode plan = text == null ? context.statistics().plan() : null;
            String shown = text != null ? text : plan == null ? "" : Explain.line(plan);
            this.sql = shown.length() <= MAX_TEXT ? shown : shown.substring(0, MAX_TEXT);
            this.rows = rows;
            QueryContext.Reason reason = context.reason();
            this.outcome = reason == QueryContext.Reason.TIMEOUT ? "timeout"
                    : reason == QueryContext.Reason.CANCEL ? "cancelled" : failure != null ? "failed" : "ok";
            this.error = failure == null ? null
                    : failure.getMessage() == null ? failure.getClass().getName() : failure.getMessage();
            this.spilledBytes = context.spill().bytesWritten();
            commit();
        }
    }
}
