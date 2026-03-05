package com.mezze.datastructures.deque;

import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for MezzeDeque.
 * Tests ring buffer operations and transforms.
 */
public class MezzeDequeTest {

    // ===== CONSTRUCTION =====

    @Test
    void testEmpty() {
        MezzeDeque<String> deque = MezzeDeque.empty();
        assertEquals(0, deque.size());
        assertTrue(deque.isEmpty());
    }

    @Test
    void testOf() {
        MezzeDeque<Integer> deque = MezzeDeque.of(1, 2, 3);
        assertEquals(3, deque.size());
        assertEquals(Integer.valueOf(1), deque.peekFront());
        assertEquals(Integer.valueOf(3), deque.peekBack());
    }

    @Test
    void testOfLongs() {
        MezzeDeque<Long> deque = MezzeDeque.ofLongs(1L, 2L, 3L);
        assertEquals(3, deque.size());
        assertEquals(Long.valueOf(1L), deque.peekFront());
    }

    // ===== ACCESS =====

    @Test
    void testPeekFront() {
        MezzeDeque<String> deque = MezzeDeque.of("a", "b", "c");
        assertEquals("a", deque.peekFront());
    }

    @Test
    void testPeekBack() {
        MezzeDeque<String> deque = MezzeDeque.of("a", "b", "c");
        assertEquals("c", deque.peekBack());
    }

    @Test
    void testPeekOnEmptyThrows() {
        MezzeDeque<String> deque = MezzeDeque.empty();
        assertThrows(NoSuchElementException.class, deque::peekFront);
        assertThrows(NoSuchElementException.class, deque::peekBack);
    }

    @Test
    void testGet() {
        MezzeDeque<String> deque = MezzeDeque.of("a", "b", "c", "d");
        assertEquals("a", deque.get(0));
        assertEquals("b", deque.get(1));
        assertEquals("d", deque.get(3));
    }

