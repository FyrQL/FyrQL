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

package net.fyrql.connector.memory;

import net.fyrql.connector.BatchReader;
import net.fyrql.connector.Capabilities;
import net.fyrql.connector.Catalog;
import net.fyrql.connector.ColumnPath;
import net.fyrql.connector.Connector;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.connector.Split;
import net.fyrql.core.expr.Evaluator;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.expr.Expressions;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.Vector;
import net.fyrql.core.vector.Vectors;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.function.Function;

/**
 * Java collections as tables: a collection of records or beans, whose
 * properties become the columns, or rows of FyrQL values with an explicit
 * schema. All tables live in the schema {@code main}. The connector reads
 * the collection when a scan runs, so later changes to the collection are
 * visible to later queries.
 * <p>
 * By default the connector takes over nothing of a scan. For tests of the
 * engine it can take over filters and a limit and read fields inside
 * struct columns, see
 * {@link Builder#pushdown}; it evaluates the filters with the evaluator of
 * the engine, so the results are the same either way.
 */
public final class MemoryConnector implements Connector {

    private final String name;
    private final Map<String, Table> tables;
    private final int batchSize;
    private final Capabilities capabilities;
    private final Catalog catalog;

    private record Table(Schema schema, Collection<?> rows, Function<Object, Object[]> toRow) {
    }

    private MemoryConnector(String name, Map<String, Table> tables, int batchSize, Capabilities capabilities) {
        this.name = name;
        this.tables = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(tables));
        this.batchSize = batchSize;
        this.capabilities = capabilities;
        this.catalog = new Catalog() {
            @Override
            public List<String> schemas() {
                return List.of(TableRef.MAIN);
            }

            @Override
            public List<String> tables(String schema) {
                return schema.equals(TableRef.MAIN) ? List.copyOf(MemoryConnector.this.tables.keySet()) : List.of();
            }

            @Override
            public Schema schema(TableRef table) {
                return table(table).schema();
            }

            @Override
            public OptionalLong rowCount(TableRef table) {
                return OptionalLong.of(table(table).rows().size());
            }
        };
    }

    /** Starts a connector with the name under which it will be registered. */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Catalog catalog() {
        return catalog;
    }

    private Table table(TableRef ref) {
        Table t = ref.schema().equals(TableRef.MAIN) ? tables.get(ref.table()) : null;
        if (t == null) {
            throw new IllegalArgumentException("No table " + ref + " in the memory source " + name);
        }
        return t;
    }

    @Override
    public ScanPlan plan(TableRef table, ScanRequest request) {
        return capabilities.plan(table, table(table).schema(), request, request);
    }

    @Override
    public List<Split> splits(ScanPlan plan) {
        return List.of(new Split(plan, 0, null));
    }

    @Override
    public BatchReader open(Split split) {
        ScanPlan plan = split.plan();
        Table table = table(plan.table());
        List<ColumnPath> columns = plan.columns();
        Schema output = plan.output();
        Expression condition = plan.acceptedFilters().isEmpty() ? null
                : Expressions.conjunction(plan.acceptedFilters());
        long limit = plan.limitAccepted() ? ((ScanRequest) plan.state()).limit().getAsLong() : Long.MAX_VALUE;
        Iterator<?> rows = table.rows().iterator();
        return new BatchReader() {
            private long delivered;

            @Override
            public Batch next() {
                while (delivered < limit && rows.hasNext()) {
                    Batch b = read();
                    if (condition != null) {
                        b = b.select(Evaluator.select(condition, b));
                    }
                    if (b.rowCount() > limit - delivered) {
                        int[] first = new int[(int) (limit - delivered)];
                        for (int i = 0; i < first.length; i++) {
                            first[i] = i;
                        }
                        b = b.select(first);
                    }
                    if (b.rowCount() > 0) {
                        delivered += b.rowCount();
                        return b;
                    }
                }
                return null;
            }

            private Batch read() {
                List<Vector.Builder> builders = new ArrayList<>();
                for (int c = 0; c < output.size(); c++) {
                    builders.add(Vectors.builder(output.type(c), batchSize));
                }
                int n = 0;
                while (n < batchSize && rows.hasNext()) {
                    Object[] row = table.toRow().apply(rows.next());
                    for (int c = 0; c < columns.size(); c++) {
                        builders.get(c).append(value(row, columns.get(c), table.schema()));
                    }
                    n++;
                }
                List<Vector> vectors = new ArrayList<>();
                for (Vector.Builder b : builders) {
                    vectors.add(b.build());
                }
                return new Batch(output, n, vectors);
            }
        };
    }

    /** The value of a column of a row, or of a field inside it. */
    private static Object value(Object[] row, ColumnPath path, Schema schema) {
        Object v = row[path.column()];
        DataType type = schema.type(path.column());
        for (String field : path.fields()) {
            if (v == null) {
                return null;
            }
            DataType.Struct s = (DataType.Struct) type;
            int i = s.indexOf(field);
            v = ((List<?>) v).get(i);
            type = s.fields().get(i).type();
        }
        return v;
    }

    /** Collects the tables of a memory connector. */
    public static final class Builder {
        private final String name;
        private final Map<String, Table> tables = new LinkedHashMap<>();
        private int batchSize = 2048;
        private Capabilities capabilities = Capabilities.NONE;

        private Builder(String name) {
            this.name = Objects.requireNonNull(name, "name");
        }

        /** Sets the number of rows per batch. */
        public Builder batchSize(int batchSize) {
            if (batchSize < 1) {
                throw new IllegalArgumentException("batchSize: " + batchSize);
            }
            this.batchSize = batchSize;
            return this;
        }

        /**
         * Lets the connector take over the filters the capabilities accept
         * and, if they allow one, a limit, and read only the fields of struct
         * columns a query uses if they allow that; an order or an aggregation
         * it never takes over. Meant for tests of the engine's pushdown.
         */
        public Builder pushdown(Capabilities capabilities) {
            this.capabilities = new Capabilities(capabilities.filter(), capabilities.limit(), o -> false, a -> false,
                    capabilities.fields());
            return this;
        }

        /** Adds a collection of records or beans as a table; the columns are their properties. */
        public <T> Builder table(String table, Class<T> type, Collection<? extends T> rows) {
            return table(table, type, rows, Map.of());
        }

        /**
         * Adds a collection of records or beans as a table, with column
         * types that replace the derived ones, e.g. a {@code DECIMAL(10,2)}
         * for a {@code BigDecimal} property.
         */
        public <T> Builder table(String table, Class<T> type, Collection<? extends T> rows,
                Map<String, DataType> types) {
            List<JavaTypes.Property> properties = JavaTypes.properties(type, types);
            Schema schema = JavaTypes.schema(type, types);
            Function<Object, Object[]> toRow = o -> {
                Object[] row = new Object[properties.size()];
                for (int i = 0; i < row.length; i++) {
                    row[i] = properties.get(i).read(o);
                }
                return row;
            };
            tables.put(table, new Table(schema, rows, toRow));
            return this;
        }

        /** Adds rows of FyrQL values with an explicit schema as a table. */
        public Builder table(String table, Schema schema, Collection<Object[]> rows) {
            tables.put(table, new Table(schema, rows, o -> (Object[]) o));
            return this;
        }

        public MemoryConnector build() {
            return new MemoryConnector(name, tables, batchSize, capabilities);
        }
    }
}
