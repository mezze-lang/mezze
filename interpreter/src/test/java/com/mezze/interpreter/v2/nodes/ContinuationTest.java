package com.mezze.interpreter.v2.nodes;

import com.mezze.interpreter.v2.nodes.continuation.*;
import com.mezze.interpreter.v2.nodes.literal.IntLiteralNode;
import com.mezze.interpreter.v2.nodes.record.RecordLiteralNode;
import com.mezze.interpreter.v2.values.MezzeRecord;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.frame.FrameDescriptor;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for continuation support in V2.
 *
 * Run with: ./gradlew :interpreterv2:test --tests "*ContinuationTest*"
 */
public class ContinuationTest {

    @BeforeEach
    void setUp() {
        // Clear delimiter stack before each test
        DelimiterStack.clear();
    }

    @AfterEach
    void tearDown() {
        DelimiterStack.clear();
    }

    /**
     * Test: MezzeCont creation and uniqueness
     */
    @Test
    void testDelimiterCreation() {
        MezzeCont d1 = MezzeCont.create();
        MezzeCont d2 = MezzeCont.create();

        assertNotNull(d1);
        assertNotNull(d2);
        assertNotEquals(d1.getId(), d2.getId());
        assertNotEquals(d1, d2);
    }

    /**
     * Test: Delimiter stack push/pop
     */
    @Test
    void testDelimiterStack() {
        MezzeCont d1 = MezzeCont.create();
        MezzeCont d2 = MezzeCont.create();

        assertEquals(0, DelimiterStack.depth());

        DelimiterStack.push(d1);
        assertEquals(1, DelimiterStack.depth());
        assertEquals(d1.getId(), DelimiterStack.current().delimiter.getId());

        DelimiterStack.push(d2);
        assertEquals(2, DelimiterStack.depth());
        assertEquals(d2.getId(), DelimiterStack.current().delimiter.getId());

        DelimiterStack.DelimiterEntry popped = DelimiterStack.pop();
        assertEquals(d2.getId(), popped.delimiter.getId());
        assertEquals(1, DelimiterStack.depth());

        popped = DelimiterStack.pop();
        assertEquals(d1.getId(), popped.delimiter.getId());
        assertEquals(0, DelimiterStack.depth());
    }

    /**
     * Test: ContinuationDelimitNode creates delimiter
     */
    @Test
    void testContinuationDelimitNode() {
        FrameDescriptor fd = FrameDescriptor.newBuilder().build();

        ContinuationDelimitNode delimNode = ContinuationDelimitNode.create();

        MezzeRootNode root = new MezzeRootNode(null, fd, delimNode, "test-delimit", 0, 0, new int[0]);
        CallTarget target = root.getCallTarget();
        Object result = target.call();

        assertTrue(result instanceof MezzeCont);
    }

    /**
     * Test: ContinuationActivateNode pushes delimiter onto stack
     */
    @Test
    void testContinuationActivateNode() {
        FrameDescriptor fd = FrameDescriptor.newBuilder().build();

        MezzeCont delim = MezzeCont.create();

        // Create a record { delim = delim }
        MezzeNode recordNode = new MezzeNode() {
            @Override
            public Object execute(com.oracle.truffle.api.frame.VirtualFrame frame) {
                return MezzeRecord.fromFields(new String[]{"delim"}, new Object[]{delim});
            }
        };

        ContinuationActivateNode activateNode = ContinuationActivateNode.create(recordNode);

        MezzeRootNode root = new MezzeRootNode(null, fd, activateNode, "test-activate", 0, 0, new int[0]);
        CallTarget target = root.getCallTarget();

        assertEquals(0, DelimiterStack.depth());
        target.call();
        assertEquals(1, DelimiterStack.depth());
        assertEquals(delim.getId(), DelimiterStack.current().delimiter.getId());
    }

