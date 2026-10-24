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

import net.fyrql.core.type.DataType;
import net.fyrql.core.vector.BytesVector;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BatchesTest {

    @Test
    void aDictionaryCountsAsMuchAsTheFlatForm() {
        byte[] bytes = "RNAOF".getBytes(StandardCharsets.UTF_8);
        BytesVector.Dictionary dictionary = BytesVector.Dictionary.of(bytes, new int[] {0, 1, 2, 3, 4, 5});
        int[] codes = {0, 1, 1, 4, 2, 0};
        boolean[] nulls = {false, false, true, false, false, true};
        BytesVector coded = BytesVector.dictionary(DataType.VARCHAR, codes, dictionary, nulls);
        BytesVector.Builder b = new BytesVector.Builder(DataType.VARCHAR, codes.length);
        for (int i = 0; i < codes.length; i++) {
            if (nulls[i]) {
                b.appendNull();
            } else {
                b.appendBytes(bytes, codes[i], 1);
            }
        }
        BytesVector flat = b.build();
        assertEquals(Batches.estimateBytes(flat), Batches.estimateBytes(coded));
        assertEquals(4 + 4 * 6 + 32, Batches.estimateBytes(coded));
    }
}
