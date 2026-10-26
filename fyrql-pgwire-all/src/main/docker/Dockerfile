# Copyright 2026 the FyrQL authors
#
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
#
# This Source Code Form is "Incompatible With Secondary Licenses", as
# defined by the Mozilla Public License, v. 2.0.

# The image of the PostgreSQL wire protocol server. The context is the directory target of
# fyrql-pgwire-all, which holds the JAR of the version:
#
#   docker build --pull --build-arg VERSION=<version> -f src/main/docker/Dockerfile target
#
# The configuration file is /config/fyrql.yaml; the data go under /data and the JDBC
# drivers of the databases of JDBC sources under /drivers. Without users in the section
# pgwire the server does not start, as it listens on 0.0.0.0.

ARG JRE=eclipse-temurin:21-jre-noble
FROM ${JRE}

ARG VERSION

LABEL org.opencontainers.image.title="FyrQL PostgreSQL wire protocol server" \
      org.opencontainers.image.description="FyrQL, a federated SQL engine on the JVM, for psql, DBeaver, Metabase, Grafana and Superset" \
      org.opencontainers.image.licenses="MPL-2.0" \
      org.opencontainers.image.source="https://github.com/FyrQL/FyrQL"

RUN groupadd --system --gid 10001 fyrql \
    && useradd --system --uid 10001 --gid fyrql --no-create-home --home-dir /nonexistent \
        --shell /usr/sbin/nologin fyrql \
    && mkdir -p /opt/fyrql /config /data /drivers

COPY --chmod=0444 fyrql-pgwire-all-${VERSION}.jar /opt/fyrql/fyrql-pgwire-all.jar

USER 10001:10001
WORKDIR /data
EXPOSE 5433

# Java is the first process, so that a stop reaches its shutdown hook, which closes the
# engine. The heap may take three quarters of the memory of the container instead of the
# quarter of the JVM; JAVA_TOOL_OPTIONS sets further options.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-cp", "/opt/fyrql/fyrql-pgwire-all.jar:/drivers/*", \
    "net.fyrql.pgwire.PgWireServerMain"]
CMD ["/config/fyrql.yaml", "--host", "0.0.0.0"]
