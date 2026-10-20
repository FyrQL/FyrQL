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

package net.fyrql;

import net.fyrql.connector.BatchReader;
import net.fyrql.connector.Catalog;
import net.fyrql.connector.Connector;
import net.fyrql.connector.FileObject;
import net.fyrql.connector.FileOutput;
import net.fyrql.connector.FileStore;
import net.fyrql.connector.FileStores;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.connector.Split;
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.Schema;
import org.junit.jupiter.api.Test;

import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine gives the stores of files its sources offer to all sources,
 * once they are all created, and refuses two stores of the same scheme.
 */
class FileStoresTest {

    /** A store of no files under a scheme. */
    private record EmptyStore(String scheme) implements FileStore {
        @Override
        public List<FileObject> list(String location, String extension) {
            return List.of();
        }

        @Override
        public FileObject object(String location) throws NoSuchFileException {
            throw new NoSuchFileException(location);
        }

        @Override
        public FileOutput create(String location) {
            throw new UnsupportedOperationException();
        }
    }

    /** A source without tables that may offer a store and keeps the stores it is given. */
    private static final class StoreSource implements Connector {
        private final String name;
        private final FileStore store;
        private final List<FileStores> given = new ArrayList<>();

        StoreSource(String name, FileStore store) {
            this.name = name;
            this.store = store;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Catalog catalog() {
            return new Catalog() {
                @Override
                public List<String> schemas() {
                    return List.of(TableRef.MAIN);
                }

                @Override
                public List<String> tables(String schema) {
                    return List.of();
                }

                @Override
                public Schema schema(TableRef table) {
                    throw new IllegalArgumentException("No table " + table);
                }
            };
        }

        @Override
        public ScanPlan plan(TableRef table, ScanRequest request) {
            throw new IllegalArgumentException("No table " + table);
        }

        @Override
        public List<Split> splits(ScanPlan plan) {
            throw new IllegalArgumentException("No tables");
        }

        @Override
        public BatchReader open(Split split) {
            throw new IllegalArgumentException("No tables");
        }

        @Override
        public Optional<FileStore> fileStore() {
            return Optional.ofNullable(store);
        }

        @Override
        public void fileStores(FileStores stores) {
            given.add(stores);
        }
    }

    @Test
    void everySourceGetsTheStoresOfAllSources() {
        StoreSource mem = new StoreSource("lake", new EmptyStore("MEM"));
        StoreSource other = new StoreSource("other", null);
        try (FyrQL engine = FyrQL.builder().register(MemoryConnector.builder("m").build()).register(mem)
                .register(other).implicitSources(false).build()) {
            assertEquals(1, mem.given.size());
            assertSame(mem.given.get(0), other.given.get(0));
            FileStores stores = other.given.get(0);
            assertEquals(List.of("mem"), stores.schemes());
            assertSame(mem.store, stores.store("mem://bucket/key"));
            assertSame(stores.local(), stores.store("x.csv"));
            assertTrue(engine.sources().contains("lake"));
        }
    }

    @Test
    void twoStoresOfTheSameSchemeAreAnError() {
        StoreSource a = new StoreSource("a", new EmptyStore("mem"));
        StoreSource b = new StoreSource("b", new EmptyStore("Mem"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> FyrQL.builder().register(a).register(b).build());
        assertEquals("Two sources handle mem:// locations: a and b", e.getMessage());
        assertEquals(List.of(), a.given);
    }
}
