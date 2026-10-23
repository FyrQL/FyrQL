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

import net.fyrql.connector.FileAccess;
import net.fyrql.exec.CodegenMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The keys of the starter under {@code fyrql}, in the form of the
 * configuration file of FyrQL:
 * <pre>{@code
 * fyrql:
 *   engine:
 *     threads: 4
 *     queryTimeout: 30s
 *   sources:
 *     - name: sales
 *       type: jdbc
 *       url: jdbc:postgresql://db/sales
 *       password: ${DB_PASSWORD}
 * }</pre>
 * The keys under {@code engine} are the settings of the engine of the file,
 * the sources are those of the file, with their names, types and options;
 * a configuration file under {@code config} adds its own. Placeholders are
 * those of Spring, such as {@code ${DB_PASSWORD}}, not those of the file.
 */
@ConfigurationProperties("fyrql")
public class FyrQLProperties {

    /** Whether the starter creates an engine. */
    private boolean enabled = true;

    /**
     * A configuration file of FyrQL, whose sources come before those under
     * fyrql.sources and whose settings those under fyrql.engine replace;
     * relative paths in it are resolved against its directory.
     */
    private String config;

    /** The settings of the engine, as under engine in a configuration file. */
    private final Engine engine = new Engine();

    /**
     * The sources, each with a name, a type and the options of its type, as
     * under sources in a configuration file; relative paths are resolved
     * against the working directory.
     */
    private List<Map<String, String>> sources = new ArrayList<>();

    /** A DataSource of the engine. */
    private final DataSource datasource = new DataSource();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getConfig() {
        return config;
    }

    public void setConfig(String config) {
        this.config = config;
    }

    public Engine getEngine() {
        return engine;
    }

    public List<Map<String, String>> getSources() {
        return sources;
    }

    public void setSources(List<Map<String, String>> sources) {
        this.sources = sources;
    }

    public DataSource getDatasource() {
        return datasource;
    }

    /**
     * The settings of the engine. A setting that is not set keeps the value
     * of the configuration file, or else the default of the engine.
     */
    public static class Engine {

        /** The source of names without one. */
        private String defaultSource;

        /** The schema of names without one in the default source. */
        private String defaultSchema;

        /** The rows of a batch. */
        private Integer batchSize;

        /**
         * The memory a query may hold, such as 512MB (powers of 1000) or 2GiB
         * (powers of 1024).
         */
        private String memoryBudget;

        /** Whether sorts, aggregations and windows move parts to disk when the memory budget runs out. */
        private Boolean spill;

        /** The directory for the parts moved to disk; by default one of the system for temporary files. */
        private String spillDirectory;

        /** When operators are compiled to bytecode. */
        private CodegenMode codegen;

        /** The rows an operator processes before it is compiled, with codegen auto. */
        private Long codegenThreshold;

        /** Whether filters, projections and more go to the sources. */
        private Boolean pushdown;

        /** The threads of a query. */
        private Integer threads;

        /** Whether results keep the order of the source without ORDER BY. */
        private Boolean preserveOrder;

        /** Whether queries may name files, such as 'data/orders.csv', without a source. */
        private Boolean implicitSources;

        /** The time zone of sessions, such as Europe/Berlin; by default that of the JVM. */
        private String timeZone;

        /** What SQL may do with files and objects that it names. */
        private FileAccess fileAccess;

        /** The directories and prefixes, such as s3://lake/, that SQL may name. */
        private List<String> allowedLocations;

        /** The time a statement may run unless a session sets another. */
        private Duration queryTimeout;

        /** The longest time limit that a session may set. */
        private Duration maxQueryTimeout;

        public String getDefaultSource() {
            return defaultSource;
        }

        public void setDefaultSource(String defaultSource) {
            this.defaultSource = defaultSource;
        }

        public String getDefaultSchema() {
            return defaultSchema;
        }

        public void setDefaultSchema(String defaultSchema) {
            this.defaultSchema = defaultSchema;
        }

