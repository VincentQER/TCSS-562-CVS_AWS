package edu.uw.tcss562.tlq;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;

/** Single-host MySQL connection; credentials never appear in URL, output or toString(). */
public final class MysqlConnectionFactory implements JdbcLoadService.ConnectionFactory {
    private final String url;
    private final Properties properties;
    private final String table;

    private MysqlConnectionFactory(String url, Properties properties, String table) {
        this.url = url;
        this.properties = properties;
        this.table = table;
    }

    static MysqlConnectionFactory fromEnvironment(Map<String, String> environment) {
        String host = required(environment, "DB_HOST");
        String database = required(environment, "DB_NAME");
        String table = required(environment, "DB_TABLE");
        if (!host.matches("[A-Za-z0-9.-]+") || !database.matches("[A-Za-z0-9_]+")
                || !table.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) {
            throw new IllegalStateException("Invalid database host, database or table configuration");
        }
        int port;
        try { port = Integer.parseInt(environment.getOrDefault("DB_PORT", "3306")); }
        catch (NumberFormatException exception) { throw new IllegalStateException("Invalid DB_PORT"); }
        if (port < 1 || port > 65535) throw new IllegalStateException("Invalid DB_PORT");
        String sslMode = environment.getOrDefault("DB_SSL_MODE", "VERIFY_IDENTITY");
        if (!java.util.Set.of("VERIFY_IDENTITY", "VERIFY_CA", "REQUIRED", "DISABLED").contains(sslMode)) {
            throw new IllegalStateException("Invalid DB_SSL_MODE");
        }
        Properties properties = new Properties();
        properties.setProperty("user", required(environment, "DB_USER"));
        properties.setProperty("password", required(environment, "DB_PASSWORD"));
        properties.setProperty("sslMode", sslMode);
        properties.setProperty("autoReconnect", "false");
        properties.setProperty("autoReconnectForPools", "false");
        properties.setProperty("continueBatchOnError", "false");
        properties.setProperty("rewriteBatchedStatements", "false");
        properties.setProperty("useServerPrepStmts", "true");
        properties.setProperty("jdbcCompliantTruncation", "true");
        properties.setProperty("connectTimeout", "5000");
        properties.setProperty("socketTimeout", "20000");
        properties.setProperty("characterEncoding", "UTF-8");
        return new MysqlConnectionFactory("jdbc:mysql://" + host + ":" + port + "/" + database, properties, table);
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isEmpty()) throw new IllegalStateException("Missing " + name);
        return value;
    }

    String table() { return table; }

    @Override public Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(url, properties);
        try {
            // Reject typed schemas until date/amount conversions have been agreed.
            java.util.Set<String> textColumns = new java.util.HashSet<>();
            try (var columns = connection.getMetaData().getColumns(connection.getCatalog(), null, table, null)) {
                while (columns.next()) {
                    // JDBC table-name patterns may treat underscores as wildcards.
                    if (!table.equals(columns.getString("TABLE_NAME"))) continue;
                    int type = columns.getInt("DATA_TYPE");
                    if (java.util.Set.of(java.sql.Types.CHAR, java.sql.Types.VARCHAR, java.sql.Types.LONGVARCHAR,
                            java.sql.Types.NCHAR, java.sql.Types.NVARCHAR, java.sql.Types.LONGNVARCHAR).contains(type)) {
                        textColumns.add(columns.getString("COLUMN_NAME"));
                    }
                }
            }
            if (!textColumns.contains("run_id") || !textColumns.containsAll(PaymentSchema.JSON_FIELDS)) {
                throw new SQLException("Load requires run_id and all 75 fields to use textual columns");
            }
            try (var statement = connection.prepareStatement(
                    "SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?")) {
                statement.setString(1, table);
                try (var result = statement.executeQuery()) {
                    if (!result.next() || !"InnoDB".equalsIgnoreCase(result.getString(1))) {
                        throw new SQLException("Load requires an InnoDB transactional table");
                    }
                }
            }
            return connection;
        } catch (SQLException exception) {
            try { connection.close(); } catch (SQLException ignored) { }
            throw exception;
        }
    }
}
