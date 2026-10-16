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

package net.fyrql.test.tpch;

import io.trino.tpch.Customer;
import io.trino.tpch.CustomerGenerator;
import io.trino.tpch.LineItem;
import io.trino.tpch.LineItemGenerator;
import io.trino.tpch.Nation;
import io.trino.tpch.NationGenerator;
import io.trino.tpch.Order;
import io.trino.tpch.OrderGenerator;
import io.trino.tpch.Part;
import io.trino.tpch.PartGenerator;
import io.trino.tpch.PartSupplier;
import io.trino.tpch.PartSupplierGenerator;
import io.trino.tpch.Region;
import io.trino.tpch.RegionGenerator;
import io.trino.tpch.Supplier;
import io.trino.tpch.SupplierGenerator;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;

import java.math.BigDecimal;
import java.util.Iterator;
import java.util.Locale;
import java.util.function.Function;

/**
 * The eight tables of TPC-H, with the types of DuckDB's extension
 * {@code tpch} (keys {@code INTEGER}, amounts {@code DECIMAL(15,2)}, dates
 * {@code DATE}, texts {@code VARCHAR}), the kind of source each lives in,
 * and its rows from the data generator as the Java values of FyrQL (a date
 * as its days since 1970-01-01).
 */
enum TpchTable {

    REGION(Store.MEMORY, fields(
            "r_regionkey", DataType.INTEGER, "r_name", DataType.VARCHAR, "r_comment", DataType.VARCHAR)),
    NATION(Store.MEMORY, fields(
            "n_nationkey", DataType.INTEGER, "n_name", DataType.VARCHAR, "n_regionkey", DataType.INTEGER,
            "n_comment", DataType.VARCHAR)),
    PART(Store.JSON, fields(
            "p_partkey", DataType.INTEGER, "p_name", DataType.VARCHAR, "p_mfgr", DataType.VARCHAR,
            "p_brand", DataType.VARCHAR, "p_type", DataType.VARCHAR, "p_size", DataType.INTEGER,
            "p_container", DataType.VARCHAR, "p_retailprice", money(), "p_comment", DataType.VARCHAR)),
    SUPPLIER(Store.JSON, fields(
            "s_suppkey", DataType.INTEGER, "s_name", DataType.VARCHAR, "s_address", DataType.VARCHAR,
            "s_nationkey", DataType.INTEGER, "s_phone", DataType.VARCHAR, "s_acctbal", money(),
            "s_comment", DataType.VARCHAR)),
    PARTSUPP(Store.CSV, fields(
            "ps_partkey", DataType.INTEGER, "ps_suppkey", DataType.INTEGER, "ps_availqty", DataType.INTEGER,
            "ps_supplycost", money(), "ps_comment", DataType.VARCHAR)),
    CUSTOMER(Store.DATABASE, fields(
            "c_custkey", DataType.INTEGER, "c_name", DataType.VARCHAR, "c_address", DataType.VARCHAR,
            "c_nationkey", DataType.INTEGER, "c_phone", DataType.VARCHAR, "c_acctbal", money(),
            "c_mktsegment", DataType.VARCHAR, "c_comment", DataType.VARCHAR)),
    ORDERS(Store.DATABASE, fields(
            "o_orderkey", DataType.INTEGER, "o_custkey", DataType.INTEGER, "o_orderstatus", DataType.VARCHAR,
            "o_totalprice", money(), "o_orderdate", DataType.DATE, "o_orderpriority", DataType.VARCHAR,
            "o_clerk", DataType.VARCHAR, "o_shippriority", DataType.INTEGER, "o_comment", DataType.VARCHAR)),
    LINEITEM(Store.CSV, fields(
            "l_orderkey", DataType.INTEGER, "l_partkey", DataType.INTEGER, "l_suppkey", DataType.INTEGER,
            "l_linenumber", DataType.INTEGER, "l_quantity", money(), "l_extendedprice", money(),
            "l_discount", money(), "l_tax", money(), "l_returnflag", DataType.VARCHAR,
            "l_linestatus", DataType.VARCHAR, "l_shipdate", DataType.DATE, "l_commitdate", DataType.DATE,
            "l_receiptdate", DataType.DATE, "l_shipinstruct", DataType.VARCHAR, "l_shipmode", DataType.VARCHAR,
            "l_comment", DataType.VARCHAR));

