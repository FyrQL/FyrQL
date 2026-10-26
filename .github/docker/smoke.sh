#!/usr/bin/env bash
# Copyright 2026 the FyrQL authors
#
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
#
# This Source Code Form is "Incompatible With Secondary Licenses", as
# defined by the Mozilla Public License, v. 2.0.

# The smoke tests of the images of both servers. Each image runs as the user 10001 and
# refuses to listen on 0.0.0.0 without users. Each server reads a CSV file of the data
# directory and writes and reads a table through the JDBC driver of HSQLDB in the drivers
# directory: the PostgreSQL wire protocol server through psql, the Flight SQL server
# through ADBC. A stop ends each within 10 seconds. The ports 5433 and 32010 must be free.
#
#   smoke.sh <image of pgwire> <image of Flight SQL> <directory with the HSQLDB JAR>

set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
pgwire=$1
flight=$2
drivers=$(cd "$3" && pwd)
python=${PYTHON:-python3}
export FYRQL_SMOKE_PASSWORD=${FYRQL_SMOKE_PASSWORD:-smoke-$RANDOM$RANDOM}
out=$(mktemp -d)

trap 'docker rm -f fyrql-smoke-pgwire fyrql-smoke-flight > /dev/null 2>&1 || true' EXIT

fail() {
    echo "smoke test failed: $*" >&2
    exit 1
}

# Starts a server in the background and waits until it listens: name, image, port, file.
start() {
    docker run -d --name "$1" -p "$3:$3" -e FYRQL_SMOKE_PASSWORD \
        -v "$here/config/$4:/config/fyrql.yaml:ro" -v "$here/data:/data:ro" -v "$drivers:/drivers:ro" \
        "$2" > /dev/null
    for _ in $(seq 60); do
        # Without -q, grep reads all the logs, so that pipefail sees no broken pipe.
        if docker logs "$1" 2>&1 | grep "listening on" > /dev/null; then
            docker logs "$1"
            return
        fi
        if [ "$(docker inspect -f '{{.State.Running}}' "$1")" != true ]; then
            docker logs "$1"
            fail "$1 ended before it listened"
        fi
        sleep 1
    done
    docker logs "$1"
    fail "$1 did not listen within 60 seconds"
}

# Stops a server as docker stop does, with SIGTERM, and checks that the JVM ended in time.
stop() {
    local began=$SECONDS
    docker stop -t 10 "$1" > /dev/null
    local took=$((SECONDS - began))
    local code
    code=$(docker inspect -f '{{.State.ExitCode}}' "$1")
    docker rm "$1" > /dev/null
    [ "$took" -lt 10 ] || fail "$1 took $took seconds to stop"
    # 143 is the exit code of a JVM that SIGTERM ended.
    [ "$code" = 143 ] || [ "$code" = 0 ] || fail "$1 ended with $code"
}

for image in "$pgwire" "$flight"; do
    user=$(docker run --rm --entrypoint id "$image" -u)
    [ "$user" = 10001 ] || fail "$image runs as the user $user"
    if docker run --rm -v "$here/config/no-users.yaml:/config/fyrql.yaml:ro" -v "$here/data:/data:ro" \
            "$image" > "$out/no-users.txt" 2>&1; then
        fail "$image started on 0.0.0.0 without users"
    fi
    grep -q "without sign-in" "$out/no-users.txt" || { cat "$out/no-users.txt"; fail "$image ended otherwise"; }
done

start fyrql-smoke-pgwire "$pgwire" 5433 pgwire.yaml
PGPASSWORD=$FYRQL_SMOKE_PASSWORD psql "host=127.0.0.1 port=5433 user=smoke dbname=hsql" -v ON_ERROR_STOP=1 \
    -At -f "$here/pgwire_check.sql" > "$out/pgwire.txt"
diff "$here/pgwire_check.out" "$out/pgwire.txt" || fail "psql gave other rows"
stop fyrql-smoke-pgwire

start fyrql-smoke-flight "$flight" 32010 flight-sql.yaml
"$python" "$here/flight_check.py" grpc://127.0.0.1:32010 smoke
stop fyrql-smoke-flight

echo "The smoke tests of $pgwire and $flight passed"
