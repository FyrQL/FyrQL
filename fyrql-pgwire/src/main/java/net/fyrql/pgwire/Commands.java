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

import net.fyrql.pgwire.SqlText.Kind;
import net.fyrql.pgwire.SqlText.Token;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The statements the server answers itself, read from the tokens of a
 * statement: transactions, {@code SET}, {@code RESET} and {@code SHOW},
 * cursors, {@code DISCARD} and {@code DEALLOCATE}, and those it refuses,
 * such as {@code COPY ... FROM STDIN}. Every other statement goes to the
 * engine.
 */
final class Commands {

    private Commands() {
    }

    /** A statement as the server runs it. */
    sealed interface Command {
    }

    /** A statement for the engine. */
    record Engine(String sql) implements Command {
    }

    enum TransactionOp {
        BEGIN, COMMIT, ROLLBACK, SAVEPOINT, RELEASE, ROLLBACK_TO
    }

    /** {@code BEGIN}, {@code COMMIT}, {@code ROLLBACK} and their kin, with the tag of their answer. */
    record Transaction(TransactionOp op, String tag) implements Command {
    }

    /**
     * {@code SET name TO value}, also {@code SET TIME ZONE} and the forms of
     * the characteristics of transactions, or {@code RESET name} with a
     * value of {@code null}.
     *
     * @param values the values, several for a list such as {@code search_path}
     * @param local whether it is {@code SET LOCAL}
     */
    record Set(String name, List<Token> values, boolean local, String tag) implements Command {
    }

    /** {@code RESET ALL}. */
    record ResetAll() implements Command {
    }

    /** {@code SHOW name} or {@code SHOW ALL}, with the name as PostgreSQL spells it. */
    record Show(String name) implements Command {
    }

    /** {@code DECLARE name CURSOR FOR query}. */
    record Declare(String name, String query, boolean hold) implements Command {
    }

    /**
     * {@code FETCH} or {@code MOVE} forward.
     *
     * @param count the rows, {@link Long#MAX_VALUE} for all
     */
    record Fetch(String name, long count, boolean move) implements Command {
    }

    /** {@code CLOSE name}, or {@code CLOSE ALL} with a name of {@code null}. */
    record Close(String name) implements Command {
    }

    /** {@code DISCARD ALL} and its kin, with their tag. */
    record Discard(String tag) implements Command {
    }

    /** {@code DEALLOCATE name}, or {@code DEALLOCATE ALL} with a name of {@code null}. */
    record Deallocate(String name) implements Command {
    }

    /** A statement that has no effect in FyrQL, answered with its tag. */
    record Ignored(String tag) implements Command {
    }

    /** A statement the server refuses, with the reason. */
    record Unsupported(String message) implements Command {
    }

    /**
     * Reads what a statement is.
     *
     * @throws SqlText.UnterminatedException if a string or a comment has no end
     * @throws PgException if a statement of the server is malformed
     */
    static Command parse(String sql) {
        List<Token> t = SqlText.tokens(sql);
        if (t.isEmpty()) {
            return new Engine(sql);
        }
        Token first = t.get(0);
        if (first.kind() != Kind.WORD) {
            return new Engine(sql);
        }
        return switch (first.upper()) {
            case "BEGIN" -> new Transaction(TransactionOp.BEGIN, "BEGIN");
            case "START" -> word(t, 1, "TRANSACTION") ? new Transaction(TransactionOp.BEGIN, "START TRANSACTION")
                    : new Engine(sql);
            case "COMMIT" -> word(t, 1, "PREPARED") ? unsupported("two-phase commit")
                    : new Transaction(TransactionOp.COMMIT, "COMMIT");
            case "END" -> new Transaction(TransactionOp.COMMIT, "COMMIT");
            case "ABORT" -> new Transaction(TransactionOp.ROLLBACK, "ROLLBACK");
            case "ROLLBACK" -> rollback(t);
            case "SAVEPOINT" -> new Transaction(TransactionOp.SAVEPOINT, "SAVEPOINT");
            case "RELEASE" -> new Transaction(TransactionOp.RELEASE, "RELEASE");
            case "PREPARE" -> word(t, 1, "TRANSACTION") ? unsupported("two-phase commit")
                    : unsupported("PREPARE in SQL; prepare statements with the extended protocol");
            case "EXECUTE" -> unsupported("EXECUTE in SQL; prepare statements with the extended protocol");
            case "SET" -> set(t, sql);
            case "RESET" -> reset(t);
            case "SHOW" -> show(t, sql);
            case "DECLARE" -> declare(t, sql);
            case "FETCH", "MOVE" -> fetch(t);
            case "CLOSE" -> t.size() > 1 && t.get(1).is("ALL") ? new Close(null) : new Close(name(t, 1, "CLOSE"));
            case "DISCARD" -> new Discard("DISCARD " + (t.size() > 1 ? t.get(1).upper() : "ALL"));
            case "DEALLOCATE" -> deallocate(t);
            case "UNLISTEN" -> new Ignored("UNLISTEN");
            case "LISTEN", "NOTIFY" -> unsupported(first.upper());
            case "VACUUM", "CHECKPOINT", "CLUSTER", "REINDEX", "LOCK", "GRANT", "REVOKE", "TRUNCATE", "COMMENT",
                    "SECURITY", "REFRESH", "IMPORT", "LOAD" -> unsupported(first.upper());
            case "ANALYZE", "ANALYSE" -> unsupported("ANALYZE");
            case "COPY" -> copy(t, sql);
            default -> new Engine(sql);
        };
    }

