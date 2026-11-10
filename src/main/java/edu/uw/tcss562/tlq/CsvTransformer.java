package edu.uw.tcss562.tlq;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/** Local conversion only; supports quoted embedded newlines. */
public final class CsvTransformer {
    static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder().setIgnoreEmptyLines(false).build();

    public List<Map<String, String>> transform(Reader input) throws IOException {
        List<Map<String, String>> records = new ArrayList<>();
        try (CSVParser parser = FORMAT.parse(input)) {
            var iterator = parser.iterator();
            if (!iterator.hasNext()) {
                throw new IllegalArgumentException("Source has no CSV header");
            }
            validateHeader(iterator.next());
            while (iterator.hasNext()) {
                records.add(toJsonRecord(iterator.next()));
            }
        } catch (UncheckedIOException exception) {
            throw new IllegalArgumentException("Invalid CSV: " + exception.getMessage(), exception);
        }
        return records;
    }

    static void validateHeader(CSVRecord header) {
        if (!PaymentSchema.HEADERS.equals(header.toList())) {
            throw new IllegalArgumentException("CSV header must match the fixed 75-column schema exactly");
        }
    }

    static Map<String, String> toJsonRecord(CSVRecord record) {
        if (record.size() != PaymentSchema.HEADERS.size()) {
            throw new IllegalArgumentException("CSV record %d has %d columns; expected 75"
                .formatted(record.getRecordNumber(), record.size()));
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < record.size(); i++) {
            result.put(PaymentSchema.JSON_FIELDS.get(i), record.get(i));
        }
        return result;
    }
}

