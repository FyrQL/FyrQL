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

package net.fyrql.pgwire;

import net.fyrql.core.type.DataType;
import net.fyrql.pgwire.SqlText.Kind;
import net.fyrql.pgwire.SqlText.Token;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Rewrites the forms of PostgreSQL that the engine, which follows DuckDB,
 * does not take into forms it takes, on the tokens of the text: what is
 * between the tokens, such as comments, stays as it is. The rules leave
 * every text alone that the engine takes already, except for {@code ~},
 * which matches a part of a string in PostgreSQL and the whole string in
 * DuckDB. Three passes run one after the other: names, casts and other
 * forms of single tokens; the operators {@code ~}, {@code = ANY} and
 * their kin, which need the extent of their operands; and the forms of
 * {@code FROM}.
 */
final class PgSql {

    private PgSql() {
    }

    /** What the rules need to know of the connection. */
    interface Context {
        /** The user of the connection. */
        String user();

        /** The text of {@code version()}. */
        String version();

        /**
         * The schemas of the search path that exist, in its order, with
         * {@code pg_catalog} first if {@code implicit} and the path does not
         * name it.
         */
        List<String> schemas(boolean implicit);

        /** The first schema of the search path that exists, as {@code current_schema} gives it, or {@code null}. */
        default String currentSchema() {
            List<String> schemas = schemas(false);
            return schemas.isEmpty() ? null : schemas.get(0);
        }

        /** The value of a parameter the server holds, or {@code null} if it holds none of the name. */
        String setting(String name);

        /**
         * Returns the OID of a table by its name, of one or two parts as
         * {@code ::regclass} reads it, along the search path.
         *
         * @throws PgException {@code 42P01} if there is no such table
         */
        int relation(String name);

        /** The number of the backend, as {@code pg_backend_pid()} gives it. */
        int backend();
    }

    /** Returns the name of a schema as a literal, or a {@code NULL} of its type. */
    private static String schema(String name) {
        return name == null ? "CAST(NULL AS VARCHAR)" : literal(name);
    }

    /** Returns a text as a string literal of SQL. */
    static String literal(String s) {
        return s == null ? "NULL" : "'" + s.replace("'", "''") + "'";
    }

    /** A replacement of the tokens from one to another, both included. */
    private record Edit(int from, int to, String text) {
    }

    private static String apply(String sql, List<Token> t, List<Edit> edits) {
        if (edits.isEmpty()) {
            return sql;
        }
        edits.sort((a, b) -> Integer.compare(a.from(), b.from()));
        StringBuilder b = new StringBuilder(sql.length() + 32);
        int copied = 0;
        int lastToken = -1;
        for (Edit e : edits) {
            if (e.from() <= lastToken) {
                continue;
            }
            b.append(sql, copied, t.get(e.from()).start()).append(e.text());
            copied = t.get(e.to()).end();
            lastToken = e.to();
        }
        return b.append(sql, copied, sql.length()).toString();
    }

    /** Returns the text of tokens from one to another, both included, with what is between them. */
    private static String text(String sql, List<Token> t, int from, int to) {
        return sql.substring(t.get(from).start(), t.get(to).end());
    }

    /**
     * Rewrites a statement.
     *
     * @throws SqlText.UnterminatedException if a string or a comment has no end
     * @throws PgException if a name of {@code ::regclass} names no table
     */
    static String rewrite(String sql, Context context) {
        String s = psql(sql);
        s = emptySubqueries(s);
        s = tokens(s, context);
        // An operator inside the operand of another, as a cast to regclass inside one, is rewritten in a round of
        // its own, since a round keeps only the outer of two edits that overlap.
        for (int round = 0; round < 8; round++) {
            String rewritten = operators(s);
            if (rewritten.equals(s)) {
                break;
            }
            s = rewritten;
        }
        return from(s);
    }

    // --------------------------------------------------------- psql

    /** {@code array(select 'toast.' || x from unnest(tc.reloptions) x)} of {@code \\d} in psql. */
    private static final Pattern PREFIXED = Pattern.compile(
            "array\\(\\s*select\\s+'([^']*)'\\s*\\|\\|\\s*(\\w+)\\s+from\\s+(?:pg_catalog\\.)?unnest\\(([\\w.]+)\\)"
                    + "\\s+\\2\\s*\\)", Pattern.CASE_INSENSITIVE);

    /** The names of the roles of a policy, in {@code \\d} of psql. */
    private static final Pattern ROLES = Pattern.compile(
            "array\\(\\s*select\\s+rolname\\s+from\\s+(?:pg_catalog\\.)?pg_roles\\s+where\\s+oid\\s*=\\s*any\\s*"
                    + "\\(([\\w.]+)\\)\\s+order\\s+by\\s+1\\s*\\)", Pattern.CASE_INSENSITIVE);

    /** The names of the columns of a publication, in {@code \\d} of psql. */
    private static final Pattern ATTRIBUTES = Pattern.compile(
            "\\(\\s*SELECT\\s+string_agg\\(attname,\\s*', '\\)\\s+FROM\\s+(?:pg_catalog\\.)?generate_series\\(0,\\s*"
                    + "(?:pg_catalog\\.)?array_upper\\(([\\w.]+)(?:::(?:pg_catalog\\.)?int2\\[\\])?,\\s*1\\)\\)"
                    + "\\s+s,\\s+(?:pg_catalog\\.)?pg_attribute\\s+WHERE\\s+attrelid\\s*=\\s*([\\w.]+)"
                    + "\\s+AND\\s+attnum\\s*=\\s*\\w+\\[s\\]\\s*\\)", Pattern.CASE_INSENSITIVE);

    /**
     * Rewrites three subqueries of {@code \\d} in psql that correlate in a way
     * the engine does not take into calls of functions of the server with the
     * same meaning: a list with a prefix on each element, the names of roles
     * and the names of columns by their numbers.
     */
    private static String psql(String sql) {
        if (!sql.contains("unnest") && !sql.contains("pg_roles") && !sql.contains("prattrs")) {
            return sql;
        }
        String s = PREFIXED.matcher(sql).replaceAll("prefix_elements($3, '$1')");
        s = ROLES.matcher(s).replaceAll("role_names($1)");
        return ATTRIBUTES.matcher(s).replaceAll("attribute_names($2, $1)");
    }

    // ------------------------------------------------------- tokens

