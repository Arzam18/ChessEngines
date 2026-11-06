package com.kelseyde.calvin.evaluation;

import com.kelseyde.calvin.evaluation.activation.Activation;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.io.FilterInputStream;
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

        private InputStream openNetworkStream(String location) throws IOException {
            if (location.startsWith("apkzip:")) {
                String archiveSpec = location.substring("apkzip:".length());
                int separator = archiveSpec.indexOf("!/");
                if (separator <= 0 || separator + 2 >= archiveSpec.length()) {
                    throw new IOException("Invalid APK network location: " + location);
                }
                File apkFile = new File(archiveSpec.substring(0, separator));
                String entryName = archiveSpec.substring(separator + 2);
                ZipFile zip = new ZipFile(apkFile);
                ZipEntry entry = zip.getEntry(entryName);
                if (entry == null || entry.isDirectory()) {
                    zip.close();
                    throw new FileNotFoundException("NNUE asset " + entryName
                            + " not found in " + apkFile);
                }
                InputStream entryStream;
                try {
                    entryStream = zip.getInputStream(entry);
                } catch (IOException error) {
                    zip.close();
                    throw error;
                }
                return new FilterInputStream(entryStream) {
                    @Override
                    public void close() throws IOException {
                        IOException failure = null;
                        try {
                            super.close();
                        } catch (IOException error) {
                            failure = error;
                        }
                        try {
                            zip.close();
                        } catch (IOException error) {
                            if (failure == null) failure = error;
                            else failure.addSuppressed(error);
                        }
                        if (failure != null) throw failure;
                    }
                };
            }

            File networkFile = new File(location);
            if (networkFile.isAbsolute() || networkFile.isFile()) {
                return new FileInputStream(networkFile);
            }
            InputStream resource = Network.class.getClassLoader().getResourceAsStream(location);
            if (resource == null) {
                throw new FileNotFoundException("NNUE file not found: " + location);
            }
            return resource;
        }

        public Network loadNetwork() {
            try {
                int buckets = bucketCount();
                long expectedBytes = ((long) inputSize * hiddenSize * buckets
                        + hiddenSize + (long) hiddenSize * 2L + 1L) * 2L;
                if (expectedBytes <= 0L || expectedBytes > Integer.MAX_VALUE) {
                    throw new IOException("Invalid NNUE payload size: " + expectedBytes);
                }

                byte[] fileBytes = new byte[(int) expectedBytes];
                try (InputStream source = openNetworkStream(file)) {
                    int offset = 0;
                    while (offset < fileBytes.length) {
                        int read = source.read(fileBytes, offset, fileBytes.length - offset);
                        if (read < 0) {
                            throw new IOException("Invalid NNUE size: expected at least "
                                    + expectedBytes + " bytes but found " + offset);
                        }
                        if (read == 0) continue;
                        offset += read;
                    }
                    // A few upstream network downloads contain harmless trailing
                    // metadata/padding. Only the exact tensor payload is consumed.
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
