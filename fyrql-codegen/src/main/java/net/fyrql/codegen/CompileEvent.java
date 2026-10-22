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

package net.fyrql.codegen;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * The event of Java Flight Recorder of a class that {@link JaninoCompiler}
 * compiles, not one it takes from its cache. Only touched where the module
 * {@code jdk.jfr} is there.
 */
@Name("net.fyrql.Compile")
@Label("Compile")
@Category("FyrQL")
@Description("A class of generated code that Janino compiled")
@StackTrace(false)
final class CompileEvent extends Event {

    @Label("Kind")
    @Description("What the class is, such as CompiledFilter or CompiledPipeline")
    String kind;

    @Label("Class Name")
    String className;

    @Label("Source Length")
    @Description("The characters of the source of the class")
    long sourceLength;

    @Label("Outcome")
    @Description("compiled, tooLarge if a method is too large for the JVM to compile, or failed")
    String outcome;

    /** Starts an event, or returns {@code null} if none is recorded. */
    static CompileEvent start() {
        CompileEvent event = new CompileEvent();
        if (!event.isEnabled()) {
            return null;
        }
        event.begin();
        return event;
    }

    /** Ends the event and commits it, if its duration is recorded. */
    void done(String kind, String className, long sourceLength, String outcome) {
        end();
        if (shouldCommit()) {
            this.kind = kind;
            this.className = className;
            this.sourceLength = sourceLength;
            this.outcome = outcome;
            commit();
        }
    }
}
