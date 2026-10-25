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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * psql against the server, with the program the system property
 * {@code fyrql.test.psql} names, such as
 * {@code -Dfyrql.test.psql=/usr/lib/postgresql/18/bin/psql}: its commands
 * that describe the database, queries, settings and several statements in
 * one command.
 */
@EnabledIfSystemProperty(named = "fyrql.test.psql", matches = ".+")
class PsqlTest {

    private static TestServer server;

    @BeforeAll
    static void start() throws Exception {
        server = new TestServer();
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    /** The output of psql for commands, after checking that it ran without an error. */
    private static String psql(String database, String... commands) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(System.getProperty("fyrql.test.psql"), "-h", "127.0.0.1",
                "-p", Integer.toString(server.server.port()), "-U", "tester", "-d", database, "-X", "-v",
                "ON_ERROR_STOP=1"));
        for (String c : commands) {
            command.add("-c");
            command.add(c);
        }
        ProcessBuilder pb = new ProcessBuilder(command);
        Map<String, String> env = pb.environment();
        env.put("PGCLIENTENCODING", "UTF8");
        env.put("LC_ALL", "C");
        env.put("LC_MESSAGES", "C");
        env.put("LANG", "C");
        env.put("PGTZ", "UTC");
        Process p = pb.start();
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, p.exitValue(), String.join(" ", commands) + "\n" + out + err);
        assertFalse(err.contains("ERROR"), err);
        return out;
    }

    @Test
    void theCommandsThatDescribeTheDatabase() throws Exception {
        assertTrue(psql("shop", "\\l").contains("shop"));
        assertTrue(psql("shop", "\\dn").contains("sales"));
        assertTrue(psql("shop", "\\dt").contains("customers"));
        assertTrue(psql("shop", "\\dt *.*").contains("orders"));
        String customers = psql("shop", "\\d customers");
        assertTrue(customers.contains("integer") && customers.contains("not null"), customers);
        String orders = psql("shop", "\\d+ sales.orders");
        assertTrue(orders.contains("numeric(10,2)"), orders);
        psql("shop", "\\dv");
        psql("shop", "\\di");
        assertTrue(psql("shop", "\\df lower").contains("lower"));
        assertTrue(psql("shop", "\\du").contains(PgWireServer.OWNER));
        psql("shop", "\\dT");
        assertTrue(psql("mem", "\\d t").contains("bytea"));
        assertTrue(psql("shop", "\\conninfo").contains("shop"));
    }

    @Test
    void queriesSettingsAndSeveralStatements() throws Exception {
        String orders = psql("shop", "SELECT * FROM sales.orders ORDER BY id");
        assertTrue(orders.contains("7.25") && orders.contains("(3 rows)"), orders);
        String utc = psql("shop", "SET TimeZone = 'Asia/Tokyo'; SHOW TimeZone");
        assertTrue(utc.contains("Asia/Tokyo"), utc);
        String two = psql("mem", "SELECT count(*) FROM numbers; SELECT k FROM tiny ORDER BY k");
        assertTrue(two.contains("1000") && two.contains("(2 rows)"), two);
        String all = psql("mem", "SELECT * FROM t");
        assertTrue(all.contains("1 year 2 mons -3 days +04:05:06.789"), all);
    }
}
