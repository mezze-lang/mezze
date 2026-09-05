// Java: equivalent benchmarks using LongStream (idiomatic) + hand loops
// Compile: javac IterPerfCompare.java && java IterPerfCompare

import java.util.stream.LongStream;

public class IterPerfCompare {
    static final long N = 10_000_000L;

    // ── Stream versions (idiomatic Java) ──
    static long streamSum() {
        return LongStream.range(0, N).sum();
    }
    static long streamFilterFold() {
        long half = N / 2;
        return LongStream.range(0, N).filter(x -> x < half).sum();
    }
    static long streamFilterMapFold() {
        long half = N / 2;
        return LongStream.range(0, N).filter(x -> x < half).map(x -> x * 2).sum();
    }
    static long streamCount() {
        long half = N / 2;
        return LongStream.range(0, N).filter(x -> x < half).count();
    }
    static boolean streamAny() {
        return LongStream.range(0, N).anyMatch(x -> x == N - 1);
    }
    static long streamFind() {
        return LongStream.range(0, N).filter(x -> x == N - 1).findFirst().orElse(-1);
    }
    static long streamFilterToArray() {
        return LongStream.range(0, N).filter(x -> x % 2 == 0).toArray().length;
    }

    // ── Hand-loop versions (best-case) ──
    static long loopSum() {
        long acc = 0;
        for (long i = 0; i < N; i++) acc += i;
        return acc;
    }
    static long loopFilterFold() {
        long half = N / 2; long acc = 0;
        for (long i = 0; i < N; i++) if (i < half) acc += i;
        return acc;
    }
    static long loopFilterMapFold() {
        long half = N / 2; long acc = 0;
        for (long i = 0; i < N; i++) if (i < half) acc += i * 2;
        return acc;
    }
    static long loopCount() {
        long half = N / 2; long count = 0;
        for (long i = 0; i < N; i++) if (i < half) count++;
        return count;
    }
    static boolean loopAny() {
        for (long i = 0; i < N; i++) if (i == N - 1) return true;
        return false;
    }
    static long loopFind() {
        for (long i = 0; i < N; i++) if (i == N - 1) return i;
        return -1;
    }
    static long loopFilterCollect() {
        // ArrayList to simulate materialization
        var list = new java.util.ArrayList<Long>((int)(N / 2));
        for (long i = 0; i < N; i++) if (i % 2 == 0) list.add(i);
        return list.size();
    }

    static void bench(String label, Runnable warmup, java.util.function.LongSupplier measured) {
        // 3 warmup
        for (int i = 0; i < 3; i++) {
            long t0 = System.nanoTime();
            warmup.run();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            System.out.println("  [warmup] " + label + " iter=" + i + " " + ms + "ms");
        }
        // 5 measured
        for (int i = 0; i < 5; i++) {
            long t0 = System.nanoTime();
            long r = measured.getAsLong();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            System.out.println("  [MEASURE] " + label + " iter=" + i + " " + ms + "ms (r=" + r + ")");
        }
    }

    static void benchBool(String label, Runnable warmup, java.util.function.BooleanSupplier measured) {
        for (int i = 0; i < 3; i++) {
            long t0 = System.nanoTime();
            warmup.run();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            System.out.println("  [warmup] " + label + " iter=" + i + " " + ms + "ms");
        }
        for (int i = 0; i < 5; i++) {
            long t0 = System.nanoTime();
            boolean r = measured.getAsBoolean();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            System.out.println("  [MEASURE] " + label + " iter=" + i + " " + ms + "ms (r=" + r + ")");
        }
    }

    public static void main(String[] args) {
        System.out.println("== Java Iter Perf N=" + N + " ==");

        System.out.println("\n--- STREAM (idiomatic) ---");
        bench("stream:sum", () -> streamSum(), () -> streamSum());
        System.out.println();
        bench("stream:filter+fold", () -> streamFilterFold(), () -> streamFilterFold());
        System.out.println();
        bench("stream:filter+map+fold", () -> streamFilterMapFold(), () -> streamFilterMapFold());
        System.out.println();
        bench("stream:count", () -> streamCount(), () -> streamCount());
        System.out.println();
        benchBool("stream:any", () -> streamAny(), () -> streamAny());
        System.out.println();
        bench("stream:find", () -> streamFind(), () -> streamFind());
        System.out.println();
        bench("stream:filter+toArray", () -> streamFilterToArray(), () -> streamFilterToArray());

        System.out.println("\n--- HAND LOOP (best-case) ---");
        bench("loop:sum", () -> loopSum(), () -> loopSum());
        System.out.println();
        bench("loop:filter+fold", () -> loopFilterFold(), () -> loopFilterFold());
        System.out.println();
        bench("loop:filter+map+fold", () -> loopFilterMapFold(), () -> loopFilterMapFold());
        System.out.println();
        bench("loop:count", () -> loopCount(), () -> loopCount());
        System.out.println();
        benchBool("loop:any", () -> loopAny(), () -> loopAny());
        System.out.println();
        bench("loop:find", () -> loopFind(), () -> loopFind());
        System.out.println();
        bench("loop:filter+collect", () -> loopFilterCollect(), () -> loopFilterCollect());

        System.out.println("\n== done ==");
    }
}
