package edu.uw.tcss562.tlq;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real loopback HTTP + real Transform handler. These tests never connect to MySQL. */
class LocalHandlerHttpAdapterTest {
    @Test void realTransformHandlerReceivesCsvAndReturnsUnwrappedJsonArray() throws Exception {
        String csv = String.join(",", PaymentSchema.HEADERS) + "\n" + "NEW," + ",".repeat(73) + "\n";
        try (var server = new LocalHandlerHttpAdapter(Map.of("/transform", new TransformUrlHandler()), run -> {
            assertEquals("unit-local-run", run);
        })) {
            var request = HttpRequest.newBuilder(URI.create(server.baseUrl() + "/transform"))
                .header("Content-Type", "text/csv").header("X-TLQ-Run-Id", "unit-local-run")
                .POST(HttpRequest.BodyPublishers.ofString(csv)).build();
            var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            var rows = new ObjectMapper().readTree(response.body());
            assertTrue(rows.isArray()); assertEquals(1, rows.size()); assertEquals(75, rows.get(0).size());
            assertEquals("NEW", rows.get(0).get("changeType").asText());
            assertEquals(200, server.observations().getFirst().get("httpStatus"));
            assertTrue(((Number) server.observations().getFirst().get("handlerElapsedMs")).doubleValue() >= 0);
        }
    }

    @Test void realHandlerErrorsAndRunGuardStaySanitized() throws Exception {
        try (var server = new LocalHandlerHttpAdapter(Map.of("/transform", new TransformUrlHandler()), run -> {})) {
            var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(server.baseUrl() + "/transform")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(405, response.statusCode()); assertEquals("POST", response.headers().firstValue("allow").orElseThrow());
        }
        try (var server = new LocalHandlerHttpAdapter(Map.of("/transform", new TransformUrlHandler()), run -> {
            throw new java.sql.SQLException("password=must-not-log", "28000", 1045);
        })) {
            var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(server.baseUrl() + "/transform")).POST(HttpRequest.BodyPublishers.ofString("csv")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(500, response.statusCode()); assertFalse(response.body().contains("must-not-log"));
            assertFalse(server.observations().toString().contains("must-not-log"));
            assertEquals(1045, server.observations().getFirst().get("databaseErrorCode"));
        }
    }
}
