package edu.uw.tcss562.tlq;

import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/** Test-only loopback HTTP -> real Lambda event adapter. No simulated business responses. */
final class LocalHandlerHttpAdapter implements AutoCloseable {
    @FunctionalInterface interface RunGate { void verify(String runId) throws Exception; }
    private static final int MAX_BODY_BYTES = 1024 * 1024;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpServer server;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Map<String, RequestHandler<Map<String, Object>, Map<String, Object>>> handlers;
    private final RunGate runGate;
    private final List<Map<String, Object>> observations = new ArrayList<>();

    LocalHandlerHttpAdapter(Map<String, RequestHandler<Map<String, Object>, Map<String, Object>>> handlers,
                            RunGate runGate) throws java.io.IOException {
        this.handlers = Map.copyOf(handlers); this.runGate = runGate;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    synchronized List<Map<String, Object>> observations() { return List.copyOf(observations); }

    private void handle(HttpExchange exchange) throws java.io.IOException {
        long started = System.nanoTime();
        Map<String, Object> metric = new LinkedHashMap<>();
        String path = exchange.getRequestURI().getPath();
        metric.put("path", path); metric.put("startedAt", Instant.now().toString());
        int status = 500;
        byte[] responseBody = "{\"error\":\"LOCAL_ADAPTER_FAILED\"}".getBytes(StandardCharsets.UTF_8);
        Map<String, Object> responseHeaders = Map.of("content-type", "application/json; charset=utf-8");
        try {
            var handler = handlers.get(path);
            if (handler == null || exchange.getRequestURI().getRawQuery() != null) {
                status = 404;
            } else {
                String runId = exchange.getRequestHeaders().getFirst("X-TLQ-Run-Id");
                runGate.verify(runId);
                metric.put("runId", runId);
                byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
                metric.put("requestBytes", body.length);
                if (body.length > MAX_BODY_BYTES) {
                    status = 413;
                    responseBody = "{\"error\":\"LOCAL_REQUEST_TOO_LARGE\"}".getBytes(StandardCharsets.UTF_8);
                } else {
                    Map<String, String> headers = new LinkedHashMap<>();
                    exchange.getRequestHeaders().forEach((key, values) -> headers.put(key.toLowerCase(java.util.Locale.ROOT), String.join(",", values)));
                    Map<String, Object> event = Map.of("version", "2.0", "headers", headers,
                        "requestContext", Map.of("http", Map.of("method", exchange.getRequestMethod(), "path", path)),
                        "rawPath", path, "body", Base64.getEncoder().encodeToString(body), "isBase64Encoded", true);
                    long handlerStarted = System.nanoTime();
                    var response = handler.handleRequest(event, null);
                    metric.put("handlerElapsedMs", (System.nanoTime() - handlerStarted) / 1_000_000.0);
                    status = ((Number) response.get("statusCode")).intValue();
                    String responseText = (String) response.get("body");
                    responseBody = Boolean.TRUE.equals(response.get("isBase64Encoded"))
                        ? Base64.getDecoder().decode(responseText) : responseText.getBytes(StandardCharsets.UTF_8);
                    if (response.get("headers") instanceof Map<?, ?> values) {
                        Map<String, Object> safe = new LinkedHashMap<>();
                        for (String name : List.of("content-type", "allow")) if (values.containsKey(name)) safe.put(name, values.get(name));
                        responseHeaders = safe;
                    }
                }
            }
        } catch (Exception failure) {
            status = 500;
            // Numeric error metadata only. Never serialize messages, request headers or credentials.
            metric.put("error", "LOCAL_ADAPTER_OR_RUN_GUARD_FAILED");
            if (failure instanceof java.sql.SQLException sql) {
                metric.put("databaseErrorCode", sql.getErrorCode());
                metric.put("sqlState", QueryIntegrationDiagnostics.safeState(sql.getSQLState()));
            }
        }
        metric.put("httpStatus", status); metric.put("responseBytes", responseBody.length);
        metric.put("adapterElapsedMs", (System.nanoTime() - started) / 1_000_000.0);
        synchronized (this) { observations.add(metric); }
        try {
            for (var header : responseHeaders.entrySet()) exchange.getResponseHeaders().set(header.getKey(), header.getValue().toString());
            exchange.sendResponseHeaders(status, responseBody.length);
            exchange.getResponseBody().write(responseBody);
        } finally { exchange.close(); }
    }

    @Override public void close() {
        server.stop(0); executor.shutdownNow();
    }
}
