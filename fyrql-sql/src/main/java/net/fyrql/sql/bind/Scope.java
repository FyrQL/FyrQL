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

package net.fyrql.sql.bind;

import net.fyrql.core.expr.Expression;
import net.fyrql.sql.SqlException;
import net.fyrql.sql.ast.Position;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The columns that a {@code FROM} clause offers to the expressions of its
 * query, each with the table it comes from and its value as an expression
 * over the output of the plan of the clause. Names match without regard to
 * case, as in DuckDB; an exact match decides between columns of one table
 * that differ only in case. The scope of a subquery may reach the columns
 * of the query around it as well, after its own; see {@link Outer}.
 */
final class Scope {

    /**
     * Resolves the names that the columns of a scope do not reach, in the
     * scope of the query around a subquery.
     */
    interface Outer {
        /**
         * Resolves a name of one or more parts as {@link Scope#resolve} does.
         *
         * @return the column and the fields, or {@code null} if no prefix names a column
         */
        Resolved resolve(List<String> name, Position position);
    }

    /**
     * A table of a {@code FROM} clause as qualifiers name it: by its alias,
     * which is the table name if none is given, and, for a table of the
     * catalog without an alias, also by {@code schema.table} and
     * {@code source.schema.table}.
     *
     * @param alias the alias
     * @param path source, schema and table of a table without an alias, otherwise empty
     */
    record Table(String alias, List<String> path) {
        Table {
            Objects.requireNonNull(alias, "alias");
            path = List.copyOf(path);
        }

