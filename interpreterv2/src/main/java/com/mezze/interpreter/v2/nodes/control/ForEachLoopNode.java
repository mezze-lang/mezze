package com.mezze.interpreter.v2.nodes.control;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.values.MezzeIntArray;
import com.mezze.interpreter.v2.values.MezzeFloatArray;
import com.mezze.interpreter.v2.values.MezzeUnit;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.LoopNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.RepeatingNode;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;

/**
 * For-each loop node with OSR support - TYPE SPECIALIZED.
 *
 * Uses Truffle's LoopNode for On-Stack Replacement (OSR).
 * This enables JIT compilation mid-loop for long-running iterations.
 *
 * Type specializations:
 * 1. MezzeIntArray - NO BOXING, direct long iteration
 * 2. MezzeFloatArray - NO BOXING, direct double iteration
 * 3. Object[] - Generic object iteration
 *
 * The loop index is stored in a frame slot for proper Truffle semantics.
 * The array reference is passed via a frame slot to avoid storing mutable state in nodes.
 *
 * Example Mezze code this compiles:
 *   for x in array do body
 */
@NodeInfo(shortName = "foreach")
public final class ForEachLoopNode extends MezzeNode {

    @Child
    private MezzeNode iterableNode;

    @CompilationFinal
    private final int elementSlot;  // Frame slot to bind current element

    @CompilationFinal
    private final int indexSlot;    // Frame slot for loop index

    @CompilationFinal
    private final int arraySlot;    // Frame slot for array reference

    @CompilationFinal
    private final int lengthSlot;   // Frame slot for array length

    // Specialized loop nodes - LAZILY INITIALIZED
    // Only the loop type actually used will be created
    @Child
    private LoopNode intArrayLoopNode;

    @Child
    private LoopNode floatArrayLoopNode;

    @Child
    private LoopNode objectArrayLoopNode;

    // Body node template for lazy loop creation
    @Child
    private MezzeNode bodyNode;

    // Error branch profile - tell JIT this path is cold
    private final InlinedBranchProfile notIterableProfile = InlinedBranchProfile.getUncached();

    public ForEachLoopNode(MezzeNode iterableNode, int elementSlot, int indexSlot,
                           int arraySlot, int lengthSlot, MezzeNode bodyNode) {
        this.iterableNode = iterableNode;
        this.elementSlot = elementSlot;
        this.indexSlot = indexSlot;
        this.arraySlot = arraySlot;
        this.lengthSlot = lengthSlot;
        this.bodyNode = bodyNode;
        // Loop nodes are created lazily on first use
    }

    private LoopNode getOrCreateIntArrayLoop() {
        if (intArrayLoopNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            intArrayLoopNode = insert(Truffle.getRuntime().createLoopNode(
                new IntArrayRepeatingNode(elementSlot, indexSlot, arraySlot, lengthSlot, bodyNode)
            ));
        }
        return intArrayLoopNode;
    }

    private LoopNode getOrCreateFloatArrayLoop() {
        if (floatArrayLoopNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            floatArrayLoopNode = insert(Truffle.getRuntime().createLoopNode(
                new FloatArrayRepeatingNode(elementSlot, indexSlot, arraySlot, lengthSlot, bodyNode)
            ));
        }
        return floatArrayLoopNode;
    }

    private LoopNode getOrCreateObjectArrayLoop() {
        if (objectArrayLoopNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            objectArrayLoopNode = insert(Truffle.getRuntime().createLoopNode(
                new ObjectArrayRepeatingNode(elementSlot, indexSlot, arraySlot, lengthSlot, bodyNode)
            ));
        }
        return objectArrayLoopNode;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object iterable = iterableNode.execute(frame);

        if (iterable instanceof MezzeIntArray intArray) {
            return executeIntArray(frame, intArray);
        } else if (iterable instanceof MezzeFloatArray floatArray) {
            return executeFloatArray(frame, floatArray);
        } else if (iterable instanceof Object[] objArray) {
            return executeObjectArray(frame, objArray);
        }

        notIterableProfile.enter(this);
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeException("Cannot iterate over: " + iterable.getClass().getSimpleName());
    }

    private Object executeIntArray(VirtualFrame frame, MezzeIntArray array) {
        long[] elements = array.getElements();

        // Initialize loop state in frame slots
        frame.setInt(indexSlot, 0);
        frame.setObject(arraySlot, elements);  // Store long[] directly
        frame.setInt(lengthSlot, elements.length);

        // Execute OSR-enabled loop (lazy init on first use)
        getOrCreateIntArrayLoop().execute(frame);

        return MezzeUnit.INSTANCE;
    }

    private Object executeFloatArray(VirtualFrame frame, MezzeFloatArray array) {
        double[] elements = array.getElements();

        // Initialize loop state in frame slots
        frame.setInt(indexSlot, 0);
        frame.setObject(arraySlot, elements);  // Store double[] directly
        frame.setInt(lengthSlot, elements.length);

        // Execute OSR-enabled loop (lazy init on first use)
        getOrCreateFloatArrayLoop().execute(frame);

        return MezzeUnit.INSTANCE;
    }

