package com.mezze.interpreter.v2.nodes;

import com.mezze.interpreter.v2.nodes.continuation.*;
import com.mezze.interpreter.v2.nodes.effect.HandleNode;
import com.mezze.interpreter.v2.nodes.effect.PerformNode;
import com.mezze.interpreter.v2.nodes.function.FunctionDefinitionNode;
import com.mezze.interpreter.v2.nodes.function.InvokeNode;
import com.mezze.interpreter.v2.nodes.literal.IntLiteralNode;
import com.mezze.interpreter.v2.nodes.record.RecordLiteralNode;
import com.mezze.interpreter.v2.nodes.variable.ReadLocalNode;
import com.mezze.interpreter.v2.nodes.variable.WriteLocalNode;
import com.mezze.interpreter.v2.values.MezzeCont;
import com.mezze.interpreter.v2.values.MezzeFunction;
import com.mezze.interpreter.v2.values.MezzeRecord;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for effect handler support in V2.
 *
 * Run with: ./gradlew :interpreterv2:test --tests "*EffectHandlerTest*"
 */
public class EffectHandlerTest {

    @BeforeEach
    void setUp() {
        DelimiterStack.clear();
    }

    @AfterEach
    void tearDown() {
        DelimiterStack.clear();
    }

    /**
     * Test: HandleNode installs handler in frame slot
     */
    @Test
    void testHandleNodeInstallsHandler() {
        FrameDescriptor.Builder builder = FrameDescriptor.newBuilder();
        int handlerSlot = builder.addSlot(FrameSlotKind.Object, "handler", null);
        int resultSlot = builder.addSlot(FrameSlotKind.Object, "result", null);
        FrameDescriptor fd = builder.build();

        // Handler: record { operation = fn }
        MezzeNode handlerNode = RecordLiteralNode.allObjects(
                new String[]{"operation"},
                new MezzeNode[]{new IntLiteralNode(42)}
        );

        // Body: read the handler slot and return it
        MezzeNode bodyNode = new ReadLocalNode(handlerSlot, ReadLocalNode.KIND_OBJECT);

        HandleNode handleNode = new HandleNode(handlerSlot, new String[0], null, handlerNode, bodyNode);

        MezzeRootNode root = new MezzeRootNode(null, fd, handleNode, "test-handle", 0, 0, new int[0]);
        CallTarget target = root.getCallTarget();
        Object result = target.call();

        // Result should be the handler record
        assertTrue(result instanceof MezzeRecord);
        MezzeRecord handler = (MezzeRecord) result;
        assertEquals(42L, handler.getField("operation"));
    }

    /**
     * Test: HandleNode restores previous handler on exit
     */
    @Test
    void testHandleNodeRestoresPreviousHandler() {
        FrameDescriptor.Builder builder = FrameDescriptor.newBuilder();
        int handlerSlot = builder.addSlot(FrameSlotKind.Object, "handler", null);
        int resultSlot = builder.addSlot(FrameSlotKind.Object, "result", null);
        FrameDescriptor fd = builder.build();

        // Setup: write initial handler
        MezzeNode setupNode = WriteLocalNode.createObject(
                RecordLiteralNode.allObjects(
                        new String[]{"tag"},
                        new MezzeNode[]{new IntLiteralNode(1)}
                ),
                handlerSlot
        );

        // Inner handler
        MezzeNode innerHandlerNode = RecordLiteralNode.allObjects(
                new String[]{"tag"},
                new MezzeNode[]{new IntLiteralNode(2)}
        );

        // Inner body just returns 100
        MezzeNode innerBodyNode = new IntLiteralNode(100);

        // Handle with inner handler
        HandleNode innerHandle = new HandleNode(handlerSlot, new String[0], null, innerHandlerNode, innerBodyNode);

        // Outer body: do inner handle, then read handler slot
        MezzeNode outerBodyNode = new MezzeNode() {
            @Child
            MezzeNode inner = innerHandle;

            @Override
            public Object execute(com.oracle.truffle.api.frame.VirtualFrame frame) {
                inner.execute(frame); // Run inner handle
                return frame.getObject(handlerSlot); // Read handler slot - should be restored
            }
        };

        // Outer handler
        MezzeNode outerHandlerNode = RecordLiteralNode.allObjects(
                new String[]{"tag"},
                new MezzeNode[]{new IntLiteralNode(0)}
        );

        HandleNode outerHandle = new HandleNode(handlerSlot, new String[0], null, outerHandlerNode, outerBodyNode);

        MezzeRootNode root = new MezzeRootNode(null, fd, outerHandle, "test-restore", 0, 0, new int[0]);
        CallTarget target = root.getCallTarget();
        Object result = target.call();

        // After inner handle exits, outer handler should be restored
        assertTrue(result instanceof MezzeRecord);
        MezzeRecord handler = (MezzeRecord) result;
        assertEquals(0L, handler.getField("tag"));
    }

