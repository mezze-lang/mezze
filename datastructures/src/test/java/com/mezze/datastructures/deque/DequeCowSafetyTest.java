package com.mezze.datastructures.deque;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verify Deque COW doesn't corrupt shared state when a deque is reused.
 * Before the ownership-token fix, the boolean 'owned' flag would let
 * multiple pushes from the same deque mutate the shared buffer.
 */
public class DequeCowSafetyTest {

    @Test
    void pushFrontFromSameDequeDoesNotCorrupt() {
        MezzeDeque<Integer> d = MezzeDeque.of(1, 2, 3);

        // Two independent pushes from the same deque
        MezzeDeque<Integer> d1 = d.pushFront(10);
        MezzeDeque<Integer> d2 = d.pushFront(20);

        // d1 should have 10 at front, d2 should have 20 at front
        assertEquals(10, d1.peekFront());
        assertEquals(20, d2.peekFront());

        // Original d should be unchanged
        assertEquals(1, d.peekFront());
        assertEquals(3, d.size());

        // Both derived deques should have size 4
        assertEquals(4, d1.size());
        assertEquals(4, d2.size());

        // d1 and d2 should not interfere with each other
        assertNotEquals(d1.peekFront(), d2.peekFront());
    }

    @Test
    void pushBackFromSameDequeDoesNotCorrupt() {
        MezzeDeque<Integer> d = MezzeDeque.of(1, 2, 3);

        MezzeDeque<Integer> d1 = d.pushBack(10);
        MezzeDeque<Integer> d2 = d.pushBack(20);

        assertEquals(10, d1.peekBack());
        assertEquals(20, d2.peekBack());
        assertEquals(3, d.peekBack());
    }

    @Test
    void readThenPushDoesNotCorrupt() {
        MezzeDeque<Integer> d = MezzeDeque.of(1, 2, 3);

        // Read first — should revoke ownership
        assertEquals(1, d.peekFront());
        assertEquals(3, d.peekBack());
        assertEquals(2, d.get(1));

        // Push after read should clone, not mutate
        MezzeDeque<Integer> d1 = d.pushBack(4);
        assertEquals(3, d.size());
        assertEquals(4, d1.size());
        assertEquals(3, d.peekBack());
        assertEquals(4, d1.peekBack());
    }

    @Test
    void popThenPushDoesNotCorrupt() {
        MezzeDeque<Integer> d = MezzeDeque.of(1, 2, 3, 4, 5);

        // Pop revokes ownership
        var popped = d.popFront();
        MezzeDeque<Integer> rest = popped.rest();

        // Push on original after pop
        MezzeDeque<Integer> pushed = d.pushBack(99);

        // rest should still have [2, 3, 4, 5]
        assertEquals(2, rest.peekFront());
        assertEquals(5, rest.peekBack());
        assertEquals(4, rest.size());

        // pushed should have [1, 2, 3, 4, 5, 99]
        assertEquals(1, pushed.peekFront());
        assertEquals(99, pushed.peekBack());
        assertEquals(6, pushed.size());
    }

    @Test
    void chainedPushesWorkCorrectly() {
        // This is the pattern COW optimizes — each intermediate is discarded
        MezzeDeque<Integer> d = MezzeDeque.<Integer>empty()
            .pushBack(1).pushBack(2).pushBack(3).pushBack(4).pushBack(5);

        assertEquals(5, d.size());
        assertEquals(1, d.peekFront());
        assertEquals(5, d.peekBack());
        for (int i = 0; i < 5; i++) {
            assertEquals(i + 1, d.get(i));
        }
    }
}
