package com.mezze.datastructures.bytes;

import org.junit.jupiter.api.*;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for MezzeBytes, MezzeBytesView, and MezzeByteBuffer.
 */
public class MezzeBytesTest {

    // ===== MezzeBytes CONSTRUCTION =====

    @Test
    void testEmpty() {
        MezzeBytes bytes = MezzeBytes.empty();
        assertEquals(0, bytes.size());
        assertTrue(bytes.isEmpty());
    }

    @Test
    void testOf() {
        MezzeBytes bytes = MezzeBytes.of((byte) 1, (byte) 2, (byte) 3);
        assertEquals(3, bytes.size());
        assertEquals((byte) 1, bytes.get(0));
        assertEquals((byte) 3, bytes.get(2));
    }

    @Test
    void testFromString() {
        MezzeBytes bytes = MezzeBytes.fromString("hello");
        assertEquals(5, bytes.size());
        assertEquals("hello", bytes.toStringUtf8());
    }

    @Test
    void testFromStringWithCharset() {
        MezzeBytes bytes = MezzeBytes.fromString("hello", StandardCharsets.US_ASCII);
        assertEquals(5, bytes.size());
        assertEquals("hello", bytes.toString(StandardCharsets.US_ASCII));
    }

    @Test
    void testWrap() {
        byte[] data = {1, 2, 3, 4, 5};
        MezzeBytes bytes = MezzeBytes.wrap(data);
        assertEquals(5, bytes.size());
        assertEquals((byte) 1, bytes.get(0));
    }

    // ===== MezzeBytes ACCESS =====

    @Test
    void testGet() {
        MezzeBytes bytes = MezzeBytes.of((byte) 10, (byte) 20, (byte) 30);
        assertEquals((byte) 10, bytes.get(0));
        assertEquals((byte) 20, bytes.get(1));
        assertEquals((byte) 30, bytes.get(2));
    }

    @Test
    void testGetUnsigned() {
        MezzeBytes bytes = MezzeBytes.of((byte) 0xFF, (byte) 0x80);
        assertEquals(255, bytes.getUnsigned(0));
        assertEquals(128, bytes.getUnsigned(1));
    }

    @Test
    void testGetData() {
        MezzeBytes bytes = MezzeBytes.of((byte) 1, (byte) 2);
        byte[] data = bytes.getData();
        assertArrayEquals(new byte[]{1, 2}, data);
    }

    // ===== MezzeBytes SLICING =====

    @Test
    void testSlice() {
        MezzeBytes bytes = MezzeBytes.of((byte) 1, (byte) 2, (byte) 3, (byte) 4, (byte) 5);
        MezzeBytes slice = bytes.slice(1, 4);

        assertEquals(3, slice.size());
        assertEquals((byte) 2, slice.get(0));
        assertEquals((byte) 4, slice.get(2));
    }

    @Test
    void testSliceEmpty() {
        MezzeBytes bytes = MezzeBytes.of((byte) 1, (byte) 2, (byte) 3);
        MezzeBytes slice = bytes.slice(1, 1);

        assertTrue(slice.isEmpty());
    }

    @Test
    void testSliceWhole() {
        MezzeBytes bytes = MezzeBytes.of((byte) 1, (byte) 2, (byte) 3);
        MezzeBytes slice = bytes.slice(0, 3);

        assertSame(bytes, slice);
    }

    @Test
    void testSliceView() {
        MezzeBytes bytes = MezzeBytes.of((byte) 1, (byte) 2, (byte) 3, (byte) 4, (byte) 5);
        MezzeBytesView view = bytes.sliceView(1, 4);

        assertEquals(3, view.size());
        assertEquals((byte) 2, view.get(0));
    }

    @Test
    void testTake() {
        MezzeBytes bytes = MezzeBytes.of((byte) 1, (byte) 2, (byte) 3, (byte) 4, (byte) 5);
        MezzeBytes taken = bytes.take(3);

        assertEquals(3, taken.size());
        assertEquals((byte) 1, taken.get(0));
        assertEquals((byte) 3, taken.get(2));
    }

    @Test
    void testDrop() {
        MezzeBytes bytes = MezzeBytes.of((byte) 1, (byte) 2, (byte) 3, (byte) 4, (byte) 5);
        MezzeBytes dropped = bytes.drop(2);

        assertEquals(3, dropped.size());
        assertEquals((byte) 3, dropped.get(0));
    }

