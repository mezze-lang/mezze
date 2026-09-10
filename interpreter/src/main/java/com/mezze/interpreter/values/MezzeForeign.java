package com.mezze.interpreter.values;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.StopIterationException;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.interop.ArityException;

import java.math.BigInteger;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

/**
 * Wrapper for a guest-language polyglot value, exposed to Mezze as an
 * opaque `Foreign` newtype.
 *
 * MezzeForeign implements InteropLibrary by delegating every message to
 * the wrapped {@code delegate} object.  Two design points:
 *
 * <ol>
 *   <li>The delegate may be any of: a guest-language {@code TruffleObject}
 *       (e.g., an ArrayList proxied from GraalPy), a Java host object
 *       exposed via {@code Env.asGuestValue}, a boxed primitive
 *       ({@code Long}, {@code Double}, {@code String}, {@code Boolean}),
 *       or {@link MezzeNull#INSTANCE} for a guest-language null.
 *       {@link InteropLibrary} already knows how to treat boxed Java
 *       primitives, so delegation works uniformly.</li>
 *
 *   <li>The wrapper itself is a plain {@code TruffleObject}. Guest
 *       languages that receive a MezzeForeign back see whatever the
 *       delegate is — arrays present as arrays, executables execute,
 *       members are readable/writable. This is what makes it safe to
 *       hand a MezzeForeign around across language boundaries.</li>
 * </ol>
 *
 * The {@code sourceLanguage} tag ("jvm", "python", "js", ...) is retained
 * only for introspection/debugging — dispatch doesn't key on it.
 */
@ExportLibrary(InteropLibrary.class)
public final class MezzeForeign implements TruffleObject {

    // Package-private so the Truffle-DSL generated node classes for our
    // @ExportMessage methods can reference @CachedLibrary("this.delegate").
    final Object delegate;
    final String sourceLanguage;

    public MezzeForeign(Object delegate, String sourceLanguage) {
        // Never wrap a null delegate — replace with MezzeNull so InteropLibrary
        // delegation never NPEs on the receiver.
        this.delegate = delegate == null ? MezzeNull.INSTANCE : delegate;
        this.sourceLanguage = sourceLanguage == null ? "unknown" : sourceLanguage;
    }

    public Object getDelegate() {
        return delegate;
    }

    public String getSourceLanguage() {
        return sourceLanguage;
    }

    /**
     * Convenience: wrap a value if it isn't already a MezzeForeign.
     * Idempotent — nested MezzeForeign layers collapse.
     */
    public static MezzeForeign wrap(Object value, String sourceLanguage) {
        if (value instanceof MezzeForeign f) return f;
        return new MezzeForeign(value, sourceLanguage);
    }

    // ==================== Null ====================
    @ExportMessage
    boolean isNull(@CachedLibrary("this.delegate") InteropLibrary lib) {
        return lib.isNull(delegate);
    }

    // ==================== Boolean ====================
    @ExportMessage
    boolean isBoolean(@CachedLibrary("this.delegate") InteropLibrary lib) {
        return lib.isBoolean(delegate);
    }

    @ExportMessage
    boolean asBoolean(@CachedLibrary("this.delegate") InteropLibrary lib)
            throws UnsupportedMessageException {
        return lib.asBoolean(delegate);
    }

    // ==================== String ====================
    @ExportMessage
    boolean isString(@CachedLibrary("this.delegate") InteropLibrary lib) {
        return lib.isString(delegate);
    }

    @ExportMessage
    String asString(@CachedLibrary("this.delegate") InteropLibrary lib)
            throws UnsupportedMessageException {
        return lib.asString(delegate);
    }

    // ==================== Number ====================
    @ExportMessage
    boolean isNumber(@CachedLibrary("this.delegate") InteropLibrary lib) {
        return lib.isNumber(delegate);
    }

    @ExportMessage
    boolean fitsInByte(@CachedLibrary("this.delegate") InteropLibrary lib) {
        return lib.fitsInByte(delegate);
    }

    @ExportMessage
    boolean fitsInShort(@CachedLibrary("this.delegate") InteropLibrary lib) {
        return lib.fitsInShort(delegate);
    }

    @ExportMessage
    boolean fitsInInt(@CachedLibrary("this.delegate") InteropLibrary lib) {
        return lib.fitsInInt(delegate);
    }

    @ExportMessage
    boolean fitsInLong(@CachedLibrary("this.delegate") InteropLibrary lib) {
        return lib.fitsInLong(delegate);
    }

    @ExportMessage
    boolean fitsInFloat(@CachedLibrary("this.delegate") InteropLibrary lib) {
        return lib.fitsInFloat(delegate);
    }

    @ExportMessage
    boolean fitsInDouble(@CachedLibrary("this.delegate") InteropLibrary lib) {
        return lib.fitsInDouble(delegate);
    }

