/*
 * Copyright 2026 the FyrQL authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fyrql.test.connector;

import net.fyrql.test.db.TestDatabases;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The conformance of the JDBC connector over every database the tests
 * reach: H2, SQLite and PostgreSQL always, MySQL in the profile
 * {@code containers}.
 */
class JdbcConformanceTest {

    @Test
    void h2() {
        JdbcConformance.check(TestDatabases.h2());
    }

    @Test
    void sqlite() {
        JdbcConformance.check(TestDatabases.sqlite());
    }

    @Test
    void postgres() {
        var db = TestDatabases.postgres();
        assumeTrue(db.isPresent(), "PostgreSQL is off");
        JdbcConformance.check(db.get());
    }

    @Test
    void mysql() {
        var db = TestDatabases.mysql();
        assumeTrue(db.isPresent(), "MySQL runs in Docker, in the profile containers");
        JdbcConformance.check(db.get());
    }
}
