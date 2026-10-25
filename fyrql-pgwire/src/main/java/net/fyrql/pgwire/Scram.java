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

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The sign-in with SCRAM-SHA-256 (RFC 5802 and RFC 7677) as PostgreSQL
 * does it, without channel binding: the server keeps for each user only the
 * keys derived from the password with a salt it draws at its start, and a
 * client proves that it knows the password without sending it. A user the
 * server does not know goes through the same steps with keys of its own
 * and fails at the end, so that the answers do not tell which users exist.
 */
final class Scram {

    static final String MECHANISM = "SCRAM-SHA-256";
    static final int ITERATIONS = 4096;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** The keys of a user. */
    private record Keys(byte[] salt, byte[] storedKey, byte[] serverKey) {
    }

    private final Map<String, Keys> users = new LinkedHashMap<>();
    /** The secret from which the keys of unknown users come. */
    private final byte[] secret = random(32);

    /** Derives the keys of the users from their passwords. */
    Scram(Map<String, String> passwords) {
        for (Map.Entry<String, String> e : passwords.entrySet()) {
            byte[] salt = random(16);
            users.put(e.getKey(), keys(salt, salted(e.getValue(), salt)));
        }
    }

    private static Keys keys(byte[] salt, byte[] saltedPassword) {
        byte[] clientKey = hmac(saltedPassword, "Client Key");
        return new Keys(salt, sha256(clientKey), hmac(saltedPassword, "Server Key"));
    }

    private static byte[] random(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    /** The password derived with the salt, after the normalization of SASLprep. */
    private static byte[] salted(String password, byte[] salt) {
        String normalized = Normalizer.normalize(password, Normalizer.Form.NFKC);
        try {
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return f.generateSecret(new PBEKeySpec(normalized.toCharArray(), salt, ITERATIONS, 256)).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("The JDK has no PBKDF2WithHmacSHA256", e);
        }
    }

    private static byte[] hmac(byte[] key, String text) {
        return hmac(key, text.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmac(byte[] key, byte[] text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(text);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("The JDK has no HmacSHA256", e);
        }
    }

    private static byte[] sha256(byte[] b) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(b);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("The JDK has no SHA-256", e);
        }
    }

    /** Starts the exchange of a user. */
    Exchange begin(String user) {
        Keys keys = users.get(user);
        boolean known = keys != null;
        if (!known) {
            byte[] fake = hmac(secret, user);
            byte[] salt = new byte[16];
            System.arraycopy(fake, 0, salt, 0, 16);
            keys = keys(salt, hmac(secret, fake));
        }
        return new Exchange(keys, known);
    }

    /** A sign-in that failed: the messages do not follow the mechanism, or the proof is wrong. */
    static final class Failure extends Exception {
        private static final long serialVersionUID = 1L;
        private final boolean malformed;

        Failure(String message, boolean malformed) {
            super(message);
            this.malformed = malformed;
        }

        /** Tells whether the client broke the protocol, rather than sending a wrong password. */
        boolean malformed() {
            return malformed;
        }
    }

    /** The exchange of one sign-in. */
    static final class Exchange {
        private final Keys keys;
        private final boolean known;
        private String clientFirstBare;
        private String serverFirst;
        private String nonce;

        private Exchange(Keys keys, boolean known) {
            this.keys = keys;
            this.known = known;
        }

        /**
         * Answers the first message of the client with the nonce, the salt and
         * the iterations.
         *
         * @throws Failure if the message is not one of the mechanism, or asks for channel binding
         */
        String serverFirst(String clientFirst) throws Failure {
            if (clientFirst.startsWith("p=")) {
                throw new Failure("channel binding is not supported; use SCRAM-SHA-256 without -PLUS", true);
            }
            if (!clientFirst.startsWith("n,") && !clientFirst.startsWith("y,")) {
                throw new Failure("malformed SCRAM message (no GS2 header)", true);
            }
            int second = clientFirst.indexOf(',', 2);
            if (second < 0) {
                throw new Failure("malformed SCRAM message (no GS2 header)", true);
            }
            clientFirstBare = clientFirst.substring(second + 1);
            String clientNonce = attribute(clientFirstBare, 'r');
            if (clientNonce == null || clientNonce.isEmpty()) {
                throw new Failure("malformed SCRAM message (no nonce)", true);
            }
            nonce = clientNonce + Base64.getEncoder().withoutPadding().encodeToString(random(18));
            serverFirst = "r=" + nonce + ",s=" + Base64.getEncoder().encodeToString(keys.salt()) + ",i=" + ITERATIONS;
            return serverFirst;
        }

        /**
         * Checks the proof of the client and answers with the signature of the
         * server.
         *
         * @throws Failure if the message is malformed or the proof is wrong
         */
        String serverFinal(String clientFinal) throws Failure {
            if (serverFirst == null) {
                throw new Failure("malformed SCRAM message (out of order)", true);
            }
            int proofAt = clientFinal.lastIndexOf(",p=");
            if (proofAt < 0) {
                throw new Failure("malformed SCRAM message (no proof)", true);
            }
            String withoutProof = clientFinal.substring(0, proofAt);
            String binding = attribute(withoutProof, 'c');
            String finalNonce = attribute(withoutProof, 'r');
            if (binding == null || !nonce.equals(finalNonce)) {
                throw new Failure("malformed SCRAM message (wrong nonce or binding)", true);
            }
            String header = new String(decode(binding), StandardCharsets.UTF_8);
            if (!header.equals("n,,") && !header.equals("y,,")) {
                throw new Failure("malformed SCRAM message (unexpected channel binding)", true);
            }
            byte[] proof = decode(clientFinal.substring(proofAt + 3));
            String authMessage = clientFirstBare + "," + serverFirst + "," + withoutProof;
            byte[] signature = hmac(keys.storedKey(), authMessage);
            if (proof.length != signature.length) {
                throw new Failure("malformed SCRAM message (proof of the wrong length)", true);
            }
            byte[] clientKey = new byte[proof.length];
            for (int i = 0; i < proof.length; i++) {
                clientKey[i] = (byte) (proof[i] ^ signature[i]);
            }
            if (!MessageDigest.isEqual(sha256(clientKey), keys.storedKey()) || !known) {
                throw new Failure("wrong password", false);
            }
            return "v=" + Base64.getEncoder().encodeToString(hmac(keys.serverKey(), authMessage));
        }

        private static byte[] decode(String base64) throws Failure {
            try {
                return Base64.getDecoder().decode(base64);
            } catch (IllegalArgumentException e) {
                throw new Failure("malformed SCRAM message (invalid base64)", true);
            }
        }

        /** Returns the value of an attribute {@code a=value} of a message, or {@code null}. */
        private static String attribute(String message, char name) {
            for (String part : message.split(",")) {
                if (part.length() >= 2 && part.charAt(0) == name && part.charAt(1) == '=') {
                    return part.substring(2);
                }
            }
            return null;
        }
    }
}