    @ExportMessage
    boolean fitsInBigInteger(@CachedLibrary("this.delegate") InteropLibrary lib) {
        return lib.fitsInBigInteger(delegate);
    }

    @ExportMessage
    BigInteger asBigInteger(@CachedLibrary("this.delegate") InteropLibrary lib)
            throws UnsupportedMessageException {
        return lib.asBigInteger(delegate);
    }

    @ExportMessage
    byte asByte(@CachedLibrary("this.delegate") InteropLibrary lib)
            throws UnsupportedMessageException {
        return lib.asByte(delegate);
    }

    @ExportMessage
    short asShort(@CachedLibrary("this.delegate") InteropLibrary lib)
            throws UnsupportedMessageException {
        return lib.asShort(delegate);
    }

    @ExportMessage
    int asInt(@CachedLibrary("this.delegate") InteropLibrary lib)
            throws UnsupportedMessageException {
        return lib.asInt(delegate);
    }

    @ExportMessage
    long asLong(@CachedLibrary("this.delegate") InteropLibrary lib)
            throws UnsupportedMessageException {
        return lib.asLong(delegate);
    }

    @ExportMessage
    float asFloat(@CachedLibrary("this.delegate") InteropLibrary lib)
            throws UnsupportedMessageException {
        return lib.asFloat(delegate);
    }

    @ExportMessage
    double asDouble(@CachedLibrary("this.delegate") InteropLibrary lib)
            throws UnsupportedMessageException {
        return lib.asDouble(delegate);
    }

    // ==================== Members ====================
    @ExportMessage
    boolean hasMembers(@CachedLibrary("this.delegate") InteropLibrary lib) {
        return lib.hasMembers(delegate);
    }

    @ExportMessage
    Object getMembers(boolean includeInternal,
                      @CachedLibrary("this.delegate") InteropLibrary lib)
            throws UnsupportedMessageException {
        return lib.getMembers(delegate, includeInternal);
    }

    @ExportMessage
    boolean isMemberReadable(String member,
                             @CachedLibrary("this.delegate") InteropLibrary lib) {
        return lib.isMemberReadable(delegate, member);
    }

    @ExportMessage
    boolean isMemberModifiable(String member,
                               @CachedLibrary("this.delegate") InteropLibrary lib) {
        return lib.isMemberModifiable(delegate, member);
    }

    @ExportMessage
    boolean isMemberInsertable(String member,
                               @CachedLibrary("this.delegate") InteropLibrary lib) {
        return lib.isMemberInsertable(delegate, member);
    }

    @ExportMessage
    boolean isMemberInvocable(String member,
                              @CachedLibrary("this.delegate") InteropLibrary lib) {
        return lib.isMemberInvocable(delegate, member);
    }

    @ExportMessage
    boolean isMemberRemovable(String member,
                              @CachedLibrary("this.delegate") InteropLibrary lib) {
        return lib.isMemberRemovable(delegate, member);
    }

    @ExportMessage
    Object readMember(String member,
                      @CachedLibrary("this.delegate") InteropLibrary lib)
            throws UnknownIdentifierException, UnsupportedMessageException {
        return lib.readMember(delegate, member);
    }

    @ExportMessage
    void writeMember(String member, Object value,
                     @CachedLibrary("this.delegate") InteropLibrary lib)
            throws UnknownIdentifierException, UnsupportedMessageException,
                   UnsupportedTypeException {
        lib.writeMember(delegate, member, unwrap(value));
    }

    @ExportMessage
    void removeMember(String member,
                      @CachedLibrary("this.delegate") InteropLibrary lib)
            throws UnknownIdentifierException, UnsupportedMessageException {
        lib.removeMember(delegate, member);
    }

    @ExportMessage
    Object invokeMember(String member, Object[] arguments,
                        @CachedLibrary("this.delegate") InteropLibrary lib)
            throws UnknownIdentifierException, UnsupportedMessageException,
                   UnsupportedTypeException, ArityException {
        return lib.invokeMember(delegate, member, unwrapAll(arguments));
    }

    // ==================== Array elements ====================
    @ExportMessage
    boolean hasArrayElements(@CachedLibrary("this.delegate") InteropLibrary lib) {
        return lib.hasArrayElements(delegate);
    }

    @ExportMessage
    long getArraySize(@CachedLibrary("this.delegate") InteropLibrary lib)
            throws UnsupportedMessageException {
        return lib.getArraySize(delegate);
    }

    @ExportMessage
    boolean isArrayElementReadable(long index,
                                   @CachedLibrary("this.delegate") InteropLibrary lib) {
        return lib.isArrayElementReadable(delegate, index);
    }

    @ExportMessage
    boolean isArrayElementModifiable(long index,
                                     @CachedLibrary("this.delegate") InteropLibrary lib) {
        return lib.isArrayElementModifiable(delegate, index);
    }

