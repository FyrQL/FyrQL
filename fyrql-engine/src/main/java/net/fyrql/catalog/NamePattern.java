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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A pattern for the names of sources, schemas, tables and columns, as in
 * the metadata of JDBC: {@code %} stands for any number of characters,
 * {@code _} for one, and {@code \} makes the character after it stand for
 * itself. A pattern without a text matches every name.
 * <p>
 * Names are case-insensitive as in the {@link NameResolver}, while sources
 * may tell case apart: {@link #select} keeps the names that match exactly
 * if there are any, and otherwise those that match ignoring case.
 */
public final class NamePattern {

    /** The pattern that matches every name. */
    public static final NamePattern ANY = new NamePattern(null, null, null);

    private final String text;
    private final Pattern exact;
    private final Pattern ignoringCase;

    private NamePattern(String text, Pattern exact, Pattern ignoringCase) {
        this.text = text;
        this.exact = exact;
        this.ignoringCase = ignoringCase;
    }

    /** Returns the pattern of a text with wildcards, or {@link #ANY} for {@code null}. */
    public static NamePattern of(String pattern) {
        if (pattern == null) {
            return ANY;
        }
        StringBuilder regex = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\\' && i + 1 < pattern.length()) {
                literal.append(pattern.charAt(++i));
            } else if (c == '%' || c == '_') {
                flush(literal, regex);
                regex.append(c == '%' ? ".*" : ".");
            } else {
                literal.append(c);
            }
        }
        flush(literal, regex);
        return compile(pattern, regex.toString());
    }

    /** Returns the pattern of a name without wildcards, or {@link #ANY} for {@code null}. */
    public static NamePattern name(String name) {
        return name == null ? ANY : compile(name, Pattern.quote(name));
    }

    private static NamePattern compile(String text, String regex) {
        return new NamePattern(text, Pattern.compile(regex, Pattern.DOTALL),
                Pattern.compile(regex, Pattern.DOTALL | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
    }

    private static void flush(StringBuilder literal, StringBuilder regex) {
        if (!literal.isEmpty()) {
            regex.append(Pattern.quote(literal.toString()));
            literal.setLength(0);
        }
    }

    /** Tells whether the pattern matches every name. */
    public boolean isAny() {
        return text == null;
    }

    /** Tells whether a name matches the pattern ignoring case. */
    public boolean matches(String name) {
        return text == null || ignoringCase.matcher(name).matches();
    }

    /**
     * Returns the names that match the pattern exactly, or, if there are
     * none, those that match it ignoring case, in their order.
     */
    public List<String> select(List<String> names) {
        if (text == null) {
            return List.copyOf(names);
        }
        List<String> exactly = new ArrayList<>();
        List<String> anyCase = new ArrayList<>();
        for (String n : names) {
            if (exact.matcher(n).matches()) {
                exactly.add(n);
            } else if (ignoringCase.matcher(n).matches()) {
                anyCase.add(n);
            }
        }
        return exactly.isEmpty() ? anyCase : exactly;
    }

    /** Returns the text of the pattern, or {@code %} for {@link #ANY}. */
    @Override
    public String toString() {
        return text == null ? "%" : text;
    }
}
