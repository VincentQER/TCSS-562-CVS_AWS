package edu.uw.tcss562.tlq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/** Manual, password-interactive full local chain. Only adds one NEW run; never changes old runs. */
public final class LocalRealTlqMain {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static void require(boolean condition, String code) throws QueryIntegrationDiagnostics.CheckFailure {
        if (!condition) throw new QueryIntegrationDiagnostics.CheckFailure(code);
    }

    public static void main(String[] args) {
        String stage = "VALIDATE_LOCAL_INPUT";
        Path directory = Path.of("test-output", "local-real-tlq-" + UUID.randomUUID()).toAbsolutePath();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("status", "RUNNING");
        report.put("scope", "LOCAL_FUNCTIONAL_VALIDATION_ONLY_NOT_CLOUD_PERFORMANCE");
        report.put("target", "127.0.0.1:3306/tlq_load_it/payments_raw");
        report.put("usesActualHandlersAndMysql", true);
        AtomicReference<String> acceptedRun = new AtomicReference<>();
        try {
            require(args.length == 0, "NO_ARGUMENTS_ALLOWED");
            Path source = Files.isRegularFile(Path.of("test-data/1K_RECORDS.csv")) ? Path.of("test-data/1K_RECORDS.csv") : Path.of("1K_RECORDS.csv");
            require(Files.isRegularFile(source) && Files.isRegularFile(Path.of("target/client-sync-tlq.jar"))
                && Files.isRegularFile(Path.of("scripts/tlq-sync.sh")), "RUN_FROM_PROJECT_ROOT_WITH_BUILT_JAR");
            List<Map<String, String>> expected;
            try (var reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) { expected = new CsvTransformer().transform(reader); }
            require(expected.size() == 998, "EXPECTED_998_INPUT_RECORDS");
            BigDecimal sum = null; long amountCount = 0;
            for (var row : expected) {
                var value = QueryRules.parseReference(row.get("totalAmountOfPaymentUsdollars"));
                if (value != null) { amountCount++; sum = sum == null ? value : sum.add(value); }
            }
            String expectedSum = QueryRules.sum(sum);
            String expectedAvg = amountCount == 0 ? null : sum.divide(BigDecimal.valueOf(amountCount), QueryRules.AVG_SCALE, QueryRules.ROUNDING).toPlainString();
            report.put("expectedRecords", 998); report.put("expectedAmountCount", amountCount);
            report.put("expectedSum", expectedSum); report.put("expectedAvg", expectedAvg);
            stage = "SECURE_CONSOLE";
            var console = System.console(); require(console != null, "NO_SECURE_CONSOLE");
            char[] password = console.readPassword("Local MySQL tlq_load_it password (hidden; NEW run only): ");
            require(password != null && password.length > 0, "PASSWORD_CANCELLED_OR_EMPTY");
            MysqlConnectionFactory factory;
            try {
                factory = MysqlConnectionFactory.fromEnvironment(Map.of("DB_HOST", "127.0.0.1", "DB_PORT", "3306",
                    "DB_NAME", "tlq_load_it", "DB_USER", "tlq_load_it", "DB_PASSWORD", new String(password),
                    "DB_TABLE", "payments_raw", "DB_SSL_MODE", "REQUIRED"));
            } finally { Arrays.fill(password, '\0'); }
            stage = "DATABASE_PREFLIGHT";
            try (var connection = factory.open(); var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT CURRENT_USER(), DATABASE(), VERSION()")) {
                require(rows.next() && "tlq_load_it@127.0.0.1".equals(rows.getString(1))
                    && "tlq_load_it".equals(rows.getString(2)) && "8.4.9".equals(rows.getString(3)), "LOCAL_DATABASE_IDENTITY_MISMATCH");
                report.put("account", rows.getString(1)); report.put("serverVersion", rows.getString(3));
            }
            Files.createDirectory(directory);
            JSON.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("verification.json").toFile(), report);
            var loader = new JdbcLoadService(factory, "payments_raw");
            var query = new JdbcQueryService(factory, "payments_raw");
            stage = "BASH_HTTP_REAL_HANDLERS_AND_JDBC";
            try (var adapter = new LocalHandlerHttpAdapter(Map.of(
                    "/transform", new TransformUrlHandler(), "/load", new LoadUrlHandler(loader::load),
                    "/query", new QueryUrlHandler(query::query)), run -> {
                        require(run != null && run.matches("run-[A-Za-z0-9-]+"), "INVALID_CLIENT_RUN_ID");
                        if (acceptedRun.get() == null) {
                            try (var observer = factory.open(); var statement = observer.prepareStatement(
                                    "SELECT COUNT(*) FROM payments_raw WHERE run_id = ?")) {
                                statement.setString(1, run);
                                try (var count = statement.executeQuery()) {
                                    require(count.next() && count.getLong(1) == 0, "RUN_ID_ALREADY_EXISTS_STOP");
                                }
                            }
                            acceptedRun.set(run);
                        }
                        require(run.equals(acceptedRun.get()), "ADAPTER_REJECTS_OTHER_RUN_IDS");
                    })) {
                String base = adapter.baseUrl();
                boolean windows = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");
                String bash = System.getenv().getOrDefault("TLQ_BASH", windows ? "C:/Program Files/Git/usr/bin/bash.exe" : "bash");
                String java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString().replace('\\', '/');
                var command = List.of(bash, Path.of("scripts/tlq-sync.sh").toAbsolutePath().toString().replace('\\', '/'),
                    "--csv", source.toAbsolutePath().toString().replace('\\', '/'), "--batch-size", "200", "--auth", "none",
                    "--transform-url", base + "/transform", "--load-url", base + "/load", "--query-url", base + "/query",
                    "--output-root", directory.resolve("runs").toString().replace('\\', '/'));
                var builder = new ProcessBuilder(command).inheritIO();
                builder.environment().put("TLQ_JAVA", java);
                builder.environment().put("TLQ_AWS", "DO_NOT_CALL_AWS_IN_LOCAL_REAL_TEST");
                for (String name : List.of("DB_PASSWORD", "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN")) builder.environment().remove(name);
                long started = System.nanoTime();
                int exit = builder.start().waitFor();
                report.put("bashProcessElapsedMs", (System.nanoTime() - started) / 1_000_000.0);
                report.put("bashExitCode", exit); report.put("adapterSteps", adapter.observations());
                require(exit == 0, "BASH_CHAIN_FAILED_STOP_NO_RETRY");
            }
            report.put("runId", acceptedRun.get());
            stage = "VERIFY_CLIENT_SUMMARY";
            Path runDirectory;
            try (var children = Files.list(directory.resolve("runs"))) {
                var runs = children.filter(Files::isDirectory).toList(); require(runs.size() == 1, "EXPECTED_ONE_NEW_CLIENT_RUN"); runDirectory = runs.getFirst();
            }
            JsonNode summary = JSON.readTree(runDirectory.resolve("summary.json").toFile());
            require("PASSED".equals(summary.path("status").asText()) && summary.path("acknowledgedRecords").asInt() == 998
                && summary.path("successfulLoadBatches").asInt() == 5 && summary.path("expectedRecords").asInt() == 998,
                "CLIENT_SUMMARY_RECORD_COUNT_MISMATCH");
            require(acceptedRun.get() != null && acceptedRun.get().equals(summary.path("runId").asText()), "CLIENT_RUN_ID_MISMATCH");
            List<String> paths = new ArrayList<>();
            for (JsonNode step : summary.path("steps")) {
                String name = step.path("stage").asText();
                if (List.of("transform", "load", "query").contains(name)) {
                    require(step.path("httpStatus").asInt() == 200, "EXPECTED_ALL_HTTP_200"); paths.add(name);
                }
            }
            require(paths.equals(List.of("transform", "load", "transform", "load", "transform", "load", "transform", "load", "transform", "load", "query")),
                "UNEXPECTED_HTTP_STAGE_ORDER");
            report.put("clientSummary", runDirectory.resolve("summary.json").toString());
            report.put("clientTotalElapsedMs", summary.path("totalElapsedMs").doubleValue());
            stage = "VERIFY_REAL_QUERY_AGGREGATES";
            JsonNode response = JSON.readTree(runDirectory.resolve("responses/query.json").toFile());
            require(response.path("runId").asText().equals(acceptedRun.get()) && response.path("results").size() == 1, "QUERY_SCOPE_MISMATCH");
            var aggregate = response.path("results").get(0);
            require(aggregate.path("count").asLong() == 998 && aggregate.path("amountCount").asLong() == amountCount
                && java.util.Objects.equals(expectedSum, aggregate.path("sum").isNull() ? null : aggregate.path("sum").asText())
                && java.util.Objects.equals(expectedAvg, aggregate.path("avg").isNull() ? null : aggregate.path("avg").asText()), "QUERY_AGGREGATE_MISMATCH");
            report.put("actualAggregate", JSON.convertValue(aggregate, Map.class));
            stage = "INDEPENDENT_DATABASE_VERIFY_75_FIELDS";
            verifyStored(factory, acceptedRun.get(), expected);
            report.put("independentDatabaseRows", 998); report.put("all75FieldsMatch", true);
            report.put("status", "PASSED"); report.put("step", "COMPLETED");
            System.out.println("ALL LOCAL REAL TLQ CHAIN TESTS PASSED; LOCAL FUNCTIONAL DATA, NOT CLOUD PERFORMANCE");
        } catch (Exception failure) {
            report.put("status", "FAILED"); report.put("step", stage);
            report.put("diagnostic", QueryIntegrationDiagnostics.describe(stage, failure));
            if (acceptedRun.get() != null) report.put("runId", acceptedRun.get());
            System.err.println("Local chain stopped at " + stage + "; no retry or deletion; see safe report");
        }
        try {
            Files.createDirectories(directory);
            JSON.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("verification.json").toFile(), report);
            System.out.println("Verification report: " + directory.resolve("verification.json"));
        } catch (Exception ignored) { System.err.println("Unable to save local verification report"); }
        if (!"PASSED".equals(report.get("status"))) System.exit(1);
    }

    private static void verifyStored(MysqlConnectionFactory factory, String run, List<Map<String, String>> expected) throws Exception {
        String columns = PaymentSchema.JSON_FIELDS.stream().map(field -> "`" + field + "`").collect(Collectors.joining(","));
        try (Connection observer = factory.open(); var statement = observer.prepareStatement(
                "SELECT " + columns + " FROM payments_raw WHERE run_id = ? ORDER BY test_row_id")) {
            statement.setString(1, run);
            try (var rows = statement.executeQuery()) {
                int index = 0;
                while (rows.next()) {
                    require(index < expected.size(), "EXTRA_DATABASE_ROWS");
                    for (int field = 0; field < 75; field++) require(java.util.Objects.equals(
                        expected.get(index).get(PaymentSchema.JSON_FIELDS.get(field)), rows.getString(field + 1)), "DATABASE_FIELD_MISMATCH");
                    index++;
                }
                require(index == expected.size(), "MISSING_DATABASE_ROWS");
            }
        }
    }
}
