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

package net.fyrql.connector.odata;

import net.fyrql.core.type.DataType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * A {@code $filter} of OData as a fake service evaluates it over rows in
 * the representation of FyrQL: comparisons by {@code eq ne lt le gt ge},
 * where {@code null} equals only itself and is never less or greater,
 * {@code and}, {@code or}, {@code not}, parentheses, the functions
 * {@code startswith}, {@code endswith}, and {@code contains} of version 4
 * or {@code substringof} of version 2, and the literals of the version.
 * It is strict as real services are: a literal must be of the kind its
 * property compares with ({@code datetime'...'} for {@code Edm.DateTime},
 * {@code 1.5M} for {@code Edm.Decimal} of version 2, no prefixes and
 * suffixes in version 4), and every property must exist and allow
 * filtering. A service may compare strings without case or ignore spaces
 * at their end, as some databases do.
 */
final class FakeFilter {

    private static final Pattern GUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern DATE_TIME = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}:\\d{2})");
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?[LlMmDdFf]?");
    private static final Pattern PATH = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(/[A-Za-z_][A-Za-z0-9_]*)*");

    /** A property of a row: its type in FyrQL and in OData, and how to read its value from a row. */
    record Column(DataType type, String edm, java.util.function.Function<Object[], Object> value) {
    }

    /** Resolves the path of a property to its column. */
    interface Resolver {
        /**
         * @throws IllegalArgumentException if there is no such property or the service does not filter by it
         */
        Column column(String path);
    }

    /** How a service compares strings. */
    record Quirks(boolean caseInsensitive, boolean ignoreTrailingSpaces) {
        static final Quirks NONE = new Quirks(false, false);
    }

    private enum Kind {
        NULL, BOOLEAN, NUMBER, STRING, DATE, DATE_TIME, DATE_TIME_OFFSET, GUID
    }

    /** A node of a filter. */
    private sealed interface Node permits Literal, Property, Compare, Logical, Not, Function {
    }

    /** A literal: its kind, its value and, of a number, its suffix or 0. */
    private record Literal(Kind kind, Object value, char suffix) implements Node {
    }

    private record Property(String path, Column column) implements Node {
    }

    private record Compare(String op, Node left, Node right) implements Node {
    }

    private record Logical(boolean and, Node left, Node right) implements Node {
    }

    private record Not(Node operand) implements Node {
    }

    private record Function(String name, Node string, Node text) implements Node {
    }

    private final ODataVersion version;
    private final Resolver resolver;
    private final Quirks quirks;
    private final List<String> tokens;
    private int at;

    private FakeFilter(String text, ODataVersion version, Resolver resolver, Quirks quirks) {
        this.version = version;
        this.resolver = resolver;
        this.quirks = quirks;
        this.tokens = tokens(text);
    }

    /**
     * Reads a filter.
     *
     * @throws IllegalArgumentException if it is no filter of the version over the properties
     */
    static Predicate<Object[]> parse(String text, ODataVersion version, Resolver resolver, Quirks quirks) {
        FakeFilter f = new FakeFilter(text, version, resolver, quirks);
        Node node = f.or();
        if (f.at < f.tokens.size()) {
            throw new IllegalArgumentException("Unexpected " + f.tokens.get(f.at) + " in the filter " + text);
        }
        if (!(node instanceof Compare || node instanceof Logical || node instanceof Not || node instanceof Function
                || node instanceof Literal l && l.kind() == Kind.BOOLEAN
                || node instanceof Property p && p.column().type() == DataType.BOOLEAN)) {
            throw new IllegalArgumentException("The filter " + text + " is not a condition");
        }
        return row -> Boolean.TRUE.equals(f.evaluate(node, row));
    }

    private static List<String> tokens(String text) {
        List<String> tokens = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == ' ') {
                i++;
            } else if (c == '(' || c == ')' || c == ',') {
                tokens.add(String.valueOf(c));
                i++;
            } else {
                int start = i;
                while (i < text.length() && " (),'".indexOf(text.charAt(i)) < 0) {
                    i++;
                }
                if (i < text.length() && text.charAt(i) == '\'') {
                    // A string, or a literal with a prefix such as datetime'...'.
                    i++;
                    while (true) {
                        if (i >= text.length()) {
                            throw new IllegalArgumentException("A string does not end in the filter " + text);
                        }
                        if (text.charAt(i) == '\'') {
                            if (i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                                i += 2;
                                continue;
                            }
                            i++;
                            break;
                        }
                        i++;
                    }
                }
                tokens.add(text.substring(start, i));
            }
        }
        return tokens;
    }

    private String peek() {
        return at < tokens.size() ? tokens.get(at) : null;
    }

    private String next() {
        if (at >= tokens.size()) {
            throw new IllegalArgumentException("The filter ends early");
        }
        return tokens.get(at++);
    }

    private void expect(String token) {
        String t = next();
        if (!t.equals(token)) {
            throw new IllegalArgumentException("Expected " + token + " instead of " + t);
        }
    }

    private Node or() {
        Node left = and();
        while ("or".equals(peek())) {
            next();
            left = new Logical(false, left, and());
        }
        return left;
    }

    private Node and() {
        Node left = not();
        while ("and".equals(peek())) {
            next();
            left = new Logical(true, left, not());
        }
        return left;
    }

    private Node not() {
        if ("not".equals(peek())) {
            next();
            return new Not(not());
        }
        return comparison();
    }

    private Node comparison() {
        Node left = primary();
        String op = peek();
        if (op != null && Set.of("eq", "ne", "lt", "le", "gt", "ge").contains(op)) {
            next();
            Node right = primary();
            check(op, left, right);
            check(op, right, left);
            return new Compare(op, left, right);
        }
        return left;
    }

    /** Checks that a property compares with a literal of its kind. */
    private void check(String op, Node a, Node b) {
        if (!(a instanceof Property p) || !(b instanceof Literal l)) {
            return;
        }
        if (l.kind() == Kind.NULL) {
            if (!op.equals("eq") && !op.equals("ne")) {
                throw new IllegalArgumentException("The property " + p.path() + " compares with null by " + op);
            }
            return;
        }
        String edm = p.column().edm();
        boolean v2 = version == ODataVersion.V2;
        boolean fits = switch (edm) {
            case "Edm.Boolean" -> l.kind() == Kind.BOOLEAN;
            case "Edm.SByte", "Edm.Byte", "Edm.Int16", "Edm.Int32" -> l.kind() == Kind.NUMBER && l.suffix() == 0
                    && integral(l);
            case "Edm.Int64" -> l.kind() == Kind.NUMBER && integral(l) && (l.suffix() == 0
                    || v2 && Character.toUpperCase(l.suffix()) == 'L');
            case "Edm.Decimal" -> l.kind() == Kind.NUMBER && (v2 ? Character.toUpperCase(l.suffix()) == 'M'
                    || l.suffix() == 0 && integral(l) : l.suffix() == 0);
            case "Edm.String" -> l.kind() == Kind.STRING;
            case "Edm.Guid" -> l.kind() == Kind.GUID;
            case "Edm.Date" -> l.kind() == Kind.DATE;
            case "Edm.DateTime" -> l.kind() == Kind.DATE_TIME;
            case "Edm.DateTimeOffset" -> l.kind() == Kind.DATE_TIME_OFFSET;
            default -> false;
        };
        if (!fits) {
            throw new IllegalArgumentException("The property " + p.path() + " of the type " + edm
                    + " does not compare with the literal " + l.value() + (l.suffix() == 0 ? "" : "" + l.suffix())
                    + " of the kind " + l.kind());
        }
    }

    private static boolean integral(Literal l) {
        BigDecimal d = (BigDecimal) l.value();
        return d.stripTrailingZeros().scale() <= 0;
    }

    private Node primary() {
        String t = next();
        if (t.equals("(")) {
            Node inner = or();
            expect(")");
            return inner;
        }
        if (Set.of("startswith", "endswith", "contains", "substringof").contains(t) && "(".equals(peek())) {
            if (version == ODataVersion.V2 ? t.equals("contains") : t.equals("substringof")) {
                throw new IllegalArgumentException("No function " + t + " in version " + version);
            }
            next();
            Node first = primary();
            expect(",");
            Node second = primary();
            expect(")");
            Node string = t.equals("substringof") ? second : first;
            Node text = t.equals("substringof") ? first : second;
            if (!(string instanceof Property p) || !p.column().edm().equals("Edm.String")
                    || !(text instanceof Literal l) || l.kind() != Kind.STRING) {
                throw new IllegalArgumentException("The function " + t + " takes a string property and a string");
            }
            return new Function(t, string, text);
        }
        return literalOrProperty(t);
    }

    private Node literalOrProperty(String t) {
        boolean v2 = version == ODataVersion.V2;
        switch (t) {
            case "null" -> {
                return new Literal(Kind.NULL, null, (char) 0);
            }
            case "true", "false" -> {
                return new Literal(Kind.BOOLEAN, Boolean.parseBoolean(t), (char) 0);
            }
            default -> {
                // Handled below.
            }
        }
        int quote = t.indexOf('\'');
        if (quote >= 0) {
            if (!t.endsWith("'") || t.length() < quote + 2) {
                throw new IllegalArgumentException("Not a literal: " + t);
            }
            String prefix = t.substring(0, quote);
            String body = t.substring(quote + 1, t.length() - 1);
            switch (prefix) {
                case "" -> {
                    return new Literal(Kind.STRING, body.replace("''", "'"), (char) 0);
                }
                case "datetime" -> {
                    requireV2(t);
                    return new Literal(Kind.DATE_TIME, micros(LocalDateTime.parse(body)), (char) 0);
                }
                case "datetimeoffset" -> {
                    requireV2(t);
                    return new Literal(Kind.DATE_TIME_OFFSET, offset(body), (char) 0);
                }
                case "guid" -> {
                    requireV2(t);
                    return new Literal(Kind.GUID, UUID.fromString(body), (char) 0);
                }
                default -> throw new IllegalArgumentException("Not a literal: " + t);
            }
        }
        if (GUID.matcher(t).matches()) {
            requireV4(t);
            return new Literal(Kind.GUID, UUID.fromString(t), (char) 0);
        }
        if (DATE.matcher(t).matches()) {
            requireV4(t);
            return new Literal(Kind.DATE, (int) LocalDate.parse(t).toEpochDay(), (char) 0);
        }
        if (DATE_TIME.matcher(t).matches()) {
            requireV4(t);
            return new Literal(Kind.DATE_TIME_OFFSET, offset(t), (char) 0);
        }
        if (NUMBER.matcher(t).matches()) {
            char last = t.charAt(t.length() - 1);
            boolean suffixed = Character.isLetter(last) && last != 'e' && last != 'E';
            if (suffixed && !v2) {
                throw new IllegalArgumentException("Version 4 has no suffix of numbers: " + t);
            }
            return new Literal(Kind.NUMBER, new BigDecimal(suffixed ? t.substring(0, t.length() - 1) : t),
                    suffixed ? last : (char) 0);
        }
        if (PATH.matcher(t).matches() && !Set.of("and", "or", "not", "eq", "ne", "lt", "le", "gt", "ge")
                .contains(t)) {
            return new Property(t, resolver.column(t));
        }
        throw new IllegalArgumentException("Not a literal or property: " + t);
    }

    private void requireV2(String t) {
        if (version != ODataVersion.V2) {
            throw new IllegalArgumentException("Version 4 has no literal " + t);
        }
    }

    private void requireV4(String t) {
        if (version != ODataVersion.V4) {
            throw new IllegalArgumentException("Version 2 has no literal " + t);
        }
    }

    private static long micros(LocalDateTime t) {
        return t.toEpochSecond(ZoneOffset.UTC) * 1_000_000L + t.getNano() / 1000;
    }

    private static long offset(String text) {
        try {
            OffsetDateTime t = OffsetDateTime.parse(text);
            return micros(t.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Not a date and time with an offset: " + text, e);
        }
    }

    /** Evaluates a node over a row: a value, {@code null}, or a {@code Boolean} for a condition. */
    private Object evaluate(Node node, Object[] row) {
        return switch (node) {
            case Literal l -> l.value();
            case Property p -> value(p.column(), p.column().value().apply(row));
            case Not n -> !Boolean.TRUE.equals(evaluate(n.operand(), row));
            case Logical l -> l.and()
                    ? Boolean.TRUE.equals(evaluate(l.left(), row)) && Boolean.TRUE.equals(evaluate(l.right(), row))
                    : Boolean.TRUE.equals(evaluate(l.left(), row)) || Boolean.TRUE.equals(evaluate(l.right(), row));
            case Compare c -> compare(c.op(), evaluate(c.left(), row), evaluate(c.right(), row));
            case Function f -> {
                Object s = evaluate(f.string(), row);
                Object t = evaluate(f.text(), row);
                if (s == null || t == null) {
                    yield null;
                }
                String a = fold((String) s);
                String b = fold((String) t);
                yield switch (f.name()) {
                    case "startswith" -> a.startsWith(b);
                    case "endswith" -> a.endsWith(b);
                    default -> a.contains(b);
                };
            }
        };
    }

    /** Returns a value of a property in the form literals have: numbers as decimals. */
    private static Object value(Column column, Object v) {
        if (v == null) {
            return null;
        }
        return switch (v) {
            case Byte b -> BigDecimal.valueOf(b);
            case Short s -> BigDecimal.valueOf(s);
            case Integer i when column.type() != DataType.DATE -> BigDecimal.valueOf(i);
            case Long l when column.type() != DataType.TIMESTAMP -> BigDecimal.valueOf(l);
            default -> v;
        };
    }

    private String fold(String s) {
        return quirks.caseInsensitive() ? s.toLowerCase(Locale.ROOT) : s;
    }

    private String trim(String s) {
        if (!quirks.ignoreTrailingSpaces()) {
            return s;
        }
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == ' ') {
            end--;
        }
        return s.substring(0, end);
    }

    @SuppressWarnings({"unchecked"})
    private Object compare(String op, Object a, Object b) {
        if (op.equals("eq") || op.equals("ne")) {
            boolean equal;
            if (a == null || b == null) {
                equal = a == null && b == null;
            } else if (a instanceof String x && b instanceof String y) {
                equal = fold(trim(x)).equals(fold(trim(y)));
            } else if (a instanceof BigDecimal x && b instanceof BigDecimal y) {
                equal = x.compareTo(y) == 0;
            } else {
                equal = a.equals(b);
            }
            return op.equals("eq") == equal;
        }
        if (a == null || b == null) {
            return false;
        }
        int c;
        if (a instanceof String x && b instanceof String y) {
            c = fold(x).compareTo(fold(y));
        } else if (a instanceof Comparable x && a.getClass() == b.getClass()) {
            c = x.compareTo(b);
        } else {
            throw new IllegalArgumentException("Cannot compare " + a + " with " + b);
        }
        return switch (op) {
            case "lt" -> c < 0;
            case "le" -> c <= 0;
            case "gt" -> c > 0;
            default -> c >= 0;
        };
    }
}
