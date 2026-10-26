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

package net.fyrql.core.function;

import net.fyrql.core.expr.Expression;
import net.fyrql.core.expr.Expressions;
import net.fyrql.core.type.DataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The functions of regular expressions, after DuckDB 1.5.6, with the
 * regular expressions of Java instead of RE2: the syntax is mostly the
 * same, but Java also takes back references and lookaround, and RE2 runs in
 * linear time. {@code regexp_matches} finds the pattern anywhere in the
 * string, {@code regexp_full_match} matches the whole string, which is
 * also what {@code ~} does. The options are letters in a string:
 * {@code c} case-sensitive (the default), {@code i} case-insensitive,
 * {@code l} the pattern as a literal string, {@code s} a dot also matches
 * a line break, {@code m}, {@code n} and {@code p} it does not (the
 * default), and {@code g} for {@code regexp_replace} only, which replaces
 * every match instead of the first. The options, and the group of
 * {@code regexp_extract}, are constants, as DuckDB requires.
 */
final class RegexFunctions {

    /** The compiled patterns, by flags and text; cleared when it grows large. */
    private static final Map<String, Pattern> PATTERNS = new ConcurrentHashMap<>();
    private static final int MAX_PATTERNS = 256;

    private RegexFunctions() {
    }

    static List<ScalarFunction> all() {
        return List.of(MATCHES, FULL_MATCH, REPLACE, EXTRACT);
    }

    /** The options of a call: the flags of the pattern and whether it replaces every match. */
    private record Options(int flags, boolean global) {
    }

    private static Options options(String text, boolean replace) {
        int flags = 0;
        boolean global = false;
        if (text != null) {
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                switch (c) {
                    case 'c' -> flags &= ~(Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
                    case 'i' -> flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
                    case 'l' -> flags |= Pattern.LITERAL;
                    case 's' -> flags |= Pattern.DOTALL;
                    case 'm', 'n', 'p' -> flags &= ~Pattern.DOTALL;
                    case 'g' -> {
                        if (!replace) {
                            throw new IllegalArgumentException(
                                    "Option 'g' (global replace) is only valid for regexp_replace");
                        }
                        global = true;
                    }
                    default -> throw new IllegalArgumentException("Unrecognized Regex option " + c);
                }
            }
        }
        return new Options(flags, global);
    }

    private static Pattern pattern(String regex, int flags) {
        String key = flags + ":" + regex;
        Pattern p = PATTERNS.get(key);
        if (p == null) {
            try {
                p = Pattern.compile(regex, flags);
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException("Invalid regular expression: " + e.getDescription() + " in "
                        + regex, e);
            }
            if (PATTERNS.size() >= MAX_PATTERNS) {
                PATTERNS.clear();
            }
            PATTERNS.put(key, p);
        }
        return p;
    }

    /**
     * Requires the argument at a position to be a constant, as DuckDB
     * requires the options and the group, and checks options.
     *
     * @throws IllegalArgumentException if it is no constant, or no valid options
     */
    private static void constant(List<Expression> arguments, int at, String what, boolean replace) {
        if (at >= arguments.size()) {
            return;
        }
        Object value;
        try {
            value = Expressions.constantValue(arguments.get(at));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("The " + what + " of a regular expression must be a constant", e);
        }
        if (what.equals("options") && value != null) {
            options((String) value, replace);
        }
        // As DuckDB, which reads at most nine groups, checks the group before it reads a string, NULL too.
        if (what.equals("group") && value instanceof Number n && (n.longValue() < 0 || n.longValue() > 9)) {
            throw new IllegalArgumentException("Group index must be between 0 and 9!");
        }
    }

    /** Tells whether the types are {@code VARCHAR}, from a minimum to a maximum count. */
    private static boolean varchars(List<DataType> types, int min, int max) {
        return types.size() >= min && types.size() <= max && SimpleFunction.varchars(types, types.size());
    }

