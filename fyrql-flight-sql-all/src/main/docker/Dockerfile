# Copyright 2026 the FyrQL authors
#
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
#
# This Source Code Form is "Incompatible With Secondary Licenses", as
# defined by the Mozilla Public License, v. 2.0.

# The image of the Flight SQL server. The context is the directory target of
# fyrql-flight-sql-all, which holds the JAR of the version:
#
#   docker build --pull --build-arg VERSION=<version> -f src/main/docker/Dockerfile target
#
# The configuration file is /config/fyrql.yaml; the data go under /data and the JDBC
# drivers of the databases of JDBC sources under /drivers. Without users or a token in the
# section server the server does not start, as it listens on 0.0.0.0.

ARG JRE=eclipse-temurin:21-jre-noble
FROM ${JRE}

ARG VERSION

LABEL org.opencontainers.image.title="FyrQL Flight SQL server" \
      org.opencontainers.image.description="FyrQL, a federated SQL engine on the JVM, for clients of Arrow Flight SQL such as ADBC and the JDBC driver of Arrow" \
      org.opencontainers.image.licenses="MPL-2.0" \
      org.opencontainers.image.source="https://github.com/FyrQL/FyrQL"

RUN groupadd --system --gid 10001 fyrql \
    && useradd --system --uid 10001 --gid fyrql --no-create-home --home-dir /nonexistent \
        --shell /usr/sbin/nologin fyrql \
    && mkdir -p /opt/fyrql /config /data /drivers

COPY --chmod=0444 fyrql-flight-sql-all-${VERSION}.jar /opt/fyrql/fyrql-flight-sql-all.jar

USER 10001:10001
WORKDIR /data
EXPOSE 32010

# Java is the first process, so that a stop reaches its shutdown hook, which closes the
# engine. The heap may take three quarters of the memory of the container instead of the
# quarter of the JVM; JAVA_TOOL_OPTIONS sets further options. Arrow needs the option of
# java.nio that the manifest of the JAR sets for java -jar.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "--add-opens=java.base/java.nio=ALL-UNNAMED", "-cp", \
    "/opt/fyrql/fyrql-flight-sql-all.jar:/drivers/*", "net.fyrql.flight.FlightSqlServerMain"]
CMD ["/config/fyrql.yaml", "--host", "0.0.0.0"]
