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

package net.fyrql.connector.s3;

import net.fyrql.connector.BatchReader;
import net.fyrql.connector.Catalog;
import net.fyrql.connector.Connector;
import net.fyrql.connector.FileStore;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.connector.Split;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.Schema;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The buckets of S3, or of a store with its API such as MinIO, as the
 * store of the files of {@code s3://bucket/key} locations, see
 * {@link #fileStore()}: the sources of file formats read and write them,
 * as in {@code SELECT * FROM 's3://lake/events/*.parquet'} or
 * {@code COPY t TO 's3://lake/t.parquet'}. The source has no tables of its
 * own. An engine has at most one source of this kind.
 * <p>
 * Requests go over HTTP, signed with the Signature Version 4 of AWS if
 * there are credentials; without them they are sent unsigned, as for a
 * public bucket. What the source is not given it takes from the
 * environment as the tools of AWS do, see {@link Builder}.
 */
public final class S3Connector implements Connector {

    /** The bytes of a part of an upload by default: 8 MiB. */
    public static final int PART_SIZE = 8 << 20;

    /** The smallest part S3 takes, but for the last one: 5 MiB. */
    public static final int MIN_PART_SIZE = 5 << 20;

    private final String name;
    private final S3FileStore store;
    private final Catalog catalog = new Catalog() {
        @Override
        public List<String> schemas() {
            return List.of(TableRef.MAIN);
        }

        @Override
        public List<String> tables(String schema) {
            return List.of();
        }

        @Override
        public Schema schema(TableRef table) {
            throw noTables();
        }
    };

    private S3Connector(String name, S3FileStore store) {
        this.name = name;
        this.store = store;
    }

    /** Starts a connector with the name under which it will be registered. */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Catalog catalog() {
        return catalog;
    }

    private IllegalArgumentException noTables() {
        return new IllegalArgumentException("The S3 source " + name + " has no tables; read its files with"
                + " FROM 's3://bucket/key' or a table function such as read_parquet");
    }

    @Override
    public ScanPlan plan(TableRef table, ScanRequest request) {
        throw noTables();
    }

    @Override
    public List<Split> splits(ScanPlan plan) {
        throw noTables();
    }

    @Override
    public BatchReader open(Split split) {
        throw noTables();
    }

    /** Returns the store of the files of {@code s3://} locations. */
    @Override
    public Optional<FileStore> fileStore() {
        return Optional.of(store);
    }

    /** Returns the endpoint the requests go to. */
    public URI endpoint() {
        return store.client().endpoint();
    }

    /** Returns the region of the signatures. */
    public String region() {
        return store.client().region();
    }

    /** Tells whether the bucket is the first part of the path rather than of the host name. */
    public boolean pathStyle() {
        return store.client().pathStyle();
    }

    @Override
    public void close() {
        store.client().close();
    }

    /**
     * Collects the settings of an S3 source. What is not set comes from the
     * environment: the endpoint from {@code AWS_ENDPOINT_URL_S3} or
     * {@code AWS_ENDPOINT_URL}, else that of AWS in the region; the region
     * from {@code AWS_REGION} or {@code AWS_DEFAULT_REGION}, else
     * {@code us-east-1}; the credentials from {@code AWS_ACCESS_KEY_ID},
     * {@code AWS_SECRET_ACCESS_KEY} and {@code AWS_SESSION_TOKEN}, else from
     * the profile of {@code ~/.aws/credentials} that {@link #profile} or
     * {@code AWS_PROFILE} names, {@code default} by default, else none.
     */
    public static final class Builder {
        private final String name;
        private String endpoint;
        private String region;
        private S3Credentials credentials;
        private String profile;
        private Boolean pathStyle;
        private Duration timeout = Duration.ofSeconds(30);
        private int partSize = PART_SIZE;
        private int retries = 3;
        private Map<String, String> environment;
        private Path home;
        private boolean useEnvironment = true;

        private Builder(String name) {
            this.name = Objects.requireNonNull(name, "name");
        }

        /** Sets the endpoint, such as {@code http://127.0.0.1:9000} for MinIO. */
        public Builder endpoint(String endpoint) {
            this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
            return this;
        }

        /** Sets the region of the signatures and of the endpoint of AWS, such as {@code eu-central-1}. */
        public Builder region(String region) {
            this.region = Objects.requireNonNull(region, "region");
            return this;
        }

        /** Sets the credentials. */
        public Builder credentials(S3Credentials credentials) {
            this.credentials = Objects.requireNonNull(credentials, "credentials");
            return this;
        }

        /** Sets the profile of {@code ~/.aws/credentials} to take the credentials from. */
        public Builder profile(String profile) {
            this.profile = Objects.requireNonNull(profile, "profile");
            return this;
        }

        /**
         * Sets whether the bucket is the first part of the path, as MinIO
         * needs it, rather than of the host name; by default it is with an
         * endpoint that is set and not with the endpoint of AWS.
         */
        public Builder pathStyle(boolean pathStyle) {
            this.pathStyle = pathStyle;
            return this;
        }

        /** Sets the time to wait for a connection and for an answer; 30 seconds by default. */
        public Builder timeout(Duration timeout) {
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("The timeout must be positive: " + timeout);
            }
            this.timeout = timeout;
            return this;
        }

        /** Sets the bytes of a part of an upload; 8 MiB by default, at least 5 MiB. */
        public Builder partSize(int bytes) {
            if (bytes < MIN_PART_SIZE) {
                throw new IllegalArgumentException("The part size must be at least 5 MiB: " + bytes);
            }
            this.partSize = bytes;
            return this;
        }

        /** Sets how often a request that failed in a way that may pass is tried again; 3 by default. */
        public Builder retries(int retries) {
            if (retries < 0) {
                throw new IllegalArgumentException("The retries must not be negative: " + retries);
            }
            this.retries = retries;
            return this;
        }

        /**
         * Sets whether what is not set comes from the environment and the
         * file of credentials; on by default.
         */
        public Builder useEnvironment(boolean use) {
            this.useEnvironment = use;
            return this;
        }

        /** Sets the environment and home directory to take what is not set from, instead of those of the process. */
        Builder environment(Map<String, String> environment, Path home) {
            this.environment = environment;
            this.home = home;
            return this;
        }

        /**
         * Builds the connector.
         *
         * @throws IllegalArgumentException if the endpoint is no URI of
         *         {@code http} or {@code https}, or the environment or the
         *         file of credentials has incomplete credentials
         */
        public S3Connector build() {
            CredentialChain chain = environment != null ? new CredentialChain(environment, home)
                    : useEnvironment ? CredentialChain.system() : new CredentialChain(Map.of(), null);
            String r = region != null ? region : chain.region().orElse("us-east-1");
            String e = endpoint != null ? endpoint : chain.endpoint().orElse(null);
            boolean path = pathStyle != null ? pathStyle : e != null;
            URI uri;
            try {
                uri = URI.create(e != null ? e : "https://s3." + r + ".amazonaws.com");
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("The endpoint of the S3 source " + name + " is no URI: " + e, ex);
            }
            if (uri.getScheme() == null || !(uri.getScheme().equalsIgnoreCase("http")
                    || uri.getScheme().equalsIgnoreCase("https")) || uri.getHost() == null) {
                throw new IllegalArgumentException("The endpoint of the S3 source " + name
                        + " must be a URI of http or https with a host: " + e);
            }
            S3Credentials c = credentials;
            if (c == null) {
                try {
                    c = chain.credentials(profile).orElse(null);
                } catch (IllegalArgumentException ex) {
                    throw new IllegalArgumentException("The S3 source " + name + ": " + ex.getMessage(), ex);
                }
            }
            S3Client client = new S3Client(name, uri, r, path, c, timeout, retries);
            return new S3Connector(name, new S3FileStore(client, partSize));
        }
    }
}
