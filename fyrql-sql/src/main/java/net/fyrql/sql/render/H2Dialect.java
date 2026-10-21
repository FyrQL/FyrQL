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

package net.fyrql.sql.render;

import net.fyrql.core.expr.Expression;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.AggregateFunction;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.DataType.Scalar;

import java.util.List;

/**
 * The dialect of H2. Types are those of H2 2: {@code HUGEINT} is
 * {@code NUMERIC(39)}, {@code BLOB} is {@code VARBINARY}; {@code NaN} and
 * the infinities have no literal. {@code EXCEPT} and {@code INTERSECT} have
 * no {@code ALL}, and there is no full join; {@code string_agg} is
 * {@code LISTAGG ... WITHIN GROUP}.
 * Integer division is written as {@code /}, which divides integers as
 * FyrQL does but not decimals. H2 compares strings by their UTF-16 units,
 * so a source evaluates the equality of strings but not their order.
 */
public final class H2Dialect extends StandardDialect {

    public static final H2Dialect INSTANCE = new H2Dialect();

    private H2Dialect() {
    }

    @Override
    public boolean supportsJoin(PlanNode.JoinType type) {
        return type != PlanNode.JoinType.FULL && super.supportsJoin(type);
    }

    @Override
    public String name() {
        return "h2";
    }

    @Override
    String scalarTypeName(Scalar type) {
        return switch (type) {
            case BOOLEAN -> "BOOLEAN";
            case TINYINT -> "TINYINT";
            case SMALLINT -> "SMALLINT";
            case INTEGER -> "INTEGER";
            case BIGINT -> "BIGINT";
            case HUGEINT -> "NUMERIC(39)";
            case FLOAT -> "REAL";
            case DOUBLE -> "DOUBLE PRECISION";
            case VARCHAR -> "VARCHAR";
            case BLOB -> "VARBINARY";
            case DATE -> "DATE";
            case TIMESTAMP -> "TIMESTAMP(6)";
            case UUID -> "UUID";
            case TIME -> "TIME(6)";
            case TIMESTAMPTZ -> "TIMESTAMP(6) WITH TIME ZONE";
            case INTERVAL -> "VARCHAR";
        };
    }

    @Override
    String decimalTypeName(int precision, int scale) {
        return "NUMERIC(" + precision + "," + scale + ")";
    }

    @Override
    public String arithmetic(Expression.ArithmeticOp op, String left, String right) {
        return super.arithmetic(op == Expression.ArithmeticOp.INT_DIVIDE ? Expression.ArithmeticOp.DIVIDE : op, left,
                right);
    }

    @Override
    public String aggregate(AggregateFunction function, boolean distinct, String argument, String separator,
            List<String> orderKeys) {
        if (function != AggregateFunction.STRING_AGG) {
            return super.aggregate(function, distinct, argument, separator, orderKeys);
        }
        return "LISTAGG(" + (distinct ? "DISTINCT " : "") + argument + ", " + separator + ")"
                + (orderKeys.isEmpty() ? "" : " WITHIN GROUP (ORDER BY " + String.join(", ", orderKeys) + ")");
    }

    @Override
    public String setOperation(PlanNode.SetOperationKind kind, boolean all) {
        if (all) {
            throw new IllegalArgumentException(name() + " has no " + kind + " ALL");
        }
        return super.setOperation(kind, all);
    }

    @Override
    boolean storesExactly(DataType type) {
        return exactNumber(type) || type == Scalar.VARCHAR || type == Scalar.DATE || type == Scalar.TIMESTAMP
                || type == Scalar.TIME || type == Scalar.TIMESTAMPTZ;
    }
}
