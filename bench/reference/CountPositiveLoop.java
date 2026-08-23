// Java hand-loop reference for count_positive bench.
import java.util.Arrays;

public class CountPositiveLoop {
    static long compute(long n) {
        long c = 0;
        for (long i = 0; i < n; i++) if (i > 0) c++;
        return c;
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
