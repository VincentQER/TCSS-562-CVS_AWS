package edu.uw.tcss562.tlq;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Structural validation only. No unapproved business conversions. */
public final class LoadPayload {
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private LoadPayload() {}

    public static List<Map<String, String>> parse(String json) {
        try {
            JsonNode array = MAPPER.readTree(json);
            if (array == null || !array.isArray()) {
                throw new IllegalArgumentException("Load body must be a JSON array");
            }
            List<Map<String, String>> records = new ArrayList<>();
            for (JsonNode object : array) {
                if (!object.isObject() || object.size() != PaymentSchema.JSON_FIELDS.size()) {
                    throw new IllegalArgumentException("Each record must contain exactly the 75 contract fields");
                }
                Map<String, String> record = new LinkedHashMap<>();
                for (String field : PaymentSchema.JSON_FIELDS) {
                    JsonNode value = object.get(field);
                    if (value == null || !value.isTextual()) {
                        throw new IllegalArgumentException("All 75 contract values must be strings");
                    }
                    record.put(field, value.textValue());
                }
                records.add(record);
            }
            return records;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Invalid JSON array");
        }
    }
}
