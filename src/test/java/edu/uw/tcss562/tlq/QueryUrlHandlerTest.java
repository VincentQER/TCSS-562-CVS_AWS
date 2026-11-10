package edu.uw.tcss562.tlq;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QueryUrlHandlerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private Map<String, Object> event(String body, boolean encoded) {
        return new HashMap<>(Map.of("headers", Map.of("Content-Type", "application/json", "X-TLQ-Run-Id", "run-A"),
            "requestContext", Map.of("http", Map.of("method", "POST")), "body", body, "isBase64Encoded", encoded));
    }
    @Test void plainAndBase64RequestsReturnAggregateJson() throws Exception {
        for (boolean base64 : new boolean[] {false, true}) {
            String body = "{\"groupBy\":[\"state\"],\"metrics\":[\"count\"]}";
            var handler = new QueryUrlHandler(request -> { assertEquals("run-A", request.runId()); return List.of(Map.of("state", "WA", "count", 2L)); });
            var response = handler.handleRequest(event(base64 ? Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8)) : body, base64), null);
            assertEquals(200, response.get("statusCode"));
            var result = mapper.readTree((String) response.get("body"));
            assertEquals("run-A", result.get("runId").asText()); assertEquals(2, result.get("results").get(0).get("count").asInt());
        }
    }
    @Test void rejectsMissingRunAndMalformedQueryBeforeDatabase() {
        var handler = new QueryUrlHandler(request -> { fail("Must not query database"); return List.of(); });
        var noRun = event("{}", false); noRun.put("headers", Map.of("content-type", "application/json"));
        assertEquals(400, handler.handleRequest(noRun, null).get("statusCode"));
        assertEquals(400, handler.handleRequest(event("{\"groupBy\":[\"recordId\"]}", false), null).get("statusCode"));
        assertEquals(400, handler.handleRequest(event("%%%", true), null).get("statusCode"));
        assertEquals(400, handler.handleRequest(event("/w==", true), null).get("statusCode"));
        assertEquals(400, handler.handleRequest(null, null).get("statusCode"));
    }
    @Test void rejectsMethodMediaAndSize() {
        var handler = new QueryUrlHandler(request -> { fail("Must not query"); return List.of(); });
        var get = event("{}", false); get.put("requestContext", Map.of("http", Map.of("method", "GET")));
        assertEquals(405, handler.handleRequest(get, null).get("statusCode"));
        var bad = event("{}", false); bad.put("headers", Map.of("content-type", "text/csv"));
        assertEquals(415, handler.handleRequest(bad, null).get("statusCode"));
        assertEquals(413, handler.handleRequest(event("x".repeat(QueryUrlHandler.MAX_REQUEST_BYTES + 1), false), null).get("statusCode"));
    }
    @Test void invalidAmountsReturn422AndErrorsAreSanitized() throws Exception {
        var handler = new QueryUrlHandler(request -> { throw new JdbcQueryService.QueryFailure(JdbcQueryService.Error.INVALID_AMOUNT); });
        var response = handler.handleRequest(event("{}", false), null);
        assertEquals(422, response.get("statusCode"));
        assertEquals("INVALID_AMOUNT", mapper.readTree((String) response.get("body")).get("error").asText());
        assertFalse(mapper.readTree((String) response.get("body")).has("results"));
        var bad = new QueryUrlHandler(request -> { throw new IllegalStateException("password=hidden"); });
        assertFalse(bad.handleRequest(event("{}", false), null).toString().contains("hidden"));
    }
    @Test void oversizedResponseFailsInsteadOfTruncating() {
        var handler = new QueryUrlHandler(request -> List.of(Map.of("state", "a".repeat(QueryUrlHandler.MAX_RESPONSE_BYTES))));
        assertEquals(413, handler.handleRequest(event("{}", false), null).get("statusCode"));
    }
}
