/*
 * Calvin NNUE ARM64 NEON kernels.
 *
 * This is a math accelerator only. Search, move generation, UCI, and all
 * engine decisions remain in Calvin's Java implementation.
 */
#ifndef CALVIN_NEON_KERNEL_TEST
#include <jni.h>
#endif

#include <stdint.h>
#include <stddef.h>
#include <string.h>

#if defined(__aarch64__)
#include <arm_neon.h>
#endif

#define CALVIN_MAX_HIDDEN 2048

static int valid_hidden_size(int hidden_size) {
    return hidden_size > 0 && hidden_size <= CALVIN_MAX_HIDDEN;
}

static int64_t calvin_screlu_sum(const int16_t *us,
                                 const int16_t *them,
                                 const int16_t *weights,
                                 int hidden_size,
                                 int qa) {
#if defined(__aarch64__)
    const int16x8_t zero = vdupq_n_s16(0);
    const int16x8_t ceiling = vdupq_n_s16((int16_t) qa);
    int64x2_t sum_low = vdupq_n_s64(0);
    int64x2_t sum_high = vdupq_n_s64(0);
    int i = 0;

    for (; i + 8 <= hidden_size; i += 8) {
        int16x8_t u = vminq_s16(vmaxq_s16(vld1q_s16(us + i), zero), ceiling);
        int16x8_t t = vminq_s16(vmaxq_s16(vld1q_s16(them + i), zero), ceiling);
        int16x8_t uw = vld1q_s16(weights + i);
        int16x8_t tw = vld1q_s16(weights + hidden_size + i);

        /* vmulq_s16 keeps the low 16 bits, matching ShortVector.mul(). */
        int16x8_t u_term = vmulq_s16(u, uw);
        int16x8_t t_term = vmulq_s16(t, tw);

        int32x4_t low_products = vaddq_s32(
                vmull_s16(vget_low_s16(u), vget_low_s16(u_term)),
                vmull_s16(vget_low_s16(t), vget_low_s16(t_term)));
        int32x4_t high_products = vaddq_s32(
                vmull_s16(vget_high_s16(u), vget_high_s16(u_term)),
                vmull_s16(vget_high_s16(t), vget_high_s16(t_term)));

        /* Widen pairwise to 64-bit so no accumulator lane can overflow. */
        sum_low = vaddq_s64(sum_low, vpaddlq_s32(low_products));
        sum_high = vaddq_s64(sum_high, vpaddlq_s32(high_products));
    }

    int64_t total = vgetq_lane_s64(sum_low, 0)
            + vgetq_lane_s64(sum_low, 1)
            + vgetq_lane_s64(sum_high, 0)
            + vgetq_lane_s64(sum_high, 1);

    /* The configured Calvin hidden layer is 1024 (a multiple of eight).
       Keep a correct tail path if another network size is used later. */
    for (; i < hidden_size; ++i) {
        int32_t u = us[i] < 0 ? 0 : (us[i] > qa ? qa : us[i]);
        int32_t t = them[i] < 0 ? 0 : (them[i] > qa ? qa : them[i]);
        int16_t u_term = (int16_t) (uint16_t) (u * weights[i]);
        int16_t t_term = (int16_t) (uint16_t) (t * weights[hidden_size + i]);
        total += (int64_t) u * u_term + (int64_t) t * t_term;
    }
    return total;
#else
    int64_t total = 0;
    for (int i = 0; i < hidden_size; ++i) {
        int32_t u = us[i] < 0 ? 0 : (us[i] > qa ? qa : us[i]);
        int32_t t = them[i] < 0 ? 0 : (them[i] > qa ? qa : them[i]);
        int16_t u_term = (int16_t) (uint16_t) (u * weights[i]);
        int16_t t_term = (int16_t) (uint16_t) (t * weights[hidden_size + i]);
        total += (int64_t) u * u_term + (int64_t) t * t_term;
    }
    return total;
#endif
}

static int32_t calvin_screlu_evaluate(int64_t sum, int qa, int qb, int scale, int16_t output_bias) {
    /* Java int arithmetic wraps modulo 2^32; spell it explicitly in C. */
    uint32_t evaluation_bits = (uint32_t) (int32_t) (sum / qa);
    evaluation_bits += (uint32_t) (int32_t) output_bias;
    evaluation_bits *= (uint32_t) scale;
    int32_t evaluation;
    memcpy(&evaluation, &evaluation_bits, sizeof(evaluation));
    evaluation /= qa * qb;
    return evaluation;
}

