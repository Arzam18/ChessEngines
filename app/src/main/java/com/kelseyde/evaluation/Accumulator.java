package com.kelseyde.calvin.evaluation;

import com.kelseyde.calvin.board.Colour;

/**
 * The accumulator keeps track of the activations of the hidden layer of the neural network. It is incrementally updated
 * during search to avoid recomputing the entire network each time evaluation is called. The activations are accumulated
 * from both white's and black's perspective, so that during evaluation the 'side to move' and 'not side to move' can be
 * easily flipped.
 * </p>
 * Android uses ARM64 NEON kernels when available and retains the scalar Java path as fallback.
 */
public class Accumulator {

    private static final int HIDDEN_SIZE = NNUE.NETWORK.hiddenSize();
    private static final short[] BIASES = NNUE.NETWORK.inputBiases();
    private static final int LOOP_LENGTH = HIDDEN_SIZE;

    public short[] whiteFeatures;
    public short[] blackFeatures;
    public final boolean[] mirrored;

    public Accumulator(int featureCount) {
        this.whiteFeatures = new short[featureCount];
        this.blackFeatures = new short[featureCount];
        this.mirrored = new boolean[2];
    }

    public void add(short[] weights, Feature feature, boolean whitePerspective) {
        // Add a single feature to the accumulator.
        boolean mirror = mirrored[Colour.index(whitePerspective)];
        int offset = feature.index(whitePerspective, mirror) * HIDDEN_SIZE;
        short[] features = whitePerspective ? whiteFeatures : blackFeatures;
        if (CalvinNeon.isAvailable()) {
            CalvinNeon.updateFeaturesNative(features, weights, HIDDEN_SIZE,
                    offset, -1, -1, -1, -1, -1, -1, -1, 1, 0);
            return;
        }

        for (int i = 0; i < LOOP_LENGTH; i++) {

            short value = features[i];
            value = (short) (value + weights[i + offset]);
            features[i] = value;

        }
    }

    public void sub(short[] weights, Feature feature, boolean whitePerspective) {
        // Subtract a single feature from the accumulator.
        boolean mirror = mirrored[Colour.index(whitePerspective)];
        int offset = feature.index(whitePerspective, mirror) * HIDDEN_SIZE;
        short[] features = whitePerspective ? whiteFeatures : blackFeatures;
        if (CalvinNeon.isAvailable()) {
            CalvinNeon.updateFeaturesNative(features, weights, HIDDEN_SIZE,
                    -1, -1, -1, -1, offset, -1, -1, -1, 0, 1);
            return;
        }

        for (int i = 0; i < LOOP_LENGTH; i++) {

            short value = features[i];
            value = (short) (value - weights[i + offset]);
            features[i] = value;

        }
    }

    public void addAddAddAdd(short[] weights, Feature feat1, Feature feat2, Feature feat3, Feature feat4, boolean whitePerspective) {
        // Add a quartet of features to the accumulator.
        boolean mirror = mirrored[Colour.index(whitePerspective)];
        int offset1 = feat1.index(whitePerspective, mirror) * HIDDEN_SIZE;
        int offset2 = feat2.index(whitePerspective, mirror) * HIDDEN_SIZE;
        int offset3 = feat3.index(whitePerspective, mirror) * HIDDEN_SIZE;
        int offset4 = feat4.index(whitePerspective, mirror) * HIDDEN_SIZE;
        short[] features = whitePerspective ? whiteFeatures : blackFeatures;
        if (CalvinNeon.isAvailable()) {
            CalvinNeon.updateFeaturesNative(features, weights, HIDDEN_SIZE,
                    offset1, offset2, offset3, offset4, -1, -1, -1, -1, 4, 0);
            return;
        }

        for (int i = 0; i < LOOP_LENGTH; i++) {

            short value = features[i];
            value = (short) (value + weights[i + offset1]);
            value = (short) (value + weights[i + offset2]);
            value = (short) (value + weights[i + offset3]);
            value = (short) (value + weights[i + offset4]);
            features[i] = value;

        }
    }

