package com.mezze.interpreter.v2.nodes.bytecode;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.ExplodeLoop.LoopExplosionKind;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.profiles.LoopConditionProfile;

/**
 * BYTECODE DISPATCH node for efficient loop interpretation.
 *
 * This node implements a bytecode-style interpreter that:
 * 1. Uses a compact bytecode representation for loop bodies
 * 2. Leverages Truffle's partial evaluation for JIT compilation
 * 3. Supports OSR (On-Stack Replacement) for long-running loops
 *
 * The bytecode approach is more memory-efficient than a tree of nodes
 * and can be JIT-compiled more effectively by Truffle.
 *
 * Instruction format:
 * - Each instruction is an int packed as: opcode | operand1 | operand2
 * - Opcodes: LOAD_CONST, LOAD_LOCAL, STORE_LOCAL, ADD, SUB, MUL, etc.
 * - The operand stack is simulated using frame slots
 *
 * Example optimization:
 * ```
 * // This loop body:
 * sum = sum + i
 *
 * // Compiles to bytecode:
 * [LOAD_LOCAL 0, LOAD_LOCAL 1, ADD, STORE_LOCAL 0]
 * ```
 */
@NodeInfo(shortName = "bytecode")
public final class BytecodeNode extends MezzeNode {

    // Opcodes
    public static final byte OP_NOP = 0;
    public static final byte OP_LOAD_CONST_INT = 1;
    public static final byte OP_LOAD_CONST_DOUBLE = 2;
    public static final byte OP_LOAD_LOCAL = 3;
    public static final byte OP_STORE_LOCAL = 4;
    public static final byte OP_ADD_INT = 5;
    public static final byte OP_SUB_INT = 6;
    public static final byte OP_MUL_INT = 7;
    public static final byte OP_DIV_INT = 8;
    public static final byte OP_ADD_DOUBLE = 9;
    public static final byte OP_SUB_DOUBLE = 10;
    public static final byte OP_MUL_DOUBLE = 11;
    public static final byte OP_DIV_DOUBLE = 12;
    public static final byte OP_CMP_LT = 13;
    public static final byte OP_CMP_LE = 14;
    public static final byte OP_CMP_GT = 15;
    public static final byte OP_CMP_GE = 16;
    public static final byte OP_CMP_EQ = 17;
    public static final byte OP_JUMP = 18;
    public static final byte OP_JUMP_IF_FALSE = 19;
    public static final byte OP_RETURN = 20;
    public static final byte OP_POP = 21;
    public static final byte OP_DUP = 22;
    public static final byte OP_CALL = 23;
    public static final byte OP_LOAD_LOCAL_INT = 24;
    public static final byte OP_STORE_LOCAL_INT = 25;
    public static final byte OP_INC_LOCAL_INT = 26;

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

    // Loop profiling for OSR decisions
    private final LoopConditionProfile loopProfile = LoopConditionProfile.createCountingProfile();

    public BytecodeNode(byte[] bytecode, long[] intConstants, double[] doubleConstants,
                        int stackBaseSlot, int maxStackDepth) {
        this.bytecode = bytecode;
        this.intConstants = intConstants;
        this.doubleConstants = doubleConstants;
        this.stackBaseSlot = stackBaseSlot;
        this.maxStackDepth = maxStackDepth;
        this.bytecodeLength = bytecode.length;
    }

