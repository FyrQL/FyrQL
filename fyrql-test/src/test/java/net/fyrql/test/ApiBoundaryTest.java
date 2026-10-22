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

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The boundary of the API: every package says in its {@code package-info.java}
 * whether it is part of the API, of the SPI for connectors, or internal, and
 * a class may say otherwise for itself; the public types of the API and the
 * SPI name in their signatures only the JDK, types of the API and the SPI,
 * and Arrow in the modules of Arrow and Flight SQL; and the section
 * Compatibility of the manual lists the same tiers.
 */
class ApiBoundaryTest {

    private enum Tier {
        API("Part of the API; covered by the compatibility promise."),
        SPI("Part of the SPI for connectors; covered by the compatibility promise."),
        INTERNAL("Internal; not covered by the compatibility promise.");

        final String sentence;

        Tier(String sentence) {
            this.sentence = sentence;
        }
    }

    /** Members of the API that name internal types on purpose, as {@code class#member}. */
    private static final Set<String> EXCEPTIONS = Set.of("net.fyrql.FyrQL#catalog",
            "net.fyrql.connector.jdbc.JdbcConnector#sql", "net.fyrql.connector.jdbc.JdbcDialect#sql");

    /** The packages whose public types may name types of Arrow. */
    private static final Set<String> ARROW = Set.of("net.fyrql.arrow", "net.fyrql.flight");

    private static final Pattern PACKAGE = Pattern.compile("^package ([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern ROW = Pattern.compile("^\\| `([\\w.]+)` \\| (API|SPI|Internal) \\| (.*) \\|$");

    /** The tier of each package, from its {@code package-info.java}. */
    private final Map<String, Tier> packages = new TreeMap<>();
    /** The tier of each top-level type that says otherwise than its package, by its name. */
    private final Map<String, Tier> overrides = new TreeMap<>();
    /** The public top-level types, by their names. */
    private final Set<String> publicTypes = new TreeSet<>();
    private final List<String> problems = new ArrayList<>();
    /** The types whose members were checked. */
    private int checked;

    @Test
    void thePackagesSayTheirTierAndTheApiNamesNoInternalTypes() throws Exception {
        // The tests run in the directory of the module.
        Path root = Path.of("..").toAbsolutePath().normalize();
        scan(root);
        assertTrue(packages.size() >= 30, packages.toString());
        for (String type : publicTypes) {
            if (tier(type) != Tier.INTERNAL) {
                check(Class.forName(type, false, getClass().getClassLoader()));
            }
        }
        compareWithTheManual(root.resolve("docs/manual.md"));
        assertTrue(problems.isEmpty(), String.join("\n", problems));
        assertTrue(checked >= 200, "checked only " + checked + " types");
    }

    /** Reads the tiers of the packages and of the types that say otherwise, and the public types. */
    private void scan(Path root) throws IOException {
        List<Path> sources;
        try (Stream<Path> modules = Files.list(root)) {
            sources = modules.map(m -> m.resolve("src/main/java")).filter(Files::isDirectory).toList();
        }
        for (Path source : sources) {
            List<Path> files;
            try (Stream<Path> walk = Files.walk(source)) {
                files = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
            }
            for (Path file : files) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                Matcher m = PACKAGE.matcher(text);
                if (!m.find()) {
                    problems.add(file + ": no package");
                    continue;
                }
                String pkg = m.group(1);
                String name = file.getFileName().toString().replace(".java", "");
                if (name.equals("package-info")) {
                    List<Tier> tiers = tiers(text);
                    if (tiers.size() != 1) {
                        problems.add(pkg + ": the package says " + tiers.size() + " tiers, not one");
                    } else {
                        packages.put(pkg, tiers.get(0));
                    }
                    continue;
                }
                String declaration = "public (?:(?:final|abstract|sealed|non-sealed|static) )*"
                        + "(?:class|interface|record|enum|@interface) " + name + "\\b";
                Matcher d = Pattern.compile("^" + declaration, Pattern.MULTILINE).matcher(text);
                if (!d.find()) {
                    continue;
                }
                publicTypes.add(pkg + "." + name);
                // The comment of the type is the last one before its declaration.
                String before = text.substring(0, d.start());
                int start = before.lastIndexOf("/**");
                List<Tier> tiers = start < 0 ? List.of() : tiers(before.substring(start));
                if (tiers.size() > 1) {
                    problems.add(pkg + "." + name + ": the type says " + tiers.size() + " tiers");
                } else if (tiers.size() == 1) {
                    overrides.put(pkg + "." + name, tiers.get(0));
                }
            }
        }
        for (String pkg : new TreeSet<>(packageNames(publicTypes))) {
            if (!packages.containsKey(pkg)) {
                problems.add(pkg + ": no package-info.java with its tier");
            }
        }
        overrides.forEach((type, tier) -> {
            if (tier == packages.get(packageOf(type))) {
                problems.add(type + ": says the tier of its package again");
            }
        });
    }

