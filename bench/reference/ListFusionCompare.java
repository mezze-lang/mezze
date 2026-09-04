import java.util.ArrayList;
import java.util.List;

/**
 * Java reference for list chain fusion benchmarks.
 * Uses ArrayList for fair comparison (Mezze list is cons-based but
 * fusion converts to array first, so runtime behavior matches).
 *
 * javac ListFusionCompare.java && java ListFusionCompare
 */
public class ListFusionCompare {
    static final int N = 100_000;

    static List<Integer> buildList(int size) {
        var xs = new ArrayList<Integer>(size);
        for (int i = 0; i < size; i++) xs.add(i);
        return xs;
    }

    // fold only — baseline
    static long foldOnly(List<Integer> xs) {
        long acc = 0;
        for (int x : xs) acc += x;
        return acc;
    }

    // map then fold — fused: one pass
    static long mapFold(List<Integer> xs) {
        long acc = 0;
        for (int x : xs) acc += x * 2;
        return acc;
    }

    // filter then fold — fused
    static long filterFold(List<Integer> xs) {
        long acc = 0;
        for (int x : xs) if (x % 2 == 0) acc += x;
        return acc;
    }

    // map + filter + fold — 3-stage fused
    static long mapFilterFold(List<Integer> xs) {
        long acc = 0;
        for (int x : xs) {
            int mapped = x * 3;
            if (mapped % 4 == 0) acc += mapped;
        }
        return acc;
    }

    // filter + any
    static int filterAny(List<Integer> xs) {
        for (int x : xs) {
            if (x % 2 == 0 && x > N - 10) return 1;
        }
        return 0;
    }

    // map + all
    static int mapAll(List<Integer> xs) {
        for (int x : xs) {
            if (x * 2 < 0) return 0;
        }
        return 1;
    }

    // map + find
    static int mapFind(List<Integer> xs) {
        for (int x : xs) {
            int mapped = x * 2;
            if (mapped > N * 2 - 20) return mapped;
        }
        return -1;
    }

    // Streams version for comparison (JVM fusion via Stream API)
    static long mapFoldStream(List<Integer> xs) {
        return xs.stream().mapToLong(x -> x * 2L).sum();
    }

    static long filterFoldStream(List<Integer> xs) {
        return xs.stream().filter(x -> x % 2 == 0).mapToLong(x -> (long) x).sum();
    }

    static void bench(String label, java.util.function.Supplier<Object> f) {
        // warmup
        for (int i = 0; i < 3; i++) f.get();
        // measure
        long[] times = new long[5];
        Object result = null;
        for (int i = 0; i < 5; i++) {
            long t0 = System.nanoTime();
            result = f.get();
            times[i] = System.nanoTime() - t0;
        }
        long avg = 0;
        for (long t : times) avg += t;
        avg /= 5;
        System.out.printf("%s: avg=%dms  [%d %d %d %d %d]ns  r=%s%n",
            label, avg / 1_000_000, times[0], times[1], times[2], times[3], times[4], result);
    }

    public static void main(String[] args) {
        System.out.println("== Java List Chain Fusion Reference N=" + N + " ==");
        var xs = buildList(N);
        System.out.println("list built: size=" + xs.size());

        bench("fold_only       ", () -> foldOnly(xs));
        bench("map+fold        ", () -> mapFold(xs));
        bench("filter+fold     ", () -> filterFold(xs));
        bench("map+filter+fold ", () -> mapFilterFold(xs));
        bench("filter+any      ", () -> filterAny(xs));
        bench("map+all         ", () -> mapAll(xs));
        bench("map+find        ", () -> mapFind(xs));
        bench("map+fold STREAM ", () -> mapFoldStream(xs));
        bench("filt+fold STREAM", () -> filterFoldStream(xs));
        System.out.println("== done ==");
    }
}
