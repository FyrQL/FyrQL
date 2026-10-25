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

package net.fyrql.pgwire;

import net.fyrql.core.function.FunctionRegistry;
import net.fyrql.core.function.ScalarFunction;
import net.fyrql.core.type.DataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.LongBinaryOperator;

/**
 * The functions of PostgreSQL that clients call when they read the
 * catalogs, in the sessions of the server only: {@code format_type},
 * {@code pg_get_userbyid}, {@code has_table_privilege} and its kin, which
 * always grant, the visibility of objects, which they always have, and the
 * texts of definitions of indexes, constraints, rules and views, which
 * FyrQL has none of and so are {@code NULL}. Sizes are 0.
 */
final class PgFunctions {

    private PgFunctions() {
    }

    /** A function of a fixed result for a number of arguments of any type. */
    private static class Fixed implements ScalarFunction {
        private final String name;
        private final int min;
        private final int max;
        private final DataType result;
        private final boolean strict;
        private final Function<Object[], Object> body;

        Fixed(String name, int min, int max, DataType result, boolean strict, Function<Object[], Object> body) {
            this.name = name;
            this.min = min;
            this.max = max;
            this.result = result;
            this.strict = strict;
            this.body = body;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public DataType resultType(List<DataType> arguments) {
            return arguments.size() >= min && arguments.size() <= max ? result : null;
        }

        @Override
        public boolean nullOnNullInput() {
            return strict;
        }

        @Override
        public Object evaluate(List<DataType> argumentTypes, Object[] arguments) {
            return body.apply(arguments);
        }
    }

    private static ScalarFunction constant(String name, int min, int max, DataType result, Object value) {
        return new Fixed(name, min, max, result, false, a -> value);
    }

    /** What the functions of a connection look up in the catalogs. */
    interface Lookups {
        /** The OID of a table by its name along the search path, or {@code null} if there is none. */
        Integer relation(String name);

        /** The names of the columns of a table by its OID, or an empty list. */
        List<String> columns(int oid);
    }

    /** Returns the functions of the engine and those of the server, in a registry of their own. */
    static FunctionRegistry registry(FunctionRegistry engine, String user, Lookups lookups) {
        FunctionRegistry r = engine.copy();
        for (ScalarFunction f : all(user)) {
            r.register(f);
        }
        Function<String, Integer> relations = lookups::relation;
        r.register(new Fixed("attribute_names", 2, 2, DataType.VARCHAR, true, a -> {
            List<String> names = lookups.columns(((Number) a[0]).intValue());
            List<String> picked = new ArrayList<>();
            for (Object n : (List<?>) a[1]) {
                int i = n == null ? -1 : ((Number) n).intValue();
                if (i >= 1 && i <= names.size()) {
                    picked.add(names.get(i - 1));
                }
            }
            return String.join(", ", picked);
        }) {
            @Override
            public boolean foldable() {
                return false;
            }
        });
        r.register(new Fixed("to_regclass", 1, 1, DataType.INTEGER, true, a -> a[0] instanceof Number n ? n.intValue()
                : relations.apply(a[0].toString())) {
            @Override
            public DataType resultType(List<DataType> arguments) {
                return arguments.size() == 1 && (arguments.get(0) == DataType.VARCHAR || arguments.get(0).isIntegral())
                        ? DataType.INTEGER : null;
            }

            @Override
            public boolean foldable() {
                return false;
            }
        });
        return r;
    }

