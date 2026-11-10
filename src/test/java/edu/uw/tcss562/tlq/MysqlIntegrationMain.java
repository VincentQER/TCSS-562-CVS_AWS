package edu.uw.tcss562.tlq;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.stream.Collectors;

/** Explicit, interactive real-MySQL tests. Never run automatically by Maven. */
public final class MysqlIntegrationMain {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String session = "it-" + UUID.randomUUID();
    private final Path reportFile = Path.of("test-output", "mysql-integration-" + session + ".json");
    private final List<Map<String, Object>> results = new ArrayList<>();
    private final Map<String, Object> report = new LinkedHashMap<>();
    private final Map<String, MysqlConnectionFactory> factories = new LinkedHashMap<>();

    private MysqlIntegrationMain() {
        report.put("session", session);
        report.put("target", "127.0.0.1:3306/tlq_load_it");
        report.put("status", "RUNNING");
        report.put("cases", results);
    }

    public static void main(String[] args) {
        MysqlIntegrationMain tests = new MysqlIntegrationMain();
        try {
            boolean dryRun = args.length == 1 && "--check-inputs".equals(args[0]);
            require(args.length == 0 || dryRun, "Use no arguments, or --check-inputs (no database access)");
            var small = readSource("1K_RECORDS.csv", 998);
            var large = readSource("10K_RECORDS.csv", 9998);
            if (dryRun) {
                System.out.println("INPUTS OK: 1K=998, 10K=9998, 75 string fields; database not accessed");
                return;
            }
            var console = System.console();
            require(console != null, "No secure console. Run this command manually in Git Bash with winpty; do not pipe a password");
            char[] password = console.readPassword("Password for local MySQL tlq_load_it (hidden): ");
            require(password != null && password.length > 0, "Password input cancelled or empty; database not accessed");
            try {
                // Configuration map is built in memory; no environment or credential files are read.
                for (String table : List.of("payments_raw", "payments_rollback")) {
                    tests.factories.put(table, MysqlConnectionFactory.fromEnvironment(Map.of(
                        "DB_HOST", "127.0.0.1", "DB_PORT", "3306", "DB_NAME", "tlq_load_it",
                        "DB_USER", "tlq_load_it", "DB_PASSWORD", new String(password),
                        "DB_TABLE", table, "DB_SSL_MODE", "REQUIRED")));
                }
            } finally {
                Arrays.fill(password, '\0');
            }
            tests.preflight();
            tests.singleBatch(small.subList(0, 200));
            tests.duplicates(small.getFirst());
            tests.isolation(small);
            tests.rollback(small);
            tests.fullDataset("full-1k", small);
            tests.fullDataset("full-10k", large);
            tests.report.put("status", "PASSED");
            tests.save();
            System.out.println("ALL REAL MYSQL TESTS PASSED; report: " + tests.reportFile);
        } catch (Exception failure) {
            String safeMessage = failure instanceof VerificationFailure ? failure.getMessage()
                : failure instanceof JdbcLoadService.LoadFailure load ? load.getMessage()
                : "Integration test stopped after an internal/database error; no automatic retry";
            tests.report.put("status", "FAILED");
            tests.report.put("failure", safeMessage);
            if (failure instanceof JdbcLoadService.LoadFailure load) {
                tests.report.put("databaseOutcome", load.outcome().name());
                tests.report.put("databaseErrorCode", load.databaseErrorCode());
            }
            try { tests.save(); } catch (Exception ignored) { }
            // Never print raw driver exceptions, stack traces, config objects or payload values.
            System.err.println(safeMessage);
            System.err.println("Stop this run. Existing data retained. Report: " + tests.reportFile);
            System.exit(1);
        } finally {
            tests.factories.clear();
        }
    }

    private static List<Map<String, String>> readSource(String filename, int expected) throws Exception {
        Path source = Files.isRegularFile(Path.of("test-data", filename)) ? Path.of("test-data", filename) : Path.of(filename);
        require(Files.isRegularFile(source), "Missing input file: " + filename);
        try (var reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            var rows = new CsvTransformer().transform(reader);
            require(rows.size() == expected, "Unexpected input record count: " + filename);
            return rows;
        }
    }

