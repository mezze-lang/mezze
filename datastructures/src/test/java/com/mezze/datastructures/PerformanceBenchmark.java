package com.mezze.datastructures;

import com.mezze.datastructures.bitset.MezzeBitSet;
import com.mezze.datastructures.bytes.MezzeBytes;
import com.mezze.datastructures.deque.MezzeDeque;
import com.mezze.datastructures.map.LongKeyMapStorage;
import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.datastructures.set.LongSetStorage;

import org.junit.jupiter.api.Test;

import java.util.Random;

/**
 * Performance benchmark for datastructures optimizations.
 * Run with: ./gradlew :datastructures:test --tests "*.PerformanceBenchmark" -i
 */
public class PerformanceBenchmark {

    private static final int WARMUP_ITERATIONS = 1000;
    private static final int BENCHMARK_ITERATIONS = 10000;
    private static final Random RANDOM = new Random(42);

    @Test
    public void runAllBenchmarks() {
        System.out.println("\n=== Datastructures Performance Benchmark ===\n");

        benchmarkLongSetStorageBinarySearch();
        benchmarkLongSetStorageSetOperations();
        benchmarkMezzeBytesPatternSearch();
        benchmarkMezzeSeqMap();
        benchmarkMezzeDequeConcat();
        benchmarkMezzeBitSetOperations();
        benchmarkLongKeyMapStorage();
    }

    /**
     * Benchmark 1: LongSetStorage binary search vs linear search
     */
    private static void benchmarkLongSetStorageBinarySearch() {
        System.out.println("--- Benchmark 1: LongSetStorage Binary Search ---");

        // Create storage with 32 elements
        long[] elements = new long[32];
        for (int i = 0; i < 32; i++) {
            elements[i] = i * 3;  // 0, 3, 6, 9, ...
        }
        LongSetStorage storage = new LongSetStorage(elements);

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            storage.containsLong(45);
            storage.containsLong(99);
        }

        // Benchmark
        long start = System.nanoTime();
        int found = 0;
        for (int iter = 0; iter < BENCHMARK_ITERATIONS; iter++) {
            for (int i = 0; i < 100; i++) {
                if (storage.containsLong(i)) found++;
            }
        }
        long elapsed = System.nanoTime() - start;

