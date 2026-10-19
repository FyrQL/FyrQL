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
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.DataType.Scalar;

/**
 * The dialect of PostgreSQL. {@code TINYINT} is {@code SMALLINT},
 * {@code HUGEINT} is {@code NUMERIC(39)}, {@code BLOB} is {@code BYTEA}
 * with the hex form of its literal, and {@code NaN} and the infinities are
 * written as strings. Integer division is written as {@code /}, which
 * divides integers as FyrQL does but not decimals. PostgreSQL orders
 * strings by the collation of the database, so a source evaluates the
 * equality of strings but not their order.
 */
public final class PostgresDialect extends StandardDialect {

    public static final PostgresDialect INSTANCE = new PostgresDialect();

    private PostgresDialect() {
    }

    @Override
    public String name() {
        return "postgresql";
    }

    @Override
    String scalarTypeName(Scalar type) {
        return switch (type) {
            case BOOLEAN -> "BOOLEAN";
            case TINYINT, SMALLINT -> "SMALLINT";
            case INTEGER -> "INTEGER";
            case BIGINT -> "BIGINT";
            case HUGEINT -> "NUMERIC(39)";
            case FLOAT -> "REAL";
            case DOUBLE -> "DOUBLE PRECISION";
            case VARCHAR -> "VARCHAR";
            case BLOB -> "BYTEA";
            case DATE -> "DATE";
            case TIMESTAMP -> "TIMESTAMP";
            case UUID -> "UUID";
        };
    }

    @Override
    String decimalTypeName(int precision, int scale) {
        return "NUMERIC(" + precision + "," + scale + ")";
    }

    @Override
    String nonFiniteLiteral(String digits, Scalar type) {
        String text = switch (digits) {
            case "nan" -> "NaN";
            case "inf" -> "Infinity";
            default -> "-Infinity";
        };
        return "CAST(" + string(text) + " AS " + typeName(type) + ")";
    }

    @Override
    String blobLiteral(byte[] value) {
        StringBuilder b = new StringBuilder("CAST('\\x");
        for (byte x : value) {
            b.append(Character.toUpperCase(Character.forDigit((x >> 4) & 0xF, 16)))
                    .append(Character.toUpperCase(Character.forDigit(x & 0xF, 16)));
        }
        return b.append("' AS BYTEA)").toString();
    }

    @Override
    public String arithmetic(Expression.ArithmeticOp op, String left, String right) {
        return super.arithmetic(op == Expression.ArithmeticOp.INT_DIVIDE ? Expression.ArithmeticOp.DIVIDE : op, left,
                right);
    }

    @Override
    boolean storesExactly(DataType type) {
        return exactNumber(type) || type == Scalar.VARCHAR || type == Scalar.DATE || type == Scalar.TIMESTAMP;
    }
}