    /** Where a table lives in the federated setup. */
    enum Store {
        /** Rows of the memory connector. */
        MEMORY,
        /** A file of JSON lines. */
        JSON,
        /** A CSV file with a header and {@link TpchData#DELIMITER} between the fields. */
        CSV,
        /** A table of a database, loaded from a CSV file as the CSV tables are written. */
        DATABASE
    }

    private final Store store;
    private final Schema schema;

    TpchTable(Store store, Schema schema) {
        this.store = store;
        this.schema = schema;
    }

    /** The name of the table in the queries. */
    String tableName() {
        return name().toLowerCase(Locale.ROOT);
    }

    Store store() {
        return store;
    }

    Schema schema() {
        return schema;
    }

    /** Returns the rows of the table at a scale factor, generated as they are read. */
    Iterable<Object[]> rows(double scaleFactor) {
        return switch (this) {
            case REGION -> map(new RegionGenerator(), (Region r) -> new Object[] {(int) r.regionKey(), r.name(),
                    r.comment()});
            case NATION -> map(new NationGenerator(), (Nation n) -> new Object[] {(int) n.nationKey(), n.name(),
                    (int) n.regionKey(), n.comment()});
            case PART -> map(new PartGenerator(scaleFactor, 1, 1), (Part p) -> new Object[] {(int) p.partKey(),
                    p.name(), p.manufacturer(), p.brand(), p.type(), p.size(), p.container(),
                    cents(p.retailPriceInCents()), p.comment()});
            case SUPPLIER -> map(new SupplierGenerator(scaleFactor, 1, 1), (Supplier s) -> new Object[] {
                    (int) s.supplierKey(), s.name(), s.address(), (int) s.nationKey(), s.phone(),
                    cents(s.accountBalanceInCents()), s.comment()});
            case PARTSUPP -> map(new PartSupplierGenerator(scaleFactor, 1, 1), (PartSupplier p) -> new Object[] {
                    (int) p.partKey(), (int) p.supplierKey(), p.availableQuantity(), cents(p.supplyCostInCents()),
                    p.comment()});
            case CUSTOMER -> map(new CustomerGenerator(scaleFactor, 1, 1), (Customer c) -> new Object[] {
                    (int) c.customerKey(), c.name(), c.address(), (int) c.nationKey(), c.phone(),
                    cents(c.accountBalanceInCents()), c.marketSegment(), c.comment()});
            case ORDERS -> map(new OrderGenerator(scaleFactor, 1, 1), (Order o) -> new Object[] {(int) o.orderKey(),
                    (int) o.customerKey(), String.valueOf(o.orderStatus()), cents(o.totalPriceInCents()),
                    o.orderDate(), o.orderPriority(), o.clerk(), o.shipPriority(), o.comment()});
            case LINEITEM -> map(new LineItemGenerator(scaleFactor, 1, 1), (LineItem l) -> new Object[] {
                    (int) l.orderKey(), (int) l.partKey(), (int) l.supplierKey(), l.lineNumber(),
                    cents(l.quantity() * 100), cents(l.extendedPriceInCents()), cents(l.discountPercent()),
                    cents(l.taxPercent()), l.returnFlag(), l.status(), l.shipDate(), l.commitDate(),
                    l.receiptDate(), l.shipInstructions(), l.shipMode(), l.comment()});
        };
    }

    /** The type of amounts, quantities and rates. */
    private static DataType money() {
        return DataType.decimal(15, 2);
    }

    /** An amount in hundredths as a decimal with two places. */
    private static BigDecimal cents(long hundredths) {
        return BigDecimal.valueOf(hundredths, 2);
    }

    private static <T> Iterable<Object[]> map(Iterable<T> generator, Function<T, Object[]> row) {
        return () -> {
            Iterator<T> it = generator.iterator();
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    return it.hasNext();
                }

                @Override
                public Object[] next() {
                    return row.apply(it.next());
                }
            };
        };
    }

    /** A schema of nullable fields from pairs of names and types. */
    private static Schema fields(Object... namesAndTypes) {
        Schema.Field[] fields = new Schema.Field[namesAndTypes.length / 2];
        for (int i = 0; i < fields.length; i++) {
            fields[i] = new Schema.Field((String) namesAndTypes[2 * i], (DataType) namesAndTypes[2 * i + 1]);
        }
        return Schema.of(fields);
    }
}
