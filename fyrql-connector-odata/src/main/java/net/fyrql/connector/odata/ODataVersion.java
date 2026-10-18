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

package net.fyrql.connector.odata;

import java.util.Locale;

/**
 * The version of the OData protocol a service speaks, as far as reading
 * its entity sets as JSON depends on it: where the records of an answer
 * are, and under which keys an answer links to its next page and tells the
 * number of all records. Version 3 is read as version 2, whose verbose JSON
 * it shares.
 */
public enum ODataVersion {

    /** OData 2.0 (and 1.0 and 3.0): the records are under {@code d.results}. */
    V2("/d/results", "__next", "__count"),

    /** OData 4.0 and 4.01: the records are under {@code value}. */
    V4("/value", "@odata.nextLink", "@odata.count");

    private final String recordsPointer;
    private final String nextLinkKey;
    private final String countKey;

    ODataVersion(String recordsPointer, String nextLinkKey, String countKey) {
        this.recordsPointer = recordsPointer;
        this.nextLinkKey = nextLinkKey;
        this.countKey = countKey;
    }

    /** Returns the JSON pointer to the array of records in the answer to a query of an entity set. */
    public String recordsPointer() {
        return recordsPointer;
    }

    /** Returns the key, next to the array of records, of the link to the next page. */
    public String nextLinkKey() {
        return nextLinkKey;
    }

    /** Returns the key, next to the array of records, of the number of all records. */
    public String countKey() {
        return countKey;
    }

    /**
     * Returns the version a text names: {@code v2}, {@code 2} or
     * {@code 2.0} for version 2, {@code v4}, {@code 4}, {@code 4.0} or
     * {@code 4.01} for version 4, in any case.
     *
     * @throws IllegalArgumentException if the text names no version
     */
    public static ODataVersion parse(String text) {
        String t = text.strip().toLowerCase(Locale.ROOT);
        if (t.startsWith("v")) {
            t = t.substring(1);
        }
        return switch (t) {
            case "2", "2.0" -> V2;
            case "4", "4.0", "4.01" -> V4;
            default -> throw new IllegalArgumentException("Unknown OData version " + text
                    + "; known are v2 and v4");
        };
    }
}
