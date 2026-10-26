# Spring Boot

Part of the [FyrQL manual](manual.md).

With the module `fyrql-spring-boot-starter`, an application of Spring Boot 4 gets an
engine as a bean, configured in its `application.yml`. The application adds the starter
and the connectors of its sources; Spring Boot it brings itself, in its own version of
4.x (the starter is built and tested with 4.1.2):

```xml
<dependency>
    <groupId>io.github.fyrql</groupId>
    <artifactId>fyrql-spring-boot-starter</artifactId>
    <version>0.19.5</version>
</dependency>
<dependency>
    <groupId>io.github.fyrql</groupId>
    <artifactId>fyrql-connector-jdbc</artifactId>
    <version>0.19.5</version>
</dependency>
```

The keys under `fyrql` take the form of the [configuration
file](manual.md#configuration-file), with the settings under `engine`, the sources under
`sources` and the [views](manual.md#views) under `views`:

```yaml
fyrql:
  engine:
    threads: 4
    memory-budget: 2GiB
    query-timeout: 30s
  sources:
    - name: sales
      type: jdbc
      url: jdbc:postgresql://db/sales
      user: sales
      password: ${DB_PASSWORD}
    - name: files
      type: csv
      directory: data
  views:
    - name: sales.public.revenue_by_region
      columns: [region, revenue]
      sql: |
        SELECT r.region, sum(o.amount)
        FROM sales.orders AS o JOIN files.regions AS r ON o.region_id = r.id
        GROUP BY r.region
```

```java
@Service
class Revenue {

    private final FyrQL engine;

    Revenue(FyrQL engine) {
        this.engine = engine;
    }

    List<Object[]> byRegion() {
        return engine.rows("SELECT region, revenue FROM sales.public.revenue_by_region");
    }
}
```

The settings under `fyrql.engine` are those of [Engine
settings](manual.md#engine-settings), with the relaxed names of Spring: `memory-budget`
and `memoryBudget` are the same key, and an IDE completes them from the metadata of the
starter. `memory-budget` takes the units of FyrQL, so that `512MB` are 512,000,000 bytes
and `512MiB` 536,870,912, not those of Spring; `query-timeout` and `max-query-timeout` are
durations such as `30s` or `5m`, or `0` for none. The sources under `fyrql.sources` are
those of the file: a `name`, a `type` and the options of the type below them, with nested
keys joined by dots and lists by commas. Unlike the settings, the options keep the names
of their connector exactly, such as `recordStatements`, since Spring does not relax the
keys of a map; for the same reason an option cannot come from an environment variable
named after its key, which Spring reads in lower case, but from a placeholder in the YAML.
The views under `fyrql.views` are those of the file too: a `name`, its query under `sql`
and optionally its `columns`; the engine creates them in their order as it is built.

Placeholders are those of Spring, such as `${DB_PASSWORD}`, which Spring resolves from
environment variables, system properties and its other sources. The form
`${env:NAME}` of the configuration file does not work in `application.yml`: Spring reads
it as the property `env` with the default `NAME` and gives the text `NAME`, without an
error.

`fyrql.config` names a configuration file of FyrQL in addition. Its sources come first,
with `${env:NAME}` as in any file and relative paths against its directory, then those
under `fyrql.sources`, and so do its views before those under `fyrql.views`; the
settings under `fyrql.engine` replace those of the file.
Relative paths under `fyrql.sources` and `fyrql.engine` are resolved against the working
directory. Two sources with the same name, in any case, are an error.

The engine closes with the application context. A bean of the type `FyrQL` of the
application replaces it, and `fyrql.enabled: false` turns the starter off. Sources and
functions of the application, such as tables in memory, join through beans of the type
`FyrQLBuilderCustomizer`, which the starter applies in the order of their `@Order` before
it builds the engine:

```java
@Bean
FyrQLBuilderCustomizer products(ProductRepository repository) {
    return builder -> builder.register(MemoryConnector.builder("app")
            .table("products", Product.class, repository.findAll()).build());
}
```

If the engine cannot be built, the application does not start, and the message names the
setting, the source or the view at fault; the connectors opened until then are closed.

With `fyrql.datasource.enabled: true`, there is a `DataSource` of the engine, the bean
`fyrqlDataSource`, for code that wants JDBC, such as a `JdbcTemplate`. Its connections
share the engine and do not close it, each a [session](manual.md#sessions) of its own, as
with `new FyrQLDataSource(engine)` of the [JDBC driver](manual.md#jdbc-driver). It is no
default candidate: the `DataSource` of the application, which Spring Boot still creates
from `spring.datasource`, stays the one that is injected by its type, and that of FyrQL is
injected by its name:

```java
@Bean
JdbcTemplate fyrqlTemplate(@Qualifier("fyrqlDataSource") DataSource dataSource) {
    return new JdbcTemplate(dataSource);
}
```

An application that wants FyrQL as its only `DataSource` defines the bean itself, as
`new FyrQLDataSource(engine)`.

With the health module of Spring Boot, which `spring-boot-starter-actuator` brings, the
health indicator `fyrql` checks each source of the configuration by listing its schemas,
which reaches the database or service behind it. It is up when all answer and down when
one fails; its details give the status of each source and the error of one that failed,
shown as Spring shows details (`management.endpoint.health.show-details`). Sources that
a customizer registers, and those of the file formats, are not checked; without sources
of the configuration, the indicator runs `SELECT 1`. `management.health.fyrql.enabled:
false` turns it off. The messages of the indicator hold no passwords, keys or tokens of
the sources. The endpoints `env` and `configprops` of Actuator show the values of
`fyrql.sources`, the passwords among them, only with `show-values` set to `always`, or to
`when-authorized` for the users it authorizes; by default, `never`, Spring Boot masks
them.
