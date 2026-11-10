package edu.uw.tcss562.tlq;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;

public final class BatchMain {
    private BatchMain() {}

    public static void main(String[] args) {
        if (args.length < 2 || args.length > 3) {
            System.err.println("Usage: java -cp target/client-sync-tlq.jar edu.uw.tcss562.tlq.BatchMain input.csv NEW_OUTPUT_DIR [batchSize=200]");
            System.exit(2);
        }
        try {
            int size = args.length == 3 ? Integer.parseInt(args[2]) : CsvBatcher.DEFAULT_BATCH_SIZE;
            var report = new CsvBatcher().split(Path.of(args[0]), Path.of(args[1]), size);
            System.out.println(new ObjectMapper().writeValueAsString(report));
        } catch (Exception exception) {
            System.err.println("Batching failed: " + exception.getMessage());
            System.exit(1);
        }
    }
}
