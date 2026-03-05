package com.mezze.datastructures.benchmark;

import com.mezze.datastructures.bitset.MezzeBitSet;
import com.mezze.datastructures.bytes.MezzeBytes;
import com.mezze.datastructures.deque.MezzeDeque;
import com.mezze.datastructures.list.MezzeList;
import com.mezze.datastructures.map.LongKeyMapStorage;
import com.mezze.datastructures.map.MezzeMap;
import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.datastructures.set.LongSetStorage;
import com.mezze.datastructures.set.MezzeSet;
import com.mezze.datastructures.sorted.MezzeSortedMap;
import com.mezze.datastructures.sorted.MezzeSortedSet;
import com.mezze.datastructures.util.VectorOps;

import java.util.Random;

/**
 * Comprehensive benchmark suite for all Mezze data structures.
 *
 * Run with:
 *   ./gradlew :datastructures:test --tests "*.DatastructuresBenchmark.runAllBenchmarks" -i
 *
 * Or run individual benchmarks:
 *   ./gradlew :datastructures:test --tests "*.DatastructuresBenchmark.benchmarkMezzeSeq" -i
 */
public class DatastructuresBenchmark {

    // Configuration
    private static final int WARMUP_ITERATIONS = 500;
    private static final int BENCHMARK_ITERATIONS = 5000;
    private static final Random RANDOM = new Random(42);

    // Results storage for comparison
    private static final StringBuilder RESULTS = new StringBuilder();

    public static void main(String[] args) {
        new DatastructuresBenchmark().runAllBenchmarks();
    }

    @org.junit.jupiter.api.Test
    public void runAllBenchmarks() {
        RESULTS.setLength(0);

        printHeader("MEZZE DATASTRUCTURES BENCHMARK SUITE");
        printConfig();

        // Run all benchmarks
        benchmarkMezzeSeq();
        benchmarkMezzeList();
        benchmarkMezzeDeque();
        benchmarkMezzeSet();
        benchmarkMezzeMap();
        benchmarkMezzeSortedSet();
        benchmarkMezzeSortedMap();
        benchmarkMezzeBitSet();
        benchmarkMezzeBytes();
        benchmarkLongSetStorage();
        benchmarkLongKeyMapStorage();
        benchmarkVectorOps();

        // Print summary
        printHeader("BENCHMARK SUMMARY");
        System.out.println(RESULTS.toString());
    }

    // ==================== MEZZE SEQ ====================

    @org.junit.jupiter.api.Test
    public void benchmarkMezzeSeq() {
        printHeader("MezzeSeq (Indexed Sequence)");

        // Setup
        MezzeSeq<Long> seq1000 = MezzeSeq.fromLongs(generateLongs(1000));
        MezzeSeq<Long> seq100 = MezzeSeq.fromLongs(generateLongs(100));

        // Get
        benchmark("get(random index)", () -> {
            long sum = 0;
            for (int i = 0; i < 100; i++) {
                sum += seq1000.getLong(RANDOM.nextInt(1000));
            }
            return sum;
        });

        // Append
        benchmark("append", () -> {
            MezzeSeq<Long> s = MezzeSeq.empty();
            for (int i = 0; i < 100; i++) {
                s = s.append((long) i);
            }
            return s.size();
        });

        // Prepend
        benchmark("prepend", () -> {
            MezzeSeq<Long> s = MezzeSeq.empty();
            for (int i = 0; i < 100; i++) {
                s = s.prepend((long) i);
            }
            return s.size();
        });

        // Map
        benchmark("map (1000 elements)", () -> {
            return seq1000.map(x -> x * 2).size();
        });

        // Filter
        benchmark("filter (1000 elements)", () -> {
            return seq1000.filter(x -> x % 2 == 0).size();
        });

        // Fold
        benchmark("fold/sum (1000 elements)", () -> {
            return seq1000.sumLong();
        });

        // Concat
        benchmark("concat (100 + 100)", () -> {
            return seq100.concat(seq100).size();
        });

        // Slice
        benchmark("slice (1000 -> 500)", () -> {
            return seq1000.slice(250, 750).size();
        });

        // Reverse
        benchmark("reverse (1000 elements)", () -> {
            return seq1000.reverse().size();
        });

        // Sort
        benchmark("sort (100 elements)", () -> {
            return seq100.sort().size();
        });

        System.out.println();
    }

    // ==================== MEZZE LIST ====================

