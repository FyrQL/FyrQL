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

package net.fyrql.exec.compiled;

import net.fyrql.core.expr.Expression;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.exec.CodegenStatistics;

import java.util.List;

/**
 * Compiles expressions and key functions into code that computes the same
 * results as the interpreter, with the same errors. The executor finds an
 * implementation through {@link java.util.ServiceLoader}; without one,
 * everything runs in the interpreter.
 * <p>
 * Every method may decline with {@code null}, for example for a type it
 * does not handle or after a failed compilation; the executor then keeps
 * interpreting. An implementation is used by many queries at once and must
 * be thread-safe.
 */
public interface ExpressionCompiler {

    /** Returns a short name for messages, e.g. {@code janino}. */
    String name();

    /** Compiles an expression over the columns of an input, or returns {@code null}. */
    CompiledProjection projection(Expression expression, Schema input);

    /**
     * Compiles a condition into a selection with the semantics of
     * {@link net.fyrql.core.expr.Evaluator#select}, or returns {@code null}.
     */
    CompiledFilter filter(Expression condition, Schema input);

    /** Compiles the hash and equality of keys of the types, or returns {@code null}. */
    KeyFunctions keys(List<DataType> types);

    /** Tells whether {@link #projection} and {@link #filter} would compile an expression, for {@code explain}. */
    boolean supports(Expression expression);

    /** Tells whether {@link #keys} would compile keys of the types, for {@code explain}. */
    boolean supportsKeys(List<DataType> types);

    /**
     * Compiles a chain of stages into one loop, or returns {@code null}; by
     * default, chains are not compiled, and their nodes run as operators.
     */
    default CompiledPipeline pipeline(PipelineSpec spec) {
        return null;
    }

    /** Tells whether {@link #pipeline} would compile a chain; the executor fuses only such chains. */
    default boolean supportsPipeline(PipelineSpec spec) {
        return false;
    }

    /** Returns what the compiler has done so far. */
    CodegenStatistics statistics();
}