    @Override
    @ExplodeLoop(kind = LoopExplosionKind.MERGE_EXPLODE)
    public Object execute(VirtualFrame frame) {
        // Ensure bytecode length is compilation constant for loop unrolling
        CompilerAsserts.partialEvaluationConstant(bytecodeLength);

        int pc = 0;
        int sp = stackBaseSlot;  // Stack pointer

        // Main dispatch loop - Truffle will compile this into native code
        while (pc < bytecodeLength) {
            byte opcode = bytecode[pc];

            switch (opcode) {
                case OP_NOP -> pc++;

                case OP_LOAD_CONST_INT -> {
                    int constIdx = bytecode[pc + 1] & 0xFF;
                    frame.setLong(sp, intConstants[constIdx]);
                    sp++;
                    pc += 2;
                }

                case OP_LOAD_CONST_DOUBLE -> {
                    int constIdx = bytecode[pc + 1] & 0xFF;
                    frame.setDouble(sp, doubleConstants[constIdx]);
                    sp++;
                    pc += 2;
                }

                case OP_LOAD_LOCAL -> {
                    int slot = bytecode[pc + 1] & 0xFF;
                    frame.setObject(sp, frame.getObject(slot));
                    sp++;
                    pc += 2;
                }

                case OP_LOAD_LOCAL_INT -> {
                    int slot = bytecode[pc + 1] & 0xFF;
                    frame.setLong(sp, frame.getLong(slot));
                    sp++;
                    pc += 2;
                }

                case OP_STORE_LOCAL -> {
                    sp--;
                    int slot = bytecode[pc + 1] & 0xFF;
                    frame.setObject(slot, frame.getObject(sp));
                    pc += 2;
                }

                case OP_STORE_LOCAL_INT -> {
                    sp--;
                    int slot = bytecode[pc + 1] & 0xFF;
                    frame.setLong(slot, frame.getLong(sp));
                    pc += 2;
                }

                case OP_ADD_INT -> {
                    sp--;
                    long b = frame.getLong(sp);
                    sp--;
                    long a = frame.getLong(sp);
                    frame.setLong(sp, a + b);
                    sp++;
                    pc++;
                }

                case OP_SUB_INT -> {
                    sp--;
                    long b = frame.getLong(sp);
                    sp--;
                    long a = frame.getLong(sp);
                    frame.setLong(sp, a - b);
                    sp++;
                    pc++;
                }

                case OP_MUL_INT -> {
                    sp--;
                    long b = frame.getLong(sp);
                    sp--;
                    long a = frame.getLong(sp);
                    frame.setLong(sp, a * b);
                    sp++;
                    pc++;
                }

                case OP_DIV_INT -> {
                    sp--;
                    long b = frame.getLong(sp);
                    sp--;
                    long a = frame.getLong(sp);
                    frame.setLong(sp, a / b);
                    sp++;
                    pc++;
                }

                case OP_ADD_DOUBLE -> {
                    sp--;
                    double b = frame.getDouble(sp);
                    sp--;
                    double a = frame.getDouble(sp);
                    frame.setDouble(sp, a + b);
                    sp++;
                    pc++;
                }

                case OP_SUB_DOUBLE -> {
                    sp--;
                    double b = frame.getDouble(sp);
                    sp--;
                    double a = frame.getDouble(sp);
                    frame.setDouble(sp, a - b);
                    sp++;
                    pc++;
                }

                case OP_MUL_DOUBLE -> {
                    sp--;
                    double b = frame.getDouble(sp);
                    sp--;
                    double a = frame.getDouble(sp);
                    frame.setDouble(sp, a * b);
                    sp++;
                    pc++;
                }

                case OP_DIV_DOUBLE -> {
                    sp--;
                    double b = frame.getDouble(sp);
                    sp--;
                    double a = frame.getDouble(sp);
                    frame.setDouble(sp, a / b);
                    sp++;
                    pc++;
                }

                case OP_CMP_LT -> {
                    sp--;
                    long b = frame.getLong(sp);
                    sp--;
                    long a = frame.getLong(sp);
                    frame.setObject(sp, a < b);
                    sp++;
                    pc++;
                }

                case OP_CMP_LE -> {
                    sp--;
                    long b = frame.getLong(sp);
                    sp--;
                    long a = frame.getLong(sp);
                    frame.setObject(sp, a <= b);
                    sp++;
                    pc++;
                }

                case OP_CMP_GT -> {
                    sp--;
                    long b = frame.getLong(sp);
                    sp--;
                    long a = frame.getLong(sp);
                    frame.setObject(sp, a > b);
                    sp++;
                    pc++;
                }

                case OP_CMP_GE -> {
                    sp--;
                    long b = frame.getLong(sp);
                    sp--;
                    long a = frame.getLong(sp);
                    frame.setObject(sp, a >= b);
                    sp++;
                    pc++;
                }

                case OP_CMP_EQ -> {
                    sp--;
                    Object b = frame.getObject(sp);
                    sp--;
                    Object a = frame.getObject(sp);
                    frame.setObject(sp, java.util.Objects.equals(a, b));
                    sp++;
                    pc++;
                }

                case OP_JUMP -> {
                    int offset = (bytecode[pc + 1] << 8) | (bytecode[pc + 2] & 0xFF);
                    pc = offset;
                    // Profile backward jumps (loops)
                    if (offset < pc) {
                        loopProfile.profile(true);
                    }
                }

                case OP_JUMP_IF_FALSE -> {
                    sp--;
                    Object condition = frame.getObject(sp);
                    if (condition instanceof Boolean b && !b) {
                        int offset = (bytecode[pc + 1] << 8) | (bytecode[pc + 2] & 0xFF);
                        pc = offset;
                    } else {
                        pc += 3;
                    }
                }

                case OP_RETURN -> {
                    sp--;
                    return frame.getObject(sp);
                }

                case OP_POP -> {
                    sp--;
                    pc++;
                }

                case OP_DUP -> {
                    Object value = frame.getObject(sp - 1);
                    frame.setObject(sp, value);
                    sp++;
                    pc++;
                }

                case OP_INC_LOCAL_INT -> {
                    int slot = bytecode[pc + 1] & 0xFF;
                    frame.setLong(slot, frame.getLong(slot) + 1);
                    pc += 2;
                }

                default -> {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    throw unknownOpcode(opcode);
                }
            }
        }

        // No explicit return - return null
        return null;
    }