static void calvin_update_features(int16_t *features,
                                   const int16_t *weights,
                                   int hidden_size,
                                   int add0,
                                   int add1,
                                   int add2,
                                   int add3,
                                   int sub0,
                                   int sub1,
                                   int sub2,
                                   int sub3,
                                   int add_count,
                                   int sub_count) {
#if defined(__aarch64__)
    int i = 0;
    for (; i + 8 <= hidden_size; i += 8) {
        int16x8_t value = vld1q_s16(features + i);
        if (add_count > 0) value = vaddq_s16(value, vld1q_s16(weights + add0 + i));
        if (add_count > 1) value = vaddq_s16(value, vld1q_s16(weights + add1 + i));
        if (add_count > 2) value = vaddq_s16(value, vld1q_s16(weights + add2 + i));
        if (add_count > 3) value = vaddq_s16(value, vld1q_s16(weights + add3 + i));
        if (sub_count > 0) value = vsubq_s16(value, vld1q_s16(weights + sub0 + i));
        if (sub_count > 1) value = vsubq_s16(value, vld1q_s16(weights + sub1 + i));
        if (sub_count > 2) value = vsubq_s16(value, vld1q_s16(weights + sub2 + i));
        if (sub_count > 3) value = vsubq_s16(value, vld1q_s16(weights + sub3 + i));
        vst1q_s16(features + i, value);
    }
    for (; i < hidden_size; ++i) {
        uint16_t value = (uint16_t) features[i];
        if (add_count > 0) value = (uint16_t) (value + (uint16_t) weights[add0 + i]);
        if (add_count > 1) value = (uint16_t) (value + (uint16_t) weights[add1 + i]);
        if (add_count > 2) value = (uint16_t) (value + (uint16_t) weights[add2 + i]);
        if (add_count > 3) value = (uint16_t) (value + (uint16_t) weights[add3 + i]);
        if (sub_count > 0) value = (uint16_t) (value - (uint16_t) weights[sub0 + i]);
        if (sub_count > 1) value = (uint16_t) (value - (uint16_t) weights[sub1 + i]);
        if (sub_count > 2) value = (uint16_t) (value - (uint16_t) weights[sub2 + i]);
        if (sub_count > 3) value = (uint16_t) (value - (uint16_t) weights[sub3 + i]);
        features[i] = (int16_t) value;
    }
#else
    for (int i = 0; i < hidden_size; ++i) {
        uint16_t value = (uint16_t) features[i];
        if (add_count > 0) value = (uint16_t) (value + (uint16_t) weights[add0 + i]);
        if (add_count > 1) value = (uint16_t) (value + (uint16_t) weights[add1 + i]);
        if (add_count > 2) value = (uint16_t) (value + (uint16_t) weights[add2 + i]);
        if (add_count > 3) value = (uint16_t) (value + (uint16_t) weights[add3 + i]);
        if (sub_count > 0) value = (uint16_t) (value - (uint16_t) weights[sub0 + i]);
        if (sub_count > 1) value = (uint16_t) (value - (uint16_t) weights[sub1 + i]);
        if (sub_count > 2) value = (uint16_t) (value - (uint16_t) weights[sub2 + i]);
        if (sub_count > 3) value = (uint16_t) (value - (uint16_t) weights[sub3 + i]);
        features[i] = (int16_t) value;
    }
#endif
}

#ifndef CALVIN_NEON_KERNEL_TEST

static void throw_illegal_argument(JNIEnv *env, const char *message) {
    jclass exception_class = (*env)->FindClass(env, "java/lang/IllegalArgumentException");
    if (exception_class != NULL) {
        (*env)->ThrowNew(env, exception_class, message);
    }
}

JNIEXPORT jint JNICALL
Java_com_kelseyde_calvin_evaluation_CalvinNeon_screluForwardNative(
        JNIEnv *env,
        jclass type,
        jshortArray us_array,
        jshortArray them_array,
        jshortArray weights_array,
        jint hidden_size,
        jint qa,
        jint qb,
        jint scale,
        jshort output_bias) {
    (void) type;
    if (!valid_hidden_size(hidden_size) || qa <= 0 || qb <= 0) {
        throw_illegal_argument(env, "Invalid Calvin SCReLU dimensions or quantisation");
        return 0;
    }
    if (us_array == NULL || them_array == NULL || weights_array == NULL
            || (*env)->GetArrayLength(env, us_array) < hidden_size
            || (*env)->GetArrayLength(env, them_array) < hidden_size
            || (*env)->GetArrayLength(env, weights_array) < hidden_size * 2) {
        throw_illegal_argument(env, "Calvin SCReLU arrays are shorter than the configured network");
        return 0;
    }

    int16_t us[CALVIN_MAX_HIDDEN];
    int16_t them[CALVIN_MAX_HIDDEN];
    (*env)->GetShortArrayRegion(env, us_array, 0, hidden_size, (jshort *) us);
    if ((*env)->ExceptionCheck(env)) return 0;
    (*env)->GetShortArrayRegion(env, them_array, 0, hidden_size, (jshort *) them);
    if ((*env)->ExceptionCheck(env)) return 0;

    /* Only one critical array is pinned; no JNI calls occur until it is released. */
    jshort *weights = (jshort *) (*env)->GetPrimitiveArrayCritical(env, weights_array, NULL);
    if (weights == NULL) return 0;
    int64_t sum = calvin_screlu_sum(us, them, (const int16_t *) weights, hidden_size, qa);
    (*env)->ReleasePrimitiveArrayCritical(env, weights_array, weights, JNI_ABORT);

    return (jint) calvin_screlu_evaluate(sum, qa, qb, scale, output_bias);
}

