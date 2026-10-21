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

package net.fyrql.connector.iceberg;

import net.fyrql.connector.Batches;
import net.fyrql.connector.ColumnPath;
import net.fyrql.connector.parquet.ParquetFile;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The deletes of the files of one scan, each delete file read once: the
 * positions of deletion vectors and position delete files, and the rows of
 * equality delete files as keys.
 * <p>
 * The values of a key are compared after widening, so that an
 * {@code int} matches the {@code long} it became, and {@code NULL} matches
 * {@code NULL}.
 */
final class Deletes {

    private static final int DELETE_POS = 2147483545;
    private static final int BATCH = 4096;

    private final IcebergFiles files;
    private final Map<String, long[]> vectors = new ConcurrentHashMap<>();
    private final Map<String, Map<String, long[]>> positions = new ConcurrentHashMap<>();
    private final Map<String, Set<List<Object>>> equalities = new ConcurrentHashMap<>();

    Deletes(IcebergFiles files) {
        this.files = files;
    }

    /** Returns the deleted positions of the data file of a task, ascending and each once. */
    long[] positions(SnapshotScan.FileTask task) {
        if (task.deletionVector() != null) {
            Manifests.ContentFile dv = task.deletionVector();
            return vectors.computeIfAbsent(dv.location() + "@" + dv.contentOffset(), k -> vector(dv));
        }
        if (task.positionDeletes().isEmpty()) {
            return new long[0];
        }
        List<long[]> parts = new ArrayList<>();
        int total = 0;
        for (Manifests.ContentFile d : task.positionDeletes()) {
            long[] p = positions.computeIfAbsent(d.location(), k -> positionFile(d)).get(task.data().path());
            if (p != null) {
                parts.add(p);
                total += p.length;
            }
        }
        long[] all = new long[total];
        int n = 0;
        for (long[] p : parts) {
            System.arraycopy(p, 0, all, n, p.length);
            n += p.length;
        }
        Arrays.sort(all);
        int unique = 0;
        for (int i = 0; i < all.length; i++) {
            if (i == 0 || all[i] != all[i - 1]) {
                all[unique++] = all[i];
            }
        }
        return Arrays.copyOf(all, unique);
    }

    /** Returns the rows of an equality delete file as keys in the order of its equality ids. */
    Set<List<Object>> equality(Manifests.ContentFile file) {
        return equalities.computeIfAbsent(file.location(), k -> equalityFile(file));
    }

    /** Returns a value as it is compared in a key: integers as {@code long}, floats as {@code double}. */
    static Object key(Object value) {
        return switch (value) {
            case null -> null;
            case Integer i -> i.longValue();
            case Short s -> s.longValue();
            case Byte b -> b.longValue();
            case Float f -> f.doubleValue();
            case byte[] b -> ByteBuffer.wrap(b);
            default -> value;
        };
    }

    private long[] vector(Manifests.ContentFile dv) {
        if (dv.contentOffset() == null || dv.contentSize() == null) {
            throw new IllegalArgumentException("The deletion vector " + dv.path() + " has no offset or size");
        }
        try {
            return PositionBitmap.positions(files.range(dv.location(), dv.contentOffset(),
                    Math.toIntExact(dv.contentSize())));
        } catch (IOException e) {
            throw new IllegalArgumentException("The deletion vector in " + dv.path() + " at byte "
                    + dv.contentOffset() + ": " + e.getMessage(), e);
        }
    }

    /** Reads a position delete file: the positions by the paths of the data files. */
    private Map<String, long[]> positionFile(Manifests.ContentFile d) {
        ParquetFile f = files.parquet(d.location());
        int path = column(f, SnapshotScan.DELETE_FILE_PATH, d);
        int pos = column(f, DELETE_POS, d);
        Map<String, List<Long>> byPath = new HashMap<>();
        Schema schema = new Schema(List.of(new Schema.Field("file_path", DataType.VARCHAR, false),
                new Schema.Field("pos", DataType.BIGINT, false)));
        for (Batch b : rows(f, List.of(new ColumnPath(path, List.of()), new ColumnPath(pos, List.of())), schema)) {
            for (int r = 0; r < b.rowCount(); r++) {
                byPath.computeIfAbsent((String) b.column(0).get(r), k -> new ArrayList<>())
                        .add((Long) b.column(1).get(r));
            }
        }
        Map<String, long[]> result = new HashMap<>();
        byPath.forEach((p, list) -> result.put(p, list.stream().mapToLong(Long::longValue).toArray()));
        return result;
    }

    /** Reads an equality delete file: its rows as keys of the columns of its equality ids. */
    private Set<List<Object>> equalityFile(Manifests.ContentFile d) {
        if (d.equalityIds().isEmpty()) {
            throw new IllegalArgumentException("The equality delete file " + d.path() + " has no equality ids");
        }
        ParquetFile f = files.parquet(d.location());
        List<ColumnPath> columns = new ArrayList<>();
        List<Schema.Field> fields = new ArrayList<>();
        for (int id : d.equalityIds()) {
            int c = column(f, id, d);
            columns.add(new ColumnPath(c, List.of()));
            fields.add(new Schema.Field(f.schema().name(c), f.schema().type(c), true));
        }
        Set<List<Object>> keys = new HashSet<>();
        for (Batch b : rows(f, columns, new Schema(fields))) {
            for (int r = 0; r < b.rowCount(); r++) {
                List<Object> key = new ArrayList<>(columns.size());
                for (int c = 0; c < columns.size(); c++) {
                    key.add(key(b.column(c).get(r)));
                }
                keys.add(key);
            }
        }
        return keys;
    }

    private static int column(ParquetFile f, int fieldId, Manifests.ContentFile d) {
        int[] ids = f.fieldIds();
        for (int c = 0; c < ids.length; c++) {
            if (ids[c] == fieldId) {
                return c;
            }
        }
        throw new IllegalArgumentException("The delete file " + d.path() + " has no column with the field id "
                + fieldId);
    }

    private static List<Batch> rows(ParquetFile f, List<ColumnPath> columns, Schema schema) {
        List<Batch> batches = new ArrayList<>();
        List<Expression> none = List.of();
        for (int g = 0; g < f.rowGroups(); g++) {
            batches.addAll(Batches.readAll(f.read(g, f.rows(g), schema, columns, none, BATCH, null)));
        }
        return batches;
    }
}