    public void subSubSubSub(short[] weights, Feature feat1, Feature feat2, Feature feat3, Feature feat4, boolean whitePerspective) {
        // Subtract a quartet of features from the accumulator.
        boolean mirror = mirrored[Colour.index(whitePerspective)];
        int offset1 = feat1.index(whitePerspective, mirror) * HIDDEN_SIZE;
        int offset2 = feat2.index(whitePerspective, mirror) * HIDDEN_SIZE;
        int offset3 = feat3.index(whitePerspective, mirror) * HIDDEN_SIZE;
        int offset4 = feat4.index(whitePerspective, mirror) * HIDDEN_SIZE;
        short[] features = whitePerspective ? whiteFeatures : blackFeatures;
        if (CalvinNeon.isAvailable()) {
            CalvinNeon.updateFeaturesNative(features, weights, HIDDEN_SIZE,
                    -1, -1, -1, -1, offset1, offset2, offset3, offset4, 0, 4);
            return;
        }

        for (int i = 0; i < LOOP_LENGTH; i++) {

            short value = features[i];
            value = (short) (value - weights[i + offset1]);
            value = (short) (value - weights[i + offset2]);
            value = (short) (value - weights[i + offset3]);
            value = (short) (value - weights[i + offset4]);
            features[i] = value;

        }
    }

    public void apply(Accumulator prev, AccumulatorUpdate update, short[] whiteWeights, short[] blackWeights) {
        // Accumulator updates are 'fused' together, so that multiple feature updates can be applied in a single pass.
        switch (update.getUpdateType()) {
            case ADD -> add(prev, update, whiteWeights, blackWeights);
            case ADD_SUB -> addSub(prev, update, whiteWeights, blackWeights);
            case ADD_SUB_SUB -> addSubSub(prev, update, whiteWeights, blackWeights);
            case ADD_ADD_SUB_SUB -> addAddSubSub(prev, update, whiteWeights, blackWeights);
        }
    }

    private void updateBothFromPrevious(Accumulator prev,
                                        short[] whiteWeights,
                                        short[] blackWeights,
                                        int whiteAdd0, int whiteAdd1,
                                        int whiteSub0, int whiteSub1,
                                        int blackAdd0, int blackAdd1,
                                        int blackSub0, int blackSub1,
                                        int addCount, int subCount) {
        CalvinNeon.updateBothFromPreviousNative(
                prev.whiteFeatures, whiteFeatures, whiteWeights,
                prev.blackFeatures, blackFeatures, blackWeights,
                HIDDEN_SIZE,
                whiteAdd0, whiteAdd1, -1, -1,
                whiteSub0, whiteSub1, -1, -1,
                blackAdd0, blackAdd1, -1, -1,
                blackSub0, blackSub1, -1, -1,
                addCount, subCount);
    }

    public void add(Accumulator prev, AccumulatorUpdate update, short[] whiteWeights, short[] blackWeights) {

        Feature add1 = update.adds[0];

        boolean whiteMirror = mirrored[Colour.WHITE];
        boolean blackMirror = mirrored[Colour.BLACK];

        int wOffset = add1.index(true, whiteMirror) * HIDDEN_SIZE;
        int bOffset = add1.index(false, blackMirror) * HIDDEN_SIZE;

        if (CalvinNeon.isAvailable()) {
            updateBothFromPrevious(prev, whiteWeights, blackWeights,
                    wOffset, -1, -1, -1, bOffset, -1, -1, -1, 1, 0);
            return;
        }

        for (int i = 0; i < LOOP_LENGTH; i++) {

            short whiteValue = prev.whiteFeatures[i];
            whiteValue = (short) (whiteValue + whiteWeights[i + wOffset]);
            whiteFeatures[i] = whiteValue;

            short blackValue = prev.blackFeatures[i];
            blackValue = (short) (blackValue + blackWeights[i + bOffset]);
            blackFeatures[i] = blackValue;

        }
    }

