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

import net.fyrql.connector.ColumnPath;
import net.fyrql.core.expr.Expression;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Leaves out the manifests and data files in which no row can satisfy the
 * filters of a scan, by what the manifests say about them: the partition
 * values of a data file and the bounds of the partition values of the files
 * of a manifest, the least and greatest values, nulls and {@code NaN}s of
 * each column of a data file.
 * <p>
 * A filter is a condition over the output of a scan; comparisons,
 * {@code IN}, {@code BETWEEN}, {@code IS NULL}, {@code AND}, {@code OR} and
 * {@code NOT} of top-level columns with constants of their type count,
 * anything else may hold anywhere. Through a partition transform a
 * comparison counts as follows: {@code identity} as it is; {@code year},
 * {@code month}, {@code day}, {@code hour} and {@code truncate}, which keep
 * the order, for {@code =}, {@code <}, {@code <=}, {@code >} and
 * {@code >=}; {@code bucket} for {@code =}. Bounds of {@code FLOAT} and
 * {@code DOUBLE} columns count only where the file has no {@code NaN},
 * which compares greater than any number; bounds of strings and bytes may
 * be truncated and never show that all values are the same. A result is
 * never stronger than the filters: a file that is kept may have no row
 * that satisfies them.
 */
final class FilePruning {

    private final IcebergSchema schema;
    private final List<ColumnPath> columns;
    private final List<Expression> filters;
    private final Map<Integer, PartitionSpec> specs;

    /** What is known about the values of a column in some rows; {@code null} where it is not known. */
    private record Known(Object lower, Object upper, boolean exact, Boolean hasNull, Boolean allNull,
            Boolean hasNan) {
    }

    /**
     * @param schema the schema of the snapshot
     * @param columns the paths of the output of the scan in the table
     * @param filters the filters of the scan over its output
     * @param specs the partition specs of the table
     */
    FilePruning(IcebergSchema schema, List<ColumnPath> columns, List<Expression> filters,
            Map<Integer, PartitionSpec> specs) {
        this.schema = schema;
        this.columns = columns;
        this.filters = filters;
        this.specs = specs;
    }

    /** Tells whether a manifest may hold data files with rows that satisfy the filters; delete manifests always. */
    boolean mayMatch(Manifests.ManifestFile manifest) {
        if (filters.isEmpty() || manifest.content() != Manifests.DATA || manifest.partitions().isEmpty()) {
            return true;
        }
        PartitionSpec spec = specs.get(manifest.specId());
        if (spec == null || spec.fields().size() != manifest.partitions().size()) {
            return true;
        }
        Known[] partitions = new Known[spec.fields().size()];
        for (int k = 0; k < partitions.length; k++) {
            Manifests.FieldSummary s = manifest.partitions().get(k);
            IcebergSchema.Primitive type = partitionType(spec.fields().get(k));
            if (type == null) {
                continue;
            }
            try {
                Object lower = s.lower() == null ? null : SingleValues.bound(type, s.lower());
                Object upper = s.upper() == null ? null : SingleValues.bound(type, s.upper());
                partitions[k] = new Known(lower, upper, false, s.containsNull(),
                        lower == null && upper == null && s.containsNull(), s.containsNan());
            } catch (RuntimeException e) {
                // A summary this reader cannot decode tells nothing.
            }
        }
        return all(spec, partitions, null);
    }

    /** Tells whether a data file may have rows that satisfy the filters. */
    boolean mayMatch(Manifests.ContentFile file) {
        if (filters.isEmpty()) {
            return true;
        }
        PartitionSpec spec = specs.getOrDefault(file.specId(), PartitionSpec.UNPARTITIONED);
        Known[] partitions = new Known[spec.fields().size()];
        for (int k = 0; k < partitions.length && k < file.partition().length; k++) {
            Object p = file.partition()[k];
            partitions[k] = new Known(p, p, true, p == null, p == null, Boolean.FALSE);
        }
        return all(spec, partitions, file);
    }

    /** Tells whether every filter can hold, by the partition values and the statistics of a file, if any. */
    private boolean all(PartitionSpec spec, Known[] partitions, Manifests.ContentFile file) {
        for (Expression filter : filters) {
            if (!may(filter, true, spec, partitions, file)) {
                return false;
            }
        }
        return true;
    }

