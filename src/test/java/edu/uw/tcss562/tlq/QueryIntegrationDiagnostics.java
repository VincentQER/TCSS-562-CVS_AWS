package edu.uw.tcss562.tlq;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Only allowlisted codes and metadata: never exception messages, configs or stack traces. */
final class QueryIntegrationDiagnostics {
    static final class CheckFailure extends Exception {
        final String code;
        CheckFailure(String code) { super(code); this.code = code; }
    }

    static Map<String, Object> describe(String step, Exception failure) {
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("step", step);
        String type = failure.getClass().getSimpleName();
        if (type.matches("[A-Za-z0-9_$]+")) safe.put("exceptionType", type);
        if (failure instanceof CheckFailure check) {
            safe.put("category", check.code);
            return safe;
        }
        if (failure instanceof JdbcQueryService.QueryFailure query) {
            safe.put("category", query.error().name());
            safe.put("sqlState", query.sqlState());
            safe.put("databaseErrorCode", query.databaseErrorCode());
            return safe;
        }
        if (failure instanceof SQLException sql) {
            String state = safeState(sql.getSQLState());
            int code = sql.getErrorCode();
            safe.put("sqlState", state); safe.put("databaseErrorCode", code);
            String category = code == 1045 || state.startsWith("28") ? "AUTHENTICATION_FAILED"
                : code == 1044 || code == 1142 || code == 1227 ? "AUTHORIZATION_FAILED"
                : code == 1049 ? "DATABASE_NOT_FOUND"
                : code == 1146 || code == 1054 ? "TABLE_OR_COLUMN_MISSING"
                : state.startsWith("08") ? "CONNECTION_OR_TLS_FAILED"
                : state.startsWith("42") ? "SQL_OR_PERMISSION_ERROR" : "DATABASE_PREFLIGHT_FAILED";
            safe.put("category", category);
            return safe;
        }
        safe.put("category", failure instanceof java.io.IOException ? "LOCAL_FILE_ERROR" : "INTERNAL_TEST_ERROR");
        return safe;
    }

    static String safeState(String state) {
        return state != null && state.matches("[A-Z0-9]{5}") ? state : "UNKNOWN";
    }
}