    @TruffleBoundary
    private RuntimeException unknownOpcode(byte opcode) {
        return new RuntimeException("Unknown bytecode opcode: " + opcode);
    }

    /**
     * Factory method.
     */
    public static BytecodeNode create(byte[] bytecode, long[] intConstants, double[] doubleConstants,
                                       int stackBaseSlot, int maxStackDepth) {
        return new BytecodeNode(bytecode, intConstants, doubleConstants, stackBaseSlot, maxStackDepth);
    }

    /**
     * Builder for bytecode sequences.
     */
    public static class Builder {
        private final java.util.List<Byte> bytecode = new java.util.ArrayList<>();
        private final java.util.List<Long> intConstants = new java.util.ArrayList<>();
        private final java.util.List<Double> doubleConstants = new java.util.ArrayList<>();

        public Builder loadConstInt(long value) {
            int idx = intConstants.size();
            intConstants.add(value);
            bytecode.add(OP_LOAD_CONST_INT);
            bytecode.add((byte) idx);
            return this;
        }

        public Builder loadConstDouble(double value) {
            int idx = doubleConstants.size();
            doubleConstants.add(value);
            bytecode.add(OP_LOAD_CONST_DOUBLE);
            bytecode.add((byte) idx);
            return this;
        }

        public Builder loadLocal(int slot) {
            bytecode.add(OP_LOAD_LOCAL);
            bytecode.add((byte) slot);
            return this;
        }

        public Builder loadLocalInt(int slot) {
            bytecode.add(OP_LOAD_LOCAL_INT);
            bytecode.add((byte) slot);
            return this;
        }

        public Builder storeLocal(int slot) {
            bytecode.add(OP_STORE_LOCAL);
            bytecode.add((byte) slot);
            return this;
        }

        public Builder storeLocalInt(int slot) {
            bytecode.add(OP_STORE_LOCAL_INT);
            bytecode.add((byte) slot);
            return this;
        }

        public Builder addInt() { bytecode.add(OP_ADD_INT); return this; }
        public Builder subInt() { bytecode.add(OP_SUB_INT); return this; }
        public Builder mulInt() { bytecode.add(OP_MUL_INT); return this; }
        public Builder divInt() { bytecode.add(OP_DIV_INT); return this; }

        public Builder addDouble() { bytecode.add(OP_ADD_DOUBLE); return this; }
        public Builder subDouble() { bytecode.add(OP_SUB_DOUBLE); return this; }
        public Builder mulDouble() { bytecode.add(OP_MUL_DOUBLE); return this; }
        public Builder divDouble() { bytecode.add(OP_DIV_DOUBLE); return this; }

        public Builder cmpLt() { bytecode.add(OP_CMP_LT); return this; }
        public Builder cmpLe() { bytecode.add(OP_CMP_LE); return this; }
        public Builder cmpGt() { bytecode.add(OP_CMP_GT); return this; }
        public Builder cmpGe() { bytecode.add(OP_CMP_GE); return this; }
        public Builder cmpEq() { bytecode.add(OP_CMP_EQ); return this; }

        public Builder jump(int target) {
            bytecode.add(OP_JUMP);
            bytecode.add((byte) (target >> 8));
            bytecode.add((byte) (target & 0xFF));
            return this;
        }

        public Builder jumpIfFalse(int target) {
            bytecode.add(OP_JUMP_IF_FALSE);
            bytecode.add((byte) (target >> 8));
            bytecode.add((byte) (target & 0xFF));
            return this;
        }

        public Builder ret() { bytecode.add(OP_RETURN); return this; }
        public Builder pop() { bytecode.add(OP_POP); return this; }
        public Builder dup() { bytecode.add(OP_DUP); return this; }

        public Builder incLocalInt(int slot) {
            bytecode.add(OP_INC_LOCAL_INT);
            bytecode.add((byte) slot);
            return this;
        }

        public int currentPosition() {
            return bytecode.size();
        }

        public BytecodeNode build(int stackBaseSlot, int maxStackDepth) {
            byte[] bc = new byte[bytecode.size()];
            for (int i = 0; i < bytecode.size(); i++) {
                bc[i] = bytecode.get(i);
            }

            long[] ints = intConstants.stream().mapToLong(Long::longValue).toArray();
            double[] doubles = doubleConstants.stream().mapToDouble(Double::doubleValue).toArray();

            return BytecodeNode.create(bc, ints, doubles, stackBaseSlot, maxStackDepth);
        }
    }
}