    private void preflight() throws Exception {
        for (String table : factories.keySet()) {
            try (Connection connection = factories.get(table).open(); var statement = connection.createStatement()) {
                try (var result = statement.executeQuery("SELECT VERSION(), CURRENT_USER(), DATABASE(), @@port, "
                        + "@@sql_mode, @@character_set_database, @@collation_database")) {
                    require(result.next(), "Missing server metadata");
                    require("8.4.9".equals(result.getString(1)), "Server is not the approved MySQL 8.4.9; stop");
                    require("tlq_load_it@127.0.0.1".equals(result.getString(2)), "Unexpected account; root fallback forbidden");
                    require("tlq_load_it".equals(result.getString(3)) && result.getInt(4) == 3306, "Unexpected database or port");
                    report.put("serverVersion", result.getString(1));
                    report.put("account", result.getString(2));
                    report.put("sqlMode", result.getString(5));
                    report.put("characterSet", result.getString(6));
                    report.put("collation", result.getString(7));
                    report.put("jdbcDriverVersion", connection.getMetaData().getDriverVersion());
                }
                try (var result = statement.executeQuery("SHOW SESSION STATUS LIKE 'Ssl_cipher'")) {
                    require(result.next() && !result.getString(2).isBlank(), "TLS is required");
                    report.put("tlsCipher", result.getString(2));
                }
                try (var result = statement.executeQuery("SHOW CREATE TABLE `" + table + "`")) {
                    require(result.next(), "Missing test table");
                    String ddl = result.getString(2);
                    boolean marker = ddl.contains("__TLQ_IT_FORCE_FAIL__");
                    require(marker == table.equals("payments_rollback"), "Unexpected failure-injection table definition");
                }
            }
            // Unique constraints on business values could silently change duplicate-row semantics.
            try (Connection connection = factories.get(table).open(); var statement = connection.prepareStatement(
                    "SELECT COLUMN_NAME FROM information_schema.STATISTICS "
                    + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND NON_UNIQUE = 0")) {
                statement.setString(1, "tlq_load_it"); statement.setString(2, table);
                try (var result = statement.executeQuery()) {
                    while (result.next()) require("test_row_id".equals(result.getString(1)), "Unexpected unique constraint on test table");
                }
            }
        }
        save();
        System.out.println("PREFLIGHT PASS: MySQL 8.4.9, project user, TLS, InnoDB text fixture tables");
    }

    private Map<String, Object> begin(String name, String table, int expected) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        String run = session + "-" + name;
        result.put("case", name); result.put("run_id", run); result.put("table", table);
        result.put("expectedRows", expected); result.put("status", "RUNNING");
        results.add(result);
        save(); // Persist only non-secret run IDs BEFORE any write, including interrupted runs.
        verifyRows(table, run, List.of());
        return result;
    }

    private int load(String table, String run, List<Map<String, String>> rows) throws Exception {
        // Exercise the same JSON structural validator used by the HTTP handler.
        var parsed = LoadPayload.parse(JSON.writeValueAsString(rows));
        return new JdbcLoadService(factories.get(table), table).load(run, parsed);
    }

    private void finish(Map<String, Object> result, int committed) throws Exception {
        result.put("committedRecords", committed); result.put("status", "PASSED");
        result.put("independentConnectionVerified", true);
        save();
        System.out.println("PASS " + result.get("case") + ": " + result.get("run_id") + ", rows=" + committed);
    }

    private void singleBatch(List<Map<String, String>> rows) throws Exception {
        var result = begin("batch-200", "payments_raw", 200);
        String run = (String) result.get("run_id");
        int committed = load("payments_raw", run, rows);
        require(committed == 200, "200-record batch returned wrong committed count");
        verifyRows("payments_raw", run, rows);
        finish(result, committed);
    }

    private void duplicates(Map<String, String> row) throws Exception {
        var rows = List.of(row, new LinkedHashMap<>(row));
        var result = begin("duplicates", "payments_raw", 2);
        String run = (String) result.get("run_id");
        int committed = load("payments_raw", run, rows);
        require(committed == 2, "Duplicate rows were not both committed");
        verifyRows("payments_raw", run, rows);
        finish(result, committed);
    }

