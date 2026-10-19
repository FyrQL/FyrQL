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

package net.fyrql.codegen;

/** Writes source code line by line, with an indentation of four spaces per level. */
final class CodeWriter {

    private static final String INDENT = "    ";

    private final StringBuilder sb = new StringBuilder();
    private int level;

    CodeWriter(int level) {
        this.level = level;
    }

    /** Writes one line. */
    CodeWriter line(String text) {
        sb.append(INDENT.repeat(level)).append(text).append('\n');
        return this;
    }

    /** Writes an empty line. */
    CodeWriter blank() {
        sb.append('\n');
        return this;
    }

    /** Writes the text and an opening brace, and indents the following lines. */
    CodeWriter open(String text) {
        line(text + " {");
        level++;
        return this;
    }

    /** Ends a block with a closing brace. */
    CodeWriter close() {
        if (level == 0) {
            throw new IllegalStateException("No open block");
        }
        level--;
        return line("}");
    }

    /** Ends a block and opens the next one on the same line, e.g. {@code else}. */
    CodeWriter reopen(String text) {
        level--;
        line("} " + text + " {");
        level++;
        return this;
    }

    /** Writes text that is already indented, e.g. a loop body generated before the loop. */
    CodeWriter raw(String text) {
        sb.append(text);
        return this;
    }

    /** Moves lines written at one indentation level to another; lines indented less stay as they are. */
    static String reindent(String text, int from, int to) {
        String prefix = INDENT.repeat(from);
        String replacement = INDENT.repeat(to);
        StringBuilder out = new StringBuilder(text.length());
        for (String line : text.split("\n", -1)) {
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append(line.startsWith(prefix) ? replacement + line.substring(prefix.length()) : line);
        }
        return out.toString();
    }

    /** Returns the current indentation level. */
    int level() {
        return level;
    }

    @Override
    public String toString() {
        return sb.toString();
    }
}