    private static Unsupported unsupported(String what) {
        return new Unsupported("Not supported in FyrQL: " + what);
    }

    private static boolean word(List<Token> t, int i, String... words) {
        return i < t.size() && t.get(i).is(words);
    }

    private static Command rollback(List<Token> t) {
        int i = 1;
        if (word(t, i, "WORK", "TRANSACTION")) {
            i++;
        }
        if (word(t, i, "TO")) {
            return new Transaction(TransactionOp.ROLLBACK_TO, "ROLLBACK");
        }
        if (word(t, i, "PREPARED")) {
            return unsupported("two-phase commit");
        }
        return new Transaction(TransactionOp.ROLLBACK, "ROLLBACK");
    }

    /** Returns a name at a token: a word or a quoted name, as PostgreSQL folds it. */
    private static String name(List<Token> t, int i, String statement) {
        if (i >= t.size() || t.get(i).kind() != Kind.WORD && t.get(i).kind() != Kind.QUOTED) {
            throw new PgException(PgException.SYNTAX_ERROR, "syntax error in " + statement + ": a name is missing");
        }
        return t.get(i).value();
    }

    private static Command set(List<Token> t, String sql) {
        int i = 1;
        boolean local = false;
        if (word(t, i, "SESSION") && !word(t, i + 1, "CHARACTERISTICS", "AUTHORIZATION")) {
            i++;
        } else if (word(t, i, "LOCAL")) {
            local = true;
            i++;
        }
        if (word(t, i, "SESSION") && word(t, i + 1, "CHARACTERISTICS")) {
            // SET SESSION CHARACTERISTICS AS TRANSACTION ...
            return transactionModes(t, i + 4, "default_");
        }
        if (word(t, i, "TRANSACTION")) {
            return word(t, i + 1, "SNAPSHOT") ? unsupported("SET TRANSACTION SNAPSHOT")
                    : transactionModes(t, i + 1, "");
        }
        if (word(t, i, "TIME") && word(t, i + 1, "ZONE")) {
            return new Set(ServerSettings.TIME_ZONE, values(t, i + 2), local, "SET");
        }
        if (word(t, i, "NAMES")) {
            return new Set(ServerSettings.CLIENT_ENCODING, values(t, i + 1), local, "SET");
        }
        if (word(t, i, "SCHEMA")) {
            return new Set(ServerSettings.SEARCH_PATH, values(t, i + 1), local, "SET");
        }
        if (word(t, i, "ROLE")) {
            return word(t, i + 1, "NONE") ? new Ignored("SET") : unsupported("SET ROLE");
        }
        if (word(t, i, "SESSION") && word(t, i + 1, "AUTHORIZATION")) {
            return word(t, i + 2, "DEFAULT") ? new Ignored("SET") : unsupported("SET SESSION AUTHORIZATION");
        }
        int nameStart = i;
        StringBuilder name = new StringBuilder(name(t, i++, "SET"));
        while (i + 1 < t.size() && t.get(i).isSymbol(".")) {
            name.append('.').append(name(t, i + 1, "SET"));
            i += 2;
        }
        if (i < t.size() && (t.get(i).is("TO") || t.get(i).isSymbol("="))) {
            i++;
        } else {
            throw new PgException(PgException.SYNTAX_ERROR, "syntax error at or near \""
                    + (i < t.size() ? t.get(i).text() : t.get(nameStart).text()) + "\" in SET");
        }
        return new Set(name.toString(), values(t, i), local, "SET");
    }