static void update_features_jni(JNIEnv *env,
                                jshortArray source_array,
                                jshortArray destination_array,
                                jshortArray weights_array,
                                jint hidden_size,
                                jint add0,
                                jint add1,
                                jint add2,
                                jint add3,
                                jint sub0,
                                jint sub1,
                                jint sub2,
                                jint sub3,
                                jint add_count,
                                jint sub_count) {
    if (!valid_hidden_size(hidden_size) || add_count < 0 || add_count > 4
            || sub_count < 0 || sub_count > 4 || add_count + sub_count == 0) {
        throw_illegal_argument(env, "Invalid Calvin accumulator update");
        return;
    }
    if (source_array == NULL || destination_array == NULL || weights_array == NULL
            || (*env)->GetArrayLength(env, source_array) < hidden_size
            || (*env)->GetArrayLength(env, destination_array) < hidden_size) {
        throw_illegal_argument(env, "Calvin accumulator arrays are too short");
        return;
    }

    jsize weight_length = (*env)->GetArrayLength(env, weights_array);
    if ((add_count > 0 && (add0 < 0 || add0 > weight_length - hidden_size))
            || (add_count > 1 && (add1 < 0 || add1 > weight_length - hidden_size))
            || (add_count > 2 && (add2 < 0 || add2 > weight_length - hidden_size))
            || (add_count > 3 && (add3 < 0 || add3 > weight_length - hidden_size))
            || (sub_count > 0 && (sub0 < 0 || sub0 > weight_length - hidden_size))
            || (sub_count > 1 && (sub1 < 0 || sub1 > weight_length - hidden_size))
            || (sub_count > 2 && (sub2 < 0 || sub2 > weight_length - hidden_size))
            || (sub_count > 3 && (sub3 < 0 || sub3 > weight_length - hidden_size))) {
        throw_illegal_argument(env, "Calvin accumulator feature offset is outside the NNUE weights");
        return;
    }

    int16_t feature_values[CALVIN_MAX_HIDDEN];
    (*env)->GetShortArrayRegion(env, source_array, 0, hidden_size, (jshort *) feature_values);
    if ((*env)->ExceptionCheck(env)) return;

    jshort *weights = (jshort *) (*env)->GetPrimitiveArrayCritical(env, weights_array, NULL);
    if (weights == NULL) return;
    calvin_update_features(feature_values, (const int16_t *) weights, hidden_size,
                           add0, add1, add2, add3, sub0, sub1, sub2, sub3,
                           add_count, sub_count);
    (*env)->ReleasePrimitiveArrayCritical(env, weights_array, weights, JNI_ABORT);

    (*env)->SetShortArrayRegion(env, destination_array, 0, hidden_size,
                                (const jshort *) feature_values);
}

JNIEXPORT void JNICALL
Java_com_kelseyde_calvin_evaluation_CalvinNeon_updateFeaturesNative(
        JNIEnv *env,
        jclass type,
        jshortArray features_array,
        jshortArray weights_array,
        jint hidden_size,
        jint add0, jint add1, jint add2, jint add3,
        jint sub0, jint sub1, jint sub2, jint sub3,
        jint add_count,
        jint sub_count) {
    (void) type;
    update_features_jni(env, features_array, features_array, weights_array,
            hidden_size, add0, add1, add2, add3, sub0, sub1, sub2, sub3,
            add_count, sub_count);
}

