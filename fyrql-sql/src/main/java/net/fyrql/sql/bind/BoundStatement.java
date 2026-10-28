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

package net.fyrql.sql.bind;

import net.fyrql.core.expr.Expression;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A statement, bound: a query as its plan, or a statement that writes with
 * the plan of the rows it writes, which the engine optimizes and runs as
 * the plan of any query, or the definition of a view, or a setting of the
 * session, or {@code EXPLAIN} of one of these. Whether a table or a view to
 * create exists, or one to write or drop, is checked when the statement
 * runs.
 */
public sealed interface BoundStatement {

    /** The columns of the result of a statement that writes, as in DuckDB: the number of rows written. */
    Schema COUNT = Schema.of(new Schema.Field("Count", DataType.BIGINT, false));

    /** The columns of the result of {@code EXPLAIN}: the plan, one line per row. */
    Schema PLAN = Schema.of(new Schema.Field("plan", DataType.VARCHAR, false));

    /** Returns the columns of the result: those of a query, {@link #PLAN} or {@link #COUNT}. */
    default Schema schema() {
        return switch (this) {
            case Query q -> q.plan().schema();
            case Explain e -> PLAN;
            default -> COUNT;
        };
    }

    /**
     * Tells whether running the statement changes something: a table, a
     * file or a setting of the session. A query does not, nor does
     * {@code EXPLAIN} without {@code ANALYZE}.
     */
    default boolean writes() {
        return switch (this) {
            case Query q -> false;
            case Explain e -> e.analyze() && e.statement().writes();
            default -> true;
        };
    }

    /** A query. */
    record Query(PlanNode plan) implements BoundStatement {
        public Query {
            Objects.requireNonNull(plan, "plan");
        }
    }

    /**
     * {@code COPY ... TO}.
     *
     * @param input the rows to write
     * @param path the file, as written
     * @param format the format in lower case, from {@code FORMAT} or from
     *        the extension of the file
     * @param options the options besides the format, by names in lower
     *        case, in their order, with {@code compression} from an
     *        extension {@code .gz} or {@code .zst} of a text format if not
     *        given
     */
    record Copy(PlanNode input, String path, String format, Map<String, String> options) implements BoundStatement {
        public Copy {
            Objects.requireNonNull(input, "input");
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(format, "format");
            options = Collections.unmodifiableMap(new LinkedHashMap<>(options));
        }
    }

    /**
     * {@code COPY ... FROM}.
     *
     * @param table the table
     * @param input the rows of the file, in the columns of the table, in
     *        their order and of their types
     * @param path the file, as written
     * @param format the format in lower case, as in {@link Copy}
     * @param options the options besides the format, as in {@link Copy}
     */
    record CopyFrom(TableRef table, PlanNode input, String path, String format, Map<String, String> options)
            implements BoundStatement {
        public CopyFrom {
            Objects.requireNonNull(table, "table");
            Objects.requireNonNull(input, "input");
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(format, "format");
            options = Collections.unmodifiableMap(new LinkedHashMap<>(options));
        }
    }

    /** What {@code CREATE TABLE} does with a table that exists, or {@code CREATE VIEW} with a view. */
    enum CreateMode {
        /** Fails. */
        CREATE,
        /** {@code OR REPLACE}: drops it first. */
        OR_REPLACE,
        /** {@code IF NOT EXISTS}: writes nothing. */
        IF_NOT_EXISTS
    }

    /**
     * {@code CREATE TABLE}.
     *
     * @param table where the table goes
     * @param columns its columns: the columns of the definition, or those of
     *        the query of {@code AS}, which may hold {@code NULL} as in
     *        DuckDB; not named {@code schema}, whose accessor would hide
     *        {@link BoundStatement#schema()}, the column {@code Count}
     * @param input the rows of {@code AS}, of the types of the columns, or
     *        {@code null} for a table of a definition, which starts empty
     * @param primaryKey the names of the columns of the primary key, as in
     *        the columns, which are {@code NOT NULL}; empty for none
     */
    record Create(TableRef table, Schema columns, PlanNode input, CreateMode mode, List<String> primaryKey)
            implements BoundStatement {
        public Create {
            Objects.requireNonNull(table, "table");
            Objects.requireNonNull(columns, "columns");
            Objects.requireNonNull(mode, "mode");
            primaryKey = List.copyOf(primaryKey);
        }
    }

    /**
     * {@code INSERT INTO}.
     *
     * @param table the table
     * @param input the rows, in the columns of the table, in their order
     *        and of their types
     */
    record Insert(TableRef table, PlanNode input) implements BoundStatement {
        public Insert {
            Objects.requireNonNull(table, "table");
            Objects.requireNonNull(input, "input");
        }
    }

    /**
     * {@code UPDATE}. The rows to update come as the {@code keyed} plan: the
     * key columns of the table with the values of the rows before the
     * update, then the new values of the columns to set, in the order of
     * {@code columns}; a row of the table that several rows of
     * {@code FROM} match comes once for each, and the last one counts. A
     * statement without {@code FROM} and without subqueries also comes as
     * expressions over the columns of the table, which a source may run as
     * a whole.
     *
     * @param table the table
     * @param columns the positions of the columns to set, each once
     * @param values for each column to set, its new value as an expression
     *        over the columns of the table, of the type of the column; or
     *        {@code null} for a statement that only comes keyed
     * @param condition the condition over the columns of the table, or
     *        {@code null} for every row
     * @param keys the positions of the key columns: the primary key, or
     *        every column of a table without one
     * @param keyed the rows to update by key
     */
    record Update(TableRef table, int[] columns, List<Expression> values, Expression condition, int[] keys,
            PlanNode keyed) implements BoundStatement {
        public Update {
            Objects.requireNonNull(table, "table");
            columns = columns.clone();
            values = values == null ? null : List.copyOf(values);
            keys = keys.clone();
            Objects.requireNonNull(keyed, "keyed");
        }
    }

