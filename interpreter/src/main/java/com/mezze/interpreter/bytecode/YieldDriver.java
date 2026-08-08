package com.mezze.interpreter.bytecode;

import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.language.typedast.Hash;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.bytecode.ContinuationResult;

/**
 * Path D driver: walks a chain of {@link ContinuationResult}s produced
 * by a BDSL root whose {@code PerformOpIR} sites were lowered to
 * {@code beginYield / MakeRecord{effect_hash, op_name, arg} / endYield}
 * (see {@link BytecodeCompiler}).
 *
 * <p>The framework's {@link ContinuationResult#getResult()} returns
 * whatever the yield operand evaluated to — for us, that's the
 * three-field record. We hand (effect_hash, op_name, arg) to an
 * {@link OpDispatcher}, then feed the answer back via
 * {@link ContinuationResult#continueWith}. The dispatcher can invoke
 * the handler however it likes (calling back into Mezze code is fine —
 * it can even yield again; we just keep looping).
 *
 * <p>Zero fibers, zero Loom, zero reflection. Runs on the calling
 * thread. Compare {@link com.mezze.interpreter.nodes.continuation.HandlerChainRegistry}
 * for the Loom-shaped equivalent.
 */
public final class YieldDriver {

    private YieldDriver() {}

    /**
     * Handler-chain lookup callback. Given the (effect_hash, op_name,
     * arg) triple, produce the value the perform expression should
     * evaluate to. Implementations locate the innermost handler for
     * {@code effectHash}, invoke its op impl with {@code arg}, and
     * return whatever the impl returned via resume.
     *
     * <p>Throwing from here escapes the driver — used by handlers that
     * abort the perform.
     */
    @FunctionalInterface
    public interface OpDispatcher {
        Object dispatch(Hash effectHash, String opName, Object arg) throws Exception;
    }

    /**
     * Drive an initial call. If {@code initialResult} is not a
     * {@link ContinuationResult} (fn ran to completion without a
     * perform), returns it verbatim. Otherwise walks the yield chain,
     * dispatching each yielded op and feeding the answer back.
     *
     * <p>Rethrows dispatcher exceptions as {@link RuntimeException}
     * for callers that want to catch aborts as regular exceptions.
     */
    @TruffleBoundary
    public static Object drive(Object initialResult, OpDispatcher dispatcher) {
        Object cur = initialResult;
        while (cur instanceof ContinuationResult cr) {
            Object yielded = cr.getResult();
            if (!(yielded instanceof MezzeRecord opRecord)) {
                throw new IllegalStateException(
                    "Path D driver: yield operand was not a MezzeRecord — got "
                    + (yielded == null ? "null" : yielded.getClass().getName()));
            }
            Object rawHash = opRecord.getField("effect_hash");
            Object rawName = opRecord.getField("op_name");
            Object arg = opRecord.getField("arg");
            if (!(rawHash instanceof Hash effectHash) || !(rawName instanceof String opName)) {
                throw new IllegalStateException(
                    "Path D driver: malformed yield record — effect_hash/op_name missing or wrong type");
            }
            Object answer;
            try {
                answer = dispatcher.dispatch(effectHash, opName, arg);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            cur = cr.continueWith(answer);
        }
        return cur;
    }
}
