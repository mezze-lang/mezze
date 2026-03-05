package com.mezze.datastructures.list;

import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for MezzeList (immutable linked list).
 * Tests specialized storage and transitions.
 */
public class MezzeListTest {

    // ===== CONSTRUCTION =====

    @Test
    void testNil() {
        MezzeList<Object> list = MezzeList.empty();
        assertTrue(list.isEmpty());
        assertEquals(0, list.size());
    }

    @Test
    void testPrepend() {
        MezzeList<String> list = MezzeList.<String>empty().prepend("hello");
        assertFalse(list.isEmpty());
        assertEquals(1, list.size());
        assertEquals("hello", list.head());
    }

    @Test
    void testOf() {
        MezzeList<Integer> list = MezzeList.of(1, 2, 3);
        assertEquals(3, list.size());
        assertEquals(1, list.head());
    }

    @Test
    void testOfLongsUsesSpecializedStorage() {
        MezzeList<Long> list = MezzeList.ofLongs(1L, 2L, 3L);
        assertEquals(3, list.size());
        assertEquals(1L, list.head());
        assertTrue(list instanceof MezzeLongCons);
    }

    @Test
    void testOfDoublesUsesSpecializedStorage() {
        MezzeList<Double> list = MezzeList.ofDoubles(1.5, 2.5, 3.5);
        assertEquals(3, list.size());
        assertEquals(1.5, list.head());
        assertTrue(list instanceof MezzeDoubleCons);
    }

    // ===== HEAD/TAIL =====

    @Test
    void testHead() {
        MezzeList<Integer> list = MezzeList.of(1, 2, 3);
        assertEquals(1, list.head());
    }

    @Test
    void testHeadOnNilThrows() {
        MezzeList<Object> list = MezzeList.empty();
        assertThrows(java.util.NoSuchElementException.class, list::head);
    }

    @Test
    void testTail() {
        MezzeList<Integer> list = MezzeList.of(1, 2, 3);
        MezzeList<Integer> tail = list.tail();
        assertEquals(2, tail.size());
        assertEquals(2, tail.head());
    }

    @Test
    void testTailOnNilThrows() {
        MezzeList<Object> list = MezzeList.empty();
        assertThrows(java.util.NoSuchElementException.class, list::tail);
    }

    // ===== PREPEND =====

    @Test
    void testPrependPrependsElement() {
        MezzeList<Integer> list = MezzeList.of(2, 3);
        MezzeList<Integer> result = list.prepend(1);

        assertEquals(3, result.size());
        assertEquals(1, result.head());
    }

    // ===== TRANSFORMS =====

    @Test
    void testMap() {
        MezzeList<Integer> list = MezzeList.of(1, 2, 3);
        MezzeList<Integer> result = list.map(x -> x * 2);

        assertEquals(3, result.size());
        assertEquals(2, result.head());
        assertEquals(4, result.tail().head());
        assertEquals(6, result.tail().tail().head());
    }

    @Test
    void testFilter() {
        MezzeList<Integer> list = MezzeList.of(1, 2, 3, 4, 5);
        MezzeList<Integer> result = list.filter(x -> x % 2 == 0);

        assertEquals(2, result.size());
        assertEquals(2, result.head());
        assertEquals(4, result.tail().head());
    }

    @Test
    void testFold() {
        MezzeList<Integer> list = MezzeList.of(1, 2, 3, 4);
        int sum = list.fold(0, Integer::sum);
        assertEquals(10, sum);
    }

    @Test
    void testFoldRight() {
        MezzeList<String> list = MezzeList.of("a", "b", "c");
        String result = list.foldRight("", (s, acc) -> s + acc);
        assertEquals("abc", result);
    }

    @Test
    void testReverse() {
        MezzeList<Integer> list = MezzeList.of(1, 2, 3);
        MezzeList<Integer> reversed = list.reverse();

        assertEquals(3, reversed.head());
        assertEquals(2, reversed.tail().head());
        assertEquals(1, reversed.tail().tail().head());
    }

    // ===== CONCAT =====

    @Test
    void testConcat() {
        MezzeList<Integer> list1 = MezzeList.of(1, 2);
        MezzeList<Integer> list2 = MezzeList.of(3, 4);
        MezzeList<Integer> result = list1.concat(list2);

        assertEquals(4, result.size());
        assertEquals(1, result.head());
    }

    @Test
    void testConcatWithNil() {
        MezzeList<Integer> list = MezzeList.of(1, 2, 3);
        MezzeList<Integer> nil = MezzeList.empty();

        // For immutable lists, concat with nil should return an equivalent list
        assertEquals(list, list.concat(nil));
        assertEquals(list, nil.concat(list));
    }

    // ===== TAKE/DROP =====

    @Test
    void testTake() {
        MezzeList<Integer> list = MezzeList.of(1, 2, 3, 4, 5);
        MezzeList<Integer> taken = list.take(3);

        assertEquals(3, taken.size());
        assertEquals(1, taken.head());
    }