    /** The values of {@code SET}: none for {@code DEFAULT}, else each one between commas. */
    private static List<Token> values(List<Token> t, int from) {
        if (from >= t.size()) {
            throw new PgException(PgException.SYNTAX_ERROR, "syntax error in SET: a value is missing");
        }
        if (from == t.size() - 1 && t.get(from).is("DEFAULT", "LOCAL")) {
            return null;
        }
        List<Token> values = new ArrayList<>();
        for (int i = from; i < t.size(); i++) {
            Token v = t.get(i);
            if (v.isSymbol(",")) {
                continue;
            }
            if (v.isSymbol("-") && i + 1 < t.size() && t.get(i + 1).kind() == Kind.NUMBER) {
                Token n = t.get(++i);
                values.add(new Token(Kind.NUMBER, "-" + n.text(), v.start(), n.end()));
                continue;
            }
            values.add(v);
        }
        return values;
    }

    /** {@code SET [SESSION CHARACTERISTICS AS] TRANSACTION} with isolation levels and access modes. */
    private static Command transactionModes(List<Token> t, int from, String prefix) {
        List<Set> sets = new ArrayList<>();
        int i = from;
        while (i < t.size()) {
            if (t.get(i).isSymbol(",")) {
                i++;
            } else if (word(t, i, "ISOLATION") && word(t, i + 1, "LEVEL")) {
                i += 2;
                String level;
                if (word(t, i, "SERIALIZABLE")) {
                    level = "serializable";
                    i++;
                } else if (word(t, i, "READ", "REPEATABLE") && i + 1 < t.size()) {
                    level = (t.get(i).text() + " " + t.get(i + 1).text()).toLowerCase(Locale.ROOT);
                    i += 2;
                } else {
                    throw new PgException(PgException.SYNTAX_ERROR, "syntax error in ISOLATION LEVEL");
                }
                sets.add(new Set(prefix + "transaction_isolation", List.of(word(level)), false, "SET"));
            } else if (word(t, i, "READ") && word(t, i + 1, "ONLY", "WRITE")) {
                sets.add(new Set(prefix + "transaction_read_only",
                        List.of(word(t.get(i + 1).is("ONLY") ? "on" : "off")), false, "SET"));
                i += 2;
            } else if (word(t, i, "NOT") && word(t, i + 1, "DEFERRABLE")) {
                sets.add(new Set(prefix + "transaction_deferrable", List.of(word("off")), false, "SET"));
                i += 2;
            } else if (word(t, i, "DEFERRABLE")) {
                sets.add(new Set(prefix + "transaction_deferrable", List.of(word("on")), false, "SET"));
                i++;
            } else {
                throw new PgException(PgException.SYNTAX_ERROR, "syntax error at or near \"" + t.get(i).text()
                        + "\"");
            }
        }
        if (sets.isEmpty()) {
            throw new PgException(PgException.SYNTAX_ERROR, "syntax error in SET TRANSACTION");
        }
        // One statement sets each mode it names; the connection applies them in order.
        return sets.size() == 1 ? sets.get(0) : new Many(sets);
    }

    /** Several settings of one statement, such as an isolation level and an access mode. */
    record Many(List<Set> sets) implements Command {
    }

    private static Token word(String text) {
        return new Token(Kind.STRING, "'" + text.replace("'", "''") + "'", 0, 0);
    }

    private static Command reset(List<Token> t) {
        if (word(t, 1, "ALL")) {
            return new ResetAll();
        }
        if (word(t, 1, "TIME") && word(t, 2, "ZONE")) {
            return new Set(ServerSettings.TIME_ZONE, null, false, "RESET");
        }
        if (word(t, 1, "ROLE", "SESSION")) {
            return new Ignored("RESET");
        }
        StringBuilder name = new StringBuilder(name(t, 1, "RESET"));
        for (int i = 2; i + 1 < t.size() && t.get(i).isSymbol("."); i += 2) {
            name.append('.').append(name(t, i + 1, "RESET"));
        }
        return new Set(name.toString(), null, false, "RESET");
    }