    public void addSub(Accumulator prev, AccumulatorUpdate update, short[] whiteWeights, short[] blackWeights) {

        Feature add1 = update.adds[0];
        Feature sub1 = update.subs[0];

        boolean whiteMirror = mirrored[Colour.WHITE];
        boolean blackMirror = mirrored[Colour.BLACK];

        int wOffset1 = add1.index(true, whiteMirror) * HIDDEN_SIZE;
        int bOffset1 = add1.index(false, blackMirror) * HIDDEN_SIZE;
        int wOffset2 = sub1.index(true, whiteMirror) * HIDDEN_SIZE;
        int bOffset2 = sub1.index(false, blackMirror) * HIDDEN_SIZE;

        if (CalvinNeon.isAvailable()) {
            updateBothFromPrevious(prev, whiteWeights, blackWeights,
                    wOffset1, -1, wOffset2, -1,
                    bOffset1, -1, bOffset2, -1, 1, 1);
            return;
        }

        for (int i = 0; i < LOOP_LENGTH; i++) {

            short whiteValue = prev.whiteFeatures[i];
            whiteValue = (short) (whiteValue + whiteWeights[i + wOffset1]);
            whiteValue = (short) (whiteValue - whiteWeights[i + wOffset2]);
            whiteFeatures[i] = whiteValue;

            short blackValue = prev.blackFeatures[i];
            blackValue = (short) (blackValue + blackWeights[i + bOffset1]);
            blackValue = (short) (blackValue - blackWeights[i + bOffset2]);
            blackFeatures[i] = blackValue;

        }
    }

    public void addSubSub(Accumulator prev, AccumulatorUpdate update, short[] whiteWeights, short[] blackWeights) {

        Feature add1 = update.adds[0];
        Feature sub1 = update.subs[0];
        Feature sub2 = update.subs[1];

        boolean whiteMirror = mirrored[Colour.WHITE];
        boolean blackMirror = mirrored[Colour.BLACK];

        int wOffset1 = add1.index(true, whiteMirror) * HIDDEN_SIZE;
        int bOffset1 = add1.index(false, blackMirror) * HIDDEN_SIZE;
        int wOffset2 = sub1.index(true, whiteMirror) * HIDDEN_SIZE;
        int bOffset2 = sub1.index(false, blackMirror) * HIDDEN_SIZE;
        int wOffset3 = sub2.index(true, whiteMirror) * HIDDEN_SIZE;
        int bOffset3 = sub2.index(false, blackMirror) * HIDDEN_SIZE;

        if (CalvinNeon.isAvailable()) {
            updateBothFromPrevious(prev, whiteWeights, blackWeights,
                    wOffset1, -1, wOffset2, wOffset3,
                    bOffset1, -1, bOffset2, bOffset3, 1, 2);
            return;
        }

        for (int i = 0; i < LOOP_LENGTH; i++) {

            short whiteValue = prev.whiteFeatures[i];
            whiteValue = (short) (whiteValue + whiteWeights[i + wOffset1]);
            whiteValue = (short) (whiteValue - whiteWeights[i + wOffset2]);
            whiteValue = (short) (whiteValue - whiteWeights[i + wOffset3]);
            whiteFeatures[i] = whiteValue;

            short blackValue = prev.blackFeatures[i];
            blackValue = (short) (blackValue + blackWeights[i + bOffset1]);
            blackValue = (short) (blackValue - blackWeights[i + bOffset2]);
            blackValue = (short) (blackValue - blackWeights[i + bOffset3]);
            blackFeatures[i] = blackValue;

        }
    }