    private static final Set<String> TEXT_CASTS = Set.of("name", "char", "cstring", "regproc", "regprocedure",
            "regnamespace", "regrole", "regoper", "regoperator", "pg_node_tree", "aclitem", "unknown");

    /** The keywords that the engine does not take as the label of a column, unless in quotes. */
    private static final Set<String> LABELS = Set.of("SELECT", "INSERT", "UPDATE", "DELETE", "TABLE", "FROM",
            "WHERE", "GROUP", "ORDER", "LIMIT", "OFFSET", "UNION", "ALL", "DISTINCT", "COLUMN", "USER", "ROLE",
            "SCHEMA", "CHECK", "DEFAULT", "PRIMARY", "REFERENCES", "WINDOW", "WITH", "ANALYZE", "DO", "END", "ONLY",
            "TO", "INTO", "IN", "IS", "ON", "OR", "AND", "NOT", "NULL", "TRUE", "FALSE", "CASE", "WHEN", "THEN", "ELSE",
            "ARRAY", "LEFT", "RIGHT", "JOIN", "CROSS", "FULL", "INNER", "OUTER", "NATURAL", "USING", "HAVING",
            "FETCH", "FOR", "CAST", "COLLATE", "CONSTRAINT", "CREATE", "GRANT", "LATERAL", "LIKE", "ILIKE",
            "LOCALTIME", "LOCALTIMESTAMP", "PLACING", "RETURNING", "SOME", "ANY", "VARIADIC", "ASC", "DESC", "AS",
            "BOTH", "LEADING", "TRAILING", "BETWEEN", "OVER", "FILTER", "VALUES", "SET", "TYPE", "KEY", "LEVEL");

    /** Tells whether the AS at a token is that of a CAST, after which a type follows. */
    private static boolean insideCast(List<Token> t, int as) {
        int depth = 0;
        for (int j = as - 1; j >= 0; j--) {
            if (t.get(j).isSymbol(")")) {
                depth++;
            } else if (t.get(j).isSymbol("(")) {
                if (depth == 0) {
                    return j > 0 && t.get(j - 1).is("CAST", "TRY_CAST");
                }
                depth--;
            }
        }
        return false;
    }

    /**
     * The fields of the patterns of {@code to_date} and {@code to_timestamp}, in any case, those that start alike
     * longest first, with their forms in strptime.
     */
    private static final List<String[]> PATTERN_FIELDS = List.of(new String[] {"HH24", "%H"},
            new String[] {"HH12", "%I"}, new String[] {"HH", "%I"}, new String[] {"YYYY", "%Y"},
            new String[] {"YY", "%y"}, new String[] {"MONTH", "%B"}, new String[] {"MON", "%b"},
            new String[] {"MM", "%m"}, new String[] {"MI", "%M"}, new String[] {"MS", "%g"}, new String[] {"DD", "%d"},
            new String[] {"SS", "%S"}, new String[] {"US", "%f"}, new String[] {"AM", "%p"}, new String[] {"PM", "%p"});

    /**
     * Rewrites {@code to_date(text, pattern)} and {@code to_timestamp(text, pattern)} of PostgreSQL, as Superset
     * writes the bounds of a time range, into {@code strptime} with the pattern in its form, a date for
     * {@code to_date}. A pattern with a field strptime does not have stays as it is.
     *
     * @return whether the call is rewritten
     */
    private static boolean parse(String sql, List<Token> t, int i, List<Edit> edits) {
        int close = closing(t, i + 1);
        if (close < 4 || t.get(close - 1).kind() != Kind.STRING || !t.get(close - 2).isSymbol(",")) {
            return false;
        }
        for (int j = i + 2, depth = 0; j < close - 2; j++) {
            if (t.get(j).isSymbol("(")) {
                depth++;
            } else if (t.get(j).isSymbol(")")) {
                depth--;
            } else if (depth == 0 && t.get(j).isSymbol(",")) {
                return false;
            }
        }
        String pattern = t.get(close - 1).value();
        StringBuilder format = new StringBuilder();
        for (int p = 0; p < pattern.length(); ) {
            String[] field = null;
            for (String[] f : PATTERN_FIELDS) {
                if (pattern.regionMatches(true, p, f[0], 0, f[0].length())) {
                    field = f;
                    break;
                }
            }
            char c = pattern.charAt(p);
            if (field != null) {
                format.append(field[1]);
                p += field[0].length();
            } else if (Character.isLetterOrDigit(c)) {
                return false;
            } else {
                format.append(c == '%' ? "%%" : String.valueOf(c));
                p++;
            }
        }
        String parsed = "strptime(" + text(sql, t, i + 2, close - 3) + ", " + literal(format.toString()) + ")";
        boolean date = t.get(i).is("TO_DATE");
        edits.add(named(t, i, close, date ? "CAST(" + parsed + " AS DATE)" : parsed,
                date ? "to_date" : "to_timestamp"));
        return true;
    }

    /** The catalogs that have no rows, as FyrQL has no sequences. */
    private static final Set<String> EMPTY_CATALOGS = Set.of("PG_SEQUENCE");

    /**
     * Replaces a scalar subquery that reads first from a catalog without
     * rows by {@code NULL}, its value, as the one with which SQLAlchemy
     * reads the identity of a column from {@code pg_sequence}; such a
     * subquery may reference the outer query where FyrQL does not take it.
     * A count is left alone, as it is 0.
     */
    private static String emptySubqueries(String sql) {
        List<Token> t = SqlText.tokens(sql);
        List<Edit> edits = new ArrayList<>();
        for (int i = 0; i + 1 < t.size(); i++) {
            if (!t.get(i).isSymbol("(") || !t.get(i + 1).is("SELECT")
                    || i > 0 && t.get(i - 1).is("EXISTS", "IN", "ANY", "SOME", "ALL", "ARRAY")) {
                continue;
            }
            int close = closing(t, i);
            int from = -1;
            boolean counts = false;
            for (int j = i + 1, depth = 0; close > 0 && j < close && from < 0; j++) {
                Token k = t.get(j);
                if (k.isSymbol("(")) {
                    depth++;
                } else if (k.isSymbol(")")) {
                    depth--;
                } else if (depth == 0 && k.is("FROM")) {
                    from = j;
                } else if (k.is("COUNT")) {
                    counts = true;
                }
            }
            if (from < 0 || counts) {
                continue;
            }
            int name = from + 1;
            if (name + 2 < close && t.get(name).is("PG_CATALOG") && t.get(name + 1).isSymbol(".")) {
                name += 2;
            }
            if (name < close && t.get(name).kind() == Kind.WORD && EMPTY_CATALOGS.contains(t.get(name).upper())) {
                edits.add(new Edit(i, close, "NULL"));
                i = close;
            }
        }
        return apply(sql, t, edits);
    }

