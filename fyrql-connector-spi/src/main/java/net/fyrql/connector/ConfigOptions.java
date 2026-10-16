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

package net.fyrql.connector;

import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The options of a source, or of another part of a configuration, as a
 * {@link ConnectorFactory} receives them: flat, with nested keys joined by
 * dots. Reads them typed, with messages that name the option and what it
 * belongs to, and finds the options that nothing asked for, which are
 * misspelled or unknown; see {@link #rejectUnknown()}. A message never
 * holds the value of an option that is not read as a number, a flag or
 * another value of a fixed form, so that passwords stay out of it.
 */
public final class ConfigOptions {

    private static final Pattern DURATION = Pattern.compile("(\\d+)\\s*(ms|s|m|h)");
    private static final Pattern BYTES = Pattern.compile("(\\d+)\\s*([kmgt]i?b|b)?");
    private static final String TABLES = "tables";

    private final String owner;
    private final Map<String, String> options;
    private final Path directory;
    private final Set<String> read = new HashSet<>();
    private final Set<String> known = new LinkedHashSet<>();

    /**
     * @param owner what the options belong to, for messages, such as
     *        {@code the CSV source files}
     * @param options the options, with nested keys joined by dots
     * @param directory the directory against which relative paths are
     *        resolved, such as that of the configuration file
     */
    public ConfigOptions(String owner, Map<String, String> options, Path directory) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.options = Map.copyOf(options);
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    /**
     * Reads an option.
     *
     * @param expected what the value must be, for the message if it does not
     *        parse, such as {@code an integer}; {@code null} to use the
     *        message of the exception of the parse function instead
     * @param parse turns the value into what it stands for; it throws a
     *        runtime exception if it cannot
     * @return the parsed value, or empty if the option is not set
     * @throws IllegalArgumentException if the value does not parse
     */
    public <T> Optional<T> get(String key, String expected, Function<String, ? extends T> parse) {
        known.add(key);
        String value = options.get(key);
        if (value == null) {
            return Optional.empty();
        }
        read.add(key);
        return Optional.of(parse(key, value, expected, parse));
    }

    /** Reads an option as it is. */
    public Optional<String> string(String key) {
        return get(key, null, v -> v);
    }

    /**
     * Reads an option that must be set.
     *
     * @throws IllegalArgumentException if it is not set or blank
     */
    public String required(String key) {
        Optional<String> value = string(key);
        if (value.isEmpty() || value.get().isBlank()) {
            throw new IllegalArgumentException(capitalized() + " needs the option " + key);
        }
        return value.get();
    }

    /** Reads an option as an {@code int}. */
    public Optional<Integer> integer(String key) {
        return get(key, "an integer", v -> Integer.parseInt(v.strip()));
    }

    /** Reads an option as a {@code double}. */
    public Optional<Double> number(String key) {
        return get(key, "a number", v -> Double.parseDouble(v.strip()));
    }

    /** Reads an option as {@code true} or {@code false}, or {@code on} or {@code off}, in any case. */
    public Optional<Boolean> bool(String key) {
        return get(key, "true, false, on or off", v -> switch (v.strip().toLowerCase(Locale.ROOT)) {
            case "true", "on" -> true;
            case "false", "off" -> false;
            default -> throw new IllegalArgumentException(v);
        });
    }

    /** Reads an option as one character; {@code \t} stands for a tab. */
    public Optional<Character> character(String key) {
        return get(key, "one character", v -> {
            if (v.equals("\\t")) {
                return '\t';
            }
            if (v.length() != 1) {
                throw new IllegalArgumentException(v);
            }
            return v.charAt(0);
        });
    }

    /**
     * Reads an option as a duration: a number with the unit {@code ms},
     * {@code s}, {@code m} or {@code h}, such as {@code 30s}, or as in ISO
     * 8601, such as {@code PT30S}.
     */
    public Optional<Duration> duration(String key) {
        return get(key, "a duration such as 500ms, 30s, 2m or PT30S", ConfigOptions::parseDuration);
    }

    /**
     * Reads an option as a number of bytes: a number, optionally with the
     * unit {@code B}, {@code KB}, {@code MB}, {@code GB} or {@code TB} for
     * powers of 1000, or {@code KiB}, {@code MiB}, {@code GiB} or
     * {@code TiB} for powers of 1024, in any case, as DuckDB reads them.
     */
    public Optional<Long> bytes(String key) {
        return get(key, "a number of bytes such as 1000000, 512MB or 2GiB", ConfigOptions::parseBytes);
    }

    /** Reads an option as a path, resolved against the directory. */
    public Optional<Path> path(String key) {
        return get(key, "a path", this::resolve);
    }

    /** Resolves a path against the directory, unless it is absolute. */
    public Path resolve(String path) {
        return directory.resolve(path);
    }

    /** Returns the directory against which relative paths are resolved. */
    public Path directory() {
        return directory;
    }

    /**
     * Reads the options under a prefix, such as {@code headers.Accept} and
     * {@code headers.Authorization} under {@code headers}.
     *
     * @param placeholder what stands for the rest of a key in the list of
     *        known options, such as {@code <name>}
     * @return the values by the rest of their keys, in the order of the keys
     */
    public Map<String, String> prefixed(String prefix, String placeholder) {
        known.add(prefix + "." + placeholder);
        Map<String, String> values = new TreeMap<>();
        for (Map.Entry<String, String> e : options.entrySet()) {
            String key = e.getKey();
            if (key.startsWith(prefix + ".") && key.length() > prefix.length() + 1) {
                read.add(key);
                values.put(key.substring(prefix.length() + 1), e.getValue());
            }
        }
        return values;
    }

    /**
     * Reads the tables of a source, which a configuration names under
     * {@code tables}: either {@code tables.<name>} with the main attribute
     * of the table, such as its file or URL, or {@code tables.<name>.<attribute>}
     * for each attribute. A table name holds no dot.
     *
     * @param main the attribute that every table has, such as {@code file}
     * @param attributes the other attributes a table may have
     * @return the attributes of each table by its name, in the order of the
     *         names; the main attribute is always set
     * @throws IllegalArgumentException if a table lacks the main attribute,
     *         has it twice or has an unknown attribute
     */
    public Map<String, Map<String, String>> tables(String main, String... attributes) {
        List<String> allowed = new ArrayList<>();
        allowed.add(main);
        allowed.addAll(List.of(attributes));
        known.add(TABLES + ".<name>");
        for (String a : allowed) {
            known.add(TABLES + ".<name>." + a);
        }
        Map<String, Map<String, String>> tables = new TreeMap<>();
        for (Map.Entry<String, String> e : options.entrySet()) {
            String key = e.getKey();
            if (!key.startsWith(TABLES + ".") || key.length() == TABLES.length() + 1) {
                continue;
            }
            read.add(key);
            String rest = key.substring(TABLES.length() + 1);
            int dot = rest.indexOf('.');
            String table = dot < 0 ? rest : rest.substring(0, dot);
            String attribute = dot < 0 ? main : rest.substring(dot + 1);
            if (!allowed.contains(attribute)) {
                throw new IllegalArgumentException("The table " + table + " of " + owner
                        + " has an unknown attribute " + attribute + "; known are " + String.join(", ", allowed));
            }
            Map<String, String> table0 = tables.computeIfAbsent(table, t -> new LinkedHashMap<>());
            if (table0.putIfAbsent(attribute, e.getValue()) != null) {
                throw new IllegalArgumentException("The table " + table + " of " + owner + " has both "
                        + TABLES + "." + table + " and " + TABLES + "." + table + "." + main);
            }
        }
        for (Map.Entry<String, Map<String, String>> e : tables.entrySet()) {
            if (!e.getValue().containsKey(main)) {
                throw new IllegalArgumentException("The table " + e.getKey() + " of " + owner + " needs "
                        + TABLES + "." + e.getKey() + "." + main);
            }
        }
        return tables;
    }

    /**
     * Fails if an option was not asked for by any of the methods that read
     * options, so that a misspelled option is not silently ignored. Call it
     * after all options are read.
     *
     * @throws IllegalArgumentException naming the unknown options and the known ones
     */
    public void rejectUnknown() {
        List<String> unknown = new ArrayList<>();
        for (String key : new TreeMap<>(options).keySet()) {
            if (!read.contains(key)) {
                unknown.add(key);
            }
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(capitalized() + " has " + (unknown.size() == 1 ? "an unknown option "
                    : "the unknown options ") + String.join(", ", unknown) + "; known are "
                    + String.join(", ", known));
        }
    }

    private <T> T parse(String key, String value, String expected, Function<String, ? extends T> parse) {
        try {
            return Objects.requireNonNull(parse.apply(value));
        } catch (RuntimeException e) {
            String message = expected != null ? "must be " + expected + ": " + value : "is invalid: " + e.getMessage();
            throw new IllegalArgumentException("The option " + key + " of " + owner + " " + message, e);
        }
    }

    private String capitalized() {
        return owner.substring(0, 1).toUpperCase(Locale.ROOT) + owner.substring(1);
    }

    private static Duration parseDuration(String value) {
        String v = value.strip();
        if (v.regionMatches(true, 0, "PT", 0, 2)) {
            try {
                return positive(Duration.parse(v));
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException(v, e);
            }
        }
        Matcher m = DURATION.matcher(v.toLowerCase(Locale.ROOT));
        if (!m.matches()) {
            throw new IllegalArgumentException(v);
        }
        long n = Long.parseLong(m.group(1));
        return positive(switch (m.group(2)) {
            case "ms" -> Duration.ofMillis(n);
            case "s" -> Duration.ofSeconds(n);
            case "m" -> Duration.ofMinutes(n);
            default -> Duration.ofHours(n);
        });
    }

    private static Duration positive(Duration d) {
        if (d.isNegative() || d.isZero()) {
            throw new IllegalArgumentException(d.toString());
        }
        return d;
    }

    private static long parseBytes(String value) {
        Matcher m = BYTES.matcher(value.strip().toLowerCase(Locale.ROOT));
        if (!m.matches()) {
            throw new IllegalArgumentException(value);
        }
        long n = Long.parseLong(m.group(1));
        String unit = m.group(2);
        if (unit == null || unit.equals("b")) {
            return n;
        }
        int power = "kmgt".indexOf(unit.charAt(0)) + 1;
        long base = unit.length() == 3 ? 1024 : 1000;
        long factor = 1;
        for (int i = 0; i < power; i++) {
            factor *= base;
        }
        return Math.multiplyExact(n, factor);
    }
}
