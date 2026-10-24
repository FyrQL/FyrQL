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

package net.fyrql.test.db;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import net.fyrql.connector.jdbc.JdbcDialect;
import net.fyrql.core.type.DataType;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.mysql.MySQLContainer;

import java.io.File;
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
 * <li>for the generic dialect H2 in memory in a mode of compatibility,
 * Derby and HSQLDB in memory, always;</li>
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

    /**
     * A new H2 database in memory in a mode of compatibility, such as
     * {@code Oracle}, read through the generic dialect.
     */
    public static TestDatabase h2Mode(String mode) {
        return new TestDatabase(JdbcDialect.GENERIC, "jdbc:h2:mem:fyrqltest" + COUNT.incrementAndGet()
                + ";DB_CLOSE_DELAY=-1;MODE=" + mode, null, null);
    }

    /** A new Derby database in memory, read through the generic dialect; its log goes to a temporary file. */
    public static TestDatabase derby() {
        DerbyLog.set();
        return new TestDatabase(JdbcDialect.GENERIC, "jdbc:derby:memory:fyrqltest" + COUNT.incrementAndGet()
                + ";create=true", null, null);
    }

    /** A new HSQLDB database in memory, read through the generic dialect. */
    public static TestDatabase hsqldb() {
        return new TestDatabase(JdbcDialect.GENERIC, "jdbc:hsqldb:mem:fyrqltest" + COUNT.incrementAndGet(), "SA",
                "");
    }

    /**
     * The name of a type in Derby, for {@link TestDatabase#load(String,
     * net.fyrql.core.type.Schema, java.util.function.Function, java.util.List)}:
     * a {@code TINYINT} is a {@code SMALLINT}, a {@code UUID} a
     * {@code CHAR(36)} and a timestamp with a time zone one without;
     * {@code null} for a {@code HUGEINT}, since no decimal of Derby is as wide.
     */
    public static String derbyTypeName(DataType type) {
        return type == DataType.HUGEINT ? null : sql92(type, "SMALLINT", "BLOB", "TIMESTAMP", "CHAR(36)");
    }

    /**
     * The name of a type in HSQLDB, for {@link TestDatabase#load(String,
     * net.fyrql.core.type.Schema, java.util.function.Function, java.util.List)}:
     * a {@code HUGEINT} is a {@code DECIMAL(39,0)} and a timestamp with a
     * time zone one without.
     */
    public static String hsqldbTypeName(DataType type) {
        return type == DataType.HUGEINT ? "DECIMAL(39,0)"
                : sql92(type, "TINYINT", "VARBINARY(100)", "TIMESTAMP(6)", "UUID");
    }

    /** The name of a type in SQL-92, with the names of a database for the types that differ. */
    private static String sql92(DataType type, String tinyint, String blob, String timestamp, String uuid) {
        if (type instanceof DataType.Decimal d) {
            return "DECIMAL(" + d.precision() + "," + d.scale() + ")";
        }
        return switch ((DataType.Scalar) type) {
            case BOOLEAN -> "BOOLEAN";
            case TINYINT -> tinyint;
            case SMALLINT -> "SMALLINT";
            case INTEGER -> "INTEGER";
            case BIGINT -> "BIGINT";
            case FLOAT -> "REAL";
            case DOUBLE -> "DOUBLE";
            case VARCHAR -> "VARCHAR(100)";
            case BLOB -> blob;
            case DATE -> "DATE";
            case TIMESTAMP -> timestamp;
            case UUID -> uuid;
            case TIME -> "TIME";
            case TIMESTAMPTZ -> timestamp;
            case INTERVAL -> "VARCHAR(64)";
            case HUGEINT -> throw new IllegalArgumentException("No type for HUGEINT");
        };
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

    /** The file Derby logs to instead of {@code derby.log} in the working directory, set once. */
    private static final class DerbyLog {

        static {
            if (System.getProperty("derby.stream.error.file") == null) {
                try {
                    File file = Files.createTempFile("fyrql-derby", ".log").toFile();
                    file.deleteOnExit();
                    System.setProperty("derby.stream.error.file", file.getPath());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }

        /** Sets the file before Derby starts; the work is done once, when the class is first used. */
        static void set() {
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
