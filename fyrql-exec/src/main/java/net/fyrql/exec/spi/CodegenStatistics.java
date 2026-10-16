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