    private static String tokens(String sql, Context context) {
        List<Token> t = SqlText.tokens(sql);
        List<Edit> edits = new ArrayList<>();
        for (int i = 0; i < t.size(); i++) {
            Token k = t.get(i);
            Token previous = i > 0 ? t.get(i - 1) : null;
            // A name after pg_catalog. counts as not qualified, as the qualifier goes away.
            boolean qualified = previous != null && previous.isSymbol(".")
                    && !(i >= 2 && t.get(i - 2).is("PG_CATALOG"));
            boolean call = i + 1 < t.size() && t.get(i + 1).isSymbol("(");
            boolean qualifies = i + 1 < t.size() && t.get(i + 1).isSymbol(".");
            boolean bare = k.kind() == Kind.WORD && !qualified && !qualifies && !call;
            boolean label = i + 1 == t.size() || t.get(i + 1).isSymbol(",") || t.get(i + 1).isSymbol(")")
                    || t.get(i + 1).is("FROM");
            if (k.kind() == Kind.WORD && previous != null && previous.is("AS") && LABELS.contains(k.upper()) && label
                    && !insideCast(t, i - 1)) {
                // PostgreSQL takes any keyword as the label of a column after AS; the engine takes it in quotes.
                edits.add(new Edit(i, i, "\"" + k.value() + "\""));
            } else if (bare && k.is("CURRENT_USER", "SESSION_USER", "USER", "CURRENT_ROLE")) {
                edits.add(named(t, i, i, literal(context.user()), k.value()));
            } else if (bare && k.is("CURRENT_CATALOG")) {
                edits.add(named(t, i, i, "current_database()", k.value()));
            } else if (bare && k.is("CURRENT_SCHEMA")) {
                edits.add(named(t, i, i, schema(context.currentSchema()), k.value()));
            } else if (call && !qualified && k.is("CURRENT_SCHEMA") && i + 2 < t.size()
                    && t.get(i + 2).isSymbol(")")) {
                edits.add(named(t, i, i + 2, schema(context.currentSchema()), "current_schema"));
            } else if (k.is("PG_CATALOG", "INFORMATION_SCHEMA") && !qualified && qualifies && i + 3 < t.size()
                    && t.get(i + 2).kind() == Kind.WORD && t.get(i + 3).isSymbol("(")) {
                // A function of pg_catalog is a function of the engine or of the server.
                edits.add(new Edit(i, i + 1, ""));
            } else if (k.is("PG_CATALOG") && previous != null && (previous.isSymbol("::") || previous.is("AS"))
                    && qualifies) {
                edits.add(new Edit(i, i + 1, ""));
            } else if (call && !qualified && k.is("VERSION") && i + 2 < t.size() && t.get(i + 2).isSymbol(")")) {
                edits.add(named(t, i, i + 2, literal(context.version()), "version"));
            } else if (call && !qualified && k.is("PG_BACKEND_PID") && i + 2 < t.size()
                    && t.get(i + 2).isSymbol(")")) {
                edits.add(named(t, i, i + 2, Integer.toString(context.backend()), "pg_backend_pid"));
            } else if (call && !qualified && k.is("CURRENT_SCHEMAS") && i + 3 < t.size()
                    && t.get(i + 3).isSymbol(")")) {
                edits.add(named(t, i, i + 3, list(context.schemas(!t.get(i + 2).is("FALSE"))), "current_schemas"));
            } else if (call && !qualified && k.is("CURRENT_SETTING") && i + 3 < t.size()
                    && t.get(i + 2).kind() == Kind.STRING) {
                currentSetting(t, i, context, edits);
            } else if (call && !qualified && k.is("TO_REGCLASS") && i + 3 < t.size()
                    && t.get(i + 2).kind() == Kind.STRING && t.get(i + 3).isSymbol(")")) {
                Integer oid;
                try {
                    oid = context.relation(t.get(i + 2).value());
                } catch (PgException e) {
                    oid = null;
                }
                edits.add(named(t, i, i + 3, oid == null ? "CAST(NULL AS INTEGER)" : oid.toString(), "to_regclass"));
            } else if (call && !qualified && k.is("TO_DATE", "TO_TIMESTAMP") && parse(sql, t, i, edits)) {
                i = closing(t, i + 1);
            } else if (call && !qualified && k.is("GENERATE_SUBSCRIPTS") && closing(t, i + 1) > 0) {
                // The arrays that clients number are those of pg_index, as SQLAlchemy reads the keys of a table
                // with; it has no rows, as FyrQL has no indexes.
                int close = closing(t, i + 1);
                edits.add(named(t, i, close, "CAST(NULL AS INTEGER)", "generate_subscripts"));
                i = close;
            } else if (k.is("COLLATE") && i + 1 < t.size()) {
                int end = i + 1;
                while (end + 2 < t.size() && t.get(end + 1).isSymbol(".")) {
                    end += 2;
                }
                edits.add(new Edit(i, end, ""));
                i = end;
            } else if (k.isSymbol("::") && i + 1 < t.size()) {
                i = cast(sql, t, i, context, edits);
            } else if (k.is("CAST") && call && i + 5 < t.size() && t.get(i + 2).kind() == Kind.STRING
                    && t.get(i + 3).is("AS") && t.get(i + 5).isSymbol(")")
                    && t.get(i + 4).is("REGCLASS", "REGTYPE", "REGPROC", "OID")) {
                String value = regLiteral(t.get(i + 4).value(), t.get(i + 2).value(), context);
                edits.add(named(t, i, i + 5, value, t.get(i + 4).value()));
                i += 5;
            } else if (k.is("CAST") && call) {
                castAs(t, i, edits);
            } else if (k.kind() == Kind.STRING && (k.text().startsWith("E") || k.text().startsWith("e"))
                    && k.text().contains("\\'")) {
                edits.add(new Edit(i, i, escapedQuotes(k.text())));
            } else if (k.kind() == Kind.STRING && arrayText(k) && (previous != null && comparison(previous)
                    || i + 1 < t.size() && comparison(t.get(i + 1)))) {
                // An array literal compared with a list, as '{0}' in psql.
                edits.add(new Edit(i, i, listLiteral(k.value())));
            } else if (k.kind() == Kind.DOLLAR) {
                // The engine reads strings in $$ but not in $tag$.
                edits.add(new Edit(i, i, literal(k.value())));
            } else if (k.kind() == Kind.QUOTED && call && !qualified) {
                // A function name in quotes, such as "substring"(...), names the function in any case.
                edits.add(new Edit(i, i, k.value()));
            }
        }
        return apply(sql, t, edits);
    }