    @Test
    void testGetOutOfBoundsThrows() {
        MezzeDeque<String> deque = MezzeDeque.of("a", "b");
        assertThrows(IndexOutOfBoundsException.class, () -> deque.get(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> deque.get(2));
    }

    // ===== PUSH =====

    @Test
    void testPushFront() {
        MezzeDeque<Integer> deque = MezzeDeque.of(2, 3);
        MezzeDeque<Integer> result = deque.pushFront(1);

        assertEquals(2, deque.size()); // Original unchanged
        assertEquals(3, result.size());
        assertEquals(Integer.valueOf(1), result.peekFront());
    }

    @Test
    void testPushBack() {
        MezzeDeque<Integer> deque = MezzeDeque.of(1, 2);
        MezzeDeque<Integer> result = deque.pushBack(3);

        assertEquals(2, deque.size());
        assertEquals(3, result.size());
        assertEquals(Integer.valueOf(3), result.peekBack());
    }

    @Test
    void testPushFrontOnEmpty() {
        MezzeDeque<String> deque = MezzeDeque.<String>empty().pushFront("a");
        assertEquals(1, deque.size());
        assertEquals("a", deque.peekFront());
        assertEquals("a", deque.peekBack());
    }

    @Test
    void testPushBackOnEmpty() {
        MezzeDeque<String> deque = MezzeDeque.<String>empty().pushBack("a");
        assertEquals(1, deque.size());
        assertEquals("a", deque.peekFront());
        assertEquals("a", deque.peekBack());
    }

    // ===== POP =====

    @Test
    void testPopFront() {
        MezzeDeque<Integer> deque = MezzeDeque.of(1, 2, 3);
        var result = deque.popFront();

        assertEquals(Integer.valueOf(1), result.element());
        assertEquals(2, result.rest().size());
        assertEquals(Integer.valueOf(2), result.rest().peekFront());
    }

    @Test
    void testPopBack() {
        MezzeDeque<Integer> deque = MezzeDeque.of(1, 2, 3);
        var result = deque.popBack();

        assertEquals(Integer.valueOf(3), result.element());
        assertEquals(2, result.rest().size());
        assertEquals(Integer.valueOf(2), result.rest().peekBack());
    }

    @Test
    void testPopOnSingleElement() {
        MezzeDeque<String> deque = MezzeDeque.of("a");

        var front = deque.popFront();
        assertEquals("a", front.element());
        assertTrue(front.rest().isEmpty());

        var back = deque.popBack();
        assertEquals("a", back.element());
        assertTrue(back.rest().isEmpty());
    }

    @Test
    void testPopOnEmptyThrows() {
        MezzeDeque<String> deque = MezzeDeque.empty();
        assertThrows(NoSuchElementException.class, deque::popFront);
        assertThrows(NoSuchElementException.class, deque::popBack);
    }

    // ===== RING BUFFER WRAPAROUND =====

    @Test
    void testRingBufferWraparound() {
        // Build up and pop to force wraparound
        MezzeDeque<Integer> deque = MezzeDeque.empty();

        // Push front several times
        for (int i = 0; i < 10; i++) {
            deque = deque.pushFront(i);
        }

        // Pop front several times
        for (int i = 0; i < 5; i++) {
            deque = deque.popFront().rest();
        }

        // Push back several times
        for (int i = 100; i < 110; i++) {
            deque = deque.pushBack(i);
        }

        // Verify elements
        assertEquals(15, deque.size());
        assertEquals(Integer.valueOf(4), deque.peekFront());
        assertEquals(Integer.valueOf(109), deque.peekBack());
    }

    // ===== TRANSFORMS =====

    @Test
    void testMap() {
        MezzeDeque<Integer> deque = MezzeDeque.of(1, 2, 3);
        MezzeDeque<Integer> result = deque.map(x -> x * 10);

        assertEquals(3, result.size());
        assertEquals(Integer.valueOf(10), result.get(0));
        assertEquals(Integer.valueOf(20), result.get(1));
        assertEquals(Integer.valueOf(30), result.get(2));
    }

    @Test
    void testFilter() {
        MezzeDeque<Integer> deque = MezzeDeque.of(1, 2, 3, 4, 5);
        MezzeDeque<Integer> result = deque.filter(x -> x % 2 == 0);

        assertEquals(2, result.size());
        assertEquals(Integer.valueOf(2), result.get(0));
        assertEquals(Integer.valueOf(4), result.get(1));
    }

    @Test
    void testFold() {
        MezzeDeque<Integer> deque = MezzeDeque.of(1, 2, 3, 4);
        int sum = deque.fold(0, Integer::sum);
        assertEquals(10, sum);
    }

    @Test
    void testFoldRight() {
        MezzeDeque<String> deque = MezzeDeque.of("a", "b", "c");
        String result = deque.foldRight("", (s, acc) -> s + acc);
        assertEquals("abc", result);
    }

    @Test
    void testReverse() {
        MezzeDeque<Integer> deque = MezzeDeque.of(1, 2, 3, 4);
        MezzeDeque<Integer> reversed = deque.reverse();

        assertEquals(4, reversed.size());
        assertEquals(Integer.valueOf(4), reversed.get(0));
        assertEquals(Integer.valueOf(1), reversed.get(3));
    }

    // ===== CONCAT =====

    @Test
    void testConcat() {
        MezzeDeque<Integer> deque1 = MezzeDeque.of(1, 2);
        MezzeDeque<Integer> deque2 = MezzeDeque.of(3, 4);
        MezzeDeque<Integer> result = deque1.concat(deque2);

        assertEquals(4, result.size());
        assertEquals(Integer.valueOf(1), result.get(0));
        assertEquals(Integer.valueOf(4), result.get(3));
    }

    @Test
    void testConcatWithEmpty() {
        MezzeDeque<Integer> deque = MezzeDeque.of(1, 2, 3);
        MezzeDeque<Integer> empty = MezzeDeque.empty();

        assertSame(deque, deque.concat(empty));
        assertSame(deque, empty.concat(deque));
    }

    // ===== ITERATION =====

    @Test
    void testIteration() {
        MezzeDeque<Integer> deque = MezzeDeque.of(1, 2, 3);
        List<Integer> collected = new ArrayList<>();
        for (Integer i : deque) {
            collected.add(i);
        }
        assertEquals(List.of(1, 2, 3), collected);
    }

    @Test
    void testToArray() {
        MezzeDeque<String> deque = MezzeDeque.of("a", "b", "c");
        Object[] arr = deque.toArray();
        assertArrayEquals(new Object[]{"a", "b", "c"}, arr);
    }

    // ===== EQUALITY =====

    @Test
    void testEquality() {
        MezzeDeque<Integer> deque1 = MezzeDeque.of(1, 2, 3);
        MezzeDeque<Integer> deque2 = MezzeDeque.of(1, 2, 3);
        MezzeDeque<Integer> deque3 = MezzeDeque.of(1, 2, 4);

        assertEquals(deque1, deque2);
        assertNotEquals(deque1, deque3);
    }

    @Test
    void testHashCode() {
        MezzeDeque<Integer> deque1 = MezzeDeque.of(1, 2, 3);
        MezzeDeque<Integer> deque2 = MezzeDeque.of(1, 2, 3);

        assertEquals(deque1.hashCode(), deque2.hashCode());
    }

    // ===== EDGE CASES =====

    @Test
    void testEmptyDequeOperations() {
        MezzeDeque<Integer> empty = MezzeDeque.empty();

        assertTrue(empty.isEmpty());
        assertFalse(empty.nonEmpty());
        assertSame(empty, empty.reverse());
        assertSame(empty, empty.filter(x -> true));
    }

    @Test
    void testLargeDeque() {
        MezzeDeque<Integer> deque = MezzeDeque.empty();

        // Build large deque
        for (int i = 0; i < 1000; i++) {
            if (i % 2 == 0) {
                deque = deque.pushBack(i);
            } else {
                deque = deque.pushFront(-i);
            }
        }

        assertEquals(1000, deque.size());
    }

    @Test
    void testAlternatingPushPop() {
        // Stress test for ring buffer
        MezzeDeque<Integer> deque = MezzeDeque.empty();

        for (int i = 0; i < 100; i++) {
            deque = deque.pushBack(i);
            deque = deque.pushFront(i * 10);
        }

        for (int i = 0; i < 50; i++) {
            deque = deque.popFront().rest();
            deque = deque.popBack().rest();
        }

        assertEquals(100, deque.size());
    }

    // ===== STRUCTURAL SHARING =====

    @Test
    void testStructuralSharingOnPop() {
        // After pop, the new deque should share structure with the old one
        MezzeDeque<Integer> original = MezzeDeque.of(1, 2, 3, 4, 5);
        var result = original.popFront();

        // Both should have consistent data
        assertEquals(5, original.size());
        assertEquals(4, result.rest().size());
        assertEquals(Integer.valueOf(1), original.peekFront());
        assertEquals(Integer.valueOf(2), result.rest().peekFront());
    }

    @Test
    void testStructuralSharingChain() {
        // Chain of pops should work correctly with structural sharing
        MezzeDeque<Integer> deque = MezzeDeque.of(1, 2, 3, 4, 5);

        MezzeDeque<Integer> d1 = deque.popFront().rest();
        MezzeDeque<Integer> d2 = d1.popFront().rest();
        MezzeDeque<Integer> d3 = d2.popFront().rest();

        // All intermediate deques should be independent
        assertEquals(4, d1.size());
        assertEquals(3, d2.size());
        assertEquals(2, d3.size());

        assertEquals(Integer.valueOf(2), d1.peekFront());
        assertEquals(Integer.valueOf(3), d2.peekFront());
        assertEquals(Integer.valueOf(4), d3.peekFront());
    }

    @Test
    void testPushAfterPopSharesCorrectly() {
        // Push after pop should work correctly
        MezzeDeque<Integer> original = MezzeDeque.of(1, 2, 3);
        MezzeDeque<Integer> afterPop = original.popFront().rest();
        MezzeDeque<Integer> afterPush = afterPop.pushBack(99);

        assertEquals(2, afterPop.size());
        assertEquals(3, afterPush.size());
        assertEquals(Integer.valueOf(99), afterPush.peekBack());

        // Original should be unchanged
        assertEquals(3, original.size());
        assertEquals(Integer.valueOf(3), original.peekBack());
    }

    // ===== ADDITIONAL COVERAGE =====

    @Test
    void testToString() {
        MezzeDeque<Integer> deque = MezzeDeque.of(1, 2, 3);
        String str = deque.toString();
        assertTrue(str.contains("1"));
        assertTrue(str.contains("2"));
        assertTrue(str.contains("3"));
    }

    @Test
    void testEqualsNull() {
        MezzeDeque<Integer> deque = MezzeDeque.of(1, 2, 3);
        assertNotEquals(deque, null);
    }

    @Test
    void testEqualsOtherType() {
        MezzeDeque<Integer> deque = MezzeDeque.of(1, 2, 3);
        assertNotEquals(deque, "not a deque");
    }

    @Test
    void testEqualsDifferentSizes() {
        MezzeDeque<Integer> deque1 = MezzeDeque.of(1, 2, 3);
        MezzeDeque<Integer> deque2 = MezzeDeque.of(1, 2);
        assertNotEquals(deque1, deque2);
    }
}
