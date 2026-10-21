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

import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.DataType.Decimal;
import net.fyrql.core.type.DataType.ListType;
import net.fyrql.core.type.DataType.MapType;
import net.fyrql.core.type.DataType.Scalar;
import net.fyrql.core.type.DataType.Struct;
import net.fyrql.core.value.Casts;
import net.fyrql.core.value.Conversions;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * The dialect of DuckDB, which is also the dialect of FyrQL: every type has
 * its own name, every literal is written with an explicit cast so that it
 * has exactly the type of the expression, and all join types exist.
 */
public final class DuckDbDialect implements Dialect {

    public static final DuckDbDialect INSTANCE = new DuckDbDialect();

    private DuckDbDialect() {
    }

    @Override
    public String name() {
        return "duckdb";
    }

    @Override
    public String quoteIdentifier(String name) {
        return '"' + name.replace("\"", "\"\"") + '"';
    }

    @Override
    public String typeName(DataType type) {
        return type.sqlName();
    }

    @Override
    public String literal(Object value, DataType type) {
        if (value == null) {
            return "CAST(NULL AS " + typeName(type) + ")";
        }
        if (type instanceof Decimal) {
            return "CAST('" + ((BigDecimal) value).toPlainString() + "' AS " + typeName(type) + ")";
        }
        if (type instanceof Struct s) {
            List<?> values = (List<?>) value;
            StringBuilder b = new StringBuilder("{");
            for (int i = 0; i < values.size(); i++) {
                b.append(i > 0 ? ", " : "").append(string(s.fields().get(i).name())).append(": ")
                        .append(literal(values.get(i), s.fields().get(i).type()));
            }
            return "CAST(" + b.append('}') + " AS " + typeName(type) + ")";
        }
        if (type instanceof ListType l) {
            StringBuilder b = new StringBuilder("[");
            int i = 0;
            for (Object v : (List<?>) value) {
                b.append(i++ > 0 ? ", " : "").append(literal(v, l.element()));
            }
            return "CAST(" + b.append(']') + " AS " + typeName(type) + ")";
        }
        if (type instanceof MapType m) {
            StringBuilder keys = new StringBuilder("[");
            StringBuilder values = new StringBuilder("[");
            int i = 0;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                keys.append(i > 0 ? ", " : "").append(literal(e.getKey(), m.key()));
                values.append(i++ > 0 ? ", " : "").append(literal(e.getValue(), m.value()));
            }
            return "CAST(map(" + keys.append(']') + ", " + values.append(']') + ") AS " + typeName(type) + ")";
        }
        return switch ((Scalar) type) {
            case BOOLEAN -> value.toString();
            case TINYINT, SMALLINT, INTEGER, BIGINT, HUGEINT -> "CAST(" + value + " AS " + typeName(type) + ")";
            case FLOAT -> "CAST(" + string(Conversions.formatFloat((Float) value)) + " AS FLOAT)";
            case DOUBLE -> "CAST(" + string(Conversions.formatDouble((Double) value)) + " AS DOUBLE)";
            case VARCHAR -> string((String) value);
            case BLOB -> "CAST(" + string(Conversions.formatBlob((byte[]) value)) + " AS BLOB)";
            case DATE -> "DATE " + string(Conversions.formatDate((Integer) value));
            case TIMESTAMP -> "TIMESTAMP " + string(Conversions.formatTimestamp((Long) value));
            case UUID -> "CAST(" + string(value.toString()) + " AS UUID)";
            case TIME -> "TIME " + string(Conversions.formatTime((Long) value));
            case TIMESTAMPTZ -> "TIMESTAMPTZ " + string(Conversions.formatTimestampTz((Long) value,
                    java.time.ZoneOffset.UTC));
            case INTERVAL -> "INTERVAL " + string(value.toString());
        };
    }

    /** A string literal in single quotes; DuckDB has no backslash escapes, only the doubled quote. */
    private static String string(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    @Override
    public boolean supportsJoin(PlanNode.JoinType type) {
        return type != PlanNode.JoinType.MARK && type != PlanNode.JoinType.SINGLE;
    }

    @Override
    public boolean hasSemiJoinKeywords() {
        return true;
    }

    @Override
    public String joinKeyword(PlanNode.JoinType type) {
        return switch (type) {
            case INNER -> "INNER JOIN";
            case LEFT -> "LEFT JOIN";
            case RIGHT -> "RIGHT JOIN";
            case FULL -> "FULL JOIN";
            case SEMI -> "SEMI JOIN";
            case ANTI -> "ANTI JOIN";
            case CROSS -> "CROSS JOIN";
            case MARK, SINGLE -> throw new IllegalArgumentException("DuckDB has no " + type + " join");
        };
    }

    @Override
    public String functionName(String name) {
        return name;
    }

    /** The text form of a value, for messages. */
    static String describe(Object value, DataType type) {
        return Casts.format(value, type);
    }
}