    private static void currentSetting(List<Token> t, int i, Context context, List<Edit> edits) {
        // The parameters of the server are constants; those of the engine stay for it.
        String name = t.get(i + 2).value();
        int close = t.get(i + 3).isSymbol(")") ? i + 3
                : i + 5 < t.size() && t.get(i + 3).isSymbol(",") && t.get(i + 5).isSymbol(")") ? i + 5 : -1;
        if (close < 0) {
            return;
        }
        if (engineSetting(name)) {
            edits.add(named(t, i, close, "current_setting(" + literal(name) + ")", "current_setting"));
            return;
        }
        String value = context.setting(name);
        if (value != null || close == i + 5 && t.get(i + 4).is("TRUE")) {
            edits.add(named(t, i, close, literal(value), "current_setting"));
        }
    }

    /** The clauses before which the items of a select list end. */
    private static final Set<String> AFTER_ITEM = Set.of("FROM", "INTO", "WHERE", "GROUP", "HAVING", "WINDOW", "ORDER",
            "LIMIT", "OFFSET", "FETCH", "UNION", "INTERSECT", "EXCEPT");

    /** The words before which no item of a select list stands at the same depth. */
    private static final Set<String> NOT_IN_ITEMS = Set.of("FROM", "WHERE", "BY", "HAVING", "WINDOW", "LIMIT",
            "OFFSET", "ON", "USING", "JOIN", "VALUES", "SET", "RETURNING", "INTO", "PARTITION");

    /** The label PostgreSQL gives an item it cannot name, such as an operator. */
    private static final String UNNAMED = "?column?";

    /**
     * Replaces the tokens from {@code from} to {@code to} with a text, which
     * gets the label PostgreSQL gives the expression when it is a whole item
     * of a select list, rather than the text of the engine.
     */
    private static Edit named(List<Token> t, int from, int to, String text, String label) {
        return new Edit(from, to, selectItem(t, from, to) ? text + " AS \"" + label + "\"" : text);
    }

    /** Tells whether the tokens from {@code from} to {@code to} are an item of a select list without a label. */
    private static boolean selectItem(List<Token> t, int from, int to) {
        if (from >= 2 && t.get(from - 1).isSymbol(".") && t.get(from - 2).is("PG_CATALOG")) {
            from -= 2;
        }
        if (to + 1 < t.size()) {
            Token next = t.get(to + 1);
            if (!next.isSymbol(",") && !next.isSymbol(")") && !next.isSymbol(";")
                    && !(next.kind() == Kind.WORD && AFTER_ITEM.contains(next.upper()))) {
                return false;
            }
        }
        if (from == 0 || !(t.get(from - 1).isSymbol(",") || t.get(from - 1).is("SELECT", "DISTINCT"))) {
            return false;
        }
        int depth = 0;
        for (int j = from - 1; j >= 0; j--) {
            Token k = t.get(j);
            if (k.isSymbol(")")) {
                depth++;
            } else if (k.isSymbol("(")) {
                if (depth == 0) {
                    return false;
                }
                depth--;
            } else if (depth == 0 && k.is("SELECT")) {
                return true;
            } else if (depth == 0 && k.kind() == Kind.WORD && NOT_IN_ITEMS.contains(k.upper())) {
                return false;
            }
        }
        return false;
    }

    /**
     * Tells whether the engine holds a setting of the name. The search path
     * is the one of the connection, in the names of PostgreSQL, as
     * {@code SHOW} gives it.
     */
    private static boolean engineSetting(String name) {
        return name.equalsIgnoreCase(ServerSettings.TIME_ZONE)
                || name.equalsIgnoreCase(ServerSettings.STATEMENT_TIMEOUT);
    }

    /**
     * Rewrites the cast at {@code ::}: a string as a {@code regclass},
     * {@code regtype} or {@code regproc} becomes the value it names, an
     * array literal a list; the types of PostgreSQL that FyrQL does not
     * have become the types that hold their values.
     *
     * @return the last token rewritten
     */
    private static int cast(String sql, List<Token> t, int i, Context context, List<Edit> edits) {
        if (i == 0) {
            return i;
        }
        int typeAt = i + 1;
        if (t.get(typeAt).is("PG_CATALOG") && typeAt + 2 < t.size() && t.get(typeAt + 1).isSymbol(".")) {
            typeAt += 2;
        }
        Token type = t.get(typeAt);
        String name = type.kind() == Kind.QUOTED || type.kind() == Kind.WORD ? type.value() : null;
        if (name == null) {
            return i;
        }
        boolean array = typeAt + 2 < t.size() && t.get(typeAt + 1).isSymbol("[") && t.get(typeAt + 2).isSymbol("]");
        Token operand = t.get(i - 1);
        boolean literal = i > 0 && operand.kind() == Kind.STRING && !(i > 1 && t.get(i - 2).isSymbol("::"));
        if (literal && !array && Set.of("regclass", "regtype", "regproc", "oid").contains(name)) {
            edits.add(named(t, i - 1, typeAt, regLiteral(name, operand.value(), context), name));
            return typeAt;
        }
        if (literal && (array || name.startsWith("_"))) {
            String element = array ? name : name.substring(1);
            int last = array ? typeAt + 2 : typeAt;
            edits.add(new Edit(i - 1, last, arrayLiteral(operand.value(), element)));
            return last;
        }
        if (array) {
            if (typeAt != i + 1) {
                edits.add(new Edit(i + 1, typeAt, text(sql, t, typeAt, typeAt)));
            }
            return typeAt;
        }
        if (name.equals("regclass")) {
            if (typeAt != i + 1) {
                edits.add(new Edit(i + 1, typeAt, "regclass"));
            }
            return typeAt;
        }
        String replacement = switch (name) {
            case "regtype", "oid", "xid", "cid" -> "INTEGER";
            case "int2vector" -> "SMALLINT[]";
            case "oidvector" -> "INTEGER[]";
            default -> TEXT_CASTS.contains(name) ? "VARCHAR" : null;
        };
        if (replacement != null && !(name.equals("char") && typeAt + 1 < t.size() && t.get(typeAt + 1).isSymbol("("))) {
            edits.add(new Edit(i + 1, typeAt, replacement));
        } else if (typeAt != i + 1) {
            edits.add(new Edit(i + 1, typeAt, text(sql, t, typeAt, typeAt)));
        }
        return typeAt;
    }