        System.out.printf("  Contains (100 lookups x %d): %.2f ms%n",
                BENCHMARK_ITERATIONS, elapsed / 1_000_000.0);
        System.out.printf("  Found: %d%n%n", found);
    }

    /**
     * Benchmark 2: LongSetStorage set operations (two-pointer vs nested loops)
     */
    private static void benchmarkLongSetStorageSetOperations() {
        System.out.println("--- Benchmark 2: LongSetStorage Set Operations ---");

        long[] elements1 = new long[32];
        long[] elements2 = new long[32];
        for (int i = 0; i < 32; i++) {
            elements1[i] = i * 2;      // Even: 0, 2, 4, ...
            elements2[i] = i * 2 + 1;  // Odd: 1, 3, 5, ... (some overlap by design)
        }
        // Add some overlap
        for (int i = 16; i < 32; i++) {
            elements2[i] = i * 2;  // Now overlaps with elements1
        }

        LongSetStorage set1 = new LongSetStorage(elements1);
        LongSetStorage set2 = new LongSetStorage(elements2);

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            set1.unionLong(set2);
            set1.intersectLong(set2);
            set1.differenceLong(set2);
        }

        // Benchmark union
        long start = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            set1.unionLong(set2);
        }
        long unionTime = System.nanoTime() - start;

        // Benchmark intersection
        start = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            set1.intersectLong(set2);
        }
        long intersectTime = System.nanoTime() - start;

        // Benchmark difference
        start = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            set1.differenceLong(set2);
        }
        long diffTime = System.nanoTime() - start;

        System.out.printf("  Union (%d iterations): %.2f ms%n", BENCHMARK_ITERATIONS, unionTime / 1_000_000.0);
        System.out.printf("  Intersection (%d iterations): %.2f ms%n", BENCHMARK_ITERATIONS, intersectTime / 1_000_000.0);
        System.out.printf("  Difference (%d iterations): %.2f ms%n%n", BENCHMARK_ITERATIONS, diffTime / 1_000_000.0);
    }

    /**
     * Benchmark 3: MezzeBytes KMP pattern search
     */
    private static void benchmarkMezzeBytesPatternSearch() {
        System.out.println("--- Benchmark 3: MezzeBytes KMP Pattern Search ---");

        // Create a large byte sequence with a pattern somewhere in the middle
        byte[] data = new byte[10000];
        RANDOM.nextBytes(data);

        // Put a specific pattern at position 5000
        byte[] patternBytes = "FINDTHISPATTERN".getBytes();
        System.arraycopy(patternBytes, 0, data, 5000, patternBytes.length);

        MezzeBytes bytes = MezzeBytes.wrap(data);
        MezzeBytes pattern = MezzeBytes.of(patternBytes);

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            bytes.indexOf(pattern);
        }

        // Benchmark
        long start = System.nanoTime();
        int foundAt = -1;
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            foundAt = bytes.indexOf(pattern);
        }
        long elapsed = System.nanoTime() - start;

        System.out.printf("  Pattern search in 10KB (%d iterations): %.2f ms%n",
                BENCHMARK_ITERATIONS, elapsed / 1_000_000.0);
        System.out.printf("  Found at: %d%n%n", foundAt);
    }

    /**
     * Benchmark 4: MezzeSeq.map() with pre-allocation
     */
    private static void benchmarkMezzeSeqMap() {
        System.out.println("--- Benchmark 4: MezzeSeq.map() Pre-allocation ---");

        MezzeSeq<Long> seq = MezzeSeq.fromLongs(
                java.util.stream.LongStream.range(0, 1000).toArray()
        );

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            seq.map(x -> x * 2);
        }

        // Benchmark
        long start = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            seq.map(x -> x * 2);
        }
        long elapsed = System.nanoTime() - start;

        System.out.printf("  Map 1000 elements (%d iterations): %.2f ms%n%n",
                BENCHMARK_ITERATIONS, elapsed / 1_000_000.0);
    }

    /**
     * Benchmark 5: MezzeDeque.concat() with bulk copy
     */
    private static void benchmarkMezzeDequeConcat() {
        System.out.println("--- Benchmark 5: MezzeDeque.concat() Bulk Copy ---");

        // Create two deques
        MezzeDeque<Integer> deque1 = MezzeDeque.empty();
        MezzeDeque<Integer> deque2 = MezzeDeque.empty();
        for (int i = 0; i < 100; i++) {
            deque1 = deque1.pushBack(i);
            deque2 = deque2.pushBack(i + 100);
        }

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            deque1.concat(deque2);
        }

        // Benchmark
        long start = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            deque1.concat(deque2);
        }
        long elapsed = System.nanoTime() - start;

        System.out.printf("  Concat 100+100 elements (%d iterations): %.2f ms%n%n",
                BENCHMARK_ITERATIONS, elapsed / 1_000_000.0);
    }

    /**
     * Benchmark 6: MezzeBitSet operations with inline cardinality
     */
    private static void benchmarkMezzeBitSetOperations() {
        System.out.println("--- Benchmark 6: MezzeBitSet Inline Cardinality ---");

        // Create bitsets with overlapping ranges
        MezzeBitSet set1 = MezzeBitSet.range(0, 1000);
        MezzeBitSet set2 = MezzeBitSet.range(500, 1500);

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            set1.union(set2);
            set1.intersect(set2);
            set1.difference(set2);
        }

        // Benchmark union
        long start = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            set1.union(set2);
        }
        long unionTime = System.nanoTime() - start;

        // Benchmark intersection
        start = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            set1.intersect(set2);
        }
        long intersectTime = System.nanoTime() - start;

        // Benchmark difference
        start = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            set1.difference(set2);
        }
        long diffTime = System.nanoTime() - start;

        System.out.printf("  Union 1000-bit sets (%d iterations): %.2f ms%n", BENCHMARK_ITERATIONS, unionTime / 1_000_000.0);
        System.out.printf("  Intersection (%d iterations): %.2f ms%n", BENCHMARK_ITERATIONS, intersectTime / 1_000_000.0);
        System.out.printf("  Difference (%d iterations): %.2f ms%n%n", BENCHMARK_ITERATIONS, diffTime / 1_000_000.0);
    }

    /**
     * Benchmark 7: LongKeyMapStorage binary search
     */
    private static void benchmarkLongKeyMapStorage() {
        System.out.println("--- Benchmark 7: LongKeyMapStorage Binary Search ---");

        // Create map with 32 entries
        long[] keys = new long[32];
        Object[] values = new Object[32];
        for (int i = 0; i < 32; i++) {
            keys[i] = i * 5;  // 0, 5, 10, 15, ...
            values[i] = "value" + i;
        }
        LongKeyMapStorage map = new LongKeyMapStorage(keys, values);

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            map.getLong(75);
            map.getLong(999);
        }

        // Benchmark
        long start = System.nanoTime();
        int found = 0;
        for (int iter = 0; iter < BENCHMARK_ITERATIONS; iter++) {
            for (int i = 0; i < 100; i++) {
                if (map.getLong(i) != null) found++;
            }
        }
        long elapsed = System.nanoTime() - start;

        System.out.printf("  Get (100 lookups x %d): %.2f ms%n",
                BENCHMARK_ITERATIONS, elapsed / 1_000_000.0);
        System.out.printf("  Found: %d%n%n", found);
    }
}
