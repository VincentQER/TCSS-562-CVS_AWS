package edu.uw.tcss562.tlq;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** JDBC test double, not a MySQL server. */
class JdbcLoadServiceTest {
    static Map<String, String> row() {
        Map<String, String> row = new LinkedHashMap<>();
        PaymentSchema.JSON_FIELDS.forEach(field -> row.put(field, ""));
        row.put("recordId", "00042");
        row.put("dateOfPayment", "2/30/2018");
        row.put("totalAmountOfPaymentUsdollars", " not-yet-validated ");
        row.put("physicianFirstName", "O'Brien'); DROP TABLE payments; --");
        return row;
    }

    static class FakeJdbc implements JdbcLoadService.ConnectionFactory {
        int opens, executions, commits, rollbacks, closes;
        boolean failExecute, failCommit, failRollback, failClose, warning, noInfo, failedCount;
        boolean autoCommit = true;
        String sql;
        Map<Integer, String> parameters = new LinkedHashMap<>();
        List<Map<Integer, String>> batches = new ArrayList<>();
        List<Map<Integer, String>> staged = new ArrayList<>();
        List<Map<Integer, String>> stored = new ArrayList<>();

        @Override public Connection open() {
            opens++;
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setAutoCommit" -> { autoCommit = (boolean) args[0]; yield null; }
                    case "prepareStatement" -> { sql = (String) args[0]; yield statement(); }
                    case "commit" -> {
                        commits++;
                        stored.addAll(staged); staged.clear();
                        if (failCommit) throw new SQLException("Lost COMMIT acknowledgement with sensitive details");
                        yield null;
                    }
                    case "rollback" -> {
                        rollbacks++;
                        if (failRollback) throw new SQLException("rollback failed");
                        staged.clear(); yield null;
                    }
                    case "close" -> { closes++; if (failClose) throw new SQLException("close failed"); yield null; }
                    case "getWarnings" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        }

        private PreparedStatement statement() {
            return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {PreparedStatement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setString" -> { parameters.put((int) args[0], (String) args[1]); yield null; }
                    case "addBatch" -> { batches.add(new LinkedHashMap<>(parameters)); yield null; }
                    case "executeBatch" -> {
                        executions++;
                        if (failExecute) { staged.add(batches.getFirst()); throw new java.sql.BatchUpdateException("batch failed", "HY000", 3819, new int[] {1}); }
                        staged.addAll(batches);
                        int[] counts = new int[batches.size()];
                        Arrays.fill(counts, failedCount ? Statement.EXECUTE_FAILED : noInfo ? Statement.SUCCESS_NO_INFO : 1);
                        yield counts;
                    }
                    case "getWarnings" -> warning ? new SQLWarning("Data truncated") : null;
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        }
    }

    @Test void bindsAllValuesPreservesDuplicateRowsAndCommitsOnce() throws Exception {
        var db = new FakeJdbc();
        String run = "run-' OR 1=1 --";
        assertEquals(2, new JdbcLoadService(db, "payments").load(run, List.of(row(), row())));
        assertFalse(db.autoCommit);
        assertEquals(2, db.stored.size());
        assertEquals(db.stored.get(0), db.stored.get(1));
        assertEquals(76, db.stored.getFirst().size());
        assertEquals(run, db.stored.getFirst().get(1));
        for (int i = 0; i < 75; i++) assertEquals(row().get(PaymentSchema.JSON_FIELDS.get(i)), db.stored.getFirst().get(i + 2));
        assertEquals(76, db.sql.chars().filter(ch -> ch == '?').count());
        assertTrue(db.sql.startsWith("INSERT INTO `payments`"));
        assertFalse(db.sql.contains(run));
        assertFalse(db.sql.contains("DROP TABLE"));
        assertEquals(1, db.opens);
        assertEquals(1, db.executions);
        assertEquals(1, db.commits);
        assertEquals(0, db.rollbacks);
        assertEquals(1, db.closes);
    }