    /** The functions of the server; those of the user name the given user. */
    static List<ScalarFunction> all(String user) {
        return List.of(
                new Fixed("format_type", 1, 2, DataType.VARCHAR, false, a -> a[0] == null ? null
                        : PgCatalog.formatType(number(a[0]), a.length > 1 && a[1] != null ? number(a[1]) : null)),
                constant("pg_get_userbyid", 1, 1, DataType.VARCHAR, PgWireServer.OWNER),
                constant("pg_encoding_to_char", 1, 1, DataType.VARCHAR, "UTF8"),
                constant("pg_char_to_encoding", 1, 1, DataType.INTEGER, 6),
                constant("pg_table_is_visible", 1, 1, DataType.BOOLEAN, true),
                constant("pg_type_is_visible", 1, 1, DataType.BOOLEAN, true),
                constant("pg_function_is_visible", 1, 1, DataType.BOOLEAN, true),
                constant("pg_collation_is_visible", 1, 1, DataType.BOOLEAN, true),
                constant("has_table_privilege", 2, 3, DataType.BOOLEAN, true),
                constant("has_schema_privilege", 2, 3, DataType.BOOLEAN, true),
                constant("has_database_privilege", 2, 3, DataType.BOOLEAN, true),
                constant("has_column_privilege", 3, 4, DataType.BOOLEAN, true),
                constant("has_any_column_privilege", 2, 3, DataType.BOOLEAN, true),
                constant("has_function_privilege", 2, 3, DataType.BOOLEAN, true),
                constant("has_sequence_privilege", 2, 3, DataType.BOOLEAN, true),
                constant("pg_has_role", 2, 3, DataType.BOOLEAN, true),
                constant("obj_description", 1, 2, DataType.VARCHAR, null),
                constant("col_description", 2, 2, DataType.VARCHAR, null),
                constant("shobj_description", 2, 2, DataType.VARCHAR, null),
                constant("pg_get_expr", 2, 3, DataType.VARCHAR, null),
                constant("pg_get_indexdef", 1, 3, DataType.VARCHAR, null),
                constant("pg_get_constraintdef", 1, 2, DataType.VARCHAR, null),
                constant("pg_get_ruledef", 1, 2, DataType.VARCHAR, null),
                constant("pg_get_triggerdef", 1, 2, DataType.VARCHAR, null),
                constant("pg_get_partkeydef", 1, 1, DataType.VARCHAR, null),
                constant("pg_get_viewdef", 1, 2, DataType.VARCHAR, null),
                constant("pg_get_statisticsobjdef_columns", 1, 1, DataType.VARCHAR, null),
                constant("pg_get_function_result", 1, 1, DataType.VARCHAR, null),
                constant("pg_get_function_arguments", 1, 1, DataType.VARCHAR, null),
                constant("pg_get_function_identity_arguments", 1, 1, DataType.VARCHAR, null),
                constant("pg_get_serial_sequence", 2, 2, DataType.VARCHAR, null),
                constant("pg_relation_size", 1, 2, DataType.BIGINT, 0L),
                constant("pg_total_relation_size", 1, 1, DataType.BIGINT, 0L),
                constant("pg_table_size", 1, 1, DataType.BIGINT, 0L),
                constant("pg_indexes_size", 1, 1, DataType.BIGINT, 0L),
                constant("pg_database_size", 1, 1, DataType.BIGINT, 0L),
                constant("pg_stat_get_numscans", 1, 1, DataType.BIGINT, 0L),
                constant("pg_tablespace_location", 1, 1, DataType.VARCHAR, ""),
                constant("pg_relation_is_publishable", 1, 1, DataType.BOOLEAN, false),
                constant("pg_is_in_recovery", 0, 0, DataType.BOOLEAN, false),
                constant("pg_cancel_backend", 1, 1, DataType.BOOLEAN, false),
                constant("pg_terminate_backend", 1, 2, DataType.BOOLEAN, false),
                constant("txid_current", 0, 0, DataType.BIGINT, 1L),
                constant("inet_server_addr", 0, 0, DataType.VARCHAR, null),
                constant("inet_server_port", 0, 0, DataType.INTEGER, null),
                constant("inet_client_addr", 0, 0, DataType.VARCHAR, null),
                new Fixed("array_upper", 2, 2, DataType.INTEGER, true, a -> number(a[1]) != 1
                        || ((List<?>) a[0]).isEmpty() ? null : ((List<?>) a[0]).size()) {
                    @Override
                    public DataType resultType(List<DataType> arguments) {
                        return arguments.size() == 2 && arguments.get(0) instanceof DataType.ListType ? DataType.INTEGER
                                : null;
                    }
                },
                new Fixed("array_lower", 2, 2, DataType.INTEGER, true, a -> number(a[1]) != 1
                        || ((List<?>) a[0]).isEmpty() ? null : 1) {
                    @Override
                    public DataType resultType(List<DataType> arguments) {
                        return arguments.size() == 2 && arguments.get(0) instanceof DataType.ListType ? DataType.INTEGER
                                : null;
                    }
                },
                new Fixed("prefix_elements", 2, 2, DataType.listOf(DataType.VARCHAR), true, a -> {
                    List<Object> result = new ArrayList<>();
                    for (Object e : (List<?>) a[0]) {
                        result.add(e == null ? null : a[1] + e.toString());
                    }
                    return result;
                }),
                new Fixed("role_names", 1, 1, DataType.listOf(DataType.VARCHAR), true, a -> {
                    List<Object> result = new ArrayList<>();
                    for (Object e : (List<?>) a[0]) {
                        if (e instanceof Number n && n.intValue() == PgCatalog.OWNER) {
                            result.add(PgWireServer.OWNER);
                        }
                    }
                    return result;
                }),
                new Fixed("pg_partition_ancestors", 1, 1, DataType.INTEGER, true, a -> a[0] instanceof Number n
                        ? n.intValue() : Integer.valueOf(a[0].toString().strip())),
                integral("bitwise_and", (x, y) -> x & y),
                integral("bitwise_or", (x, y) -> x | y),
                new Fixed("quote_ident", 1, 1, DataType.VARCHAR, true, a -> PgCatalog.quoteIdentifier(text(a[0]))),
                new Fixed("quote_literal", 1, 1, DataType.VARCHAR, true, a -> PgSql.literal(text(a[0]))),
                new Fixed("pg_size_pretty", 1, 1, DataType.VARCHAR, true, a -> size(((Number) a[0]).longValue())),
                new Fixed("format", 1, 64, DataType.VARCHAR, false, PgFunctions::format));
    }

