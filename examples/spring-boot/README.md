# Spring Boot

A REST application of Spring Boot 4 with the starter `fyrql-spring-boot-starter`. The
starter builds the engine of FyrQL as a bean from `application.yml`, and the application
uses it in two ways: through the bean, and through its `DataSource` with a
`JdbcTemplate`. The orders are in the database of the application, H2 in memory, which
Spring Boot fills from [`schema.sql`](src/main/resources/schema.sql) and
[`data.sql`](src/main/resources/data.sql); the customers are in a CSV file,
[`data/customers.csv`](data/customers.csv).

The [`pom.xml`](pom.xml) has the parent `spring-boot-starter-parent`, as an application
of Spring Boot usually has, and adds the starter of FyrQL and the connectors of its
sources:

```xml
<dependency>
    <groupId>io.github.stefan-zobel</groupId>
    <artifactId>fyrql-spring-boot-starter</artifactId>
    <version>${fyrql.version}</version>
</dependency>
<dependency>
    <groupId>io.github.stefan-zobel</groupId>
    <artifactId>fyrql-connector-csv</artifactId>
    <version>${fyrql.version}</version>
</dependency>
<dependency>
    <groupId>io.github.stefan-zobel</groupId>
    <artifactId>fyrql-connector-jdbc</artifactId>
    <version>${fyrql.version}</version>
</dependency>
```

[`application.yml`](src/main/resources/application.yml) names the CSV source and asks
for the `DataSource` of the engine:

```yaml
fyrql:
  engine:
    threads: 2
  sources:
    - name: files
      type: csv
      directory: data
  datasource:
    enabled: true
```

The database of the application joins the engine as the source `shop` through a
`FyrQLBuilderCustomizer`, which the starter applies before it builds the engine
([`ShopApplication.java`](src/main/java/net/fyrql/examples/springboot/ShopApplication.java)):

```java
@Bean
@DependsOnDatabaseInitialization
FyrQLBuilderCustomizer shopDatabase(DataSource dataSource) {
    return builder -> builder.register(JdbcConnector.builder("shop").dataSource(dataSource).build());
}
```

The controller
([`ShopController.java`](src/main/java/net/fyrql/examples/springboot/ShopController.java))
takes the engine, and its `DataSource` by the name of the bean, `fyrqlDataSource`: by its
type, the `DataSource` that is injected stays that of the application.

```java
public ShopController(FyrQL engine, @Qualifier("fyrqlDataSource") DataSource fyrqlDataSource) {
    this.engine = engine;
    this.fyrql = new JdbcTemplate(fyrqlDataSource);
}

@GetMapping("/revenue")
public List<Revenue> revenue() {
    return engine.rows("""
            SELECT c.region, count(*) AS orders, sum(o.amount) AS revenue
            FROM shop.orders AS o JOIN files.customers AS c ON o.customer_id = c.id
            GROUP BY c.region
            ORDER BY revenue DESC""").stream()
            .map(row -> new Revenue((String) row[0], (Long) row[1], (BigDecimal) row[2]))
            .toList();
}

@GetMapping("/customers")
public List<Customer> customers(@RequestParam String region) {
    return fyrql.query("SELECT id, name FROM files.customers WHERE region = ? ORDER BY id",
            (rs, i) -> new Customer(rs.getLong("id"), rs.getString("name")), region);
}
```

## Running it

FyrQL is not yet published, so install it once from the root of the repository, then
start the application in this directory and ask it from another shell:

```
./mvnw -B -ntp install -DskipTests
cd examples/spring-boot
../../mvnw -q spring-boot:run
```

Without installing, the build of FyrQL builds and tests it from the root:
`./mvnw -Pexamples -pl examples/spring-boot -am verify`.

## Output

```text
$ curl -s 'localhost:8080/revenue'
[{"region":"south","orders":3,"revenue":536.00},{"region":"north","orders":5,"revenue":260.75},{"region":"east","orders":2,"revenue":251.50}]
$ curl -s 'localhost:8080/customers?region=north'
[{"id":1,"name":"Alder & Co"},{"id":3,"name":"Cedar Inc"}]
$ curl -s 'localhost:8080/actuator/health/fyrql'
{"details":{"files":{"status":"UP"}},"status":"UP"}
```

A test of the example starts the application on a free port, sends these requests and
checks that the answers are exactly these.

## What to look at

- **One query, two sources:** `/revenue` joins the orders of the database of the
  application with the customers of the CSV file.
- **Two `DataSource`s:** Spring Boot still creates the one of the application from
  `spring.datasource`, which the starter does not replace; the one of FyrQL is the bean
  `fyrqlDataSource`, which only a `@Qualifier` gets. Its connections share the engine and
  do not close it.
- **Health:** the indicator `fyrql` of the starter checks the sources of the
  configuration, here `files`; `management.endpoint.health.show-details: always` shows
  them. A source that a customizer registers, as `shop` here, is not checked.
- **`@DependsOnDatabaseInitialization`** lets the engine be built after Spring Boot has
  run `schema.sql` and `data.sql`.
- **The parent of Spring Boot** manages the versions of the libraries of the
  application, Jackson among them; FyrQL keeps the parser of its configuration file
  under names of its own, so that they do not meet.

More in the manual: [Spring Boot](../../docs/manual.md#spring-boot).
