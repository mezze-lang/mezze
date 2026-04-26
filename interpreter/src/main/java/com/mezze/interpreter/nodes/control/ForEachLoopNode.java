package com.mezze.interpreter.nodes.control;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.datastructures.seq.SeqStorage;
import com.mezze.datastructures.seq.LongSeqStorage;
import com.mezze.datastructures.seq.DoubleSeqStorage;
import com.mezze.datastructures.seq.ObjectSeqStorage;
import com.mezze.datastructures.list.MezzeList;
import com.mezze.interpreter.values.MezzeUnit;
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
 * For-each loop node with OSR support.
 *
 * Uses Truffle's LoopNode for On-Stack Replacement (OSR).
 * This enables JIT compilation mid-loop for long-running iterations.
 *
 * Type specializations:
 * 1. MezzeSeq (Vavr Vector) - O(log32 n) access per element
 * 2. MezzeList (Vavr List) - O(n) access per element (but O(1) for head/tail iteration)
 * 3. Object[] - Generic object iteration
 *
 * The loop index is stored in a frame slot for proper Truffle semantics.
 *
 * Example Mezze code this compiles:
 *   for x in sequence do body
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
    private final int arraySlot;    // Frame slot for sequence reference

    @CompilationFinal
    private final int lengthSlot;   // Frame slot for sequence length

    // Specialized loop nodes - LAZILY INITIALIZED
    @Child
    private LoopNode seqLoopNode;

    @Child
    private LoopNode listLoopNode;

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
    }

    private LoopNode getOrCreateSeqLoop() {
        if (seqLoopNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            seqLoopNode = insert(Truffle.getRuntime().createLoopNode(
                new SeqRepeatingNode(elementSlot, indexSlot, arraySlot, lengthSlot, bodyNode)
            ));
        }
        return seqLoopNode;
    }

    private LoopNode getOrCreateListLoop() {
        if (listLoopNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            listLoopNode = insert(Truffle.getRuntime().createLoopNode(
                new ListRepeatingNode(elementSlot, indexSlot, arraySlot, lengthSlot, bodyNode)
            ));
        }
        return listLoopNode;
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

        if (iterable instanceof MezzeSeq seq) {
            return executeSeq(frame, seq);
        } else if (iterable instanceof MezzeList list) {
            return executeList(frame, list);
        } else if (iterable instanceof Object[] objArray) {
            return executeObjectArray(frame, objArray);
        }

        notIterableProfile.enter(this);
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeException("Cannot iterate over: " + iterable.getClass().getSimpleName());
    }

    private Object executeSeq(VirtualFrame frame, MezzeSeq seq) {
        // Initialize loop state in frame slots
        frame.setInt(indexSlot, 0);
        frame.setObject(arraySlot, seq);
        frame.setInt(lengthSlot, seq.size());

        // Execute OSR-enabled loop
        getOrCreateSeqLoop().execute(frame);

        return MezzeUnit.INSTANCE;
    }

    private Object executeList(VirtualFrame frame, MezzeList list) {
        // Initialize loop state in frame slots
        frame.setInt(indexSlot, 0);
        frame.setObject(arraySlot, list);
        frame.setInt(lengthSlot, list.size());

        // Execute OSR-enabled loop
        getOrCreateListLoop().execute(frame);

        return MezzeUnit.INSTANCE;
    }

    private Object executeObjectArray(VirtualFrame frame, Object[] array) {
        // Initialize loop state in frame slots
        frame.setInt(indexSlot, 0);
        frame.setObject(arraySlot, array);
        frame.setInt(lengthSlot, array.length);

        // Execute OSR-enabled loop
        getOrCreateObjectArrayLoop().execute(frame);

        return MezzeUnit.INSTANCE;
    }

    /**
     * Create a for-each loop node.
     */
    public static ForEachLoopNode create(MezzeNode iterableNode, int elementSlot,
                                         int indexSlot, int arraySlot, int lengthSlot,
                                         MezzeNode bodyNode) {
        return new ForEachLoopNode(iterableNode, elementSlot, indexSlot, arraySlot, lengthSlot, bodyNode);
    }

    // ==================== RepeatingNode implementations ====================

    /**
     * RepeatingNode for MezzeSeq with storage-type profiling.
     * Direct array access for primitive sequences.
     */
    private static final class SeqRepeatingNode extends Node implements RepeatingNode {

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

        // Storage type profiling for JIT optimization
        @CompilationFinal
        private Class<?> seenStorageType;

        @CompilationFinal
        private boolean seenMultipleTypes;

        SeqRepeatingNode(int elementSlot, int indexSlot, int arraySlot, int lengthSlot, MezzeNode bodyNode) {
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

            // Get sequence and extract element with storage-type profiling
            MezzeSeq<?> seq = (MezzeSeq<?>) frame.getObject(arraySlot);
            SeqStorage storage = seq.getStorage();
            Class<?> storageType = storage.getClass();

            // Profile storage type
            if (!seenMultipleTypes) {
                if (seenStorageType == null) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    seenStorageType = storageType;
                } else if (seenStorageType != storageType) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    seenMultipleTypes = true;
                }
            }

            // Type-directed element extraction
            Object element;
            if (!seenMultipleTypes && seenStorageType == LongSeqStorage.class) {
                element = ((LongSeqStorage) storage).getArray()[index];
            } else if (!seenMultipleTypes && seenStorageType == DoubleSeqStorage.class) {
                element = ((DoubleSeqStorage) storage).getArray()[index];
            } else if (!seenMultipleTypes && seenStorageType == ObjectSeqStorage.class) {
                element = ((ObjectSeqStorage) storage).getArray()[index];
            } else {
                // Megamorphic fallback
                element = seq.get(index);
            }

            frame.setObject(elementSlot, element);

            // Increment index
            frame.setInt(indexSlot, index + 1);

            // Execute body
            bodyNode.execute(frame);
            return true;  // Continue
        }
    }

    /**
     * RepeatingNode for MezzeList (Vavr List).
     * Note: O(n²) total because each get(index) is O(n).
     * For better performance, consider using head/tail iteration in the future.
     */
    private static final class ListRepeatingNode extends Node implements RepeatingNode {

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

        ListRepeatingNode(int elementSlot, int indexSlot, int arraySlot, int lengthSlot, MezzeNode bodyNode) {
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

            // Get list and extract element (O(n) per access)
            MezzeList list = (MezzeList) frame.getObject(arraySlot);
            frame.setObject(elementSlot, list.get(index));

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
