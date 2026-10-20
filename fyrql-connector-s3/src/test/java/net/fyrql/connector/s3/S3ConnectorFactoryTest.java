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

import net.fyrql.connector.ConnectorFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3ConnectorFactoryTest {

    @TempDir
    Path home;

    private final S3ConnectorFactory factory = new S3ConnectorFactory();

    private static Map<String, String> map(String... keysAndValues) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            m.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return m;
    }

    private S3Connector create(Map<String, String> options, Map<String, String> environment) {
        return factory.create("s3", options, Path.of(""), environment, home);
    }

    private String failure(Map<String, String> options, Map<String, String> environment) {
        return assertThrows(IllegalArgumentException.class, () -> create(options, environment)).getMessage();
    }

    @Test
    void theFactoryIsFoundAndHasNoFormats() {
        List<String> types = ServiceLoader.load(ConnectorFactory.class).stream().map(p -> p.get().type()).toList();
        assertTrue(types.contains("s3"), types.toString());
        assertEquals(List.of(), factory.fileFormats());
    }

    @Test
    void theOptionsSetTheEndpointRegionAndAddressing() {
        try (S3Connector c = create(map("endpoint", "http://127.0.0.1:9000", "accessKey", "a", "secretKey", "b"),
                Map.of())) {
            assertEquals(URI.create("http://127.0.0.1:9000"), c.endpoint());
            assertEquals("us-east-1", c.region());
            assertTrue(c.pathStyle());
            assertTrue(c.fileStore().isPresent());
        }
        try (S3Connector c = create(map("region", "eu-central-1"), Map.of())) {
            assertEquals(URI.create("https://s3.eu-central-1.amazonaws.com"), c.endpoint());
            assertFalse(c.pathStyle());
        }
        try (S3Connector c = create(map("endpoint", "https://minio.example.org", "pathStyle", "false", "timeout", "5s",
                "partSize", "16MB", "retries", "0"), Map.of())) {
            assertFalse(c.pathStyle());
        }
    }

    @Test
    void whatIsNotGivenComesFromTheEnvironment() throws IOException {
        try (S3Connector c = create(Map.of(), Map.of("AWS_REGION", "eu-west-1", "AWS_ENDPOINT_URL_S3",
                "http://localhost:9000", "AWS_ACCESS_KEY_ID", "a", "AWS_SECRET_ACCESS_KEY", "b"))) {
            assertEquals("eu-west-1", c.region());
            assertEquals(URI.create("http://localhost:9000"), c.endpoint());
            assertTrue(c.pathStyle());
        }
        try (S3Connector c = create(map("region", "ap-south-1"), Map.of("AWS_DEFAULT_REGION", "eu-west-1",
                "AWS_ENDPOINT_URL", "http://localhost:9001"))) {
            assertEquals("ap-south-1", c.region());
            assertEquals(URI.create("http://localhost:9001"), c.endpoint());
        }
        Files.createDirectories(home.resolve(".aws"));
        Files.writeString(home.resolve(".aws").resolve("credentials"), """
                # comment
                [default]
                aws_access_key_id = d
                aws_secret_access_key = e

                [minio]
                aws_access_key_id=m
                aws_secret_access_key=n
                aws_session_token=t
                [broken]
                aws_access_key_id=x
                """);
        CredentialChain chain = new CredentialChain(Map.of(), home);
        assertEquals(S3Credentials.of("d", "e"), chain.credentials(null).orElseThrow());
        assertEquals(new S3Credentials("m", "n", "t"), chain.credentials("minio").orElseThrow());
        assertEquals(new S3Credentials("m", "n", "t"), new CredentialChain(Map.of("AWS_PROFILE", "minio"), home)
                .credentials(null).orElseThrow());
        assertEquals(S3Credentials.of("a", "b"), new CredentialChain(Map.of("AWS_ACCESS_KEY_ID", "a",
                "AWS_SECRET_ACCESS_KEY", "b"), home).credentials(null).orElseThrow());
        assertEquals(new S3Credentials("m", "n", "t"), new CredentialChain(Map.of("AWS_ACCESS_KEY_ID", "a",
                "AWS_SECRET_ACCESS_KEY", "b"), home).credentials("minio").orElseThrow());
        assertTrue(new CredentialChain(Map.of(), home.resolve("none")).credentials(null).isEmpty());
        assertEquals("The S3 source s3: The profile nope is not in the file of credentials "
                + home.resolve(".aws").resolve("credentials"), failure(map("profile", "nope"), Map.of()));
        assertEquals("The S3 source s3: The profile broken of the file of credentials "
                + home.resolve(".aws").resolve("credentials") + " needs aws_access_key_id and aws_secret_access_key",
                failure(map("profile", "broken"), Map.of()));
        create(map("profile", "minio"), Map.of()).close();
    }

    @Test
    void wrongOptionsAreErrorsThatNameNoValues() {
        assertEquals("The S3 source s3 has an unknown option bucket; known are endpoint, region, accessKey, secretKey,"
                + " sessionToken, profile, pathStyle, timeout, partSize, retries",
                failure(map("bucket", "lake"), Map.of()));
        assertEquals("The options accessKey and secretKey of the S3 source s3 go together; secretKey is missing",
                failure(map("accessKey", "AKIA-SECRET-ISH"), Map.of()));
        assertEquals("The option sessionToken of the S3 source s3 needs accessKey and secretKey",
                failure(map("sessionToken", "tok"), Map.of()));
        assertEquals("The option partSize of the S3 source s3 must be at least 5MB and less than 2GB",
                failure(map("partSize", "1MB"), Map.of()));
        assertEquals("The endpoint of the S3 source s3 must be a URI of http or https with a host: ftp://x",
                failure(map("endpoint", "ftp://x"), Map.of()));
        assertEquals("The S3 source s3: The environment has AWS_ACCESS_KEY_ID without AWS_SECRET_ACCESS_KEY",
                failure(Map.of(), Map.of("AWS_ACCESS_KEY_ID", "a")));
        assertEquals("S3Credentials[accessKey=a]", S3Credentials.of("a", "very-secret").toString());
    }
}
