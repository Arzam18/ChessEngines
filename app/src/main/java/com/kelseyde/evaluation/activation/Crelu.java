package com.kelseyde.calvin.evaluation.activation;

import static com.kelseyde.calvin.evaluation.NNUE.NETWORK;

/** Android-compatible scalar CReLU inference. */
public final class Crelu {

    private Crelu() { }

    public static int forward(short[] us, short[] them) {
        int qa = NETWORK.quantisations()[0];
        int qb = NETWORK.quantisations()[1];
        int qab = qa * qb;
        short[] weights = NETWORK.outputWeights();
        int eval = NETWORK.outputBias();

        // Preserve short-width product semantics used by the original vector implementation.
        // Each 128-bit vector reduction on ARM NEON held eight short lanes.
        for (int base = 0; base < NETWORK.hiddenSize(); base += 8) {
            short usPartial = 0;
            short themPartial = 0;
            for (int lane = 0; lane < 8 && base + lane < NETWORK.hiddenSize(); lane++) {
                int i = base + lane;
                short u = (short) Math.max(0, Math.min(qa, us[i]));
                short t = (short) Math.max(0, Math.min(qa, them[i]));
                usPartial = (short) (usPartial + (short) (u * weights[i]));
                themPartial = (short) (themPartial + (short) (t * weights[i + NETWORK.hiddenSize()]));
            }
            eval += usPartial;
            eval += themPartial;
        }
        eval *= NETWORK.scale();
        eval /= qab;
        return eval;
    }
}
