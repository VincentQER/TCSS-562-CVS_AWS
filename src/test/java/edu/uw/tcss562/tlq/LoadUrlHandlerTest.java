package edu.uw.tcss562.tlq;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LoadUrlHandlerTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private Map<String, Object> request(String body, boolean encoded) {
        return new HashMap<>(Map.of("requestContext", Map.of("http", Map.of("method", "POST")),
            "headers", Map.of("Content-Type", "application/json; charset=utf-8", "X-TLQ-Run-Id", "run-A"),
            "body", body, "isBase64Encoded", encoded));
    }

    private void error(Map<String, Object> response, int status, String code) throws Exception {
        assertEquals(status, response.get("statusCode"));
        var body = mapper.readTree((String) response.get("body"));
        assertEquals(code, body.get("error").asText());
        assertFalse(body.get("retryable").asBoolean());
        assertFalse(body.has("committedRecords"));
    }

    @Test void plainAndBase64RequestsPreserveRunIdAndDuplicates() throws Exception {
        for (boolean encoded : new boolean[] {false, true}) {
            var rows = List.of(JdbcLoadServiceTest.row(), JdbcLoadServiceTest.row());
            int[] calls = {0};
            var handler = new LoadUrlHandler((run, records) -> {
                calls[0]++; assertEquals("run-A", run); assertEquals(rows, records); return records.size();
            });
            String body = mapper.writeValueAsString(rows);
            var response = handler.handleRequest(request(encoded ? Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8)) : body, encoded), null);
            assertEquals(200, response.get("statusCode"));
            assertEquals(2, mapper.readTree((String) response.get("body")).get("committedRecords").asInt());
            assertEquals(1, calls[0]);
        }
    }

    @Test void rejectsBadJsonAndWrongFieldTypesBeforeLoad() throws Exception {
        var handler = new LoadUrlHandler((run, rows) -> { fail("Must not call database"); return 0; });
        var nullValue = new java.util.LinkedHashMap<>(JdbcLoadServiceTest.row()); nullValue.put("recordId", null);
        var number = new java.util.LinkedHashMap<String, Object>(JdbcLoadServiceTest.row()); number.put("programYear", 2018);
        for (String body : List.of("{}", "[", "[{}]", "[] []", mapper.writeValueAsString(List.of(nullValue)),
                mapper.writeValueAsString(List.of(number)), "[{\"x\":\"1\",\"x\":\"2\"}]")) {
            error(handler.handleRequest(request(body, false), null), 400, "INVALID_RECORDS");
        }
    }

    @Test void requiresRunIdAndPostAndJson() throws Exception {
        var handler = new LoadUrlHandler((run, rows) -> { fail("Must not load"); return 0; });
        var noRun = request("[]", false); noRun.put("headers", Map.of("content-type", "application/json"));
        error(handler.handleRequest(noRun, null), 400, "MISSING_RUN_ID");
        var get = request("[]", false); get.put("requestContext", Map.of("http", Map.of("method", "GET")));
        var response = handler.handleRequest(get, null);
        error(response, 405, "METHOD_NOT_ALLOWED");
        assertEquals("POST", ((Map<?, ?>) response.get("headers")).get("allow"));
        var wrongType = request("[]", false); wrongType.put("headers", Map.of("content-type", "text/csv"));
        error(handler.handleRequest(wrongType, null), 415, "UNSUPPORTED_MEDIA_TYPE");
        error(handler.handleRequest(null, null), 400, "INVALID_EVENT");
    }

    @Test void rejectsOversizeAndInvalidEncodingBeforeLoad() throws Exception {
        var handler = new LoadUrlHandler((run, rows) -> { fail("Must not load"); return 0; });
        error(handler.handleRequest(request("x".repeat(LoadUrlHandler.MAX_REQUEST_BYTES + 1), false), null), 413, "REQUEST_TOO_LARGE");
        error(handler.handleRequest(request("%%%", true), null), 400, "INVALID_BASE64");
        error(handler.handleRequest(request("/w==", true), null), 400, "INVALID_UTF8");
        var flag = request("[]", false); flag.put("isBase64Encoded", "true");
        error(handler.handleRequest(flag, null), 400, "INVALID_EVENT");
    }

    @Test void reportsKnownRollbackAndUnknownCommitWithoutRetry() throws Exception {
        for (var outcome : JdbcLoadService.Outcome.values()) {
            int[] calls = {0};
            var handler = new LoadUrlHandler((run, rows) -> { calls[0]++; throw new JdbcLoadService.LoadFailure(outcome); });
            error(handler.handleRequest(request("[]", false), null), 500,
                outcome == JdbcLoadService.Outcome.UNKNOWN ? "COMMIT_OUTCOME_UNKNOWN" : "LOAD_NOT_COMMITTED");
            assertEquals(1, calls[0]);
        }
    }

    @Test void configurationFailuresDoNotExposeSecrets() throws Exception {
        var handler = new LoadUrlHandler((run, rows) -> { throw new IllegalStateException("password=do-not-output"); });
        var response = handler.handleRequest(request("[]", false), null);
        error(response, 500, "LOAD_UNAVAILABLE");
        assertFalse(response.toString().contains("do-not-output"));
    }
}
