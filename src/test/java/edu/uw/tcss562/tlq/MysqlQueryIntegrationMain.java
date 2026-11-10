package edu.uw.tcss562.tlq;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Explicit interactive integration check. SELECT only; never inserts fixtures or changes data. */
public final class MysqlQueryIntegrationMain {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static void require(boolean condition, String safeMessage) {
        if (!condition) throw new IllegalStateException(safeMessage);
    }

    private static void check(boolean condition, String code) throws QueryIntegrationDiagnostics.CheckFailure {
        if (!condition) throw new QueryIntegrationDiagnostics.CheckFailure(code);
    }

    public static void main(String[] args) {
        Path output = Path.of("test-output", "query-mysql-it-" + UUID.randomUUID() + ".json");
        Map<String, Object> report = new LinkedHashMap<>();
        List<Map<String, Object>> checks = new ArrayList<>(); report.put("checks", checks);
        String step = "VALIDATE_ARGUMENTS";
        try {
            check(args.length == 0, "UNEXPECTED_ARGUMENTS");
            step = "FIND_PASSED_LOAD_REPORT"; report.put("step", step);
            Path loadReport;
            try (var files = Files.list(Path.of("test-output"))) {
                loadReport = files.filter(file -> file.getFileName().toString().startsWith("mysql-integration-it-")
                    && file.toString().endsWith(".json"))
                    .filter(file -> { try { return "PASSED".equals(JSON.readTree(file.toFile()).path("status").asText()); }
                        catch (Exception ignored) { return false; } })
                    .max(Comparator.comparingLong(file -> file.toFile().lastModified())).orElse(null);
            }
            check(loadReport != null, "NO_PASSED_LOAD_REPORT");
            report.put("loadReport", loadReport.toString());
            step = "VALIDATE_LOAD_REPORT"; report.put("step", step);
            var previous = JSON.readTree(loadReport.toFile());
            check("127.0.0.1:3306/tlq_load_it".equals(previous.path("target").asText()), "SOURCE_REPORT_TARGET_MISMATCH");
            step = "READ_1K_INPUT"; report.put("step", step);
            var small = source("1K_RECORDS.csv", 998);
            step = "READ_10K_INPUT"; report.put("step", step);
            var large = source("10K_RECORDS.csv", 9998);
            step = "SECURE_CONSOLE"; report.put("step", step);
            var console = System.console();
            check(console != null, "NO_SECURE_CONSOLE");
            step = "READ_PASSWORD"; report.put("step", step);
            char[] password = console.readPassword("Local tlq_load_it password (hidden, read-only Query tests): ");
            check(password != null && password.length > 0, "PASSWORD_CANCELLED_OR_EMPTY");
            step = "BUILD_LOCAL_CONFIG"; report.put("step", step);
            MysqlConnectionFactory factory;
            try {
                factory = MysqlConnectionFactory.fromEnvironment(Map.of("DB_HOST", "127.0.0.1", "DB_PORT", "3306",
                    "DB_NAME", "tlq_load_it", "DB_USER", "tlq_load_it", "DB_PASSWORD", new String(password),
                    "DB_TABLE", "payments_raw", "DB_SSL_MODE", "REQUIRED"));
            } finally { Arrays.fill(password, '\0'); }
            step = "OPEN_DATABASE_AND_VALIDATE_SCHEMA"; report.put("step", step);
            try (var connection = factory.open(); var statement = connection.createStatement()) {
                connection.setReadOnly(true);
                step = "READ_SERVER_METADATA"; report.put("step", step);
                try (var rows = statement.executeQuery("SELECT CURRENT_USER(), DATABASE(), VERSION(), @@div_precision_increment")) {
                    check(rows.next(), "SERVER_METADATA_MISSING");
                    step = "VERIFY_ACCOUNT_AND_DATABASE"; report.put("step", step);
                    check("tlq_load_it@127.0.0.1".equals(rows.getString(1)) && "tlq_load_it".equals(rows.getString(2)), "DATABASE_IDENTITY_MISMATCH");
                    step = "VERIFY_SERVER_VERSION"; report.put("step", step);
                    check("8.4.9".equals(rows.getString(3)), "DATABASE_VERSION_MISMATCH");
                    report.put("serverVersion", rows.getString(3)); report.put("divPrecisionIncrement", rows.getInt(4));
                }
            }
            report.put("rulesVersion", QueryRules.VERSION); report.put("loadReport", loadReport.toString());
            var service = new JdbcQueryService(factory, "payments_raw");
            for (var saved : previous.path("cases")) {
                if (!"PASSED".equals(saved.path("status").asText()) || !"payments_raw".equals(saved.path("table").asText())) continue;
                String name = saved.path("case").asText(), run = saved.path("run_id").asText();
                List<Map<String, String>> expected = switch (name) {
                    case "batch-200" -> small.subList(0, 200);
                    case "duplicates" -> List.of(small.getFirst(), small.getFirst());
                    case "isolation-a" -> small.subList(0, 2);
                    case "isolation-b" -> small.subList(2, 5);
                    case "full-1k" -> small;
                    case "full-10k" -> large;
                    default -> throw new IllegalStateException("Unknown source test case");
                };
                String state = expected.getFirst().get("recipientState"), year = expected.getFirst().get("programYear");
                List<String> bodies = List.of("{}", "{\"groupBy\":[\"state\"]}", "{\"groupBy\":[\"year\"]}",
                    "{\"groupBy\":[\"state\",\"year\"]}",
                    JSON.writeValueAsString(Map.of("filters", Map.of("state", state, "year", year), "groupBy", List.of("state"))));
                report.put("currentCase", name); report.put("currentRunId", run);
                int queryIndex = 0;
                for (String body : bodies) {
                    report.put("queryIndex", ++queryIndex);
                    step = "EXECUTE_AGGREGATE"; report.put("step", step);
                    var request = QueryRequest.parse(run, body);
                    var actual = service.query(request);
                    step = "COMPARE_SOURCE_REFERENCE"; report.put("step", step);
                    compare(actual, reference(expected, request));
                }
                checks.add(Map.of("case", name, "run_id", run, "queries", bodies.size(), "status", "PASSED"));
                System.out.println("PASS real SQL groups/count/sum/avg + filters: " + name);
            }
            step = "VERIFY_EMPTY_RUN"; report.put("step", step);
            String missing = "query-absent-" + UUID.randomUUID();
            var empty = service.query(QueryRequest.parse(missing, "{}"));
            require(empty.size() == 1 && empty.getFirst().get("count").equals(0L)
                && empty.getFirst().get("sum") == null && empty.getFirst().get("avg") == null, "Empty aggregate mismatch");
            require(service.query(QueryRequest.parse(missing, "{\"groupBy\":[\"state\"]}")).isEmpty(), "Empty grouped query mismatch");
            checks.add(Map.of("case", "empty-run", "status", "PASSED"));
            step = "VERIFY_READ_ONLY_AMOUNT_BOUNDARIES"; report.put("step", step);
            boundaryChecks(factory);
            checks.add(Map.of("case", "read-only-derived-amount-boundaries", "status", "PASSED"));
            step = "VERIFY_HTTP_EVENT_TO_JDBC"; report.put("step", step);
            String run = previous.path("cases").get(0).path("run_id").asText();
            var handler = new QueryUrlHandler(service::query);
            var response = handler.handleRequest(Map.of("requestContext", Map.of("http", Map.of("method", "POST")),
                "headers", Map.of("content-type", "application/json", "x-tlq-run-id", run), "body", "{}", "isBase64Encoded", false), null);
            require(response.get("statusCode").equals(200), "HTTP handler-to-JDBC check failed");
            checks.add(Map.of("case", "http-event-to-real-jdbc", "status", "PASSED"));
            require(checks.size() == 9, "Expected six saved Load cases and three extra Query checks");
            step = "COMPLETED"; report.put("step", step); report.put("status", "PASSED");
            System.out.println("ALL READ-ONLY MYSQL QUERY TESTS PASSED");
        } catch (Exception failure) {
            report.put("status", "FAILED");
            var diagnostic = QueryIntegrationDiagnostics.describe(step, failure);
            report.put("error", diagnostic.get("category")); report.put("diagnostic", diagnostic);
            System.err.println("Query integration stopped; step=" + step + ", category=" + diagnostic.get("category") + ". No data modified.");
        }
        try {
            Files.createDirectories(output.getParent()); JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
            System.out.println("Report: " + output);
        } catch (Exception ignored) { System.err.println("Unable to save non-secret report"); }
        if (!"PASSED".equals(report.get("status"))) System.exit(1);
    }

