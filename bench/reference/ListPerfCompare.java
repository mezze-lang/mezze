import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class ListPerfCompare {
    static final int N = 100_000;
    static final int WARMUP = 3;
    static final int MEASURED = 5;

    public static void main(String[] args) {
        // Build list
        List<Long> xs = new ArrayList<>(N);
        for (int i = 0; i < N; i++) xs.add((long) i);

        // Also build a linked list for fairer comparison with Mezze cons-cells
        java.util.LinkedList<Long> linked = new java.util.LinkedList<>(xs);

        System.out.println("== Java List Perf N=" + N + " ==");
        System.out.println("list built: size=" + xs.size());

        // ArrayList benchmarks
        bench("AL_sum       ", () -> { long s = 0; for (long x : xs) s += x; return s; });
        bench("AL_fold(+)   ", () -> { long s = 0; for (long x : xs) s += x; return s; });
        bench("AL_map(*2)   ", () -> { var r = new ArrayList<Long>(N); for (long x : xs) r.add(x * 2); return (long) r.size(); });
        bench("AL_filter(%) ", () -> { var r = new ArrayList<Long>(N / 2); for (long x : xs) if (x % 2 == 0) r.add(x); return (long) r.size(); });
        bench("AL_any       ", () -> { for (long x : xs) if (x > N - 10) return 1L; return 0L; });
        bench("AL_all       ", () -> { for (long x : xs) if (x < 0) return 0L; return 1L; });
        bench("AL_find      ", () -> { for (long x : xs) if (x > N - 10) return x; return -1L; });
        bench("AL_reverse   ", () -> { var r = new ArrayList<Long>(N); for (int i = xs.size() - 1; i >= 0; i--) r.add(xs.get(i)); return (long) r.size(); });
        bench("AL_contains  ", () -> xs.contains((long)(N - 1)) ? 1L : 0L);

        // Stream benchmarks
        bench("ST_sum       ", () -> xs.stream().mapToLong(x -> x).sum());
        bench("ST_fold(+)   ", () -> xs.stream().reduce(0L, Long::sum));
        bench("ST_map(*2)   ", () -> (long) xs.stream().map(x -> x * 2).collect(Collectors.toList()).size());
        bench("ST_filter(%) ", () -> (long) xs.stream().filter(x -> x % 2 == 0).collect(Collectors.toList()).size());
        bench("ST_any       ", () -> xs.stream().anyMatch(x -> x > N - 10) ? 1L : 0L);
        bench("ST_all       ", () -> xs.stream().allMatch(x -> x >= 0) ? 1L : 0L);
        bench("ST_find      ", () -> xs.stream().filter(x -> x > N - 10).findFirst().orElse(-1L));

        // LinkedList benchmarks (closer to Mezze cons-cell structure)
        bench("LL_sum       ", () -> { long s = 0; for (long x : linked) s += x; return s; });
        bench("LL_fold(+)   ", () -> { long s = 0; for (long x : linked) s += x; return s; });
        bench("LL_any       ", () -> { for (long x : linked) if (x > N - 10) return 1L; return 0L; });
        bench("LL_find      ", () -> { for (long x : linked) if (x > N - 10) return x; return -1L; });
        bench("LL_contains  ", () -> linked.contains((long)(N - 1)) ? 1L : 0L);

        System.out.println("== done ==");
    }

    @FunctionalInterface
    interface LongFn { long run(); }

    static void bench(String label, LongFn f) {
        // Warmup
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
        System.out.println(label + ": avg=" + avgMs + "ms  [" + sb + "]ns  r=" + result);
    }
}
