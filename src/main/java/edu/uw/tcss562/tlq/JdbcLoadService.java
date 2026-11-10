package edu.uw.tcss562.tlq;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** A single INSERT batch and a single transaction. Never retries. */
public final class JdbcLoadService {
    @FunctionalInterface
    public interface ConnectionFactory {
        Connection open() throws SQLException;
    }

    public enum Outcome { NOT_COMMITTED, UNKNOWN }

    public static final class LoadFailure extends Exception {
        private final Outcome outcome;
        private final int databaseErrorCode;
        LoadFailure(Outcome outcome) {
            this(outcome, 0);
        }
        LoadFailure(Outcome outcome, int databaseErrorCode) {
            super(outcome == Outcome.UNKNOWN ? "Commit outcome is unknown; stop this experiment and do not retry"
                : "Batch was not committed; do not automatically retry");
            this.outcome = outcome;
            this.databaseErrorCode = databaseErrorCode;
        }
        public Outcome outcome() { return outcome; }
        public int databaseErrorCode() { return databaseErrorCode; }
    }

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
    private final ConnectionFactory connections;
    private final String insertSql;

    public JdbcLoadService(ConnectionFactory connections, String table) {
        if (table == null || !IDENTIFIER.matcher(table).matches()) {
            throw new IllegalArgumentException("Invalid configured table identifier");
        }
        this.connections = connections;
        String columns = "`run_id`, " + PaymentSchema.JSON_FIELDS.stream()
            .map(field -> "`" + field + "`").collect(Collectors.joining(", "));
        this.insertSql = "INSERT INTO `" + table + "` (" + columns + ") VALUES ("
            + String.join(", ", Collections.nCopies(76, "?")) + ")";
    }

    public int load(String runId, List<Map<String, String>> records) throws LoadFailure {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("A nonblank run_id is required");
        }
        // Snapshot and validate the whole batch before opening a database connection.
        List<List<String>> rows = new ArrayList<>();
        if (records == null) throw new IllegalArgumentException("Records are required");
        for (Map<String, String> record : records) {
            if (record == null || record.size() != 75) throw new IllegalArgumentException("Expected 75 fields");
            List<String> values = new ArrayList<>();
            for (String field : PaymentSchema.JSON_FIELDS) {
                String value = record.get(field);
                if (value == null) throw new IllegalArgumentException("Expected 75 non-null string values");
                values.add(value);
            }
            rows.add(values);
        }
        if (rows.isEmpty()) return 0;
        Connection connection = null;
        boolean transactionStarted = false;
        boolean commitAttempted = false;
        try {
            connection = connections.open();
            connection.setAutoCommit(false);
            transactionStarted = true;
            try (var statement = connection.prepareStatement(insertSql)) {
                for (List<String> values : rows) {
                    statement.setString(1, runId);
                    for (int i = 0; i < 75; i++) statement.setString(i + 2, values.get(i));
                    statement.addBatch();
                }
                int[] counts = statement.executeBatch();
                if (counts.length != rows.size()) throw new SQLException("Unexpected batch result count");
                for (int count : counts) {
                    if (count != 1 && count != Statement.SUCCESS_NO_INFO) {
                        throw new SQLException("INSERT batch did not succeed completely");
                    }
                }
                // Reject warnings rather than silently accepting coercion or truncation.
                if (statement.getWarnings() != null || connection.getWarnings() != null) {
                    throw new SQLException("Database returned a warning");
                }
            }
            commitAttempted = true;
            connection.commit();
            return rows.size();
        } catch (SQLException | RuntimeException exception) {
            boolean rollbackConfirmed = !transactionStarted;
            if (transactionStarted) {
                try {
                    connection.rollback();
                    rollbackConfirmed = true;
                } catch (SQLException rollbackException) {
                    rollbackConfirmed = false;
                }
            }
            // An acknowledged rollback after a failed COMMIT does not prove COMMIT failed remotely.
            throw new LoadFailure(commitAttempted || !rollbackConfirmed ? Outcome.UNKNOWN : Outcome.NOT_COMMITTED,
                exception instanceof SQLException sqlException ? sqlException.getErrorCode() : 0);
        } finally {
            if (connection != null) {
                try { connection.close(); } catch (SQLException ignored) {
                    // A close failure must not turn an acknowledged commit into a failed response.
                }
            }
        }
    }
}
