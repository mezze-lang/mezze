#include <stdio.h>
#include <stdint.h>

static int64_t fib(int64_t n) {
    if (n < 2) return n;
    return fib(n - 1) + fib(n - 2);
}

int main(void) {
    printf("fib(35) = %lld\n", (long long) fib(35));
    printf("fib(38) = %lld\n", (long long) fib(38));
    return 0;
}
