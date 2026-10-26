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

package net.fyrql.test;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The JARs run on the module path as automatic modules, under the names their manifests give them: the engine with
 * its connectors and the JDBC driver, each found through the {@link ServiceLoader} in a layer of modules of its own,
 * and each JAR that carries everything as a single module. No two modules share a package, or the layer would not
 * resolve. Since automatic modules name no modules they need, the layer holds all modules of the path, as an
 * application gets them with {@code --add-modules ALL-MODULE-PATH}.
 */
class ModulePathTest {

    /** The modules of the build that the module path holds, by the names of their modules. */
    private static final Map<String, String> MODULES = Map.ofEntries(
            Map.entry("fyrql-core", "net.fyrql.core"),
            Map.entry("fyrql-connector-spi", "net.fyrql.connector"),
            Map.entry("fyrql-exec", "net.fyrql.exec"),
            Map.entry("fyrql-codegen", "net.fyrql.codegen"),
            Map.entry("fyrql-sql", "net.fyrql.sql"),
            Map.entry("fyrql-optimizer", "net.fyrql.optimizer"),
            Map.entry("fyrql-engine", "net.fyrql"),
            Map.entry("fyrql-config", "net.fyrql.config"),
            Map.entry("fyrql-connector-memory", "net.fyrql.connector.memory"),
            Map.entry("fyrql-connector-csv", "net.fyrql.connector.csv"),
            Map.entry("fyrql-connector-json", "net.fyrql.connector.json"),
            Map.entry("fyrql-connector-parquet", "net.fyrql.connector.parquet"),
            Map.entry("fyrql-connector-http", "net.fyrql.connector.http"),
            Map.entry("fyrql-connector-odata", "net.fyrql.connector.odata"),
            Map.entry("fyrql-connector-jdbc", "net.fyrql.connector.jdbc"),
            Map.entry("fyrql-connector-s3", "net.fyrql.connector.s3"),
            Map.entry("fyrql-connector-iceberg", "net.fyrql.connector.iceberg"),
            Map.entry("fyrql-connector-xlsx", "net.fyrql.connector.xlsx"),
            Map.entry("fyrql-jdbc", "net.fyrql.jdbc"));

    @TempDir
    Path dir;

    @Test
    void theEngineAndTheDriverRunAsModules() throws Exception {
        List<Path> path = modulePath();
        ModuleFinder finder = ModuleFinder.of(path.toArray(Path[]::new));
        Map<String, String> found = new TreeMap<>();
        for (ModuleReference m : finder.findAll()) {
            Path jar = Path.of(m.location().orElseThrow());
            String artifact = artifact(jar);
            if (artifact != null) {
                found.put(artifact, m.descriptor().name());
                assertTrue(m.descriptor().isAutomatic(), artifact);
                assertEquals(m.descriptor().name(), automaticModuleName(jar), artifact);
            }
        }
        assertEquals(new TreeMap<>(MODULES), found);
        // An automatic module names no modules it needs. The libraries that are named modules are resolved only
        // when added, as with --add-modules ALL-MODULE-PATH, which the manual tells applications to give.
        assertEquals(Set.of("com.fasterxml.jackson.core", "de.siegmar.fastcsv", "net.sf.jsqlparser"),
                finder.findAll().stream().map(ModuleReference::descriptor).filter(d -> !d.isAutomatic())
                        .map(ModuleDescriptor::name).collect(Collectors.toSet()));

        ModuleLayer layer = layer(finder, finder.findAll().stream().map(m -> m.descriptor().name())
                .collect(Collectors.toSet()));
        ClassLoader loader = layer.findLoader("net.fyrql");
        assertEquals(Set.copyOf(MODULES.values()), layer.modules().stream().map(Module::getName)
                .filter(MODULES::containsValue).collect(Collectors.toSet()));

        Path parquet = dir.resolve("numbers.parquet");
        Path csv = dir.resolve("numbers.csv");
        Files.writeString(csv, "n\n1\n2\n3\n", StandardCharsets.UTF_8);
        withLoader(loader, () -> {
            Class<?> fyrql = loader.loadClass("net.fyrql.FyrQL");
            assertEquals("net.fyrql", fyrql.getModule().getName());
            Object builder = fyrql.getMethod("builder").invoke(null);
            try (AutoCloseable engine = (AutoCloseable) builder.getClass().getMethod("build").invoke(builder)) {
                Method rows = fyrql.getMethod("rows", String.class);
                assertEquals("[[3]]", text(rows.invoke(engine, "SELECT x + 1 FROM (VALUES (2)) v(x)")));
                assertEquals("[[3, 6]]", text(rows.invoke(engine, "SELECT count(*), sum(n) FROM read_csv('"
                        + sql(csv) + "')")));
                assertEquals(3L, fyrql.getMethod("update", String.class).invoke(engine, "COPY (SELECT n * 10 AS n"
                        + " FROM read_csv('" + sql(csv) + "')) TO '" + sql(parquet) + "'"));
                assertEquals("[[60]]", text(rows.invoke(engine, "SELECT sum(n) FROM '" + sql(parquet) + "'")));
            }
            Object compiler = loader.loadClass("net.fyrql.exec.Codegen").getMethod("loadCompiler").invoke(null);
            assertEquals("net.fyrql.codegen", compiler.getClass().getModule().getName());
            return null;
        });
        withLoader(loader, () -> {
            Driver driver = ServiceLoader.load(layer, Driver.class).stream().map(ServiceLoader.Provider::get)
                    .filter(d -> d.getClass().getModule().getLayer() == layer).findFirst().orElseThrow();
            assertEquals("net.fyrql.jdbc", driver.getClass().getModule().getName());
            try (Connection c = driver.connect("jdbc:fyrql:", new Properties());
                    Statement s = c.createStatement();
                    ResultSet r = s.executeQuery("SELECT sum(n) FROM '" + sql(parquet) + "'")) {
                assertTrue(r.next());
                assertEquals(60L, r.getLong(1));
            }
            return null;
        });
    }

