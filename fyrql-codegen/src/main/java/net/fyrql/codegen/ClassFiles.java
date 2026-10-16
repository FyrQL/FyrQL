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

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/** Reads what the compiler needs to know of a class file: the length of the code of its methods. */
final class ClassFiles {

    private ClassFiles() {
    }

    /** Returns the length, in bytes, of the longest code of a method of a class file. */
    static int maxCodeLength(byte[] classFile) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(classFile))) {
            in.skipNBytes(8); // magic, minor and major version
            int constants = in.readUnsignedShort();
            String[] utf8 = new String[constants];
            for (int k = 1; k < constants; k++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> utf8[k] = in.readUTF();
                    case 7, 8, 16, 19, 20 -> in.skipNBytes(2);
                    case 15 -> in.skipNBytes(3);
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipNBytes(4);
                    case 5, 6 -> {
                        in.skipNBytes(8);
                        k++; // a long or double takes two entries
                    }
                    default -> throw new IllegalArgumentException("Unknown constant tag " + tag);
                }
            }
            in.skipNBytes(6); // access flags, this class, super class
            in.skipNBytes(2L * in.readUnsignedShort()); // interfaces
            int fields = in.readUnsignedShort();
            for (int f = 0; f < fields; f++) {
                in.skipNBytes(6);
                skipAttributes(in);
            }
            int max = 0;
            int methods = in.readUnsignedShort();
            for (int m = 0; m < methods; m++) {
                in.skipNBytes(6); // access flags, name, descriptor
                int attributes = in.readUnsignedShort();
                for (int a = 0; a < attributes; a++) {
                    String name = utf8[in.readUnsignedShort()];
                    long length = in.readInt() & 0xFFFFFFFFL;
                    if ("Code".equals(name)) {
                        in.skipNBytes(4); // max stack, max locals
                        int code = in.readInt();
                        max = Math.max(max, code);
                        in.skipNBytes(length - 8);
                    } else {
                        in.skipNBytes(length);
                    }
                }
            }
            return max;
        } catch (IOException e) {
            throw new UncheckedIOException("Not a class file", e);
        }
    }

    private static void skipAttributes(DataInputStream in) throws IOException {
        int attributes = in.readUnsignedShort();
        for (int a = 0; a < attributes; a++) {
            in.skipNBytes(2);
            in.skipNBytes(in.readInt() & 0xFFFFFFFFL);
        }
    }
}
