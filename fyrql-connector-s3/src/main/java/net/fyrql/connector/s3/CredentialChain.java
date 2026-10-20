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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Finds what a source of S3 leaves open where the tools of AWS find it:
 * the region in {@code AWS_REGION} or {@code AWS_DEFAULT_REGION}, the
 * endpoint in {@code AWS_ENDPOINT_URL_S3} or {@code AWS_ENDPOINT_URL}, and
 * the credentials in {@code AWS_ACCESS_KEY_ID}, {@code AWS_SECRET_ACCESS_KEY}
 * and {@code AWS_SESSION_TOKEN}, or else in the profile of
 * {@code ~/.aws/credentials} that {@code AWS_PROFILE} names, {@code default}
 * by default. No message names a value.
 */
final class CredentialChain {

    private final Map<String, String> environment;
    private final Path home;

    /**
     * Creates the chain over an environment and a home directory.
     *
     * @param environment the variables of the environment
     * @param home the home directory, or {@code null} for none
     */
    CredentialChain(Map<String, String> environment, Path home) {
        this.environment = environment;
        this.home = home;
    }

    /** Returns the chain over the environment and the home directory of the process. */
    static CredentialChain system() {
        String home = System.getProperty("user.home");
        return new CredentialChain(System.getenv(), home == null ? null : Path.of(home));
    }

    private Optional<String> variable(String... names) {
        for (String name : names) {
            String value = environment.get(name);
            if (value != null && !value.isBlank()) {
                return Optional.of(value.strip());
            }
        }
        return Optional.empty();
    }

    /** Returns the region of the environment. */
    Optional<String> region() {
        return variable("AWS_REGION", "AWS_DEFAULT_REGION");
    }

    /** Returns the endpoint of the environment. */
    Optional<String> endpoint() {
        return variable("AWS_ENDPOINT_URL_S3", "AWS_ENDPOINT_URL");
    }

    /**
     * Returns the credentials of the environment, or else of a profile of
     * the file of credentials.
     *
     * @param profile the profile, or {@code null} for the one of the environment
     * @throws IllegalArgumentException if the environment or the profile has
     *         an access key without a secret key or the other way round, or a
     *         profile that is asked for does not exist
     */
    Optional<S3Credentials> credentials(String profile) {
        Optional<String> access = variable("AWS_ACCESS_KEY_ID");
        Optional<String> secret = variable("AWS_SECRET_ACCESS_KEY");
        if (access.isPresent() != secret.isPresent()) {
            throw new IllegalArgumentException("The environment has " + (access.isPresent()
                    ? "AWS_ACCESS_KEY_ID without AWS_SECRET_ACCESS_KEY" : "AWS_SECRET_ACCESS_KEY without"
                    + " AWS_ACCESS_KEY_ID"));
        }
        if (access.isPresent() && profile == null) {
            return Optional.of(new S3Credentials(access.get(), secret.get(),
                    variable("AWS_SESSION_TOKEN").orElse(null)));
        }
        String name = profile != null ? profile : variable("AWS_PROFILE").orElse("default");
        Map<String, String> section = profile(name);
        if (section == null) {
            if (profile != null || variable("AWS_PROFILE").isPresent()) {
                throw new IllegalArgumentException("The profile " + name + " is not in the file of credentials "
                        + file());
            }
            return Optional.empty();
        }
        String key = section.get("aws_access_key_id");
        String secretKey = section.get("aws_secret_access_key");
        if (key == null || secretKey == null) {
            throw new IllegalArgumentException("The profile " + name + " of the file of credentials " + file()
                    + " needs aws_access_key_id and aws_secret_access_key");
        }
        return Optional.of(new S3Credentials(key, secretKey, section.get("aws_session_token")));
    }

    private Path file() {
        return variable("AWS_SHARED_CREDENTIALS_FILE").map(Path::of)
                .orElse(home == null ? null : home.resolve(".aws").resolve("credentials"));
    }

    /** Returns the keys and values of a profile of the file of credentials, or {@code null}. */
    private Map<String, String> profile(String name) {
        Path file = file();
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the file of credentials " + file, e);
        }
        Map<String, String> section = null;
        boolean inside = false;
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                inside = line.substring(1, line.length() - 1).strip().equals(name);
                if (inside && section == null) {
                    section = new HashMap<>();
                }
                continue;
            }
            int eq = line.indexOf('=');
            if (inside && eq > 0) {
                section.put(line.substring(0, eq).strip().toLowerCase(java.util.Locale.ROOT),
                        line.substring(eq + 1).strip());
            }
        }
        return section;
    }
}
