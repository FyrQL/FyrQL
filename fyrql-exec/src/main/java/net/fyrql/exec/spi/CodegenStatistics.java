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

package net.fyrql.exec.spi;

/**
 * What an {@link ExpressionCompiler} has done so far.
 *
 * @param compiled     the classes compiled
 * @param cacheHits    the requests answered from the cache of classes
 * @param declined     the requests declined, for an unsupported expression
 *                     or chain, a failed compilation, or a class with a
 *                     method too long for the JIT
 * @param failures     the compilations that failed
 * @param compileNanos the time spent generating and compiling code
 */
public record CodegenStatistics(long compiled, long cacheHits, long declined, long failures, long compileNanos) {

    /** The statistics of an engine without a compiler. */
    public static final CodegenStatistics NONE = new CodegenStatistics(0, 0, 0, 0, 0);
}
