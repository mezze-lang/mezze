#include <stdio.h>
#include <stdint.h>

static int64_t compute(int64_t n) {
    int64_t half = n / 2;
    int64_t acc = 0;
    for (int64_t i = 0; i < n; i++) {
        if (i < half) acc += i * 2;
    }
    return acc;
}

int main(void) {
    printf("filter_map_fold 0..1M = %lld\n", (long long) compute(1000000LL));
    printf("filter_map_fold 0..10M = %lld\n", (long long) compute(10000000LL));
    return 0;
}
