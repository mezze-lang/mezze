package com.mezze.interpreter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Deque (double-ended queue) data structure.
 * Uses V2 unboxed values directly.
 */
class DequeIntegrationTest {
    // ========================================
    // Construction Tests
    // ========================================

    @Test
    void emptyDequeIsEmpty() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque::{empty, is_empty}
            is_empty { d = empty {} }
            """);
        assertEquals(true, result);
    }

    @Test
    void singletonDequeHasLength1() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            deque::length { d = deque::singleton { x = 42 } }
            """);
        assertEquals(1L, result);
    }

    @Test
    void fromListCreatesDequeWithAllElements() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            use std::collections::list
            let xs = list::cons { head = 1, tail = list::cons { head = 2, tail = list::cons { head = 3, tail = list::empty {} } } }
            deque::length { d = deque::from_list { xs = xs } }
            """);
        assertEquals(3L, result);
    }

    // ========================================
    // Push Operations Tests
    // ========================================

    @Test
    void pushFrontAddsElementToFront() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::empty {}
            let d2 = deque::push_front { d = d, x = 1 }
            let d3 = deque::push_front { d = d2, x = 2 }
            match deque::peek_front { d = d3 } is
                Some { val } -> val
                None -> 0
            """);
        assertEquals(2L, result);
    }

    @Test
    void pushBackAddsElementToBack() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::empty {}
            let d2 = deque::push_back { d = d, x = 1 }
            let d3 = deque::push_back { d = d2, x = 2 }
            match deque::peek_back { d = d3 } is
                Some { val } -> val
                None -> 0
            """);
        assertEquals(2L, result);
    }

    @Test
    void pushOperationsMaintainOrder() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::empty {}
            let d2 = deque::push_back { d = d, x = 1 }
            let d3 = deque::push_back { d = d2, x = 2 }
            let d4 = deque::push_back { d = d3, x = 3 }
            deque::length { d = d4 }
            """);
        assertEquals(3L, result);
    }

    // ========================================
    // Pop Operations Tests
    // ========================================

    @Test
    void popFrontReturnsFrontElementAndRemainingDeque() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::push_back { d = deque::push_back { d = deque::empty {}, x = 1 }, x = 2 }
            match deque::pop_front { d = d } is
                Some { val } -> val.head
                None -> 0
            """);
        assertEquals(1L, result);
    }

    @Test
    void popBackReturnsBackElementAndRemainingDeque() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::push_back { d = deque::push_back { d = deque::empty {}, x = 1 }, x = 2 }
            match deque::pop_back { d = d } is
                Some { val } -> val.last
                None -> 0
            """);
        assertEquals(2L, result);
    }

    @Test
    void popFrontOnEmptyReturnsNone() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            match deque::pop_front { d = deque::empty {} } is
                Some { val } -> 1
                None -> 0
            """);
        assertEquals(0L, result);
    }

    @Test
    void popBackOnEmptyReturnsNone() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            match deque::pop_back { d = deque::empty {} } is
                Some { val } -> 1
                None -> 0
            """);
        assertEquals(0L, result);
    }

    // ========================================
    // Peek Operations Tests
    // ========================================

    @Test
    void peekFrontReturnsFrontWithoutRemoving() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::push_back { d = deque::push_back { d = deque::empty {}, x = 1 }, x = 2 }
            let front = match deque::peek_front { d = d } is
                Some { val } -> val
                None -> 0
            let stillSameLength = deque::length { d = d }
            front + stillSameLength
            """);
        assertEquals(3L, result); // 1 (front) + 2 (length)
    }

    @Test
    void peekBackReturnsBackWithoutRemoving() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::push_back { d = deque::push_back { d = deque::empty {}, x = 1 }, x = 2 }
            match deque::peek_back { d = d } is
                Some { val } -> val
                None -> 0
            """);
        assertEquals(2L, result);
    }

    // ========================================
    // Queue Operations (FIFO) Tests
    // ========================================

    @Test
    void enqueueAndDequeueWorkAsFIFO() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::enqueue { d = deque::empty {}, x = 1 }
            let d2 = deque::enqueue { d = d, x = 2 }
            let d3 = deque::enqueue { d = d2, x = 3 }
            match deque::dequeue { d = d3 } is
                Some { val } -> val.head
                None -> 0
            """);
        assertEquals(1L, result); // FIFO: first in, first out
    }

    // ========================================
    // Stack Operations (LIFO) Tests
    // ========================================

    @Test
    void pushAndPopWorkAsLIFO() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::push { d = deque::empty {}, x = 1 }
            let d2 = deque::push { d = d, x = 2 }
            let d3 = deque::push { d = d2, x = 3 }
            match deque::pop { d = d3 } is
                Some { val } -> val.head
                None -> 0
            """);
        assertEquals(3L, result); // LIFO: last in, first out
    }

    // ========================================
    // Conversion Tests
    // ========================================

    @Test
    void toListConvertsDequeToList() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            use std::collections::list
            let d = deque::push_back { d = deque::push_back { d = deque::empty {}, x = 1 }, x = 2 }
            let xs = deque::to_list { d = d }
            if list::is_empty { xs = xs } then 0 else 1
            """);
        assertEquals(1L, result); // Not empty
    }

    @Test
    void toSeqConvertsDequeToSeq() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            use std::collections::seq
            let d = deque::push_back { d = deque::push_back { d = deque::empty {}, x = 1 }, x = 2 }
            let arr = deque::to_seq { d = d }
            seq::length { xs = arr }
            """);
        assertEquals(2L, result);
    }

    // ========================================
    // Higher-Order Function Tests
    // ========================================

    @Test
    void mapTransformsAllElements() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::push_back { d = deque::push_back { d = deque::empty {}, x = 1 }, x = 2 }
            let doubled = deque::map { d = d, f = { x } -> x * 2 }
            match deque::peek_front { d = doubled } is
                Some { val } -> val
                None -> 0
            """);
        assertEquals(2L, result); // 1 * 2 = 2
    }

    @Test
    void filterKeepsMatchingElements() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::push_back { d = deque::push_back { d = deque::push_back { d = deque::empty {}, x = 1 }, x = 2 }, x = 3 }
            let evens = deque::filter { d = d, p = { x } -> x % 2 == 0 }
            deque::length { d = evens }
            """);
        assertEquals(1L, result); // Only 2 is even
    }

    @Test
    void foldAccumulatesValues() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::push_back { d = deque::push_back { d = deque::push_back { d = deque::empty {}, x = 1 }, x = 2 }, x = 3 }
            deque::fold { d = d, init = 0, f = { acc, x } -> acc + x }
            """);
        assertEquals(6L, result); // 1 + 2 + 3 = 6
    }

    // ========================================
    // Transformation Tests
    // ========================================

    @Test
    void reverseReversesTheDeque() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::push_back { d = deque::push_back { d = deque::push_back { d = deque::empty {}, x = 1 }, x = 2 }, x = 3 }
            let reversed = deque::reverse { d = d }
            match deque::peek_front { d = reversed } is
                Some { val } -> val
                None -> 0
            """);
        assertEquals(3L, result); // 3 is now at front
    }

    @Test
    void concatJoinsTwoDeques() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d1 = deque::push_back { d = deque::empty {}, x = 1 }
            let d2 = deque::push_back { d = deque::empty {}, x = 2 }
            let combined = deque::concat { d1 = d1, d2 = d2 }
            deque::length { d = combined }
            """);
        assertEquals(2L, result);
    }
}
