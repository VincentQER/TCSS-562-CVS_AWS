package edu.uw.tcss562.tlq;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import org.apache.commons.csv.CSVPrinter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CsvBatcherTest {
    @TempDir Path temporary;

    private Path source(int count) throws Exception {
        Path source = temporary.resolve("source.csv");
        try (var writer = Files.newBufferedWriter(source, StandardCharsets.UTF_8);
             var printer = new CSVPrinter(writer, CsvTransformer.FORMAT)) {
            printer.printRecord(PaymentSchema.HEADERS);
            for (int i = 0; i < count; i++) {
                var values = new ArrayList<>(Collections.nCopies(75, ""));
                values.set(6, "名字, \"quoted\"\r\nsecond line");
                values.set(45, Integer.toString(i));
                printer.printRecord(values);
            }
        }
        return source;
    }

    @Test void batchesLogicalRecordsAndPreservesEveryValueInOrder() throws Exception {
        Path source = source(5);
        Path out = temporary.resolve("batches");
        var report = new CsvBatcher().split(source, out, 2);
        assertEquals(5, report.totalRecords());
        assertEquals(3, report.batches().size());
        var joined = new ArrayList<Object>();
        long nextRecord = 1;
        long max = 0;
        var mapper = new ObjectMapper();
        for (var batch : report.batches()) {
            assertEquals(nextRecord, batch.firstRecord());
            assertTrue(batch.recordCount() <= 2);
            nextRecord = batch.lastRecord() + 1;
            try (var reader = Files.newBufferedReader(out.resolve(batch.csvFile()))) {
                var converted = new CsvTransformer().transform(reader);
                assertEquals(batch.recordCount(), converted.size());
                assertEquals(mapper.valueToTree(converted), mapper.readTree(out.resolve(batch.jsonFile()).toFile()));
                joined.addAll(converted);
            }
            assertEquals(Files.size(out.resolve(batch.jsonFile())), batch.jsonBytes());
            max = Math.max(max, batch.jsonBytes());
        }
        try (var reader = Files.newBufferedReader(source)) {
            assertEquals(new CsvTransformer().transform(reader), joined);
        }
        assertEquals(6, nextRecord);
        assertEquals(max, report.maxJsonBytes());
        assertTrue(Files.exists(out.resolve("manifest.json")));
    }

    @Test void headerOnlyProducesNoDataBatches() throws Exception {
        var report = new CsvBatcher().split(source(0), temporary.resolve("empty"), 200);
        assertEquals(0, report.totalRecords());
        assertEquals(0, report.maxJsonBytes());
        assertTrue(report.batches().isEmpty());
    }

    @Test void rejectsInvalidSizeAndExistingDirectory() throws Exception {
        Path source = source(1);
        Path out = temporary.resolve("new");
        assertThrows(IllegalArgumentException.class, () -> new CsvBatcher().split(source, out, 0));
        assertFalse(Files.exists(out));
        assertThrows(java.nio.file.FileAlreadyExistsException.class,
            () -> new CsvBatcher().split(source, temporary, 200));
    }
}
