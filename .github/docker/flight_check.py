# Copyright 2026 the FyrQL authors
#
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
#
# This Source Code Form is "Incompatible With Secondary Licenses", as
# defined by the Mozilla Public License, v. 2.0.

"""The smoke test of the image of the Flight SQL server through ADBC: it signs
in, reads a CSV file of the data directory, and writes and reads a table
through the JDBC driver from the drivers directory. The arguments are the URI
of the server and the user; the password comes from FYRQL_SMOKE_PASSWORD."""

import os
import sys

import adbc_driver_flightsql.dbapi as flight


def rows(cursor, sql):
    """Runs a statement and returns its rows."""
    cursor.execute(sql)
    return cursor.fetchall()


def main():
    uri, user = sys.argv[1], sys.argv[2]
    options = {"username": user, "password": os.environ["FYRQL_SMOKE_PASSWORD"]}
    with flight.connect(uri, db_kwargs=options, autocommit=True) as connection:
        with connection.cursor() as cursor:
            read = rows(cursor, "SELECT count(*), sum(n) FROM files.main.numbers")
            assert read == [(5, 15)], read
            rows(cursor, "CREATE TABLE hsql.main.copied AS SELECT * FROM files.main.numbers")
            copied = rows(cursor, "SELECT count(*), sum(n) FROM hsql.main.copied")
            assert copied == [(5, 15)], copied
            one = rows(cursor, "SELECT 1")
            assert one == [(1,)], one
    print("flight sql ok")


main()
