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

package net.fyrql.flight;

import net.fyrql.FyrQL;
import org.apache.arrow.flight.CloseSessionRequest;
import org.apache.arrow.flight.CloseSessionResult;
import org.apache.arrow.flight.FlightCallHeaders;
import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.GetSessionOptionsRequest;
import org.apache.arrow.flight.HeaderCallOption;
import org.apache.arrow.flight.SessionOptionValue;
import org.apache.arrow.flight.SessionOptionValueFactory;
import org.apache.arrow.flight.SessionOptionValueVisitor;
import org.apache.arrow.flight.SetSessionOptionsRequest;
import org.apache.arrow.flight.SetSessionOptionsResult;
import org.apache.arrow.flight.client.ClientCookieMiddleware;
import org.apache.arrow.flight.sql.FlightSqlClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Each client of the server is a session of the engine: clients that keep
 * cookies keep their settings, the others get the settings of the engine
 * in each call.
 */
class SessionTest {

    private static final String NOW = "SELECT CAST(TIMESTAMPTZ '2024-01-01 12:00:00+00' AS VARCHAR)";

    private TestServer server;

    @BeforeEach
    void open() throws Exception {
        server = new TestServer(ServerConfig.defaults().withPort(0), FyrQL.builder().timeZone(ZoneId.of("UTC")));
    }

    @AfterEach
    void close() {
        server.close();
    }

    /** A client that keeps the cookies of the server, as the JDBC driver of Arrow does. */
    private FlightSqlClient withCookies() {
        return server.client(FlightClient.builder(server.allocator, server.location())
                .intercept(new ClientCookieMiddleware.Factory()).build());
    }

    private static Object value(FlightSqlClient client, String sql) throws Exception {
        return TestServer.rows(client, client.execute(sql)).get(0)[0];
    }

    /** The text of a value of a session option. */
    private static final SessionOptionValueVisitor<String> TEXT = new SessionOptionValueVisitor<>() {
        @Override
        public String visit(String value) {
            return value;
        }

        @Override
        public String visit(boolean value) {
            return "bool " + value;
        }

        @Override
        public String visit(long value) {
            return "long " + value;
        }

        @Override
        public String visit(double value) {
            return "double " + value;
        }

        @Override
        public String visit(String[] value) {
            return "list";
        }

        @Override
        public String visit(Void value) {
            return "empty";
        }
    };

    private static Map<String, String> options(FlightSqlClient client) {
        Map<String, String> options = new LinkedHashMap<>();
        client.getSessionOptions(new GetSessionOptionsRequest()).getSessionOptions()
                .forEach((k, v) -> options.put(k, v.acceptVisitor(TEXT)));
        return options;
    }

    private static Map<String, String> set(FlightSqlClient client, Map<String, SessionOptionValue> options) {
        Map<String, String> errors = new LinkedHashMap<>();
        client.setSessionOptions(new SetSessionOptionsRequest(options)).getErrors()
                .forEach((k, v) -> errors.put(k, v.value.name()));
        return errors;
    }

    @Test
    void twoClientsHaveTheirOwnSettings() throws Exception {
        FlightSqlClient berlin = withCookies();
        FlightSqlClient tokyo = withCookies();
        assertEquals(0, berlin.executeUpdate("SET TimeZone = 'Europe/Berlin'"));
        assertEquals(0, tokyo.executeUpdate("SET TimeZone = 'Asia/Tokyo'"));
        assertEquals("2024-01-01 13:00:00+01", value(berlin, NOW));
        assertEquals("2024-01-01 21:00:00+09", value(tokyo, NOW));
        assertEquals("Asia/Tokyo", value(tokyo, "SHOW TimeZone"));
        assertEquals(ZoneId.of("UTC"), server.engine.timeZone(), "the engine keeps its time zone");
        try (FlightSqlClient.PreparedStatement p = berlin.prepare(NOW)) {
            assertEquals("2024-01-01 13:00:00+01", TestServer.rows(berlin, p.execute()).get(0)[0]);
        }
        try (FlightSqlClient.PreparedStatement p = tokyo.prepare("SET TimeZone = 'America/New_York'")) {
            assertEquals(0, p.executeUpdate());
        }
        assertEquals("2024-01-01 07:00:00-05", value(tokyo, NOW), "a prepared SET changes the session that runs it");

        assertEquals(0L, value(berlin, "SET search_path = ''"), "a SET run as a query gives its count");
        assertEquals("", value(berlin, "SHOW search_path"));
        assertEquals("mem.main", value(tokyo, "SHOW search_path"));
        assertEquals(2L, value(berlin, "SELECT count(*) FROM tiny"));
        TestServer.awaitTrue("two sessions", 2000, () -> server.server.sessions() == 2);
    }