    @org.junit.jupiter.api.Test
    public void benchmarkMezzeList() {
        printHeader("MezzeList (Cons List)");

        // Setup
        MezzeList<Long> list100 = buildList(100);

        // Cons (prepend)
        benchmark("cons (prepend)", () -> {
            MezzeList<Long> l = MezzeList.empty();
            for (int i = 0; i < 100; i++) {
                l = MezzeList.cons((long) i, l);
            }
            return l.size();
        });

        // Head
        benchmark("head", () -> {
            long sum = 0;
            for (int i = 0; i < 100; i++) {
                sum += list100.head();
            }
            return sum;
        });

        // Tail
        benchmark("tail (100 times)", () -> {
            MezzeList<Long> l = list100;
            int count = 0;
            while (l.nonEmpty()) {
                l = l.tail();
                count++;
            }
            return count;
        });

        // Map
        benchmark("map (100 elements)", () -> {
            return list100.map(x -> x * 2).size();
        });

        // Filter
        benchmark("filter (100 elements)", () -> {
            return list100.filter(x -> x % 2 == 0).size();
        });

        // Fold
        benchmark("fold (100 elements)", () -> {
            return list100.fold(0L, Long::sum);
        });

        // Reverse
        benchmark("reverse (100 elements)", () -> {
            return list100.reverse().size();
        });

        System.out.println();
    }

    // ==================== MEZZE DEQUE ====================

    @org.junit.jupiter.api.Test
    public void benchmarkMezzeDeque() {
        printHeader("MezzeDeque (Double-Ended Queue)");

        // Setup
        MezzeDeque<Integer> deque100 = MezzeDeque.of(generateIntegers(100));
        MezzeDeque<Integer> deque50 = MezzeDeque.of(generateIntegers(50));

        // PushFront
        benchmark("pushFront", () -> {
            MezzeDeque<Integer> d = MezzeDeque.empty();
            for (int i = 0; i < 100; i++) {
                d = d.pushFront(i);
            }
            return d.size();
        });

        // PushBack
        benchmark("pushBack", () -> {
            MezzeDeque<Integer> d = MezzeDeque.empty();
            for (int i = 0; i < 100; i++) {
                d = d.pushBack(i);
            }
            return d.size();
        });

        // PopFront
        benchmark("popFront (100 times)", () -> {
            MezzeDeque<Integer> d = deque100;
            int count = 0;
            while (d.nonEmpty()) {
                d = d.popFront().rest();
                count++;
            }
            return count;
        });

        // PopBack
        benchmark("popBack (100 times)", () -> {
            MezzeDeque<Integer> d = deque100;
            int count = 0;
            while (d.nonEmpty()) {
                d = d.popBack().rest();
                count++;
            }
            return count;
        });

        // Get (random access)
        benchmark("get(random index)", () -> {
            int sum = 0;
            for (int i = 0; i < 100; i++) {
                sum += deque100.get(RANDOM.nextInt(100));
            }
            return sum;
        });

        // Concat
        benchmark("concat (50 + 50)", () -> {
            return deque50.concat(deque50).size();
        });

        // Map
        benchmark("map (100 elements)", () -> {
            return deque100.map(x -> x * 2).size();
        });

        // Filter
        benchmark("filter (100 elements)", () -> {
            return deque100.filter(x -> x % 2 == 0).size();
        });

        // Reverse
        benchmark("reverse (100 elements)", () -> {
            return deque100.reverse().size();
        });

        System.out.println();
    }

    // ==================== MEZZE SET ====================

    @org.junit.jupiter.api.Test
    public void benchmarkMezzeSet() {
        printHeader("MezzeSet (Hash Set)");

        // Setup
        MezzeSet<Long> set100 = buildSet(100);
        MezzeSet<Long> set50a = buildSet(50);
        MezzeSet<Long> set50b = buildSetOffset(50, 25);  // 25-74, overlaps with 25-49

        // Add
        benchmark("add", () -> {
            MezzeSet<Long> s = MezzeSet.empty();
            for (int i = 0; i < 100; i++) {
                s = s.add((long) i);
            }
            return s.size();
        });

        // Contains
        benchmark("contains (100 lookups)", () -> {
            int found = 0;
            for (int i = 0; i < 100; i++) {
                if (set100.contains((long) (i * 2))) found++;
            }
            return found;
        });

        // Remove
        benchmark("remove (50 elements)", () -> {
            MezzeSet<Long> s = set100;
            for (int i = 0; i < 50; i++) {
                s = s.remove((long) i);
            }
            return s.size();
        });

        // Union
        benchmark("union (50 + 50)", () -> {
            return set50a.addAll(set50b).size();
        });

        // Map
        benchmark("map (100 elements)", () -> {
            return set100.map(x -> x * 2).size();
        });

        // Filter
        benchmark("filter (100 elements)", () -> {
            return set100.filter(x -> x % 2 == 0).size();
        });

        System.out.println();
    }

