package edu.uw.tcss562.tlq;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Buffered Function URL payload v2.0 adapter. Makes no AWS service calls. */
public final class TransformUrlHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {
    public static final int MAX_REQUEST_BYTES = 1024 * 1024;
    public static final int MAX_RESPONSE_BYTES = 5 * 1024 * 1024;
    private final int maxRequestBytes;
    private final int maxResponseBytes;
    private final ObjectMapper mapper = new ObjectMapper();

    public TransformUrlHandler() {
        this(MAX_REQUEST_BYTES, MAX_RESPONSE_BYTES);
    }

    TransformUrlHandler(int maxRequestBytes, int maxResponseBytes) {
        if (maxRequestBytes < 1 || maxResponseBytes < 1) {
            throw new IllegalArgumentException("Payload limits must be positive");
        }
        this.maxRequestBytes = maxRequestBytes;
        this.maxResponseBytes = maxResponseBytes;
    }

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        try {
            JsonNode request = mapper.valueToTree(event);
            if (request == null || !request.isObject()) {
                return error(400, "INVALID_EVENT", "Expected a Function URL payload v2.0 event");
            }
            JsonNode method = request.path("requestContext").path("http").path("method");
            if (!method.isTextual()) {
                return error(400, "INVALID_EVENT", "Missing requestContext.http.method");
            }
            if (!"POST".equals(method.textValue())) {
                var response = error(405, "METHOD_NOT_ALLOWED", "Use POST with a text/csv body");
                response.put("headers", Map.of("content-type", "application/json; charset=utf-8", "allow", "POST"));
                return response;
            }
            String contentType = "";
            var headers = request.path("headers").fields();
            while (headers.hasNext()) {
                var header = headers.next();
                if ("content-type".equalsIgnoreCase(header.getKey())) {
                    contentType = header.getValue().asText("");
                }
            }
            String[] parts = contentType.split(";");
            if (!"text/csv".equalsIgnoreCase(parts[0].trim())) {
                return error(415, "UNSUPPORTED_MEDIA_TYPE", "Content-Type must be text/csv with UTF-8 encoding");
            }
            for (int i = 1; i < parts.length; i++) {
                String[] parameter = parts[i].trim().split("=", 2);
                if ("charset".equalsIgnoreCase(parameter[0]) && (parameter.length != 2
                        || !"utf-8".equalsIgnoreCase(parameter[1].trim().replace("\"", "")))) {
                    return error(415, "UNSUPPORTED_CHARSET", "CSV must use UTF-8 encoding");
                }
            }
            JsonNode body = request.path("body");
            JsonNode encoded = request.path("isBase64Encoded");
            if (!body.isTextual() || (!encoded.isMissingNode() && !encoded.isBoolean())) {
                return error(400, "INVALID_EVENT", "Expected a string body and boolean isBase64Encoded");
            }
            String text = body.textValue();
            boolean base64 = encoded.asBoolean(false);
            long maxCharacters = base64 ? 4L * ((maxRequestBytes + 2L) / 3L) : maxRequestBytes;
            if (text.length() > maxCharacters) {
                return requestTooLarge();
            }
            byte[] csvBytes;
            try {
                csvBytes = base64 ? Base64.getDecoder().decode(text) : text.getBytes(StandardCharsets.UTF_8);
            } catch (IllegalArgumentException exception) {
                return error(400, "INVALID_BASE64", "Request body is not valid Base64");
            }
            if (csvBytes.length > maxRequestBytes) {
                return requestTooLarge();
            }
            String csv;
            try {
                csv = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(csvBytes)).toString();
            } catch (CharacterCodingException exception) {
                return error(400, "INVALID_UTF8", "Request body must contain valid UTF-8 CSV");
            }
            Object records;
            try {
                records = new CsvTransformer().transform(new StringReader(csv));
            } catch (IOException | IllegalArgumentException exception) {
                return error(400, "INVALID_CSV", "CSV must have the exact 75-column header and valid 75-column records");
            }
            String json = mapper.writeValueAsString(records);
            var response = response(200, json);
            // Check UTF-8 bytes, including escaping the JSON array as the envelope's body string.
            if (json.getBytes(StandardCharsets.UTF_8).length > maxResponseBytes
                    || mapper.writeValueAsBytes(response).length > maxResponseBytes) {
                return error(413, "RESPONSE_TOO_LARGE", "Transformed response exceeds the "
                    + maxResponseBytes + " byte response budget; send fewer CSV records per batch");
            }
            return response;
        } catch (Exception exception) {
            // Do not include CSV values or request metadata in errors or logs.
            return error(500, "INTERNAL_ERROR", "Unable to transform the request");
        }
    }

    private Map<String, Object> requestTooLarge() {
        return error(413, "REQUEST_TOO_LARGE", "Decoded CSV exceeds " + maxRequestBytes
            + " bytes; send fewer CSV records per batch");
    }

    private Map<String, Object> error(int status, String code, String message) {
        try {
            return response(status, mapper.writeValueAsString(Map.of("error", code, "message", message)));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to serialize fixed error response", exception);
        }
    }

    private Map<String, Object> response(int status, String body) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("statusCode", status);
        response.put("headers", Map.of("content-type", "application/json; charset=utf-8"));
        response.put("isBase64Encoded", false);
        response.put("body", body);
        return response;
    }
}
