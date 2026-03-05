package com.mezze.datastructures.nodes;

import com.mezze.datastructures.list.*;
import com.mezze.datastructures.map.*;
import com.mezze.datastructures.seq.*;
import com.mezze.datastructures.set.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Truffle DSL specialization nodes.
 * Verifies that type-specific fast paths work correctly.
 */
public class SpecializationNodesTest {

    // ==================== Seq Get Nodes ====================

    @Test
    void testSeqGetNode() {
        LongSeqStorage storage = new LongSeqStorage(new long[]{10, 20, 30, 40, 50});
        SeqGetNode node = SeqGetNode.create();

        assertEquals(10L, node.execute(storage, 0));
        assertEquals(30L, node.execute(storage, 2));
        assertEquals(50L, node.execute(storage, 4));
    }

    @Test
    void testSeqGetDoubleNode() {
        DoubleSeqStorage storage = new DoubleSeqStorage(new double[]{1.1, 2.2, 3.3});
        SeqGetDoubleNode node = SeqGetDoubleNode.create();

        assertEquals(1.1, node.execute(storage, 0), 1e-10);
        assertEquals(2.2, node.execute(storage, 1), 1e-10);
        assertEquals(3.3, node.execute(storage, 2), 1e-10);
    }

    // ==================== Seq Sum Nodes ====================

    @Test
    void testSeqSumNodeSmall() {
        LongSeqStorage storage = new LongSeqStorage(new long[]{1, 2, 3, 4, 5});
        SeqSumNode node = SeqSumNode.create();

        assertEquals(15L, node.execute(storage));
    }

    @Test
    void testSeqSumNodeLarge() {
        long[] data = new long[100];
        long expected = 0;
        for (int i = 0; i < 100; i++) {
            data[i] = i + 1;
            expected += data[i];
        }
        LongSeqStorage storage = new LongSeqStorage(data);
        SeqSumNode node = SeqSumNode.create();

        assertEquals(expected, node.execute(storage));
    }

    @Test
    void testSeqSumDoubleNodeSmall() {
        DoubleSeqStorage storage = new DoubleSeqStorage(new double[]{1.0, 2.0, 3.0});
        SeqSumDoubleNode node = SeqSumDoubleNode.create();

        assertEquals(6.0, node.execute(storage), 1e-10);
    }

    @Test
    void testSeqSumDoubleNodeLarge() {
        double[] data = new double[100];
        double expected = 0;
        for (int i = 0; i < 100; i++) {
            data[i] = i + 0.5;
            expected += data[i];
        }
        DoubleSeqStorage storage = new DoubleSeqStorage(data);
        SeqSumDoubleNode node = SeqSumDoubleNode.create();

        assertEquals(expected, node.execute(storage), 1e-6);
    }

    // ==================== Seq IndexOf Nodes ====================

    @Test
    void testSeqIndexOfNodeSmall() {
        LongSeqStorage storage = new LongSeqStorage(new long[]{10, 20, 30, 40, 50});
        SeqIndexOfNode node = SeqIndexOfNode.create();

        assertEquals(0, node.execute(storage, 10));
        assertEquals(2, node.execute(storage, 30));
        assertEquals(4, node.execute(storage, 50));
        assertEquals(-1, node.execute(storage, 99));
    }

    @Test
    void testSeqIndexOfNodeLarge() {
        long[] data = new long[100];
        for (int i = 0; i < 100; i++) {
            data[i] = i * 2;
        }
        LongSeqStorage storage = new LongSeqStorage(data);
        SeqIndexOfNode node = SeqIndexOfNode.create();

        assertEquals(50, node.execute(storage, 100));
        assertEquals(-1, node.execute(storage, 101));
    }

    @Test
    void testSeqIndexOfDoubleNode() {
        DoubleSeqStorage storage = new DoubleSeqStorage(new double[]{1.1, 2.2, 3.3, 4.4, 5.5});
        SeqIndexOfDoubleNode node = SeqIndexOfDoubleNode.create();

        assertEquals(0, node.execute(storage, 1.1));
        assertEquals(2, node.execute(storage, 3.3));
        assertEquals(-1, node.execute(storage, 9.9));
    }

    // ==================== Seq Update Nodes ====================

