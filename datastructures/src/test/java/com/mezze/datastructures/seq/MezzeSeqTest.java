package com.mezze.datastructures.seq;

import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for MezzeSeq.
 * Tests all storage strategies and transitions.
 */
public class MezzeSeqTest {

    // ===== CONSTRUCTION =====

    @Test
    void testEmptySeq() {
        MezzeSeq<Object> seq = MezzeSeq.empty();
        assertEquals(0, seq.size());
        assertTrue(seq.isEmpty());
    }

    @Test
    void testSingleElement() {
        MezzeSeq<String> seq = MezzeSeq.of("hello");
        assertEquals(1, seq.size());
        assertFalse(seq.isEmpty());
        assertEquals("hello", seq.get(0));
    }

    @Test
    void testMultipleElements() {
        MezzeSeq<Integer> seq = MezzeSeq.of(1, 2, 3, 4, 5);
        assertEquals(5, seq.size());
        assertEquals(1, seq.get(0));
        assertEquals(5, seq.get(4));
    }

    @Test
    void testFromLongsUsesSpecializedStorage() {
        MezzeSeq<Long> seq = MezzeSeq.fromLongs(1L, 2L, 3L);
        assertEquals(3, seq.size());
        assertEquals(1L, seq.get(0));
        assertEquals(3L, seq.get(2));
    }

    @Test
    void testFromDoublesUsesSpecializedStorage() {
        MezzeSeq<Double> seq = MezzeSeq.fromDoubles(1.5, 2.5, 3.5);
        assertEquals(3, seq.size());
        assertEquals(1.5, seq.get(0));
        assertEquals(3.5, seq.get(2));
    }

    @Test
    void testFromBooleansUsesPackedStorage() {
        MezzeSeq<Boolean> seq = MezzeSeq.fromBooleans(true, false, true, false);
        assertEquals(4, seq.size());
        assertTrue(seq.get(0));
        assertFalse(seq.get(1));
        assertTrue(seq.get(2));
    }

    @Test
    void testLargeBooleanSeq() {
        // Test packed storage with more than 64 booleans
        boolean[] values = new boolean[100];
        for (int i = 0; i < 100; i++) {
            values[i] = i % 3 == 0;
        }
        MezzeSeq<Boolean> seq = MezzeSeq.fromBooleans(values);
        assertEquals(100, seq.size());
        for (int i = 0; i < 100; i++) {
            assertEquals(i % 3 == 0, seq.get(i));
        }
    }

    // ===== ACCESS =====