        public Integer getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(Integer batchSize) {
            this.batchSize = batchSize;
        }

        public String getMemoryBudget() {
            return memoryBudget;
        }

        public void setMemoryBudget(String memoryBudget) {
            this.memoryBudget = memoryBudget;
        }

        public Boolean getSpill() {
            return spill;
        }

        public void setSpill(Boolean spill) {
            this.spill = spill;
        }

        public String getSpillDirectory() {
            return spillDirectory;
        }

        public void setSpillDirectory(String spillDirectory) {
            this.spillDirectory = spillDirectory;
        }

        public CodegenMode getCodegen() {
            return codegen;
        }

        public void setCodegen(CodegenMode codegen) {
            this.codegen = codegen;
        }

        public Long getCodegenThreshold() {
            return codegenThreshold;
        }

        public void setCodegenThreshold(Long codegenThreshold) {
            this.codegenThreshold = codegenThreshold;
        }

        public Boolean getPushdown() {
            return pushdown;
        }

        public void setPushdown(Boolean pushdown) {
            this.pushdown = pushdown;
        }

        public Integer getThreads() {
            return threads;
        }

        public void setThreads(Integer threads) {
            this.threads = threads;
        }

        public Boolean getPreserveOrder() {
            return preserveOrder;
        }

        public void setPreserveOrder(Boolean preserveOrder) {
            this.preserveOrder = preserveOrder;
        }

        public Boolean getImplicitSources() {
            return implicitSources;
        }

        public void setImplicitSources(Boolean implicitSources) {
            this.implicitSources = implicitSources;
        }

        public String getTimeZone() {
            return timeZone;
        }

        public void setTimeZone(String timeZone) {
            this.timeZone = timeZone;
        }

        public FileAccess getFileAccess() {
            return fileAccess;
        }

        public void setFileAccess(FileAccess fileAccess) {
            this.fileAccess = fileAccess;
        }

        public List<String> getAllowedLocations() {
            return allowedLocations;
        }

        public void setAllowedLocations(List<String> allowedLocations) {
            this.allowedLocations = allowedLocations;
        }

        public Duration getQueryTimeout() {
            return queryTimeout;
        }

        public void setQueryTimeout(Duration queryTimeout) {
            this.queryTimeout = queryTimeout;
        }

        public Duration getMaxQueryTimeout() {
            return maxQueryTimeout;
        }

        public void setMaxQueryTimeout(Duration maxQueryTimeout) {
            this.maxQueryTimeout = maxQueryTimeout;
        }

        /** Returns the settings that are set, as text in the form of the configuration file. */
        Map<String, String> settings() {
            Map<String, String> s = new LinkedHashMap<>();
            put(s, "defaultSource", defaultSource);
            put(s, "defaultSchema", defaultSchema);
            put(s, "batchSize", batchSize);
            put(s, "memoryBudget", memoryBudget);
            put(s, "spill", spill);
            put(s, "spillDirectory", spillDirectory);
            put(s, "codegen", codegen == null ? null : codegen.name().toLowerCase(Locale.ROOT));
            put(s, "codegenThreshold", codegenThreshold);
            put(s, "pushdown", pushdown);
            put(s, "threads", threads);
            put(s, "preserveOrder", preserveOrder);
            put(s, "implicitSources", implicitSources);
            put(s, "timeZone", timeZone);
            put(s, "fileAccess", fileAccess);
            put(s, "allowedLocations", allowedLocations == null ? null : String.join(",", allowedLocations));
            put(s, "queryTimeout", queryTimeout);
            put(s, "maxQueryTimeout", maxQueryTimeout);
            return s;
        }

        private static void put(Map<String, String> settings, String key, Object value) {
            if (value != null) {
                settings.put(key, value.toString());
            }
        }
    }

    /** A DataSource of the engine, besides the DataSource of the application. */
    public static class DataSource {

        /**
         * Whether there is a DataSource of the engine, as the bean
         * fyrqlDataSource, which is no candidate for injection by its type
         * alone.
         */
        private boolean enabled;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
