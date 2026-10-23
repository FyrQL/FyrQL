# Security

## Supported versions

Until 1.0.0, only the newest release gets fixes. From 1.0.0 on, the newest 1.x release
does; there are no fixes for older releases, so that a fix comes with an update to the
newest one.

## Reporting a vulnerability

Please report a vulnerability privately, through
[Private vulnerability reporting](https://github.com/stefan-zobel/fyrql/security/advisories/new)
of GitHub, and not in a public issue. A report that names the version, the setting, the
statement or file that shows the problem and what it lets an attacker do helps most.

FyrQL is maintained in spare time: a report is acknowledged within a week, as far as
possible, and there is no bounty. A confirmed vulnerability gets a security advisory on
GitHub and a release with the fix; the advisory names the reporter, if they wish.

## Threat model

FyrQL is a library that runs the SQL its application gives it. What counts as a
vulnerability follows from who may give it SQL and settings:

- **The application is trusted.** The engine runs the statements the application sends,
  with the sources, settings and credentials the application or its configuration file
  gives it. A statement that reads or writes what these allow is no vulnerability, also
  when its text comes from a user the application did not check.
- **The Flight SQL server and the URLs of the JDBC driver are boundaries.** For the
  clients of the server and for a URL of the driver, the sign-in of the server,
  `fileAccess`, `allowedLocations`, `maxQueryTimeout`, `memoryBudget` and `maxQueries`
  apply. A client or URL that gets around one of them, such as reading a file outside the
  allowed locations, has found a vulnerability.
- **Secrets stay out of messages.** Passwords, tokens, keys and the values of headers of
  the configuration, and the user information and query of URLs, appear in no message of
  an exception, no event of JFR and no text of `EXPLAIN`. One that does is a
  vulnerability.

What FyrQL does not protect against, by design:

- **The URL of the JDBC driver is as trusted as the configuration file.** Its settings
  win over those of the file, `fileAccess` and the time limits too, so that whoever
  chooses the URL chooses what the engine allows.
- **Files must be well-formed in their sizes.** The readers of Parquet, Avro and Iceberg
  allocate the sizes that the headers of pages and blocks give, and read the metadata of
  an Iceberg table in full; the memory budget does not cover these buffers. A file that
  the operator does not trust can exhaust the heap. Avro records that contain themselves,
  annotations of OData nested more than 64 deep and XML answers of S3 above 16 MiB are
  refused.
- **The allowed directories belong to the operator.** The check of a location comes
  before the file is opened; whoever may change an allowed directory, such as by putting a
  link in place of a directory between the check and the read, can lead a statement
  elsewhere.
- **The Flight SQL server limits queries, not clients.** Sessions and statements stay
  until their idle timeouts, without an upper number, and a failed sign-in does not lock
  a user out. A server that others reach belongs behind a network that limits who
  connects. Clients that sign in without TLS on an address other than a loopback address
  send their passwords and tokens in plain text; the server warns about this at the start.
- **Spring Boot Actuator shows what it is told to show.** With `show-values: always` on
  the endpoints `env` or `configprops`, the passwords of `fyrql.sources` appear there;
  Spring Boot masks them by default.

The [manual](docs/manual.md) describes these settings under
[File access](docs/manual.md#file-access), [Time limits](docs/manual.md#time-limits),
[Memory budget and spilling](docs/manual.md#memory-budget-and-spilling) and
[Flight SQL server](docs/manual.md#flight-sql-server).