    // ===== MezzeBytes CONCAT =====

    @Test
    void testConcat() {
        MezzeBytes b1 = MezzeBytes.of((byte) 1, (byte) 2);
        MezzeBytes b2 = MezzeBytes.of((byte) 3, (byte) 4);
        MezzeBytes result = b1.concat(b2);

        assertEquals(4, result.size());
        assertEquals((byte) 1, result.get(0));
        assertEquals((byte) 4, result.get(3));
    }

    @Test
    void testConcatWithEmpty() {
        MezzeBytes bytes = MezzeBytes.of((byte) 1, (byte) 2);
        MezzeBytes empty = MezzeBytes.empty();

        assertSame(bytes, bytes.concat(empty));
        assertSame(bytes, empty.concat(bytes));
    }

    @Test
    void testStaticConcat() {
        MezzeBytes b1 = MezzeBytes.of((byte) 1);
        MezzeBytes b2 = MezzeBytes.of((byte) 2);
        MezzeBytes b3 = MezzeBytes.of((byte) 3);
        MezzeBytes result = MezzeBytes.concat(b1, b2, b3);

        assertEquals(3, result.size());
        assertEquals((byte) 1, result.get(0));
        assertEquals((byte) 3, result.get(2));
    }

    // ===== MezzeBytes SEARCH =====

    @Test
    void testIndexOfByte() {
        MezzeBytes bytes = MezzeBytes.of((byte) 1, (byte) 2, (byte) 3, (byte) 2);
        assertEquals(1, bytes.indexOf((byte) 2));
        assertEquals(-1, bytes.indexOf((byte) 5));
    }

    @Test
    void testIndexOfByteFromIndex() {
        MezzeBytes bytes = MezzeBytes.of((byte) 1, (byte) 2, (byte) 3, (byte) 2);
        assertEquals(3, bytes.indexOf((byte) 2, 2));
    }

    @Test
    void testIndexOfPattern() {
        MezzeBytes bytes = MezzeBytes.fromString("hello world");
        MezzeBytes pattern = MezzeBytes.fromString("world");

        assertEquals(6, bytes.indexOf(pattern));
    }

    @Test
    void testIndexOfPatternNotFound() {
        MezzeBytes bytes = MezzeBytes.fromString("hello world");
        MezzeBytes pattern = MezzeBytes.fromString("xyz");

        assertEquals(-1, bytes.indexOf(pattern));
    }

    @Test
    void testContainsByte() {
        MezzeBytes bytes = MezzeBytes.of((byte) 1, (byte) 2, (byte) 3);
        assertTrue(bytes.contains((byte) 2));
        assertFalse(bytes.contains((byte) 5));
    }

    @Test
    void testContainsPattern() {
        MezzeBytes bytes = MezzeBytes.fromString("hello world");
        assertTrue(bytes.contains(MezzeBytes.fromString("llo")));
        assertFalse(bytes.contains(MezzeBytes.fromString("xyz")));
    }

    @Test
    void testStartsWith() {
        MezzeBytes bytes = MezzeBytes.fromString("hello world");
        assertTrue(bytes.startsWith(MezzeBytes.fromString("hello")));
        assertFalse(bytes.startsWith(MezzeBytes.fromString("world")));
    }

    @Test
    void testEndsWith() {
        MezzeBytes bytes = MezzeBytes.fromString("hello world");
        assertTrue(bytes.endsWith(MezzeBytes.fromString("world")));
        assertFalse(bytes.endsWith(MezzeBytes.fromString("hello")));
    }

    // ===== MezzeBytes CONVERSION =====

    @Test
    void testToStringUtf8() {
        MezzeBytes bytes = MezzeBytes.fromString("hello");
        assertEquals("hello", bytes.toStringUtf8());
    }

    @Test
    void testToArray() {
        MezzeBytes bytes = MezzeBytes.of((byte) 1, (byte) 2, (byte) 3);
        byte[] arr = bytes.toArray();
        assertArrayEquals(new byte[]{1, 2, 3}, arr);
    }

    @Test
    void testToHexString() {
        MezzeBytes bytes = MezzeBytes.of((byte) 0x0A, (byte) 0xFF, (byte) 0x00);
        assertEquals("0aff00", bytes.toHexString());
    }

    // ===== MezzeBytes EQUALITY =====