    @Test
    void aClientWithoutCookiesHasTheSettingsOfTheEngineInEachCall() throws Exception {
        FlightSqlClient plain = server.client();
        assertEquals(0, plain.executeUpdate("SET TimeZone = 'Europe/Berlin'"));
        assertEquals("UTC", value(plain, "SHOW TimeZone"));
        assertEquals("2024-01-01 12:00:00+00", value(plain, NOW));
        TestServer.awaitTrue("only the changed session is kept", 2000, () -> server.server.sessions() == 1);
        FlightCallHeaders unknown = new FlightCallHeaders();
        unknown.insert("cookie", Sessions.COOKIE + "=no-such-session");
        assertEquals(0, plain.executeUpdate("SET TimeZone = 'Asia/Tokyo'", new HeaderCallOption(unknown)));
        TestServer.awaitTrue("an unknown cookie gives a new session", 2000, () -> server.server.sessions() == 2);
    }

    @Test
    void sessionOptionsSetShowAndCloseTheSession() throws Exception {
        FlightSqlClient c = withCookies();
        Map<String, SessionOptionValue> options = new LinkedHashMap<>();
        options.put("timezone", SessionOptionValueFactory.makeSessionOptionValue("Asia/Tokyo"));
        options.put("statement_timeout", SessionOptionValueFactory.makeSessionOptionValue(30_000L));
        options.put("search_path", SessionOptionValueFactory.makeSessionOptionValue("mem"));
        options.put("work_mem", SessionOptionValueFactory.makeSessionOptionValue("1GB"));
        assertEquals(Map.of("work_mem", "INVALID_NAME"), set(c, options));
        assertEquals(Map.of("TimeZone", "Asia/Tokyo", "search_path", "mem.main", "statement_timeout", "30s"),
                options(c));
        assertEquals("Asia/Tokyo", value(c, "SHOW TimeZone"));

        options.clear();
        options.put("TimeZone", SessionOptionValueFactory.makeSessionOptionValue("Nowhere/City"));
        options.put("search_path", SessionOptionValueFactory.makeSessionOptionValue(new String[] {"a", "b"}));
        options.put("statement_timeout", SessionOptionValueFactory.makeSessionOptionValue(true));
        assertEquals(Map.of("TimeZone", "INVALID_VALUE", "search_path", "INVALID_VALUE", "statement_timeout",
                "INVALID_VALUE"), set(c, options));
        assertEquals("Asia/Tokyo", options(c).get("TimeZone"), "a wrong value changes nothing");

        assertEquals(Map.of(), set(c, Map.of("TimeZone", SessionOptionValueFactory.makeEmptySessionOptionValue())));
        assertEquals("UTC", value(c, "SHOW TimeZone"), "an empty value resets the setting");

        assertEquals(CloseSessionResult.Status.CLOSED, c.closeSession(new CloseSessionRequest()).getStatus());
        assertEquals(Map.of("TimeZone", "UTC", "search_path", "mem.main", "statement_timeout", "0"), options(c),
                "after CloseSession the client starts a new session");
        TestServer.awaitTrue("no session is kept", 2000, () -> server.server.sessions() == 0);
    }
}