    // ==================== MEZZE MAP ====================

    @org.junit.jupiter.api.Test
    public void benchmarkMezzeMap() {
        printHeader("MezzeMap (Hash Map)");

        // Setup
        MezzeMap<Long, String> map100 = buildMap(100);
        MezzeMap<Long, String> map50 = buildMap(50);

        // Put
        benchmark("put", () -> {
            MezzeMap<Long, String> m = MezzeMap.empty();
            for (int i = 0; i < 100; i++) {
                m = m.put((long) i, "value" + i);
            }
            return m.size();
        });

        // Get
        benchmark("get (100 lookups)", () -> {
            int found = 0;
            for (int i = 0; i < 100; i++) {
                if (map100.get((long) i) != null) found++;
            }
            return found;
        });

        // Contains
        benchmark("containsKey (100 lookups)", () -> {
            int found = 0;
            for (int i = 0; i < 100; i++) {
                if (map100.containsKey((long) (i * 2))) found++;
            }
            return found;
        });

        // Remove
        benchmark("remove (50 keys)", () -> {
            MezzeMap<Long, String> m = map100;
            for (int i = 0; i < 50; i++) {
                m = m.remove((long) i);
            }
            return m.size();
        });

        // PutAll
        benchmark("putAll (50 + 50)", () -> {
            return map50.putAll(map50).size();
        });

        // MapValues
        benchmark("mapValues (100 elements)", () -> {
            return map100.mapValues(v -> v + "_mapped").size();
        });

        // Filter
        benchmark("filter (100 elements)", () -> {
            return map100.filter((k, v) -> k % 2 == 0).size();
        });

        System.out.println();
    }

    // ==================== MEZZE SORTED SET ====================

    @org.junit.jupiter.api.Test
    public void benchmarkMezzeSortedSet() {
        printHeader("MezzeSortedSet (Sorted Set)");

        // Setup
        MezzeSortedSet<Long> sortedSet100 = buildSortedSet(100);
        MezzeSortedSet<Long> sortedSet50a = buildSortedSet(50);
        MezzeSortedSet<Long> sortedSet50b = buildSortedSetOffset(50, 25);

        // Add
        benchmark("add", () -> {
            MezzeSortedSet<Long> s = MezzeSortedSet.empty();
            for (int i = 0; i < 100; i++) {
                s = s.add((long) (RANDOM.nextInt(200)));  // Some duplicates
            }
            return s.size();
        });

        // Contains (binary search)
        benchmark("contains (100 lookups)", () -> {
            int found = 0;
            for (int i = 0; i < 100; i++) {
                if (sortedSet100.contains((long) i)) found++;
            }
            return found;
        });

        // Union
        benchmark("union (50 + 50)", () -> {
            return sortedSet50a.union(sortedSet50b).size();
        });

        // Intersection
        benchmark("intersect (50 & 50)", () -> {
            return sortedSet50a.intersect(sortedSet50b).size();
        });

        // Difference
        benchmark("difference (50 - 50)", () -> {
            return sortedSet50a.difference(sortedSet50b).size();
        });

        // Range
        benchmark("range (25 to 75)", () -> {
            return sortedSet100.range(25L, 75L).size();
        });

        // Min/Max
        benchmark("min/max", () -> {
            return sortedSet100.min() + sortedSet100.max();
        });

        System.out.println();
    }

    // ==================== MEZZE SORTED MAP ====================

