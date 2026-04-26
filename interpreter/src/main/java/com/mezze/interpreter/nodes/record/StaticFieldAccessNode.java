package com.mezze.interpreter.nodes.record;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeRecord.Shape;
import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.InvalidAssumptionException;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;

/**
 * STATIC field access nodes - ZERO DISPATCH OVERHEAD.
 *
 * These are specialized per storage type, eliminating the need for:
 * 1. @Specialization guards
 * 2. Storage type dispatch at runtime
 * 3. Abstract method calls
 *
 * When we know the record shape at compile time (via content-hash),
 * we generate the exact node class needed.
 *
 * After JIT: Direct array access, fully inlined.
 */
public final class StaticFieldAccessNode {

    private StaticFieldAccessNode() {} // Utility class

    /**
     * Access a Long (Int) field - direct unboxed access.
     * Uses Truffle Assumptions for shape stability - JIT can eliminate all guards.
     */
    @NodeInfo(shortName = "field-long")
    public static final class LongField extends MezzeNode {
        @Child
        private MezzeNode recordNode;

        @CompilationFinal
        private final int locationIndex;

        // Shape assumption - allows JIT to completely remove shape checks
        @CompilationFinal
        private Assumption shapeStableAssumption;

        // Expected shape for fallback identity check
        @CompilationFinal
        private Shape expectedShape;

        // Error profile for shape mismatch (rarely used after warmup)
        private final InlinedBranchProfile shapeMismatchProfile = InlinedBranchProfile.getUncached();

        public LongField(MezzeNode recordNode, int locationIndex) {
            this.recordNode = recordNode;
            this.locationIndex = locationIndex;
        }

        public LongField(MezzeNode recordNode, int locationIndex, Shape expectedShape) {
            this.recordNode = recordNode;
            this.locationIndex = locationIndex;
            this.expectedShape = expectedShape;
            // Create assumption for this shape - if shape never changes, JIT removes all checks
            this.shapeStableAssumption = Truffle.getRuntime().createAssumption("shape-stable-long");
        }

        @Override
        public Object execute(VirtualFrame frame) {
            MezzeRecord record = (MezzeRecord) recordNode.execute(frame);
            checkShapeStability(record);
            return record.getLongField(locationIndex);
        }

        @Override
        public long executeLong(VirtualFrame frame) {
            MezzeRecord record = (MezzeRecord) recordNode.execute(frame);
            checkShapeStability(record);
            return record.getLongField(locationIndex);
        }

        @Override
        public double executeDouble(VirtualFrame frame) {
            MezzeRecord record = (MezzeRecord) recordNode.execute(frame);
            checkShapeStability(record);
            return record.getLongField(locationIndex);
        }

        private void checkShapeStability(MezzeRecord record) {
            // Fast path: assumption still valid means JIT can eliminate this entire check
            if (shapeStableAssumption != null && shapeStableAssumption.isValid()) {
                if (record.getShape() == expectedShape) {
                    return; // Shape matches, all good
                }
                // Shape changed - invalidate assumption
                shapeStableAssumption.invalidate();
            }
            // Slow path: shape guard with deoptimization
            if (expectedShape != null && record.getShape() != expectedShape) {
                shapeMismatchProfile.enter(this);
                CompilerDirectives.transferToInterpreterAndInvalidate();
                expectedShape = null;
                shapeStableAssumption = null;
            }
        }
    }

    /**
     * Access a Double (Float) field - direct unboxed access.
     * Uses Truffle Assumptions for shape stability - JIT can eliminate all guards.
     */
    @NodeInfo(shortName = "field-double")
    public static final class DoubleField extends MezzeNode {
        @Child
        private MezzeNode recordNode;

        @CompilationFinal
        private final int locationIndex;

        @CompilationFinal
        private Assumption shapeStableAssumption;

        @CompilationFinal
        private Shape expectedShape;

        private final InlinedBranchProfile shapeMismatchProfile = InlinedBranchProfile.getUncached();

        public DoubleField(MezzeNode recordNode, int locationIndex) {
            this.recordNode = recordNode;
            this.locationIndex = locationIndex;
        }

