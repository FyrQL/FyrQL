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

package net.fyrql.exec;

import net.fyrql.connector.Batches;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.DataGenerator;
import net.fyrql.core.value.Keys;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.Vector;
import net.fyrql.exec.compiled.InterpretedKeys;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The group table numbers groups as a map of {@link RowKey}s in insertion
 * order would, for keys of every type with edge values and duplicates, and
 * gives the keys of the groups as vectors in that order.
 */
class GroupTableTest {

    private static final List<DataType> TYPES = List.of(DataType.BOOLEAN, DataType.TINYINT, DataType.SMALLINT,
            DataType.INTEGER, DataType.BIGINT, DataType.HUGEINT, DataType.FLOAT, DataType.DOUBLE,
            DataType.decimal(5, 2), DataType.decimal(18, 4), DataType.decimal(30, 6), DataType.VARCHAR, DataType.BLOB,
            DataType.DATE, DataType.TIMESTAMP, DataType.listOf(DataType.DOUBLE),
            DataType.struct(new DataType.Struct.Field("x", DataType.FLOAT), new DataType.Struct.Field("y",
                    DataType.VARCHAR)));

    @Test
    void groupsAsRowKeysDo() {
        List<Schema.Field> fields = new ArrayList<>();
        for (int i = 0; i < TYPES.size(); i++) {
            fields.add(new Schema.Field("c" + i, TYPES.get(i)));
        }
        Schema schema = new Schema(fields);
        SplittableRandom random = new SplittableRandom(20261014);
        for (int round = 0; round < 40; round++) {
            List<Object[]> distinct = DataGenerator.withSeed(round).generate(schema, 25);
            List<Object[]> rows = new ArrayList<>();
            for (int i = 0; i < 300; i++) {
                rows.add(distinct.get(random.nextInt(distinct.size())).clone());
            }
            // One or two key columns of random types, so that most keys repeat.
            List<Integer> keys = new ArrayList<>(List.of(random.nextInt(TYPES.size())));
            if (random.nextBoolean()) {
                keys.add(random.nextInt(TYPES.size()));
            }
            List<Batch> batches = Batches.split(Batch.ofRows(schema, rows), 1 + random.nextInt(64));
            GroupTable table = new GroupTable(keys);
            Map<RowKey, Integer> expected = new LinkedHashMap<>();
            for (Batch b : batches) {
                int[] groups = table.groups(b, InterpretedKeys.INSTANCE, stored -> {
                });
                for (int row = 0; row < b.rowCount(); row++) {
                    RowKey key = RowKey.of(b, keys, row);
                    Integer g = expected.computeIfAbsent(key, k -> expected.size());
                    assertEquals(g, groups[row], "round " + round + " keys " + keys.stream().map(TYPES::get)
                            .toList() + " row " + row);
                }
            }
            assertEquals(expected.size(), table.size());
            List<Vector> keyVectors = new ArrayList<>();
            for (int k = 0; k < keys.size(); k++) {
                keyVectors.add(table.keys(k));
                assertEquals(schema.type(keys.get(k)), keyVectors.get(k).type());
                assertEquals(table.size(), keyVectors.get(k).size());
            }
            int g = 0;
            for (RowKey key : expected.keySet()) {
                for (int k = 0; k < keys.size(); k++) {
                    assertTrue(Keys.equalObjects(key.values()[k], Keys.canonical(keyVectors.get(k).get(g))));
                }
                g++;
            }
        }
    }
}