        /** Tells whether a qualifier of one to three parts names this table. */
        boolean matches(List<String> qualifier) {
            if (qualifier.size() == 1) {
                return alias.equalsIgnoreCase(qualifier.get(0));
            }
            if (path.isEmpty() || qualifier.size() > path.size()) {
                return false;
            }
            List<String> tail = path.subList(path.size() - qualifier.size(), path.size());
            for (int i = 0; i < qualifier.size(); i++) {
                if (!tail.get(i).equalsIgnoreCase(qualifier.get(i))) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * A column.
     *
     * @param table the table, {@code null} for the merged column of a
     *        {@code USING} join, which no qualifier names
     * @param name the name
     * @param expression the value, over the output of the plan of the clause
     * @param qualifiedOnly whether only a qualified name or {@code t.*}
     *        reaches the column, as for the two columns a {@code USING} join
     *        merges
     * @param untypedNull whether the column is a bare {@code NULL} of a
     *        subquery, whose type the context decides as for a literal
     */
    record Column(Table table, String name, Expression expression, boolean qualifiedOnly, boolean untypedNull) {
        Column {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(expression, "expression");
        }

        Column(Table table, String name, Expression expression, boolean qualifiedOnly) {
            this(table, name, expression, qualifiedOnly, false);
        }

        /** Returns the same column, reachable only by a qualified name. */
        Column hidden() {
            return new Column(table, name, expression, true, untypedNull);
        }
    }

    private final List<Column> columns;
    private final boolean hasFrom;
    private final Outer outer;

    Scope(List<Column> columns, boolean hasFrom) {
        this(columns, hasFrom, null);
    }

    private Scope(List<Column> columns, boolean hasFrom, Outer outer) {
        this.columns = List.copyOf(columns);
        this.hasFrom = hasFrom;
        this.outer = outer;
    }

    /** The same columns, and the names they do not reach resolved by another scope. */
    Scope withOuter(Outer newOuter) {
        return new Scope(columns, hasFrom, newOuter);
    }

    /** The scope of a query without {@code FROM}. */
    static Scope none() {
        return new Scope(List.of(), false);
    }

    List<Column> columns() {
        return columns;
    }

    boolean hasFrom() {
        return hasFrom;
    }

    /**
     * Finds the column with a name of one to four parts: the column, after
     * one to three parts that name its table.
     *
     * @return the column, or {@code null} if there is none
     * @throws SqlException if the name is ambiguous, or the qualifier names no table
     */
    Column find(List<String> name, Position position) {
        return find(name, false, position);
    }

    /**
     * A column that a name reaches, with the parts of the name after it,
     * which name fields of its value.
     */
    record Resolved(Column column, List<String> fields) {
        Resolved {
            Objects.requireNonNull(column, "column");
            fields = List.copyOf(fields);
        }
    }

    /**
     * Resolves a name of one or more parts as DuckDB does: the longest
     * prefix of at most four parts that names a column, after one to three
     * parts that name its table, is the column, and the parts after it name
     * fields. So {@code t.a} is the column {@code a} of the table {@code t}
     * if there is one, and otherwise the field {@code a} of a column
     * {@code t}. A name that no prefix reaches goes to the outer scope, if
     * there is one.
     *
     * @return the column and the fields, or {@code null} if no prefix names a column
     * @throws SqlException if a prefix is ambiguous, or the first part of a
     *         name of several parts names neither a table nor a column
     */
    Resolved resolve(List<String> name, Position position) {
        Resolved r = lookup(name, position);
        if (r == null && name.size() > 1) {
            // Report the first part as the table it would have to be.
            tables(name.subList(0, 1), false, position);
        }
        return r;
    }

    /**
     * Resolves a name as {@link #resolve} does, but finds nothing where the
     * first part of a name of several parts names no table.
     */
    Resolved lookup(List<String> name, Position position) {
        for (int k = Math.min(name.size(), 4); k >= 1; k--) {
            Column c = find(name.subList(0, k), true, position);
            if (c != null) {
                return new Resolved(c, name.subList(k, name.size()));
            }
        }
        return outer == null ? null : outer.resolve(name, position);
    }

    /**
     * Finds a column by a name of one to four parts, see {@link #find}; if
     * lenient, a qualifier that names no table is no error but finds nothing.
     */
    private Column find(List<String> name, boolean lenient, Position position) {
        String column = name.get(name.size() - 1);
        List<Column> candidates = new ArrayList<>();
        if (name.size() == 1) {
            for (Column c : columns) {
                if (!c.qualifiedOnly() && c.name().equalsIgnoreCase(column)) {
                    candidates.add(c);
                }
            }
        } else {
            List<String> qualifier = name.subList(0, name.size() - 1);
            Set<Table> tables = tables(qualifier, lenient, position);
            for (Column c : columns) {
                if (c.table() != null && tables.contains(c.table()) && c.name().equalsIgnoreCase(column)) {
                    candidates.add(c);
                }
            }
        }
        if (candidates.size() > 1) {
            // The merged column of a USING join comes before the other columns of its name, as in DuckDB.
            List<Column> merged = candidates.stream().filter(c -> c.table() == null).toList();
            if (merged.size() == 1) {
                return merged.get(0);
            }
            List<Column> exact = candidates.stream().filter(c -> c.name().equals(column)).toList();
            if (exact.size() == 1 && sameTable(candidates)) {
                return exact.get(0);
            }
            List<String> options = new ArrayList<>();
            for (Column c : candidates) {
                options.add("\"" + (c.table() != null ? c.table().alias() + "." : "") + c.name() + "\"");
            }
            throw new SqlException("Ambiguous reference to column name \"" + column + "\" (use: "
                    + String.join(" or ", options) + ")", position);
        }
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private static boolean sameTable(List<Column> candidates) {
        for (Column c : candidates) {
            if (c.table() != candidates.get(0).table()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the tables a qualifier names: exactly one, or none if lenient.
     *
     * @throws SqlException if it names several, or none and is not lenient
     */
    private Set<Table> tables(List<String> qualifier, boolean lenient, Position position) {
        Set<Table> tables = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Column c : columns) {
            if (c.table() != null && c.table().matches(qualifier)) {
                tables.add(c.table());
            }
        }
        String name = String.join(".", qualifier);
        if (tables.isEmpty() && lenient) {
            return tables;
        }
        if (tables.isEmpty()) {
            List<String> candidates = new ArrayList<>();
            for (Column c : columns) {
                if (c.table() != null && !candidates.contains("\"" + c.table().alias() + "\"")) {
                    candidates.add("\"" + c.table().alias() + "\"");
                }
            }
            throw new SqlException("Referenced table \"" + name + "\" not found!"
                    + (candidates.isEmpty() ? "" : " Candidate tables: " + String.join(", ", candidates)), position);
        }
        if (tables.size() > 1) {
            throw new SqlException("Ambiguous reference to table \"" + name + "\" (duplicate alias \"" + name
                    + "\", explicitly alias one of the tables using \"AS my_alias\")", position);
        }
        return tables;
    }

    /**
     * Returns the columns of {@code *}, or of {@code t.*} for a qualifier.
     *
     * @throws SqlException if the qualifier names no table or several, or
     *         an unqualified star meets two tables with the same alias
     */
    List<Column> star(List<String> qualifier, Position position) {
        if (!hasFrom) {
            throw new SqlException("SELECT * expression without FROM clause!", position);
        }
        List<Column> result = new ArrayList<>();
        if (qualifier.isEmpty()) {
            List<Table> seen = new ArrayList<>();
            for (Column c : columns) {
                Table t = c.table();
                if (t != null && seen.stream().noneMatch(s -> s == t)) {
                    if (seen.stream().anyMatch(s -> s.alias().equalsIgnoreCase(t.alias()))) {
                        throw new SqlException("Ambiguous reference to table \"" + t.alias() + "\" (duplicate alias \""
                                + t.alias() + "\", explicitly alias one of the tables using \"AS my_alias\")",
                                position);
                    }
                    seen.add(t);
                }
                if (!c.qualifiedOnly()) {
                    result.add(c);
                }
            }
            return result;
        }
        Set<Table> tables = tables(qualifier, false, position);
        for (Column c : columns) {
            if (c.table() != null && tables.contains(c.table())) {
                result.add(c);
            }
        }
        return result;
    }
}