    /**
     * Rewrites {@code CAST(x AS type)} of an expression to a type of
     * PostgreSQL that FyrQL does not have: to {@code regclass} as
     * {@code to_regclass(x)}, to the others as the type that holds their
     * values.
     */
    private static void castAs(List<Token> t, int i, List<Edit> edits) {
        int close = closing(t, i + 1);
        if (close < 0) {
            return;
        }
        int depth = 0;
        int as = -1;
        for (int j = i + 1; j < close; j++) {
            if (t.get(j).isSymbol("(")) {
                depth++;
            } else if (t.get(j).isSymbol(")")) {
                depth--;
            } else if (depth == 1 && t.get(j).is("AS")) {
                as = j;
            }
        }
        if (as < 0 || as + 1 >= close) {
            return;
        }
        int typeAt = as + 1;
        if (t.get(typeAt).is("PG_CATALOG") && typeAt + 2 < close && t.get(typeAt + 1).isSymbol(".")) {
            typeAt += 2;
        }
        if (typeAt != close - 1 || !isName(t.get(typeAt))) {
            return;
        }
        String name = t.get(typeAt).value();
        if (name.equals("regclass")) {
            edits.add(new Edit(i, i, "to_regclass"));
            edits.add(new Edit(as, typeAt, ""));
            return;
        }
        String replacement = switch (name) {
            case "regtype", "oid", "xid", "cid" -> "INTEGER";
            default -> TEXT_CASTS.contains(name) ? "VARCHAR" : null;
        };
        if (replacement != null) {
            edits.add(new Edit(as + 1, typeAt, replacement));
        }
    }

    /** The value a string names as a {@code regclass}, {@code regtype}, {@code regproc} or {@code oid}. */
    private static String regLiteral(String type, String value, Context context) {
        return switch (type) {
            case "regclass" -> {
                String v = value.strip();
                yield Integer.toString(v.matches("\\d+") ? Integer.parseInt(v) : context.relation(v));
            }
            case "regtype" -> {
                int oid = value.strip().matches("\\d+") ? Integer.parseInt(value.strip())
                        : PgCatalog.typeOid(stripSchema(value));
                if (oid == 0) {
                    throw new PgException("42704", "type \"" + value + "\" does not exist");
                }
                yield Integer.toString(oid);
            }
            case "regproc" -> literal(stripSchema(value));
            default -> value.strip().matches("-?\\d+") ? value.strip() : literal(value);
        };
    }

    private static String stripSchema(String name) {
        String n = name.strip();
        return n.toLowerCase(Locale.ROOT).startsWith("pg_catalog.") ? n.substring("pg_catalog.".length()) : n;
    }

    /** An array literal of PostgreSQL such as {@code '{a,b}'} as a list of FyrQL. */
    private static String arrayLiteral(String text, String element) {
        List<Object> values = PgTypes.parseArray(text, DataType.VARCHAR, ZoneOffset.UTC);
        StringBuilder b = new StringBuilder("CAST([");
        for (int i = 0; i < values.size(); i++) {
            b.append(i > 0 ? ", " : "").append(values.get(i) == null ? "NULL" : literal((String) values.get(i)));
        }
        String type = switch (element) {
            case "text", "varchar", "name", "char", "bpchar", "regproc" -> "VARCHAR";
            case "int2" -> "SMALLINT";
            case "int4", "oid", "regclass", "regtype" -> "INTEGER";
            case "int8" -> "BIGINT";
            case "float4" -> "FLOAT";
            case "float8" -> "DOUBLE";
            case "bool" -> "BOOLEAN";
            default -> element;
        };
        return b.append("] AS ").append(type).append("[])").toString();
    }

    private static boolean arrayText(Token k) {
        String v = k.value().strip();
        return v.startsWith("{") && v.endsWith("}") && !(k.text().startsWith("E") || k.text().startsWith("e"));
    }

    private static boolean comparison(Token k) {
        return k.isSymbol("=") || k.isSymbol("<>") || k.isSymbol("!=");
    }