    /** {@code regexp_matches(string, pattern [, options])}: whether the pattern matches a part of the string. */
    static final SimpleFunction MATCHES = new SimpleFunction("regexp_matches",
            types -> varchars(types, 2, 3) ? DataType.BOOLEAN : null) {
        @Override
        public ScalarFunction specialize(List<Expression> arguments) {
            constant(arguments, 2, "options", false);
            return this;
        }

        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            Options o = options(args.length > 2 ? (String) args[2] : null, false);
            return pattern((String) args[1], o.flags()).matcher((String) args[0]).find();
        }
    };

    /** {@code regexp_full_match(string, pattern [, options])}: whether the pattern matches the whole string. */
    static final SimpleFunction FULL_MATCH = new SimpleFunction("regexp_full_match",
            types -> varchars(types, 2, 3) ? DataType.BOOLEAN : null) {
        @Override
        public ScalarFunction specialize(List<Expression> arguments) {
            constant(arguments, 2, "options", false);
            return this;
        }

        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            Options o = options(args.length > 2 ? (String) args[2] : null, false);
            return pattern((String) args[1], o.flags()).matcher((String) args[0]).matches();
        }
    };

    /**
     * {@code regexp_replace(string, pattern, replacement [, options])}: the
     * string with the first match, or with {@code g} every match, replaced.
     * In the replacement, a backslash and a digit stand for that group,
     * {@code \0} for the whole match, and two backslashes for one. As in
     * RE2, an empty match right after a match is not replaced.
     */
    static final SimpleFunction REPLACE = new SimpleFunction("regexp_replace",
            types -> varchars(types, 3, 4) ? DataType.VARCHAR : null) {
        @Override
        public ScalarFunction specialize(List<Expression> arguments) {
            constant(arguments, 3, "options", true);
            return this;
        }

        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            Options o = options(args.length > 3 ? (String) args[3] : null, true);
            String s = (String) args[0];
            String replacement = (String) args[2];
            Matcher m = pattern((String) args[1], o.flags()).matcher(s);
            StringBuilder b = new StringBuilder(s.length());
            int from = 0;
            int lastEnd = -1;
            while (m.find()) {
                if (m.start() == m.end() && m.start() == lastEnd) {
                    continue;
                }
                b.append(s, from, m.start());
                rewrite(b, m, replacement);
                from = m.end();
                lastEnd = m.end();
                if (!o.global()) {
                    break;
                }
            }
            return b.append(s, from, s.length()).toString();
        }
    };

    /** Appends a replacement with its groups, see {@link #REPLACE}. */
    private static void rewrite(StringBuilder b, Matcher m, String replacement) {
        int n = replacement.length();
        for (int i = 0; i < n; i++) {
            char c = replacement.charAt(i);
            if (c != '\\' || i + 1 == n) {
                b.append(c);
                continue;
            }
            char x = replacement.charAt(++i);
            if (x >= '0' && x <= '9') {
                int group = x - '0';
                if (group > m.groupCount()) {
                    throw new IllegalArgumentException("The replacement asks for group " + group
                            + ", but the regular expression has " + m.groupCount());
                }
                String value = m.group(group);
                if (value != null) {
                    b.append(value);
                }
            } else {
                b.append(x);
            }
        }
    }

    /**
     * {@code regexp_extract(string, pattern [, group [, options]])}: the
     * group of the first match, by default the whole match; the empty
     * string if nothing matches or the pattern has no such group. The
     * group is a constant from 0 to 9, as in DuckDB.
     */
    static final SimpleFunction EXTRACT = new SimpleFunction("regexp_extract",
            types -> types.size() == 2 && SimpleFunction.varchars(types, 2)
                    || types.size() >= 3 && types.size() <= 4 && types.get(0) == DataType.VARCHAR
                    && types.get(1) == DataType.VARCHAR && SimpleFunction.isInt32(types.get(2))
                    && (types.size() == 3 || types.get(3) == DataType.VARCHAR) ? DataType.VARCHAR : null,
            types -> {
                List<DataType> result = new ArrayList<>(types);
                if (result.size() > 2) {
                    result.set(2, DataType.INTEGER);
                }
                return result;
            }) {
        @Override
        public ScalarFunction specialize(List<Expression> arguments) {
            constant(arguments, 2, "group", false);
            constant(arguments, 3, "options", false);
            return this;
        }

        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            int group = args.length > 2 ? (Integer) args[2] : 0;
            Options o = options(args.length > 3 ? (String) args[3] : null, false);
            Matcher m = pattern((String) args[1], o.flags()).matcher((String) args[0]);
            if (group < 0) {
                throw new IllegalArgumentException("Group index must be non-negative");
            }
            if (!m.find() || group > m.groupCount()) {
                return "";
            }
            String value = m.group(group);
            return value == null ? "" : value;
        }
    };
}
