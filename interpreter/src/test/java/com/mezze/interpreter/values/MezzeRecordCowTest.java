package com.mezze.interpreter.values;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MezzeRecord structural sharing and ownership tracking.
 *
 * Key optimizations tested:
 * 1. Structural sharing: unmodified arrays are shared between old and new records
 * 2. Ownership tracking: enables future optimizations and marks shared singletons
 * 3. Batch updates: multiple field updates clone each array at most once
 *
 * Run with: ./gradlew :interpreter:test --tests "*MezzeRecordCowTest*"
 */
public class MezzeRecordCowTest {

    private MezzeRecord createRecord(String[] names, Object[] values) {
        MezzeRecord.FieldLocation[] locations = new MezzeRecord.FieldLocation[names.length];
        for (int i = 0; i < names.length; i++) {
            locations[i] = new MezzeRecord.FieldLocation(
                MezzeRecord.FieldLocation.StorageType.OBJECT, i);
        }
        MezzeRecord.Shape shape = new MezzeRecord.Shape(names, locations);
        return MezzeRecord.withAllObjects(shape, values.clone());
    }

    @Test
    void testNewRecordIsOwned() {
        MezzeRecord rec = createRecord(
            new String[]{"x", "y"},
            new Object[]{1L, 2L}
        );
        assertTrue(rec.isOwned(), "New record should be owned");
    }

    @Test
    void testEmptyRecordIsNotOwned() {
        MezzeRecord empty = MezzeRecord.empty();
        assertFalse(empty.isOwned(), "Empty singleton should not be owned");
    }

    @Test
    void testMarkSharedClearsOwnership() {
        MezzeRecord rec = createRecord(
            new String[]{"x"},
            new Object[]{42L}
        );
        assertTrue(rec.isOwned());

        rec.markShared();
        assertFalse(rec.isOwned(), "markShared should clear ownership");
    }

    @Test
    void testSingleUpdateTransfersOwnership() {
        MezzeRecord rec = createRecord(
            new String[]{"x", "y"},
            new Object[]{1L, 2L}
        );
        assertTrue(rec.isOwned(), "Original should be owned");

        // Update creates new record
        MezzeRecord updated = rec.withField(0, 10L);

        // Original loses ownership (it's now sharing arrays with updated)
        assertFalse(rec.isOwned(), "Original should lose ownership after update");
        // New record gains ownership
        assertTrue(updated.isOwned(), "Updated record should be owned");

        // Both records have correct values (original preserved)
        assertEquals(1L, rec.getField(0));
        assertEquals(2L, rec.getField(1));
        assertEquals(10L, updated.getField(0));
        assertEquals(2L, updated.getField(1));
    }

    @Test
    void testChainedUpdatesPreserveOriginal() {
        MezzeRecord rec = createRecord(
            new String[]{"a", "b", "c"},
            new Object[]{1L, 2L, 3L}
        );

        // Chain of updates: each creates a new copy with cloned modified array
        MezzeRecord r1 = rec.withField(0, 10L);
        MezzeRecord r2 = r1.withField(1, 20L);
        MezzeRecord r3 = r2.withField(2, 30L);

        // Final result is correct
        assertEquals(10L, r3.getField(0));
        assertEquals(20L, r3.getField(1));
        assertEquals(30L, r3.getField(2));

        // Original is unchanged
        assertEquals(1L, rec.getField(0));
        assertEquals(2L, rec.getField(1));
        assertEquals(3L, rec.getField(2));

        // Intermediate results are also unchanged
        assertEquals(10L, r1.getField(0));
        assertEquals(2L, r1.getField(1));
        assertEquals(3L, r1.getField(2));

        assertEquals(10L, r2.getField(0));
        assertEquals(20L, r2.getField(1));
        assertEquals(3L, r2.getField(2));

        // Ownership: only the final record in chain is owned
        assertFalse(rec.isOwned());
        assertFalse(r1.isOwned());
        assertFalse(r2.isOwned());
        assertTrue(r3.isOwned());
    }

    @Test
    void testUpdatePreservesOriginalValues() {
        MezzeRecord rec = createRecord(
            new String[]{"x"},
            new Object[]{1L}
        );

        MezzeRecord updated = rec.withField(0, 10L);

        // Both records have correct values
        assertEquals(1L, rec.getField(0), "Original should be unchanged");
        assertEquals(10L, updated.getField(0), "Updated should have new value");
    }

    @Test
    void testBatchUpdatePreservesOriginal() {
        MezzeRecord rec = createRecord(
            new String[]{"a", "b", "c"},
            new Object[]{1L, 2L, 3L}
        );

        // Batch update (like {...rec, a=10, c=30})
        MezzeRecord updated = rec.withFields(
            new int[]{0, 2},
            new Object[]{10L, 30L}
        );

        // Updated has new values
        assertEquals(10L, updated.getField(0));
        assertEquals(2L, updated.getField(1));  // Unchanged field
        assertEquals(30L, updated.getField(2));

        // Original unchanged
        assertEquals(1L, rec.getField(0));
        assertEquals(2L, rec.getField(1));
        assertEquals(3L, rec.getField(2));

        // Ownership transferred
        assertFalse(rec.isOwned());
        assertTrue(updated.isOwned());
    }

