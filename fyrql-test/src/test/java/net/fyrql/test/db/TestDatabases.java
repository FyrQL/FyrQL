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

package net.fyrql.test.db;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import net.fyrql.connector.jdbc.JdbcDialect;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.mysql.MySQLContainer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The databases the tests run against, each new and empty:
 * <ul>
 * <li>H2 in memory and SQLite in a temporary file, always;</li>
 * <li>PostgreSQL from binaries the build unpacks and starts in a
 * directory of its own, one server for all tests of a run, with a
 * database of its own for every test; {@code -Dfyrql.test.postgres=off}
 * leaves it out;</li>
 * <li>MySQL in Docker, in the profile {@code containers}, if Docker
 * runs.</li>
 * </ul>
 * A server of one's own takes the place of the started one with
 * {@code -Dfyrql.test.postgres.url=jdbc:postgresql://...} or
 * {@code -Dfyrql.test.mysql.url=jdbc:mysql://...}, and {@code .user} and
 * {@code .password} in the same way; the tests then use the database of
 * the URL as it is and replace their tables in it.
 */
public final class TestDatabases {

    private static final AtomicInteger COUNT = new AtomicInteger();

    /** The image of MySQL: the release with long-term support. */
    private static final String MYSQL_IMAGE = "mysql:8.4";

    private TestDatabases() {
    }

    /** A new H2 database in memory, which lives until the tests end. */
    public static TestDatabase h2() {
        return new TestDatabase(JdbcDialect.H2, "jdbc:h2:mem:fyrqltest" + COUNT.incrementAndGet() + ";DB_CLOSE_DELAY=-1",
                null, null);
    }

    /** A new SQLite database in a file that is deleted when the tests end. */
    public static TestDatabase sqlite() {
        try {
            Path dir = Files.createTempDirectory("fyrql-sqlite");
            dir.toFile().deleteOnExit();
            Path file = dir.resolve("test" + COUNT.incrementAndGet() + ".db");
            file.toFile().deleteOnExit();
            return new TestDatabase(JdbcDialect.SQLITE, "jdbc:sqlite:" + file, null, null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A new PostgreSQL database, or the one of {@code fyrql.test.postgres.url};
     * empty only if {@code fyrql.test.postgres} is {@code off}. That the
     * server cannot start is an error, not a reason to skip.
     */
    public static Optional<TestDatabase> postgres() {
        Optional<TestDatabase> configured = configured("postgres", JdbcDialect.POSTGRESQL);
        if (configured.isPresent() || "off".equalsIgnoreCase(System.getProperty("fyrql.test.postgres"))) {
            return configured;
        }
        EmbeddedPostgres server = EmbeddedServer.SERVER;
        String name = "fyrqltest" + COUNT.incrementAndGet();
        new TestDatabase(JdbcDialect.POSTGRESQL, server.getJdbcUrl("postgres", "postgres"), "postgres", null)
                .execute("CREATE DATABASE " + name + " ENCODING 'UTF8' LC_COLLATE 'C' LC_CTYPE 'C' TEMPLATE template0");
        return Optional.of(new TestDatabase(JdbcDialect.POSTGRESQL, server.getJdbcUrl("postgres", name), "postgres",
                null));
    }

    /**
     * A new MySQL database, or the one of {@code fyrql.test.mysql.url};
     * empty unless the profile {@code containers} is on and Docker runs.
     */
    public static Optional<TestDatabase> mysql() {
        Optional<TestDatabase> configured = configured("mysql", JdbcDialect.MYSQL);
        if (configured.isPresent() || !Boolean.getBoolean("fyrql.test.containers") || !Docker.RUNS) {
            return configured;
        }
        MySQLContainer server = MySqlServer.SERVER;
        String name = "fyrqltest" + COUNT.incrementAndGet();
        TestDatabase root = mysqlDatabase(server, "");
        root.execute("CREATE DATABASE " + name + " CHARACTER SET utf8mb4");
        return Optional.of(mysqlDatabase(server, name));
    }

    private static TestDatabase mysqlDatabase(MySQLContainer server, String database) {
        return new TestDatabase(JdbcDialect.MYSQL, "jdbc:mysql://" + server.getHost() + ":"
                + server.getMappedPort(MySQLContainer.MYSQL_PORT) + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true", "root", server.getPassword());
    }

    /** A database given by the system properties {@code fyrql.test.<name>.url}, {@code .user} and {@code .password}. */
    private static Optional<TestDatabase> configured(String name, JdbcDialect dialect) {
        String url = System.getProperty("fyrql.test." + name + ".url");
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new TestDatabase(dialect, url, System.getProperty("fyrql.test." + name + ".user"),
                System.getProperty("fyrql.test." + name + ".password")));
    }

    /** The PostgreSQL server of the run, started on first use and stopped when the tests end. */
    private static final class EmbeddedServer {

        static final EmbeddedPostgres SERVER = start();

        private static EmbeddedPostgres start() {
            try {
                // Messages in English, whatever the language of the machine.
                return EmbeddedPostgres.builder().setServerConfig("lc_messages", "C").setRegisterShutdownHook(true)
                        .start();
            } catch (IOException e) {
                throw new UncheckedIOException("PostgreSQL does not start; -Dfyrql.test.postgres=off leaves it out", e);
            }
        }
    }

    /** Whether Docker runs, asked once. */
    private static final class Docker {

        static final boolean RUNS = DockerClientFactory.instance().isDockerAvailable();
    }

    /** The MySQL server of the run, started on first use; Testcontainers stops it when the tests end. */
    private static final class MySqlServer {

        static final MySQLContainer SERVER = start();

        @SuppressWarnings("resource")
        private static MySQLContainer start() {
            MySQLContainer server = new MySQLContainer(MYSQL_IMAGE);
            server.start();
            return server;
        }
    }
}
