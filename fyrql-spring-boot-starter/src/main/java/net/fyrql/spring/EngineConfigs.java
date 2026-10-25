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

import net.fyrql.config.EngineConfig;
import net.fyrql.config.SourceConfig;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns the keys of the starter into the configuration of an engine. */
final class EngineConfigs {

    /**
     * An element of a list, as Spring binds it into a map: the key, then
     * the index after a dot, or in brackets as in properties.
     */
    private static final Pattern ELEMENT = Pattern.compile("(.+?)(?:\\.(\\d+)|\\[(\\d+)])");

    /** The key of a list before an element that is no plain value, such as a mapping in a list. */
    private static final Pattern INNER = Pattern.compile("(.+?)(?:\\.\\d+(?:\\.|\\[|$)|\\[)");

    private EngineConfigs() {
    }

    /**
     * Returns the configuration of the keys: the sources of the file under
     * {@code fyrql.config} and those under {@code fyrql.sources}, and the
     * settings of the file, replaced by those under {@code fyrql.engine}.
     *
     * @throws net.fyrql.config.ConfigException if the file is no valid configuration
     * @throws IllegalArgumentException if a source has no name or type, or two sources have the same name
     */
    static EngineConfig of(FyrQLProperties properties) {
        EngineConfig file = properties.getConfig() == null
                ? new EngineConfig(List.of(), Map.of(), Path.of("").toAbsolutePath())
                : EngineConfig.read(Path.of(properties.getConfig()));
        List<SourceConfig> sources = new ArrayList<>(file.sources());
        Set<String> names = new HashSet<>();
        for (SourceConfig s : sources) {
            names.add(s.name().toLowerCase(Locale.ROOT));
        }
        List<Map<String, String>> listed = properties.getSources();
        for (int i = 0; i < listed.size(); i++) {
            SourceConfig s = source(listed.get(i), "fyrql.sources[" + i + "]");
            if (!names.add(s.name().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("There are two sources named " + s.name());
            }
            sources.add(s);
        }
        Map<String, String> settings = new LinkedHashMap<>(file.settings());
        settings.putAll(properties.getEngine().settings());
        return new EngineConfig(sources, settings, file.server(), file.pgwire(), file.directory());
    }

    /** Returns a source of a map with its name, its type and its options, whose lists are joined by commas. */
    private static SourceConfig source(Map<String, String> map, String key) {
        String name = map.get("name");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("The source " + key + " has no name");
        }
        String type = map.get("type");
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("The source " + name + " has no type");
        }
        Map<String, String> options = new LinkedHashMap<>();
        Map<String, TreeMap<Integer, String>> lists = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : map.entrySet()) {
            String option = e.getKey();
            if (option.equals("name") || option.equals("type")) {
                continue;
            }
            Matcher m = ELEMENT.matcher(option);
            String plain = m.matches() ? m.group(1) : option;
            Matcher inner = INNER.matcher(plain);
            if (inner.lookingAt()) {
                throw new IllegalArgumentException("The option " + inner.group(1) + " of the source " + name
                        + " is a list, which may hold only plain values");
            }
            if (m.matches()) {
                String index = m.group(2) != null ? m.group(2) : m.group(3);
                lists.computeIfAbsent(plain, k -> new TreeMap<>()).put(Integer.valueOf(index), e.getValue());
            } else {
                options.put(option, e.getValue());
            }
        }
        for (Map.Entry<String, TreeMap<Integer, String>> e : lists.entrySet()) {
            if (options.putIfAbsent(e.getKey(), String.join(",", e.getValue().values())) != null) {
                throw new IllegalArgumentException("The option " + e.getKey() + " of the source " + name
                        + " is set twice");
            }
        }
        return new SourceConfig(name, type, options);
    }
}
