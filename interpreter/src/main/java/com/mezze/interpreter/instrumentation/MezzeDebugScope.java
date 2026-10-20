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
    /** #356 — non-null for BDSL frames; used to read/write via BytecodeNode's API instead of FrameDescriptor slots. */
    private final com.oracle.truffle.api.bytecode.BytecodeNode bytecodeNode;
    private final int bci;
    /** For BDSL frames, memberSlots[i] is the localOffset in the bytecodeNode; for AST frames, it's the FrameDescriptor slot index. */
    private final boolean bdsl;

    public MezzeDebugScope(Frame frame) {
        this(frame, null, -1);
    }

    /**
     * #356 — BDSL scope constructor. When {@code bytecodeNode != null},
     * local names / values are read via {@code bytecodeNode.getLocalName(bci, offset)}
     * and {@code bytecodeNode.getLocalValue(bci, frame, offset)} rather
     * than the FrameDescriptor slots (BDSL puts human names in the
     * bytecode's own local table, not the frame descriptor).
     */
    public MezzeDebugScope(Frame frame,
                           com.oracle.truffle.api.bytecode.BytecodeNode bytecodeNode,
                           int bci) {
        this.frame = frame;
        this.bytecodeNode = bytecodeNode;
        this.bci = bci;
        this.bdsl = bytecodeNode != null && bci >= 0;
        List<String> names = new ArrayList<>();
        List<Integer> slots = new ArrayList<>();
        if (bdsl) {
            int count = bytecodeNode.getLocalCount(bci);
            for (int i = 0; i < count; i++) {
                Object nm = bytecodeNode.getLocalName(bci, i);
                if (nm instanceof String s && !s.isEmpty()) {
                    names.add(s);
                    slots.add(i);
                }
            }
        } else {
            FrameDescriptor desc = frame.getFrameDescriptor();
            for (int i = 0; i < desc.getNumberOfSlots(); i++) {
                Object info = desc.getSlotName(i);
                if (info instanceof String s && !s.isEmpty()) {
                    names.add(s);
                    slots.add(i);
                }
            }
        }
        this.memberNames = names.toArray(new String[0]);
        // A plain loop, not `slots.stream().mapToInt(...).toArray()`. This
        // constructor is reachable from partially-evaluated code (NodeLibrary
        // .getScope), and the stream made every lambda in the image reachable for
        // runtime compilation: the native `mez`, which carries the interpreter,
        // failed to build with 78 "blocklisted methods" reports through this line.
        int[] memberSlots = new int[slots.size()];
        for (int i = 0; i < memberSlots.length; i++) memberSlots[i] = slots.get(i);
        this.memberSlots = memberSlots;
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

    // ── Writing (the debugger's setVariable) ──
    // Without isMemberModifiable/writeMember, DebugValue.isWritable() is false
    // and setVariable can't change a local mid-debug.

    @ExportMessage
    boolean isMemberModifiable(String member) {
        for (String name : memberNames) {
            if (name.equals(member)) return true;
        }
        return false;
    }

    @ExportMessage
    boolean isMemberInsertable(@SuppressWarnings("unused") String member) {
        return false; // scopes have a fixed slot set; no new members
    }

    @ExportMessage
    void writeMember(String member, Object value)
            throws UnknownIdentifierException, com.oracle.truffle.api.interop.UnsupportedTypeException {
        for (int i = 0; i < memberNames.length; i++) {
            if (memberNames[i].equals(member)) {
                writeSlot(memberSlots[i], value);
                return;
            }
        }
        throw UnknownIdentifierException.create(member);
    }

    // Behind a boundary: a debugger's write is never hot, and `Number.longValue()`
    // on an unknown Number is a megamorphic call the native `mez` refuses to
    // runtime-compile (reachable through NodeLibrary from partially-evaluated code).
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private void writeSlot(int slot, Object value) {
        if (bdsl) {
            bytecodeNode.setLocalValue(bci, frame, slot, value);
            return;
        }
        FrameSlotKind kind = frame.getFrameDescriptor().getSlotKind(slot);
        switch (kind) {
            case Long:    frame.setLong(slot, ((Number) value).longValue()); break;
            case Double:  frame.setDouble(slot, ((Number) value).doubleValue()); break;
            case Boolean: frame.setBoolean(slot, (Boolean) value); break;
            default:      frame.setObject(slot, value); break;
        }
    }

    private Object readSlot(int slot) {
        if (bdsl) {
            try {
                Object v = bytecodeNode.getLocalValue(bci, frame, slot);
                return v == null ? "<uninitialized>" : v;
            } catch (Throwable t) {
                return "<unreadable>";
            }
        }
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

    // GraalVM's DebugScope.getDeclaredValues() only enumerates members when the
    // interop object identifies itself as a SCOPE. Without isScope()==true the
    // debugger sees an object with members but not a scope, so the variables
    // panel comes back empty even though getMembers() is populated. This was
    // the real cause of the "empty locals" — NOT unnamed slots (slots are named
    // by TruffleCompiler.buildFrameDescriptor).
    @ExportMessage
    boolean isScope() { return true; }

    // A scope must also be a member-bearing object per the interop scope
    // contract; hasMembers() above already returns true.

    @ExportMessage
    boolean hasScopeParent() { return false; }

    @ExportMessage
    Object getScopeParent() throws com.oracle.truffle.api.interop.UnsupportedMessageException {
        throw com.oracle.truffle.api.interop.UnsupportedMessageException.create();
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
