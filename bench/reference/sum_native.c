#include <stdio.h>
#include <stdint.h>

static int64_t sum_to(int64_t n) {
    int64_t acc = 0;
    for (int64_t i = 0; i < n; i++) acc += i;
    return acc;
}

int main(void) {
    printf("sum 0..1M = %lld\n", (long long) sum_to(1000000LL));
    printf("sum 0..10M = %lld\n", (long long) sum_to(10000000LL));
    return 0;
}
