/* Host-side arithmetic tests for the NEON kernels' reference semantics. */
#define CALVIN_NEON_KERNEL_TEST
#include "../../main/cpp/calvin_neon.c"

#include <stdio.h>
#include <stdlib.h>

static uint32_t random_state = 0x6d2b79f5u;
static uint32_t next_random(void) {
    random_state ^= random_state << 13;
    random_state ^= random_state >> 17;
    random_state ^= random_state << 5;
    return random_state;
}

static int16_t random_short(void) {
    return (int16_t) (uint16_t) next_random();
}

static int check_screlu(int hidden_size) {
    int16_t us[CALVIN_MAX_HIDDEN];
    int16_t them[CALVIN_MAX_HIDDEN];
    int16_t weights[CALVIN_MAX_HIDDEN * 2];
    const int qa = 255;
    const int qb = 64;
    const int scale = 400;
    const int16_t bias = -123;
    int64_t expected_sum = 0;

    for (int i = 0; i < hidden_size; ++i) {
        us[i] = (int16_t) ((int) (next_random() % 601u) - 150);
        them[i] = (int16_t) ((int) (next_random() % 601u) - 150);
        weights[i] = random_short();
        weights[hidden_size + i] = random_short();

        int u = us[i] < 0 ? 0 : (us[i] > qa ? qa : us[i]);
        int t = them[i] < 0 ? 0 : (them[i] > qa ? qa : them[i]);
        int16_t u_term = (int16_t) (uint16_t) (u * weights[i]);
        int16_t t_term = (int16_t) (uint16_t) (t * weights[hidden_size + i]);
        expected_sum += (int64_t) u * u_term + (int64_t) t * t_term;
    }

    uint32_t expected_bits = (uint32_t) (int32_t) (expected_sum / qa);
    expected_bits += (uint32_t) (int32_t) bias;
    expected_bits *= (uint32_t) scale;
    int32_t expected;
    memcpy(&expected, &expected_bits, sizeof(expected));
    expected /= qa * qb;

    int64_t actual_sum = calvin_screlu_sum(us, them, weights, hidden_size, qa);
    int32_t actual = calvin_screlu_evaluate(actual_sum, qa, qb, scale, bias);

    if (actual != expected) {
        fprintf(stderr, "SCReLU mismatch h=%d expected=%d actual=%d\n",
                hidden_size, expected, actual);
        return 1;
    }
    return 0;
}

static int check_update(int hidden_size, int add_count, int sub_count) {
    int16_t values[CALVIN_MAX_HIDDEN];
    int16_t expected[CALVIN_MAX_HIDDEN];
    int16_t weights[CALVIN_MAX_HIDDEN * 8];
    const int offsets[8] = {
        0 * hidden_size, 1 * hidden_size, 2 * hidden_size, 3 * hidden_size,
        4 * hidden_size, 5 * hidden_size, 6 * hidden_size, 7 * hidden_size
    };

    for (int i = 0; i < hidden_size; ++i) {
        values[i] = random_short();
        expected[i] = values[i];
    }
    for (int i = 0; i < hidden_size * 8; ++i) weights[i] = random_short();

    for (int i = 0; i < hidden_size; ++i) {
        uint16_t value = (uint16_t) expected[i];
        for (int n = 0; n < add_count; ++n)
            value = (uint16_t) (value + (uint16_t) weights[offsets[n] + i]);
        for (int n = 0; n < sub_count; ++n)
            value = (uint16_t) (value - (uint16_t) weights[offsets[4 + n] + i]);
        expected[i] = (int16_t) value;
    }

    calvin_update_features(values, weights, hidden_size,
            offsets[0], offsets[1], offsets[2], offsets[3],
            offsets[4], offsets[5], offsets[6], offsets[7],
            add_count, sub_count);

    if (memcmp(values, expected, (size_t) hidden_size * sizeof(int16_t)) != 0) {
        fprintf(stderr, "Accumulator mismatch h=%d adds=%d subs=%d\n",
                hidden_size, add_count, sub_count);
        return 1;
    }
    return 0;
}

int main(void) {
    if (!valid_hidden_size(1024) || valid_hidden_size(0) || valid_hidden_size(CALVIN_MAX_HIDDEN + 1)) {
        fprintf(stderr, "hidden-size validation failed\n");
        return 1;
    }

    if (check_screlu(1024) || check_screlu(1021)) return 1;
    const int cases[][2] = {{1,0}, {0,1}, {4,0}, {0,4}, {1,1}, {1,2}, {2,2}};
    for (unsigned i = 0; i < sizeof(cases) / sizeof(cases[0]); ++i) {
        if (check_update(1024, cases[i][0], cases[i][1])) return 1;
        if (check_update(1021, cases[i][0], cases[i][1])) return 1;
    }

    puts("Calvin NNUE kernel arithmetic tests passed (SCReLU and all accumulator update shapes).");
    return 0;
}
