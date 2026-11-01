package com.kelseyde.calvin.evaluation.activation;

import static com.kelseyde.calvin.evaluation.NNUE.NETWORK;

/** Android-compatible scalar SCReLU inference. */
public final class Screlu {

    private Screlu() { }

    public static int forward(short[] us, short[] them) {
        int qa = NETWORK.quantisations()[0];
        int qb = NETWORK.quantisations()[1];
        int qab = qa * qb;
        int scale = NETWORK.scale();
        short[] weights = NETWORK.outputWeights();
        long sum = 0L;

        for (int i = 0; i < NETWORK.hiddenSize(); i++) {
            int u = Math.max(0, Math.min(qa, us[i]));
            int t = Math.max(0, Math.min(qa, them[i]));
            short uTerm = (short) (u * weights[i]);
            short tTerm = (short) (t * weights[i + NETWORK.hiddenSize()]);
            sum += (long) u * uTerm;
            sum += (long) t * tTerm;
        }

        // Match the original integer division order to preserve NNUE scaling.
        int eval = (int) (sum / qa);
        eval += NETWORK.outputBias();
        eval *= scale;
        eval /= qab;
        return eval;
    }
}
