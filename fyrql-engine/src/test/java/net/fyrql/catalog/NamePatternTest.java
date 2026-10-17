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

package net.fyrql.catalog;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NamePatternTest {

    private static final List<String> NAMES = List.of("t", "t1", "tx", "t_x", "tax", "t12", "T1", "", "%");

    private static List<String> select(String pattern) {
        return NamePattern.of(pattern).select(NAMES);
    }

    @Test
    void wildcardsStandForAnyCharactersAndOneCharacter() {
        assertSame(NamePattern.ANY, NamePattern.of(null));
        assertTrue(NamePattern.ANY.isAny());
        assertEquals(NAMES, select(null));
        assertEquals(NAMES, select("%"));
        assertEquals(List.of("t1", "tx"), select("t_"), "T1 matches only ignoring case");
        assertEquals(List.of("T1"), select("T_"));
        assertEquals(List.of("t_x", "tax"), select("t_x"));
        assertEquals(List.of("t", "t1", "tx", "t_x", "tax", "t12"), select("t%"));
        assertEquals(List.of("t1", "t12"), select("t%1%"));
        assertEquals(List.of(""), select(""), "an empty pattern matches only the empty name");
        assertFalse(NamePattern.of("t").isAny());
    }

    @Test
    void aBackslashMakesTheNextCharacterStandForItself() {
        assertEquals(List.of("t_x"), select("t\\_x"));
        assertEquals(List.of("%"), select("\\%"));
        assertTrue(NamePattern.of("a\\").matches("a\\"), "a backslash at the end stands for itself");
        assertTrue(NamePattern.of("a\\\\b").matches("a\\b"));
        assertTrue(NamePattern.of("a.b(c)*$").matches("a.b(c)*$"), "other characters stand for themselves");
        assertFalse(NamePattern.of("a.b").matches("axb"));
        assertTrue(NamePattern.of("a%").matches("a\nb"), "% stands for line ends too");
    }

    @Test
    void namesMatchExactlyFirstAndOtherwiseIgnoringCase() {
        List<String> names = List.of("Orders", "ORDERS", "orders", "items");
        assertEquals(List.of("ORDERS"), NamePattern.of("ORDERS").select(names));
        assertEquals(List.of("Orders", "ORDERS", "orders"), NamePattern.of("oRdErS").select(names));
        assertEquals(List.of("Orders"), NamePattern.of("Ord%").select(names));
        assertEquals(List.of("ORDERS"), NamePattern.of("ORD%S").select(names));
        assertEquals(List.of("Orders", "ORDERS", "orders"), NamePattern.of("ord%S").select(names),
                "ord%S matches no name exactly");
        assertTrue(NamePattern.of("\u00C4RGER").matches("\u00E4rger"), "case is ignored beyond ASCII");
        assertEquals(List.of(), NamePattern.of("x%").select(names));
    }

    @Test
    void aNameHasNoWildcards() {
        assertSame(NamePattern.ANY, NamePattern.name(null));
        assertEquals(List.of("t_x"), NamePattern.name("t_x").select(NAMES));
        assertEquals(List.of("%"), NamePattern.name("%").select(NAMES));
        assertEquals(List.of("T1"), NamePattern.name("T1").select(List.of("t1", "T1")));
        assertEquals(List.of("tx", "TX"), NamePattern.name("tX").select(List.of("tx", "TX", "ty")));
        assertEquals("t_x", NamePattern.name("t_x").toString());
        assertEquals("%", NamePattern.ANY.toString());
    }
}
