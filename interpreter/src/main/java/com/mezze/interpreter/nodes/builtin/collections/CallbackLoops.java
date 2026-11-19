package com.mezze.interpreter.nodes.builtin.collections;


import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeRecord;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.nodes.IndirectCallNode;

/** The callback loops the list and seq natives share: `call` an element, `accept` the result
 *  (CONTINUE or STOP), `finish`. A callback that parks blocks its carrier; the loop never sees it. */
public final class CallbackLoops {
    private CallbackLoops() {}

    public static final Object CONTINUE = new Object(), STOP = new Object();

    public interface Body {
        /** Invoke the callback for element {@code i}; may return a suspension. */
        Object call(int i);
        /** Consume the callback's result for element {@code i}: CONTINUE or STOP. */
        Object accept(int i, Object r);
        /** The native's result once the loop is over (or stopped). */
        Object finish();
    }

    public static Object run(int start, int n, Body b) {
        for (int i = start; i < n; i++) {
            Object r = b.call(i);
            if (b.accept(i, r) == STOP) return b.finish();
        }
        return b.finish();
    }

    /** A two-argument call (folder/reducer) honouring the scalar ABI's slot order. */
    public static final class Call2 {
        private final CallTarget target; private final Object[] args; private final int accSlot, elemSlot; private final boolean scalar;
        private final IndirectCallNode callNode;
        public Call2(MezzeFunction fn, IndirectCallNode callNode) {
            this.callNode = callNode;
            Object[] closure = fn.getClosureValues(); int len = closure.length;
            CallTarget inner = ScalarBypass.tryResolve(fn);
            scalar = inner != null;
            target = scalar ? inner : fn.getCallTarget();
            if (scalar) {
                String[] params = fn.getParameterNames();
                boolean accFirst = params.length < 2 || "acc".equals(params[0]);
                accSlot = accFirst ? len : len + 1; elemSlot = accFirst ? len + 1 : len;
                args = new Object[len + 2];
            } else { accSlot = len; elemSlot = -1; args = new Object[len + 1]; }
            System.arraycopy(closure, 0, args, 0, len);
        }
        public Object on(Object acc, Object x) {
            if (scalar) { args[accSlot] = acc; args[elemSlot] = x; }
            else args[accSlot] = MezzeRecord.wrapAccX(acc, x);
            return callNode.call(target, args);
        }
    }
}