    /**
     * Test: MezzeContinuationSuspended structure
     */
    @Test
    void testSuspendedContinuationStructure() {
        FrameDescriptor fd = FrameDescriptor.newBuilder().build();

        // Create trivial body
        MezzeNode body = new IntLiteralNode(42);
        MezzeRootNode rootNode = new MezzeRootNode(null, fd, body, "trivial", 0, 1);

        MezzeContinuation cont = MezzeContinuation.create(rootNode.getCallTarget(), 123L);
        MezzeContinuationSuspended suspended = new MezzeContinuationSuspended("yielded_value", cont);

        assertEquals("yielded_value", suspended.getSuspendedValue());
        assertNotNull(suspended.getContinuation());
        assertEquals(123L, suspended.getContinuation().getDelimiterId());
    }

    /**
     * Test: Resuming a trivial continuation
     */
    @Test
    void testTrivialContinuationResume() {
        // Create frame descriptor with no slots - the body accesses args directly
        FrameDescriptor fd = FrameDescriptor.newBuilder().build();

        // Create a body that extracts value from { value: x } and returns it
        // Uses frame.getArguments() directly, not frame slots
        MezzeNode body = new MezzeNode() {
            @Override
            public Object execute(com.oracle.truffle.api.frame.VirtualFrame frame) {
                Object[] args = frame.getArguments();
                if (args.length > 0 && args[0] instanceof MezzeRecord record) {
                    Object value = record.getField("value");
                    return value != null ? value : 0L;
                }
                return 0L;
            }
        };

        // Use constructor with no slot types (0 closures, 0 params) to avoid slot binding
        MezzeRootNode rootNode = new MezzeRootNode(null, fd, body, "extract-value", 0, 0, new int[0]);
        MezzeContinuation cont = MezzeContinuation.create(rootNode.getCallTarget(), 1L);

        // Resume with { value = 100 }
        MezzeRecord resumeValue = MezzeRecord.fromFields(new String[]{"value"}, new Object[]{100L});
        Object result = cont.resume(resumeValue);

        assertEquals(100L, result);
    }

    /**
     * Test: MezzeVariant Some/None helpers
     */
    @Test
    void testVariantHelpers() {
        var none = com.mezze.interpreter.v2.values.MezzeVariant.none();
        assertNotNull(none);
        assertEquals("None", none.getConstructorName());
        assertTrue(none.isNone());
        assertFalse(none.isSome());

        var some = com.mezze.interpreter.v2.values.MezzeVariant.some(42L);
        assertNotNull(some);
        assertEquals("Some", some.getConstructorName());
        assertTrue(some.isSome());
        assertFalse(some.isNone());
    }

    /**
     * Performance test for delimiter creation
     */
    @Test
    void testDelimiterCreationPerformance() {
        // Warmup
        for (int i = 0; i < 10_000; i++) {
            MezzeCont.create();
        }

        // Benchmark
        int iterations = 100_000;
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            MezzeCont.create();
        }
        long elapsed = System.nanoTime() - start;

        System.out.printf("Delimiter creation: %d iterations in %d ms (%.2f ns/op)%n",
                iterations, elapsed / 1_000_000, (double) elapsed / iterations);

        // Should be fast - just atomic counter increment
        assertTrue(elapsed / iterations < 1000, "Delimiter creation should be <1µs per op");
    }

    /**
     * Performance test for delimiter stack operations
     */
    @Test
    void testDelimiterStackPerformance() {
        MezzeCont delim = MezzeCont.create();

        // Warmup
        for (int i = 0; i < 10_000; i++) {
            DelimiterStack.push(delim);
            DelimiterStack.pop();
        }

        // Benchmark
        int iterations = 100_000;
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            DelimiterStack.push(delim);
            DelimiterStack.pop();
        }
        long elapsed = System.nanoTime() - start;

        System.out.printf("Delimiter stack push/pop: %d iterations in %d ms (%.2f ns/op)%n",
                iterations, elapsed / 1_000_000, (double) elapsed / iterations);
    }
}
