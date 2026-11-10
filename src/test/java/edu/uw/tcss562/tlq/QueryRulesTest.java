package edu.uw.tcss562.tlq;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QueryRulesTest {
    @Test void acceptsOnlyProvisionalPlainDecimalGrammar() {
        for (String raw : new String[] {"0", "00.01", "-12.34", "999999999999999999.99", "-0.1"}) {
            assertEquals(new BigDecimal(raw).setScale(2), QueryRules.parseReference(raw));
        }
        for (String raw : new String[] {" ", "1\n", "1\r\n", "+1", "1e2", "1,000", "$1", "1.234", "1.", ".1", "--1", "1000000000000000000", "１２.３４"}) {
            assertThrows(IllegalArgumentException.class, () -> QueryRules.parseReference(raw));
        }
        assertNull(QueryRules.parseReference("")); assertNull(QueryRules.parseReference(null));
    }

    @Test void formatsOnlyDatabaseAggregatesAndAvoidsDoubleRounding() {
        assertEquals("12.30", QueryRules.sum(new BigDecimal("12.3")));
        assertEquals("0.3333", QueryRules.average(new BigDecimal("0.333333"), BigDecimal.ONE, 3));
        assertEquals("-0.0001", QueryRules.average(new BigDecimal("-0.000050"), new BigDecimal("-0.01"), 200));
        assertEquals("0.0000", QueryRules.average(new BigDecimal("0.000050"), BigDecimal.ONE, 20001));
        assertNull(QueryRules.sum(null)); assertNull(QueryRules.average(null, null, 0));
    }
}