    private Object executeObjectArray(VirtualFrame frame, Object[] array) {
        // Initialize loop state in frame slots
        frame.setInt(indexSlot, 0);
        frame.setObject(arraySlot, array);
        frame.setInt(lengthSlot, array.length);

        // Execute OSR-enabled loop (lazy init on first use)
        getOrCreateObjectArrayLoop().execute(frame);

        return MezzeUnit.INSTANCE;
    }

    /**
     * Create a for-each loop node.
     * Requires 3 additional frame slots: indexSlot, arraySlot, lengthSlot
     */
    public static ForEachLoopNode create(MezzeNode iterableNode, int elementSlot,
                                         int indexSlot, int arraySlot, int lengthSlot,
                                         MezzeNode bodyNode) {
        return new ForEachLoopNode(iterableNode, elementSlot, indexSlot, arraySlot, lengthSlot, bodyNode);
    }

    // ==================== RepeatingNode implementations ====================

    /**
     * RepeatingNode for MezzeIntArray - NO BOXING, direct long storage.
     * All state is in frame slots for proper Truffle semantics.
     */
    private static final class IntArrayRepeatingNode extends Node implements RepeatingNode {

        @CompilationFinal
        private final int elementSlot;

        @CompilationFinal
        private final int indexSlot;

        @CompilationFinal
        private final int arraySlot;

        @CompilationFinal
        private final int lengthSlot;

        @Child
        private MezzeNode bodyNode;

        IntArrayRepeatingNode(int elementSlot, int indexSlot, int arraySlot, int lengthSlot, MezzeNode bodyNode) {
            this.elementSlot = elementSlot;
            this.indexSlot = indexSlot;
            this.arraySlot = arraySlot;
            this.lengthSlot = lengthSlot;
            this.bodyNode = bodyNode;
        }

        @Override
        public boolean executeRepeating(VirtualFrame frame) {
            int index = frame.getInt(indexSlot);
            int length = frame.getInt(lengthSlot);

            if (index >= length) {
                return false;  // Done
            }

            // Get array and extract element - NO BOXING
            long[] elements = (long[]) frame.getObject(arraySlot);
            frame.setLong(elementSlot, elements[index]);

            // Increment index
            frame.setInt(indexSlot, index + 1);

            // Execute body
            bodyNode.execute(frame);
            return true;  // Continue
        }
    }

    /**
     * RepeatingNode for MezzeFloatArray - NO BOXING, direct double storage.
     */
    private static final class FloatArrayRepeatingNode extends Node implements RepeatingNode {

        @CompilationFinal
        private final int elementSlot;

        @CompilationFinal
        private final int indexSlot;

        @CompilationFinal
        private final int arraySlot;

        @CompilationFinal
        private final int lengthSlot;

        @Child
        private MezzeNode bodyNode;

        FloatArrayRepeatingNode(int elementSlot, int indexSlot, int arraySlot, int lengthSlot, MezzeNode bodyNode) {
            this.elementSlot = elementSlot;
            this.indexSlot = indexSlot;
            this.arraySlot = arraySlot;
            this.lengthSlot = lengthSlot;
            this.bodyNode = bodyNode;
        }

        @Override
        public boolean executeRepeating(VirtualFrame frame) {
            int index = frame.getInt(indexSlot);
            int length = frame.getInt(lengthSlot);

            if (index >= length) {
                return false;  // Done
            }

            // Get array and extract element - NO BOXING
            double[] elements = (double[]) frame.getObject(arraySlot);
            frame.setDouble(elementSlot, elements[index]);

            // Increment index
            frame.setInt(indexSlot, index + 1);

            // Execute body
            bodyNode.execute(frame);
            return true;  // Continue
        }
    }

    /**
     * RepeatingNode for Object[] - generic object iteration.
     */
    private static final class ObjectArrayRepeatingNode extends Node implements RepeatingNode {

        @CompilationFinal
        private final int elementSlot;

        @CompilationFinal
        private final int indexSlot;

        @CompilationFinal
        private final int arraySlot;

        @CompilationFinal
        private final int lengthSlot;

        @Child
        private MezzeNode bodyNode;

        ObjectArrayRepeatingNode(int elementSlot, int indexSlot, int arraySlot, int lengthSlot, MezzeNode bodyNode) {
            this.elementSlot = elementSlot;
            this.indexSlot = indexSlot;
            this.arraySlot = arraySlot;
            this.lengthSlot = lengthSlot;
            this.bodyNode = bodyNode;
        }

        @Override
        public boolean executeRepeating(VirtualFrame frame) {
            int index = frame.getInt(indexSlot);
            int length = frame.getInt(lengthSlot);

            if (index >= length) {
                return false;  // Done
            }

            // Get array and extract element
            Object[] elements = (Object[]) frame.getObject(arraySlot);
            frame.setObject(elementSlot, elements[index]);

            // Increment index
            frame.setInt(indexSlot, index + 1);

            // Execute body
            bodyNode.execute(frame);
            return true;  // Continue
        }
    }
}
