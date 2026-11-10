package edu.uw.tcss562.tlq;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QueryIntegrationDiagnosticsTest {
    @Test void classifiesAuthenticationAndPermissionsWithoutMessages() {
        var auth = QueryIntegrationDiagnostics.describe("OPEN_DATABASE_AND_VALIDATE_SCHEMA",
            new SQLException("password=do-not-log; jdbc:mysql://private-host", "28000", 1045));
        assertEquals("AUTHENTICATION_FAILED", auth.get("category"));
        assertEquals("28000", auth.get("sqlState")); assertEquals(1045, auth.get("databaseErrorCode"));
        assertFalse(auth.toString().contains("do-not-log")); assertFalse(auth.toString().contains("private-host"));
        assertEquals("AUTHORIZATION_FAILED", QueryIntegrationDiagnostics.describe("OPEN_DATABASE_AND_VALIDATE_SCHEMA",
            new SQLException("sensitive SQL", "42000", 1142)).get("category"));
    }

    @Test void separatesConsoleFilesConnectionAndVersionFailures() {
        assertEquals("NO_SECURE_CONSOLE", QueryIntegrationDiagnostics.describe("SECURE_CONSOLE",
            new QueryIntegrationDiagnostics.CheckFailure("NO_SECURE_CONSOLE")).get("category"));
        assertEquals("LOCAL_FILE_ERROR", QueryIntegrationDiagnostics.describe("READ_1K_INPUT",
            new java.io.IOException("secret path")).get("category"));
        assertEquals("CONNECTION_OR_TLS_FAILED", QueryIntegrationDiagnostics.describe("OPEN_DATABASE_AND_VALIDATE_SCHEMA",
            new SQLException("secret connection info", "08001", 0)).get("category"));
        assertEquals("DATABASE_VERSION_MISMATCH", QueryIntegrationDiagnostics.describe("VERIFY_SERVER_VERSION",
            new QueryIntegrationDiagnostics.CheckFailure("DATABASE_VERSION_MISMATCH")).get("category"));
    }

    @Test void sqlStateAndServiceDiagnosticsAreSanitized() {
        var error = new JdbcQueryService.QueryFailure(JdbcQueryService.Error.DATABASE_ERROR, "42000", 1064);
        var safe = QueryIntegrationDiagnostics.describe("EXECUTE_AGGREGATE", error);
        assertEquals("DATABASE_ERROR", safe.get("category")); assertEquals(1064, safe.get("databaseErrorCode"));
        assertEquals("42000", safe.get("sqlState"));
        assertEquals("UNKNOWN", QueryIntegrationDiagnostics.safeState("password=hidden"));
        assertEquals("UNKNOWN", new JdbcQueryService.QueryFailure(JdbcQueryService.Error.DATABASE_ERROR, "password=hidden", 0).sqlState());
    }
}