    @Test
    void testEquality() {
        MezzeBytes b1 = MezzeBytes.of((byte) 1, (byte) 2, (byte) 3);
        MezzeBytes b2 = MezzeBytes.of((byte) 1, (byte) 2, (byte) 3);
        MezzeBytes b3 = MezzeBytes.of((byte) 1, (byte) 2, (byte) 4);

        assertEquals(b1, b2);
        assertNotEquals(b1, b3);
    }

    @Test
    void testHashCode() {
        MezzeBytes b1 = MezzeBytes.of((byte) 1, (byte) 2, (byte) 3);
        MezzeBytes b2 = MezzeBytes.of((byte) 1, (byte) 2, (byte) 3);

        assertEquals(b1.hashCode(), b2.hashCode());
    }

    // ===== MezzeBytesView =====

    @Test
    void testViewBasics() {
        byte[] data = {1, 2, 3, 4, 5};
        MezzeBytesView view = new MezzeBytesView(data, 1, 3);

        assertEquals(3, view.size());
        assertEquals((byte) 2, view.get(0));
        assertEquals((byte) 4, view.get(2));
    }

    @Test
    void testViewSlice() {
        byte[] data = {1, 2, 3, 4, 5};
        MezzeBytesView view = new MezzeBytesView(data, 0, 5);
        MezzeBytesView subView = view.slice(1, 4);

        assertEquals(3, subView.size());
        assertEquals((byte) 2, subView.get(0));
    }

    @Test
    void testViewToBytes() {
        byte[] data = {1, 2, 3, 4, 5};
        MezzeBytesView view = new MezzeBytesView(data, 1, 3);
        MezzeBytes bytes = view.toBytes();

        assertEquals(3, bytes.size());
        assertEquals((byte) 2, bytes.get(0));
    }

    @Test
    void testViewToArray() {
        byte[] data = {1, 2, 3, 4, 5};
        MezzeBytesView view = new MezzeBytesView(data, 2, 2);
        byte[] arr = view.toArray();

        assertArrayEquals(new byte[]{3, 4}, arr);
    }

    @Test
    void testViewEquality() {
        byte[] data1 = {1, 2, 3, 4, 5};
        byte[] data2 = {0, 2, 3, 4, 0};

        MezzeBytesView view1 = new MezzeBytesView(data1, 1, 3);
        MezzeBytesView view2 = new MezzeBytesView(data2, 1, 3);

        assertEquals(view1, view2);
    }

    // ===== MezzeByteBuffer =====

    @Test
    void testBufferCreate() {
        MezzeByteBuffer buffer = MezzeByteBuffer.create();
        assertEquals(0, buffer.position());
    }

    @Test
    void testBufferWithCapacity() {
        MezzeByteBuffer buffer = MezzeByteBuffer.withCapacity(1024);
        assertEquals(0, buffer.position());
        assertTrue(buffer.capacity() >= 1024);
    }

    @Test
    void testBufferWriteByte() {
        MezzeByteBuffer buffer = MezzeByteBuffer.create();
        buffer.writeByte((byte) 42);
        buffer.writeByte(100); // int version

        MezzeBytes bytes = buffer.toBytes();
        assertEquals(2, bytes.size());
        assertEquals((byte) 42, bytes.get(0));
        assertEquals((byte) 100, bytes.get(1));
    }

    @Test
    void testBufferWriteShort() {
        MezzeByteBuffer buffer = MezzeByteBuffer.create();
        buffer.writeShort((short) 0x1234);

        MezzeBytes bytes = buffer.toBytes();
        assertEquals(2, bytes.size());
        assertEquals((byte) 0x12, bytes.get(0)); // Big-endian
        assertEquals((byte) 0x34, bytes.get(1));
    }

    @Test
    void testBufferWriteInt() {
        MezzeByteBuffer buffer = MezzeByteBuffer.create();
        buffer.writeInt(0x12345678);

        MezzeBytes bytes = buffer.toBytes();
        assertEquals(4, bytes.size());
        assertEquals((byte) 0x12, bytes.get(0));
        assertEquals((byte) 0x34, bytes.get(1));
        assertEquals((byte) 0x56, bytes.get(2));
        assertEquals((byte) 0x78, bytes.get(3));
    }

    @Test
    void testBufferWriteLong() {
        MezzeByteBuffer buffer = MezzeByteBuffer.create();
        buffer.writeLong(0x123456789ABCDEF0L);

        MezzeBytes bytes = buffer.toBytes();
        assertEquals(8, bytes.size());
        assertEquals((byte) 0x12, bytes.get(0));
        assertEquals((byte) 0xF0, bytes.get(7));
    }

