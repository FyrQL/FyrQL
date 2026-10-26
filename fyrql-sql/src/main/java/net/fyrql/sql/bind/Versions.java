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

package net.fyrql.sql.bind;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/** The version of FyrQL, which {@code version()} gives, from a resource the build fills in. */
final class Versions {

    /** The text of {@code version()}, such as {@code FyrQL 0.19.0}. */
    static final String VERSION = "FyrQL " + number();

    private Versions() {
    }

    private static String number() {
        try (InputStream in = Versions.class.getResourceAsStream("sql.properties")) {
            if (in == null) {
                return "0.0";
            }
            Properties p = new Properties();
            p.load(in);
            String version = p.getProperty("version", "0.0").strip();
            return version.startsWith("${") ? "0.0" : version;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
