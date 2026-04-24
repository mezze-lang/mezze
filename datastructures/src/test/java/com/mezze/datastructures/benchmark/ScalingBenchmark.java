package com.mezze.datastructures.benchmark;

import com.mezze.datastructures.list.MezzeList;
import com.mezze.datastructures.map.MezzeMap;
import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.datastructures.set.MezzeSet;
import com.mezze.datastructures.sorted.MezzeSortedMap;
import com.mezze.datastructures.sorted.MezzeSortedSet;

import java.util.Random;

/**
 * Scaling benchmarks to measure how operation cost grows with collection size.
 * Tests incremental build (repeated append/put/add) at various sizes to reveal
 * O(n²) blowup from full-copy-on-mutation in flat storage strategies.
 *
 * Run all:
 *   ./gradlew :datastructures:test --tests "*.ScalingBenchmark.runAllScaling" -i
 *
 * Run individual:
 *   ./gradlew :datastructures:test --tests "*.ScalingBenchmark.scalingSeqAppend" -i
 */
public class ScalingBenchmark {

    private static final int WARMUP = 3;
    private static final Random RANDOM = new Random(42);
    private static final StringBuilder RESULTS = new StringBuilder();

    private static final int[] SEQ_SIZES = {100, 1000, 10000, 100000};
    private static final int[] MAP_SET_SIZES = {100, 1000, 10000, 100000};
    private static final int[] SORTED_SIZES = {100, 1000, 10000};

    @org.junit.jupiter.api.Test
    public void runAllScaling() {
        RESULTS.setLength(0);

        printHeader("SCALING BENCHMARK SUITE");
        System.out.printf("Java version: %s%n%n", System.getProperty("java.version"));

        scalingSeqAppend();
        scalingSeqGet();
        scalingSeqUpdate();
        scalingMapPut();
        scalingMapGet();
        scalingSetAdd();
        scalingSetContains();
        scalingSortedMapPut();
        scalingSortedMapGet();
        scalingSortedSetAdd();

        printHeader("SUMMARY");
        System.out.println(RESULTS.toString());
    }

    // ==================== SEQ SCALING ====================

    @org.junit.jupiter.api.Test
    public void scalingSeqAppend() {
        printHeader("Seq: Incremental Build via append (n pushes → O(n²) total?)");

        for (int n : SEQ_SIZES) {
            scalingBenchmark("append n=" + n, WARMUP, () -> {
                MezzeSeq<Long> s = MezzeSeq.empty();
                for (int i = 0; i < n; i++) {
                    s = s.append((long) i);
                }
                return s.size();
            });
        }
        System.out.println();
    }

    @org.junit.jupiter.api.Test
    public void scalingSeqGet() {
        printHeader("Seq: Random access get (should be O(1) per access)");

        for (int n : SEQ_SIZES) {
            MezzeSeq<Long> seq = MezzeSeq.fromLongs(generateLongs(n));
            int lookups = Math.min(n, 10000);
            int size = n;
            scalingBenchmark("get n=" + n + " (" + lookups + " lookups)", 10, () -> {
                long sum = 0;
                for (int i = 0; i < lookups; i++) {
                    sum += seq.getLong(RANDOM.nextInt(size));
                }
                return sum;
            });
        }
        System.out.println();
    }

    @org.junit.jupiter.api.Test
    public void scalingSeqUpdate() {
        printHeader("Seq: Single element update (O(n) copy per update?)");

        for (int n : SEQ_SIZES) {
            MezzeSeq<Long> seq = MezzeSeq.fromLongs(generateLongs(n));
            int size = n;
            scalingBenchmark("update n=" + n + " (100 updates)", 10, () -> {
                MezzeSeq<Long> s = seq;
                for (int i = 0; i < 100; i++) {
                    s = s.update(RANDOM.nextInt(size), 999L);
                }
                return s.size();
            });
        }
        System.out.println();
    }

    // ==================== MAP SCALING ====================

    @org.junit.jupiter.api.Test
    public void scalingMapPut() {
        printHeader("Map: Incremental Build via put (n inserts → O(n²) total?)");

        for (int n : MAP_SET_SIZES) {
            scalingBenchmark("put n=" + n, WARMUP, () -> {
                MezzeMap<Long, String> m = MezzeMap.empty();
                for (int i = 0; i < n; i++) {
                    m = m.put((long) i, "v" + i);
                }
                return m.size();
            });
        }
        System.out.println();
    }

