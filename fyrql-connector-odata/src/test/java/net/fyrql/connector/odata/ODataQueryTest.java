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

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The URLs of the requests to a service, and the errors in their answers. */
class ODataQueryTest {

    private static final URI ROOT = URI.create("https://host/sap/opu/odata/sap/ZSALES_SRV");

    private static Map<String, String> options(String... pairs) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            options.put(pairs[i], pairs[i + 1]);
        }
        return options;
    }

    @Test
    void urlsOfResourcesOfAService() {
        assertEquals("https://host/sap/opu/odata/sap/ZSALES_SRV/$metadata",
                ODataQuery.of(ROOT, "$metadata", Map.of()).toString());
        assertEquals("https://host/sap/opu/odata/sap/ZSALES_SRV/Orders?$select=Id,Address/City&$format=json",
                ODataQuery.of(URI.create(ROOT + "/"), "Orders", options("$select", "Id,Address/City",
                        "$format", "json")).toString());
        // The query options of the root go first with every request.
        URI client = URI.create("https://host:8443/svc/?sap-client=100&sap-language=DE");
        assertEquals("https://host:8443/svc/$metadata?sap-client=100&sap-language=DE",
                ODataQuery.of(client, "$metadata", Map.of()).toString());
        assertEquals("https://host:8443/svc/Orders?sap-client=100&sap-language=DE&$count=true",
                ODataQuery.of(client, "Orders", options("$count", "true")).toString());
    }

    @Test
    void encoding() {
        assertEquals("http://h/s/Set?$filter=Name%20eq%20'O''Neil%20%26%20Sons%2B1'%20%23x%20%C3%A4%3Db%3Bc%25",
                ODataQuery.of(URI.create("http://h/s"), "Set", options("$filter",
                        "Name eq 'O''Neil & Sons+1' #x \u00E4=b;c%")).toString());
        assertEquals("Set%20With%20Space", ODataQuery.segment("Set With Space"));
        assertEquals("A%2FB%3F%23", ODataQuery.segment("A/B?#"));
        assertEquals("Orders('1')", ODataQuery.segment("Orders('1')"));
    }

    @Test
    void systemQueryOptionsOfAQuery() {
        assertEquals(options("$select", "a,b", "$inlinecount", "allpages", "$format", "json"),
                ODataQuery.options(ODataVersion.V2, List.of("a", "b"), null, null, null, true));
        assertEquals(List.of("$select", "$filter", "$orderby", "$top", "$inlinecount", "$format"),
                List.copyOf(ODataQuery.options(ODataVersion.V2, List.of("a", "b"), "a eq 1", "b desc", 10, true)
                        .keySet()));
        assertEquals(options("$format", "json"), ODataQuery.options(ODataVersion.V2, List.of(), null, null, null,
                false));
        assertEquals(options("$select", "a/b", "$count", "true"),
                ODataQuery.options(ODataVersion.V4, List.of("a/b"), null, null, null, true));
        assertEquals(options("$filter", "a lt 5", "$orderby", "a asc,b desc", "$top", "0"),
                ODataQuery.options(ODataVersion.V4, List.of(), "a lt 5", "a asc,b desc", 0, false));
        assertEquals(Map.of(), ODataQuery.options(ODataVersion.V4, List.of(), null, null, null, false));
        assertEquals("http://h/s/Set?$filter=a%20eq%20'x'&$orderby=a%20asc,b%20desc&$top=5",
                ODataQuery.of(URI.create("http://h/s"), "Set", ODataQuery.options(ODataVersion.V4, List.of(),
                        "a eq 'x'", "a asc,b desc", 5, false)).toString());
    }

    @Test
    void linksToNextPages() {
        URI root = URI.create("http://h/svc/?sap-client=100");
        assertEquals("http://h/svc/People?%24skiptoken=8", ODataQuery.next(root, "People?%24skiptoken=8")
                .toString());
        assertEquals("https://other/x?$skiptoken='a'", ODataQuery.next(root, " https://other/x?$skiptoken='a' ")
                .toString());
        assertEquals("http://h/People", ODataQuery.next(root, "/People").toString());
        assertThrows(IllegalArgumentException.class, () -> ODataQuery.next(root, "People?$skiptoken=a b"));
    }

    @Test
    void originsAndShownUrls() {
        assertTrue(ODataQuery.sameOrigin(URI.create("http://h/x"), URI.create("HTTP://H:80/y")));
        assertTrue(ODataQuery.sameOrigin(URI.create("https://h/x"), URI.create("https://h:443/y")));
        assertFalse(ODataQuery.sameOrigin(URI.create("http://h/x"), URI.create("https://h/x")));
        assertFalse(ODataQuery.sameOrigin(URI.create("http://h/x"), URI.create("http://g/x")));
        assertFalse(ODataQuery.sameOrigin(URI.create("http://h:8080/x"), URI.create("http://h/x")));
        assertFalse(ODataQuery.sameOrigin(URI.create("x/y"), URI.create("http://h/x")));
        assertEquals("https://h:8443/svc/Set", ODataQuery.shown(URI.create(
                "https://user:secret@h:8443/svc/Set?apikey=1#f")));
        assertEquals("http://h", ODataQuery.shown(URI.create("http://h")));
        assertEquals("Set", ODataQuery.shown(URI.create("Set?apikey=1")));
    }

    private static ODataError error(String json) {
        return ODataError.parse(json.replace('\'', '"').getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void errorsOfBothVersions() {
        ODataError v4 = error("{'error': {'code': 'BadRequest', 'message': 'No property X', 'target': 'X',"
                + " 'details': [{'code': 'a', 'message': 'b'}]}}");
        assertEquals(new ODataError("BadRequest", "No property X"), v4);
        assertEquals("No property X (BadRequest)", v4.toString());
        ODataError v2 = error("{'odata.metadata': 1, 'error': {'code': '/IWBEP/CM_MGW_RT/020', 'message': {'lang':"
                + " 'en', 'value': ' Resource not found '}, 'innererror': {'errordetails': []}}}");
        assertEquals("Resource not found (/IWBEP/CM_MGW_RT/020)", v2.toString());
        assertEquals("Value cannot be null. Parameter name: source",
                error("{'error': {'code': '', 'message': 'Value cannot be null.\\r\\nParameter name: source'}}")
                        .message());
        assertEquals("only a message", error("{'error': {'message': 'only a message'}}").toString());
        assertEquals("E1", error("{'error': {'code': 'E1', 'message': null}}").toString());
        assertNull(error("{'error': {}}"));
        assertNull(error("{'error': 'text'}"));
        assertNull(error("{'value': []}"));
        assertNull(error("[1]"));
        assertNull(error("<error/>"));
        assertNull(error("{'error': {'code': 'E1', 'message': "));
    }
}
