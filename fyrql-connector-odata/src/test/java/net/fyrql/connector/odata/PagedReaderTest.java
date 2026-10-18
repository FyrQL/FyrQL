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

package net.fyrql.connector.odata;

import net.fyrql.connector.BatchReader;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static net.fyrql.connector.odata.ODataConnectorTest.read;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pages of the answers of the fake server, also of services with quirks. */
class PagedReaderTest {

    private static final Schema NUMBERS = Schema.of(new Schema.Field("n", DataType.INTEGER, false));

    private final FakeODataServer server = FakeODataServer.start();

    @AfterEach
    void stop() {
        server.close();
    }

    /** Adds a service with the entity set {@code Numbers} of the numbers from 1 to 5. */
    private FakeODataServer.Service service(ODataVersion version) {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            rows.add(new Object[] {i});
        }
        return server.service(version == ODataVersion.V2 ? "v2" : "v4", version).entitySet("Numbers", NUMBERS,
                rows, "n");
    }

    private ODataConnector connector(int batchSize) {
        return ODataConnector.builder("erp").service("v2", server.url("v2")).service("v4", server.url("v4"))
                .batchSize(batchSize).build();
    }

    private static TableRef numbers(ODataVersion version) {
        return new TableRef("erp", version == ODataVersion.V2 ? "v2" : "v4", "Numbers");
    }

    private static List<List<Object>> all() {
        return List.of(List.of(1), List.of(2), List.of(3), List.of(4), List.of(5));
    }

    private static BatchReader open(ODataConnector c, TableRef table) {
        ScanPlan plan = c.plan(table, ScanRequest.columns(List.of(0)));
        return c.open(c.splits(plan).get(0));
    }

    @Test
    void pagesWithAbsoluteAndRelativeLinks() {
        service(ODataVersion.V2).pageSize(2).relativeLinks(true);
        service(ODataVersion.V4).pageSize(3);
        try (ODataConnector c = connector(10)) {
            assertEquals(all(), read(c, numbers(ODataVersion.V2), 0));
            assertEquals(all(), read(c, numbers(ODataVersion.V4), 0));
        }
        assertEquals(3, server.requests("/v2/Numbers").size());
        assertEquals(2, server.requests("/v4/Numbers").size());
    }

    @Test
    void theNextPageIsRequestedOnlyWhenItIsRead() {
        service(ODataVersion.V4).pageSize(2);
        try (ODataConnector c = connector(10); BatchReader reader = open(c, numbers(ODataVersion.V4))) {
            assertEquals(2, reader.next().rowCount());
            assertEquals(1, server.requests("/v4/Numbers").size());
            assertEquals(2, reader.next().rowCount());
            assertEquals(2, server.requests("/v4/Numbers").size());
        }
        assertEquals(2, server.requests("/v4/Numbers").size());
    }

    @Test
    void anAnswerCutShortIsAnError() {
        service(ODataVersion.V2).pageSize(2).cutShort(true);
        service(ODataVersion.V4).pageSize(2).cutShort(true);
        try (ODataConnector c = connector(10)) {
            for (ODataVersion v : ODataVersion.values()) {
                ODataSourceException e = assertThrows(ODataSourceException.class, () -> read(c, numbers(v), 0));
                assertEquals("The service " + numbers(v).schema() + " of the OData source erp answered the query of"
                        + " the entity set Numbers with 2 of 5 records and no link to the next page; the answer is"
                        + " incomplete", e.getMessage());
                assertEquals("Numbers", e.entitySet());
            }
        }
    }

    @Test
    void withoutTheNumberOfAllRecordsAnAnswerCutShortCannotBeTold() {
        service(ODataVersion.V4).pageSize(2).cutShort(true).noCount(true);
        try (ODataConnector c = connector(10)) {
            assertEquals(all().subList(0, 2), read(c, numbers(ODataVersion.V4), 0));
        }
    }

    @Test
    void atomIsAnError() {
        service(ODataVersion.V2).atom(true);
        try (ODataConnector c = connector(10)) {
            ODataSourceException e = assertThrows(ODataSourceException.class,
                    () -> read(c, numbers(ODataVersion.V2), 0));
            assertEquals("The service v2 of the OData source erp answered the query of the entity set Numbers at "
                    + server.url("v2") + "Numbers with application/atom+xml instead of JSON; the connector asks for"
                    + " JSON with the header Accept and $format=json, which the service ignores", e.getMessage());
        }
    }

    @Test
    void errorsOfTheServiceWithItsMessage() {
        service(ODataVersion.V2).failing(400);
        service(ODataVersion.V4).failing(500);
        try (ODataConnector c = connector(10)) {
            ODataSourceException e = assertThrows(ODataSourceException.class,
                    () -> read(c, numbers(ODataVersion.V2), 0));
            assertEquals("The service v2 of the OData source erp answered the query of the entity set Numbers at "
                    + server.url("v2") + "Numbers with the status 400: The query of Numbers failed (Failed)",
                    e.getMessage());
            assertEquals(400, e.status());
            assertEquals("The query of Numbers failed (Failed)", e.serviceMessage());
            e = assertThrows(ODataSourceException.class, () -> read(c, numbers(ODataVersion.V4), 0));
            assertEquals(500, e.status());
            assertTrue(e.getMessage().endsWith("with the status 500: The query of Numbers failed (Failed)"),
                    e.getMessage());
        }
    }

    @Test
    void linksToOtherHostsAndToTheSamePageAreErrors() {
        service(ODataVersion.V4).pageSize(2).foreignLinks(true);
        service(ODataVersion.V2).pageSize(2).sameLink(true);
        try (ODataConnector c = connector(10)) {
            ODataSourceException e = assertThrows(ODataSourceException.class,
                    () -> read(c, numbers(ODataVersion.V4), 0));
            assertEquals("The service v4 of the OData source erp links to the next page of the query of the entity"
                    + " set Numbers at another host, http://other.example/v4/Numbers; the connector follows links to"
                    + " next pages only on the host of the service", e.getMessage());
            e = assertThrows(ODataSourceException.class, () -> read(c, numbers(ODataVersion.V2), 0));
            assertEquals("The service v2 of the OData source erp links to the same page of the query of the entity"
                    + " set Numbers again, " + server.url("v2") + "Numbers", e.getMessage());
            assertEquals(2, server.requests("/v2/Numbers").size());
        }
    }

    @Test
    void cancelingAReaderThatWaitsOnAPageEndsIt() throws Exception {
        service(ODataVersion.V4).stallAfter(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ODataConnector c = connector(1); BatchReader reader = open(c, numbers(ODataVersion.V4))) {
            assertEquals(1, reader.next().rowCount());
            Future<?> cancel = executor.submit(() -> {
                server.awaitStall();
                reader.cancel();
                return null;
            });
            assertThrows(RuntimeException.class, () -> {
                while (reader.next() != null) {
                    // The reader must fail before the end.
                }
            });
            cancel.get();
            assertThrows(CancellationException.class, reader::next);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aClosedReaderReadsNoMore() {
        service(ODataVersion.V4).pageSize(2);
        try (ODataConnector c = connector(10)) {
            BatchReader reader = open(c, numbers(ODataVersion.V4));
            reader.close();
            assertNull(reader.next());
            reader.cancel();
            reader.close();
        }
        assertEquals(0, server.requests("/v4/Numbers").size());
    }
}
