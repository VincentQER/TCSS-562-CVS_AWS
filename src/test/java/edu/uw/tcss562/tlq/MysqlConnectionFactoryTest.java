package edu.uw.tcss562.tlq;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MysqlConnectionFactoryTest {
    private Map<String, String> environment() {
        return new HashMap<>(Map.of("DB_HOST", "localhost", "DB_NAME", "test_db", "DB_TABLE", "payments",
            "DB_USER", "test-user", "DB_PASSWORD", "test-only-value"));
    }

    @Test void validatesConfigurationWithoutOpeningDatabaseOrDisplayingPassword() {
        var factory = MysqlConnectionFactory.fromEnvironment(environment());
        assertEquals("payments", factory.table());
        assertFalse(factory.toString().contains("test-only-value"));
        var missing = environment(); missing.remove("DB_PASSWORD");
        assertThrows(IllegalStateException.class, () -> MysqlConnectionFactory.fromEnvironment(missing));
    }

    @Test void rejectsUrlAndIdentifierInjection() {
        for (String key : new String[] {"DB_HOST", "DB_NAME", "DB_TABLE"}) {
            var bad = environment(); bad.put(key, "value?autoReconnect=true");
            assertThrows(IllegalStateException.class, () -> MysqlConnectionFactory.fromEnvironment(bad));
        }
        var badPort = environment(); badPort.put("DB_PORT", "0");
        assertThrows(IllegalStateException.class, () -> MysqlConnectionFactory.fromEnvironment(badPort));
    }
}