    private void isolation(List<Map<String, String>> source) throws Exception {
        var a = begin("isolation-a", "payments_raw", 2);
        var b = begin("isolation-b", "payments_raw", 3);
        var rowsA = source.subList(0, 2);
        var rowsB = source.subList(2, 5);
        int countA = load("payments_raw", (String) a.get("run_id"), rowsA);
        int countB = load("payments_raw", (String) b.get("run_id"), rowsB);
        require(countA == 2 && countB == 3, "Isolation cases returned incorrect committed counts");
        verifyRows("payments_raw", (String) a.get("run_id"), rowsA);
        verifyRows("payments_raw", (String) b.get("run_id"), rowsB);
        finish(a, countA); finish(b, countB);
    }

    private void rollback(List<Map<String, String>> source) throws Exception {
        var result = begin("rollback", "payments_rollback", 0);
        String run = (String) result.get("run_id");
        var bad = new LinkedHashMap<>(source.get(1));
        bad.put("changeType", "__TLQ_IT_FORCE_FAIL__");
        try {
            load("payments_rollback", run, List.of(source.getFirst(), bad));
            throw new VerificationFailure("CHECK batch unexpectedly committed; stop, do not delete or retry");
        } catch (JdbcLoadService.LoadFailure failure) {
            require(failure.outcome() == JdbcLoadService.Outcome.NOT_COMMITTED, "Rollback outcome is uncertain; stop");
            // MySQL ER_CHECK_CONSTRAINT_VIOLATED: distinguish CHECK failure from an unrelated failure.
            require(failure.databaseErrorCode() == 3819, "Batch failed for a reason other than the expected CHECK violation");
            result.put("expectedDatabaseErrorCode", failure.databaseErrorCode());
        }
        verifyRows("payments_rollback", run, List.of());
        finish(result, 0);
    }

    private void fullDataset(String name, List<Map<String, String>> rows) throws Exception {
        var result = begin(name, "payments_raw", rows.size());
        String run = (String) result.get("run_id");
        int committed = 0;
        int batches = 0;
        for (int start = 0; start < rows.size(); start += 200) {
            var batch = rows.subList(start, Math.min(start + 200, rows.size()));
            int count = load("payments_raw", run, batch);
            require(count == batch.size(), "Batch returned incorrect committed count; stop without retry");
            committed += count;
            batches++;
            result.put("acknowledgedRecords", committed); result.put("acknowledgedBatches", batches);
            save();
            System.out.println("Progress " + name + ": " + committed + "/" + rows.size());
        }
        require(committed == rows.size(), "Full dataset committed count mismatch");
        verifyRows("payments_raw", run, rows);
        finish(result, committed);
    }

    private void verifyRows(String table, String run, List<Map<String, String>> expected) throws Exception {
        String columns = PaymentSchema.JSON_FIELDS.stream().map(field -> "`" + field + "`").collect(Collectors.joining(","));
        // A fresh observer connection is never the loader's transaction/connection.
        try (Connection observer = factories.get(table).open(); var statement = observer.prepareStatement(
                "SELECT " + columns + " FROM `" + table + "` WHERE `run_id` = ? ORDER BY `test_row_id`")) {
            statement.setString(1, run);
            try (var result = statement.executeQuery()) {
                int index = 0;
                while (result.next()) {
                    require(index < expected.size(), "Extra records found for current run_id");
                    for (int i = 0; i < 75; i++) {
                        require(java.util.Objects.equals(expected.get(index).get(PaymentSchema.JSON_FIELDS.get(i)), result.getString(i + 1)),
                            "Field mismatch at row " + (index + 1) + ", field " + PaymentSchema.JSON_FIELDS.get(i));
                    }
                    index++;
                }
                require(index == expected.size(), "Missing records for current run_id");
            }
        }
    }

    private void save() throws Exception {
        Files.createDirectories(reportFile.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(reportFile.toFile(), report);
    }

    private static void require(boolean condition, String message) throws VerificationFailure {
        if (!condition) throw new VerificationFailure(message);
    }

    private static final class VerificationFailure extends Exception {
        VerificationFailure(String message) { super(message); }
    }
}