        public DoubleField(MezzeNode recordNode, int locationIndex, Shape expectedShape) {
            this.recordNode = recordNode;
            this.locationIndex = locationIndex;
            this.expectedShape = expectedShape;
            this.shapeStableAssumption = Truffle.getRuntime().createAssumption("shape-stable-double");
        }

        @Override
        public Object execute(VirtualFrame frame) {
            MezzeRecord record = (MezzeRecord) recordNode.execute(frame);
            checkShapeStability(record);
            return record.getDoubleField(locationIndex);
        }

        @Override
        public double executeDouble(VirtualFrame frame) {
            MezzeRecord record = (MezzeRecord) recordNode.execute(frame);
            checkShapeStability(record);
            return record.getDoubleField(locationIndex);
        }

        private void checkShapeStability(MezzeRecord record) {
            if (shapeStableAssumption != null && shapeStableAssumption.isValid()) {
                if (record.getShape() == expectedShape) {
                    return;
                }
                shapeStableAssumption.invalidate();
            }
            if (expectedShape != null && record.getShape() != expectedShape) {
                shapeMismatchProfile.enter(this);
                CompilerDirectives.transferToInterpreterAndInvalidate();
                expectedShape = null;
                shapeStableAssumption = null;
            }
        }
    }

    /**
     * Access an Object field.
     * Uses Truffle Assumptions for shape stability - JIT can eliminate all guards.
     */
    @NodeInfo(shortName = "field-object")
    public static final class ObjectField extends MezzeNode {
        @Child
        private MezzeNode recordNode;

        @CompilationFinal
        private final int locationIndex;

        @CompilationFinal
        private Assumption shapeStableAssumption;

        @CompilationFinal
        private Shape expectedShape;

        private final InlinedBranchProfile shapeMismatchProfile = InlinedBranchProfile.getUncached();

        public ObjectField(MezzeNode recordNode, int locationIndex) {
            this.recordNode = recordNode;
            this.locationIndex = locationIndex;
        }

        public ObjectField(MezzeNode recordNode, int locationIndex, Shape expectedShape) {
            this.recordNode = recordNode;
            this.locationIndex = locationIndex;
            this.expectedShape = expectedShape;
            this.shapeStableAssumption = Truffle.getRuntime().createAssumption("shape-stable-object");
        }

        @Override
        public Object execute(VirtualFrame frame) {
            MezzeRecord record = (MezzeRecord) recordNode.execute(frame);
            checkShapeStability(record);
            return record.getObjectField(locationIndex);
        }

        private void checkShapeStability(MezzeRecord record) {
            if (shapeStableAssumption != null && shapeStableAssumption.isValid()) {
                if (record.getShape() == expectedShape) {
                    return;
                }
                shapeStableAssumption.invalidate();
            }
            if (expectedShape != null && record.getShape() != expectedShape) {
                shapeMismatchProfile.enter(this);
                CompilerDirectives.transferToInterpreterAndInvalidate();
                expectedShape = null;
                shapeStableAssumption = null;
            }
        }
    }

    /**
     * Access a Boolean field (stored in Object array).
     * Uses Truffle Assumptions for shape stability - JIT can eliminate all guards.
     */
    @NodeInfo(shortName = "field-boolean")
    public static final class BooleanField extends MezzeNode {
        @Child
        private MezzeNode recordNode;

        @CompilationFinal
        private final int locationIndex;

        @CompilationFinal
        private Assumption shapeStableAssumption;

        @CompilationFinal
        private Shape expectedShape;

        private final InlinedBranchProfile shapeMismatchProfile = InlinedBranchProfile.getUncached();

        public BooleanField(MezzeNode recordNode, int locationIndex) {
            this.recordNode = recordNode;
            this.locationIndex = locationIndex;
        }

        public BooleanField(MezzeNode recordNode, int locationIndex, Shape expectedShape) {
            this.recordNode = recordNode;
            this.locationIndex = locationIndex;
            this.expectedShape = expectedShape;
            this.shapeStableAssumption = Truffle.getRuntime().createAssumption("shape-stable-boolean");
        }

        @Override
        public Object execute(VirtualFrame frame) {
            MezzeRecord record = (MezzeRecord) recordNode.execute(frame);
            checkShapeStability(record);
            return record.getObjectField(locationIndex);
        }

