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

import net.fyrql.connector.ConfigOptions;
import net.fyrql.connector.ConnectorFactory;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Creates S3 sources of the type {@code s3} from options: {@code endpoint},
 * such as {@code http://127.0.0.1:9000} for MinIO; {@code region};
 * {@code accessKey} and {@code secretKey}, both or neither, and
 * {@code sessionToken}; {@code profile} of {@code ~/.aws/credentials};
 * {@code pathStyle}; {@code timeout}, such as {@code 30s}; {@code partSize},
 * such as {@code 8MB}; and {@code retries}. What is not given comes from
 * the environment, see {@link S3Connector.Builder}. No message names the
 * value of an option.
 */
public final class S3ConnectorFactory implements ConnectorFactory {

    @Override
    public String type() {
        return "s3";
    }

    @Override
    public S3Connector create(String name, Map<String, String> options, Path directory) {
        return create(name, options, directory, null, null);
    }

    /** Creates a source with an environment and home directory instead of those of the process, for tests. */
    S3Connector create(String name, Map<String, String> options, Path directory, Map<String, String> environment,
            Path home) {
        String owner = "the S3 source " + name;
        ConfigOptions o = new ConfigOptions(owner, options, directory);
        S3Connector.Builder b = S3Connector.builder(name);
        o.string("endpoint").ifPresent(b::endpoint);
        o.string("region").ifPresent(b::region);
        Optional<String> access = o.string("accessKey");
        Optional<String> secret = o.string("secretKey");
        Optional<String> token = o.string("sessionToken");
        o.string("profile").ifPresent(b::profile);
        o.bool("pathStyle").ifPresent(b::pathStyle);
        o.duration("timeout").ifPresent(b::timeout);
        Optional<Long> partSize = o.bytes("partSize");
        o.integer("retries").ifPresent(b::retries);
        o.rejectUnknown();
        if (access.isPresent() != secret.isPresent()) {
            throw new IllegalArgumentException("The options accessKey and secretKey of " + owner
                    + " go together; " + (access.isPresent() ? "secretKey" : "accessKey") + " is missing");
        }
        if (token.isPresent() && access.isEmpty()) {
            throw new IllegalArgumentException("The option sessionToken of " + owner
                    + " needs accessKey and secretKey");
        }
        access.ifPresent(a -> b.credentials(new S3Credentials(a, secret.get(), token.orElse(null))));
        if (partSize.isPresent()) {
            if (partSize.get() < S3Connector.MIN_PART_SIZE || partSize.get() > Integer.MAX_VALUE - 8) {
                throw new IllegalArgumentException("The option partSize of " + owner
                        + " must be at least 5MB and less than 2GB");
            }
            b.partSize(partSize.get().intValue());
        }
        if (environment != null) {
            b.environment(environment, home);
        }
        return b.build();
    }
}
