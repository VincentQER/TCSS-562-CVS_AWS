package edu.uw.tcss562.tlq;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: java -jar target/client-sync-tlq.jar input.csv > output.json");
            System.exit(2);
        }
        try (var reader = Files.newBufferedReader(Path.of(args[0]), StandardCharsets.UTF_8)) {
            var records = new CsvTransformer().transform(reader);
            String json = new ObjectMapper().writeValueAsString(records);
            var output = new PrintWriter(System.out, false, StandardCharsets.UTF_8);
            output.println(json);
            output.flush();
            if (output.checkError()) {
                throw new java.io.IOException("Unable to write JSON to standard output");
            }
        } catch (Exception exception) {
            System.err.println("Conversion failed: " + exception.getMessage());
            System.exit(1);
        }
    }
}

