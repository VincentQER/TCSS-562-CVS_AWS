package edu.uw.tcss562.tlq;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** JDBC doubles test orchestration only; SQL execution needs the manual real-MySQL runner. */
class JdbcQueryServiceTest {
    static Map<String, Object> aggregate(long count, long amountCount, String sum, String avg, long invalid) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("row_count", count); row.put("amount_count", amountCount); row.put("invalid_count", invalid);
        row.put("amount_sum", sum == null ? null : new BigDecimal(sum));
        row.put("amount_avg", avg == null ? null : new BigDecimal(avg));
        row.put("state", "WA"); row.put("year", "2018");
        return row;
    }
    static class FakeJdbc implements JdbcLoadService.ConnectionFactory {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> parameters = new ArrayList<>();
        int opens, queries, index = -1;
        boolean readOnly, warning, fail;
        String sql;
        @Override public Connection open() {
            opens++;
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setReadOnly" -> { readOnly = (boolean) args[0]; yield null; }
                    case "prepareStatement" -> { sql = (String) args[0]; yield statement(); }
                    case "getWarnings", "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        }
        PreparedStatement statement() {
            return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {PreparedStatement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setString" -> { parameters.add((String) args[1]); yield null; }
                    case "executeQuery" -> { queries++; if (fail) throw new SQLException("password=must-not-leak", "42000", 1064); yield result(); }
                    case "getWarnings" -> warning ? new SQLWarning("Conversion warning") : null;
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        }
        ResultSet result() {
            return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> ++index < rows.size();
                    case "getString", "getBigDecimal" -> rows.get(index).get((String) args[0]);
                    case "getLong" -> ((Number) rows.get(index).get((String) args[0])).longValue();
                    case "getWarnings", "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        }
    }

    @Test void buildsOneParameterizedServerAggregateAndReadsOnlyGroups() throws Exception {
        var db = new FakeJdbc(); db.rows.add(aggregate(3, 2, "30.00", "15.000000", 0));
        var request = QueryRequest.parse("run' OR 1=1 --", "{\"filters\":{\"state\":\"WA' --\",\"year\":\"2018\"},\"groupBy\":[\"state\",\"year\"]}");
        var rows = new JdbcQueryService(db, "payments_raw").query(request);
        assertEquals(1, rows.size()); assertEquals(3L, rows.getFirst().get("count"));
        assertEquals(2L, rows.getFirst().get("amountCount")); assertEquals("30.00", rows.getFirst().get("sum"));
        assertEquals("15.0000", rows.getFirst().get("avg"));
        assertTrue(db.readOnly); assertEquals(1, db.queries); assertEquals(1, db.opens);
        assertTrue(db.sql.contains("GROUP BY `state`, `year`")); assertTrue(db.sql.contains("COUNT(*)"));
        assertTrue(db.sql.contains("SUM(amount_decimal)")); assertTrue(db.sql.contains("AVG(amount_decimal)"));
        assertTrue(db.sql.contains("CASE WHEN amount_status = 1 THEN CAST(raw_amount AS DECIMAL(20,2)) ELSE NULL END"));
        assertTrue(db.sql.contains("OCTET_LENGTH(raw_amount) = 0"));
        assertFalse(db.sql.contains("DISTINCT")); assertFalse(db.sql.contains(request.runId())); assertFalse(db.sql.contains(request.state()));
        assertEquals(List.of(request.runId(), request.state(), "2018", QueryRules.MYSQL_PATTERN, QueryRules.MYSQL_FORBIDDEN), db.parameters);
    }

    @Test void invalidAmountInLaterGroupFailsWholeRequest() {
        var db = new FakeJdbc(); db.rows.add(aggregate(1, 1, "1", "1", 0)); db.rows.add(aggregate(1, 0, null, null, 1));
        var error = assertThrows(JdbcQueryService.QueryFailure.class,
            () -> new JdbcQueryService(db, "payments_raw").query(QueryRequest.parse("run", "{\"groupBy\":[\"state\"]}")));
        assertEquals(JdbcQueryService.Error.INVALID_AMOUNT, error.error());
    }

    @Test void countOnlyDoesNotCastOrRejectUnvalidatedAmounts() throws Exception {
        var db = new FakeJdbc(); db.rows.add(aggregate(4, 0, null, null, 4));
        assertEquals(Map.of("count", 4L), new JdbcQueryService(db, "payments_raw").query(
            QueryRequest.parse("run", "{\"metrics\":[\"count\"]}")).getFirst());
        assertFalse(db.sql.contains("CAST(")); assertFalse(db.sql.contains("REGEXP_LIKE"));
        assertEquals(List.of("run"), db.parameters);
    }

    @Test void emptyAndAllMissingAggregatesAreNull() throws Exception {
        var db = new FakeJdbc(); db.rows.add(aggregate(0, 0, null, null, 0));
        var row = new JdbcQueryService(db, "payments_raw").query(QueryRequest.parse("run", "{}")).getFirst();
        assertEquals(0L, row.get("count")); assertNull(row.get("sum")); assertNull(row.get("avg"));
        var empty = new FakeJdbc();
        assertTrue(new JdbcQueryService(empty, "payments_raw").query(QueryRequest.parse("run", "{\"groupBy\":[\"year\"]}")).isEmpty());
    }

    @Test void warningsAndDatabaseErrorsNeverReturnPartialSuccessOrSecrets() {
        for (boolean warning : new boolean[] {true, false}) {
            var db = new FakeJdbc(); db.warning = warning; db.fail = !warning; db.rows.add(aggregate(1, 1, "1", "1", 0));
            var error = assertThrows(JdbcQueryService.QueryFailure.class,
                () -> new JdbcQueryService(db, "payments_raw").query(QueryRequest.parse("run", "{}")));
            assertEquals(JdbcQueryService.Error.DATABASE_ERROR, error.error());
            if (!warning) { assertEquals("42000", error.sqlState()); assertEquals(1064, error.databaseErrorCode()); }
            assertFalse(error.toString().contains("must-not-leak")); assertEquals(1, db.queries);
        }
        assertThrows(IllegalArgumentException.class, () -> new JdbcQueryService(new FakeJdbc(), "payments;DROP"));
    }
}