    @org.junit.jupiter.api.Test
    public void scalingMapGet() {
        printHeader("Map: Random access get (should be O(1) per lookup)");

        for (int n : MAP_SET_SIZES) {
            MezzeMap<Long, String> map = buildMap(n);
            int lookups = Math.min(n, 10000);
            int size = n;
            scalingBenchmark("get n=" + n + " (" + lookups + " lookups)", 10, () -> {
                int found = 0;
                for (int i = 0; i < lookups; i++) {
                    if (map.get((long) RANDOM.nextInt(size)) != null) found++;
                }
                return found;
            });
        }
        System.out.println();
    }

    // ==================== SET SCALING ====================

    @org.junit.jupiter.api.Test
    public void scalingSetAdd() {
        printHeader("Set: Incremental Build via add (n inserts → O(n²) total?)");

        for (int n : MAP_SET_SIZES) {
            scalingBenchmark("add n=" + n, WARMUP, () -> {
                MezzeSet<Long> s = MezzeSet.empty();
                for (int i = 0; i < n; i++) {
                    s = s.add((long) i);
                }
                return s.size();
            });
        }
        System.out.println();
    }

    @org.junit.jupiter.api.Test
    public void scalingSetContains() {
        printHeader("Set: Contains lookup (should be O(1) per lookup)");

        for (int n : MAP_SET_SIZES) {
            MezzeSet<Long> set = buildSet(n);
            int lookups = Math.min(n, 10000);
            int size = n;
            scalingBenchmark("contains n=" + n + " (" + lookups + " lookups)", 10, () -> {
                int found = 0;
                for (int i = 0; i < lookups; i++) {
                    if (set.contains((long) RANDOM.nextInt(size))) found++;
                }
                return found;
            });
        }
        System.out.println();
    }

    // ==================== SORTED MAP SCALING ====================

    @org.junit.jupiter.api.Test
    public void scalingSortedMapPut() {
        printHeader("SortedMap: Incremental Build via put (O(n) array rebuild per insert?)");

        for (int n : SORTED_SIZES) {
            scalingBenchmark("put n=" + n, WARMUP, () -> {
                MezzeSortedMap<Long, String> m = MezzeSortedMap.empty();
                for (int i = 0; i < n; i++) {
                    m = m.put((long) i, "v" + i);
                }
                return m.size();
            });
        }
        System.out.println();
    }

    @org.junit.jupiter.api.Test
    public void scalingSortedMapGet() {
        printHeader("SortedMap: Get (binary search, should be O(log n) per lookup)");

        for (int n : SORTED_SIZES) {
            MezzeSortedMap<Long, String> map = buildSortedMap(n);
            int lookups = Math.min(n, 10000);
            int size = n;
            scalingBenchmark("get n=" + n + " (" + lookups + " lookups)", 10, () -> {
                int found = 0;
                for (int i = 0; i < lookups; i++) {
                    if (map.get((long) RANDOM.nextInt(size)) != null) found++;
                }
                return found;
            });
        }
        System.out.println();
    }

    // ==================== SORTED SET SCALING ====================

    @org.junit.jupiter.api.Test
    public void scalingSortedSetAdd() {
        printHeader("SortedSet: Incremental Build via add (O(n) array rebuild per insert?)");

        for (int n : SORTED_SIZES) {
            scalingBenchmark("add n=" + n, WARMUP, () -> {
                MezzeSortedSet<Long> s = MezzeSortedSet.empty();
                for (int i = 0; i < n; i++) {
                    s = s.add((long) i);
                }
                return s.size();
            });
        }
        System.out.println();
    }

    // ==================== LIST SCALING ====================

    @org.junit.jupiter.api.Test
    public void scalingListPrepend() {
        printHeader("List: Incremental Build via cons/prepend (should be O(n) total)");

        int[] sizes = {100, 1000, 10000, 100000};
        for (int n : sizes) {
            scalingBenchmark("prepend n=" + n, WARMUP, () -> {
                MezzeList<Long> l = MezzeList.empty();
                for (int i = 0; i < n; i++) {
                    l = MezzeList.cons((long) i, l);
                }
                return l.size();
            });
        }
        System.out.println();
    }

