package edu.uw.tcss562.tlq;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Function URL adapter. HTTP names are provisional pending teammate confirmation. */
public final class LoadUrlHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {
    public static final int MAX_REQUEST_BYTES = 1024 * 1024;
    @FunctionalInterface interface Loader {
        int load(String runId, List<Map<String, String>> records) throws JdbcLoadService.LoadFailure;
    }
    private final ObjectMapper mapper = new ObjectMapper();
    private final Loader loader;

    public LoadUrlHandler() {
        this((runId, rows) -> {
            var factory = MysqlConnectionFactory.fromEnvironment(System.getenv());
            return new JdbcLoadService(factory, factory.table()).load(runId, rows);
        });
    }

    LoadUrlHandler(Loader loader) { this.loader = loader; }

    @Override public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        try {
            var request = mapper.valueToTree(event);
            if (request == null || !request.isObject()) return error(400, "INVALID_EVENT", "Invalid HTTP event");
            var method = request.path("requestContext").path("http").path("method");
            if (!method.isTextual()) return error(400, "INVALID_EVENT", "Missing HTTP method");
            if (!"POST".equals(method.textValue())) {
                var response = error(405, "METHOD_NOT_ALLOWED", "Use POST");
                response.put("headers", Map.of("content-type", "application/json; charset=utf-8", "allow", "POST"));
                return response;
            }
            String type = "";
            String runId = null;
            var headers = request.path("headers").fields();
            while (headers.hasNext()) {
                var header = headers.next();
                if ("content-type".equalsIgnoreCase(header.getKey())) type = header.getValue().asText("");
                if ("x-tlq-run-id".equalsIgnoreCase(header.getKey()) && header.getValue().isTextual()) runId = header.getValue().textValue();
            }
            String[] media = type.split(";", -1);
            if (!"application/json".equalsIgnoreCase(media[0].trim())) return error(415, "UNSUPPORTED_MEDIA_TYPE", "Use application/json with UTF-8 encoding");
            for (int i = 1; i < media.length; i++) {
                String[] parameter = media[i].trim().split("=", 2);
                if ("charset".equalsIgnoreCase(parameter[0]) && (parameter.length != 2
                        || !"utf-8".equalsIgnoreCase(parameter[1].trim().replace("\"", "")))) {
                    return error(415, "UNSUPPORTED_CHARSET", "JSON must use UTF-8 encoding");
                }
            }
            if (runId == null || runId.isBlank()) return error(400, "MISSING_RUN_ID", "X-TLQ-Run-Id is required");
            var body = request.path("body");
            var encoded = request.path("isBase64Encoded");
            if (!body.isTextual() || (!encoded.isMissingNode() && !encoded.isBoolean())) return error(400, "INVALID_EVENT", "Invalid body or encoding flag");
            boolean base64 = encoded.asBoolean(false);
            String text = body.textValue();
            long maxCharacters = base64 ? 4L * ((MAX_REQUEST_BYTES + 2L) / 3L) : MAX_REQUEST_BYTES;
            if (text.length() > maxCharacters) return error(413, "REQUEST_TOO_LARGE", "Send a smaller JSON batch");
            byte[] bytes;
            try { bytes = base64 ? Base64.getDecoder().decode(text) : text.getBytes(StandardCharsets.UTF_8); }
            catch (IllegalArgumentException exception) { return error(400, "INVALID_BASE64", "Invalid Base64 body"); }
            if (bytes.length > MAX_REQUEST_BYTES) return error(413, "REQUEST_TOO_LARGE", "Send a smaller JSON batch");
            String json;
            try {
                json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException exception) { return error(400, "INVALID_UTF8", "Invalid UTF-8 body"); }
            List<Map<String, String>> rows;
            try { rows = LoadPayload.parse(json); }
            catch (IllegalArgumentException exception) { return error(400, "INVALID_RECORDS", "Expected an array of exactly 75 string fields per record"); }
            int committed = loader.load(runId, rows);
            return response(200, Map.of("committedRecords", committed));
        } catch (JdbcLoadService.LoadFailure failure) {
            return error(500, failure.outcome() == JdbcLoadService.Outcome.UNKNOWN ? "COMMIT_OUTCOME_UNKNOWN" : "LOAD_NOT_COMMITTED",
                failure.getMessage());
        } catch (Exception exception) {
            return error(500, "LOAD_UNAVAILABLE", "Load could not complete; stop this experiment and do not retry automatically");
        }
    }

    private Map<String, Object> error(int status, String code, String message) {
        return response(status, Map.of("error", code, "message", message, "retryable", false));
    }

    private Map<String, Object> response(int status, Object body) {
        try {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("statusCode", status);
            response.put("headers", Map.of("content-type", "application/json; charset=utf-8"));
            response.put("isBase64Encoded", false);
            response.put("body", mapper.writeValueAsString(body));
            return response;
        } catch (java.io.IOException exception) { throw new IllegalStateException("Response serialization failed"); }
    }
}