    /** Tells whether an expression can be {@code TRUE}, or {@code FALSE} if not {@code truth}, in some row. */
    private boolean may(Expression e, boolean truth, PartitionSpec spec, Known[] partitions,
            Manifests.ContentFile file) {
        return switch (e) {
            case Expression.And a -> truth ? may(a.left(), true, spec, partitions, file)
                    && may(a.right(), true, spec, partitions, file)
                    : may(a.left(), false, spec, partitions, file) || may(a.right(), false, spec, partitions, file);
            case Expression.Or o -> truth ? may(o.left(), true, spec, partitions, file)
                    || may(o.right(), true, spec, partitions, file)
                    : may(o.left(), false, spec, partitions, file) && may(o.right(), false, spec, partitions, file);
            case Expression.Not n -> may(n.operand(), !truth, spec, partitions, file);
            case Expression.Comparison c -> comparison(c, truth, spec, partitions, file);
            case Expression.Between b when truth -> comparison(new Expression.Comparison(Expression.ComparisonOp.GE,
                    b.value(), b.low()), true, spec, partitions, file) && comparison(new Expression.Comparison(
                    Expression.ComparisonOp.LE, b.value(), b.high()), true, spec, partitions, file);
            case Expression.In i when truth -> {
                for (Expression element : i.list()) {
                    if (comparison(new Expression.Comparison(Expression.ComparisonOp.EQ, i.value(), element), true,
                            spec, partitions, file)) {
                        yield true;
                    }
                }
                yield i.list().isEmpty();
            }
            case Expression.IsNull n when n.operand() instanceof Expression.Column c -> isNull(c,
                    truth != n.negated(), spec, partitions, file);
            default -> true;
        };
    }

    private boolean comparison(Expression.Comparison c, boolean truth, PartitionSpec spec, Known[] partitions,
            Manifests.ContentFile file) {
        Expression.ComparisonOp op = truth ? c.op() : negate(c.op());
        Expression.Column column;
        Expression.Literal literal;
        if (c.left() instanceof Expression.Column l && c.right() instanceof Expression.Literal r) {
            column = l;
            literal = r;
        } else if (c.right() instanceof Expression.Column r && c.left() instanceof Expression.Literal l) {
            column = r;
            literal = l;
            op = flip(op);
        } else {
            return true;
        }
        IcebergSchema.Column col = column(column);
        if (col == null || literal.value() == null || !literal.type().equals(col.type())) {
            return true;
        }
        Object value = icebergValue(col, literal.value());
        if (value == null) {
            return true;
        }
        if (file != null) {
            Known known = known(col, file);
            if (known != null && !compare(op, known, value, col.primitive())) {
                return false;
            }
        }
        for (int k = 0; k < spec.fields().size(); k++) {
            PartitionSpec.Field f = spec.fields().get(k);
            Known p = k < partitions.length ? partitions[k] : null;
            if (f.sourceId() != col.fieldId() || p == null || f.transform() instanceof PartitionSpec.VoidTransform) {
                continue;
            }
            if (!partition(op, f.transform(), col.primitive(), p, value)) {
                return false;
            }
        }
        return true;
    }

    private boolean isNull(Expression.Column column, boolean isNull, PartitionSpec spec, Known[] partitions,
            Manifests.ContentFile file) {
        IcebergSchema.Column col = column(column);
        if (col == null) {
            return true;
        }
        if (file != null) {
            Known known = known(col, file);
            if (known != null && (isNull ? Boolean.FALSE.equals(known.hasNull())
                    : Boolean.TRUE.equals(known.allNull()))) {
                return false;
            }
        }
        for (int k = 0; k < spec.fields().size() && k < partitions.length; k++) {
            PartitionSpec.Field f = spec.fields().get(k);
            Known p = partitions[k];
            if (f.sourceId() != col.fieldId() || p == null || f.transform() instanceof PartitionSpec.VoidTransform) {
                continue;
            }
            if (isNull ? Boolean.FALSE.equals(p.hasNull()) : Boolean.TRUE.equals(p.allNull())) {
                return false;
            }
        }
        return true;
    }

    /** Returns the top-level primitive column an output column reads, or {@code null}. */
    private IcebergSchema.Column column(Expression.Column c) {
        if (c.index() >= columns.size() || !columns.get(c.index()).fields().isEmpty()) {
            return null;
        }
        IcebergSchema.Column col = schema.columns().get(columns.get(c.index()).column());
        return col.primitive() == null || col.primitive().name().equals("uuid") ? null : col;
    }

    /** Returns what the statistics of a data file say about a column, or {@code null}. */
    private static Known known(IcebergSchema.Column col, Manifests.ContentFile file) {
        int id = col.fieldId();
        Long values = file.valueCounts().get(id);
        Long nulls = file.nullValueCounts().get(id);
        Long nans = file.nanValueCounts().get(id);
        Object lower = null;
        Object upper = null;
        try {
            byte[] l = file.lowerBounds().get(id);
            byte[] u = file.upperBounds().get(id);
            lower = l == null ? null : SingleValues.bound(col.primitive(), l);
            upper = u == null ? null : SingleValues.bound(col.primitive(), u);
        } catch (RuntimeException e) {
            // Bounds this reader cannot decode tell nothing.
        }
        if (values == null && nulls == null && lower == null && upper == null) {
            return null;
        }
        String type = col.primitive().name();
        boolean exact = !type.equals("string") && !type.equals("binary") && !type.equals("fixed");
        return new Known(lower, upper, exact, nulls == null ? null : nulls > 0,
                nulls == null || values == null ? null : nulls.longValue() == values.longValue(),
                nans == null ? null : nans > 0);
    }

