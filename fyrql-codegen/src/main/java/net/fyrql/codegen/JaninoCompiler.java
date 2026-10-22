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

import net.fyrql.core.expr.Expression;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.exec.CodegenStatistics;
import net.fyrql.exec.compiled.CompiledFilter;
import net.fyrql.exec.compiled.CompiledPipeline;
import net.fyrql.exec.compiled.CompiledProjection;
import net.fyrql.exec.compiled.ExpressionCompiler;
import net.fyrql.exec.compiled.KeyFunctions;
import net.fyrql.exec.compiled.PipelineSpec;
import org.codehaus.commons.compiler.CompileException;
import org.codehaus.janino.ClassBodyEvaluator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Compiles expressions, filters, keys and pipelines with Janino: the
 * generators write the body of a class, which {@link ClassBodyEvaluator}
 * compiles into a class of its own class loader. Classes are cached by their
 * source, which does not contain the values of literals, so that the same
 * expression with other constants reuses the class; a source that failed to
 * compile is remembered as well and declined from then on. So is a class
 * with a method of more than {@value #HUGE_METHOD} bytes of bytecode, which
 * HotSpot would never compile to machine code; the interpreter of the engine
 * is faster than such a method.
 * <p>
 * The system property {@value #DUMP_PROPERTY} names a directory into which
 * every compiled source is written, for reading what the engine runs.
 */
public final class JaninoCompiler implements ExpressionCompiler {

    /** The largest expression, in nodes, that is compiled. */
    public static final int MAX_NODES = 400;

    /** The largest pipeline, in nodes of the expressions of all its stages, that is compiled. */
    public static final int MAX_PIPELINE_NODES = 4000;

    /** The longest code of a method, in bytes, that HotSpot compiles ({@code -XX:HugeMethodLimit}). */
    public static final int HUGE_METHOD = 8000;

    /** The system property with a directory for the compiled sources. */
    public static final String DUMP_PROPERTY = "fyrql.codegen.dump";

    /** The number of classes kept in the cache. */
    static final int CACHE_SIZE = 512;

    private static final String[] IMPORTS = {"net.fyrql.core.vector.*", "net.fyrql.core.type.DataType",
        "net.fyrql.core.expr.Expression", "net.fyrql.core.expr.ScalarOps", "net.fyrql.core.value.Casts",
        "net.fyrql.core.value.ConversionException", "net.fyrql.core.value.Order",
        "net.fyrql.core.function.ScalarFunction", "net.fyrql.core.function.Strings", "java.math.BigDecimal",
        "net.fyrql.codegen.GeneratedSupport", "net.fyrql.core.type.Schema", "net.fyrql.exec.compiled.JoinTable"};

    /** The cached value of a source that does not compile, or whose class has a method too long for the JIT. */
    private static final Object FAILED = new Object();
    /** Whether the module {@code jdk.jfr} is there, as it may not be in a runtime made with jlink. */
    private static final boolean RECORDABLE = ModuleLayer.boot().findModule("jdk.jfr").isPresent();

    private final Map<String, Object> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Object> eldest) {
            return size() > CACHE_SIZE;
        }
    };
    private final AtomicLong compiled = new AtomicLong();
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong declined = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong compileNanos = new AtomicLong();

    @Override
    public String name() {
        return "janino";
    }

    @Override
    public boolean supports(Expression expression) {
        return ExpressionCodegen.supports(expression, MAX_NODES);
    }

    @Override
    public boolean supportsKeys(List<DataType> types) {
        return KeyCodegen.supports(types);
    }

    @Override
    public CompiledProjection projection(Expression expression, Schema input) {
        if (!supports(expression)) {
            declined.incrementAndGet();
            return null;
        }
        long start = System.nanoTime();
        return (CompiledProjection) instantiate(ExpressionCodegen.projection(expression), CompiledProjection.class,
                start);
    }

    @Override
    public CompiledFilter filter(Expression condition, Schema input) {
        if (!supports(condition)) {
            declined.incrementAndGet();
            return null;
        }
        long start = System.nanoTime();
        return (CompiledFilter) instantiate(ExpressionCodegen.filter(condition), CompiledFilter.class, start);
    }

    @Override
    public KeyFunctions keys(List<DataType> types) {
        if (!supportsKeys(types)) {
            declined.incrementAndGet();
            return null;
        }
        long start = System.nanoTime();
        return (KeyFunctions) instantiate(KeyCodegen.keys(types), KeyFunctions.class, start);
    }

    @Override
    public boolean supportsPipeline(PipelineSpec spec) {
        return PipelineCodegen.supports(spec);
    }

    @Override
    public CompiledPipeline pipeline(PipelineSpec spec) {
        if (!supportsPipeline(spec)) {
            declined.incrementAndGet();
            return null;
        }
        long start = System.nanoTime();
        return (CompiledPipeline) instantiate(PipelineCodegen.pipeline(spec), CompiledPipeline.class, start);
    }

    @Override
    public CodegenStatistics statistics() {
        return new CodegenStatistics(compiled.get(), cacheHits.get(), declined.get(), failures.get(),
                compileNanos.get());
    }

    /** Creates an instance of the class of a unit, compiling it unless it is cached; {@code null} if it fails. */
    private Object instantiate(ExpressionCodegen.Unit unit, Class<?> type, long start) {
        String key = unit.className() + "\n" + unit.source();
        Object cached;
        synchronized (cache) {
            cached = cache.get(key);
            if (cached == null) {
                cached = compile(unit, type);
                cache.put(key, cached);
                compileNanos.addAndGet(System.nanoTime() - start);
            } else if (cached != FAILED) {
                cacheHits.incrementAndGet();
            }
        }
        if (cached == FAILED) {
            declined.incrementAndGet();
            return null;
        }
        try {
            return ((Class<?>) cached).getConstructor(Object[].class).newInstance((Object) unit.references());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot create the generated " + unit.className(), e);
        }
    }

    /**
     * Compiles a unit; returns its class, or {@link #FAILED}. While Java
     * Flight Recorder records, this is an event {@code net.fyrql.Compile}.
     */
    private Object compile(ExpressionCodegen.Unit unit, Class<?> type) {
        dump(unit);
        CompileEvent event = RECORDABLE ? CompileEvent.start() : null;
        String outcome = "failed";
        try {
            ClassBodyEvaluator evaluator = new ClassBodyEvaluator();
            evaluator.setParentClassLoader(JaninoCompiler.class.getClassLoader());
            evaluator.setClassName("net.fyrql.generated." + unit.className());
            evaluator.setDefaultImports(IMPORTS);
            evaluator.setImplementedInterfaces(new Class<?>[] {type});
            try {
                evaluator.cook(unit.source());
                for (byte[] classFile : evaluator.getBytecodes().values()) {
                    if (ClassFiles.maxCodeLength(classFile) > HUGE_METHOD) {
                        outcome = "tooLarge";
                        return FAILED;
                    }
                }
                compiled.incrementAndGet();
                outcome = "compiled";
                return evaluator.getClazz();
            } catch (CompileException | RuntimeException | LinkageError | StackOverflowError e) {
                // Too big for the JVM, or a gap of the generator: the operator keeps interpreting.
                failures.incrementAndGet();
                return FAILED;
            }
        } finally {
            if (event != null) {
                event.done(type.getSimpleName(), unit.className(), unit.source().length(), outcome);
            }
        }
    }

    private static void dump(ExpressionCodegen.Unit unit) {
        String dir = System.getProperty(DUMP_PROPERTY);
        if (dir == null || dir.isBlank()) {
            return;
        }
        try {
            Path path = Path.of(dir);
            Files.createDirectories(path);
            String name = unit.className() + "-" + Integer.toHexString(unit.source().hashCode()) + ".java";
            Files.writeString(path.resolve(name), unit.source(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write the generated source to " + dir, e);
        }
    }
}
