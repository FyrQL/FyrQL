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

package net.fyrql.test.connector;

import net.fyrql.sql.render.GenericDialect;
import net.fyrql.test.db.TestDatabases;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The conformance of the JDBC connector over every database the tests
 * reach: H2, SQLite and PostgreSQL always, MySQL in the profile
 * {@code containers}; and through the generic dialect H2 in the modes of
 * compatibility with Oracle, SQL Server and MySQL, Derby and HSQLDB, always.
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

    @Test
    void h2Oracle() {
        JdbcConformance.check(JdbcConformance.h2("Oracle", b -> {
        }));
    }

    @Test
    void h2SqlServer() {
        JdbcConformance.check(JdbcConformance.h2("MSSQLServer", b -> b.quote("[")
                .limitStyle(GenericDialect.LimitStyle.TOP)));
    }

    @Test
    void h2MySql() {
        JdbcConformance.check(JdbcConformance.h2("MySQL", b -> b.limitStyle(GenericDialect.LimitStyle.LIMIT)));
    }

    @Test
    void derby() {
        JdbcConformance.check(JdbcConformance.derby());
    }

    @Test
    void hsqldb() {
        JdbcConformance.check(JdbcConformance.hsqldb());
    }
}
