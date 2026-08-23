// Java: hand loop baseline.
// Compile: javac FilterMapFoldLoop.java

public class FilterMapFoldLoop {
    static long compute(long n) {
        long half = n / 2;
        long acc = 0;
        for (long i = 0; i < n; i++) {
            if (i < half) acc += i * 2;
        }
        return acc;
    }

    public static void main(String[] args) {
        System.out.println("filter_map_fold 0..1M = " + compute(1_000_000L));
        System.out.println("filter_map_fold 0..10M = " + compute(10_000_000L));
    }
}
