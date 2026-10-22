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
 * What statements in SQL may do with the files and objects they name, such
 * as {@code FROM 'orders.csv'}, {@code read_parquet(...)},
 * {@code COPY ... TO} and {@code COPY ... FROM}; see
 * {@link FileAccessPolicy}. The tables and sources an application or a
 * configuration file registers are not affected.
 */
public enum FileAccess {

    /** No files from SQL. */
    NONE("none"),
    /** Reading files from SQL, but no {@code COPY ... TO}. */
    READ("read"),
    /** Reading and writing files from SQL. */
    READ_WRITE("readWrite");

    private final String text;

    FileAccess(String text) {
        this.text = text;
    }

    /**
     * Returns the access a text names: {@code none}, {@code read} or
     * {@code readWrite}, in any case.
     *
     * @throws IllegalArgumentException if it names none
     */
    public static FileAccess parse(String text) {
        for (FileAccess a : values()) {
            if (a.text.equalsIgnoreCase(text.strip())) {
                return a;
            }
        }
        throw new IllegalArgumentException("Unknown file access " + text + "; use none, read or readWrite");
    }

    /** Tells whether the access allows reading. */
    public boolean reads() {
        return this != NONE;
    }

    /** Tells whether the access allows writing. */
    public boolean writes() {
        return this == READ_WRITE;
    }

    /** Returns the name as {@link #parse} takes it, such as {@code readWrite}. */
    @Override
    public String toString() {
        return text;
    }
}