    @Test
    void eachJarWithEverythingIsOneModule() throws Exception {
        Map<String, String> jars = Map.of(
                "fyrql.jdbc.all.jar", "net.fyrql.jdbc.all",
                "fyrql.flight.all.jar", "net.fyrql.flight.all",
                "fyrql.pgwire.all.jar", "net.fyrql.pgwire.all");
        for (Map.Entry<String, String> e : jars.entrySet()) {
            String property = System.getProperty(e.getKey());
            assumeTrue(property != null && Files.isRegularFile(Path.of(property)), "no JAR in " + property);
            Path jar = Path.of(property);
            ModuleFinder finder = ModuleFinder.of(jar);
            ModuleReference m = finder.findAll().stream().findFirst().orElseThrow();
            assertEquals(e.getValue(), m.descriptor().name(), jar.toString());
            ModuleLayer layer = layer(finder, Set.of(e.getValue()));
            ClassLoader loader = layer.findLoader(e.getValue());
            withLoader(loader, () -> {
                Class<?> fyrql = loader.loadClass("net.fyrql.FyrQL");
                assertEquals(e.getValue(), fyrql.getModule().getName());
                Object builder = fyrql.getMethod("builder").invoke(null);
                try (AutoCloseable engine = (AutoCloseable) builder.getClass().getMethod("build").invoke(builder)) {
                    assertEquals("[[3]]", text(fyrql.getMethod("rows", String.class).invoke(engine,
                            "SELECT x + 1 FROM (VALUES (2)) v(x)")));
                }
                return null;
            });
            if (e.getValue().equals("net.fyrql.jdbc.all")) {
                withLoader(loader, () -> {
                    Driver driver = ServiceLoader.load(layer, Driver.class).stream()
                            .map(ServiceLoader.Provider::get)
                            .filter(d -> d.getClass().getModule().getLayer() == layer).findFirst().orElseThrow();
                    try (Connection c = driver.connect("jdbc:fyrql:", new Properties());
                            Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT 42")) {
                        assertTrue(r.next());
                        assertEquals(42, r.getInt(1));
                    }
                    return null;
                });
            }
        }
    }

    /**
     * The JARs the build lists for this module's runtime: the modules of the engine, the connectors and the JDBC
     * driver, and the libraries they need.
     */
    private static List<Path> modulePath() throws IOException {
        Path file = Path.of("target/module-path.txt");
        assumeTrue(Files.isRegularFile(file), "the build wrote no " + file);
        List<Path> path = new ArrayList<>();
        for (String entry : Files.readString(file, StandardCharsets.UTF_8).strip().split(File.pathSeparator)) {
            Path p = Path.of(entry);
            // A build that has not packed the modules lists their classes; those make no automatic modules.
            assumeTrue(Files.isRegularFile(p), "not a JAR: " + p);
            path.add(p);
        }
        return path;
    }

    /** A layer over the boot layer with the given modules and all they need, in one class loader. */
    private static ModuleLayer layer(ModuleFinder finder, Set<String> roots) {
        ModuleLayer boot = ModuleLayer.boot();
        Configuration cf = boot.configuration().resolve(finder, ModuleFinder.of(), roots);
        return boot.defineModulesWithOneLoader(cf, ClassLoader.getPlatformClassLoader());
    }

    /** Runs code with a class loader as the one of the current thread, as an application on the module path. */
    private static void withLoader(ClassLoader loader, Callable<Void> code) throws Exception {
        Thread t = Thread.currentThread();
        ClassLoader old = t.getContextClassLoader();
        t.setContextClassLoader(loader);
        try {
            code.call();
        } finally {
            t.setContextClassLoader(old);
        }
    }

    /** The module of the build that packed a JAR, such as fyrql-core for fyrql-core-1.0.0.jar, or null. */
    private static String artifact(Path jar) {
        String name = jar.getFileName().toString();
        return MODULES.keySet().stream().filter(a -> name.matches(Pattern.quote(a) + "-\\d.*\\.jar")).findFirst()
                .orElse(null);
    }

    private static String automaticModuleName(Path jar) throws IOException {
        try (JarFile f = new JarFile(jar.toFile())) {
            return f.getManifest().getMainAttributes().getValue("Automatic-Module-Name");
        }
    }

    private static String sql(Path p) {
        return p.toString().replace('\\', '/').replace("'", "''");
    }

    @SuppressWarnings("unchecked")
    private static String text(Object rows) {
        return ((List<Object[]>) rows).stream().map(Arrays::toString).toList().toString();
    }
}
