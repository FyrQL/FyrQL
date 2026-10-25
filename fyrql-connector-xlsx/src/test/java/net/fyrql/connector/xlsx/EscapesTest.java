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

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class EscapesTest {

    @Test
    void decode() {
        String plain = "no escape _x here _x12_ _xZZZZ_";
        assertSame(plain, Escapes.decode(plain));
        assertEquals("A", Escapes.decode("_x0041_"));
        assertEquals("a" + (char) 1 + "b", Escapes.decode("a_x0001_b"));
        assertEquals("_x0041_", Escapes.decode("_x005F_x0041_"));
        assertEquals("line" + (char) 13 + (char) 10, Escapes.decode("line_x000D__x000a_"));
    }

    @Test
    void encode() {
        String plain = "plain text with tab\t and line\n and _x12_";
        assertSame(plain, Escapes.encode(plain));
        assertEquals("a_x0001_b", Escapes.encode("a" + (char) 1 + "b"));
        assertEquals("cr_x000D_", Escapes.encode("cr" + (char) 13));
        assertEquals("_x005F_x0041_", Escapes.encode("_x0041_"));
        assertEquals("_x005F_x005F_", Escapes.encode("_x005F_"));
        String pair = new String(Character.toChars(0x1F600));
        assertSame(pair, Escapes.encode(pair));
        assertEquals("_xD83D_x", Escapes.encode(pair.charAt(0) + "x"));
        assertEquals("_xDE00_", Escapes.encode(String.valueOf(pair.charAt(1))));
        assertEquals("_xFFFE_", Escapes.encode(String.valueOf((char) 0xfffe)));
    }

    @Test
    void encodedTextsDecodeToThemselves() {
        Random random = new Random(7);
        char[] alphabet = {'a', '_', 'x', '0', '4', '1', 'F', (char) 1, (char) 13, (char) 0xd83d, (char) 0xde00,
            (char) 0xfffe, '\n'};
        for (int i = 0; i < 20_000; i++) {
            StringBuilder b = new StringBuilder();
            for (int n = random.nextInt(12); n > 0; n--) {
                b.append(alphabet[random.nextInt(alphabet.length)]);
            }
            String text = b.toString();
            assertEquals(text, Escapes.decode(Escapes.encode(text)), text);
        }
    }
}
