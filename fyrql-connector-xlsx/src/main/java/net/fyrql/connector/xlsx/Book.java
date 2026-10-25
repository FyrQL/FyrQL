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
package net.fyrql.connector.xlsx;

import net.fyrql.connector.FileObject;

import java.io.IOException;

/**
 * A workbook opened for reading its sheets: its structure, its shared
 * strings and its styles.
 */
record Book(Workbook workbook, SharedStrings strings, Styles styles) {

    /** Opens the workbook of a file. */
    static Book open(FileObject file) throws IOException {
        Workbook workbook = Workbook.read(ZipArchive.open(file));
        return new Book(workbook, SharedStrings.read(workbook), Styles.read(workbook));
    }

    /** Returns the location of the file, for messages. */
    String location() {
        return workbook.archive().file().location();
    }
}