    /** An array literal of PostgreSQL as a list literal, of numbers if all its elements are numbers. */
    private static String listLiteral(String text) {
        List<Object> values = PgTypes.parseArray(text, DataType.VARCHAR, ZoneOffset.UTC);
        boolean numbers = values.stream().allMatch(v -> v == null || ((String) v).matches("-?\\d+(\\.\\d+)?"));
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            Object v = values.get(i);
            b.append(i > 0 ? ", " : "").append(v == null ? "NULL" : numbers ? v : literal((String) v));
        }
        return b.append(']').toString();
    }

    private static String list(List<String> values) {
        StringBuilder b = new StringBuilder("CAST([");
        for (int i = 0; i < values.size(); i++) {
            b.append(i > 0 ? ", " : "").append(literal(values.get(i)));
        }
        return b.append("] AS VARCHAR[])").toString();
    }

    /** Writes a quote after a backslash in {@code E'...'} as two quotes, which the parser of the engine takes. */
    static String escapedQuotes(String token) {
        StringBuilder b = new StringBuilder(token.length());
        b.append(token, 0, 2);
        for (int i = 2; i < token.length() - 1; i++) {
            char c = token.charAt(i);
            if (c == '\\' && i + 1 < token.length() - 1) {
                char x = token.charAt(++i);
                if (x == '\'') {
                    b.append("''");
                } else {
                    b.append(c).append(x);
                }
            } else {
                b.append(c);
            }
        }
        return b.append('\'').toString();
    }

    // ---------------------------------------------------- operators

    /**
     * Rewrites {@code a ~ b} into {@code regexp_matches(a, b)}, as
     * PostgreSQL matches a part of the string, also for
     * {@code OPERATOR(pg_catalog.~)}, {@code !~}, {@code ~*} and {@code !~*};
     * {@code a = ANY(list)} into {@code list_contains(list, a)} and
     * {@code a <> ALL(list)} into its negation, unless the list is a query.
     */
    private static String operators(String sql) {
        List<Token> t = SqlText.tokens(sql);
        List<Edit> edits = new ArrayList<>();
        for (int i = 1; i < t.size() - 1; i++) {
            Token k = t.get(i);
            String op = null;
            int opEnd = i;
            if (k.kind() == Kind.OPERATOR && Set.of("~", "!~", "~*", "!~*").contains(k.text())) {
                op = k.text();
            } else if (k.is("OPERATOR") && i + 5 < t.size() && t.get(i + 1).isSymbol("(")
                    && t.get(i + 2).is("PG_CATALOG") && t.get(i + 3).isSymbol(".")
                    && t.get(i + 4).kind() == Kind.OPERATOR && t.get(i + 5).isSymbol(")")) {
                op = t.get(i + 4).text();
                opEnd = i + 5;
                if (!Set.of("~", "!~", "~*", "!~*", "=", "<>", "<", ">", "<=", ">=").contains(op)) {
                    continue;
                }
                if (!op.contains("~")) {
                    edits.add(new Edit(i, opEnd, op));
                    i = opEnd;
                    continue;
                }
            }
            if ((k.isSymbol("&") || k.isSymbol("|")) && t.get(i - 1).kind() != Kind.OPERATOR) {
                int left = operandStart(t, i - 1);
                int right = operandEnd(t, i + 1);
                if (left >= 0 && right >= 0) {
                    edits.add(named(t, left, right, (k.isSymbol("&") ? "bitwise_and(" : "bitwise_or(")
                            + text(sql, t, left, i - 1) + ", " + text(sql, t, i + 1, right) + ")", UNNAMED));
                    i = right;
                }
                continue;
            }
            if (k.isSymbol("::") && t.get(i + 1).is("REGCLASS")) {
                int left = operandStart(t, i - 1);
                if (left >= 0) {
                    // PostgreSQL names the cast of a column by the column, and that of anything else by its type.
                    String label = isQualifiedName(t, left, i - 1) ? t.get(i - 1).value() : "regclass";
                    edits.add(named(t, left, i + 1, "to_regclass(" + text(sql, t, left, i - 1) + ")", label));
                    i++;
                }
                continue;
            }
            if (op != null) {
                int left = operandStart(t, i - 1);
                int right = operandEnd(t, opEnd + 1);
                if (left < 0 || right < 0) {
                    continue;
                }
                String l = text(sql, t, left, i - 1);
                String r = text(sql, t, opEnd + 1, right);
                String match = "regexp_matches(" + l + ", " + r + (op.contains("*") ? ", 'i')" : ")");
                edits.add(named(t, left, right, op.startsWith("!") ? "(NOT " + match + ")" : match, UNNAMED));
                i = right;
                continue;
            }
            boolean any = k.isSymbol("=") && i + 2 < t.size() && t.get(i + 1).is("ANY", "SOME")
                    && t.get(i + 2).isSymbol("(");
            boolean all = (k.isSymbol("<>") || k.isSymbol("!=")) && i + 2 < t.size() && t.get(i + 1).is("ALL")
                    && t.get(i + 2).isSymbol("(");
            if ((any || all) && !(i + 3 < t.size() && t.get(i + 3).is("SELECT", "VALUES", "WITH"))) {
                int left = operandStart(t, i - 1);
                int close = closing(t, i + 2);
                if (left < 0 || close < 0) {
                    continue;
                }
                String l = text(sql, t, left, i - 1);
                String list = text(sql, t, i + 3, close - 1);
                String contains = "list_contains(" + list + ", " + l + ")";
                edits.add(named(t, left, close, all ? "(NOT " + contains + ")" : contains, UNNAMED));
                i = close;
            }
        }
        return apply(sql, t, edits);
    }

    /** Returns the token that closes the parenthesis or bracket at a token, or -1. */
    private static int closing(List<Token> t, int open) {
        String o = t.get(open).text();
        String c = o.equals("(") ? ")" : "]";
        int depth = 0;
        for (int i = open; i < t.size(); i++) {
            if (t.get(i).isSymbol(o)) {
                depth++;
            } else if (t.get(i).isSymbol(c) && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /** Returns the token that opens the parenthesis or bracket closed at a token, or -1. */
    private static int opening(List<Token> t, int close) {
        String c = t.get(close).text();
        String o = c.equals(")") ? "(" : "[";
        int depth = 0;
        for (int i = close; i >= 0; i--) {
            if (t.get(i).isSymbol(c)) {
                depth++;
            } else if (t.get(i).isSymbol(o) && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isName(Token k) {
        return k.kind() == Kind.WORD || k.kind() == Kind.QUOTED;
    }

    /** Tells whether the tokens from {@code from} to {@code to} are a name with qualifiers, as {@code c.oid}. */
    private static boolean isQualifiedName(List<Token> t, int from, int to) {
        for (int j = from; j <= to; j++) {
            if ((j - from) % 2 == 0 ? !isName(t.get(j)) : !t.get(j).isSymbol(".")) {
                return false;
            }
        }
        return (to - from) % 2 == 0;
    }

    private static final Set<String> KEYWORDS = Set.of("AND", "OR", "NOT", "WHERE", "ON", "WHEN", "THEN", "ELSE",
            "SELECT", "CASE", "IS", "IN", "LIKE", "BETWEEN", "HAVING", "AS", "BY", "FROM", "JOIN", "USING");

    /** Returns the first token of the operand that ends at a token, or -1. */
    private static int operandStart(List<Token> t, int end) {
        if (end < 0) {
            return -1;
        }
        Token k = t.get(end);
        int start;
        if (k.isSymbol(")") || k.isSymbol("]")) {
            start = opening(t, end);
            if (start < 0) {
                return -1;
            }
            if (start > 0 && isName(t.get(start - 1)) && !KEYWORDS.contains(t.get(start - 1).upper())) {
                start--;
            }
        } else if (k.kind() == Kind.STRING || k.kind() == Kind.NUMBER || k.kind() == Kind.PARAMETER
                || k.kind() == Kind.DOLLAR || isName(k) && !KEYWORDS.contains(k.upper())) {
            start = end;
        } else {
            return -1;
        }
        while (start >= 2 && t.get(start - 1).isSymbol(".") && isName(t.get(start - 2))) {
            start -= 2;
        }
        if (start >= 2 && t.get(start - 1).isSymbol("::")) {
            return operandStart(t, start - 2);
        }
        return start;
    }

    /** Returns the last token of the operand that starts at a token, or -1. */
    private static int operandEnd(List<Token> t, int start) {
        if (start >= t.size()) {
            return -1;
        }
        Token k = t.get(start);
        int end;
        if (k.isSymbol("(")) {
            end = closing(t, start);
        } else if (k.isSymbol("-") || k.isSymbol("+")) {
            end = operandEnd(t, start + 1);
        } else if (isName(k) && !KEYWORDS.contains(k.upper())) {
            end = start;
            while (end + 2 < t.size() && t.get(end + 1).isSymbol(".") && isName(t.get(end + 2))) {
                end += 2;
            }
            if (end + 1 < t.size() && t.get(end + 1).isSymbol("(")) {
                end = closing(t, end + 1);
            }
        } else if (k.kind() == Kind.STRING || k.kind() == Kind.NUMBER || k.kind() == Kind.PARAMETER
                || k.kind() == Kind.DOLLAR) {
            end = start;
        } else {
            return -1;
        }
        while (end >= 0 && end + 2 < t.size() && t.get(end + 1).isSymbol("::") && isName(t.get(end + 2))) {
            end += 2;
            if (end + 2 < t.size() && t.get(end + 1).isSymbol("[") && t.get(end + 2).isSymbol("]")) {
                end += 2;
            }
        }
        return end;
    }

    // --------------------------------------------------------- FROM

    /**
     * Rewrites the functions in {@code FROM} that return sets in PostgreSQL:
     * {@code generate_series(a, b) AS s(n)} unnests the list of the engine,
     * {@code string_to_array} and {@code parse_ident} are a row of their
     * value, {@code pg_get_keywords()} is the table of the server, and
     * {@code _pg_expandarray} of {@code information_schema}, which pgjdbc
     * reads the keys of indexes with, gives no rows, as FyrQL has no indexes.
     */
    private static String from(String sql) {
        List<Token> t = SqlText.tokens(sql);
        List<Edit> edits = new ArrayList<>();
        for (int i = 1; i < t.size(); i++) {
            Token k = t.get(i);
            boolean inFrom = t.get(i - 1).is("FROM", "JOIN") || t.get(i - 1).isSymbol(",") && inFromList(t, i - 1);
            int nameAt = i;
            if (inFrom && k.is("PG_CATALOG", "INFORMATION_SCHEMA") && i + 2 < t.size() && t.get(i + 1).isSymbol(".")) {
                nameAt = i + 2;
            }
            Token name = t.get(nameAt);
            boolean call = nameAt + 1 < t.size() && t.get(nameAt + 1).isSymbol("(");
            if (inFrom && call && name.is("GENERATE_SERIES")) {
                int close = closing(t, nameAt + 1);
                if (close > 0) {
                    // An alias without columns names the column as well, as in PostgreSQL.
                    int aliasAt = close + 1 < t.size() && t.get(close + 1).is("AS") ? close + 2 : close + 1;
                    boolean alias = aliasAt < t.size() && isName(t.get(aliasAt)) && !KEYWORDS.contains(
                            t.get(aliasAt).upper()) && !t.get(aliasAt).is("WHERE", "GROUP", "ORDER", "LIMIT", "UNION")
                            && !(aliasAt + 1 < t.size() && t.get(aliasAt + 1).isSymbol("("));
                    if (alias) {
                        edits.add(new Edit(i, aliasAt, "unnest(" + text(sql, t, nameAt, close) + ") AS "
                                + t.get(aliasAt).text() + "(" + t.get(aliasAt).text() + ")"));
                        i = aliasAt;
                    } else {
                        edits.add(new Edit(i, close, "unnest(" + text(sql, t, nameAt, close) + ")"));
                        i = close;
                    }
                }
            } else if (inFrom && call && name.is("STRING_TO_ARRAY", "PARSE_IDENT")) {
                int close = closing(t, nameAt + 1);
                if (close > 0) {
                    // A function of one value in FROM is a relation of one row, its column named as the relation.
                    int aliasAt = close + 1 < t.size() && t.get(close + 1).is("AS") ? close + 2 : close + 1;
                    boolean alias = aliasAt < t.size() && isName(t.get(aliasAt)) && !KEYWORDS.contains(
                            t.get(aliasAt).upper()) && !t.get(aliasAt).is("WHERE", "GROUP", "ORDER", "LIMIT", "UNION")
                            && !(aliasAt + 1 < t.size() && t.get(aliasAt + 1).isSymbol("("));
                    String relation = alias ? t.get(aliasAt).text() : name.value();
                    edits.add(new Edit(i, alias ? aliasAt : close, "(SELECT " + text(sql, t, nameAt, close) + " AS "
                            + relation + ") AS " + relation));
                    i = alias ? aliasAt : close;
                }
            } else if (k.is("ARRAY") && i + 2 < t.size() && t.get(i + 1).isSymbol("(") && t.get(i + 2).is("SELECT")) {
                int close = closing(t, i + 1);
                String list = close < 0 ? null : arrayOfQuery(sql, t, i + 2, close);
                if (list != null) {
                    edits.add(named(t, i, close, list, "array"));
                    i = close;
                }
            } else if (inFrom && call && name.is("PG_PARTITION_ANCESTORS")) {
                // FyrQL has no partitions: a table has no ancestors here, and the relation is empty.
                int close = closing(t, nameAt + 1);
                int last = close;
                if (close > 0 && close + 2 < t.size() && t.get(close + 1).is("WITH")
                        && t.get(close + 2).is("ORDINALITY")) {
                    last = close + 2;
                }
                if (close > 0) {
                    edits.add(new Edit(i, last, "(SELECT CAST(NULL AS INTEGER) AS relid, CAST(NULL AS BIGINT) AS depth"
                            + " WHERE false)"));
                    i = last;
                }
            } else if (inFrom && call && name.is("PG_GET_KEYWORDS")) {
                int close = closing(t, nameAt + 1);
                if (close > 0) {
                    edits.add(new Edit(i, close, "pg_catalog.pg_get_keywords"));
                    i = close;
                }
            } else if (call && name.is("_PG_EXPANDARRAY")) {
                int start = nameAt != i ? i : i > 1 && t.get(i - 1).isSymbol(".") && t.get(i - 2).is(
                        "INFORMATION_SCHEMA") ? i - 2 : i;
                int close = closing(t, nameAt + 1);
                if (close < 0) {
                    continue;
                }
                boolean wrapped = start > 0 && t.get(start - 1).isSymbol("(") && close + 3 < t.size()
                        && t.get(close + 1).isSymbol(")") && t.get(close + 2).isSymbol(".");
                if (wrapped) {
                    String field = t.get(close + 3).value();
                    edits.add(new Edit(start - 1, close + 3, field.equals("n") ? "CAST(NULL AS INTEGER)"
                            : "CAST(NULL AS SMALLINT)"));
                    i = close + 3;
                } else if (inFrom || start > 0 && t.get(start - 1).is("FROM", "JOIN")) {
                    edits.add(new Edit(start, close, "(SELECT CAST(NULL AS SMALLINT) AS x, CAST(NULL AS INTEGER) AS n"
                            + " WHERE false)"));
                    i = close;
                } else {
                    edits.add(new Edit(start, close, "CAST(NULL AS STRUCT(x SMALLINT, n INTEGER))"));
                    i = close;
                }
            } else if (k.isSymbol("(") && i + 4 < t.size() && isName(t.get(i + 1)) && t.get(i + 2).isSymbol(".")
                    && isName(t.get(i + 3)) && t.get(i + 4).isSymbol(")") && i + 6 < t.size()
                    && t.get(i + 5).isSymbol(".") && isName(t.get(i + 6))
                    && !(i > 0 && isName(t.get(i - 1)) && !KEYWORDS.contains(t.get(i - 1).upper()))) {
                // (result.keys).x, a field of a composite, is result.keys.x.
                edits.add(new Edit(i, i + 6, text(sql, t, i + 1, i + 3) + "." + t.get(i + 6).text()));
                i += 6;
            }
        }
        return apply(sql, t, edits);
    }

    /**
     * Rewrites {@code ARRAY(SELECT x FROM ... ORDER BY o)} into
     * {@code (SELECT list(x ORDER BY o) FROM ...)}, or returns {@code null}
     * for a query of another form.
     *
     * @param select the token {@code SELECT}
     * @param close the parenthesis that closes the query
     */
    private static String arrayOfQuery(String sql, List<Token> t, int select, int close) {
        int depth = 0;
        int from = -1;
        int order = -1;
        for (int j = select + 1; j < close; j++) {
            Token k = t.get(j);
            if (k.isSymbol("(")) {
                depth++;
            } else if (k.isSymbol(")")) {
                depth--;
            } else if (depth == 0 && from < 0 && k.is("FROM")) {
                from = j;
            } else if (depth == 0 && k.is("ORDER") && j + 1 < close && t.get(j + 1).is("BY")) {
                order = j;
            } else if (depth == 0 && k.is("UNION", "INTERSECT", "EXCEPT", "LIMIT", "GROUP")) {
                return null;
            }
        }
        if (from < 0) {
            return null;
        }
        String item = text(sql, t, select + 1, from - 1);
        String orderBy = "";
        int end = close - 1;
        if (order > 0) {
            String o = text(sql, t, order + 2, close - 1);
            orderBy = " ORDER BY " + (o.strip().equals("1") ? item : o);
            end = order - 1;
        }
        return "(SELECT list(" + item + orderBy + ") " + text(sql, t, from, end) + ")";
    }

    /** Tells whether a comma at a token separates the items of a {@code FROM}. */
    private static boolean inFromList(List<Token> t, int comma) {
        int depth = 0;
        for (int i = comma - 1; i >= 0; i--) {
            Token k = t.get(i);
            if (k.isSymbol(")")) {
                depth++;
            } else if (k.isSymbol("(")) {
                if (depth == 0) {
                    return false;
                }
                depth--;
            } else if (depth == 0 && k.is("FROM")) {
                return true;
            } else if (depth == 0 && k.is("SELECT", "WHERE", "GROUP", "ORDER", "HAVING", "SET", "VALUES")) {
                return false;
            }
        }
        return false;
    }

    // ---------------------------------------------------- parameters

    /**
     * Gives each parameter whose type the client names that type, as
     * {@code CAST($1 AS INTEGER)}, so that the statement binds as it would in
     * PostgreSQL; a parameter of a type the server leaves to the context,
     * one with a cast after it and one in {@code LIMIT} or {@code OFFSET}
     * stays as it is.
     *
     * @param oids the types of the parameters the client names, 0 for none
     */
    static String typeParameters(String sql, int[] oids) {
        DataType[] types = new DataType[oids.length];
        for (int i = 0; i < oids.length; i++) {
            types[i] = PgTypes.fyrqlType(oids[i]);
        }
        return typeParameters(sql, types);
    }

    /**
     * Gives parameters types, as {@link #typeParameters(String, int[])} does.
     *
     * @param types the type of each parameter, or {@code null} to leave it to the context
     */
    static String typeParameters(String sql, DataType[] types) {
        int[] oids = new int[types.length];
        for (int i = 0; i < types.length; i++) {
            oids[i] = types[i] == null ? 0 : 1;
        }
        boolean any = false;
        for (int oid : oids) {
            any |= oid != 0;
        }
        if (!any) {
            return sql;
        }
        List<Token> t = SqlText.tokens(sql);
        List<Edit> edits = new ArrayList<>();
        for (int i = 0; i < t.size(); i++) {
            Token k = t.get(i);
            if (k.kind() != Kind.PARAMETER) {
                continue;
            }
            int n;
            try {
                n = Integer.parseInt(k.text().substring(1));
            } catch (NumberFormatException e) {
                continue;
            }
            DataType type = n >= 1 && n <= types.length ? types[n - 1] : null;
            boolean cast = i + 1 < t.size() && t.get(i + 1).isSymbol("::");
            boolean limit = i > 0 && t.get(i - 1).is("LIMIT", "OFFSET");
            if (type == null || cast || limit) {
                continue;
            }
            edits.add(new Edit(i, i, "CAST(" + k.text() + " AS " + type.sqlName() + ")"));
        }
        return apply(sql, t, edits);
    }
}
