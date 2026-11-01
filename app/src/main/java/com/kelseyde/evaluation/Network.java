package com.kelseyde.calvin.evaluation;

import com.kelseyde.calvin.evaluation.activation.Activation;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Represents the neural network used by the engine in its evaluation function.
 * The network is loaded from the configured file on startup. The network file location, architecture, and activation
 * function are all configurable in the code via the {@link Network.Builder} builder.
 */
public record Network(int inputSize,
                      int hiddenSize,
                      Activation activation,
                      boolean horizontalMirror,
                      int[] inputBuckets,
                      int[] quantisations,
                      int scale,
                      short[][] inputWeights,
                      short[] inputBiases,
                      short[] outputWeights,
                      short outputBias) {

    public static Builder builder() {
        return new Builder();
    }

    public int inputBucketCount() {
        return inputBuckets != null ? (int) Arrays.stream(inputBuckets).distinct().count() : 1;
    }

    public static class Builder {

        private String file;
        private int inputSize;
        private int hiddenSize;
        private Activation activation;
        private boolean horizontalMirror;
        private int[] inputBuckets;
        private int[] quantisations;
        private int scale;

        public Builder file(String file) {
            this.file = file;
            return this;
        }

        public Builder inputSize(int inputSize) {
            this.inputSize = inputSize;
            return this;
        }

        public Builder activation(Activation activation) {
            this.activation = activation;
            return this;
        }

        public Builder hiddenSize(int hiddenSize) {
            this.hiddenSize = hiddenSize;
            return this;
        }

        public Builder horizontalMirror(boolean horizontalMirror) {
            this.horizontalMirror = horizontalMirror;
            return this;
        }

        public Builder inputBuckets(int[] inputBuckets) {
            this.inputBuckets = inputBuckets;
            return this;
        }

        public Builder quantisations(int[] quantisations) {
            this.quantisations = quantisations;
            return this;
        }

        public Builder scale(int scale) {
            this.scale = scale;
            return this;
        }

        public int bucketCount() {
            return inputBuckets != null ? (int) Arrays.stream(inputBuckets).distinct().count() : 1;
        }

        public Network build() {
            return loadNetwork();
        }

        public Network loadNetwork() {
            try {
                InputStream inputStream;
                File networkFile = new File(file);
                if (networkFile.isAbsolute() || networkFile.isFile()) {
                    inputStream = new FileInputStream(networkFile);
                } else {
                    inputStream = Network.class.getClassLoader().getResourceAsStream(file);
                }
                if (inputStream == null) {
                    throw new FileNotFoundException("NNUE file not found: " + file);
                }

                byte[] fileBytes;
                try (InputStream source = inputStream; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] chunk = new byte[16 * 1024];
                    int read;
                    while ((read = source.read(chunk)) != -1) {
                        bytes.write(chunk, 0, read);
                    }
                    fileBytes = bytes.toByteArray();
                }
                int buckets = bucketCount();
                long expectedBytes = ((long) inputSize * hiddenSize * buckets
                        + hiddenSize + (long) hiddenSize * 2L + 1L) * 2L;
                if (fileBytes.length != expectedBytes) {
                    throw new IOException("Invalid NNUE size: expected " + expectedBytes
                            + " bytes but found " + fileBytes.length);
                }
                ByteBuffer buffer = ByteBuffer.wrap(fileBytes).order(ByteOrder.LITTLE_ENDIAN);

                int inputWeightsOffset = inputSize * hiddenSize;
                int inputBiasesOffset = hiddenSize;
                int outputWeightsOffset = hiddenSize * 2;

                short[][] inputWeights = new short[buckets][];
                short[] inputBiases = new short[inputBiasesOffset];
                short[] outputWeights = new short[outputWeightsOffset];

                for (int bucket = 0; bucket < buckets; bucket++) {
                    inputWeights[bucket] = new short[inputWeightsOffset];
                    for (int i = 0; i < inputWeightsOffset; i++) {
                        inputWeights[bucket][i] = buffer.getShort();
                    }
                }

                for (int i = 0; i < inputBiasesOffset; i++) {
                    inputBiases[i] = buffer.getShort();
                }

                for (int i = 0; i < outputWeightsOffset; i++) {
                    outputWeights[i] = buffer.getShort();
                }

                short outputBias = buffer.getShort();

                return new Network(
                        inputSize, hiddenSize, activation, horizontalMirror, inputBuckets, quantisations, scale,
                        inputWeights, inputBiases, outputWeights, outputBias
                );
            } catch (IOException e) {
                throw new RuntimeException("Failed to load NNUE network", e);
            }
        }

    }

    public void save(String outputPath) {
        try (FileOutputStream outputStream = new FileOutputStream(outputPath)) {

            int inputWeightsOffset = inputSize * hiddenSize;
            int inputBiasesOffset = hiddenSize;
            int outputWeightsOffset = hiddenSize * 2;
            int buckets = inputBucketCount();

            ByteBuffer buffer = ByteBuffer.allocate(((inputWeightsOffset * buckets) + inputBiasesOffset + outputWeightsOffset + 1) * 2)
                    .order(ByteOrder.LITTLE_ENDIAN);

            for (int bucket = 0; bucket < buckets; bucket++) {
                for (int i = 0; i < inputWeightsOffset; i++) {
                    buffer.putShort(inputWeights[bucket][i]);
                }
            }

            for (int i = 0; i < inputBiasesOffset; i++) {
                buffer.putShort(inputBiases[i]);
            }

            for (int i = 0; i < outputWeightsOffset; i++) {
                buffer.putShort(outputWeights[i]);
            }

            buffer.putShort(outputBias);

            outputStream.write(buffer.array());

        } catch (IOException e) {
            throw new RuntimeException("Failed to save NNUE network", e);
        }
    }

}