    @Test
    void testTakeMoreThanSize() {
        MezzeList<Integer> list = MezzeList.of(1, 2, 3);
        MezzeList<Integer> taken = list.take(10);

        assertEquals(3, taken.size());
    }

    @Test
    void testDrop() {
        MezzeList<Integer> list = MezzeList.of(1, 2, 3, 4, 5);
        MezzeList<Integer> dropped = list.drop(2);

        assertEquals(3, dropped.size());
        assertEquals(3, dropped.head());
    }

    @Test
    void testDropMoreThanSize() {
        MezzeList<Integer> list = MezzeList.of(1, 2, 3);
        MezzeList<Integer> dropped = list.drop(10);

        assertTrue(dropped.isEmpty());
    }

    // ===== GET =====

    @Test
    void testGet() {
        MezzeList<Integer> list = MezzeList.of(1, 2, 3, 4, 5);

        assertEquals(1, list.get(0));
        assertEquals(3, list.get(2));
        assertEquals(5, list.get(4));
    }

    @Test
    void testGetOutOfBoundsThrows() {
        MezzeList<Integer> list = MezzeList.of(1, 2, 3);
        assertThrows(IndexOutOfBoundsException.class, () -> list.get(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> list.get(3));
    }

    // ===== NUMERIC (Long Specialized) =====

    @Test
    void testLongSum() {
        MezzeList<Long> list = MezzeList.ofLongs(1L, 2L, 3L, 4L, 5L);
        // MezzeLongCons has sum() method
        assertTrue(list instanceof MezzeLongCons);
        assertEquals(15L, ((MezzeLongCons) list).sum());
    }

    @Test
    void testLongProduct() {
        MezzeList<Long> list = MezzeList.ofLongs(1L, 2L, 3L, 4L);
        assertTrue(list instanceof MezzeLongCons);
        assertEquals(24L, ((MezzeLongCons) list).product());
    }

    @Test
    void testLongMin() {
        MezzeList<Long> list = MezzeList.ofLongs(5L, 2L, 8L, 1L);
        assertTrue(list instanceof MezzeLongCons);
        assertEquals(1L, ((MezzeLongCons) list).min());
    }

    @Test
    void testLongMax() {
        MezzeList<Long> list = MezzeList.ofLongs(5L, 2L, 8L, 1L);
        assertTrue(list instanceof MezzeLongCons);
        assertEquals(8L, ((MezzeLongCons) list).max());
    }

    // ===== NUMERIC (Double Specialized) =====

    @Test
    void testDoubleSum() {
        MezzeList<Double> list = MezzeList.ofDoubles(1.5, 2.5, 3.0);
        assertTrue(list instanceof MezzeDoubleCons);
        assertEquals(7.0, ((MezzeDoubleCons) list).sum(), 0.0001);
    }

    @Test
    void testDoubleProduct() {
        MezzeList<Double> list = MezzeList.ofDoubles(2.0, 3.0, 4.0);
        assertTrue(list instanceof MezzeDoubleCons);
        assertEquals(24.0, ((MezzeDoubleCons) list).product(), 0.0001);
    }

    // ===== ITERATION =====

    @Test
    void testIteration() {
        MezzeList<Integer> list = MezzeList.of(1, 2, 3);
        List<Integer> collected = new ArrayList<>();
        for (Integer i : list) {
            collected.add(i);
        }
        assertEquals(List.of(1, 2, 3), collected);
    }

    @Test
    void testToArray() {
        MezzeList<String> list = MezzeList.of("a", "b", "c");
        Object[] arr = list.toArray();
        assertArrayEquals(new Object[]{"a", "b", "c"}, arr);
    }

    // ===== EQUALITY =====

    @Test
    void testEquality() {
        MezzeList<Integer> list1 = MezzeList.of(1, 2, 3);
        MezzeList<Integer> list2 = MezzeList.of(1, 2, 3);
        MezzeList<Integer> list3 = MezzeList.of(1, 2, 4);

        assertEquals(list1, list2);
        assertNotEquals(list1, list3);
    }

    @Test
    void testHashCode() {
        MezzeList<Integer> list1 = MezzeList.of(1, 2, 3);
        MezzeList<Integer> list2 = MezzeList.of(1, 2, 3);

        assertEquals(list1.hashCode(), list2.hashCode());
    }

    @Test
    void testNilEquality() {
        MezzeList<Object> nil1 = MezzeList.empty();
        MezzeList<Object> nil2 = MezzeList.empty();

        assertSame(nil1, nil2);
        assertEquals(nil1, nil2);
    }

    // ===== EDGE CASES =====

    @Test
    void testEmptyListOperations() {
        MezzeList<Integer> nil = MezzeList.empty();

        assertTrue(nil.isEmpty());
        assertEquals(0, nil.size());
        assertSame(nil, nil.reverse());
        assertTrue(nil.take(10).isEmpty());
        assertTrue(nil.drop(10).isEmpty());
        assertTrue(nil.filter(x -> true).isEmpty());
    }

    @Test
    void testLargeList() {
        // Build a list of 1000 elements
        MezzeList<Long> list = MezzeList.empty();
        for (int i = 999; i >= 0; i--) {
            list = new MezzeLongCons((long) i, list);
        }

        assertEquals(1000, list.size());
        assertEquals(0L, list.head());
        assertEquals(999L, list.get(999));
    }

    // ===== ADDITIONAL COVERAGE =====

    @Test
    void testContains() {
        MezzeList<Integer> list = MezzeList.of(1, 2, 3, 4, 5);
        assertTrue(list.contains(3));
        assertFalse(list.contains(10));
    }

    @Test
    void testFlatMap() {
        MezzeList<Integer> list = MezzeList.of(1, 2, 3);
        MezzeList<Integer> result = list.flatMap(x -> MezzeList.of(x, x * 10));
        assertEquals(6, result.size());
    }

    @Test
    void testFindFirst() {
        MezzeList<Integer> list = MezzeList.of(1, 2, 3, 4, 5);
        assertEquals(Integer.valueOf(4), list.find(x -> x > 3));
        assertNull(list.find(x -> x > 10));
    }

    @Test
    void testForall() {
        MezzeList<Integer> list = MezzeList.of(2, 4, 6, 8);
        assertTrue(list.forall(x -> x % 2 == 0));
        assertFalse(list.forall(x -> x < 5));
    }

    @Test
    void testExists() {
        MezzeList<Integer> list = MezzeList.of(1, 2, 3, 4, 5);
        assertTrue(list.exists(x -> x == 3));
        assertFalse(list.exists(x -> x == 10));
    }

    @Test
    void testToString() {
        MezzeList<Integer> list = MezzeList.of(1, 2, 3);
        String str = list.toString();
        assertTrue(str.contains("1"));
        assertTrue(str.contains("2"));
        assertTrue(str.contains("3"));
    }

    @Test
    void testNilToString() {
        MezzeList<Object> nil = MezzeList.empty();
        assertNotNull(nil.toString());
    }

    @Test
    void testLongConsMapPreservesSpecialization() {
        MezzeList<Long> list = MezzeList.ofLongs(1L, 2L, 3L);
        MezzeList<Long> mapped = list.map(x -> x * 2);
        assertEquals(3, mapped.size());
        assertEquals(2L, mapped.head());
    }

    @Test
    void testDoubleConsMapPreservesSpecialization() {
        MezzeList<Double> list = MezzeList.ofDoubles(1.0, 2.0, 3.0);
        MezzeList<Double> mapped = list.map(x -> x * 2);
        assertEquals(3, mapped.size());
        assertEquals(2.0, mapped.head());
    }

    @Test
    void testLongConsFilter() {
        MezzeList<Long> list = MezzeList.ofLongs(1L, 2L, 3L, 4L, 5L);
        MezzeList<Long> filtered = list.filter(x -> x % 2 == 0);
        assertEquals(2, filtered.size());
    }

    @Test
    void testDoubleConsFilter() {
        MezzeList<Double> list = MezzeList.ofDoubles(1.0, 2.0, 3.0, 4.0, 5.0);
        MezzeList<Double> filtered = list.filter(x -> x > 2.5);
        assertEquals(3, filtered.size());
    }

    @Test
    void testTakeWhile() {
        MezzeList<Integer> list = MezzeList.of(1, 2, 3, 4, 5);
        MezzeList<Integer> result = list.takeWhile(x -> x < 4);
        assertEquals(3, result.size());
        assertEquals(1, result.head());
    }

    @Test
    void testDropWhile() {
        MezzeList<Integer> list = MezzeList.of(1, 2, 3, 4, 5);
        MezzeList<Integer> result = list.dropWhile(x -> x < 3);
        assertEquals(3, result.size());
        assertEquals(3, result.head());
    }

    @Test
    void testFilterNot() {
        MezzeList<Integer> list = MezzeList.of(1, 2, 3, 4, 5);
        MezzeList<Integer> result = list.filterNot(x -> x % 2 == 0);
        assertEquals(3, result.size());
        assertEquals(1, result.head());
    }

    @Test
    void testReduce() {
        MezzeList<Integer> list = MezzeList.of(1, 2, 3, 4);
        int sum = list.reduce(Integer::sum);
        assertEquals(10, sum);
    }

    @Test
    void testReduceOnEmptyThrows() {
        MezzeList<Integer> empty = MezzeList.empty();
        assertThrows(java.util.NoSuchElementException.class, () -> empty.reduce(Integer::sum));
    }

    @Test
    void testAppend() {
        MezzeList<Integer> list = MezzeList.of(1, 2, 3);
        MezzeList<Integer> result = list.append(4);
        assertEquals(4, result.size());
        assertEquals(4, result.get(3));
    }

    @Test
    void testNonEmpty() {
        MezzeList<Integer> list = MezzeList.of(1, 2, 3);
        assertTrue(list.nonEmpty());
        assertFalse(MezzeList.empty().nonEmpty());
    }

    @Test
    void testEqualsOtherType() {
        MezzeList<Integer> list = MezzeList.of(1, 2, 3);
        assertNotEquals(list, "not a list");
    }
}
