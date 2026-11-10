package edu.uw.tcss562.tlq;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public record QueryRequest(String runId, String state, String year, List<Dimension> groupBy, Set<Metric> metrics) {
    public enum Dimension {
        STATE("state", "recipientState"), YEAR("year", "programYear");
        public final String key;
        public final String column;
        Dimension(String key, String column) { this.key = key; this.column = column; }
        static Dimension parse(String key) {
            for (var value : values()) if (value.key.equals(key)) return value;
            throw new IllegalArgumentException("Unsupported grouping field");
        }
    }
    public enum Metric { COUNT, SUM, AVG }
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public QueryRequest {
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("run_id is required");
        groupBy = List.copyOf(groupBy);
        metrics = Set.copyOf(metrics);
        if (groupBy.size() != Set.copyOf(groupBy).size() || groupBy.size() > 2 || metrics.isEmpty()) {
            throw new IllegalArgumentException("Invalid grouping or metrics");
        }
    }

    public boolean needsAmounts() { return metrics.contains(Metric.SUM) || metrics.contains(Metric.AVG); }

    public static QueryRequest parse(String runId, String body) {
        try {
            JsonNode root = JSON.readTree(body);
            require(root != null && root.isObject());
            allowed(root, Set.of("filters", "groupBy", "metrics"));
            String state = null, year = null;
            if (root.has("filters")) {
                JsonNode filters = root.get("filters");
                require(filters.isObject()); allowed(filters, Set.of("state", "year"));
                if (filters.has("state")) { require(filters.get("state").isTextual()); state = filters.get("state").textValue(); }
                if (filters.has("year")) { require(filters.get("year").isTextual()); year = filters.get("year").textValue(); }
            }
            List<Dimension> groups = new ArrayList<>();
            if (root.has("groupBy")) {
                require(root.get("groupBy").isArray());
                for (var group : root.get("groupBy")) { require(group.isTextual()); groups.add(Dimension.parse(group.textValue())); }
            }
            Set<Metric> metrics = EnumSet.allOf(Metric.class);
            if (root.has("metrics")) {
                require(root.get("metrics").isArray()); metrics.clear();
                for (var metric : root.get("metrics")) {
                    require(metric.isTextual());
                    Metric value = switch (metric.textValue()) {
                        case "count" -> Metric.COUNT;
                        case "sum" -> Metric.SUM;
                        case "avg" -> Metric.AVG;
                        default -> throw new IllegalArgumentException("Unsupported metric");
                    };
                    require(metrics.add(value));
                }
            }
            return new QueryRequest(runId, state, year, groups, metrics);
        } catch (java.io.IOException exception) { throw new IllegalArgumentException("Invalid query JSON"); }
    }

    private static void allowed(JsonNode object, Set<String> names) {
        var fields = object.fieldNames();
        while (fields.hasNext()) require(names.contains(fields.next()));
    }
    private static void require(boolean condition) {
        if (!condition) throw new IllegalArgumentException("Invalid query request");
    }
}