    private static Command show(List<Token> t, String sql) {
        if (word(t, 1, "ALL")) {
            return new Show("ALL");
        }
        if (word(t, 1, "TIME") && word(t, 2, "ZONE")) {
            return new Show(ServerSettings.TIME_ZONE);
        }
        if (word(t, 1, "TRANSACTION") && word(t, 2, "ISOLATION") && word(t, 3, "LEVEL")) {
            return new Show("transaction_isolation");
        }
        if (word(t, 1, "SESSION") && word(t, 2, "AUTHORIZATION")) {
            return new Show("session_authorization");
        }
        if (word(t, 1, "TABLES", "DATABASES", "SCHEMAS", "COLUMNS")) {
            return new Engine(sql);
        }
        StringBuilder name = new StringBuilder(name(t, 1, "SHOW"));
        for (int i = 2; i + 1 < t.size() && t.get(i).isSymbol("."); i += 2) {
            name.append('.').append(name(t, i + 1, "SHOW"));
        }
        return new Show(name.toString());
    }

    private static Command declare(List<Token> t, String sql) {
        String name = name(t, 1, "DECLARE");
        boolean hold = false;
        int i = 2;
        while (i < t.size() && !t.get(i).is("FOR")) {
            Token w = t.get(i);
            if (w.is("SCROLL") && !(i > 2 && t.get(i - 1).is("NO"))) {
                return unsupported("cursors with SCROLL; FyrQL reads cursors forward only");
            }
            if (w.is("WITH") && word(t, i + 1, "HOLD")) {
                hold = true;
            }
            if (!w.is("BINARY", "INSENSITIVE", "ASENSITIVE", "NO", "SCROLL", "CURSOR", "WITH", "WITHOUT", "HOLD")) {
                throw new PgException(PgException.SYNTAX_ERROR, "syntax error at or near \"" + w.text()
                        + "\" in DECLARE");
            }
            i++;
        }
        if (i + 1 >= t.size()) {
            throw new PgException(PgException.SYNTAX_ERROR, "syntax error in DECLARE: FOR and a query are missing");
        }
        return new Declare(name, sql.substring(t.get(i + 1).start()), hold);
    }

    private static Command fetch(List<Token> t) {
        boolean move = t.get(0).is("MOVE");
        int i = 1;
        long count = 1;
        if (word(t, i, "NEXT")) {
            i++;
        } else if (word(t, i, "FORWARD")) {
            i++;
            if (word(t, i, "ALL")) {
                count = Long.MAX_VALUE;
                i++;
            } else if (i < t.size() && t.get(i).kind() == Kind.NUMBER) {
                count = count(t.get(i++));
            }
        } else if (word(t, i, "ALL")) {
            count = Long.MAX_VALUE;
            i++;
        } else if (i < t.size() && t.get(i).kind() == Kind.NUMBER) {
            count = count(t.get(i++));
        } else if (word(t, i, "PRIOR", "FIRST", "LAST", "ABSOLUTE", "RELATIVE", "BACKWARD")
                || i < t.size() && t.get(i).isSymbol("-")) {
            return unsupported("FETCH " + t.get(i).upper() + "; FyrQL reads cursors forward only");
        }
        if (word(t, i, "FROM", "IN")) {
            i++;
        }
        return new Fetch(name(t, i, move ? "MOVE" : "FETCH"), count, move);
    }

    private static long count(Token n) {
        try {
            return Long.parseLong(n.text());
        } catch (NumberFormatException e) {
            throw new PgException(PgException.SYNTAX_ERROR, "invalid count of rows: " + n.text());
        }
    }

    private static Command deallocate(List<Token> t) {
        int i = word(t, 1, "PREPARE") ? 2 : 1;
        return word(t, i, "ALL") ? new Deallocate(null) : new Deallocate(name(t, i, "DEALLOCATE"));
    }

    /** {@code COPY} to and from files goes to the engine; to and from the client the server refuses. */
    private static Command copy(List<Token> t, String sql) {
        for (Token token : t) {
            if (token.is("STDIN", "STDOUT") || token.is("PROGRAM")) {
                return unsupported("COPY " + token.upper() + "; FyrQL copies to and from files, with COPY ... TO"
                        + " 'file' and COPY ... FROM 'file'");
            }
        }
        return new Engine(sql);
    }
}