    /**
     * Test: PerformNode calls operation from handler
     */
    @Test
    void testPerformNodeCallsOperation() {
        FrameDescriptor.Builder builder = FrameDescriptor.newBuilder();
        int handlerSlot = builder.addSlot(FrameSlotKind.Object, "handler", null);
        FrameDescriptor fd = builder.build();

        // Create an operation function that returns its argument * 2
        FrameDescriptor opFd = FrameDescriptor.newBuilder().build();
        MezzeNode opBody = new MezzeNode() {
            @Override
            public Object execute(com.oracle.truffle.api.frame.VirtualFrame frame) {
                Object[] args = frame.getArguments();
                if (args.length > 0 && args[0] instanceof MezzeRecord record) {
                    Object x = record.getField("x");
                    if (x instanceof Long l) {
                        return l * 2;
                    }
                }
                return 0L;
            }
        };
        MezzeRootNode opRoot = new MezzeRootNode(null, opFd, opBody, "double", 0, 0, new int[0]);
        MezzeFunction opFunc = new MezzeFunction(opRoot.getCallTarget(), "double", new Object[0]);

        // Create handler record with the operation
        MezzeRecord handlerRecord = MezzeRecord.fromFields(
                new String[]{"double"},
                new Object[]{opFunc}
        );

        // Create a node that returns the handler
        MezzeNode handlerNode = new MezzeNode() {
            @Override
            public Object execute(com.oracle.truffle.api.frame.VirtualFrame frame) {
                return handlerRecord;
            }
        };

        // Create arguments: { x = 21 }
        MezzeNode argsNode = RecordLiteralNode.allObjects(
                new String[]{"x"},
                new MezzeNode[]{new IntLiteralNode(21)}
        );

        // PerformNode to call the "double" operation
        // handlerNode returns the handler, operationIndex 0 = "double"
        PerformNode performNode = new PerformNode(
                "double",
                0,  // operationIndex for field access
                handlerNode,
                new MezzeNode[]{argsNode}
        );

        MezzeRootNode root = new MezzeRootNode(null, fd, performNode, "test-perform", 0, 0, new int[0]);
        CallTarget target = root.getCallTarget();
        Object result = target.call();

        assertEquals(42L, result);
    }

    /**
     * Performance test for handle/perform pattern
     */
    @Test
    void testHandlePerformPerformance() {
        FrameDescriptor.Builder builder = FrameDescriptor.newBuilder();
        int handlerSlot = builder.addSlot(FrameSlotKind.Object, "handler", null);
        FrameDescriptor fd = builder.build();

        // Create simple handler with identity operation
        FrameDescriptor opFd = FrameDescriptor.newBuilder().build();
        MezzeNode opBody = new IntLiteralNode(100);
        MezzeRootNode opRoot = new MezzeRootNode(null, opFd, opBody, "identity", 0, 0, new int[0]);
        MezzeFunction opFunc = new MezzeFunction(opRoot.getCallTarget(), "identity", new Object[0]);

        MezzeRecord handlerRecord = MezzeRecord.fromFields(
                new String[]{"op"},
                new Object[]{opFunc}
        );

        MezzeNode handlerNode = new MezzeNode() {
            @Override
            public Object execute(com.oracle.truffle.api.frame.VirtualFrame frame) {
                return handlerRecord;
            }
        };

        MezzeNode argsNode = RecordLiteralNode.allObjects(new String[0], new MezzeNode[0]);

        // Body: just return handler record
        MezzeNode bodyNode = new MezzeNode() {
            @Override
            public Object execute(com.oracle.truffle.api.frame.VirtualFrame frame) {
                return handlerRecord;
            }
        };

        HandleNode handleNode = new HandleNode(handlerSlot, new String[0], null, handlerNode, bodyNode);

        MezzeRootNode root = new MezzeRootNode(null, fd, handleNode, "test-perf", 0, 0, new int[0]);
        CallTarget target = root.getCallTarget();

        // Warmup
        for (int i = 0; i < 10_000; i++) {
            target.call();
        }

        // Benchmark
        int iterations = 100_000;
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            target.call();
        }
        long elapsed = System.nanoTime() - start;

        System.out.printf("Handle/body pattern: %d iterations in %d ms (%.2f ns/op)%n",
                iterations, elapsed / 1_000_000, (double) elapsed / iterations);

        // Should be fast - just handler install/restore
        assertTrue(elapsed / iterations < 5000, "Handle pattern should be <5µs per op");
    }
}
