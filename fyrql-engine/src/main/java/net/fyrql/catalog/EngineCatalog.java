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

package net.fyrql.catalog;

import net.fyrql.connector.Connector;
import net.fyrql.connector.FileReaderProvider;
import net.fyrql.connector.FileStores;
import net.fyrql.connector.FileWriterProvider;
import net.fyrql.connector.TableFunctionProvider;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.Schema;
import net.fyrql.sql.SqlException;
import net.fyrql.sql.ast.Position;
import net.fyrql.sql.bind.CatalogView;

import java.io.UncheckedIOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The catalog of an engine for the binder: names through the
 * {@link NameResolver}, and table functions from the connectors that offer
 * them. A function offered by several connectors is taken from the default
 * source if it offers it, otherwise from the first one registered; so are
 * the writers and the readers of file formats.
 */
public final class EngineCatalog implements CatalogView {

    private final Map<String, Connector> sources;
    private final Supplier<ZoneId> zone;
    private final NameResolver resolver;
    private final Map<String, TableFunctionProvider> functions = new LinkedHashMap<>();
    private final Map<String, FileWriterProvider> fileWriters = new LinkedHashMap<>();
    private final Map<String, FileReaderProvider> fileReaders = new LinkedHashMap<>();

    /** The formats of files that a module of their own brings. */
    private static final List<String> MODULE_FORMATS = List.of("csv", "json", "parquet");

    /**
     * A catalog over sources by name, in the order of their registration, in
     * the time zone of the JVM.
     */
    public EngineCatalog(Map<String, Connector> sources, SearchPath searchPath) {
        this(sources, searchPath, ZoneId::systemDefault);
    }

    /**
     * A catalog over sources by name, in the order of their registration.
     *
     * @param zone the time zone of the session, which the catalog adds to
     *        the options of files and table functions, see
     *        {@link FileWriterProvider#TIME_ZONE}
     */
    public EngineCatalog(Map<String, Connector> sources, SearchPath searchPath, Supplier<ZoneId> zone) {
        this.zone = zone;
        this.sources = new LinkedHashMap<>(sources);
        this.resolver = new NameResolver(sources, searchPath);
        Connector preferred = searchPath.defaultSource() == null ? null : sources.get(searchPath.defaultSource());
        if (preferred instanceof TableFunctionProvider p) {
            for (String f : p.tableFunctions()) {
                functions.put(f.toLowerCase(Locale.ROOT), p);
            }
        }
        if (preferred instanceof FileWriterProvider p) {
            for (String f : p.fileFormats()) {
                fileWriters.put(f.toLowerCase(Locale.ROOT), p);
            }
        }
        if (preferred instanceof FileReaderProvider p) {
            for (String f : p.fileFormats()) {
                fileReaders.put(f.toLowerCase(Locale.ROOT), p);
            }
        }
        for (Connector c : sources.values()) {
            if (c instanceof TableFunctionProvider p) {
                for (String f : p.tableFunctions()) {
                    functions.putIfAbsent(f.toLowerCase(Locale.ROOT), p);
                }
            }
            if (c instanceof FileWriterProvider p) {
                for (String f : p.fileFormats()) {
                    fileWriters.putIfAbsent(f.toLowerCase(Locale.ROOT), p);
                }
            }
            if (c instanceof FileReaderProvider p) {
                for (String f : p.fileFormats()) {
                    fileReaders.putIfAbsent(f.toLowerCase(Locale.ROOT), p);
                }
            }
        }
    }

    /** Returns the search path. */
    public SearchPath searchPath() {
        return resolver.searchPath();
    }

    /**
     * Returns a catalog over the same sources with another search path, or
     * this catalog if the path is its own.
     *
     * @throws IllegalArgumentException if the default source of the path is not registered
     */
    public EngineCatalog withSearchPath(SearchPath searchPath) {
        return searchPath.equals(searchPath()) ? this : new EngineCatalog(sources, searchPath, zone);
    }

    /** Returns options with {@link FileWriterProvider#TIME_ZONE} added unless they have it. */
    public Map<String, String> withTimeZone(Map<String, String> options) {
        if (options.containsKey(FileWriterProvider.TIME_ZONE)) {
            return options;
        }
        Map<String, String> with = new LinkedHashMap<>(options);
        with.put(FileWriterProvider.TIME_ZONE, zone.get().getId());
        return with;
    }

    /** Returns the names of the table functions, in lower case. */
    public List<String> tableFunctions() {
        return List.copyOf(functions.keySet());
    }

