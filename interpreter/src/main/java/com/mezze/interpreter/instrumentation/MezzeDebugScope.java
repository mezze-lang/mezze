package com.mezze.interpreter.instrumentation;

import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Debugger-visible scope for a Mezze frame.
 *
 * Exposed via {@link com.oracle.truffle.api.interop.NodeLibrary#getScope}
 * on root nodes. The Chrome DevTools / VS Code debugger calls into this
 * when paused to enumerate visible local bindings.
 *
 * Surface model: every named slot in the FrameDescriptor becomes a
 * scope member. The slot's current value (cast to Object) is the
 * member value. Unnamed / synthetic slots (compiler-generated temporaries)
 * are skipped — users don't want to see them.
 *
 * This is a snapshot view: the scope object captures slot indices +
 * names at construction, then reads from the live frame at message
 * time. Sufficient for stop-and-inspect; not transactional with respect
 * to concurrent execution (which doesn't apply since the interpreter
 * is paused at a breakpoint anyway).
 */
@ExportLibrary(InteropLibrary.class)
public final class MezzeDebugScope implements TruffleObject {

    private final Frame frame;
    private final String[] memberNames;
    private final int[] memberSlots;

    public MezzeDebugScope(Frame frame) {
        this.frame = frame;
        FrameDescriptor desc = frame.getFrameDescriptor();
        // Filter to slots with a real string name; skip "" / null
        // (those are compiler-temporaries that would clutter the panel).
        List<String> names = new ArrayList<>();
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < desc.getNumberOfSlots(); i++) {
            Object info = desc.getSlotName(i);
            if (info instanceof String s && !s.isEmpty()) {
                names.add(s);
                slots.add(i);
            }
        }
        this.memberNames = names.toArray(new String[0]);
        this.memberSlots = slots.stream().mapToInt(Integer::intValue).toArray();
    }

    @ExportMessage
    boolean hasMembers() {
        return memberNames.length > 0;
    }

    @ExportMessage
    Object getMembers(boolean includeInternal) {
        return new MembersArray(memberNames);
    }

    @ExportMessage
    boolean isMemberReadable(String member) {
        for (String name : memberNames) {
            if (name.equals(member)) return true;
        }
        return false;
    }

    @ExportMessage
    Object readMember(String member) throws UnknownIdentifierException {
        for (int i = 0; i < memberNames.length; i++) {
            if (memberNames[i].equals(member)) {
                return readSlot(memberSlots[i]);
            }
        }
        throw UnknownIdentifierException.create(member);
    }

    private Object readSlot(int slot) {
        FrameDescriptor desc = frame.getFrameDescriptor();
        FrameSlotKind kind = desc.getSlotKind(slot);
        try {
            switch (kind) {
                case Long:    return frame.getLong(slot);
                case Double:  return frame.getDouble(slot);
                case Boolean: return frame.getBoolean(slot);
                case Object:  return frame.getObject(slot);
                case Illegal: return "<uninitialized>";
                default:      return frame.getValue(slot);
            }
        } catch (Throwable t) {
            // The frame slot might be uninitialized at the breakpoint
            // location (declared but not yet assigned). Don't blow up
            // the whole scope panel for one stale slot.
            return "<unreadable>";
        }
    }

    @ExportMessage
    boolean hasLanguage() { return true; }

    @ExportMessage
    Class<? extends com.oracle.truffle.api.TruffleLanguage<?>> getLanguage() {
        return com.mezze.interpreter.runtime.MezzeLanguage.class;
    }

    @ExportMessage
    Object toDisplayString(boolean allowSideEffects) {
        return "Mezze scope";
    }

    /** Members enumeration as an interop array. */
    @ExportLibrary(InteropLibrary.class)
    static final class MembersArray implements TruffleObject {
        private final String[] names;
        MembersArray(String[] names) { this.names = names; }

        @ExportMessage boolean hasArrayElements() { return true; }
        @ExportMessage long getArraySize() { return names.length; }
        @ExportMessage boolean isArrayElementReadable(long idx) { return idx >= 0 && idx < names.length; }
        @ExportMessage
        Object readArrayElement(long idx) throws InvalidArrayIndexException {
            if (idx < 0 || idx >= names.length) throw InvalidArrayIndexException.create(idx);
            return names[(int) idx];
        }
    }
}