        @Override
        public boolean executeBoolean(VirtualFrame frame) {
            MezzeRecord record = (MezzeRecord) recordNode.execute(frame);
            checkShapeStability(record);
            return (Boolean) record.getObjectField(locationIndex);
        }

        private void checkShapeStability(MezzeRecord record) {
            if (shapeStableAssumption != null && shapeStableAssumption.isValid()) {
                if (record.getShape() == expectedShape) {
                    return;
                }
                shapeStableAssumption.invalidate();
            }
            if (expectedShape != null && record.getShape() != expectedShape) {
                shapeMismatchProfile.enter(this);
                CompilerDirectives.transferToInterpreterAndInvalidate();
                expectedShape = null;
                shapeStableAssumption = null;
            }
        }
    }

    /**
     * Factory method to create the optimal static field access node.
     *
     * @param recordNode The node that evaluates to the record
     * @param storageType 0=Long, 1=Double, 2=Object
     * @param locationIndex Index into the typed storage array
     */
    public static MezzeNode create(MezzeNode recordNode, int storageType, int locationIndex) {
        return switch (storageType) {
            case 0 -> new LongField(recordNode, locationIndex);
            case 1 -> new DoubleField(recordNode, locationIndex);
            default -> new ObjectField(recordNode, locationIndex);
        };
    }

    /**
     * Factory method with shape guard for maximum JIT optimization.
     *
     * @param recordNode The node that evaluates to the record
     * @param storageType 0=Long, 1=Double, 2=Object
     * @param locationIndex Index into the typed storage array
     * @param expectedShape The expected shape for identity guard
     */
    public static MezzeNode createWithShape(MezzeNode recordNode, int storageType,
                                             int locationIndex, Shape expectedShape) {
        return switch (storageType) {
            case 0 -> new LongField(recordNode, locationIndex, expectedShape);
            case 1 -> new DoubleField(recordNode, locationIndex, expectedShape);
            default -> new ObjectField(recordNode, locationIndex, expectedShape);
        };
    }

    /**
     * Create for a known type name (convenience method).
     *
     * Type to storage mapping:
     * - Int, Nat → LongField (long[] - unboxed integers)
     * - Float, Frac → DoubleField (double[] - unboxed floats)
     * - Bool → BooleanField (Object[] with executeBoolean support)
     * - String, Char, Bytes, ByteBuffer, Ordering, records, functions, variants, etc. → ObjectField
     */
    public static MezzeNode createForType(MezzeNode recordNode, String typeName, int locationIndex) {
        return switch (typeName) {
            // Integer types → long storage
            case "Int", "Nat" -> new LongField(recordNode, locationIndex);
            // Floating point types → double storage
            case "Float", "Frac" -> new DoubleField(recordNode, locationIndex);
            // Boolean → object storage with executeBoolean
            case "Bool" -> new BooleanField(recordNode, locationIndex);
            // All other types → object storage
            // (String, Char, Bytes, ByteBuffer, Ordering, records, functions, variants, etc.)
            default -> new ObjectField(recordNode, locationIndex);
        };
    }

    /**
     * Create for a known type name with shape guard.
     *
     * Type to storage mapping:
     * - Int, Nat → LongField (long[] - unboxed integers)
     * - Float, Frac → DoubleField (double[] - unboxed floats)
     * - Bool → BooleanField (Object[] with executeBoolean support)
     * - String, Char, Bytes, ByteBuffer, Ordering, records, functions, variants, etc. → ObjectField
     */
    public static MezzeNode createForTypeWithShape(MezzeNode recordNode, String typeName,
                                                    int locationIndex, Shape expectedShape) {
        return switch (typeName) {
            // Integer types → long storage
            case "Int", "Nat" -> new LongField(recordNode, locationIndex, expectedShape);
            // Floating point types → double storage
            case "Float", "Frac" -> new DoubleField(recordNode, locationIndex, expectedShape);
            // Boolean → object storage with executeBoolean
            case "Bool" -> new BooleanField(recordNode, locationIndex, expectedShape);
            // All other types → object storage
            // (String, Char, Bytes, ByteBuffer, Ordering, records, functions, variants, etc.)
            default -> new ObjectField(recordNode, locationIndex, expectedShape);
        };
    }
}
