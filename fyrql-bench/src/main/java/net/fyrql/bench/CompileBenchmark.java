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

package net.fyrql.bench;

import net.fyrql.codegen.JaninoCompiler;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.TableRef;
import net.fyrql.exec.spi.PipelineSpec;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The cost of compiling the filter of TPC-H Q6 with Janino: with a fresh
 * compiler, whose cache is empty, and with one whose cache holds the class,
 * which leaves generating the source and creating an instance. The same for
 * the pipeline of {@link CodegenBenchmark#pipeline}: a filter, a projection
 * and a join.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(2)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
public class CompileBenchmark {

    private static final PipelineSpec PIPELINE = pipeline();

    private final JaninoCompiler cached = new JaninoCompiler();

    private static PipelineSpec pipeline() {
        PlanNode.Join join = CodegenBenchmark.revenue(
                PlanNode.Scan.all(TableRef.of("mem", "lineitem"), CodegenBenchmark.LINEITEM),
                PlanNode.Scan.all(TableRef.of("mem", "customer"), CodegenBenchmark.CUSTOMER));
        PlanNode.Project project = (PlanNode.Project) join.left();
        PlanNode.Filter filter = (PlanNode.Filter) project.input();
        return new PipelineSpec(CodegenBenchmark.LINEITEM, List.of(filter, project, join));
    }

    @Benchmark
    public Object cold() {
        return new JaninoCompiler().filter(CodegenBenchmark.q6(), CodegenBenchmark.LINEITEM);
    }

    @Benchmark
    public Object cached() {
        return cached.filter(CodegenBenchmark.q6(), CodegenBenchmark.LINEITEM);
    }

    @Benchmark
    public Object pipelineCold() {
        return new JaninoCompiler().pipeline(PIPELINE);
    }

    @Benchmark
    public Object pipelineCached() {
        return cached.pipeline(PIPELINE);
    }
}
