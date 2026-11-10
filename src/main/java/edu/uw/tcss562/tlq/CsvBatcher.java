package edu.uw.tcss562.tlq;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.commons.csv.CSVPrinter;

/** Streams input by logical CSV record and validates each batch through CsvTransformer. */
public final class CsvBatcher {
    public static final int DEFAULT_BATCH_SIZE = 200;
    private final ObjectMapper mapper = new ObjectMapper();

    public record Batch(int index, long firstRecord, long lastRecord, int recordCount,
                        String csvFile, String jsonFile, long jsonBytes) {}
    public record Report(int batchSize, long totalRecords, long maxJsonBytes, List<Batch> batches) {}

    public Report split(Path source, Path outputDirectory, int batchSize) throws IOException {
        if (batchSize < 1) {
            throw new IllegalArgumentException("Batch size must be a positive integer");
        }
        List<Batch> batches = new ArrayList<>();
        long total = 0;
        long maxBytes = 0;
        try (var reader = Files.newBufferedReader(source, StandardCharsets.UTF_8);
             var parser = CsvTransformer.FORMAT.parse(reader)) {
            var iterator = parser.iterator();
            if (!iterator.hasNext()) {
                throw new IllegalArgumentException("Source has no CSV header");
            }
            CsvTransformer.validateHeader(iterator.next());
            Path absoluteOutput = outputDirectory.toAbsolutePath().normalize();
            Files.createDirectories(absoluteOutput.getParent());
            // Refuse overwrites and stale batch files from previous runs.
            Files.createDirectory(absoluteOutput);
            while (iterator.hasNext()) {
                List<Map<String, String>> expected = new ArrayList<>();
                List<List<String>> values = new ArrayList<>();
                while (expected.size() < batchSize && iterator.hasNext()) {
                    var record = iterator.next();
                    expected.add(CsvTransformer.toJsonRecord(record));
                    values.add(record.toList());
                }
                int index = batches.size() + 1;
                String stem = "batch-%06d".formatted(index);
                Path csv = absoluteOutput.resolve(stem + ".csv");
                Path json = absoluteOutput.resolve(stem + ".json");
                try (var writer = Files.newBufferedWriter(csv, StandardCharsets.UTF_8);
                     var printer = new CSVPrinter(writer, CsvTransformer.FORMAT)) {
                    printer.printRecord(PaymentSchema.HEADERS);
                    for (var row : values) {
                        printer.printRecord(row);
                    }
                }
                List<Map<String, String>> converted;
                try (var batchReader = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
                    converted = new CsvTransformer().transform(batchReader);
                }
                if (!expected.equals(converted)) {
                    throw new IllegalStateException("Batch conversion changed records: " + csv);
                }
                Files.write(json, mapper.writeValueAsBytes(converted));
                long bytes = Files.size(json);
                batches.add(new Batch(index, total + 1, total + expected.size(), expected.size(),
                    csv.getFileName().toString(), json.getFileName().toString(), bytes));
                total += expected.size();
                maxBytes = Math.max(maxBytes, bytes);
            }
            Report report = new Report(batchSize, total, maxBytes, List.copyOf(batches));
            // Only a fully validated run has a manifest.
            mapper.writerWithDefaultPrettyPrinter().writeValue(absoluteOutput.resolve("manifest.json").toFile(), report);
            return report;
        } catch (UncheckedIOException exception) {
            throw new IllegalArgumentException("Invalid CSV: " + exception.getMessage(), exception);
        }
    }
}
