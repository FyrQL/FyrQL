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
import org.apache.arrow.flight.CallHeaders;
import org.apache.arrow.flight.CallInfo;
import org.apache.arrow.flight.CallStatus;
import org.apache.arrow.flight.FlightProducer.CallContext;
import org.apache.arrow.flight.FlightServerMiddleware;
import org.apache.arrow.flight.RequestContext;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * The sessions of the clients of a server: each client is a session of the
 * engine, see {@link Session}, which it names with the cookie
 * {@value #COOKIE}, the one the clients of Arrow keep. A call without the
 * cookie, or with one of a session that expired or belongs to another
 * user, gets a new session with the settings of the engine and the cookie
 * of it in its answer. A new session that is left as it started when its
 * call ends is forgotten at once, so that a client that keeps no cookies
 * leaves nothing behind; its next call gets a new one, which is the same.
 * A session expires after {@code sessionTimeout} without use, see
 * {@link ServerConfig#sessionTimeout()}; a statement keeps the session it
 * was bound in, a prepared statement binds in the session of the call that
 * runs it.
 */
final class Sessions implements FlightServerMiddleware.Factory<Sessions.Call> {

    /** The name of the cookie of a session. */
    static final String COOKIE = "arrow_flight_session_id";

    /** The key of the middleware of a call. */
    static final FlightServerMiddleware.Key<Call> KEY = FlightServerMiddleware.Key.of("fyrql-session");

    /** A session, the user it belongs to, and when it was used last, in nanoseconds of the clock. */
    private record Entry(String owner, Session session, AtomicLong used) {
    }

    private final FyrQL engine;
    private final long timeout;
    private final LongSupplier clock;
    private final Map<String, String> defaults;
    private final Map<String, Entry> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    /**
     * @param timeout how long a session stays without use, zero for ever
     * @param clock the clock of the expiry, in nanoseconds
     */
    Sessions(FyrQL engine, Duration timeout, LongSupplier clock) {
        this.engine = engine;
        this.timeout = timeout.toNanos();
        this.clock = clock;
        this.defaults = engine.newSession().settings();
    }

    @Override
    public Call onCallStarted(CallInfo info, CallHeaders incomingHeaders, RequestContext context) {
        return new Call(cookie(incomingHeaders));
    }

    /** Returns the value of the cookie of a session in the headers of a call, or {@code null}. */
    static String cookie(CallHeaders headers) {
        Iterable<String> values = headers.getAll("cookie");
        if (values == null) {
            return null;
        }
        for (String value : values) {
            for (String part : value.split(";")) {
                int eq = part.indexOf('=');
                if (eq > 0 && part.substring(0, eq).strip().equals(COOKIE)) {
                    String id = part.substring(eq + 1).strip();
                    if (!id.isEmpty()) {
                        return id;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Returns the session of a call: the one its cookie names, if it belongs
     * to the same user and has not expired; else a new one, whose cookie the
     * answer of the call sets.
     *
     * @param owner the user who signed in, empty without sign-in
     */
    Session session(CallContext context, String owner) {
        Call call = context.getMiddleware(KEY);
        if (call == null) {
            return engine.newSession();
        }
        synchronized (call) {
            if (call.id != null) {
                Entry e = sessions.get(call.id);
                if (e != null && e.owner().equals(owner)) {
                    e.used().set(clock.getAsLong());
                    return e.session();
                }
            }
            Entry e = new Entry(owner, engine.newSession(), new AtomicLong(clock.getAsLong()));
            String id = newId();
            sessions.put(id, e);
            call.id = id;
            call.created = true;
            call.closed = false;
            return e.session();
        }
    }

    /**
     * Forgets the session of a call, whose answer then removes the cookie.
     *
     * @return whether the call had a session of the user
     */
    boolean close(CallContext context, String owner) {
        Call call = context.getMiddleware(KEY);
        if (call == null) {
            return false;
        }
        synchronized (call) {
            Entry e = call.id == null ? null : sessions.get(call.id);
            if (e == null || !e.owner().equals(owner)) {
                return false;
            }
            sessions.remove(call.id, e);
            call.closed = true;
            return true;
        }
    }

    /** Forgets the sessions that have not been used for longer than the timeout. */
    void expire() {
        if (timeout <= 0) {
            return;
        }
        long now = clock.getAsLong();
        sessions.values().removeIf(e -> now - e.used().get() > timeout);
    }

    /** Returns the number of sessions the server holds. */
    int size() {
        return sessions.size();
    }

    private String newId() {
        byte[] bytes = new byte[18];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Forgets a session that a call made and left as it started. */
    private void release(Call call) {
        synchronized (call) {
            if (call.created && !call.closed) {
                Entry e = sessions.get(call.id);
                if (e != null && e.session().settings().equals(defaults)) {
                    sessions.remove(call.id, e);
                }
            }
        }
    }

    /** The session of a call: the id of its cookie, and whether the call made or closed the session. */
    final class Call implements FlightServerMiddleware {
        private String id;
        private boolean created;
        private boolean closed;

        Call(String id) {
            this.id = id;
        }

        @Override
        public void onBeforeSendingHeaders(CallHeaders outgoingHeaders) {
            synchronized (this) {
                if (closed) {
                    outgoingHeaders.insert("set-cookie", COOKIE + "=" + id + "; Max-Age=0");
                } else if (created) {
                    outgoingHeaders.insert("set-cookie", COOKIE + "=" + id);
                }
            }
        }

        @Override
        public void onCallCompleted(CallStatus status) {
            release(this);
        }

        @Override
        public void onCallErrored(Throwable err) {
            release(this);
        }
    }
}