    @org.junit.jupiter.api.Test
    public void scalingListMap() {
        printHeader("List: map (accumulate + reverse, should be O(n))");

        int[] sizes = {100, 1000, 10000, 100000};
        for (int n : sizes) {
            MezzeList<Long> list = buildListOfSize(n);
            scalingBenchmark("map n=" + n, WARMUP, () -> {
                return list.map(x -> x * 2).size();
            });
        }
        System.out.println();
    }

    @org.junit.jupiter.api.Test
    public void scalingListFilter() {
        printHeader("List: filter (accumulate + reverse, should be O(n))");

        int[] sizes = {100, 1000, 10000, 100000};
        for (int n : sizes) {
            MezzeList<Long> list = buildListOfSize(n);
            scalingBenchmark("filter n=" + n, WARMUP, () -> {
                return list.filter(x -> x % 2 == 0).size();
            });
        }
        System.out.println();
    }

    @org.junit.jupiter.api.Test
    public void scalingListFold() {
        printHeader("List: fold (iterative, should be O(n))");

        int[] sizes = {100, 1000, 10000, 100000};
        for (int n : sizes) {
            MezzeList<Long> list = buildListOfSize(n);
            scalingBenchmark("fold n=" + n, WARMUP, () -> {
                return list.fold(0L, Long::sum);
            });
        }
        System.out.println();
    }

    @org.junit.jupiter.api.Test
    public void scalingListReverse() {
        printHeader("List: reverse (via fold, should be O(n))");

        int[] sizes = {100, 1000, 10000, 100000};
        for (int n : sizes) {
            MezzeList<Long> list = buildListOfSize(n);
            scalingBenchmark("reverse n=" + n, WARMUP, () -> {
                return list.reverse().size();
            });
        }
        System.out.println();
    }

    private static MezzeList<Long> buildListOfSize(int n) {
        MezzeList<Long> list = MezzeList.empty();
        for (int i = n - 1; i >= 0; i--) {
            list = MezzeList.cons((long) i, list);
        }
        return list;
    }

    // ==================== HELPERS ====================

    private static void scalingBenchmark(String name, int warmupRuns, java.util.function.LongSupplier operation) {
        // Warmup
        for (int i = 0; i < warmupRuns; i++) {
            operation.getAsLong();
        }

        // Measure 5 runs, take median
        long[] times = new long[5];
        for (int run = 0; run < 5; run++) {
            long start = System.nanoTime();
            long result = operation.getAsLong();
            times[run] = System.nanoTime() - start;
            // Use result to prevent DCE
            if (result == Long.MIN_VALUE) System.out.print("");
        }
        java.util.Arrays.sort(times);
        long medianNs = times[2]; // median of 5

        double ms = medianNs / 1_000_000.0;
        double us = medianNs / 1_000.0;

        String line;
        if (ms >= 1.0) {
            line = String.format("  %-50s %10.2f ms%n", name, ms);
        } else {
            line = String.format("  %-50s %10.2f us%n", name, us);
        }
        System.out.print(line);
        RESULTS.append(line);
    }

    private static long[] generateLongs(int count) {
        long[] result = new long[count];
        for (int i = 0; i < count; i++) {
            result[i] = i;
        }
        return result;
    }

    private static MezzeMap<Long, String> buildMap(int count) {
        MezzeMap<Long, String> map = MezzeMap.empty();
        for (int i = 0; i < count; i++) {
            map = map.put((long) i, "v" + i);
        }
        return map;
    }

    private static MezzeSet<Long> buildSet(int count) {
        MezzeSet<Long> set = MezzeSet.empty();
        for (int i = 0; i < count; i++) {
            set = set.add((long) i);
        }
        return set;
    }

    private static MezzeSortedMap<Long, String> buildSortedMap(int count) {
        MezzeSortedMap<Long, String> map = MezzeSortedMap.empty();
        for (int i = 0; i < count; i++) {
            map = map.put((long) i, "v" + i);
        }
        return map;
    }

    private static void printHeader(String title) {
        System.out.println();
        System.out.println("=" .repeat(60));
        System.out.println(" " + title);
        System.out.println("=".repeat(60));
        System.out.println();
        RESULTS.append("\n").append(title).append("\n");
        RESULTS.append("-".repeat(title.length())).append("\n");
    }
}