    @Test void failedPartialBatchRollsBackEverythingWithoutRetry() {
        var db = new FakeJdbc(); db.failExecute = true;
        var error = assertThrows(JdbcLoadService.LoadFailure.class,
            () -> new JdbcLoadService(db, "payments").load("run1", List.of(row(), row())));
        assertEquals(JdbcLoadService.Outcome.NOT_COMMITTED, error.outcome());
        assertEquals(3819, error.databaseErrorCode());
        assertTrue(db.stored.isEmpty()); assertTrue(db.staged.isEmpty());
        assertEquals(1, db.rollbacks); assertEquals(0, db.commits);
        assertEquals(1, db.opens); assertEquals(1, db.executions);
    }

    @Test void lostCommitAcknowledgementRemainsUnknownEvenAfterRollback() {
        var db = new FakeJdbc(); db.failCommit = true;
        var error = assertThrows(JdbcLoadService.LoadFailure.class,
            () -> new JdbcLoadService(db, "payments").load("run1", List.of(row())));
        assertEquals(JdbcLoadService.Outcome.UNKNOWN, error.outcome());
        assertEquals(1, db.stored.size()); assertEquals(1, db.rollbacks);
        assertEquals(1, db.commits); assertEquals(1, db.opens);
        assertFalse(error.getMessage().contains("sensitive"));
    }

    @Test void rollbackFailureIsUnknown() {
        var db = new FakeJdbc(); db.failExecute = true; db.failRollback = true;
        assertEquals(JdbcLoadService.Outcome.UNKNOWN, assertThrows(JdbcLoadService.LoadFailure.class,
            () -> new JdbcLoadService(db, "payments").load("run1", List.of(row()))).outcome());
    }

    @Test void closeFailureDoesNotHideConfirmedCommit() throws Exception {
        var db = new FakeJdbc(); db.failClose = true;
        assertEquals(1, new JdbcLoadService(db, "payments").load("run1", List.of(row())));
        assertEquals(1, db.stored.size());
    }

    @Test void supportsSuccessNoInfoAndRejectsFailedCountsOrWarnings() throws Exception {
        var good = new FakeJdbc(); good.noInfo = true;
        assertEquals(1, new JdbcLoadService(good, "payments").load("run1", List.of(row())));
        for (boolean warning : new boolean[] {false, true}) {
            var bad = new FakeJdbc(); bad.warning = warning; bad.failedCount = !warning;
            assertThrows(JdbcLoadService.LoadFailure.class, () -> new JdbcLoadService(bad, "payments").load("run1", List.of(row())));
            assertTrue(bad.stored.isEmpty()); assertEquals(1, bad.rollbacks);
        }
    }

    @Test void validatesEntireBatchBeforeOpeningConnection() throws Exception {
        var db = new FakeJdbc();
        var service = new JdbcLoadService(db, "payments");
        var bad = row(); bad.remove("recordId");
        assertThrows(IllegalArgumentException.class, () -> service.load("run", List.of(row(), bad)));
        assertThrows(IllegalArgumentException.class, () -> service.load(" ", List.of(row())));
        assertEquals(0, service.load("run", List.of()));
        assertEquals(0, db.opens);
        assertThrows(IllegalArgumentException.class, () -> new JdbcLoadService(db, "payments; DROP TABLE x"));
    }

    @Test void connectionFailureNeverRetries() {
        int[] attempts = {0};
        var service = new JdbcLoadService(() -> { attempts[0]++; throw new SQLException("secret must not escape"); }, "payments");
        var failure = assertThrows(JdbcLoadService.LoadFailure.class, () -> service.load("run", List.of(row())));
        assertEquals(JdbcLoadService.Outcome.NOT_COMMITTED, failure.outcome());
        assertEquals(1, attempts[0]); assertFalse(failure.getMessage().contains("secret"));
    }
}
