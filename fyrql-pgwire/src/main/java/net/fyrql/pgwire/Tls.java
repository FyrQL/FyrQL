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

package net.fyrql.pgwire;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TLS from the JDK, from the same PEM files as the Flight SQL server takes:
 * a certificate chain and its private key in PKCS #8, of RSA, EC or EdDSA.
 * A client asks for it with {@code SSLRequest}; the server then turns the
 * connection into a TLS connection.
 */
final class Tls {

    private static final Pattern KEY = Pattern.compile(
            "-----BEGIN PRIVATE KEY-----([A-Za-z0-9+/=\\s]+)-----END PRIVATE KEY-----");
    private static final List<String> ALGORITHMS = List.of("RSA", "EC", "Ed25519", "Ed448", "EdDSA", "RSASSA-PSS");

    private final SSLContext context;

    private Tls(SSLContext context) {
        this.context = context;
    }

    /**
     * Reads the certificate chain and the key.
     *
     * @throws IOException if a file cannot be read
     * @throws IllegalArgumentException if a file holds no certificate or no key in PKCS #8
     */
    static Tls of(Path certificate, Path key) throws IOException {
        Certificate[] chain;
        try (ByteArrayInputStream in = new ByteArrayInputStream(Files.readAllBytes(certificate))) {
            chain = CertificateFactory.getInstance("X.509").generateCertificates(in).toArray(new Certificate[0]);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("The certificate " + certificate + " cannot be read: " + e.getMessage(),
                    e);
        }
        if (chain.length == 0) {
            throw new IllegalArgumentException("The file " + certificate + " holds no certificate");
        }
        PrivateKey privateKey = privateKey(key);
        try {
            char[] password = Base64.getEncoder().encodeToString(Scram.class.getName().getBytes(StandardCharsets.UTF_8))
                    .toCharArray();
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            store.setKeyEntry("server", privateKey, password, chain);
            KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(store, password);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keys.getKeyManagers(), null, null);
            return new Tls(context);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("The key " + key + " does not fit the certificate " + certificate
                    + ": " + e.getMessage(), e);
        }
    }

    private static PrivateKey privateKey(Path key) throws IOException {
        Matcher m = KEY.matcher(Files.readString(key, StandardCharsets.US_ASCII));
        if (!m.find()) {
            throw new IllegalArgumentException("The file " + key + " holds no private key in PKCS #8 (BEGIN PRIVATE"
                    + " KEY); convert it with openssl pkcs8 -topk8 -nocrypt");
        }
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(Base64.getMimeDecoder().decode(m.group(1)));
        for (String algorithm : ALGORITHMS) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(spec);
            } catch (GeneralSecurityException e) {
                // Another algorithm.
            }
        }
        throw new IllegalArgumentException("The key in " + key + " is none of " + ALGORITHMS);
    }

    /** Turns a connection into a TLS connection as its server, after the handshake. */
    SSLSocket wrap(Socket socket) throws IOException {
        SSLSocket tls = (SSLSocket) context.getSocketFactory().createSocket(socket,
                socket.getInetAddress().getHostAddress(), socket.getPort(), true);
        tls.setUseClientMode(false);
        tls.startHandshake();
        return tls;
    }
}
