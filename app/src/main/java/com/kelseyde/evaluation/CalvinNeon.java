package com.kelseyde.calvin.evaluation;

/**
 * Optional ARM64 NEON kernels for Calvin's Java NNUE implementation.
 *
 * The Java search, board, move generation, and UCI code remain unchanged.
 * If the native acceleration library cannot be loaded, callers retain the
 * scalar Java implementation as a safe fallback.
 */
public final class CalvinNeon {

    private static final boolean AVAILABLE;
    private static final String LOAD_FAILURE;

    static {
        boolean loaded = false;
        String failure = "";
        String explicitPath = System.getProperty("calvin.neon.path", "").trim();
        try {
            if (!explicitPath.isEmpty()) {
                System.load(explicitPath);
            } else {
                System.loadLibrary("calvinneon");
            }
            loaded = true;
        } catch (UnsatisfiedLinkError | SecurityException error) {
            failure = error.getClass().getSimpleName() + ": " + error.getMessage();
        }
        AVAILABLE = loaded;
        LOAD_FAILURE = failure;
    }

    private CalvinNeon() { }

    public static boolean isAvailable() {
        return AVAILABLE;
    }

    public static String backendName() {
        return AVAILABLE ? "ARM64 NEON" : "Java scalar fallback (" + LOAD_FAILURE + ")";
    }

    /**
     * Computes Calvin's SCReLU output layer. Products intentionally retain
     * the original signed 16-bit multiply wrap before widening, matching the
     * arithmetic used by the upstream Java Vector implementation.
     */
    public static native int screluForwardNative(
            short[] us,
            short[] them,
            short[] outputWeights,
            int hiddenSize,
            int qa,
            int qb,
            int scale,
            short outputBias);

    /**
     * Updates one perspective's hidden accumulator in place. Offsets point
     * to complete hidden-size feature vectors inside the bucket weight array.
     * Add/subtract operations wrap exactly as signed 16-bit arithmetic does
     * in Calvin's original vector implementation.
     */
    public static native void updateFeaturesNative(
            short[] features,
            short[] weights,
            int hiddenSize,
            int addOffset0,
            int addOffset1,
            int addOffset2,
            int addOffset3,
            int subOffset0,
            int subOffset1,
            int subOffset2,
            int subOffset3,
            int addCount,
            int subCount);

    /** Updates both colour perspectives in one JNI transition. */
    public static native void updateBothFromPreviousNative(
            short[] previousWhiteFeatures,
            short[] destinationWhiteFeatures,
            short[] whiteWeights,
            short[] previousBlackFeatures,
            short[] destinationBlackFeatures,
            short[] blackWeights,
            int hiddenSize,
            int whiteAddOffset0,
            int whiteAddOffset1,
            int whiteAddOffset2,
            int whiteAddOffset3,
            int whiteSubOffset0,
            int whiteSubOffset1,
            int whiteSubOffset2,
            int whiteSubOffset3,
            int blackAddOffset0,
            int blackAddOffset1,
            int blackAddOffset2,
            int blackAddOffset3,
            int blackSubOffset0,
            int blackSubOffset1,
            int blackSubOffset2,
            int blackSubOffset3,
            int addCount,
            int subCount);
}
