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

import com.google.protobuf.ByteString;
import org.apache.arrow.flight.FlightStatusCode;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Handles: random, found only by their client, and forgotten after the timeout unless busy. */
class HandlesTest {

    private static final long TIMEOUT = 1000;

    @Test
    void aClientFindsOnlyItsOwnHandles() {
        Handles<String> handles = new Handles<>("statement", TIMEOUT, () -> 0, v -> false);
        ByteString a = handles.add("alice", "of alice");
        ByteString b = handles.add("bob", "of bob");
        assertNotEquals(a, b);
        assertEquals(16, a.size());
        assertEquals("of alice", handles.get(a, "alice"));
        TestServer.fails(FlightStatusCode.NOT_FOUND, "The statement is unknown", () -> handles.get(a, "bob"));
        assertNull(handles.find(a, "bob"));
        assertNull(handles.remove(a, "bob"));
        assertEquals("of alice", handles.remove(a, "alice"));
        assertNull(handles.remove(a, "alice"));
        assertEquals(1, handles.size());
    }

    @Test
    void aHandleWithoutUseIsForgottenUnlessBusy() {
        AtomicLong now = new AtomicLong();
        Handles<String> handles = new Handles<>("prepared statement", TIMEOUT, now::get, "busy"::equals);
        ByteString idle = handles.add("", "idle");
        ByteString used = handles.add("", "used");
        ByteString busy = handles.add("", "busy");
        now.set(TIMEOUT);
        handles.get(used, "");
        assertEquals(0, handles.expire());
        now.set(TIMEOUT + 1);
        assertEquals(1, handles.expire());
        TestServer.fails(FlightStatusCode.NOT_FOUND, "The prepared statement is unknown or was closed or forgotten",
                () -> handles.get(idle, ""));
        now.set(3 * TIMEOUT);
        assertEquals(1, handles.expire());
        assertEquals("busy", handles.get(busy, ""));
        assertNull(handles.find(used, ""));
    }
}
