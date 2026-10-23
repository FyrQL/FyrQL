# FyrQL examples

Small programs that run, each in a directory of its own with a README that explains it
and shows its output. Each is a Maven project of its own, without a parent, so that it
can be copied out of here; it names the version of FyrQL in the property
`fyrql.version` of its `pom.xml`.

| Example | Shows | Modules |
| --- | --- | --- |
| [federated-query](federated-query/README.md) | One query over a database, a CSV file and a JSON file, with the plan, what the database took over and the statements it received | `fyrql-engine`, `fyrql-codegen`, `fyrql-connector-jdbc`, `-csv`, `-json` |
| [move-data](move-data/README.md) | A database into a Parquet file and back with `COPY ... TO`, a query over the file and `CREATE TABLE ... AS`, and a CSV file into a table with `COPY ... FROM` | `fyrql-engine`, `fyrql-connector-jdbc`, `-parquet`, `-csv` |
| [config-and-jdbc](config-and-jdbc/README.md) | Sources in `fyrql.yaml` with a password from the environment, used through plain JDBC with `DatabaseMetaData` and a `PreparedStatement`, and the setup in DBeaver | `fyrql-jdbc`, `fyrql-connector-jdbc`, `-csv` |
| [custom-connector](custom-connector/README.md) | A connector of its own through the SPI: a factory found by the `ServiceLoader`, filters taken over or declined as the rules of the SPI ask, parts read on several threads | `fyrql-connector-spi`, `fyrql-engine`, `fyrql-config` |
| [spring-boot](spring-boot/README.md) | A REST application of Spring Boot 4 with the engine as a bean from `application.yml`, the database of the application as a source through a `FyrQLBuilderCustomizer`, a `JdbcTemplate` over `fyrqlDataSource` and the health of the sources | `fyrql-spring-boot-starter`, `fyrql-connector-csv`, `-jdbc` |

## Running them

FyrQL is not yet published, so install it once from the root of the repository; then
each example runs in its directory:

```
./mvnw -B -ntp install -DskipTests
cd examples/federated-query
../../mvnw -q compile exec:java
```

The application of `spring-boot` starts with `../../mvnw -q spring-boot:run` instead,
and `config-and-jdbc` wants the password of its database in the environment variable
`SALES_PASSWORD`, as their READMEs say.

The build of FyrQL builds and tests all examples against its own modules, without
installing them, with the profile `examples`:

```
./mvnw -B -ntp -Pexamples verify
```

The test of each example runs it and checks that it prints exactly what its README
shows under "Output".

More: [Getting started](../docs/getting-started.md) and the [manual](../docs/manual.md).
