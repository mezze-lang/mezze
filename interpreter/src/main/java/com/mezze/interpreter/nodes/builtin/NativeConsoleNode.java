package com.mezze.interpreter.nodes.builtin;

import com.mezze.interpreter.runtime.NativeTypeMismatchException;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.values.MezzeRecord;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Native console print node - FULLY JIT-COMPILABLE.
 *
 * Only __native_console_print is needed - formatting is done in Mezze code.
 *
 * Key optimizations:
 * 1. Shape-cached field access for O(1) msg extraction
 * 2. @TruffleBoundary only on the actual I/O operation (unavoidable)
 * 3. Pre-computed field index after first call
 * 4. Returns MezzeRecord.empty() (singleton, no allocation)
 */
@NodeInfo(shortName = "native-console-print")
public final class NativeConsoleNode extends MezzeNode {

    @Child
    private MezzeNode argumentNode;

    // Cached field index for "msg" field
    @CompilationFinal
    private int cachedMsgFieldIndex = -2;

    @CompilationFinal
    private Object cachedShape;

    private NativeConsoleNode(MezzeNode argumentNode) {
        this.argumentNode = argumentNode;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object arg = argumentNode.execute(frame);

        if (!(arg instanceof MezzeRecord record)) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new NativeTypeMismatchException("__native_console_print expects record argument");
        }

        String msg = extractMessage(record);
        doPrint(msg);

        return MezzeRecord.empty();
    }

    private String extractMessage(MezzeRecord record) {
        Object shape = record.getShape();

        if (shape == cachedShape && cachedMsgFieldIndex >= 0) {
            Object msgValue = record.getField(cachedMsgFieldIndex);
            if (msgValue instanceof String s) {
                return s;
            }
        }

        CompilerDirectives.transferToInterpreterAndInvalidate();
        return extractMessageSlow(record, shape);
    }

    @TruffleBoundary
    private String extractMessageSlow(MezzeRecord record, Object shape) {
        int fieldIndex = record.getFieldIndex("msg");
        cachedMsgFieldIndex = fieldIndex;
        cachedShape = shape;

        if (fieldIndex < 0) {
            throw new NativeTypeMismatchException("__native_console_print expects 'msg' field");
        }

        Object msgValue = record.getField(fieldIndex);
        if (msgValue instanceof String s) {
            return s;
        }

        throw new NativeTypeMismatchException("__native_console_print expects 'msg' to be String, got: " +
            (msgValue == null ? "null" : msgValue.getClass().getName()));
    }

    @TruffleBoundary
    private static void doPrint(String msg) {
        System.out.print(msg);
    }

    public static NativeConsoleNode create(MezzeNode argNode) {
        return new NativeConsoleNode(argNode);
    }
}
