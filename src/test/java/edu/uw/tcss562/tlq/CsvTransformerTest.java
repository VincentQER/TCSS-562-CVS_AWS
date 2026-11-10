package edu.uw.tcss562.tlq;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CsvTransformerTest {
    private String header() {
        return String.join(",", PaymentSchema.HEADERS);
    }

    @Test void preservesCsvValuesAndJsonStringTypes() throws Exception {
        var values = new ArrayList<>(Collections.nCopies(75, ""));
        values.set(6, "\"Ada, \"\"A\"\"\"");
        values.set(14, "00123");
        values.set(30, "13.78");
        values.set(31, "04/13/2018");
        values.set(45, "00042");
        var rows = new CsvTransformer().transform(new StringReader(
            header() + "\r\n" + String.join(",", values) + "\r\n"));
        var json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(rows));
        assertEquals(1, json.size());
        assertEquals(75, json.get(0).size());
        assertEquals("Ada, \"A\"", json.get(0).get("physicianFirstName").asText());
        assertEquals("00123", json.get(0).get("recipientZipCode").asText());
        assertEquals("00042", json.get(0).get("recordId").asText());
        assertEquals("04/13/2018", json.get(0).get("dateOfPayment").asText());
        assertEquals("", json.get(0).get("paymentPublicationDate").asText());
        assertTrue(json.get(0).get("totalAmountOfPaymentUsdollars").isTextual());
        assertEquals("13.78", json.get(0).get("totalAmountOfPaymentUsdollars").asText());
    }

    @Test void acceptsHeaderOnly() throws Exception {
        assertTrue(new CsvTransformer().transform(new StringReader(header())).isEmpty());
    }

    @Test void rejectsMissingOrChangedHeader() {
        assertThrows(IllegalArgumentException.class,
            () -> new CsvTransformer().transform(new StringReader("")));
        assertThrows(IllegalArgumentException.class,
            () -> new CsvTransformer().transform(new StringReader(header().replace("Change_Type", "change_type"))));
        var reordered = new ArrayList<>(PaymentSchema.HEADERS);
        Collections.swap(reordered, 0, 1);
        assertThrows(IllegalArgumentException.class,
            () -> new CsvTransformer().transform(new StringReader(String.join(",", reordered))));
    }

    @Test void rejectsBadRowsInsteadOfSkippingThem() {
        for (String bad : new String[] {"a,b", "", "\"unterminated", "\"first\nsecond\""}) {
            assertThrows(IllegalArgumentException.class,
                () -> new CsvTransformer().transform(new StringReader(header() + "\n" + bad + "\n")));
        }
    }
}

