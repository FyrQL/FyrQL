/*
 * Copyright 2026 the FyrQL authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fyrql.exec.spi;

import net.fyrql.core.expr.Expression;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;

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
