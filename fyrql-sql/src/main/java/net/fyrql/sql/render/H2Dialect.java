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
        return exactNumber(type) || type == Scalar.VARCHAR || type == Scalar.DATE || type == Scalar.TIMESTAMP;
    }
}
