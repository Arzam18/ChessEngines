package com.kelseyde.calvin.utils.train;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

public class DataDebugger {

    private static final int BATCH_SIZE = 10000;

    private int count = 0;
    private int filtered = 0;
    private final List<String> batch = new ArrayList<>();

    public void score() throws IOException {

        File inputPath = new File("/Users/kelseyde/git/dan/calvin/data/calvindata_12.txt");
        File outputPath = new File("/Users/kelseyde/git/dan/calvin/data/calvindata_12_filtered.txt");
        if (!outputPath.exists()) {
            outputPath.createNewFile();
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(inputPath))) {
            String line;
            while ((line = reader.readLine()) != null) {

                count++;
                if (count % 1000000 == 0) {
                    System.out.printf("count: %d, filtered: %d\n", count, filtered);
                }

                String[] parts = line.split("\\|");
                String fen = parts[0].trim();
                int score = Integer.parseInt(parts[1].trim());
                String result = parts[2].trim();

                boolean isTooHigh = Math.abs(score) >= 10000;
                boolean isBadResult = (score >= 1000 && result.equalsIgnoreCase("0.0"))
                        || (score <= -1000 && result.equalsIgnoreCase("1.0"));

                if (isTooHigh || isBadResult) {
                    filtered++;
                    continue;
                }

                batch.add(line);

                if (batch.size() == BATCH_SIZE) {
                    try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputPath, true))) {
                        for (String item : batch) {
                            writer.write(item);
                            writer.newLine();
                        }
                    }
                    batch.clear();
                }


            }

        } catch (IOException e) {
            throw new RuntimeException("Failed to read input file", e);
        }

        System.out.println("total positions: " + count);
        System.out.printf("filtered positions: %d\n", filtered);

    }

    public static void main(String[] args) throws IOException {
        DataDebugger dataDebugger = new DataDebugger();
        dataDebugger.score();
    }

}