    private static List<Tier> tiers(String text) {
        String flat = text.replaceAll("\\s*\\n\\s*\\*\\s*", " ");
        List<Tier> tiers = new ArrayList<>();
        for (Tier tier : Tier.values()) {
            for (int i = flat.indexOf(tier.sentence); i >= 0; i = flat.indexOf(tier.sentence, i + 1)) {
                tiers.add(tier);
            }
        }
        return tiers;
    }

    private static Set<String> packageNames(Set<String> types) {
        Set<String> names = new HashSet<>();
        for (String type : types) {
            names.add(packageOf(type));
        }
        return names;
    }

    private static String packageOf(String type) {
        return type.substring(0, type.lastIndexOf('.'));
    }

    /** Returns the tier of a top-level type: its own, or else that of its package. */
    private Tier tier(String type) {
        Tier own = overrides.get(type);
        return own != null ? own : packages.getOrDefault(packageOf(type), Tier.INTERNAL);
    }

    /** Checks the public and protected members of a type of the API or the SPI and of its nested types. */
    private void check(Class<?> type) {
        if (!Modifier.isPublic(type.getModifiers())) {
            return;
        }
        checked++;
        String owner = type.getName();
        if (type.getGenericSuperclass() != null) {
            names(type, owner + " extends", type.getGenericSuperclass());
        }
        for (Type t : type.getGenericInterfaces()) {
            names(type, owner + " implements", t);
        }
        for (Field f : type.getDeclaredFields()) {
            if (visible(f.getModifiers()) && !f.isSynthetic()) {
                names(type, owner + "#" + f.getName(), f.getGenericType());
            }
        }
        for (Constructor<?> c : type.getDeclaredConstructors()) {
            if (visible(c.getModifiers()) && !c.isSynthetic()) {
                executable(type, owner + "#<init>", c);
            }
        }
        for (Method m : type.getDeclaredMethods()) {
            if (visible(m.getModifiers()) && !m.isSynthetic() && !m.isBridge()) {
                executable(type, owner + "#" + m.getName(), m);
                names(type, owner + "#" + m.getName(), m.getGenericReturnType());
            }
        }
        for (Class<?> nested : type.getDeclaredClasses()) {
            check(nested);
        }
    }

    private static boolean visible(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }

    private void executable(Class<?> type, String member, Executable e) {
        for (Type t : e.getGenericParameterTypes()) {
            names(type, member, t);
        }
        for (Type t : e.getGenericExceptionTypes()) {
            names(type, member, t);
        }
        for (TypeVariable<?> v : e.getTypeParameters()) {
            names(type, member, v);
        }
    }

    /** Adds a problem for each type a member names that is not allowed in the API or the SPI. */
    private void names(Class<?> type, String member, Type named) {
        if (EXCEPTIONS.contains(member)) {
            return;
        }
        Set<Class<?>> classes = new HashSet<>();
        collect(named, classes, new HashSet<>());
        for (Class<?> c : classes) {
            String problem = problem(type, c);
            if (problem != null) {
                problems.add(member + ": " + problem);
            }
        }
    }

