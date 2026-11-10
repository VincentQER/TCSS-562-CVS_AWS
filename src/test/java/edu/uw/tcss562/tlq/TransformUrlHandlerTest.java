package edu.uw.tcss562.tlq;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.apache.commons.csv.CSVPrinter;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TransformUrlHandlerTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private String csv() throws Exception {
        var output = new StringWriter();
        try (var printer = new CSVPrinter(output, CsvTransformer.FORMAT)) {
            printer.printRecord(PaymentSchema.HEADERS);
            var values = new ArrayList<>(Collections.nCopies(75, ""));
            values.set(6, "中文, \"name\"\nsecond line");
            values.set(14, "00123");
            values.set(30, "13.78");
            values.set(45, "00042");
            printer.printRecord(values);
        }
        return output.toString();
    }

    private Map<String, Object> event(String body, boolean encoded) {
        return new HashMap<>(Map.of("version", "2.0", "requestContext", Map.of("http", Map.of("method", "POST")),
            "headers", Map.of("Content-Type", "text/csv; charset=UTF-8"), "body", body, "isBase64Encoded", encoded));
    }

    private void assertError(Map<String, Object> response, int status, String code) throws Exception {
        assertEquals(status, response.get("statusCode"));
        assertEquals(code, mapper.readTree((String) response.get("body")).get("error").asText());
        assertEquals(false, response.get("isBase64Encoded"));
    }

    @Test void normalPostMatchesExistingConverter() throws Exception {
        String csv = csv();
        var response = new TransformUrlHandler().handleRequest(event(csv, false), null);
        assertEquals(200, response.get("statusCode"));
        assertEquals(false, response.get("isBase64Encoded"));
        assertEquals("application/json; charset=utf-8", ((Map<?, ?>) response.get("headers")).get("content-type"));
        var array = mapper.readTree((String) response.get("body"));
        assertTrue(array.isArray());
        assertEquals(75, array.get(0).size());
        assertEquals("00123", array.get(0).get("recipientZipCode").asText());
        assertEquals(mapper.valueToTree(new CsvTransformer().transform(new StringReader(csv))), array);
    }

    @Test void base64PostMatchesPlainPost() throws Exception {
        String csv = csv();
        var handler = new TransformUrlHandler();
        var response = handler.handleRequest(event(Base64.getEncoder().encodeToString(csv.getBytes(StandardCharsets.UTF_8)), true), null);
        assertEquals(handler.handleRequest(event(csv, false), null), response);
    }

    @Test void invalidCsvReturns400() throws Exception {
        for (String invalid : new String[] {"", "bad,header\na,b", String.join(",", PaymentSchema.HEADERS) + "\nshort,row",
                String.join(",", PaymentSchema.HEADERS) + "\n\"unclosed"}) {
            assertError(new TransformUrlHandler().handleRequest(event(invalid, false), null), 400, "INVALID_CSV");
        }
    }

    @Test void wrongMethodReturns405AndAllowHeader() throws Exception {
        var request = event(csv(), false);
        request.put("requestContext", Map.of("http", Map.of("method", "GET")));
        var response = new TransformUrlHandler().handleRequest(request, null);
        assertError(response, 405, "METHOD_NOT_ALLOWED");
        assertEquals("POST", ((Map<?, ?>) response.get("headers")).get("allow"));
    }

    @Test void rejectsUnsupportedContentTypeAndCharset() throws Exception {
        for (String type : new String[] {"application/json", "text/csv; charset=ISO-8859-1", ""}) {
            var request = event(csv(), false);
            request.put("headers", Map.of("content-type", type));
            assertEquals(415, new TransformUrlHandler().handleRequest(request, null).get("statusCode"));
        }
    }

    @Test void rejectsMalformedEventsAndEncoding() throws Exception {
        var handler = new TransformUrlHandler();
        assertError(handler.handleRequest(null, null), 400, "INVALID_EVENT");
        assertError(handler.handleRequest(Map.of(), null), 400, "INVALID_EVENT");
        var invalidFlag = event(csv(), false);
        invalidFlag.put("isBase64Encoded", "true");
        assertError(handler.handleRequest(invalidFlag, null), 400, "INVALID_EVENT");
        assertError(handler.handleRequest(event("%%%", true), null), 400, "INVALID_BASE64");
        assertError(handler.handleRequest(event(Base64.getEncoder().encodeToString(new byte[] {(byte) 0xff}), true), null), 400, "INVALID_UTF8");
    }

    @Test void requestLimitsUseDecodedUtf8BytesIncludingBase64() throws Exception {
        String csv = csv();
        int size = csv.getBytes(StandardCharsets.UTF_8).length;
        assertTrue(size > csv.length());
        for (boolean encoded : new boolean[] {false, true}) {
            var request = event(encoded ? Base64.getEncoder().encodeToString(csv.getBytes(StandardCharsets.UTF_8)) : csv, encoded);
            assertEquals(200, new TransformUrlHandler(size, 100_000).handleRequest(request, null).get("statusCode"));
            assertError(new TransformUrlHandler(size - 1, 100_000).handleRequest(request, null), 413, "REQUEST_TOO_LARGE");
        }
    }

    @Test void responseLimitIncludesSerializedEnvelopeAndNeverTruncates() throws Exception {
        var request = event(csv(), false);
        var success = new TransformUrlHandler().handleRequest(request, null);
        int envelopeBytes = mapper.writeValueAsBytes(success).length;
        assertTrue(envelopeBytes > ((String) success.get("body")).getBytes(StandardCharsets.UTF_8).length);
        assertEquals(success, new TransformUrlHandler(100_000, envelopeBytes).handleRequest(request, null));
        assertError(new TransformUrlHandler(100_000, envelopeBytes - 1).handleRequest(request, null), 413, "RESPONSE_TOO_LARGE");
    }
}