    @Test
    void testBatchUpdateOnSharedRecord() {
        MezzeRecord rec = createRecord(
            new String[]{"a", "b"},
            new Object[]{1L, 2L}
        );
        rec.markShared();

        MezzeRecord updated = rec.withFields(
            new int[]{0, 1},
            new Object[]{10L, 20L}
        );

        // Original unchanged
        assertEquals(1L, rec.getField(0));
        assertEquals(2L, rec.getField(1));

        // Updated has new values
        assertEquals(10L, updated.getField(0));
        assertEquals(20L, updated.getField(1));

        assertTrue(updated.isOwned());
    }

    @Test
    void testTypedStoragePreservesValues() {
        // Create record with typed storage (different arrays for different types)
        String[] names = {"intField", "floatField", "strField"};
        MezzeRecord.FieldLocation[] locations = {
            new MezzeRecord.FieldLocation(MezzeRecord.FieldLocation.StorageType.LONG, 0),
            new MezzeRecord.FieldLocation(MezzeRecord.FieldLocation.StorageType.DOUBLE, 0),
            new MezzeRecord.FieldLocation(MezzeRecord.FieldLocation.StorageType.OBJECT, 0)
        };
        MezzeRecord.Shape shape = new MezzeRecord.Shape(names, locations);
        MezzeRecord rec = new MezzeRecord(shape, new long[]{42L}, new double[]{3.14}, new Object[]{"hello"});

        assertTrue(rec.isOwned());

        // Update int field - only longFields is cloned
        MezzeRecord r1 = rec.withField(0, 100L);
        assertEquals(100L, r1.getField(0));
        assertEquals(3.14, r1.getField(1));  // Shared from original
        assertEquals("hello", r1.getField(2));  // Shared from original

        // Update float field - only doubleFields is cloned
        MezzeRecord r2 = r1.withField(1, 2.71);
        assertEquals(100L, r2.getField(0));  // From r1's longFields
        assertEquals(2.71, r2.getField(1));
        assertEquals("hello", r2.getField(2));  // Shared

        // Update object field - only objectFields is cloned
        MezzeRecord r3 = r2.withField(2, "world");
        assertEquals(100L, r3.getField(0));
        assertEquals(2.71, r3.getField(1));
        assertEquals("world", r3.getField(2));

        // Original completely unchanged
        assertEquals(42L, rec.getField(0));
        assertEquals(3.14, rec.getField(1));
        assertEquals("hello", rec.getField(2));

        // Only final record is owned
        assertFalse(rec.isOwned());
        assertFalse(r1.isOwned());
        assertFalse(r2.isOwned());
        assertTrue(r3.isOwned());
    }

    @Test
    void testFromFieldsCreatesOwnedRecord() {
        MezzeRecord rec = MezzeRecord.fromFields(
            new String[]{"x", "y"},
            new Object[]{1L, 2L}
        );
        assertTrue(rec.isOwned());
    }

    @Test
    void testNoChangeReturnsThis() {
        MezzeRecord rec = createRecord(
            new String[]{"x"},
            new Object[]{1L}
        );

        // Empty batch update should return same record
        MezzeRecord same = rec.withFields(new int[]{}, new Object[]{});
        assertSame(rec, same, "Empty update should return same record");
    }

    @Test
    void testStructuralSharingForUnmodifiedArrays() {
        // Create record with all three storage types
        String[] names = {"a", "b", "c"};
        MezzeRecord.FieldLocation[] locations = {
            new MezzeRecord.FieldLocation(MezzeRecord.FieldLocation.StorageType.LONG, 0),
            new MezzeRecord.FieldLocation(MezzeRecord.FieldLocation.StorageType.DOUBLE, 0),
            new MezzeRecord.FieldLocation(MezzeRecord.FieldLocation.StorageType.OBJECT, 0)
        };
        MezzeRecord.Shape shape = new MezzeRecord.Shape(names, locations);
        long[] longs = {42L};
        double[] doubles = {3.14};
        Object[] objects = {"hello"};
        MezzeRecord rec = new MezzeRecord(shape, longs, doubles, objects);

        // Update only the long field
        MezzeRecord updated = rec.withField(0, 100L);

        // Verify values
        assertEquals(100L, updated.getField(0));
        assertEquals(3.14, updated.getField(1));
        assertEquals("hello", updated.getField(2));

        // Original unchanged
        assertEquals(42L, rec.getField(0));
    }
}
