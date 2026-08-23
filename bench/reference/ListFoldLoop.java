// Java reference for list_fold_1m: build a linked list of N=1M and fold.
import java.util.Arrays;

public class ListFoldLoop {
    static final class Cons { long head; Cons tail; Cons(long h, Cons t){head=h;tail=t;} }
    static Cons build(long n) {
        Cons c = null;
        for (long i = 1; i <= n; i++) c = new Cons(i, c);
        return c;
    }
    static long fold(Cons xs) {
        long acc = 0;
        for (Cons c = xs; c != null; c = c.tail) acc += c.head;
        return acc;
    }
    public static void main(String[] args) {
        long n = 1_000_000L;
        Cons xs = build(n);
        long r = 0;
        for (int i = 0; i < 3; i++) {
            long t0 = System.nanoTime();
            r = fold(xs);
            long t1 = System.nanoTime();
            System.out.println("  [warmup] iter=" + i + " ns=" + (t1 - t0));
        }
        long[] times = new long[5];
        for (int i = 0; i < 5; i++) {
            long t0 = System.nanoTime();
            r = fold(xs);
            long t1 = System.nanoTime();
            times[i] = t1 - t0;
            System.out.println("  [measure] iter=" + i + " ns=" + times[i]);
        }
        Arrays.sort(times);
        System.out.println("median_ns=" + times[2] + " result=" + r);
    }
}
