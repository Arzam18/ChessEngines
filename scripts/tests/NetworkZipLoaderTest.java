package com.kelseyde.calvin.evaluation;

public final class NetworkZipLoaderTest {
    public static void main(String[] args) {
        if (args.length != 1) throw new IllegalArgumentException("Expected APK test archive path");
        String location = "apkzip:" + args[0] + "!/assets/mock.nnue";
        Network network = Network.builder()
                .file(location)
                .inputSize(3)
                .hiddenSize(2)
                .build();

        short[] inputs = network.inputWeights()[0];
        if (inputs.length != 6) throw new AssertionError("Input tensor length mismatch");
        for (int i = 0; i < inputs.length; i++) {
            if (inputs[i] != i + 1) throw new AssertionError("Input tensor mismatch at " + i);
        }
        if (network.inputBiases()[0] != 7 || network.inputBiases()[1] != 8) {
            throw new AssertionError("Input bias mismatch");
        }
        for (int i = 0; i < network.outputWeights().length; i++) {
            if (network.outputWeights()[i] != i + 9) {
                throw new AssertionError("Output tensor mismatch at " + i);
            }
        }
        if (network.outputBias() != 13) throw new AssertionError("Output bias mismatch");
        System.out.println("PASS: APK ZIP NNUE loader reads tensor payload and ignores trailing metadata");
    }
}