    @org.junit.jupiter.api.Test
    public void benchmarkMezzeSortedMap() {
        printHeader("MezzeSortedMap (Sorted Map)");

        // Setup
        MezzeSortedMap<Long, String> sortedMap100 = buildSortedMap(100);

        // Put
        benchmark("put", () -> {
            MezzeSortedMap<Long, String> m = MezzeSortedMap.empty();
            for (int i = 0; i < 100; i++) {
                m = m.put((long) i, "value" + i);
            }
            return m.size();
        });

        // Get (binary search)
        benchmark("get (100 lookups)", () -> {
            int found = 0;
            for (int i = 0; i < 100; i++) {
                if (sortedMap100.get((long) i) != null) found++;
            }
            return found;
        });

        // Range
        benchmark("range (25 to 75)", () -> {
            return sortedMap100.range(25L, 75L).size();
        });

        // FloorEntry
        benchmark("floorEntry (100 lookups)", () -> {
            int found = 0;
            for (int i = 0; i < 100; i++) {
                if (sortedMap100.floorEntry((long) i) != null) found++;
            }
            return found;
        });

        // CeilingEntry
        benchmark("ceilingEntry (100 lookups)", () -> {
            int found = 0;
            for (int i = 0; i < 100; i++) {
                if (sortedMap100.ceilingEntry((long) i) != null) found++;
            }
            return found;
        });

        System.out.println();
    }

    // ==================== MEZZE BITSET ====================

    @org.junit.jupiter.api.Test
    public void benchmarkMezzeBitSet() {
        printHeader("MezzeBitSet (Packed Bit Set)");

        // Setup
        MezzeBitSet bitset1000 = MezzeBitSet.range(0, 1000);
        MezzeBitSet bitset500a = MezzeBitSet.range(0, 500);
        MezzeBitSet bitset500b = MezzeBitSet.range(250, 750);

        // Add
        benchmark("add", () -> {
            MezzeBitSet b = MezzeBitSet.empty();
            for (int i = 0; i < 100; i++) {
                b = b.add(i * 10);
            }
            return b.size();
        });

        // Contains
        benchmark("contains (100 lookups)", () -> {
            int found = 0;
            for (int i = 0; i < 100; i++) {
                if (bitset1000.contains(i * 10)) found++;
            }
            return found;
        });

        // Union
        benchmark("union (500 | 500)", () -> {
            return bitset500a.union(bitset500b).size();
        });

        // Intersection
        benchmark("intersect (500 & 500)", () -> {
            return bitset500a.intersect(bitset500b).size();
        });

        // Difference
        benchmark("difference (500 - 500)", () -> {
            return bitset500a.difference(bitset500b).size();
        });

        // Symmetric Difference
        benchmark("symmetricDiff (500 ^ 500)", () -> {
            return bitset500a.symmetricDifference(bitset500b).size();
        });

        // Range creation
        benchmark("range(0, 1000)", () -> {
            return MezzeBitSet.range(0, 1000).size();
        });

        // Complement
        benchmark("complement(1000)", () -> {
            return bitset500a.complement(1000).size();
        });

        System.out.println();
    }

    // ==================== MEZZE BYTES ====================

    @org.junit.jupiter.api.Test
    public void benchmarkMezzeBytes() {
        printHeader("MezzeBytes (Byte Sequence)");

        // Setup
        byte[] data10k = new byte[10000];
        RANDOM.nextBytes(data10k);
        byte[] patternBytes = "SEARCHPATTERN".getBytes();
        System.arraycopy(patternBytes, 0, data10k, 5000, patternBytes.length);

        MezzeBytes bytes10k = MezzeBytes.wrap(data10k);
        MezzeBytes pattern = MezzeBytes.of(patternBytes);
        MezzeBytes bytes1k = bytes10k.slice(0, 1000);

        // IndexOf (single byte)
        benchmark("indexOf(byte) in 10KB", () -> {
            int found = 0;
            for (int i = 0; i < 10; i++) {
                if (bytes10k.indexOf((byte) 0x42) >= 0) found++;
            }
            return found;
        });

        // IndexOf (pattern - KMP)
        benchmark("indexOf(pattern) KMP in 10KB", () -> {
            return bytes10k.indexOf(pattern);
        });

        // Slice
        benchmark("slice (10KB -> 1KB)", () -> {
            return bytes10k.slice(1000, 2000).size();
        });

        // Concat
        benchmark("concat (1KB + 1KB)", () -> {
            return bytes1k.concat(bytes1k).size();
        });

        // StartsWith
        benchmark("startsWith", () -> {
            int matched = 0;
            for (int i = 0; i < 100; i++) {
                if (bytes10k.startsWith(bytes1k)) matched++;
            }
            return matched;
        });

        // EndsWith
        benchmark("endsWith", () -> {
            int matched = 0;
            for (int i = 0; i < 100; i++) {
                if (bytes10k.endsWith(bytes1k)) matched++;
            }
            return matched;
        });

        System.out.println();
    }

    // ==================== LONG SET STORAGE ====================

