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
 * the plan of any query. Whether a table to create exists, or one to
 * write or drop, is checked when the statement runs.
 */
public sealed interface BoundStatement {

    /** The columns of the result of a statement that writes, as in DuckDB: the number of rows written. */
    Schema COUNT = Schema.of(new Schema.Field("Count", DataType.BIGINT, false));

    /** Returns the columns of the result: those of a query, or {@link #COUNT}. */
    default Schema schema() {
        return this instanceof Query q ? q.plan().schema() : COUNT;
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

    /** What {@code CREATE TABLE} does with a table that exists. */
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
     * @param schema its columns: the columns of the definition, or those of
     *        the query of {@code AS}, which may hold {@code NULL} as in
     *        DuckDB
     * @param input the rows of {@code AS}, of the types of the schema, or
     *        {@code null} for a table of a definition, which starts empty
     */
    record Create(TableRef table, Schema schema, PlanNode input, CreateMode mode) implements BoundStatement {
        public Create {
            Objects.requireNonNull(table, "table");
            Objects.requireNonNull(schema, "schema");
            Objects.requireNonNull(mode, "mode");
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
}