    private static List<Map<String, String>> source(String name, int expected) throws Exception {
        Path path = Files.isRegularFile(Path.of("test-data", name)) ? Path.of("test-data", name) : Path.of(name);
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            var rows = new CsvTransformer().transform(reader); require(rows.size() == expected, "Input record count mismatch"); return rows;
        }
    }

    /** Independent test oracle only; production aggregation stays in MySQL. */
    private static List<Map<String, Object>> reference(List<Map<String, String>> source, QueryRequest request) {
        Map<List<String>, List<Map<String, String>>> groups = new LinkedHashMap<>();
        if (request.groupBy().isEmpty()) groups.put(List.of(), new ArrayList<>());
        for (var row : source) {
            if (request.state() != null && !request.state().equals(row.get("recipientState"))) continue;
            if (request.year() != null && !request.year().equals(row.get("programYear"))) continue;
            var key = request.groupBy().stream().map(dimension -> row.get(dimension.column)).toList();
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (var entry : groups.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < request.groupBy().size(); i++) row.put(request.groupBy().get(i).key, entry.getKey().get(i));
            long count = 0; BigDecimal sum = null;
            for (var record : entry.getValue()) {
                var amount = QueryRules.parseReference(record.get("totalAmountOfPaymentUsdollars"));
                if (amount != null) { count++; sum = sum == null ? amount : sum.add(amount); }
            }
            row.put("count", (long) entry.getValue().size()); row.put("amountCount", count);
            row.put("sum", QueryRules.sum(sum)); row.put("avg", count == 0 ? null : sum.divide(BigDecimal.valueOf(count), QueryRules.AVG_SCALE, QueryRules.ROUNDING).toPlainString());
            result.add(row);
        }
        return result;
    }
    private static void compare(List<Map<String, Object>> actual, List<Map<String, Object>> expected) throws Exception {
        var a = new ArrayList<String>(); var b = new ArrayList<String>();
        for (var row : actual) a.add(JSON.writeValueAsString(row));
        for (var row : expected) b.add(JSON.writeValueAsString(row));
        a.sort(String::compareTo); b.sort(String::compareTo);
        require(a.equals(b), "SQL aggregation differs from independent source reference");
    }

    private static void boundaryChecks(MysqlConnectionFactory factory) throws Exception {
        List<String> samples = Arrays.asList(null, "", " ", "1\n", "1\r\n", "1e2", "+1", "1,000", "1.234", "1.", ".1",
            "1000000000000000000", "999999999999999999.99", "-999999999999999999.99", "00.01", "-0.10", "１２.３４");
        String source = String.join(" UNION ALL ", java.util.Collections.nCopies(samples.size(), "SELECT ? AS raw_amount"));
        String sql = "WITH filtered AS (" + source + "), classified AS (SELECT *, " + QueryRules.statusSql()
            + " AS amount_status FROM filtered) SELECT raw_amount, amount_status, " + QueryRules.decimalSql() + " AS amount_decimal FROM classified";
        try (var connection = factory.open(); var statement = connection.prepareStatement(sql)) {
            connection.setReadOnly(true);
            int index = 1;
            for (String sample : samples) { if (sample == null) statement.setNull(index++, Types.VARCHAR); else statement.setString(index++, sample); }
            statement.setString(index++, QueryRules.MYSQL_PATTERN); statement.setString(index, QueryRules.MYSQL_FORBIDDEN);
            try (var rows = statement.executeQuery()) {
                int count = 0;
                while (rows.next()) {
                    String raw = rows.getString("raw_amount");
                    BigDecimal expected = null;
                    int status = raw == null || raw.isEmpty() ? 0 : 1;
                    try { expected = QueryRules.parseReference(raw); } catch (IllegalArgumentException invalid) { status = 2; }
                    require(rows.getInt("amount_status") == status, "SQL amount classification mismatch");
                    var actual = rows.getBigDecimal("amount_decimal");
                    require(expected == null ? actual == null : expected.compareTo(actual) == 0, "Unsafe amount conversion detected");
                    count++;
                }
                require(count == samples.size() && rows.getWarnings() == null, "Boundary query count/warnings mismatch");
            }
            require(statement.getWarnings() == null && connection.getWarnings() == null, "Boundary conversion produced warnings");
        }
    }
}