    @ExportMessage
    boolean isArrayElementInsertable(long index,
                                     @CachedLibrary("this.delegate") InteropLibrary lib) {
        return lib.isArrayElementInsertable(delegate, index);
    }

    @ExportMessage
    boolean isArrayElementRemovable(long index,
                                    @CachedLibrary("this.delegate") InteropLibrary lib) {
        return lib.isArrayElementRemovable(delegate, index);
    }

    @ExportMessage
    Object readArrayElement(long index,
                            @CachedLibrary("this.delegate") InteropLibrary lib)
            throws InvalidArrayIndexException, UnsupportedMessageException {
        return lib.readArrayElement(delegate, index);
    }

    @ExportMessage
    void writeArrayElement(long index, Object value,
                           @CachedLibrary("this.delegate") InteropLibrary lib)
            throws InvalidArrayIndexException, UnsupportedMessageException,
                   UnsupportedTypeException {
        lib.writeArrayElement(delegate, index, unwrap(value));
    }

    @ExportMessage
    void removeArrayElement(long index,
                            @CachedLibrary("this.delegate") InteropLibrary lib)
            throws InvalidArrayIndexException, UnsupportedMessageException {
        lib.removeArrayElement(delegate, index);
    }

    // ==================== Execute ====================
    @ExportMessage
    boolean isExecutable(@CachedLibrary("this.delegate") InteropLibrary lib) {
        return lib.isExecutable(delegate);
    }

    @ExportMessage
    Object execute(Object[] arguments,
                   @CachedLibrary("this.delegate") InteropLibrary lib)
            throws UnsupportedMessageException, UnsupportedTypeException,
                   ArityException {
        return lib.execute(delegate, unwrapAll(arguments));
    }

    // ==================== Instantiate ====================
    @ExportMessage
    boolean isInstantiable(@CachedLibrary("this.delegate") InteropLibrary lib) {
        return lib.isInstantiable(delegate);
    }

    @ExportMessage
    Object instantiate(Object[] arguments,
                       @CachedLibrary("this.delegate") InteropLibrary lib)
            throws UnsupportedMessageException, UnsupportedTypeException,
                   ArityException {
        return lib.instantiate(delegate, unwrapAll(arguments));
    }

    // ==================== Iterator ====================
    @ExportMessage
    boolean hasIterator(@CachedLibrary("this.delegate") InteropLibrary lib) {
        return lib.hasIterator(delegate);
    }

    @ExportMessage
    Object getIterator(@CachedLibrary("this.delegate") InteropLibrary lib)
            throws UnsupportedMessageException {
        return lib.getIterator(delegate);
    }

    @ExportMessage
    boolean isIterator(@CachedLibrary("this.delegate") InteropLibrary lib) {
        return lib.isIterator(delegate);
    }

    @ExportMessage
    boolean hasIteratorNextElement(@CachedLibrary("this.delegate") InteropLibrary lib)
            throws UnsupportedMessageException {
        return lib.hasIteratorNextElement(delegate);
    }

    @ExportMessage
    Object getIteratorNextElement(@CachedLibrary("this.delegate") InteropLibrary lib)
            throws UnsupportedMessageException, StopIterationException {
        return lib.getIteratorNextElement(delegate);
    }

    // ==================== Meta ====================
    @ExportMessage
    boolean hasMetaObject(@CachedLibrary("this.delegate") InteropLibrary lib) {
        return lib.hasMetaObject(delegate);
    }

    @ExportMessage
    Object getMetaObject(@CachedLibrary("this.delegate") InteropLibrary lib)
            throws UnsupportedMessageException {
        return lib.getMetaObject(delegate);
    }

    @ExportMessage
    Object toDisplayString(boolean allowSideEffects,
                           @CachedLibrary("this.delegate") InteropLibrary lib) {
        return lib.toDisplayString(delegate, allowSideEffects);
    }

    @ExportMessage
    boolean hasLanguage(@CachedLibrary("this.delegate") InteropLibrary lib) {
        return lib.hasLanguage(delegate);
    }

    @ExportMessage
    Class<? extends com.oracle.truffle.api.TruffleLanguage<?>> getLanguage(
            @CachedLibrary("this.delegate") InteropLibrary lib)
            throws UnsupportedMessageException {
        return lib.getLanguage(delegate);
    }

    // ==================== Helpers ====================

    /**
     * Unwrap a value when passing it TO the guest language. MezzeForeign
     * wrappers should pass their delegate; everything else passes through.
     */
    private static Object unwrap(Object value) {
        return value instanceof MezzeForeign f ? f.delegate : value;
    }

    private static Object[] unwrapAll(Object[] args) {
        if (args == null || args.length == 0) return args;
        Object[] out = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            out[i] = unwrap(args[i]);
        }
        return out;
    }

    @Override
    @TruffleBoundary
    public String toString() {
        return "Foreign<" + sourceLanguage + ">(" + delegate + ")";
    }
}