JNIEXPORT void JNICALL
Java_com_kelseyde_calvin_evaluation_CalvinNeon_updateBothFromPreviousNative(
        JNIEnv *env,
        jclass type,
        jshortArray previous_white_array,
        jshortArray destination_white_array,
        jshortArray white_weights_array,
        jshortArray previous_black_array,
        jshortArray destination_black_array,
        jshortArray black_weights_array,
        jint hidden_size,
        jint white_add0, jint white_add1, jint white_add2, jint white_add3,
        jint white_sub0, jint white_sub1, jint white_sub2, jint white_sub3,
        jint black_add0, jint black_add1, jint black_add2, jint black_add3,
        jint black_sub0, jint black_sub1, jint black_sub2, jint black_sub3,
        jint add_count,
        jint sub_count) {
    (void) type;
    if (!valid_hidden_size(hidden_size) || add_count < 0 || add_count > 4
            || sub_count < 0 || sub_count > 4 || add_count + sub_count == 0) {
        throw_illegal_argument(env, "Invalid Calvin dual-perspective accumulator update");
        return;
    }
    if (previous_white_array == NULL || destination_white_array == NULL || white_weights_array == NULL
            || previous_black_array == NULL || destination_black_array == NULL || black_weights_array == NULL
            || (*env)->GetArrayLength(env, previous_white_array) < hidden_size
            || (*env)->GetArrayLength(env, destination_white_array) < hidden_size
            || (*env)->GetArrayLength(env, previous_black_array) < hidden_size
            || (*env)->GetArrayLength(env, destination_black_array) < hidden_size) {
        throw_illegal_argument(env, "Calvin dual-perspective accumulator arrays are too short");
        return;
    }

    jsize white_weight_length = (*env)->GetArrayLength(env, white_weights_array);
    jsize black_weight_length = (*env)->GetArrayLength(env, black_weights_array);
    if ((add_count > 0 && (white_add0 < 0 || white_add0 > white_weight_length - hidden_size
                    || black_add0 < 0 || black_add0 > black_weight_length - hidden_size))
            || (add_count > 1 && (white_add1 < 0 || white_add1 > white_weight_length - hidden_size
                    || black_add1 < 0 || black_add1 > black_weight_length - hidden_size))
            || (add_count > 2 && (white_add2 < 0 || white_add2 > white_weight_length - hidden_size
                    || black_add2 < 0 || black_add2 > black_weight_length - hidden_size))
            || (add_count > 3 && (white_add3 < 0 || white_add3 > white_weight_length - hidden_size
                    || black_add3 < 0 || black_add3 > black_weight_length - hidden_size))
            || (sub_count > 0 && (white_sub0 < 0 || white_sub0 > white_weight_length - hidden_size
                    || black_sub0 < 0 || black_sub0 > black_weight_length - hidden_size))
            || (sub_count > 1 && (white_sub1 < 0 || white_sub1 > white_weight_length - hidden_size
                    || black_sub1 < 0 || black_sub1 > black_weight_length - hidden_size))
            || (sub_count > 2 && (white_sub2 < 0 || white_sub2 > white_weight_length - hidden_size
                    || black_sub2 < 0 || black_sub2 > black_weight_length - hidden_size))
            || (sub_count > 3 && (white_sub3 < 0 || white_sub3 > white_weight_length - hidden_size
                    || black_sub3 < 0 || black_sub3 > black_weight_length - hidden_size))) {
        throw_illegal_argument(env, "Calvin dual-perspective feature offset is outside the NNUE weights");
        return;
    }

    int16_t white_values[CALVIN_MAX_HIDDEN];
    int16_t black_values[CALVIN_MAX_HIDDEN];
    (*env)->GetShortArrayRegion(env, previous_white_array, 0, hidden_size, (jshort *) white_values);
    if ((*env)->ExceptionCheck(env)) return;
    (*env)->GetShortArrayRegion(env, previous_black_array, 0, hidden_size, (jshort *) black_values);
    if ((*env)->ExceptionCheck(env)) return;

    /* Pin one large bucket-weight array at a time; never call JNI while pinned. */
    jshort *white_weights = (jshort *) (*env)->GetPrimitiveArrayCritical(env, white_weights_array, NULL);
    if (white_weights == NULL) return;
    calvin_update_features(white_values, (const int16_t *) white_weights, hidden_size,
            white_add0, white_add1, white_add2, white_add3,
            white_sub0, white_sub1, white_sub2, white_sub3, add_count, sub_count);
    (*env)->ReleasePrimitiveArrayCritical(env, white_weights_array, white_weights, JNI_ABORT);

    jshort *black_weights = (jshort *) (*env)->GetPrimitiveArrayCritical(env, black_weights_array, NULL);
    if (black_weights == NULL) return;
    calvin_update_features(black_values, (const int16_t *) black_weights, hidden_size,
            black_add0, black_add1, black_add2, black_add3,
            black_sub0, black_sub1, black_sub2, black_sub3, add_count, sub_count);
    (*env)->ReleasePrimitiveArrayCritical(env, black_weights_array, black_weights, JNI_ABORT);

    (*env)->SetShortArrayRegion(env, destination_white_array, 0, hidden_size, (const jshort *) white_values);
    if ((*env)->ExceptionCheck(env)) return;
    (*env)->SetShortArrayRegion(env, destination_black_array, 0, hidden_size, (const jshort *) black_values);
}

#endif /* !CALVIN_NEON_KERNEL_TEST */
