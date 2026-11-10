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

public final class QueryUrlHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {
    public static final int MAX_REQUEST_BYTES = 65_536;
    public static final int MAX_RESPONSE_BYTES = 5 * 1024 * 1024;
    @FunctionalInterface interface QueryExecutor {
        List<Map<String, Object>> query(QueryRequest request) throws JdbcQueryService.QueryFailure;
    }
    private final QueryExecutor executor;
    private final ObjectMapper mapper = new ObjectMapper();

    public QueryUrlHandler() {
        this(request -> {
            var factory = MysqlConnectionFactory.fromEnvironment(System.getenv());
            return new JdbcQueryService(factory, factory.table()).query(request);
        });
    }
    QueryUrlHandler(QueryExecutor executor) { this.executor = executor; }

    @Override public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        try {
            var request = mapper.valueToTree(event);
            if (request == null || !request.isObject()) return error(400, "INVALID_EVENT");
            var method = request.path("requestContext").path("http").path("method");
            if (!method.isTextual()) return error(400, "INVALID_EVENT");
            if (!"POST".equals(method.textValue())) {
                var response = error(405, "METHOD_NOT_ALLOWED");
                response.put("headers", Map.of("content-type", "application/json; charset=utf-8", "allow", "POST"));
                return response;
            }
            String type = "", run = null;
            var headers = request.path("headers").fields();
            while (headers.hasNext()) {
                var header = headers.next();
                if ("content-type".equalsIgnoreCase(header.getKey())) type = header.getValue().asText("");
                if ("x-tlq-run-id".equalsIgnoreCase(header.getKey()) && header.getValue().isTextual()) run = header.getValue().textValue();
            }
            String[] media = type.split(";", -1);
            if (!"application/json".equalsIgnoreCase(media[0].trim())) return error(415, "UNSUPPORTED_MEDIA_TYPE");
            for (int i = 1; i < media.length; i++) {
                String[] parameter = media[i].trim().split("=", 2);
                if ("charset".equalsIgnoreCase(parameter[0]) && (parameter.length != 2
                        || !"utf-8".equalsIgnoreCase(parameter[1].trim().replace("\"", "")))) return error(415, "UNSUPPORTED_CHARSET");
            }
            if (run == null || run.isBlank()) return error(400, "MISSING_RUN_ID");
            var body = request.path("body"); var encoded = request.path("isBase64Encoded");
            if (!body.isTextual() || (!encoded.isMissingNode() && !encoded.isBoolean())) return error(400, "INVALID_EVENT");
            boolean base64 = encoded.asBoolean(false);
            if (body.textValue().length() > (base64 ? 4L * ((MAX_REQUEST_BYTES + 2L) / 3) : MAX_REQUEST_BYTES)) return error(413, "REQUEST_TOO_LARGE");
            byte[] bytes;
            try { bytes = base64 ? Base64.getDecoder().decode(body.textValue()) : body.textValue().getBytes(StandardCharsets.UTF_8); }
            catch (IllegalArgumentException exception) { return error(400, "INVALID_BASE64"); }
            if (bytes.length > MAX_REQUEST_BYTES) return error(413, "REQUEST_TOO_LARGE");
            String json;
            try {
                json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException exception) { return error(400, "INVALID_UTF8"); }
            QueryRequest query;
            try { query = QueryRequest.parse(run, json); }
            catch (IllegalArgumentException exception) { return error(400, "INVALID_QUERY"); }
            var results = executor.query(query);
            var response = response(200, Map.of("runId", run, "rulesVersion", QueryRules.VERSION, "results", results));
            if (mapper.writeValueAsBytes(response).length > MAX_RESPONSE_BYTES) return error(413, "RESPONSE_TOO_LARGE");
            return response;
        } catch (JdbcQueryService.QueryFailure failure) {
            return switch (failure.error()) {
                case INVALID_AMOUNT -> error(422, "INVALID_AMOUNT");
                case RESULT_TOO_LARGE -> error(413, "RESULT_TOO_LARGE");
                case DATABASE_ERROR -> error(500, "QUERY_UNAVAILABLE");
            };
        } catch (Exception exception) { return error(500, "QUERY_UNAVAILABLE"); }
    }

    private Map<String, Object> error(int status, String code) {
        return response(status, Map.of("error", code, "message", switch (code) {
            case "INVALID_AMOUNT" -> "A nonempty amount is invalid; no aggregate results returned";
            case "MISSING_RUN_ID" -> "X-TLQ-Run-Id is required";
            case "QUERY_UNAVAILABLE" -> "Query failed; database details are not exposed";
            default -> "Request rejected: " + code;
        }));
    }
    private Map<String, Object> response(int status, Object body) {
        try {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("statusCode", status); response.put("isBase64Encoded", false);
            response.put("headers", Map.of("content-type", "application/json; charset=utf-8"));
            response.put("body", mapper.writeValueAsString(body));
            return response;
        } catch (java.io.IOException exception) { throw new IllegalStateException("Response serialization failed"); }
    }
}
