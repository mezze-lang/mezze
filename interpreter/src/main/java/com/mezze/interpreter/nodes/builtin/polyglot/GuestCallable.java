package com.mezze.interpreter.nodes.builtin.polyglot;

import com.mezze.interpreter.values.MezzeForeign;
import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.datastructures.seq.MezzeSeq;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

/**
 * A Mezze function handed to a guest language as a callable — the one mechanism
 * behind every provider's `from_fn` / `js_fn` / `py_fn`.
 *
 * <p>The function crosses BY REFERENCE: nothing is copied, the guest holds this
 * object and sends it the interop {@code execute} message. Its ARGUMENTS and
 * RESULT cross by value, per call: every positional argument the guest passes
 * arrives in Mezze as a foreign of {@code lang} (decoded by the callee with the
 * same `.int{}` / `.str{}` verbs as any other foreign — no guessing at a type the
 * runtime cannot see), and the Mezze result crosses back as an interop value.
 *
 * <p>Arity is the guest's contract: a fixed-arity callable rejects a call with
 * the wrong count with an {@link ArityException}, raised to the GUEST caller,
 * never into Mezze. A variadic callable ({@code arity < 0}) receives every
 * argument in one {@code args} list.
 *
 * <p>Threads and effects: the guest calls from whichever thread it likes — a
 * Java callback pool, the single JS thread, a Python thread under its lock. The
 * callee therefore runs OUTSIDE every `perform`, which is why the stdlib types
 * the function with an EMPTY effect row: it may do nothing that needs a handler.
 * Completing a promise is the model use and needs none.
 */
@ExportLibrary(InteropLibrary.class)
public final class GuestCallable implements TruffleObject {
    private static final String[] ARGS_FIELD = {"args"};
    private static final String[][] POSITIONAL = {{}, {"x"}, {"a", "b"}};

    final MezzeFunction fn;
    final String lang;
    /** Fixed argument count, or -1 for variadic. */
    final int arity;

    public GuestCallable(MezzeFunction fn, String lang, int arity) {
        this.fn = fn;
        this.lang = lang;
        this.arity = arity;
    }

    @ExportMessage
    boolean isExecutable() {
        return true;
    }

    @ExportMessage
    @TruffleBoundary
    Object execute(Object[] arguments) throws ArityException {
        if (arity >= 0 && arguments.length != arity) {
            throw ArityException.create(arity, arity, arguments.length);
        }
        Object[] wrapped = new Object[arguments.length];
        for (int i = 0; i < arguments.length; i++) {
            wrapped[i] = new MezzeForeign(arguments[i], lang);
        }
        MezzeRecord param;
        if (arity < 0) {
            param = MezzeRecord.fromFields(ARGS_FIELD, new Object[]{MezzeSeq.fromArray(wrapped)});
        } else if (arity == 0) {
            param = MezzeRecord.empty();
        } else {
            // The callee's OWN parameter names, so the record it destructures is the one
            // it declared; a function without recorded names gets the stdlib's spelling.
            String[] names = fn.getParameterNames();
            if (names == null || names.length != arity) names = POSITIONAL[arity];
            param = MezzeRecord.fromFields(names, wrapped);
        }
        Object[] closure = fn.getClosureValues();
        Object[] call = new Object[closure.length + 1];
        System.arraycopy(closure, 0, call, 0, closure.length);
        call[closure.length] = param;
        return fn.getCallTarget().call(call);
    }

    @Override
    public String toString() {
        return "mezze fn " + fn.getName() + (arity < 0 ? "(...)" : "/" + arity) + " for " + lang;
    }
}