    @Test
    void testSeqUpdateNode() {
        LongSeqStorage storage = new LongSeqStorage(new long[]{1, 2, 3});
        SeqUpdateNode node = SeqUpdateNode.create();

        LongSeqStorage updated = node.execute(storage, 1, 99L);
        assertEquals(99L, updated.getLong(1));
        assertEquals(1L, updated.getLong(0));
        assertEquals(3L, updated.getLong(2));
    }

    @Test
    void testSeqUpdateDoubleNode() {
        DoubleSeqStorage storage = new DoubleSeqStorage(new double[]{1.0, 2.0, 3.0});
        SeqUpdateDoubleNode node = SeqUpdateDoubleNode.create();

        DoubleSeqStorage updated = node.execute(storage, 1, 99.9);
        assertEquals(99.9, updated.getDouble(1), 1e-10);
    }

    // ==================== Seq Append Nodes ====================

    @Test
    void testSeqAppendLongNode() {
        LongSeqStorage storage = new LongSeqStorage(new long[]{1, 2, 3});
        SeqAppendLongNode node = SeqAppendLongNode.create();

        LongSeqStorage appended = node.execute(storage, 4L);
        assertEquals(4, appended.size());
        assertEquals(4L, appended.getLong(3));
    }

    @Test
    void testSeqAppendDoubleNode() {
        DoubleSeqStorage storage = new DoubleSeqStorage(new double[]{1.0, 2.0});
        SeqAppendDoubleNode node = SeqAppendDoubleNode.create();

        DoubleSeqStorage appended = node.execute(storage, 3.0);
        assertEquals(3, appended.size());
        assertEquals(3.0, appended.getDouble(2), 1e-10);
    }

    // ==================== List Head Nodes ====================

    @Test
    void testListHeadNode() {
        MezzeLongCons list = new MezzeLongCons(42, MezzeNil.INSTANCE);
        ListHeadNode node = ListHeadNode.create();

        assertEquals(42L, node.execute(list));
    }

    @Test
    void testListHeadDoubleNode() {
        MezzeDoubleCons list = new MezzeDoubleCons(3.14, MezzeNil.INSTANCE);
        ListHeadDoubleNode node = ListHeadDoubleNode.create();

        assertEquals(3.14, node.execute(list), 1e-10);
    }

    // ==================== List Cons Nodes ====================

    @Test
    void testListConsNode() {
        MezzeLongCons tail = new MezzeLongCons(2, MezzeNil.INSTANCE);
        ListConsNode node = ListConsNode.create();

        MezzeLongCons result = node.execute(1L, tail);
        assertEquals(1L, result.headLong());
        assertEquals(2L, ((MezzeLongCons) result.tail()).headLong());
    }

    @Test
    void testListConsDoubleNode() {
        MezzeDoubleCons tail = new MezzeDoubleCons(2.0, MezzeNil.INSTANCE);
        ListConsDoubleNode node = ListConsDoubleNode.create();

        MezzeDoubleCons result = node.execute(1.0, tail);
        assertEquals(1.0, result.headDouble(), 1e-10);
        assertEquals(2.0, ((MezzeDoubleCons) result.tail()).headDouble(), 1e-10);
    }

    // ==================== Map Get Node ====================

    @Test
    void testMapGetNode() {
        LongKeyMapStorage storage = new LongKeyMapStorage(
                new long[]{1L, 2L, 3L},
                new Object[]{"one", "two", "three"});
        MapGetNode node = MapGetNode.create();

        assertEquals("one", node.execute(storage, 1L));
        assertEquals("two", node.execute(storage, 2L));
        assertNull(node.execute(storage, 99L));
    }

    // ==================== Set Contains Node ====================

    @Test
    void testSetContainsNode() {
        LongSetStorage storage = new LongSetStorage(new long[]{10L, 20L, 30L});
        SetContainsNode node = SetContainsNode.create();

        assertTrue(node.execute(storage, 10L));
        assertTrue(node.execute(storage, 20L));
        assertFalse(node.execute(storage, 99L));
    }

    // ==================== Uncached Versions ====================

    @Test
    void testUncachedVersions() {
        // Verify uncached versions work for slow path
        LongSeqStorage storage = new LongSeqStorage(new long[]{1, 2, 3});

        assertEquals(1L, SeqGetNode.getUncached().execute(storage, 0));
        assertEquals(6L, SeqSumNode.getUncached().execute(storage));
        assertEquals(1, SeqIndexOfNode.getUncached().execute(storage, 2));
    }
}