    public void addAddSubSub(Accumulator prev, AccumulatorUpdate update, short[] whiteWeights, short[] blackWeights) {

        Feature add1 = update.adds[0];
        Feature add2 = update.adds[1];
        Feature sub1 = update.subs[0];
        Feature sub2 = update.subs[1];

        boolean whiteMirror = mirrored[Colour.WHITE];
        boolean blackMirror = mirrored[Colour.BLACK];

        int wOffset1 = add1.index(true, whiteMirror) * HIDDEN_SIZE;
        int bOffset1 = add1.index(false, blackMirror) * HIDDEN_SIZE;
        int wOffset2 = add2.index(true, whiteMirror) * HIDDEN_SIZE;
        int bOffset2 = add2.index(false, blackMirror) * HIDDEN_SIZE;
        int wOffset3 = sub1.index(true, whiteMirror) * HIDDEN_SIZE;
        int bOffset3 = sub1.index(false, blackMirror) * HIDDEN_SIZE;
        int wOffset4 = sub2.index(true, whiteMirror) * HIDDEN_SIZE;
        int bOffset4 = sub2.index(false, blackMirror) * HIDDEN_SIZE;

        if (CalvinNeon.isAvailable()) {
            updateBothFromPrevious(prev, whiteWeights, blackWeights,
                    wOffset1, wOffset2, wOffset3, wOffset4,
                    bOffset1, bOffset2, bOffset3, bOffset4, 2, 2);
            return;
        }

        for (int i = 0; i < LOOP_LENGTH; i++) {

            short whiteValue = prev.whiteFeatures[i];
            whiteValue = (short) (whiteValue + whiteWeights[i + wOffset1]);
            whiteValue = (short) (whiteValue + whiteWeights[i + wOffset2]);
            whiteValue = (short) (whiteValue - whiteWeights[i + wOffset3]);
            whiteValue = (short) (whiteValue - whiteWeights[i + wOffset4]);
            whiteFeatures[i] = whiteValue;

            short blackValue = prev.blackFeatures[i];
            blackValue = (short) (blackValue + blackWeights[i + bOffset1]);
            blackValue = (short) (blackValue + blackWeights[i + bOffset2]);
            blackValue = (short) (blackValue - blackWeights[i + bOffset3]);
            blackValue = (short) (blackValue - blackWeights[i + bOffset4]);
            blackFeatures[i] = blackValue;

        }
    }

    public void copyFrom(short[] features, boolean whitePerspective) {
        if (whitePerspective) {
            vectorCopy(features, whiteFeatures, features.length);
        } else {
            vectorCopy(features, blackFeatures, features.length);
        }
    }

    public void copyFrom(Accumulator accumulator) {
        vectorCopy(accumulator.whiteFeatures, whiteFeatures, whiteFeatures.length);
        vectorCopy(accumulator.blackFeatures, blackFeatures, blackFeatures.length);
        System.arraycopy(accumulator.mirrored, 0, mirrored, 0, mirrored.length);
    }

    public static void vectorCopy(short[] src, short[] dest, int length) {
        System.arraycopy(src, 0, dest, 0, Math.min(length, Math.min(src.length, dest.length)));
    }

    public static class AccumulatorUpdate {

        public final Feature[] adds = new Feature[2];
        public final Feature[] subs = new Feature[2];

        public int addCount = 0;
        public int subCount = 0;

        public void pushAdd(Feature add) {
            adds[addCount++] = add;
        }

        public void pushSub(Feature sub) {
            subs[subCount++] = sub;
        }

        public UpdateType getUpdateType() {
            if (addCount == 1 && subCount == 0) {
                return UpdateType.ADD;
            }
            else if (addCount == 1 && subCount == 1) {
                return UpdateType.ADD_SUB;
            }
            else if (addCount == 1 && subCount == 2) {
                return UpdateType.ADD_SUB_SUB;
            }
            else if (addCount == 2 && subCount == 2) {
                return UpdateType.ADD_ADD_SUB_SUB;
            }
            else {
                throw new IllegalStateException("Unexpected update type");
            }
        }

    }

    public enum UpdateType {
        ADD,
        ADD_SUB,
        ADD_SUB_SUB,
        ADD_ADD_SUB_SUB
    }

}
