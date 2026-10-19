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

import net.fyrql.core.expr.Expression;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.exec.spi.CodegenStatistics;
import net.fyrql.exec.spi.CompiledFilter;
import net.fyrql.exec.spi.CompiledPipeline;
import net.fyrql.exec.spi.CompiledProjection;
import net.fyrql.exec.spi.ExpressionCompiler;
import net.fyrql.exec.spi.KeyFunctions;
import net.fyrql.exec.spi.PipelineSpec;

import java.util.Iterator;
import java.util.List;
import java.util.ServiceLoader;

/**
 * The code generation of an executor: a mode, a threshold of rows, and the
 * compiler, if there is one. An operator interprets until
 * {@link #due(long)} tells it to ask for compiled code, which it does once;
 * if the compiler declines or fails, the operator goes on interpreting. A
 * chain of operators that runs as one compiled pipeline is decided once,
 * when the plan is opened, by {@link #fuses(double)}.
 */
public final class Codegen {

    /** The rows an operator processes before it compiles in {@link CodegenMode#AUTO}. */
    public static final long DEFAULT_THRESHOLD = 10_000;

    private static final Codegen OFF = new Codegen(CodegenMode.OFF, DEFAULT_THRESHOLD, null);

    private final CodegenMode mode;
    private final long threshold;
    private final ExpressionCompiler compiler;

    private Codegen(CodegenMode mode, long threshold, ExpressionCompiler compiler) {
        this.mode = mode;
        this.threshold = threshold;
        this.compiler = compiler;
    }

    /** No code generation. */
    public static Codegen off() {
        return OFF;
    }

    /**
     * Returns the code generation of a mode with a compiler, which may be
     * {@code null} unless the mode is {@link CodegenMode#ALWAYS}.
     *
     * @throws IllegalArgumentException for a negative threshold
     * @throws IllegalStateException    for {@code ALWAYS} without a compiler
     */
    public static Codegen of(CodegenMode mode, long threshold, ExpressionCompiler compiler) {
        if (threshold < 0) {
            throw new IllegalArgumentException("codegen threshold: " + threshold);
        }
        if (mode == CodegenMode.ALWAYS && compiler == null) {
            throw new IllegalStateException("Code generation is set to ALWAYS, but no " + ExpressionCompiler.class
                    .getSimpleName() + " is on the class path (module fyrql-codegen)");
        }
        return new Codegen(mode, threshold, mode == CodegenMode.OFF ? null : compiler);
    }

    /**
     * Returns the first compiler that {@link ServiceLoader} finds, or
     * {@code null}; it is looked up once, so that all engines share it and
     * its cache of compiled classes.
     */
    public static ExpressionCompiler loadCompiler() {
        return Loaded.COMPILER;
    }

    /** Holds the compiler, which is looked up when it is first needed. */
    private static final class Loaded {
        static final ExpressionCompiler COMPILER = load();

        private static ExpressionCompiler load() {
            Iterator<ExpressionCompiler> it = ServiceLoader.load(ExpressionCompiler.class, Codegen.class
                    .getClassLoader()).iterator();
            return it.hasNext() ? it.next() : null;
        }
    }

    public CodegenMode mode() {
        return mode;
    }

    public long threshold() {
        return threshold;
    }

    /** Returns the compiler, or {@code null} if code is not generated. */
    public ExpressionCompiler compiler() {
        return compiler;
    }

    /** Tells whether an operator that has processed a number of rows should ask for compiled code now. */
    public boolean due(long rows) {
        return compiler != null && (mode == CodegenMode.ALWAYS || rows >= threshold);
    }

    /**
     * Tells whether a pipeline over a source with an estimated number of
     * rows is compiled when the plan is opened: always in
     * {@link CodegenMode#ALWAYS}, and in {@link CodegenMode#AUTO} from the
     * threshold on, never for an unknown estimate ({@code NaN}).
     */
    public boolean fuses(double estimatedRows) {
        return compiler != null && (mode == CodegenMode.ALWAYS || estimatedRows >= threshold);
    }

    /** Tells whether the compiler would compile a chain, {@code false} if it fails to tell. */
    public boolean compilesPipeline(PipelineSpec spec) {
        try {
            return compiler != null && compiler.supportsPipeline(spec);
        } catch (RuntimeException | LinkageError | StackOverflowError failed) {
            return false;
        }
    }

    /** Tells whether an expression would be compiled, for {@code explain}. */
    public boolean compiles(Expression e) {
        return compiler != null && compiler.supports(e);
    }

    /** Tells whether keys of the types would be compiled, for {@code explain}. */
    public boolean compilesKeys(List<DataType> types) {
        return compiler != null && !types.isEmpty() && compiler.supportsKeys(types);
    }

    /** Returns what the compiler has done, or {@link CodegenStatistics#NONE}. */
    public CodegenStatistics statistics() {
        return compiler == null ? CodegenStatistics.NONE : compiler.statistics();
    }

    /** Compiles an expression, or returns {@code null} if the compiler declines or fails. */
    CompiledProjection projection(Expression e, Schema input) {
        try {
            return compiler == null ? null : compiler.projection(e, input);
        } catch (RuntimeException | LinkageError | StackOverflowError failed) {
            return null;
        }
    }

    /** Compiles a condition, or returns {@code null} if the compiler declines or fails. */
    CompiledFilter filter(Expression condition, Schema input) {
        try {
            return compiler == null ? null : compiler.filter(condition, input);
        } catch (RuntimeException | LinkageError | StackOverflowError failed) {
            return null;
        }
    }

    /** Compiles a chain, or returns {@code null} if the compiler declines or fails. */
    CompiledPipeline pipeline(PipelineSpec spec) {
        try {
            return compiler == null ? null : compiler.pipeline(spec);
        } catch (RuntimeException | LinkageError | StackOverflowError failed) {
            return null;
        }
    }

    /** Compiles key functions, or returns {@code null} if the compiler declines or fails. */
    KeyFunctions keys(List<DataType> types) {
        try {
            return compiler == null || types.isEmpty() ? null : compiler.keys(types);
        } catch (RuntimeException | LinkageError | StackOverflowError failed) {
            return null;
        }
    }
}
