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
import net.fyrql.Session;
import org.apache.arrow.flight.CallStatus;
import org.apache.arrow.flight.FlightCallHeaders;
import org.apache.arrow.flight.FlightProducer.CallContext;
import org.apache.arrow.flight.FlightServerMiddleware;
import org.apache.arrow.flight.FlightStatusCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The sessions of the clients of a server, without a server: cookies, users, expiry. */
class SessionsTest {

    private final FyrQL engine = FyrQL.builder().timeZone(ZoneId.of("Europe/Berlin")).build();
    private final AtomicLong clock = new AtomicLong();
    private final Sessions sessions = new Sessions(engine, Duration.ofSeconds(10), clock::get);

    @AfterEach
    void close() {
        engine.close();
    }

    /** A call with the given cookie header, or none, by a user. */
    private record Call(Sessions.Call middleware, String owner) implements CallContext {
        @Override
        public String peerIdentity() {
            return owner;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends FlightServerMiddleware> T getMiddleware(FlightServerMiddleware.Key<T> key) {
            return key == Sessions.KEY ? (T) middleware : null;
        }

        @Override
        public Map<FlightServerMiddleware.Key<?>, FlightServerMiddleware> getMiddleware() {
            return Map.of(Sessions.KEY, middleware);
        }

        /** Returns the headers the answer sends, after the call ends. */
        List<String> end() {
            FlightCallHeaders headers = new FlightCallHeaders();
            middleware.onBeforeSendingHeaders(headers);
            middleware.onCallCompleted(new CallStatus(FlightStatusCode.OK));
            List<String> cookies = new ArrayList<>();
            if (headers.containsKey("set-cookie")) {
                headers.getAll("set-cookie").forEach(cookies::add);
            }
            return cookies;
        }
    }

    private Call call(String cookie, String owner) {
        FlightCallHeaders headers = new FlightCallHeaders();
        if (cookie != null) {
            headers.insert("cookie", cookie);
        }
        return new Call(sessions.onCallStarted(null, headers, null), owner);
    }

    /** Returns the id a set-cookie header gives the session. */
    private static String id(List<String> headers) {
        assertEquals(1, headers.size(), headers.toString());
        String h = headers.get(0);
        assertTrue(h.startsWith(Sessions.COOKIE + "="), h);
        return h.substring(Sessions.COOKIE.length() + 1);
    }

    @Test
    void aSessionThatChangesKeepsItsCookie() {
        Call first = call(null, "");
        Session s = sessions.session(first, "");
        assertSame(s, sessions.session(first, ""), "one call has one session");
        s.set("TimeZone", "Asia/Tokyo");
        String id = id(first.end());
        assertEquals(1, sessions.size());

        Call second = call("other=1; " + Sessions.COOKIE + "=" + id + "; more=2", "");
        assertSame(s, sessions.session(second, ""));
        assertEquals(List.of(), second.end(), "a known session sets no cookie");

        Call foreign = call(Sessions.COOKIE + "=" + id, "bob");
        Session theirs = sessions.session(foreign, "bob");
        assertNotSame(s, theirs, "the session of another user is none of this one");
        assertFalse(id(foreign.end()).equals(id));
        assertEquals(1, sessions.size(), "the new session of bob is left as it started and forgotten");
    }

    @Test
    void aSessionLeftAsItStartedIsForgottenWithItsCall() {
        Call c = call(null, "");
        sessions.session(c, "");
        String id = id(c.end());
        assertEquals(0, sessions.size());
        Call next = call(Sessions.COOKIE + "=" + id, "");
        sessions.session(next, "");
        assertFalse(id(next.end()).equals(id), "an unknown session gets a new cookie");
        Call none = call(null, "");
        assertEquals(List.of(), none.end(), "a call that asks for no session gets no cookie");
    }

    @Test
    void sessionsExpireAndClose() {
        Call c = call(null, "alice");
        sessions.session(c, "alice").set("statement_timeout", "5s");
        String id = id(c.end());
        clock.set(Duration.ofSeconds(9).toNanos());
        Call again = call(Sessions.COOKIE + "=" + id, "alice");
        assertEquals("5s", sessions.session(again, "alice").setting("statement_timeout"));
        again.end();
        clock.set(Duration.ofSeconds(15).toNanos());
        sessions.expire();
        assertEquals(1, sessions.size(), "used 6 s ago");
        clock.set(Duration.ofSeconds(20).toNanos());
        sessions.expire();
        assertEquals(0, sessions.size(), "used 11 s ago");

        Call open = call(null, "alice");
        sessions.session(open, "alice").set("TimeZone", "UTC");
        id = id(open.end());
        Call close = call(Sessions.COOKIE + "=" + id, "bob");
        assertFalse(sessions.close(close, "bob"), "bob cannot close the session of alice");
        close = call(Sessions.COOKIE + "=" + id, "alice");
        assertTrue(sessions.close(close, "alice"));
        assertEquals(List.of(Sessions.COOKIE + "=" + id + "; Max-Age=0"), close.end());
        assertEquals(0, sessions.size());

        Sessions forever = new Sessions(engine, Duration.ZERO, clock::get);
        Call kept = new Call(forever.onCallStarted(null, new FlightCallHeaders(), null), "");
        forever.session(kept, "").set("TimeZone", "UTC");
        kept.end();
        clock.set(Long.MAX_VALUE);
        forever.expire();
        assertEquals(1, forever.size());
        assertNull(Sessions.cookie(new FlightCallHeaders()));
    }
}
