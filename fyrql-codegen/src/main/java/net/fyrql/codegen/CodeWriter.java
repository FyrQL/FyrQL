/*
 * Copyright 2026 the FyrQL authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
