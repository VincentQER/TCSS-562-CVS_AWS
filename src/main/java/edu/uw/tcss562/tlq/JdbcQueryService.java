package edu.uw.tcss562.tlq;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Read-only server-side aggregation. No SQL values or exceptions are logged. */
public final class JdbcQueryService {
    public enum Error { INVALID_AMOUNT, DATABASE_ERROR, RESULT_TOO_LARGE }
    public static final class QueryFailure extends Exception {
        private final Error error;
        private final String sqlState;
        private final int databaseErrorCode;
        QueryFailure(Error error) { this(error, "UNKNOWN", 0); }
        QueryFailure(Error error, String sqlState, int databaseErrorCode) {
            super(error.name()); this.error = error;
            this.sqlState = sqlState != null && sqlState.matches("[A-Z0-9]{5}") ? sqlState : "UNKNOWN";
            this.databaseErrorCode = databaseErrorCode;
        }
        public Error error() { return error; }
        public String sqlState() { return sqlState; }
        public int databaseErrorCode() { return databaseErrorCode; }
    }
    record Plan(String sql, List<String> parameters) {}
    private final JdbcLoadService.ConnectionFactory connections;
    private final String table;

    public JdbcQueryService(JdbcLoadService.ConnectionFactory connections, String table) {
        if (table == null || !table.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) throw new IllegalArgumentException("Invalid table identifier");
        this.connections = connections; this.table = table;
    }

    Plan plan(QueryRequest request) {
        List<String> parameters = new ArrayList<>(); parameters.add(request.runId());
        String where = "`run_id` = ?";
        if (request.state() != null) { where += " AND `recipientState` = ?"; parameters.add(request.state()); }
        if (request.year() != null) { where += " AND `programYear` = ?"; parameters.add(request.year()); }
        String sql = "WITH filtered AS (SELECT `recipientState` AS `state`, `programYear` AS `year`, "
            + "`totalAmountOfPaymentUsdollars` AS raw_amount FROM `" + table + "` WHERE " + where + ")";
        if (request.needsAmounts()) {
            sql += ", classified AS (SELECT *, " + QueryRules.statusSql() + " AS amount_status FROM filtered)"
                + ", typed AS (SELECT *, " + QueryRules.decimalSql() + " AS amount_decimal FROM classified)";
            parameters.add(QueryRules.MYSQL_PATTERN); parameters.add(QueryRules.MYSQL_FORBIDDEN);
        }
        String groups = request.groupBy().stream().map(group -> "`" + group.key + "`").collect(Collectors.joining(", "));
        sql += " SELECT " + (groups.isEmpty() ? "" : groups + ", ") + "COUNT(*) AS row_count";
        if (request.needsAmounts()) {
            sql += ", COUNT(amount_decimal) AS amount_count, SUM(amount_decimal) AS amount_sum, AVG(amount_decimal) AS amount_avg, "
                + "COALESCE(SUM(CASE WHEN amount_status = 2 THEN 1 ELSE 0 END), 0) AS invalid_count";
        }
        sql += " FROM " + (request.needsAmounts() ? "typed" : "filtered");
        if (!groups.isEmpty()) sql += " GROUP BY " + groups + " ORDER BY " + groups;
        return new Plan(sql, List.copyOf(parameters));
    }

    public List<Map<String, Object>> query(QueryRequest request) throws QueryFailure {
        Plan plan = plan(request);
        List<Map<String, Object>> rows = new ArrayList<>();
        try (var connection = connections.open()) {
            connection.setReadOnly(true);
            try (var statement = connection.prepareStatement(plan.sql())) {
                for (int i = 0; i < plan.parameters().size(); i++) statement.setString(i + 1, plan.parameters().get(i));
                try (var result = statement.executeQuery()) {
                    while (result.next()) {
                        if (rows.size() >= QueryRules.MAX_GROUPS) throw new QueryFailure(Error.RESULT_TOO_LARGE);
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (var dimension : request.groupBy()) row.put(dimension.key, result.getString(dimension.key));
                        if (request.metrics().contains(QueryRequest.Metric.COUNT)) row.put("count", result.getLong("row_count"));
                        if (request.needsAmounts()) {
                            if (result.getLong("invalid_count") > 0) throw new QueryFailure(Error.INVALID_AMOUNT);
                            long count = result.getLong("amount_count");
                            var sum = result.getBigDecimal("amount_sum");
                            var average = result.getBigDecimal("amount_avg");
                            row.put("amountCount", count);
                            if (request.metrics().contains(QueryRequest.Metric.SUM)) row.put("sum", QueryRules.sum(sum));
                            if (request.metrics().contains(QueryRequest.Metric.AVG)) row.put("avg", QueryRules.average(average, sum, count));
                        }
                        rows.add(row);
                    }
                    if (result.getWarnings() != null) throw new QueryFailure(Error.DATABASE_ERROR);
                }
                if (statement.getWarnings() != null || connection.getWarnings() != null) throw new QueryFailure(Error.DATABASE_ERROR);
            }
            return rows;
        } catch (SQLException exception) {
            throw new QueryFailure(Error.DATABASE_ERROR, exception.getSQLState(), exception.getErrorCode());
        } catch (IllegalArgumentException exception) {
            throw new QueryFailure(Error.DATABASE_ERROR);
        }
    }
}
