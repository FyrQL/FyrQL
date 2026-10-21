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

package net.fyrql.test;

import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.sql.render.DuckDbDialect;
import net.fyrql.sql.render.SqlRenderer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * The reference engine: an in-memory DuckDB that holds copies of the
 * tables of a plan, runs the SQL the renderer writes for the plan, and
 * returns rows as FyrQL values.
 */
public final class DuckDbReference implements AutoCloseable {

    private static final int INSERT_CHUNK = 500;

    private final Connection connection;
    private final SqlRenderer renderer = new SqlRenderer(DuckDbDialect.INSTANCE, DuckDbReference::tableName);

    public DuckDbReference() {
        try {
            connection = DriverManager.getConnection("jdbc:duckdb:");
            // DuckDB takes the time zone of the system, FyrQL that of the JVM.
            try (Statement s = connection.createStatement()) {
                s.execute("SET TimeZone = '" + ZoneId.systemDefault().getId() + "'");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot open DuckDB", e);
        }
    }

    /** The name of the copy of a table in DuckDB. */
    public static String tableName(TableRef table) {
        return DuckDbDialect.INSTANCE.quoteIdentifier(table.source() + "_" + table.schema() + "_" + table.table());
    }

    /** Returns the connection, for probes of DuckDB itself. */
    public Connection connection() {
        return connection;
    }

    /** Returns the renderer for DuckDB. */
    public SqlRenderer renderer() {
        return renderer;
    }

    /** Creates the copy of a table and fills it; an existing copy is replaced. */
    public void load(TableRef table, Schema schema, List<Batch> batches) {
        load(tableName(table), schema, batches);
    }

    /**
     * Creates a table under a name, quoted as needed, and fills it; an
     * existing table of the name is replaced.
     */
    public void load(String name, Schema schema, List<Batch> batches) {
        execute("DROP TABLE IF EXISTS " + name);
        execute(renderer.createTable(name, schema));
        List<Object[]> rows = new ArrayList<>();
        for (Batch b : batches) {
            rows.addAll(b.rows());
        }
        for (int from = 0; from < rows.size(); from += INSERT_CHUNK) {
            execute(renderer.insert(name, schema, rows.subList(from, Math.min(rows.size(), from + INSERT_CHUNK))));
        }
    }

    /** Runs a statement without a result. */
    public void execute(String sql) {
        try (Statement s = connection.createStatement()) {
            s.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("DuckDB rejects: " + sql, e);
        }
    }

    /** Renders a plan as SQL for DuckDB. */
    public String render(PlanNode plan) {
        return renderer.render(plan);
    }

    /** Runs a query and returns its rows as the FyrQL values of the schema. */
    public List<Object[]> query(String sql, Schema schema) {
        List<Object[]> rows = new ArrayList<>();
        try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            if (n != schema.size()) {
                throw new IllegalStateException("DuckDB returns " + n + " columns, the plan " + schema.size());
            }
            while (rs.next()) {
                Object[] row = new Object[n];
                for (int c = 0; c < n; c++) {
                    // A java.sql.Timestamp loses the era of a year before 1 AD, a LocalDateTime keeps it.
                    Object value = schema.type(c) == DataType.TIMESTAMP ? rs.getObject(c + 1, LocalDateTime.class)
                            : rs.getObject(c + 1);
                    row[c] = Normalizer.normalize(value, schema.type(c));
                }
                rows.add(row);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("DuckDB fails: " + sql, e);
        }
        return rows;
    }

    /** Runs a plan in DuckDB. */
    public List<Object[]> query(PlanNode plan) {
        return query(render(plan), plan.schema());
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