    @org.junit.jupiter.api.Test
    public void benchmarkLongSetStorage() {
        printHeader("LongSetStorage (Unboxed Long Set)");

        // Setup
        long[] elements32 = generateLongs(32);
        LongSetStorage storage32 = new LongSetStorage(elements32);

        long[] elements16a = generateLongs(16);
        long[] elements16b = generateLongsOffset(16, 8);
        LongSetStorage storage16a = new LongSetStorage(elements16a);
        LongSetStorage storage16b = new LongSetStorage(elements16b);

        // Contains (binary search)
        benchmark("containsLong (100 lookups)", () -> {
            int found = 0;
            for (int i = 0; i < 100; i++) {
                if (storage32.containsLong(i)) found++;
            }
            return found;
        });

        // Add
        benchmark("addLong", () -> {
            LongSetStorage s = new LongSetStorage(new long[0], true);
            for (int i = 0; i < 32; i++) {
                s = (LongSetStorage) s.addLong(i * 3);
            }
            return s.size();
        });

        // Union (two-pointer)
        benchmark("unionLong (16 + 16)", () -> {
            return storage16a.unionLong(storage16b).size();
        });

        // Intersection (two-pointer)
        benchmark("intersectLong (16 & 16)", () -> {
            return storage16a.intersectLong(storage16b).size();
        });

        // Difference (two-pointer)
        benchmark("differenceLong (16 - 16)", () -> {
            return storage16a.differenceLong(storage16b).size();
        });

        // Sum
        benchmark("sum (32 elements)", () -> {
            return storage32.sum();
        });

        // Min
        benchmark("min (32 elements)", () -> {
            return storage32.min();
        });

        // Max
        benchmark("max (32 elements)", () -> {
            return storage32.max();
        });

        System.out.println();
    }

    // ==================== LONG KEY MAP STORAGE ====================

    @org.junit.jupiter.api.Test
    public void benchmarkLongKeyMapStorage() {
        printHeader("LongKeyMapStorage (Unboxed Long Key Map)");

        // Setup
        long[] keys32 = generateLongs(32);
        Object[] values32 = new Object[32];
        for (int i = 0; i < 32; i++) {
            values32[i] = "value" + i;
        }
        LongKeyMapStorage storage32 = new LongKeyMapStorage(keys32, values32);

        // Get (binary search)
        benchmark("getLong (100 lookups)", () -> {
            int found = 0;
            for (int i = 0; i < 100; i++) {
                if (storage32.getLong(i) != null) found++;
            }
            return found;
        });

        // ContainsKey (binary search)
        benchmark("containsKeyLong (100 lookups)", () -> {
            int found = 0;
            for (int i = 0; i < 100; i++) {
                if (storage32.containsKeyLong(i)) found++;
            }
            return found;
        });

        // Put
        benchmark("putLong", () -> {
            LongKeyMapStorage m = new LongKeyMapStorage(new long[0], new Object[0], true);
            for (int i = 0; i < 32; i++) {
                m = (LongKeyMapStorage) m.putLong(i * 5, "val" + i);
            }
            return m.size();
        });

        System.out.println();
    }

    // ==================== VECTOR OPS (SIMD) ====================

    @org.junit.jupiter.api.Test
    public void benchmarkVectorOps() {
        printHeader("VectorOps (SIMD Operations)");

        // Setup
        long[] longs1000 = generateLongs(1000);
        long[] longs1000b = generateLongs(1000);
        double[] doubles1000 = generateDoubles(1000);
        double[] doubles1000b = generateDoubles(1000);

        // Sum Long
        benchmark("sumLong (1000 elements)", () -> {
            return VectorOps.sumLong(longs1000);
        });

        // Sum Double
        benchmark("sumDouble (1000 elements)", () -> {
            return (long) VectorOps.sumDouble(doubles1000);
        });

        // Product Long
        benchmark("productLong (20 elements)", () -> {
            return VectorOps.productLong(java.util.Arrays.copyOf(longs1000, 20));
        });

        // Min/Max Long
        benchmark("minLong (1000 elements)", () -> {
            return VectorOps.minLong(longs1000);
        });

        benchmark("maxLong (1000 elements)", () -> {
            return VectorOps.maxLong(longs1000);
        });

        // Dot Product
        benchmark("dotProduct (1000 elements)", () -> {
            return (long) VectorOps.dotProduct(doubles1000, doubles1000b);
        });

        // Add arrays
        benchmark("addLong (1000 elements)", () -> {
            return VectorOps.addLong(longs1000, longs1000b).length;
        });

        // OR arrays
        benchmark("orLong (1000 elements)", () -> {
            return VectorOps.orLong(longs1000, longs1000b).length;
        });

        // PopCount
        benchmark("popCount (1000 longs)", () -> {
            return VectorOps.popCount(longs1000);
        });

        // IndexOf
        benchmark("indexOfLong (1000 elements)", () -> {
            return VectorOps.indexOfLong(longs1000, 500);
        });

        // Equals
        benchmark("equalsLong (1000 elements)", () -> {
            return VectorOps.equalsLong(longs1000, longs1000) ? 1 : 0;
        });

        System.out.println();
    }

