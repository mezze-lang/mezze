package com.mezze.polyglot.wasm;

import com.mezze.interpreter.nodes.builtin.io.BytesNatives;
import com.mezze.interpreter.nodes.builtin.polyglot.PolyglotNatives;
import com.mezze.interpreter.runtime.MezzeContext;
import com.mezze.interpreter.runtime.MezzePolyglotSupport;
import com.mezze.interpreter.values.MezzeForeign;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeVariant;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.nodes.Node;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * WebAssembly-specific natives — thin sugar over the ambient Truffle
 * Env's GraalWasm interpreter. Extracted from :interpreter into the
 * standalone `mezze-wasm` provider package (polyglot-as-a-package, Phase 6).
 *
 * Wasm's interop model is more constrained than Python or JavaScript:
 *
 *   - There is no source-level `eval` — a wasm module is compiled from
 *     a binary blob. The runtime accepts {@link MezzePolyglotSupport#evalBytes}
 *     which wraps the bytes in a `Source.newBuilder(id, ByteSequence, name)`
 *     and returns the top-level "module instance".
 *
 *   - Every export (function, memory, global, table) is reachable via
 *     an {@link InteropLibrary#readMember} on that module instance.
 *
 *   - Only numeric primitives cross the value boundary in either
 *     direction: i32/i64/f32/f64. Complex data lives in the module's
 *     linear memory and is passed as an integer offset, with the module
 *     usually exposing `alloc`/`dealloc`-like functions for buffer
 *     management.
 *
 * Because member reads / function invocations all go through the same
 * {@link com.oracle.truffle.api.interop.InteropLibrary} messages the
 * rest of the polyglot layer uses, we lean on `PolyglotNatives.readMember`,
 * `PolyglotNatives.execute`, and friends for the actual dispatch — this
 * file just handles the wasm-specific side of loading a module and
 * copying bytes to/from linear memory.
 */
public final class WasmNatives {

    private WasmNatives() {}

    // ==================== Loading ====================

    /**
     * Load a wasm module from a raw byte buffer. Returns
     * {@code Result WasmForeign InteropError} whose Ok payload wraps the
     * compiled module instance — every exported symbol is reachable as
     * a member of that instance.
     */
    @TruffleBoundary
    public static MezzeVariant loadModule(MezzeRecord args, Node currentNode) {
        BytesNatives.MezzeBytes bytes = getBytesArg(args, "bytes");
        String name = PolyglotNatives.getString(args, "name");
        return evalBytesInternal(bytes.getData(), name, currentNode);
    }

    /**
     * Load a wasm module from a filesystem path. Filesystem access is
     * gated by the Context's IO policy — the same policy that gates
     * {@code std::fs} reads. On a read failure or a wasm compilation
     * failure the returned Err wraps the underlying exception in a
     * {@code GuestError} variant.
     */
    @TruffleBoundary
    public static MezzeVariant loadFile(MezzeRecord args, Node currentNode) {
        String pathStr = PolyglotNatives.getString(args, "path");
        String name = PolyglotNatives.getString(args, "name");
        try {
            Path p = Paths.get(pathStr);
            byte[] data = Files.readAllBytes(p);
            return evalBytesInternal(data, name, currentNode);
        } catch (Throwable t) {
            return MezzeVariant.err(PolyglotNatives.translateException(t));
        }
    }

    /**
     * GraalWasm's ({@code context.eval("wasm", ...)}) evaluates the
     * binary into a compiled {@code wasm-module} value that is
     * <em>instantiable</em> but not yet a live instance — its exports
     * are only visible after calling
     * {@code InteropLibrary#instantiate}, which returns a
     * {@code wasm-module-instance} whose sole member is {@code exports}
     * (a namespace holding every exported function, memory, global, or
     * table).
     *
     * We do the compile+instantiate+exports lookup in one step so the
     * Mezze surface stays close to the JS / Python shape: {@code load_module}
     * returns something you can call {@code .get_export{}} on directly.
     * Wasm's import handling is opt-out via a lack of imports in the
     * module — modules with actual imports would need an alternate
     * loader that accepts an import object.
     */
    private static MezzeVariant evalBytesInternal(byte[] data, String name, Node currentNode) {
        try {
            MezzePolyglotSupport support =
                MezzeContext.get(currentNode).getPolyglotSupport();
            Object module = support.evalBytes("wasm", data, name);
            InteropLibrary lib = InteropLibrary.getUncached(module);
            Object instance = module;
            if (lib.isInstantiable(module)) {
                instance = lib.instantiate(module);
            }
            InteropLibrary instLib = InteropLibrary.getUncached(instance);
            Object exports = instance;
            if (instLib.isMemberReadable(instance, "exports")) {
                exports = instLib.readMember(instance, "exports");
            }
            return MezzeVariant.ok(new MezzeForeign(exports, "wasm"));
        } catch (Throwable t) {
            return MezzeVariant.err(PolyglotNatives.translateException(t));
        }
    }

    /**
     * Get an export from a wasm module instance by name. The returned
     * Foreign is the export itself — a function is executable, memory
     * has array elements, a global is a coercible number/reference.
     *
     * This is a convenience over {@code __native_polyglot_read_member}
     * that keeps the wasm-idiomatic vocabulary at the Mezze surface.
     */
    @TruffleBoundary
    public static MezzeVariant getExport(MezzeRecord args) {
        // Reuse the generic member reader — wasm exports live in the
        // same member namespace as any other InteropLibrary object.
        return PolyglotNatives.readMember(args);
    }

    // ==================== Linear memory ====================

    /**
     * Read a range of bytes from a wasm module's linear memory. The
     * receiver must be a wasm memory Foreign (typically obtained via
     * {@code module.get_export { name = "memory" }}). Returns
     * {@code Result Bytes InteropError}.
     */
    @TruffleBoundary
    public static MezzeVariant memoryRead(MezzeRecord args) {
        MezzeForeign f = PolyglotNatives.asForeign(args);
        long offset = PolyglotNatives.getLong(args, "offset");
        long length = PolyglotNatives.getLong(args, "length");
        if (offset < 0 || length < 0) {
            return MezzeVariant.err(PolyglotNatives.errorTypeError(
                "memory_read: negative offset or length (offset=" +
                offset + ", length=" + length + ")"));
        }
        try {
            InteropLibrary lib = InteropLibrary.getUncached(f.getDelegate());
            long size = lib.getArraySize(f.getDelegate());
            if (offset + length > size) {
                return MezzeVariant.err(PolyglotNatives.errorTypeError(
                    "memory_read: out of range (offset=" + offset +
                    ", length=" + length + ", memory_size=" + size + ")"));
            }
            byte[] out = new byte[(int) length];
            for (int i = 0; i < length; i++) {
                Object byteVal = lib.readArrayElement(f.getDelegate(), offset + i);
                // GraalWasm returns each byte boxed as a numeric — coerce
                // via the interop library so we don't depend on the exact
                // boxed type (Integer vs Long vs Byte).
                out[i] = (byte) InteropLibrary.getUncached(byteVal).asInt(byteVal);
            }
            return MezzeVariant.ok(new BytesNatives.MezzeBytes(out));
        } catch (Throwable t) {
            return MezzeVariant.err(PolyglotNatives.translateException(t));
        }
    }

    /**
     * Write a byte range into a wasm module's linear memory. Returns
     * {@code Result {} InteropError}: Ok on success, Err if the memory
     * is not writable or the range is out of bounds.
     */
    @TruffleBoundary
    public static MezzeVariant memoryWrite(MezzeRecord args) {
        MezzeForeign f = PolyglotNatives.asForeign(args);
        long offset = PolyglotNatives.getLong(args, "offset");
        BytesNatives.MezzeBytes bytes = getBytesArg(args, "bytes");
        if (offset < 0) {
            return MezzeVariant.err(PolyglotNatives.errorTypeError(
                "memory_write: negative offset (offset=" + offset + ")"));
        }
        byte[] data = bytes.getData();
        try {
            InteropLibrary lib = InteropLibrary.getUncached(f.getDelegate());
            long size = lib.getArraySize(f.getDelegate());
            if (offset + data.length > size) {
                return MezzeVariant.err(PolyglotNatives.errorTypeError(
                    "memory_write: out of range (offset=" + offset +
                    ", length=" + data.length + ", memory_size=" + size + ")"));
            }
            for (int i = 0; i < data.length; i++) {
                lib.writeArrayElement(f.getDelegate(), offset + i,
                    (int) (data[i] & 0xFF));
            }
            return MezzeVariant.ok(MezzeRecord.empty());
        } catch (Throwable t) {
            return MezzeVariant.err(PolyglotNatives.translateException(t));
        }
    }

    /**
     * Size (in bytes) of a wasm linear-memory export. Returns
     * {@code Result Int InteropError}.
     */
    @TruffleBoundary
    public static MezzeVariant memorySize(MezzeRecord args) {
        MezzeForeign f = PolyglotNatives.asForeign(args);
        try {
            long size = InteropLibrary.getUncached().getArraySize(f.getDelegate());
            return MezzeVariant.ok(size);
        } catch (Throwable t) {
            return MezzeVariant.err(PolyglotNatives.translateException(t));
        }
    }

    // ==================== Numeric constructors ====================

    /**
     * Lift a Mezze Int (64-bit host Long) into a wasm i32 Foreign.
     * Wasm's function signatures type-check argument types at invoke
     * time: passing a Java {@code Long} to an i32-typed parameter
     * yields an {@code UnsupportedTypeException}. Storing the value as
     * a Java {@code Integer} here (truncated with sign-preserving cast)
     * makes the resulting Foreign directly acceptable to any i32-typed
     * wasm export.
     *
     * Callers who need i64 semantics should coerce with the generic
     * {@code __native_polyglot_from_int} instead — that path stores
     * the value as a Long and matches wasm's i64 ABI.
     */
    @TruffleBoundary
    public static MezzeForeign fromInt(MezzeRecord args) {
        long v = PolyglotNatives.getLong(args, "val");
        return new MezzeForeign(Integer.valueOf((int) v), "wasm");
    }

    /**
     * Lift a Mezze Float (host Double) into a wasm f32 / f64 Foreign.
     * Wasm's f64 accepts a Java {@code Double} directly; f32 accepts a
     * {@code Float}. We use Double here to match Mezze's Float
     * semantics — a wasm export expecting f32 receives the Double and
     * GraalWasm narrows it automatically.
     */
    @TruffleBoundary
    public static MezzeForeign fromFloat(MezzeRecord args) {
        double v = PolyglotNatives.getDouble(args, "val");
        return new MezzeForeign(Double.valueOf(v), "wasm");
    }

    // ==================== Helpers ====================

    private static BytesNatives.MezzeBytes getBytesArg(MezzeRecord r, String name) {
        Object v = PolyglotNatives.getField(r, name);
        if (v instanceof BytesNatives.MezzeBytes b) return b;
        throw new com.mezze.interpreter.runtime.NativeTypeMismatchException(
            "Expected Bytes for field '" + name + "'");
    }
}
