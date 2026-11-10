package edu.uw.tcss562.tlq;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;

/** Provisional business rules: change here when the team agrees on a new contract. */
public final class QueryRules {
    public static final String VERSION = "provisional-v1";
    public static final int PRECISION = 20;
    public static final int SCALE = 2;
    public static final int AVG_SCALE = 4;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    public static final int MAX_GROUPS = 10_000;
    public static final String MYSQL_PATTERN = "^-?[0-9]{1,18}([.][0-9]{1,2})?$";
    public static final String MYSQL_FORBIDDEN = "[^0-9.-]";
    private static final Pattern JAVA_PATTERN = Pattern.compile("\\A-?[0-9]{1,18}(?:[.][0-9]{1,2})?\\z");
    private QueryRules() {}

    public static String validSql(String column) {
        return "REGEXP_LIKE(" + column + ", ?, 'c') AND NOT REGEXP_LIKE(" + column + ", ?, 'c')";
    }

    static String statusSql() {
        return "CASE WHEN raw_amount IS NULL OR OCTET_LENGTH(raw_amount) = 0 THEN 0 WHEN "
            + validSql("raw_amount") + " THEN 1 ELSE 2 END";
    }

    static String decimalSql() {
        return "CASE WHEN amount_status = 1 THEN CAST(raw_amount AS DECIMAL(" + PRECISION + "," + SCALE + ")) ELSE NULL END";
    }

    public static BigDecimal parseReference(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        if (!JAVA_PATTERN.matcher(raw).matches()) throw new IllegalArgumentException("Invalid amount");
        return new BigDecimal(raw).setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    public static String sum(BigDecimal value) {
        return value == null ? null : value.setScale(SCALE, RoundingMode.UNNECESSARY).toPlainString();
    }

    /** Format database aggregates only; never aggregates individual rows in Java. */
    public static String average(BigDecimal sqlAverage, BigDecimal sum, long amountCount) {
        if (amountCount == 0) return null;
        if (sqlAverage == null || sum == null) throw new IllegalArgumentException("Missing database aggregates");
        // SUM/count avoids double-rounding a server AVG with a session-dependent intermediate scale.
        return sum.divide(BigDecimal.valueOf(amountCount), AVG_SCALE, ROUNDING).toPlainString();
    }
}
