package com.kelseyde.calvin.evaluation.activation;

import com.kelseyde.calvin.evaluation.CalvinNeon;

import static com.kelseyde.calvin.evaluation.NNUE.NETWORK;

/** SCReLU inference with an ARM64 NEON kernel and an exact Java fallback. */
public final class Screlu {

    private Screlu() { }

    public static int forward(short[] us, short[] them) {
        int qa = NETWORK.quantisations()[0];
        int qb = NETWORK.quantisations()[1];
        int scale = NETWORK.scale();
        short[] weights = NETWORK.outputWeights();

        if (CalvinNeon.isAvailable()) {
            return CalvinNeon.screluForwardNative(
                    us, them, weights, NETWORK.hiddenSize(), qa, qb, scale, NETWORK.outputBias());
        }
        return forwardScalar(us, them, weights, NETWORK.hiddenSize(),
                qa, qb, scale, NETWORK.outputBias());
    }

    /** Kept as the correctness fallback and reference implementation. */
    static int forwardScalar(short[] us, short[] them, short[] weights, int hiddenSize,
                             int qa, int qb, int scale, short outputBias) {
        int qab = qa * qb;
        long sum = 0L;

        for (int i = 0; i < hiddenSize; i++) {
            int u = Math.max(0, Math.min(qa, us[i]));
            int t = Math.max(0, Math.min(qa, them[i]));
            short uTerm = (short) (u * weights[i]);
            short tTerm = (short) (t * weights[i + hiddenSize]);
            sum += (long) u * uTerm;
            sum += (long) t * tTerm;
        }

        int eval = (int) (sum / qa);
        eval += outputBias;
        eval *= scale;
        eval /= qab;
        return eval;
    }
}
