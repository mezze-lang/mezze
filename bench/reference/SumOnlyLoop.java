// Java hand-loop reference for sum_only bench.
// Times 3 warmup + 5 measured iters, prints median of measured.
import java.util.Arrays;

public class SumOnlyLoop {
    static long compute(long n) {
        long acc = 0;
        for (long i = 0; i < n; i++) acc += i;
        return acc;
    }
    public static void main(String[] args) {
        long n = 10_000_000L;
        long r = 0;
        for (int i = 0; i < 3; i++) {
            long t0 = System.nanoTime();
            r = compute(n);
            long t1 = System.nanoTime();
            System.out.println("  [warmup] iter=" + i + " ns=" + (t1 - t0));
        }
        long[] times = new long[5];
        for (int i = 0; i < 5; i++) {
            long t0 = System.nanoTime();
            r = compute(n);
            long t1 = System.nanoTime();
            times[i] = t1 - t0;
            System.out.println("  [measure] iter=" + i + " ns=" + times[i]);
        }
        Arrays.sort(times);
        System.out.println("median_ns=" + times[2] + " result=" + r);
    }
}
