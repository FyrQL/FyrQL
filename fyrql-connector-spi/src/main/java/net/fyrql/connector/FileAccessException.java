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

package net.fyrql.connector;

/**
 * A file or object that a statement in SQL may not read or write, by the
 * {@link FileAccessPolicy} of the engine. The message is for the user and
 * names the location.
 */
public class FileAccessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public FileAccessException(String message) {
        super(message);
    }
}