    /** Tells whether a comparison can hold for some value between the bounds. */
    private static boolean compare(Expression.ComparisonOp op, Known k, Object value, IcebergSchema.Primitive type) {
        if (Boolean.TRUE.equals(k.allNull())) {
            return false;
        }
        boolean floating = type.name().equals("float") || type.name().equals("double");
        if (floating && !Boolean.FALSE.equals(k.hasNan())) {
            return true;
        }
        Object lower = k.lower();
        Object upper = k.upper();
        return switch (op) {
            case EQ -> (lower == null || cmp(lower, value) <= 0) && (upper == null || cmp(upper, value) >= 0);
            case LT -> lower == null || cmp(lower, value) < 0;
            case LE -> lower == null || cmp(lower, value) <= 0;
            case GT -> upper == null || cmp(upper, value) > 0;
            case GE -> upper == null || cmp(upper, value) >= 0;
            case NE -> !(k.exact() && lower != null && upper != null && cmp(lower, value) == 0
                    && cmp(upper, value) == 0);
        };
    }

    /** Tells whether a comparison can hold for some value whose partition value lies between the bounds. */
    private static boolean partition(Expression.ComparisonOp op, PartitionSpec.Transform transform,
            IcebergSchema.Primitive source, Known p, Object value) {
        if (Boolean.TRUE.equals(p.allNull())) {
            return false;
        }
        if (transform instanceof PartitionSpec.Identity) {
            boolean floating = source.name().equals("float") || source.name().equals("double");
            return floating && !Boolean.FALSE.equals(p.hasNan()) || compare(op, p, value, source);
        }
        Object t;
        try {
            t = Transforms.apply(transform, source, value);
        } catch (RuntimeException e) {
            return true;
        }
        Object lower = p.lower();
        Object upper = p.upper();
        if (transform instanceof PartitionSpec.Bucket) {
            return op != Expression.ComparisonOp.EQ
                    || (lower == null || cmp(lower, t) <= 0) && (upper == null || cmp(upper, t) >= 0);
        }
        return switch (op) {
            case EQ -> (lower == null || cmp(lower, t) <= 0) && (upper == null || cmp(upper, t) >= 0);
            case LT, LE -> lower == null || cmp(lower, t) <= 0;
            case GT, GE -> upper == null || cmp(upper, t) >= 0;
            case NE -> true;
        };
    }

    /** Returns the type of the values of a partition field, or {@code null} if its column is unknown. */
    private IcebergSchema.Primitive partitionType(PartitionSpec.Field f) {
        IcebergSchema.Column c = schema.column(f.sourceId());
        return c == null || c.primitive() == null ? null : PartitionSpec.resultType(f.transform(), c.primitive());
    }

    /** Returns a value of FyrQL as the value of {@link SingleValues} of the column, or {@code null}. */
    private static Object icebergValue(IcebergSchema.Column col, Object value) {
        String type = col.primitive().name();
        if (type.endsWith("_ns")) {
            return value instanceof Long micros ? Math.multiplyExact(micros, 1000L) : null;
        }
        return value;
    }

    /** Compares two values of {@link SingleValues} of one type: strings and bytes as unsigned bytes. */
    static int cmp(Object a, Object b) {
        return switch (a) {
            case String s -> Arrays.compareUnsigned(s.getBytes(StandardCharsets.UTF_8),
                    ((String) b).getBytes(StandardCharsets.UTF_8));
            case byte[] bytes -> Arrays.compareUnsigned(bytes, (byte[]) b);
            case Float f -> Double.compare(zero(f), zero(((Number) b).doubleValue()));
            case Double d -> Double.compare(zero(d), zero(((Number) b).doubleValue()));
            case Integer i when b instanceof Integer j -> Integer.compare(i, j);
            case BigDecimal d -> d.compareTo((BigDecimal) b);
            case Boolean x -> Boolean.compare(x, (Boolean) b);
            case Number n -> Long.compare(n.longValue(), ((Number) b).longValue());
            default -> throw new IllegalArgumentException("Cannot compare " + a);
        };
    }

    private static double zero(double d) {
        return d == 0 ? 0.0 : d;
    }

    private static Expression.ComparisonOp negate(Expression.ComparisonOp op) {
        return switch (op) {
            case EQ -> Expression.ComparisonOp.NE;
            case NE -> Expression.ComparisonOp.EQ;
            case LT -> Expression.ComparisonOp.GE;
            case LE -> Expression.ComparisonOp.GT;
            case GT -> Expression.ComparisonOp.LE;
            case GE -> Expression.ComparisonOp.LT;
        };
    }

    private static Expression.ComparisonOp flip(Expression.ComparisonOp op) {
        return switch (op) {
            case EQ, NE -> op;
            case LT -> Expression.ComparisonOp.GT;
            case LE -> Expression.ComparisonOp.GE;
            case GT -> Expression.ComparisonOp.LT;
            case GE -> Expression.ComparisonOp.LE;
        };
    }
}
