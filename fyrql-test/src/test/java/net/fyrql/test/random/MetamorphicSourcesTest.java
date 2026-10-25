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

package net.fyrql.test.random;

import net.fyrql.connector.jdbc.JdbcConnector;
import net.fyrql.test.db.TestDatabase;
import net.fyrql.test.db.TestDatabases;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The {@linkplain Metamorphic metamorphic checks} over the
 * {@linkplain RandomSources other sources}: Parquet, CSV and JSON files,
 * Excel workbooks, H2, SQLite and PostgreSQL, Derby and HSQLDB through the
 * generic dialect, and Iceberg. The queries are generated over
 * the tables as each source gives them, with literals from samples of their
 * rows; a difference that is not known fails the test, and the source must
 * have done its part of the work.
 */
class MetamorphicSourcesTest {

    private static void check(RandomSources.Source source) {
        try (source) {
            Map<String, List<Object[]>> samples = Metamorphic.samples(source.engine(), source.tables());
            Metamorphic run = new Metamorphic("TLP and NoREC, " + source.name(), source.engine());
            for (int i = 0; i < RandomRun.QUERIES; i++) {
                run.check(i, new SqlGenerator(RandomRun.random(i), source.engine(), source.tables(), samples)
                        .partition());
            }
            System.out.println(run.report());
            assertTrue(run.unexplained().isEmpty(), run.report());
            source.check().run();
        }
    }

    @Test
    void parquet(@TempDir Path dir) {
        check(RandomSources.parquet(dir));
    }

    @Test
    void csv(@TempDir Path dir) {
        check(RandomSources.csv(dir));
    }

    @Test
    void xlsx(@TempDir Path dir) {
        check(RandomSources.xlsx(dir));
    }

    @Test
    void json(@TempDir Path dir) {
        check(RandomSources.json(dir));
    }

    @Test
    void h2() {
        check(RandomSources.database("H2", TestDatabases.h2()));
    }

    @Test
    void sqlite() {
        check(RandomSources.database("SQLite", TestDatabases.sqlite()));
    }

    @Test
    void postgres() {
        Optional<TestDatabase> postgres = TestDatabases.postgres();
        assumeTrue(postgres.isPresent(), "PostgreSQL is off");
        check(RandomSources.database("PostgreSQL", postgres.get()));
    }

    @Test
    void derby() {
        check(RandomSources.database("Derby", TestDatabases.derby(), TestDatabases::derbyTypeName,
                b -> b.limitStyle(JdbcConnector.LimitStyle.FETCH_FIRST)));
    }

    @Test
    void hsqldb() {
        check(RandomSources.database("HSQLDB", TestDatabases.hsqldb(), TestDatabases::hsqldbTypeName, b -> {
        }));
    }

    @Test
    void iceberg() {
        check(RandomSources.iceberg());
    }
}
