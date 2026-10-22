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

package net.fyrql.sql.ast;

import java.util.Objects;

/**
 * {@code EXPLAIN statement}: the plan of a statement as rows of text, or
 * with {@code ANALYZE} the plan after the statement ran, with what each
 * operator and scan did.
 *
 * @param analyze whether the statement runs, {@code EXPLAIN ANALYZE}
 * @param statement the statement, which is no {@code EXPLAIN}
 * @param position the position of the word {@code EXPLAIN}
 */
public record Explain(boolean analyze, Statement statement, Position position) implements Statement {

    public Explain {
        Objects.requireNonNull(statement, "statement");
        Objects.requireNonNull(position, "position");
        if (statement instanceof Explain) {
            throw new IllegalArgumentException("EXPLAIN of EXPLAIN");
        }
    }
}