    /** {@code a & b} and {@code a | b} of PostgreSQL over integers, as a {@code BIGINT}. */
    private static ScalarFunction integral(String name, LongBinaryOperator op) {
        return new Fixed(name, 2, 2, DataType.BIGINT, true, a -> op.applyAsLong(((Number) a[0]).longValue(),
                ((Number) a[1]).longValue())) {
            @Override
            public DataType resultType(List<DataType> arguments) {
                return arguments.size() == 2 && arguments.get(0).isIntegral() && arguments.get(1).isIntegral()
                        && arguments.get(0) != DataType.HUGEINT && arguments.get(1) != DataType.HUGEINT
                        ? DataType.BIGINT : null;
            }
        };
    }

    private static int number(Object o) {
        return ((Number) o).intValue();
    }

    private static String text(Object o) {
        return o == null ? null : o.toString();
    }

    /** A size as {@code pg_size_pretty} writes it, such as {@code 10 kB}. */
    static String size(long bytes) {
        String[] units = {"bytes", "kB", "MB", "GB", "TB", "PB"};
        double v = bytes;
        int u = 0;
        while (Math.abs(v) >= 10 * 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return Math.round(v) + " " + units[u];
    }

    /** {@code format(text, ...)} with {@code %s}, {@code %I}, {@code %L} and {@code %%}. */
    static Object format(Object[] a) {
        if (a[0] == null) {
            return null;
        }
        String f = a[0].toString();
        StringBuilder b = new StringBuilder();
        int next = 1;
        for (int i = 0; i < f.length(); i++) {
            char c = f.charAt(i);
            if (c != '%' || i + 1 == f.length()) {
                b.append(c);
                continue;
            }
            char k = f.charAt(++i);
            if (k == '%') {
                b.append('%');
                continue;
            }
            if (next >= a.length) {
                throw new IllegalArgumentException("too few arguments for format()");
            }
            Object v = a[next++];
            switch (Character.toLowerCase(k)) {
                case 's' -> b.append(v == null ? "" : v.toString());
                case 'i' -> {
                    if (v == null) {
                        throw new IllegalArgumentException("null values cannot be formatted as an SQL identifier");
                    }
                    b.append(PgCatalog.quoteIdentifier(v.toString()));
                }
                case 'l' -> b.append(v == null ? "NULL" : PgSql.literal(v.toString()));
                default -> throw new IllegalArgumentException("unrecognized format() type specifier \""
                        + String.valueOf(k).toLowerCase(Locale.ROOT) + "\"");
            }
        }
        return b.toString();
    }
}