    /**
     * {@code DELETE}. The rows to delete come as the {@code keyed} plan of
     * their key columns, a row possibly more than once; a statement without
     * {@code USING} and without subqueries also comes as its condition,
     * which a source may run as a whole.
     *
     * @param table the table
     * @param condition the condition over the columns of the table, or
     *        {@code null} for every row
     * @param simple whether the statement comes as its condition too
     * @param keys the positions of the key columns, as in {@link Update}
     * @param keyed the keys of the rows to delete
     */
    record Delete(TableRef table, Expression condition, boolean simple, int[] keys, PlanNode keyed)
            implements BoundStatement {
        public Delete {
            Objects.requireNonNull(table, "table");
            keys = keys.clone();
            Objects.requireNonNull(keyed, "keyed");
        }
    }

    /**
     * {@code CREATE SCHEMA}.
     *
     * @param source the source of the schema
     * @param name the name of the schema; not {@code schema}, whose accessor
     *        would hide {@link BoundStatement#schema()}
     */
    record CreateSchema(String source, String name, boolean ifNotExists) implements BoundStatement {
        public CreateSchema {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(name, "name");
        }
    }

    /**
     * {@code ALTER TABLE ... ADD COLUMN}.
     *
     * @param column the new column, which may hold {@code NULL}
     * @param defaultValue the value of the column in the rows of the table,
     *        as a value of its type, or {@code null} for {@code NULL}
     */
    record AddColumn(TableRef table, Schema.Field column, Object defaultValue, boolean ifNotExists)
            implements BoundStatement {
        public AddColumn {
            Objects.requireNonNull(table, "table");
            Objects.requireNonNull(column, "column");
        }
    }

    /** What a branch of {@code MERGE} does. */
    enum MergeAction {
        UPDATE, DELETE, INSERT, NOTHING
    }

    /**
     * {@code MERGE}. The changes come as the {@code changes} plan, in the
     * order of the pairs of a row of the table and one of the source, and
     * of the rows of either side alone, on which a branch acts other than
     * with {@code DO NOTHING}: the number of the branch from 1, the key
     * columns of the table with the values of its row, then every column of
     * the table with its new value for an update or its value for an
     * insert.
     *
     * @param table the table
     * @param actions for each branch from the first, what it does
     * @param keys the positions of the key columns, as in {@link Update}
     * @param updated the positions of the columns that an update sets: those
     *        that some branch with {@code UPDATE} sets, in their order in the
     *        table
     * @param changes the changes
     */
    record Merge(TableRef table, List<MergeAction> actions, int[] keys, int[] updated, PlanNode changes)
            implements BoundStatement {
        public Merge {
            Objects.requireNonNull(table, "table");
            actions = List.copyOf(actions);
            keys = keys.clone();
            updated = updated.clone();
            Objects.requireNonNull(changes, "changes");
        }
    }

    /**
     * {@code DROP TABLE}.
     *
     * @param table the table, or {@code null} if there was none of the name
     *        and {@code IF EXISTS} was given
     * @param name the name as written
     */
    record Drop(TableRef table, List<String> name, boolean ifExists) implements BoundStatement {
        public Drop {
            name = List.copyOf(name);
            if (table == null && !ifExists) {
                throw new IllegalArgumentException("Only DROP TABLE IF EXISTS may name no table");
            }
        }
    }

    /**
     * {@code CREATE VIEW}; its result is a count of 0.
     *
     * @param view where the view goes
     * @param definition the view
     * @param mode what to do with a view of the name that exists; a table
     *        of the name is always an error
     */
    record CreateView(TableRef view, ViewDefinition definition, CreateMode mode) implements BoundStatement {
        public CreateView {
            Objects.requireNonNull(view, "view");
            Objects.requireNonNull(definition, "definition");
            Objects.requireNonNull(mode, "mode");
        }
    }

    /**
     * {@code DROP VIEW}; its result is a count of 0.
     *
     * @param view the view, or {@code null} if there was none of the name
     *        and {@code IF EXISTS} was given
     * @param name the name as written
     */
    record DropView(TableRef view, List<String> name, boolean ifExists) implements BoundStatement {
        public DropView {
            name = List.copyOf(name);
            if (view == null && !ifExists) {
                throw new IllegalArgumentException("Only DROP VIEW IF EXISTS may name no view");
            }
        }
    }

    /**
     * {@code SET} or {@code RESET} of a setting of the session; its result
     * is a count of 0.
     *
     * @param name the name of the setting as FyrQL spells it, see
     *        {@link Settings#NAMES}, or {@link Settings#ALL} for every setting
     * @param value the new value, checked and written as {@code SHOW} shows
     *        it, or {@code null} for the value the session started with
     */
    record Set(String name, String value) implements BoundStatement {
        public Set {
            Objects.requireNonNull(name, "name");
        }
    }

    /**
     * {@code EXPLAIN}: the plan of a statement, or with {@code ANALYZE} the
     * plan after the statement ran, with what each operator did; a
     * statement that writes then writes.
     *
     * @param analyze whether the statement runs
     * @param statement the statement, which is no {@code EXPLAIN}
     */
    record Explain(boolean analyze, BoundStatement statement) implements BoundStatement {
        public Explain {
            Objects.requireNonNull(statement, "statement");
            if (statement instanceof Explain) {
                throw new IllegalArgumentException("EXPLAIN of EXPLAIN");
            }
        }
    }
}
