package com.mezze.datastructures.nodes;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Optimized record field access nodes.
 *
 * Uses inline caching for fast field access.
 */
public final class RecordFieldNode {

    /**
     * Get object field by index.
     */
    @NodeInfo(shortName = "record.getObject")
    @GenerateUncached
    public static abstract class GetObjectFieldNode extends Node {
        public abstract Object execute(Object[] fields, int index);

        @Specialization
        protected Object get(Object[] fields, int index) {
            return fields[index];
        }

        public static GetObjectFieldNode create() {
            return RecordFieldNodeFactory.GetObjectFieldNodeGen.create();
        }

        public static GetObjectFieldNode getUncached() {
            return RecordFieldNodeFactory.GetObjectFieldNodeGen.getUncached();
        }
    }

    /**
     * Get long field by index.
     * Zero boxing.
     */
    @NodeInfo(shortName = "record.getLong")
    @GenerateUncached
    public static abstract class GetLongFieldNode extends Node {
        public abstract long execute(long[] fields, int index);

        @Specialization
        protected long get(long[] fields, int index) {
            return fields[index];
        }

        public static GetLongFieldNode create() {
            return RecordFieldNodeFactory.GetLongFieldNodeGen.create();
        }

        public static GetLongFieldNode getUncached() {
            return RecordFieldNodeFactory.GetLongFieldNodeGen.getUncached();
        }
    }

    /**
     * Get double field by index.
     * Zero boxing.
     */
    @NodeInfo(shortName = "record.getDouble")
    @GenerateUncached
    public static abstract class GetDoubleFieldNode extends Node {
        public abstract double execute(double[] fields, int index);

        @Specialization
        protected double get(double[] fields, int index) {
            return fields[index];
        }

        public static GetDoubleFieldNode create() {
            return RecordFieldNodeFactory.GetDoubleFieldNodeGen.create();
        }

        public static GetDoubleFieldNode getUncached() {
            return RecordFieldNodeFactory.GetDoubleFieldNodeGen.getUncached();
        }
    }

    /**
     * Set object field, returning new array.
     */
    @NodeInfo(shortName = "record.setObject")
    @GenerateUncached
    public static abstract class SetObjectFieldNode extends Node {
        public abstract Object[] execute(Object[] fields, int index, Object value);

        @Specialization
        protected Object[] set(Object[] fields, int index, Object value) {
            Object[] newFields = fields.clone();
            newFields[index] = value;
            return newFields;
        }

        public static SetObjectFieldNode create() {
            return RecordFieldNodeFactory.SetObjectFieldNodeGen.create();
        }

        public static SetObjectFieldNode getUncached() {
            return RecordFieldNodeFactory.SetObjectFieldNodeGen.getUncached();
        }
    }

    /**
     * Set long field, returning new array.
     */
    @NodeInfo(shortName = "record.setLong")
    @GenerateUncached
    public static abstract class SetLongFieldNode extends Node {
        public abstract long[] execute(long[] fields, int index, long value);

        @Specialization
        protected long[] set(long[] fields, int index, long value) {
            long[] newFields = fields.clone();
            newFields[index] = value;
            return newFields;
        }

        public static SetLongFieldNode create() {
            return RecordFieldNodeFactory.SetLongFieldNodeGen.create();
        }

        public static SetLongFieldNode getUncached() {
            return RecordFieldNodeFactory.SetLongFieldNodeGen.getUncached();
        }
    }

    /**
     * Set double field, returning new array.
     */
    @NodeInfo(shortName = "record.setDouble")
    @GenerateUncached
    public static abstract class SetDoubleFieldNode extends Node {
        public abstract double[] execute(double[] fields, int index, double value);

        @Specialization
        protected double[] set(double[] fields, int index, double value) {
            double[] newFields = fields.clone();
            newFields[index] = value;
            return newFields;
        }

        public static SetDoubleFieldNode create() {
            return RecordFieldNodeFactory.SetDoubleFieldNodeGen.create();
        }

        public static SetDoubleFieldNode getUncached() {
            return RecordFieldNodeFactory.SetDoubleFieldNodeGen.getUncached();
        }
    }

    private RecordFieldNode() {
        // Utility class
    }
}
