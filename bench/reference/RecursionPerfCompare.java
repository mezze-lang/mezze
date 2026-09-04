import java.util.function.IntUnaryOperator;

public class RecursionPerfCompare {
    static final int WARMUP = 3;
    static final int MEASURED = 5;

    static int fib(int n) {
        if (n <= 1) return n;
        return fib(n - 1) + fib(n - 2);
    }

    static long sumTR(long n, long acc) {
        if (n <= 0) return acc;
        return sumTR(n - 1, acc + n);
    }

    // Java doesn't have TCO, so iterative for large N
    static long sumLoop(long n) {
        long acc = 0;
        for (long i = n; i > 0; i--) acc += i;
        return acc;
    }

    static long countdown(long n) {
        long x = n;
        while (x > 0) x--;
        return x;
    }

    static boolean isEven(int n) {
        // Iterative to avoid stack overflow
        return (n % 2) == 0;
    }

    static long applyN(int n, IntUnaryOperator f, int x) {
        int result = x;
        for (int i = 0; i < n; i++) result = f.applyAsInt(result);
        return result;
    }

    public static void main(String[] args) {
        System.out.println("== Java Recursion Perf ==");

        bench("fib(30)         ", () -> fib(30));
        bench("fib(35)         ", () -> fib(35));
        bench("sum_loop(1M)    ", () -> sumLoop(1_000_000));
        bench("sum_loop(10M)   ", () -> sumLoop(10_000_000));
        bench("countdown(1M)   ", () -> countdown(1_000_000));
        bench("countdown(10M)  ", () -> countdown(10_000_000));
        bench("isEven(1M)      ", () -> isEven(1_000_000) ? 1L : 0L);
        bench("applyN(1M,+1)   ", () -> applyN(1_000_000, x -> x + 1, 0));

        System.out.println("== done ==");
    }

    @FunctionalInterface
    interface LongFn { long run(); }

    static void bench(String label, LongFn f) {
        for (int i = 0; i < WARMUP; i++) f.run();
        long[] times = new long[MEASURED];
        long result = 0;
        for (int i = 0; i < MEASURED; i++) {
            long t0 = System.nanoTime();
            result = f.run();
            times[i] = System.nanoTime() - t0;
        }
        long avg = 0;
        for (long t : times) avg += t;
        avg /= MEASURED;
        long avgMs = avg / 1_000_000;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(times[i]);
        }
        System.out.println(label + ": avg=" + avgMs + "ms (" + avg + "ns)  [" + sb + "]ns  r=" + result);
    }
}