    @Test
    void testGetOutOfBoundsThrows() {
        MezzeSeq<Integer> seq = MezzeSeq.of(1, 2, 3);
        assertThrows(IndexOutOfBoundsException.class, () -> seq.get(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> seq.get(3));
    }

    @Test
    void testFirst() {
        MezzeSeq<Integer> seq = MezzeSeq.of(1, 2, 3);
        assertEquals(1, seq.first());
    }

    @Test
    void testLast() {
        MezzeSeq<Integer> seq = MezzeSeq.of(1, 2, 3);
        assertEquals(3, seq.last());
    }

    // ===== UPDATE =====

    @Test
    void testUpdateReturnsNewSeq() {
        MezzeSeq<Integer> seq1 = MezzeSeq.of(1, 2, 3);
        MezzeSeq<Integer> seq2 = seq1.update(1, 42);

        assertEquals(2, seq1.get(1)); // Original unchanged
        assertEquals(42, seq2.get(1)); // New has updated value
    }

    @Test
    void testAppend() {
        MezzeSeq<Integer> seq = MezzeSeq.of(1, 2, 3);
        MezzeSeq<Integer> result = seq.append(4);

        assertEquals(3, seq.size());
        assertEquals(4, result.size());
        assertEquals(4, result.get(3));
    }

    @Test
    void testPrepend() {
        MezzeSeq<Integer> seq = MezzeSeq.of(2, 3, 4);
        MezzeSeq<Integer> result = seq.prepend(1);

        assertEquals(3, seq.size());
        assertEquals(4, result.size());
        assertEquals(1, result.get(0));
    }

    // ===== SLICING =====

    @Test
    void testSlice() {
        MezzeSeq<Integer> seq = MezzeSeq.of(1, 2, 3, 4, 5);
        MezzeSeq<Integer> slice = seq.slice(1, 4);

        assertEquals(3, slice.size());
        assertEquals(2, slice.get(0));
        assertEquals(4, slice.get(2));
    }

    @Test
    void testTake() {
        MezzeSeq<Integer> seq = MezzeSeq.of(1, 2, 3, 4, 5);
        MezzeSeq<Integer> taken = seq.take(3);

        assertEquals(3, taken.size());
        assertEquals(1, taken.get(0));
        assertEquals(3, taken.get(2));
    }

    @Test
    void testDrop() {
        MezzeSeq<Integer> seq = MezzeSeq.of(1, 2, 3, 4, 5);
        MezzeSeq<Integer> dropped = seq.drop(2);

        assertEquals(3, dropped.size());
        assertEquals(3, dropped.get(0));
        assertEquals(5, dropped.get(2));
    }

    // ===== CONCATENATION =====

    @Test
    void testConcat() {
        MezzeSeq<Integer> seq1 = MezzeSeq.of(1, 2, 3);
        MezzeSeq<Integer> seq2 = MezzeSeq.of(4, 5, 6);
        MezzeSeq<Integer> result = seq1.concat(seq2);

        assertEquals(6, result.size());
        assertEquals(1, result.get(0));
        assertEquals(6, result.get(5));
    }

    @Test
    void testConcatWithEmpty() {
        MezzeSeq<Integer> seq = MezzeSeq.of(1, 2, 3);
        MezzeSeq<Integer> empty = MezzeSeq.empty();

        assertSame(seq, seq.concat(empty));
        assertSame(seq, empty.concat(seq));
    }

    // ===== SEARCH =====

    @Test
    void testIndexOf() {
        MezzeSeq<String> seq = MezzeSeq.of("a", "b", "c", "b");
        assertEquals(1, seq.indexOf("b"));
        assertEquals(-1, seq.indexOf("z"));
    }

    @Test
    void testContains() {
        MezzeSeq<Integer> seq = MezzeSeq.of(1, 2, 3);
        assertTrue(seq.contains(2));
        assertFalse(seq.contains(5));
    }

    // ===== NUMERIC OPERATIONS (Long Storage) =====

    @Test
    void testLongSum() {
        MezzeSeq<Long> seq = MezzeSeq.fromLongs(1L, 2L, 3L, 4L, 5L);
        assertEquals(15L, seq.sumLong());
    }

    @Test
    void testLongProduct() {
        MezzeSeq<Long> seq = MezzeSeq.fromLongs(1L, 2L, 3L, 4L);
        assertEquals(24L, seq.productLong());
    }

    @Test
    void testLongMin() {
        MezzeSeq<Long> seq = MezzeSeq.fromLongs(5L, 2L, 8L, 1L, 9L);
        assertEquals(1L, seq.minLong());
    }

    @Test
    void testLongMax() {
        MezzeSeq<Long> seq = MezzeSeq.fromLongs(5L, 2L, 8L, 1L, 9L);
        assertEquals(9L, seq.maxLong());
    }

    // ===== NUMERIC OPERATIONS (Double Storage) =====

    @Test
    void testDoubleSum() {
        MezzeSeq<Double> seq = MezzeSeq.fromDoubles(1.5, 2.5, 3.0);
        assertEquals(7.0, seq.sumDouble(), 0.0001);
    }

    @Test
    void testDoubleProduct() {
        MezzeSeq<Double> seq = MezzeSeq.fromDoubles(2.0, 3.0, 4.0);
        assertEquals(24.0, seq.productDouble(), 0.0001);
    }

    @Test
    void testDoubleMin() {
        MezzeSeq<Double> seq = MezzeSeq.fromDoubles(5.5, 2.2, 8.8, 1.1);
        assertEquals(1.1, seq.minDouble(), 0.0001);
    }

    @Test
    void testDoubleMax() {
        MezzeSeq<Double> seq = MezzeSeq.fromDoubles(5.5, 2.2, 8.8, 1.1);
        assertEquals(8.8, seq.maxDouble(), 0.0001);
    }

    // ===== TRANSFORMS =====

    @Test
    void testMap() {
        MezzeSeq<Integer> seq = MezzeSeq.of(1, 2, 3);
        MezzeSeq<Integer> result = seq.map(x -> x * 2);

        assertEquals(3, result.size());
        assertEquals(2, result.get(0));
        assertEquals(4, result.get(1));
        assertEquals(6, result.get(2));
    }

    @Test
    void testFilter() {
        MezzeSeq<Integer> seq = MezzeSeq.of(1, 2, 3, 4, 5);
        MezzeSeq<Integer> result = seq.filter(x -> x % 2 == 0);

        assertEquals(2, result.size());
        assertEquals(2, result.get(0));
        assertEquals(4, result.get(1));
    }

    @Test
    void testFold() {
        MezzeSeq<Integer> seq = MezzeSeq.of(1, 2, 3, 4);
        int sum = seq.fold(0, Integer::sum);
        assertEquals(10, sum);
    }

    @Test
    void testFoldRight() {
        MezzeSeq<String> seq = MezzeSeq.of("a", "b", "c");
        String result = seq.foldRight("", (s, acc) -> s + acc);
        assertEquals("abc", result);
    }

    @Test
    void testReverse() {
        MezzeSeq<Integer> seq = MezzeSeq.of(1, 2, 3);
        MezzeSeq<Integer> reversed = seq.reverse();

        assertEquals(3, reversed.get(0));
        assertEquals(2, reversed.get(1));
        assertEquals(1, reversed.get(2));
    }

    // ===== ITERATION =====

    @Test
    void testIteration() {
        MezzeSeq<Integer> seq = MezzeSeq.of(1, 2, 3);
        List<Integer> collected = new ArrayList<>();
        for (Integer i : seq) {
            collected.add(i);
        }
        assertEquals(List.of(1, 2, 3), collected);
    }

    // ===== EQUALITY =====

    @Test
    void testEquality() {
        MezzeSeq<Integer> seq1 = MezzeSeq.of(1, 2, 3);
        MezzeSeq<Integer> seq2 = MezzeSeq.of(1, 2, 3);
        MezzeSeq<Integer> seq3 = MezzeSeq.of(1, 2, 4);

        assertEquals(seq1, seq2);
        assertNotEquals(seq1, seq3);
    }

    @Test
    void testHashCode() {
        MezzeSeq<Integer> seq1 = MezzeSeq.of(1, 2, 3);
        MezzeSeq<Integer> seq2 = MezzeSeq.of(1, 2, 3);

        assertEquals(seq1.hashCode(), seq2.hashCode());
    }

    // ===== EDGE CASES =====

    @Test
    void testOperationsOnEmpty() {
        MezzeSeq<Integer> empty = MezzeSeq.empty();

        assertEquals(0, empty.size());
        assertTrue(empty.isEmpty());
        assertSame(empty, empty.reverse());
        assertTrue(empty.take(10).isEmpty());
        assertTrue(empty.drop(10).isEmpty());
        assertEquals(-1, empty.indexOf(1));
    }

    @Test
    void testLargeSeq() {
        // Test with a large number of elements
        int n = 10000;
        Long[] values = new Long[n];
        for (int i = 0; i < n; i++) {
            values[i] = (long) i;
        }
        MezzeSeq<Long> seq = MezzeSeq.of(values);

        assertEquals(n, seq.size());
        assertEquals(0L, seq.get(0));
        assertEquals((long)(n - 1), seq.get(n - 1));
    }

    // ===== ADDITIONAL COVERAGE TESTS =====

    @Test
    void testNonEmpty() {
        MezzeSeq<Integer> seq = MezzeSeq.of(1, 2, 3);
        assertTrue(seq.nonEmpty());
        assertFalse(MezzeSeq.empty().nonEmpty());
    }

    @Test
    void testFromArray() {
        Integer[] arr = {1, 2, 3, 4, 5};
        MezzeSeq<Integer> seq = MezzeSeq.fromArray(arr);
        assertEquals(5, seq.size());
        assertEquals(1, seq.get(0));
        assertEquals(5, seq.get(4));
    }

    @Test
    void testRange() {
        MezzeSeq<Long> seq = MezzeSeq.range(1, 5);
        assertEquals(4, seq.size());
        assertEquals(1L, seq.get(0));
        assertEquals(4L, seq.get(3));
    }

    @Test
    void testRangeWithStep() {
        MezzeSeq<Long> seq = MezzeSeq.range(0, 10, 2);
        assertEquals(5, seq.size());
        assertEquals(0L, seq.get(0));
        assertEquals(8L, seq.get(4));
    }

    @Test
    void testFill() {
        MezzeSeq<String> seq = MezzeSeq.fill(5, "x");
        assertEquals(5, seq.size());
        for (int i = 0; i < 5; i++) {
            assertEquals("x", seq.get(i));
        }
    }

    @Test
    void testTabulate() {
        MezzeSeq<Integer> seq = MezzeSeq.tabulate(5, i -> i * i);
        assertEquals(5, seq.size());
        assertEquals(0, seq.get(0));
        assertEquals(1, seq.get(1));
        assertEquals(4, seq.get(2));
        assertEquals(16, seq.get(4));
    }

    @Test
    void testGetLong() {
        MezzeSeq<Long> seq = MezzeSeq.fromLongs(10L, 20L, 30L);
        assertEquals(10L, seq.getLong(0));
        assertEquals(30L, seq.getLong(2));
    }

    @Test
    void testGetDouble() {
        MezzeSeq<Double> seq = MezzeSeq.fromDoubles(1.5, 2.5, 3.5);
        assertEquals(1.5, seq.getDouble(0), 0.0001);
        assertEquals(3.5, seq.getDouble(2), 0.0001);
    }

    @Test
    void testGetBoolean() {
        MezzeSeq<Boolean> seq = MezzeSeq.fromBooleans(true, false, true);
        assertTrue(seq.getBoolean(0));
        assertFalse(seq.getBoolean(1));
        assertTrue(seq.getBoolean(2));
    }

    @Test
    void testInsert() {
        MezzeSeq<Integer> seq = MezzeSeq.of(1, 2, 4, 5);
        MezzeSeq<Integer> result = seq.insert(2, 3);
        assertEquals(5, result.size());
        assertEquals(1, result.get(0));
        assertEquals(2, result.get(1));
        assertEquals(3, result.get(2));
        assertEquals(4, result.get(3));
        assertEquals(5, result.get(4));
    }

    @Test
    void testRemove() {
        MezzeSeq<Integer> seq = MezzeSeq.of(1, 2, 3, 4, 5);
        MezzeSeq<Integer> result = seq.remove(2);
        assertEquals(4, result.size());
        assertEquals(1, result.get(0));
        assertEquals(2, result.get(1));
        assertEquals(4, result.get(2));
        assertEquals(5, result.get(3));
    }

    @Test
    void testTakeWhile() {
        MezzeSeq<Integer> seq = MezzeSeq.of(1, 2, 3, 4, 5);
        MezzeSeq<Integer> result = seq.takeWhile(x -> x < 4);
        assertEquals(3, result.size());
        assertEquals(1, result.get(0));
        assertEquals(3, result.get(2));
    }

    @Test
    void testDropWhile() {
        MezzeSeq<Integer> seq = MezzeSeq.of(1, 2, 3, 4, 5);
        MezzeSeq<Integer> result = seq.dropWhile(x -> x < 3);
        assertEquals(3, result.size());
        assertEquals(3, result.get(0));
        assertEquals(5, result.get(2));
    }

    @Test
    void testFlatMap() {
        MezzeSeq<Integer> seq = MezzeSeq.of(1, 2, 3);
        MezzeSeq<Integer> result = seq.flatMap(x -> MezzeSeq.of(x, x * 10));
        assertEquals(6, result.size());
        assertEquals(1, result.get(0));
        assertEquals(10, result.get(1));
        assertEquals(2, result.get(2));
        assertEquals(20, result.get(3));
    }

    @Test
    void testFilterNot() {
        MezzeSeq<Integer> seq = MezzeSeq.of(1, 2, 3, 4, 5);
        MezzeSeq<Integer> result = seq.filterNot(x -> x % 2 == 0);
        assertEquals(3, result.size());
        assertEquals(1, result.get(0));
        assertEquals(3, result.get(1));
        assertEquals(5, result.get(2));
    }

    @Test
    void testReduce() {
        MezzeSeq<Integer> seq = MezzeSeq.of(1, 2, 3, 4);
        int result = seq.reduce(Integer::sum);
        assertEquals(10, result);
    }

    @Test
    void testReduceOnEmpty() {
        MezzeSeq<Integer> empty = MezzeSeq.empty();
        assertThrows(java.util.NoSuchElementException.class, () -> empty.reduce(Integer::sum));
    }

    @Test
    void testAverage() {
        MezzeSeq<Double> seq = MezzeSeq.fromDoubles(2.0, 4.0, 6.0);
        assertEquals(4.0, seq.average(), 0.0001);
    }

    @Test
    void testFind() {
        MezzeSeq<Integer> seq = MezzeSeq.of(1, 2, 3, 4, 5);
        assertEquals(Integer.valueOf(4), seq.find(x -> x > 3));
        assertNull(seq.find(x -> x > 10));
    }

    @Test
    void testFindIndex() {
        MezzeSeq<Integer> seq = MezzeSeq.of(1, 2, 3, 4, 5);
        assertEquals(3, seq.findIndex(x -> x > 3));
        assertEquals(-1, seq.findIndex(x -> x > 10));
    }

    @Test
    void testExists() {
        MezzeSeq<Integer> seq = MezzeSeq.of(1, 2, 3, 4, 5);
        assertTrue(seq.exists(x -> x == 3));
        assertFalse(seq.exists(x -> x == 10));
    }

    @Test
    void testForall() {
        MezzeSeq<Integer> seq = MezzeSeq.of(2, 4, 6, 8);
        assertTrue(seq.forall(x -> x % 2 == 0));
        assertFalse(seq.forall(x -> x < 5));
    }

    @Test
    void testCount() {
        MezzeSeq<Integer> seq = MezzeSeq.of(1, 2, 3, 4, 5, 6);
        assertEquals(3, seq.count(x -> x % 2 == 0));
    }

    @Test
    void testSort() {
        MezzeSeq<Integer> seq = MezzeSeq.of(3, 1, 4, 1, 5, 9, 2, 6);
        MezzeSeq<Integer> sorted = seq.sort();
        assertEquals(1, sorted.get(0));
        assertEquals(1, sorted.get(1));
        assertEquals(2, sorted.get(2));
        assertEquals(9, sorted.get(7));
    }

    @Test
    void testDistinct() {
        MezzeSeq<Integer> seq = MezzeSeq.of(1, 2, 2, 3, 3, 3, 4);
        MezzeSeq<Integer> distinct = seq.distinct();
        assertEquals(4, distinct.size());
    }

    @Test
    void testPartition() {
        MezzeSeq<Integer> seq = MezzeSeq.of(1, 2, 3, 4, 5, 6);
        MezzeSeq<Integer>[] parts = seq.partition(x -> x % 2 == 0);
        assertEquals(3, parts[0].size()); // evens
        assertEquals(3, parts[1].size()); // odds
    }

    @Test
    void testSplitAt() {
        MezzeSeq<Integer> seq = MezzeSeq.of(1, 2, 3, 4, 5);
        MezzeSeq<Integer>[] parts = seq.splitAt(3);
        assertEquals(3, parts[0].size());
        assertEquals(2, parts[1].size());
        assertEquals(1, parts[0].get(0));
        assertEquals(4, parts[1].get(0));
    }

    @Test
    void testZip() {
        MezzeSeq<Integer> seq1 = MezzeSeq.of(1, 2, 3);
        MezzeSeq<String> seq2 = MezzeSeq.of("a", "b", "c");
        MezzeSeq<Object[]> zipped = seq1.zip(seq2);
        assertEquals(3, zipped.size());
        assertEquals(1, zipped.get(0)[0]);
        assertEquals("a", zipped.get(0)[1]);
    }

    @Test
    void testZipWithIndex() {
        MezzeSeq<String> seq = MezzeSeq.of("a", "b", "c");
        MezzeSeq<Object[]> indexed = seq.zipWithIndex();
        assertEquals(3, indexed.size());
        assertEquals("a", indexed.get(0)[0]);
        assertEquals(0L, indexed.get(0)[1]);
        assertEquals("c", indexed.get(2)[0]);
        assertEquals(2L, indexed.get(2)[1]);
    }

    @Test
    void testForeach() {
        MezzeSeq<Integer> seq = MezzeSeq.of(1, 2, 3);
        List<Integer> collected = new ArrayList<>();
        seq.foreach(collected::add);
        assertEquals(List.of(1, 2, 3), collected);
    }

    @Test
    void testGetStorage() {
        MezzeSeq<Long> seq = MezzeSeq.fromLongs(1L, 2L, 3L);
        assertNotNull(seq.getStorage());
        assertTrue(seq.isLongStorage());
    }

    @Test
    void testIsDoubleStorage() {
        MezzeSeq<Double> seq = MezzeSeq.fromDoubles(1.0, 2.0);
        assertTrue(seq.isDoubleStorage());
    }

    @Test
    void testIsBooleanStorage() {
        MezzeSeq<Boolean> seq = MezzeSeq.fromBooleans(true, false);
        assertTrue(seq.isBooleanStorage());
    }

    @Test
    void testToString() {
        MezzeSeq<Integer> seq = MezzeSeq.of(1, 2, 3);
        String str = seq.toString();
        assertTrue(str.contains("1"));
        assertTrue(str.contains("2"));
        assertTrue(str.contains("3"));
    }

    @Test
    void testEqualsDifferentSize() {
        MezzeSeq<Integer> seq1 = MezzeSeq.of(1, 2, 3);
        MezzeSeq<Integer> seq2 = MezzeSeq.of(1, 2);
        assertNotEquals(seq1, seq2);
    }

    @Test
    void testEqualsNull() {
        MezzeSeq<Integer> seq = MezzeSeq.of(1, 2, 3);
        assertNotEquals(seq, null);
    }

    @Test
    void testEqualsOtherType() {
        MezzeSeq<Integer> seq = MezzeSeq.of(1, 2, 3);
        assertNotEquals(seq, "not a seq");
    }
}
