// Java: idiomatic LongStream chain — JIT should fuse.
// Compile: javac FilterMapFold.java

import java.util.stream.LongStream;

public class FilterMapFold {
    static long compute(long n) {
        long half = n / 2;
        return LongStream.range(0, n).filter(x -> x < half).map(x -> x * 2).sum();
    }

    public static void main(String[] args) {
        System.out.println("filter_map_fold 0..1M = " + compute(1_000_000L));
        System.out.println("filter_map_fold 0..10M = " + compute(10_000_000L));
    }
}
