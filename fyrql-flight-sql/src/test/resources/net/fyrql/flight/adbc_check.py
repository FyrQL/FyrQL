# Copyright 2026 the FyrQL authors
#
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
#
# This Source Code Form is "Incompatible With Secondary Licenses", as
# defined by the Mozilla Public License, v. 2.0.

"""ADBC against the Flight SQL server: with the cookie middleware a SET
reaches the next statements of the connection, without it each call has the
settings of the engine. The argument is the port of the server."""

import sys

import adbc_driver_flightsql
import adbc_driver_flightsql.dbapi as flight

NOW = "SELECT CAST(TIMESTAMPTZ '2024-01-01 12:00:00+00' AS VARCHAR)"


def zone(port, cookies):
    """Sets the time zone in a connection and reads it back twice."""
    options = {"adbc.flight.sql.rpc.with_cookie_middleware": "true"} if cookies else {}
    with flight.connect("grpc://127.0.0.1:" + port, db_kwargs=options, autocommit=True) as connection:
        with connection.cursor() as cursor:
            cursor.execute("SET TimeZone = 'Asia/Tokyo'")
            cursor.fetchall()
            cursor.execute("SHOW TimeZone")
            shown = cursor.fetchone()[0]
            cursor.execute(NOW)
            now = cursor.fetchone()[0]
    return shown, now


def main():
    port = sys.argv[1]
    kept = zone(port, True)
    assert kept == ("Asia/Tokyo", "2024-01-01 21:00:00+09"), kept
    lost = zone(port, False)
    assert lost == ("UTC", "2024-01-01 12:00:00+00"), lost
    print("adbc-driver-flightsql " + adbc_driver_flightsql.__version__)
    print("adbc ok")


main()
