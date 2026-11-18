package com.mezze.interpreter.nodes.continuation;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.nodes.MezzeRootNode;
import com.mezze.interpreter.nodes.builtin.EffectHandlerDriver;
import com.mezze.interpreter.runtime.MezzeLanguage;
import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.language.ast.ScopedEffectConstants;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;

/**
 * The value of the runtime-bound name {@code cont} inside a performer op body:
 * a record {@code { park, abort }}. One per language instance (a root node
 * belongs to one engine's sharing layer — see {@code MezzeLanguage.fallThroughResumeFn}).
 *
 * <p>{@code park} does not block and does not capture anything itself: it returns
 * a {@link ParkRequest}, and the lowering's suspension checks carry it out to the
 * perform boundary (see that class). {@code abort} is the ordinary abort.
 */
public final class ContBinding {
    private ContBinding() {}

    public static Object contRecord() {
        MezzeLanguage language = MezzeLanguage.get(null);
        Object cached = language.contRecord;
        if (cached != null) return cached;
        return build(language);
    }

    @TruffleBoundary
    private static synchronized Object build(MezzeLanguage language) {
        if (language.contRecord != null) return language.contRecord;
        FrameDescriptor fd = FrameDescriptor.newBuilder().build();
        MezzeNode body = new MezzeNode() {
            private Object lastShape; private int onIdx, setIdx;
            @Override
            public Object execute(VirtualFrame frame) {
                Object[] args = frame.getArguments();
                Object last = args.length == 0 ? null : args[args.length - 1];
                if (!(last instanceof MezzeRecord rec)) {
                    throw new RuntimeException("cont.park expects a record argument { on, set }");
                }
                com.mezze.interpreter.runtime.MezzeContext.get(null).taskMachine().notePark();
                // Field indices cached per shape (shapes are interned): no string lookup per park.
                Object shape = rec.getShape();
                int on = onIdx, set = setIdx;
                if (shape != lastShape) {
                    on = rec.getFieldIndex("on"); set = rec.getFieldIndex("set");
                    onIdx = on; setIdx = set; lastShape = shape;
                }
                return new ParkRequest(rec.getField(on), rec.getField(set));
            }
        };
        RootCallTarget target = new MezzeRootNode(language, fd, body, "cont-park", 0, 1, new int[0]).getCallTarget();
        MezzeFunction park = new MezzeFunction(target, ScopedEffectConstants.CONT_PARK, new Object[0]);
        MezzeRecord rec = MezzeRecord.fromFields(
            new String[]{ScopedEffectConstants.CONT_PARK, ScopedEffectConstants.CONT_ABORT},
            new Object[]{park, EffectHandlerDriver.makeAbortFunction()}
        );
        language.contRecord = rec;
        return rec;
    }
}