    @Test
    void testBufferWriteBytes() {
        MezzeByteBuffer buffer = MezzeByteBuffer.create();
        buffer.writeBytes(new byte[]{1, 2, 3});
        buffer.writeBytes(MezzeBytes.of((byte) 4, (byte) 5));

        MezzeBytes bytes = buffer.toBytes();
        assertEquals(5, bytes.size());
    }

    @Test
    void testBufferWriteString() {
        MezzeByteBuffer buffer = MezzeByteBuffer.create();
        buffer.writeString("hello");

        MezzeBytes bytes = buffer.toBytes();
        assertEquals("hello", bytes.toStringUtf8());
    }

    @Test
    void testBufferWriteLengthPrefixedString() {
        MezzeByteBuffer buffer = MezzeByteBuffer.create();
        buffer.writeLengthPrefixedString("hi");

        MezzeBytes bytes = buffer.toBytes();
        assertEquals(6, bytes.size()); // 4 bytes length + 2 bytes "hi"
        // First 4 bytes should be length (2) in big-endian
        assertEquals((byte) 0, bytes.get(0));
        assertEquals((byte) 0, bytes.get(1));
        assertEquals((byte) 0, bytes.get(2));
        assertEquals((byte) 2, bytes.get(3));
    }

    @Test
    void testBufferLittleEndian() {
        MezzeByteBuffer buffer = MezzeByteBuffer.create();
        buffer.writeShortLE((short) 0x1234);
        buffer.writeIntLE(0x12345678);

        MezzeBytes bytes = buffer.toBytes();

        // Little-endian short
        assertEquals((byte) 0x34, bytes.get(0));
        assertEquals((byte) 0x12, bytes.get(1));

        // Little-endian int
        assertEquals((byte) 0x78, bytes.get(2));
        assertEquals((byte) 0x56, bytes.get(3));
        assertEquals((byte) 0x34, bytes.get(4));
        assertEquals((byte) 0x12, bytes.get(5));
    }

    @Test
    void testBufferReset() {
        MezzeByteBuffer buffer = MezzeByteBuffer.create();
        buffer.writeByte((byte) 1);
        buffer.writeByte((byte) 2);
        buffer.reset();

        assertEquals(0, buffer.position());

        buffer.writeByte((byte) 3);
        MezzeBytes bytes = buffer.toBytes();
        assertEquals(1, bytes.size());
        assertEquals((byte) 3, bytes.get(0));
    }

    @Test
    void testBufferChaining() {
        MezzeBytes bytes = MezzeByteBuffer.create()
                .writeByte((byte) 1)
                .writeShort((short) 2)
                .writeInt(3)
                .toBytes();

        assertEquals(7, bytes.size()); // 1 + 2 + 4
    }

    @Test
    void testBufferGrowth() {
        MezzeByteBuffer buffer = MezzeByteBuffer.withCapacity(16);

        // Write more than initial capacity
        for (int i = 0; i < 100; i++) {
            buffer.writeByte((byte) i);
        }

        MezzeBytes bytes = buffer.toBytes();
        assertEquals(100, bytes.size());
        for (int i = 0; i < 100; i++) {
            assertEquals((byte) i, bytes.get(i));
        }
    }

    @Test
    void testBufferToArray() {
        MezzeByteBuffer buffer = MezzeByteBuffer.create();
        buffer.writeBytes(new byte[]{1, 2, 3});

        byte[] arr = buffer.toArray();
        assertArrayEquals(new byte[]{1, 2, 3}, arr);
    }

    // ===== Edge Cases =====

    @Test
    void testEmptyBytesOperations() {
        MezzeBytes empty = MezzeBytes.empty();

        assertTrue(empty.isEmpty());
        assertEquals(-1, empty.indexOf((byte) 1));
        assertSame(empty, empty.take(10));
        assertSame(empty, empty.drop(10));
        assertTrue(empty.startsWith(MezzeBytes.empty()));
        assertTrue(empty.endsWith(MezzeBytes.empty()));
    }

    @Test
    void testLargeBytes() {
        byte[] data = new byte[10000];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i & 0xFF);
        }

        MezzeBytes bytes = MezzeBytes.wrap(data);
        assertEquals(10000, bytes.size());

        MezzeBytes slice = bytes.slice(5000, 6000);
        assertEquals(1000, slice.size());
    }
}
