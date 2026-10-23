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

package net.fyrql.spring;

import net.fyrql.FyrQL;
import net.fyrql.connector.FileAccess;
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.exec.CodegenMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.ClassUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The engine of the starter from the keys under {@code fyrql}, as YAML of
 * an application or as properties.
 */
class FyrQLAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FyrQLAutoConfiguration.class));

    @TempDir
    Path dir;

    /** A product of a table in memory. */
    record Product(String sku, double price) {
    }

    @BeforeEach
    void reset() {
        ProbeConnectorFactory.reset();
    }

    /** Adds YAML to the context as an application.yml would. */
    private static ApplicationContextInitializer<ConfigurableApplicationContext> yaml(String text) {
        return context -> {
            try {
                List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("application.yml",
                        new ByteArrayResource(text.getBytes(StandardCharsets.UTF_8)));
                sources.forEach(s -> context.getEnvironment().getPropertySources().addFirst(s));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    private static String path(Path p) {
        return p.toAbsolutePath().toString().replace('\\', '/');
    }

    @Test
    void withoutKeysThereIsAnEngineThatAnswers() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(FyrQL.class);
            assertThat(context.getBean(FyrQL.class).rows("SELECT 1 + 1").get(0)[0]).isEqualTo(2);
        });
    }

    /**
     * Spring Framework 7 converts YAML with Jackson 2 when it finds the YAMLFactory of Jackson 2, which then needs
     * jackson-databind; the configuration keeps its parser of YAML under a name of its own, so that a web
     * application without jackson-databind starts.
     */
    @Test
    void theParserOfTheConfigurationFileIsNotThatOfJackson() {
        ClassLoader loader = getClass().getClassLoader();
        assertThat(ClassUtils.isPresent("com.fasterxml.jackson.dataformat.yaml.YAMLFactory", loader)).isFalse();
        assertThat(ClassUtils.isPresent("net.fyrql.internal.config.jackson.dataformat.yaml.YAMLFactory", loader))
                .isTrue();
    }

    @Test
    void theSourcesOfTheYamlAreThoseOfAConfigurationFile() throws IOException {
        Files.writeString(dir.resolve("orders.csv"), "id,qty\n1,3\n2,5\n", StandardCharsets.UTF_8);
        runner.withInitializer(yaml("""
                fyrql:
                  sources:
                    - name: files
                      type: csv
                      tables:
                        orders:
                          file: %s
                    - name: probe
                      type: probe
                      recordStatements: true
                      columns: [a, b]
                      headers:
                        Accept: text/csv
                        X-Api-Key: secret
                """.formatted(path(dir.resolve("orders.csv"))))).run(context -> {
                    FyrQL engine = context.getBean(FyrQL.class);
                    assertThat(((Number) engine.rows("SELECT sum(qty) FROM files.orders").get(0)[0]).longValue())
                            .isEqualTo(8L);
                    assertThat(ProbeConnectorFactory.options("probe")).isEqualTo(Map.of("recordStatements", "true",
                            "columns", "a,b", "headers.Accept", "text/csv", "headers.X-Api-Key", "secret"));
                });
        runner.withPropertyValues("fyrql.sources[0].name=probe", "fyrql.sources[0].type=probe",
                "fyrql.sources[0].columns[0]=a", "fyrql.sources[0].columns[1]=b").run(context -> assertThat(
                        ProbeConnectorFactory.options("probe")).isEqualTo(Map.of("columns", "a,b")));
    }

    @Test
    void everySettingOfTheEngineReachesIt() throws IOException {
        Path spill = Files.createDirectories(dir.resolve("spill"));
        Path data = Files.createDirectories(dir.resolve("data"));
        runner.withPropertyValues("fyrql.sources[0].name=p", "fyrql.sources[0].type=probe",
                "fyrql.engine.default-source=p", "fyrql.engine.defaultSchema=main", "fyrql.engine.batch-size=1000",
                "fyrql.engine.memory-budget=64MB", "fyrql.engine.spill=true",
                "fyrql.engine.spill-directory=" + path(spill), "fyrql.engine.codegen=off",
                "fyrql.engine.codegen-threshold=5000", "fyrql.engine.pushdown=false", "fyrql.engine.threads=3",
                "fyrql.engine.preserve-order=true", "fyrql.engine.implicit-sources=false",
                "fyrql.engine.time-zone=Europe/Berlin", "fyrql.engine.file-access=read",
                "fyrql.engine.allowed-locations=" + path(data) + ",s3://lake/", "fyrql.engine.query-timeout=30s",
                "fyrql.engine.max-query-timeout=5m").run(context -> {
                    Map<String, String> settings = EngineConfigs.of(context.getBean(FyrQLProperties.class))
                            .settings();
                    assertThat(settings).containsExactly(Map.entry("defaultSource", "p"),
                            Map.entry("defaultSchema", "main"), Map.entry("batchSize", "1000"),
                            Map.entry("memoryBudget", "64MB"), Map.entry("spill", "true"),
                            Map.entry("spillDirectory", path(spill)), Map.entry("codegen", "off"),
                            Map.entry("codegenThreshold", "5000"), Map.entry("pushdown", "false"),
                            Map.entry("threads", "3"), Map.entry("preserveOrder", "true"),
                            Map.entry("implicitSources", "false"), Map.entry("timeZone", "Europe/Berlin"),
                            Map.entry("fileAccess", "read"),
                            Map.entry("allowedLocations", path(data) + ",s3://lake/"),
                            Map.entry("queryTimeout", "PT30S"), Map.entry("maxQueryTimeout", "PT5M"));
                    FyrQL engine = context.getBean(FyrQL.class);
                    assertThat(engine.spillDirectory()).isEqualTo(Optional.of(spill));
                    assertThat(engine.codegen()).isEqualTo(CodegenMode.OFF);
                    assertThat(engine.pushdown()).isFalse();
                    assertThat(engine.threads()).isEqualTo(3);
                    assertThat(engine.preserveOrder()).isTrue();
                    assertThat(engine.timeZone()).isEqualTo(ZoneId.of("Europe/Berlin"));
                    assertThat(engine.fileAccess()).isEqualTo(FileAccess.READ);
                    assertThat(engine.allowedLocations()).hasSize(2).endsWith("s3://lake/");
                    assertThat(engine.queryTimeout()).isEqualTo(Optional.of(Duration.ofSeconds(30)));
                    assertThat(engine.maxQueryTimeout()).isEqualTo(Optional.of(Duration.ofMinutes(5)));
                });
    }

    @Test
    void anInvalidKeyStopsTheContextAndSaysWhatIsWrong() {
        runner.withPropertyValues("fyrql.engine.memory-budget=lots").run(context -> assertThat(context)
                .getFailure().hasStackTraceContaining("The option memoryBudget of the engine must be a"
                        + " number of bytes such as 1000000, 512MB or 2GiB: lots"));
        runner.withPropertyValues("fyrql.engine.default-source=nowhere").run(context -> assertThat(context)
                .getFailure().hasStackTraceContaining("The default source nowhere of the engine is none"));
        runner.withPropertyValues("fyrql.sources[0].name=p").run(context -> assertThat(context).getFailure()
                .hasStackTraceContaining("The source p has no type"));
        runner.withPropertyValues("fyrql.sources[0].type=probe").run(context -> assertThat(context).getFailure()
                .hasStackTraceContaining("The source fyrql.sources[0] has no name"));
        runner.withPropertyValues("fyrql.sources[0].name=p", "fyrql.sources[0].type=probe",
                "fyrql.sources[1].name=P", "fyrql.sources[1].type=probe").run(context -> assertThat(context)
                        .getFailure().hasStackTraceContaining("There are two sources named P"));
        runner.withPropertyValues("fyrql.sources[0].name=p", "fyrql.sources[0].type=nope").run(context -> assertThat(
                context).getFailure().hasStackTraceContaining("The source p has the type nope, which no"
                        + " connector on the class path has"));
        runner.withInitializer(yaml("""
                fyrql:
                  sources:
                    - name: p
                      type: probe
                      pairs:
                        - key: a
                """)).run(context -> assertThat(context).getFailure().hasStackTraceContaining(
                        "The option pairs of the source p is a list, which may hold only plain values"));
        assertThat(ProbeConnectorFactory.open()).isEmpty();
    }

    @Test
    void aConfigurationFileAddsItsSourcesAndTheKeysReplaceItsSettings() throws IOException {
        Files.createDirectories(dir.resolve("data"));
        Files.writeString(dir.resolve("data/customers.csv"), "id,name\n1,Ann\n2,Bob\n", StandardCharsets.UTF_8);
        Path file = Files.writeString(dir.resolve("fyrql.yaml"), """
                sources:
                  - name: files
                    type: csv
                    directory: data
                engine:
                  threads: 2
                  pushdown: false
                """, StandardCharsets.UTF_8);
        runner.withPropertyValues("fyrql.config=" + path(file), "fyrql.engine.threads=5", "fyrql.sources[0].name=p",
                "fyrql.sources[0].type=probe").run(context -> {
                    FyrQL engine = context.getBean(FyrQL.class);
                    assertThat(engine.threads()).isEqualTo(5);
                    assertThat(engine.pushdown()).isFalse();
                    assertThat(engine.sources()).contains("files", "p");
                    assertThat(engine.rows("SELECT name FROM files.customers ORDER BY id")).hasSize(2);
                });
        runner.withPropertyValues("fyrql.config=" + path(file), "fyrql.sources[0].name=FILES",
                "fyrql.sources[0].type=probe").run(context -> assertThat(context).getFailure()
                        .hasStackTraceContaining("There are two sources named FILES"));
    }

    @Test
    void customizersAddToTheEngineAndAnEngineOfTheApplicationComesFirst() {
        List<Product> products = List.of(new Product("p1", 1.5), new Product("p2", 4));
        runner.withBean(FyrQLBuilderCustomizer.class, () -> b -> b.register(MemoryConnector.builder("app")
                .table("products", Product.class, products).build()).defaultSource("app")).run(context -> {
                    FyrQL engine = context.getBean(FyrQL.class);
                    assertThat(((Number) engine.rows("SELECT count(*) FROM products").get(0)[0]).longValue())
                            .isEqualTo(2L);
                });
        FyrQL own = FyrQL.builder().build();
        runner.withPropertyValues("fyrql.sources[0].name=p", "fyrql.sources[0].type=probe")
                .withBean("own", FyrQL.class, () -> own).run(context -> {
                    assertThat(context).hasSingleBean(FyrQL.class);
                    assertThat(context.getBean(FyrQL.class)).isSameAs(own);
                    assertThat(ProbeConnectorFactory.open()).isEmpty();
                });
        runner.withPropertyValues("fyrql.enabled=false").run(context -> assertThat(context)
                .doesNotHaveBean(FyrQL.class).doesNotHaveBean(FyrQLProperties.class));
    }

    @Test
    void theConnectorsCloseWithTheContextAndWhenACustomizerFails() {
        runner.withPropertyValues("fyrql.sources[0].name=p1", "fyrql.sources[0].type=probe",
                "fyrql.sources[1].name=p2", "fyrql.sources[1].type=probe").run(context -> {
                    assertThat(context).hasSingleBean(FyrQL.class);
                    assertThat(ProbeConnectorFactory.open()).containsExactly("p1", "p2");
                });
        assertThat(ProbeConnectorFactory.open()).isEmpty();
        runner.withPropertyValues("fyrql.sources[0].name=p1", "fyrql.sources[0].type=probe")
                .withBean(FyrQLBuilderCustomizer.class, () -> b -> {
                    throw new IllegalStateException("no products");
                }).run(context -> {
                    assertThat(context).getFailure().hasStackTraceContaining("no products");
                    assertThat(ProbeConnectorFactory.open()).isEmpty();
                });
    }

    @Test
    void theMetadataNamesTheKeysForTheIde() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/META-INF/spring-configuration-metadata.json")) {
            assertThat(in).isNotNull();
            String metadata = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(metadata).contains("\"fyrql.engine.threads\"", "\"fyrql.engine.memory-budget\"",
                    "\"fyrql.sources\"", "\"fyrql.config\"", "\"fyrql.enabled\"", "\"fyrql.datasource.enabled\"",
                    "\"management.health.fyrql.enabled\"", "\"readWrite\"");
        }
    }
}