    // ==================== HELPER METHODS ====================

    private static void benchmark(String name, java.util.function.LongSupplier operation) {
        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            operation.getAsLong();
        }

        // Benchmark
        long start = System.nanoTime();
        long result = 0;
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            result += operation.getAsLong();
        }
        long elapsed = System.nanoTime() - start;

        double avgNs = (double) elapsed / BENCHMARK_ITERATIONS;
        double avgUs = avgNs / 1000.0;
        double totalMs = elapsed / 1_000_000.0;

        String line = String.format("  %-35s %8.2f us/op  (%6.2f ms total)%n",
                name, avgUs, totalMs);
        System.out.print(line);

        // Also store in results
        RESULTS.append(line);
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

    private static void printConfig() {
        System.out.printf("Warmup iterations: %d%n", WARMUP_ITERATIONS);
        System.out.printf("Benchmark iterations: %d%n", BENCHMARK_ITERATIONS);
        System.out.printf("Java version: %s%n", System.getProperty("java.version"));
        System.out.printf("SIMD lanes (long): %d%n", VectorOps.longLaneCount());
        System.out.printf("SIMD lanes (double): %d%n", VectorOps.doubleLaneCount());
        System.out.println();
    }

    // Data generators
    private static long[] generateLongs(int count) {
        long[] result = new long[count];
        for (int i = 0; i < count; i++) {
            result[i] = i;
        }
        return result;
    }

    private static long[] generateLongsOffset(int count, int offset) {
        long[] result = new long[count];
        for (int i = 0; i < count; i++) {
            result[i] = i + offset;
        }
        return result;
    }

    private static double[] generateDoubles(int count) {
        double[] result = new double[count];
        for (int i = 0; i < count; i++) {
            result[i] = i + 0.5;
        }
        return result;
    }

    private static Integer[] generateIntegers(int count) {
        Integer[] result = new Integer[count];
        for (int i = 0; i < count; i++) {
            result[i] = i;
        }
        return result;
    }

    private static MezzeList<Long> buildList(int count) {
        MezzeList<Long> list = MezzeList.empty();
        for (int i = count - 1; i >= 0; i--) {
            list = MezzeList.cons((long) i, list);
        }
        return list;
    }

    private static MezzeSet<Long> buildSet(int count) {
        MezzeSet<Long> set = MezzeSet.empty();
        for (int i = 0; i < count; i++) {
            set = set.add((long) i);
        }
        return set;
    }

    private static MezzeSet<Long> buildSetOffset(int count, int offset) {
        MezzeSet<Long> set = MezzeSet.empty();
        for (int i = 0; i < count; i++) {
            set = set.add((long) (i + offset));
        }
        return set;
    }

    private static MezzeMap<Long, String> buildMap(int count) {
        MezzeMap<Long, String> map = MezzeMap.empty();
        for (int i = 0; i < count; i++) {
            map = map.put((long) i, "value" + i);
        }
        return map;
    }

    private static MezzeSortedSet<Long> buildSortedSet(int count) {
        MezzeSortedSet<Long> set = MezzeSortedSet.empty();
        for (int i = 0; i < count; i++) {
            set = set.add((long) i);
        }
        return set;
    }

    private static MezzeSortedSet<Long> buildSortedSetOffset(int count, int offset) {
        MezzeSortedSet<Long> set = MezzeSortedSet.empty();
        for (int i = 0; i < count; i++) {
            set = set.add((long) (i + offset));
        }
        return set;
    }

    private static MezzeSortedMap<Long, String> buildSortedMap(int count) {
        MezzeSortedMap<Long, String> map = MezzeSortedMap.empty();
        for (int i = 0; i < count; i++) {
            map = map.put((long) i, "value" + i);
        }
        return map;
    }
}
