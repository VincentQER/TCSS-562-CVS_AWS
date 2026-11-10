package edu.uw.tcss562.tlq;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QueryRequestTest {
    @Test void parsesWhitelistedFiltersGroupsAndMetrics() {
        var request = QueryRequest.parse("run", "{\"filters\":{\"state\":\"WA\",\"year\":\"2018\"},\"groupBy\":[\"state\",\"year\"],\"metrics\":[\"count\",\"sum\",\"avg\"]}");
        assertEquals("run", request.runId()); assertEquals("WA", request.state()); assertEquals("2018", request.year());
        assertEquals(2, request.groupBy().size()); assertTrue(request.needsAmounts());
        assertFalse(QueryRequest.parse("run", "{\"metrics\":[\"count\"]}").needsAmounts());
    }

    @Test void rejectsUnknownFieldsInjectionAndMalformedTypes() {
        for (String json : new String[] {"[]", "{} {}", "{\"sql\":\"DROP TABLE t\"}", "{\"filters\":{\"recordId\":\"1\"}}",
                "{\"filters\":{\"year\":2018}}", "{\"groupBy\":[\"state; DROP TABLE t\"]}", "{\"groupBy\":[\"state\",\"state\"]}",
                "{\"metrics\":[]}", "{\"metrics\":[\"count\",\"count\"]}", "{\"metrics\":[\"COUNT(*)\"]}",
                "{\"filters\":null}", "{\"groupBy\":\"state\"}", "{\"metrics\":[null]}", "{\"metrics\":[\"sum\"],\"metrics\":[\"avg\"]}"}) {
            assertThrows(IllegalArgumentException.class, () -> QueryRequest.parse("run", json));
        }
        assertThrows(IllegalArgumentException.class, () -> QueryRequest.parse(" ", "{}"));
    }
}
