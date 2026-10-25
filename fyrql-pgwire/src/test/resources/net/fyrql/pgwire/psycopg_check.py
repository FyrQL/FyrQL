# Copyright 2026 the FyrQL authors
#
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
#
# This Source Code Form is "Incompatible With Secondary Licenses", as
# defined by the Mozilla Public License, v. 2.0.

"""Checks psycopg 3 against the PostgreSQL wire protocol server of FyrQL; the port is the first argument."""

import datetime
import decimal
import sys
import uuid

import psycopg

port = int(sys.argv[1])
conninfo = f"host=127.0.0.1 port={port} dbname=mem user=tester"


def check(actual, expected, what):
    if actual != expected:
        raise AssertionError(f"{what}: expected {expected!r}, got {actual!r}")


with psycopg.connect(conninfo) as conn:
    # Parameters with types from Python and without.
    check(conn.execute("SELECT k, v FROM tiny WHERE k = %s", [2]).fetchall(), [(2, "two")], "typed parameter")
    check(conn.execute("SELECT %s, %s || 'x'", ["a", "b"]).fetchone(), ("a", "bx"), "parameters without context")
    check(conn.execute("SELECT count(*) FROM numbers WHERE n < %s LIMIT %s", [10, 5]).fetchone(), (10,), "LIMIT %s")
    # The binary format, and a statement prepared on the server, run six times.
    with conn.cursor(binary=True) as cur:
        cur.execute("SELECT b, i, bi, d, dec, v, bl, dt, ts, u FROM t WHERE i = 3")
        row = cur.fetchone()
        check(row[0], True, "binary boolean")
        check(row[2], 4, "binary bigint")
        check(row[4], decimal.Decimal("1234567890123456789012345678.0123456789"), "binary numeric")
        check(row[6], b"\xaa\x00", "binary bytea")
        check(row[7], datetime.date(2024, 2, 29), "binary date")
        check(row[9], uuid.UUID("123e4567-e89b-12d3-a456-426614174000"), "binary uuid")
    for i in range(6):
        check(conn.execute("SELECT n FROM numbers WHERE n = %s", [i], prepare=True).fetchone(), (i,), "prepared")
    # Values there and back.
    values = [datetime.date(2024, 2, 29), datetime.datetime(2024, 2, 29, 1, 2, 3, 456000),
              datetime.datetime(2024, 2, 29, 1, 2, 3, tzinfo=datetime.timezone.utc), decimal.Decimal("12.34"),
              uuid.UUID("123e4567-e89b-12d3-a456-426614174000"), b"\x01\x02", [1, 2, None], 1.5, True,
              datetime.time(4, 5, 6)]
    back = conn.execute("SELECT %s, %s, %s, %s, %s, %s, %s, %s, %s, %s", values).fetchone()
    check(list(back[:2]) + [back[2].astimezone(datetime.timezone.utc)] + list(back[3:]), values, "values there and back")
    conn.commit()
    # A cursor on the server, in parts.
    with conn.cursor(name="server_side") as cur:
        cur.itersize = 100
        cur.execute("SELECT n FROM numbers ORDER BY n")
        check(sum(1 for _ in cur), 1000, "rows of a cursor on the server")
    conn.commit()
    # An error ends the transaction, which only a rollback ends.
    try:
        conn.execute("SELECT nope FROM tiny")
        raise AssertionError("the query should fail")
    except psycopg.errors.UndefinedColumn:
        pass
    try:
        conn.execute("SELECT 1")
        raise AssertionError("the transaction should be failed")
    except psycopg.errors.InFailedSqlTransaction:
        pass
    conn.rollback()
    # Settings of the session.
    conn.execute("SET TimeZone TO 'Asia/Tokyo'")
    check(conn.execute("SHOW TimeZone").fetchone(), ("Asia/Tokyo",), "time zone")
    check(conn.info.parameter_status("TimeZone"), "Asia/Tokyo", "reported time zone")
    conn.commit()

with psycopg.connect(conninfo, autocommit=True) as conn:
    conn.execute("CREATE TABLE written (a INTEGER, b VARCHAR)")
    with conn.cursor() as cur:
        cur.executemany("INSERT INTO written VALUES (%s, %s)", [(1, "x"), (2, "y"), (3, None)])
    check(conn.execute("SELECT count(*), count(b) FROM written").fetchone(), (3, 2), "rows written")
    conn.execute("DROP TABLE written")

print("psycopg ok")
