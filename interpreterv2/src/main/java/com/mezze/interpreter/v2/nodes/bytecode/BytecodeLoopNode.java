package com.mezze.interpreter.v2.nodes.bytecode;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.values.MezzeUnit;
import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.ExplodeLoop.LoopExplosionKind;
import com.oracle.truffle.api.nodes.LoopNode;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.RepeatingNode;
import com.oracle.truffle.api.profiles.LoopConditionProfile;

/**
 * BYTECODE LOOP node with OSR (On-Stack Replacement) support.
 *
 * This combines the bytecode interpreter with Truffle's LoopNode
 * to enable JIT compilation mid-loop for hot bytecode sequences.
 *
 * The bytecode loop is optimized for:
 * 1. Numeric computations (no boxing)
 * 2. Simple control flow (counted loops)
 * 3. Array operations
 *
 * Example optimization for a sum loop:
 * ```
 * var sum = 0
 * for i in 0..n do
 *     sum = sum + i
 * ```
 *
 * Compiles to bytecode that the JIT can fully unroll and vectorize.
 */
@NodeInfo(shortName = "bytecode-loop")
public final class BytecodeLoopNode extends MezzeNode {

    @Child
    private LoopNode loopNode;

    @CompilationFinal
    private final int iteratorSlot;  // Slot for loop variable

    @CompilationFinal
    private final int limitSlot;     // Slot for upper bound

    public BytecodeLoopNode(int iteratorSlot, int limitSlot, byte[] bodyBytecode,
                            long[] intConstants, double[] doubleConstants,
                            int stackBaseSlot, int maxStackDepth) {
        this.iteratorSlot = iteratorSlot;
        this.limitSlot = limitSlot;
        this.loopNode = Truffle.getRuntime().createLoopNode(
            new BytecodeRepeatingNode(iteratorSlot, limitSlot, bodyBytecode,
                                       intConstants, doubleConstants,
                                       stackBaseSlot, maxStackDepth)
        );
    }

    @Override
    public Object execute(VirtualFrame frame) {
        loopNode.execute(frame);
        return MezzeUnit.INSTANCE;
    }

    /**
     * Factory method.
     */
    public static BytecodeLoopNode create(int iteratorSlot, int limitSlot, byte[] bodyBytecode,
                                           long[] intConstants, double[] doubleConstants,
                                           int stackBaseSlot, int maxStackDepth) {
        return new BytecodeLoopNode(iteratorSlot, limitSlot, bodyBytecode,
                                    intConstants, doubleConstants,
                                    stackBaseSlot, maxStackDepth);
    }

    /**
     * Inner repeating node that executes bytecode per iteration.
     */
    private static final class BytecodeRepeatingNode extends MezzeNode implements RepeatingNode {

        @CompilationFinal
        private final int iteratorSlot;

        @CompilationFinal
        private final int limitSlot;

        @CompilationFinal(dimensions = 1)
        private final byte[] bytecode;

        @CompilationFinal(dimensions = 1)
        private final long[] intConstants;

        @CompilationFinal(dimensions = 1)
        private final double[] doubleConstants;

        @CompilationFinal
        private final int stackBaseSlot;

        @CompilationFinal
        private final int maxStackDepth;

        @CompilationFinal
        private final int bytecodeLength;

        private final LoopConditionProfile continueProfile = LoopConditionProfile.createCountingProfile();

        BytecodeRepeatingNode(int iteratorSlot, int limitSlot, byte[] bytecode,
                              long[] intConstants, double[] doubleConstants,
                              int stackBaseSlot, int maxStackDepth) {
            this.iteratorSlot = iteratorSlot;
            this.limitSlot = limitSlot;
            this.bytecode = bytecode;
            this.intConstants = intConstants;
            this.doubleConstants = doubleConstants;
            this.stackBaseSlot = stackBaseSlot;
            this.maxStackDepth = maxStackDepth;
            this.bytecodeLength = bytecode.length;
        }

        @Override
        public boolean executeRepeating(VirtualFrame frame) {
            // Check loop condition
            long i = frame.getLong(iteratorSlot);
            long limit = frame.getLong(limitSlot);

            if (!continueProfile.profile(i < limit)) {
                return false;  // Exit loop
            }

            // Execute bytecode body
            executeBody(frame);

            // Increment iterator
            frame.setLong(iteratorSlot, i + 1);

            return true;  // Continue
        }

        @ExplodeLoop(kind = LoopExplosionKind.MERGE_EXPLODE)
        private void executeBody(VirtualFrame frame) {
            CompilerAsserts.partialEvaluationConstant(bytecodeLength);

            int pc = 0;
            int sp = stackBaseSlot;

            while (pc < bytecodeLength) {
                byte opcode = bytecode[pc];

                switch (opcode) {
                    case BytecodeNode.OP_NOP -> pc++;

                    case BytecodeNode.OP_LOAD_CONST_INT -> {
                        int constIdx = bytecode[pc + 1] & 0xFF;
                        frame.setLong(sp, intConstants[constIdx]);
                        sp++;
                        pc += 2;
                    }

                    case BytecodeNode.OP_LOAD_LOCAL_INT -> {
                        int slot = bytecode[pc + 1] & 0xFF;
                        frame.setLong(sp, frame.getLong(slot));
                        sp++;
                        pc += 2;
                    }

                    case BytecodeNode.OP_STORE_LOCAL_INT -> {
                        sp--;
                        int slot = bytecode[pc + 1] & 0xFF;
                        frame.setLong(slot, frame.getLong(sp));
                        pc += 2;
                    }

                    case BytecodeNode.OP_ADD_INT -> {
                        sp--;
                        long b = frame.getLong(sp);
                        sp--;
                        long a = frame.getLong(sp);
                        frame.setLong(sp, a + b);
                        sp++;
                        pc++;
                    }

                    case BytecodeNode.OP_SUB_INT -> {
                        sp--;
                        long b = frame.getLong(sp);
                        sp--;
                        long a = frame.getLong(sp);
                        frame.setLong(sp, a - b);
                        sp++;
                        pc++;
                    }

                    case BytecodeNode.OP_MUL_INT -> {
                        sp--;
                        long b = frame.getLong(sp);
                        sp--;
                        long a = frame.getLong(sp);
                        frame.setLong(sp, a * b);
                        sp++;
                        pc++;
                    }

                    case BytecodeNode.OP_DIV_INT -> {
                        sp--;
                        long b = frame.getLong(sp);
                        sp--;
                        long a = frame.getLong(sp);
                        frame.setLong(sp, a / b);
                        sp++;
                        pc++;
                    }

                    case BytecodeNode.OP_INC_LOCAL_INT -> {
                        int slot = bytecode[pc + 1] & 0xFF;
                        frame.setLong(slot, frame.getLong(slot) + 1);
                        pc += 2;
                    }

                    case BytecodeNode.OP_POP -> {
                        sp--;
                        pc++;
                    }

                    default -> {
                        CompilerDirectives.transferToInterpreterAndInvalidate();
                        throw new RuntimeException("Unsupported bytecode in loop body: " + opcode);
                    }
                }
            }
        }

        @Override
        public Object execute(VirtualFrame frame) {
            throw new UnsupportedOperationException("Use executeRepeating");
        }
    }
}