    private static void collect(Type t, Set<Class<?>> classes, Set<Type> seen) {
        if (!seen.add(t)) {
            return;
        }
        switch (t) {
            case Class<?> c -> {
                Class<?> e = c;
                while (e.isArray()) {
                    e = e.getComponentType();
                }
                classes.add(e);
            }
            case ParameterizedType p -> {
                collect(p.getRawType(), classes, seen);
                for (Type a : p.getActualTypeArguments()) {
                    collect(a, classes, seen);
                }
            }
            case WildcardType w -> {
                for (Type b : w.getUpperBounds()) {
                    collect(b, classes, seen);
                }
                for (Type b : w.getLowerBounds()) {
                    collect(b, classes, seen);
                }
            }
            case TypeVariable<?> v -> {
                for (Type b : v.getBounds()) {
                    collect(b, classes, seen);
                }
            }
            case GenericArrayType a -> collect(a.getGenericComponentType(), classes, seen);
            default -> throw new IllegalArgumentException("Unknown type " + t);
        }
    }

    /** Returns why a type of the API or the SPI may not name a class, or {@code null} if it may. */
    private String problem(Class<?> type, Class<?> named) {
        String name = named.getName();
        if (named.isPrimitive() || name.startsWith("java.") || name.startsWith("javax.")) {
            return null;
        }
        if (name.startsWith("org.apache.arrow.")) {
            return ARROW.contains(type.getPackageName()) ? null : "names Arrow: " + name;
        }
        if (!name.startsWith("net.fyrql.") || name.startsWith("net.fyrql.internal.")) {
            return "names a type of a library: " + name;
        }
        String top = name.contains("$") ? name.substring(0, name.indexOf('$')) : name;
        if (!publicTypes.contains(top)) {
            return "names a type that is not public: " + name;
        }
        Tier tier = tier(top);
        return tier == Tier.INTERNAL ? "names an internal type: " + name : null;
    }

    /**
     * Compares the table of the section Compatibility of the manual with the
     * tiers: one row per package with its tier and, as exceptions, the types
     * that say otherwise.
     */
    private void compareWithTheManual(Path manual) throws IOException {
        String text = Files.readString(manual, StandardCharsets.UTF_8);
        int start = text.indexOf("\n## Compatibility\n");
        if (start < 0) {
            problems.add("no section Compatibility in the manual");
            return;
        }
        int end = text.indexOf("\n## ", start + 1);
        Map<String, String> rows = new TreeMap<>();
        for (String line : text.substring(start, end < 0 ? text.length() : end).split("\n")) {
            Matcher m = ROW.matcher(line);
            if (m.matches()) {
                Set<String> names = new TreeSet<>();
                Matcher n = Pattern.compile("`([\\w.]+)`").matcher(m.group(3));
                while (n.find()) {
                    names.add(n.group(1));
                }
                rows.put(m.group(1), m.group(2).toUpperCase() + " " + names);
            }
        }
        Map<String, String> expected = new TreeMap<>();
        packages.forEach((pkg, tier) -> {
            Set<String> names = new TreeSet<>();
            overrides.keySet().stream().filter(t -> packageOf(t).equals(pkg))
                    .forEach(t -> names.add(t.substring(pkg.length() + 1)));
            expected.put(pkg, tier.name() + " " + names);
        });
        for (Map.Entry<String, String> e : expected.entrySet()) {
            if (!e.getValue().equals(rows.get(e.getKey()))) {
                problems.add("the manual lists " + e.getKey() + " as " + rows.get(e.getKey()) + ", not "
                        + e.getValue());
            }
        }
        for (String pkg : rows.keySet()) {
            if (!expected.containsKey(pkg)) {
                problems.add("the manual lists " + pkg + ", which is no package");
            }
        }
    }
}
