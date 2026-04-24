package com.mezze.datastructures.list;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verify that List operations don't stack overflow on large lists.
 * Before the iterative fix, these would fail with StackOverflowError.
 */
public class ListStackSafetyTest {

    private static final int LARGE = 100_000;

    private MezzeList<Long> buildLargeList() {
        MezzeList<Long> list = MezzeList.empty();
        for (int i = LARGE - 1; i >= 0; i--) {
            list = MezzeList.cons((long) i, list);
        }
        return list;
    }

    @Test
    void mapOnLargeList() {
        MezzeList<Long> list = buildLargeList();
        MezzeList<Long> mapped = list.map(x -> x * 2);
        assertEquals(LARGE, mapped.size());
        assertEquals(0L, mapped.head());
        assertEquals((long)(LARGE - 1) * 2, mapped.last());
    }

    @Test
    void filterOnLargeList() {
        MezzeList<Long> list = buildLargeList();
        MezzeList<Long> filtered = list.filter(x -> x % 2 == 0);
        assertEquals(LARGE / 2, filtered.size());
        assertEquals(0L, filtered.head());
    }

    @Test
    void concatLargeLists() {
        MezzeList<Long> list = buildLargeList();
        MezzeList<Long> other = MezzeList.of(999L);
        MezzeList<Long> result = list.concat(other);
        assertEquals(LARGE + 1, result.size());
        assertEquals(999L, result.last());
    }

    @Test
    void foldRightOnLargeList() {
        MezzeList<Long> list = buildLargeList();
        long sum = list.foldRight(0L, (elem, acc) -> elem + acc);
        assertEquals((long)(LARGE - 1) * LARGE / 2, sum);
    }

    @Test
    void takeOnLargeList() {
        MezzeList<Long> list = buildLargeList();
        MezzeList<Long> taken = list.take(50_000);
        assertEquals(50_000, taken.size());
        assertEquals(0L, taken.head());
        assertEquals(49_999L, taken.last());
    }

    @Test
    void takeWhileOnLargeList() {
        MezzeList<Long> list = buildLargeList();
        MezzeList<Long> taken = list.takeWhile(x -> x < 50_000);
        assertEquals(50_000, taken.size());
    }

    @Test
    void initOnLargeList() {
        MezzeList<Long> list = buildLargeList();
        MezzeList<Long> inited = list.init();
        assertEquals(LARGE - 1, inited.size());
        assertEquals(0L, inited.head());
    }

    @Test
    void reverseOnLargeList() {
        MezzeList<Long> list = buildLargeList();
        MezzeList<Long> reversed = list.reverse();
        assertEquals(LARGE, reversed.size());
        assertEquals((long)(LARGE - 1), reversed.head());
        assertEquals(0L, reversed.last());
    }

    @Test
    void flatMapOnLargeList() {
        // Use a smaller size for flatMap since it creates many intermediate lists
        int size = 10_000;
        MezzeList<Long> list = MezzeList.empty();
        for (int i = size - 1; i >= 0; i--) {
            list = MezzeList.cons((long) i, list);
        }
        MezzeList<Long> result = list.flatMap(x -> MezzeList.of(x, x * 10));
        assertEquals(size * 2, result.size());
        assertEquals(0L, result.head());
    }
}