    /** Returns the formats of files that {@code COPY ... TO} writes, in lower case. */
    public List<String> fileFormats() {
        return List.copyOf(fileWriters.keySet());
    }

    /**
     * Returns a hint at the module that brings a format of files, such as
     * {@code "; put fyrql-connector-csv on the class path or register a
     * source of the type csv"}, or an empty text for a format without a
     * module.
     */
    public static String moduleHint(String format) {
        String f = format.toLowerCase(Locale.ROOT);
        return MODULE_FORMATS.contains(f)
                ? "; put fyrql-connector-" + f + " on the class path or register a source of the type " + f : "";
    }

    /** Returns the writer of a format of files, given in any case. */
    public Optional<FileWriterProvider> fileWriter(String format) {
        return Optional.ofNullable(fileWriters.get(format.toLowerCase(Locale.ROOT)));
    }

    /** Returns the formats of files that {@code COPY ... FROM} reads, in lower case. */
    public List<String> readFormats() {
        return List.copyOf(fileReaders.keySet());
    }

    /** Returns the reader of a format of files, given in any case. */
    public Optional<FileReaderProvider> fileReader(String format) {
        return Optional.ofNullable(fileReaders.get(format.toLowerCase(Locale.ROOT)));
    }

    /**
     * Returns the table of the source that reads the format, which reads
     * the file with the given columns.
     *
     * @throws SqlException if no source reads the format, the path is no
     *         file, or the source refuses the file, the columns or the options
     */
    @Override
    public TableRef fileTable(String format, String path, Schema schema, Map<String, String> options) {
        String f = format.toLowerCase(Locale.ROOT);
        FileReaderProvider provider = fileReaders.get(f);
        if (provider == null) {
            throw new SqlException("No source reads files of the format " + format + moduleHint(format)
                    + (fileReaders.isEmpty() ? "" : "; there are " + String.join(", ", fileReaders.keySet())),
                    Position.UNKNOWN);
        }
        checkLocal(path);
        try {
            return provider.fileTable(f, path, schema, withTimeZone(options));
        } catch (IllegalArgumentException | UncheckedIOException e) {
            throw new SqlException(e.getMessage(), Position.UNKNOWN);
        }
    }

    /**
     * Checks that a location without a scheme is a valid local path.
     *
     * @throws SqlException if it is not
     */
    public static void checkLocal(String location) {
        if (FileStores.scheme(location).isEmpty()) {
            try {
                Path.of(location);
            } catch (InvalidPathException e) {
                throw new SqlException("Not a file: " + location + " (" + e.getReason() + ")", Position.UNKNOWN);
            }
        }
    }

    @Override
    public TableRef resolve(List<String> name) {
        return resolver.resolve(name);
    }

    @Override
    public Optional<TableRef> find(List<String> name) {
        return resolver.find(name);
    }

    /**
     * Returns where a new table goes, see {@link NameResolver#resolveNew}.
     *
     * @throws SqlException if the name names no source or schema, or the source writes no tables
     */
    @Override
    public TableRef resolveNew(List<String> name) {
        TableRef table = resolver.resolveNew(name);
        if (sources.get(table.source()).writer().isEmpty()) {
            throw new SqlException("Source " + table.source() + " does not write tables", Position.UNKNOWN);
        }
        return table;
    }

    @Override
    public Schema schema(TableRef table) {
        Connector c = sources.get(table.source());
        if (c == null) {
            throw new SqlException("No source " + table.source(), Position.UNKNOWN);
        }
        return c.catalog().schema(table);
    }

    @Override
    public TableRef tableFunction(String name, List<Object> arguments, Map<String, Object> namedArguments) {
        String function = name.toLowerCase(Locale.ROOT);
        TableFunctionProvider provider = functions.get(function);
        if (provider == null) {
            throw new SqlException("Table function with name " + name + " does not exist"
                    + (function.startsWith("read_") ? moduleHint(function.substring(5)) : "")
                    + (functions.isEmpty() ? "" : "; there are " + String.join(", ", functions.keySet())),
                    Position.UNKNOWN);
        }
        // Names are case-insensitive; a repeated name counts with its last value, as in DuckDB.
        Map<String, Object> named = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : namedArguments.entrySet()) {
            named.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
        }
        named.putIfAbsent(FileWriterProvider.TIME_ZONE, zone.get().getId());
        try {
            return provider.bind(function, arguments, named);
        } catch (IllegalArgumentException | UncheckedIOException e) {
            throw new SqlException(e.getMessage(), Position.UNKNOWN);
        }
    }
}
