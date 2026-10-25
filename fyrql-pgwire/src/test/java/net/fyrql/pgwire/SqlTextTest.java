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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The tokens of the server, the splitting of texts and the statements it answers itself. */
class SqlTextTest {

    @Test
    void aTextSplitsAtTheSemicolonsOutsideStringsNamesAndComments() {
        assertEquals(List.of("SELECT 1", " SELECT ';' AS \";\"", " /* ; /* ; */ */ SELECT E'\\';'"),
                SqlText.split("SELECT 1; SELECT ';' AS \";\"; /* ; /* ; */ */ SELECT E'\\';';"));
        assertEquals(List.of("SELECT $x$;$x$", " SELECT $$;$$", " SELECT a$b"),
                SqlText.split("SELECT $x$;$x$; SELECT $$;$$; SELECT a$b"));
        assertEquals(List.of(), SqlText.split(" ; -- only a comment\n;"));
        assertThrows(SqlText.UnterminatedException.class, () -> SqlText.split("SELECT 'a"));
        assertThrows(SqlText.UnterminatedException.class, () -> SqlText.split("SELECT /* a"));
    }

    @Test
    void theTokensHaveTheirValues() {
        List<SqlText.Token> t = SqlText.tokens("SELECT \"A\"\"b\", E'x\\ny', 'it''s', $1, 1.5e3::int4[]");
        assertEquals("a\"b".toUpperCase().toLowerCase().replace("a", "A"), t.get(1).value());
        assertEquals("x\ny", t.get(3).value());
        assertEquals("it's", t.get(5).value());
        assertEquals(SqlText.Kind.PARAMETER, t.get(7).kind());
        assertEquals("1.5e3", t.get(9).text());
        assertEquals("::", t.get(10).text());
    }

    @Test
    void theServerAnswersTheseStatementsItself() {
        assertInstanceOf(Commands.Transaction.class, Commands.parse("begin read only"));
        assertEquals("START TRANSACTION", ((Commands.Transaction) Commands.parse("START TRANSACTION")).tag());
        assertEquals(Commands.TransactionOp.ROLLBACK_TO, ((Commands.Transaction) Commands.parse(
                "ROLLBACK TO SAVEPOINT a")).op());
        Commands.Set set = (Commands.Set) Commands.parse("SET SESSION search_path TO 'x', \"$user\"");
        assertEquals("search_path", set.name());
        assertEquals(2, set.values().size());
        assertEquals("TimeZone", ((Commands.Set) Commands.parse("SET TIME ZONE 'UTC'")).name());
        assertEquals("myapp.x", ((Commands.Set) Commands.parse("set myapp.x = 1")).name());
        assertEquals(null, ((Commands.Set) Commands.parse("SET a TO DEFAULT")).values());
        Commands.Many many = (Commands.Many) Commands.parse(
                "SET SESSION CHARACTERISTICS AS TRANSACTION ISOLATION LEVEL READ COMMITTED, READ ONLY");
        assertEquals(List.of("default_transaction_isolation", "default_transaction_read_only"),
                many.sets().stream().map(Commands.Set::name).toList());
        assertEquals("transaction_isolation", ((Commands.Show) Commands.parse(
                "SHOW TRANSACTION ISOLATION LEVEL")).name());
        Commands.Declare d = (Commands.Declare) Commands.parse("DECLARE c CURSOR WITH HOLD FOR SELECT 1");
        assertEquals("SELECT 1", d.query());
        assertEquals(true, d.hold());
        assertEquals(Long.MAX_VALUE, ((Commands.Fetch) Commands.parse("FETCH ALL IN c")).count());
        assertEquals(7, ((Commands.Fetch) Commands.parse("MOVE FORWARD 7 FROM c")).count());
        assertInstanceOf(Commands.Unsupported.class, Commands.parse("COPY t FROM STDIN"));
        assertInstanceOf(Commands.Engine.class, Commands.parse("COPY t TO 'file.csv'"));
        assertInstanceOf(Commands.Engine.class, Commands.parse("SELECT 1"));
        assertInstanceOf(Commands.Engine.class, Commands.parse("SHOW TABLES"));
    }
}
