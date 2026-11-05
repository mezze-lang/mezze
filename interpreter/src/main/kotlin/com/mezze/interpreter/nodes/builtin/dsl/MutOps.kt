package com.mezze.interpreter.nodes.builtin.dsl

import com.mezze.datastructures.seq.MezzeSeq
import com.mezze.interpreter.runtime.NativeTypeMismatchException
import com.mezze.interpreter.values.MezzeRecord
import com.mezze.interpreter.values.MezzeVariant
import com.mezze.interpreter.values.MutableBool
import com.mezze.interpreter.values.MutableBytes
import com.mezze.interpreter.values.MutableChar
import com.mezze.interpreter.values.MutableDeque
import com.mezze.interpreter.values.MutableFloat
import com.mezze.interpreter.values.MutableInt
import com.mezze.interpreter.values.MutableMap
import com.mezze.interpreter.values.MutableSeq
import com.mezze.interpreter.values.MutableSet
import com.mezze.interpreter.values.MutableString
import com.mezze.language.truffleir.SlotKind
import com.oracle.truffle.api.CompilerDirectives

/**
 * Mut natives in DSL form.
 *
 * WHY THESE AND NOT THE LAYER THAT ALREADY EXISTED. `BytecodeCompiler` holds a
 * `KnownMutOps` table mapping 73 of these natives onto dedicated bytecode
 * operations — `MutReadInt`, `MutIncr`, `MutSize` and the rest — which return
 * unboxed `long`/`double`/`boolean` and would be strictly faster than this
 * path. It is DEAD CODE, and was when written. Every tier of it is gated on
 * `CallDirectIR.isScalarABI()`, which [com.mezze.language.truffleir.ParamScalarizer]
 * sets for EXTRACTED LAMBDAS only — deliberately, since changing a function's
 * arity is sound only if every caller is rewritten with it, and a top-level
 * `pub` declaration may be invoked from outside the map. A Mut wrapper is
 * exactly such a declaration.
 *
 * And widening that gate does not help either, because by the time the
 * bytecode compiler runs there is no call to the wrapper left to match: the
 * wrapper is inlined, and what survives in the caller is a bare
 * `CallNativeIR(name = "__native_mut_int_read", arg = MakeRecordIR([n = …]))`
 * carrying the CALLER's source line. That is the shape this file addresses.
 *
 * MEASURED, before any of this, on `bench/cases/mutabi` (n=2e7, min of 3; every
 * kernel is a tail loop with one mut op per iteration, so kernel 1 holds the
 * loop constant):
 *
 * | | ps/iter | the op alone |
 * |---|---|---|
 * | 1 control | 253 | — |
 * | `read_int` | 8 120 | 7 867 |
 * | `incr` | 12 272 | 12 019 |
 * | `seq::size` | 8 154 | 7 901 |
 * | `seq::get` | 15 461 | 15 208 |
 * | `string_char_at` | 17 039 | 16 786 |
 *
 * For scale, a `deque::is_empty` on this ABI costs 4 083 ps for comparable
 * work. The dead layer was killed outright to confirm it was dead: all seven
 * kernels moved under 4% with identical checksums, `mut_loop` went 119 → 122 ms
 * and `qsort_mut` 85.0 → 86.2 ms.
 *
 * EVERYTHING HERE IS `pure = false`, which is the default and is left implicit
 * only where it reads as such. `pure` licenses the compiler to DUPLICATE or
 * ELIDE a call, and that is wrong for a mutable cell in both directions: a
 * write obviously, but a read too, since duplicating one across a write
 * changes the answer.
 *
 * WHAT IS ABSENT: `__native_mut_seq_sort_by`, the one HOF here — it takes a
 * guest comparator and needs the `IndirectCallNode` alongside it, which the
 * scalar path does not hand the handler. No name here can collide with the
 * fusion vocabulary: `ChainCanonicalizer` matches `__native_seq_`,
 * `__native_set_`, `__native_deque_` and friends by PREFIX, and
 * `__native_mut_seq_get` starts with none of them.
 *
 * The accessors are faithful copies of `NativeDispatch`'s, message included:
 * each throws naming the OP rather than the field, because that is what the
 * record-path handler did and a changed error string is a changed test.
 */
object MutOps {
    /** Forces class load when referenced; used by NativeBootstrap. */
    @JvmField val MARKER: Unit = Unit

    private val UNIT: Any get() = MezzeRecord.empty()

    /**
     * `@TruffleBoundary` because this builds a String, and an op reached
     * through a typed shape is partially evaluated — so
     * `StringConcatHelper.simpleConcat` becomes reachable for runtime
     * compilation and native-image's blocklist fails the whole build. It cost
     * one native build to find, and it is the same failure
     * `MezzeGuestException.dividingByZero` had for the same reason: ANY string
     * building on an error path inside a PE'd native op needs a boundary.
     * Nothing is lost — it is the path that is about to throw.
     */
    @CompilerDirectives.TruffleBoundary
    private fun mismatch(op: String, type: String): Nothing =
        throw NativeTypeMismatchException("$op expects $type")

    private fun mutInt(a: NativeArgs, op: String): MutableInt =
        a.obj(0) as? MutableInt ?: mismatch(op, "MutableInt")

    private fun mutFloat(a: NativeArgs, op: String): MutableFloat =
        a.obj(0) as? MutableFloat ?: mismatch(op, "MutableFloat")

    private fun mutBool(a: NativeArgs, op: String): MutableBool =
        a.obj(0) as? MutableBool ?: mismatch(op, "MutableBool")

    private fun mutChar(a: NativeArgs, op: String): MutableChar =
        a.obj(0) as? MutableChar ?: mismatch(op, "MutableChar")

    private fun mutStr(a: NativeArgs, op: String): MutableString =
        a.obj(0) as? MutableString ?: mismatch(op, "MutableString")

    private fun mutSeq(a: NativeArgs, op: String): MutableSeq =
        a.obj(0) as? MutableSeq ?: mismatch(op, "MutableSeq")

    private fun mutSet(a: NativeArgs, op: String): MutableSet =
        a.obj(0) as? MutableSet ?: mismatch(op, "MutableSet")

    private fun mutMap(a: NativeArgs, op: String): MutableMap =
        a.obj(0) as? MutableMap ?: mismatch(op, "MutableMap")

    private fun mutDeque(a: NativeArgs, op: String): MutableDeque =
        a.obj(0) as? MutableDeque ?: mismatch(op, "MutableDeque")

    private fun mutBytes(a: NativeArgs, op: String): MutableBytes =
        a.obj(0) as? MutableBytes ?: mismatch(op, "MutableBytes")

    /** `Some v` / `None`, the shape every `get`-like handler returned. */
    private fun option(v: Any?): Any =
        if (v == null) MezzeVariant.none() else MezzeVariant.some(v)

    // ==================== MutInt ====================
    // The cell is always parameter 0; the operand's NAME differs per op
    // (`delta`, `factor`, `divisor`) and the ABI is positional, so the
    // declaration must spell the stdlib's name exactly or the transposition
    // finds no field.

    /**
     * ONE BODY, BOTH REGISTRIES. `NativeOp` publishes the record/boundary path
     * that the interpreter and every un-ported caller use;
     * `TypedNativeHandlers.registerOL` publishes the same function into the
     * `(Object) -> long` shape, which `BytecodeCompiler` emits as
     * `CallNativeOL` — no boundary, nothing packed into an array. 4 196 → 659
     * ps/iter, against 658 for the same loop calling an ordinary Mezze
     * function; see [TypedNativeHandlers.OL].
     *
     * Named rather than written twice on purpose: the existing typed
     * registrations in `NatOps` pass a second lambda for the typed side, which
     * is two bodies to keep in agreement for one op.
     *
     * `MutableInt` is the ONLY receiver class this works for today — it is the
     * only one with no `@TruffleBoundary` on its methods. See
     * [TypedNativeHandlers.OL] for what happened to the others.
     */
    private fun intRead(o: Any): Long =
        (o as? MutableInt ?: mismatch("__native_mut_int_read", "MutableInt")).get()

    private val INT_READ = NativeOp("__native_mut_int_read",
        listOf(Param("n", SlotKind.OBJECT)), SlotKind.LONG,
    ) { a -> intRead(a.obj(0)) }

    init {
        TypedNativeHandlers.registerOL("__native_mut_int_read", ::intRead)
    }

    private val INT_WRITE = NativeOp("__native_mut_int_write",
        listOf(Param("n", SlotKind.OBJECT), Param("v", SlotKind.LONG)), SlotKind.OBJECT,
    ) { a -> mutInt(a, "__native_mut_int_write").set(a.long(1)); UNIT }

    private val INT_INCR = NativeOp("__native_mut_int_incr",
        listOf(Param("n", SlotKind.OBJECT), Param("delta", SlotKind.LONG)), SlotKind.OBJECT,
    ) { a -> mutInt(a, "__native_mut_int_incr").add(a.long(1)); UNIT }

    private val INT_DECR = NativeOp("__native_mut_int_decr",
        listOf(Param("n", SlotKind.OBJECT), Param("delta", SlotKind.LONG)), SlotKind.OBJECT,
    ) { a -> mutInt(a, "__native_mut_int_decr").subtract(a.long(1)); UNIT }

    private val INT_MUL = NativeOp("__native_mut_int_mul",
        listOf(Param("n", SlotKind.OBJECT), Param("factor", SlotKind.LONG)), SlotKind.OBJECT,
    ) { a -> mutInt(a, "__native_mut_int_mul").multiply(a.long(1)); UNIT }

    private val INT_DIV = NativeOp("__native_mut_int_div",
        listOf(Param("n", SlotKind.OBJECT), Param("divisor", SlotKind.LONG)), SlotKind.OBJECT,
    ) { a -> mutInt(a, "__native_mut_int_div").divide(a.long(1)); UNIT }

    private val INT_MOD = NativeOp("__native_mut_int_mod",
        listOf(Param("n", SlotKind.OBJECT), Param("divisor", SlotKind.LONG)), SlotKind.OBJECT,
    ) { a -> mutInt(a, "__native_mut_int_mod").mod(a.long(1)); UNIT }

    /** `expected` BEFORE `new_val` — reversing them swaps the test with the
     *  value written, which still type-checks and still returns a Bool. */
    private val INT_CAS = NativeOp("__native_mut_int_cas",
        listOf(
            Param("n", SlotKind.OBJECT),
            Param("expected", SlotKind.LONG),
            Param("new_val", SlotKind.LONG),
        ), SlotKind.BOOLEAN,
    ) { a -> mutInt(a, "__native_mut_int_cas").compareAndSwap(a.long(1), a.long(2)) }

    // ==================== MutFloat ====================
    private val FLOAT_READ = NativeOp("__native_mut_float_read",
        listOf(Param("n", SlotKind.OBJECT)), SlotKind.DOUBLE,
    ) { a -> mutFloat(a, "__native_mut_float_read").get() }

    private val FLOAT_WRITE = NativeOp("__native_mut_float_write",
        listOf(Param("n", SlotKind.OBJECT), Param("v", SlotKind.DOUBLE)), SlotKind.OBJECT,
    ) { a -> mutFloat(a, "__native_mut_float_write").set(a.double(1)); UNIT }

    private val FLOAT_ADD = NativeOp("__native_mut_float_add",
        listOf(Param("n", SlotKind.OBJECT), Param("delta", SlotKind.DOUBLE)), SlotKind.OBJECT,
    ) { a -> mutFloat(a, "__native_mut_float_add").add(a.double(1)); UNIT }

    private val FLOAT_SUB = NativeOp("__native_mut_float_sub",
        listOf(Param("n", SlotKind.OBJECT), Param("delta", SlotKind.DOUBLE)), SlotKind.OBJECT,
    ) { a -> mutFloat(a, "__native_mut_float_sub").subtract(a.double(1)); UNIT }

    private val FLOAT_MUL = NativeOp("__native_mut_float_mul",
        listOf(Param("n", SlotKind.OBJECT), Param("factor", SlotKind.DOUBLE)), SlotKind.OBJECT,
    ) { a -> mutFloat(a, "__native_mut_float_mul").multiply(a.double(1)); UNIT }

    private val FLOAT_DIV = NativeOp("__native_mut_float_div",
        listOf(Param("n", SlotKind.OBJECT), Param("divisor", SlotKind.DOUBLE)), SlotKind.OBJECT,
    ) { a -> mutFloat(a, "__native_mut_float_div").divide(a.double(1)); UNIT }

    // ==================== MutBool ====================
    // The cell's field is `b` here, not `n`.
    private val BOOL_READ = NativeOp("__native_mut_bool_read",
        listOf(Param("b", SlotKind.OBJECT)), SlotKind.BOOLEAN,
    ) { a -> mutBool(a, "__native_mut_bool_read").get() }

    private val BOOL_WRITE = NativeOp("__native_mut_bool_write",
        listOf(Param("b", SlotKind.OBJECT), Param("v", SlotKind.BOOLEAN)), SlotKind.OBJECT,
    ) { a -> mutBool(a, "__native_mut_bool_write").set(a.bool(1)); UNIT }

    private val BOOL_TOGGLE = NativeOp("__native_mut_bool_toggle",
        listOf(Param("b", SlotKind.OBJECT)), SlotKind.OBJECT,
    ) { a -> mutBool(a, "__native_mut_bool_toggle").toggle(); UNIT }

    private val BOOL_SET_TRUE = NativeOp("__native_mut_bool_set_true",
        listOf(Param("b", SlotKind.OBJECT)), SlotKind.OBJECT,
    ) { a -> mutBool(a, "__native_mut_bool_set_true").set(true); UNIT }

    private val BOOL_SET_FALSE = NativeOp("__native_mut_bool_set_false",
        listOf(Param("b", SlotKind.OBJECT)), SlotKind.OBJECT,
    ) { a -> mutBool(a, "__native_mut_bool_set_false").set(false); UNIT }

    // ==================== MutChar ====================
    // A `Char` VALUE is a one-character String at runtime, so `read` hands back
    // a String and `write` takes one.
    private val CHAR_READ = NativeOp("__native_mut_char_read",
        listOf(Param("c", SlotKind.OBJECT)), SlotKind.OBJECT,
    ) { a -> mutChar(a, "__native_mut_char_read").get().toString() }

    private val CHAR_WRITE = NativeOp("__native_mut_char_write",
        listOf(Param("c", SlotKind.OBJECT), Param("v", SlotKind.OBJECT)), SlotKind.OBJECT,
    ) { a ->
        val v = a.string(1)
        if (v.isEmpty()) mismatch("__native_mut_char_write", "a non-empty Char")
        mutChar(a, "__native_mut_char_write").set(v[0])
        UNIT
    }

    private val CHAR_TO_UPPER = NativeOp("__native_mut_char_to_upper",
        listOf(Param("c", SlotKind.OBJECT)), SlotKind.OBJECT,
    ) { a -> mutChar(a, "__native_mut_char_to_upper").toUpper(); UNIT }

    private val CHAR_TO_LOWER = NativeOp("__native_mut_char_to_lower",
        listOf(Param("c", SlotKind.OBJECT)), SlotKind.OBJECT,
    ) { a -> mutChar(a, "__native_mut_char_to_lower").toLower(); UNIT }

    private val CHAR_IS_DIGIT = NativeOp("__native_mut_char_is_digit",
        listOf(Param("c", SlotKind.OBJECT)), SlotKind.BOOLEAN,
    ) { a -> mutChar(a, "__native_mut_char_is_digit").isDigit }

    private val CHAR_IS_LETTER = NativeOp("__native_mut_char_is_letter",
        listOf(Param("c", SlotKind.OBJECT)), SlotKind.BOOLEAN,
    ) { a -> mutChar(a, "__native_mut_char_is_letter").isLetter }

    private val CHAR_IS_WHITESPACE = NativeOp("__native_mut_char_is_whitespace",
        listOf(Param("c", SlotKind.OBJECT)), SlotKind.BOOLEAN,
    ) { a -> mutChar(a, "__native_mut_char_is_whitespace").isWhitespace }

    // ==================== MutString ====================
    private val STRING_READ = NativeOp("__native_mut_string_read",
        listOf(Param("s", SlotKind.OBJECT)), SlotKind.OBJECT,
    ) { a -> mutStr(a, "__native_mut_string_read").get() }

    private val STRING_WRITE = NativeOp("__native_mut_string_write",
        listOf(Param("s", SlotKind.OBJECT), Param("v", SlotKind.OBJECT)), SlotKind.OBJECT,
    ) { a -> mutStr(a, "__native_mut_string_write").set(a.string(1)); UNIT }

    private val STRING_LEN = NativeOp("__native_mut_string_len",
        listOf(Param("s", SlotKind.OBJECT)), SlotKind.LONG,
    ) { a -> mutStr(a, "__native_mut_string_len").length().toLong() }

    private val STRING_IS_EMPTY = NativeOp("__native_mut_string_is_empty",
        listOf(Param("s", SlotKind.OBJECT)), SlotKind.BOOLEAN,
    ) { a -> mutStr(a, "__native_mut_string_is_empty").isEmpty }

    /** `suffix` for append, `prefix` for prepend — different names, and the
     *  two ops are otherwise indistinguishable. */
    private val STRING_APPEND = NativeOp("__native_mut_string_append",
        listOf(Param("s", SlotKind.OBJECT), Param("suffix", SlotKind.OBJECT)), SlotKind.OBJECT,
    ) { a -> mutStr(a, "__native_mut_string_append").append(a.string(1)); UNIT }

    private val STRING_PREPEND = NativeOp("__native_mut_string_prepend",
        listOf(Param("s", SlotKind.OBJECT), Param("prefix", SlotKind.OBJECT)), SlotKind.OBJECT,
    ) { a -> mutStr(a, "__native_mut_string_prepend").prepend(a.string(1)); UNIT }

    private val STRING_CLEAR = NativeOp("__native_mut_string_clear",
        listOf(Param("s", SlotKind.OBJECT)), SlotKind.OBJECT,
    ) { a -> mutStr(a, "__native_mut_string_clear").clear(); UNIT }

    private val STRING_REVERSE = NativeOp("__native_mut_string_reverse",
        listOf(Param("s", SlotKind.OBJECT)), SlotKind.OBJECT,
    ) { a -> mutStr(a, "__native_mut_string_reverse").reverse(); UNIT }

    private val STRING_INSERT = NativeOp("__native_mut_string_insert",
        listOf(
            Param("s", SlotKind.OBJECT),
            Param("i", SlotKind.LONG),
            Param("text", SlotKind.OBJECT),
        ), SlotKind.OBJECT,
    ) { a ->
        mutStr(a, "__native_mut_string_insert").insert(a.long(1).toInt(), a.string(2))
        UNIT
    }

    /** `start` BEFORE `end`. */
    private val STRING_DELETE = NativeOp("__native_mut_string_delete",
        listOf(
            Param("s", SlotKind.OBJECT),
            Param("start", SlotKind.LONG),
            Param("end", SlotKind.LONG),
        ), SlotKind.OBJECT,
    ) { a ->
        mutStr(a, "__native_mut_string_delete").delete(a.long(1).toInt(), a.long(2).toInt())
        UNIT
    }

    /** NUL means out of range here, which is the record handler's own
     *  convention and is kept rather than improved. */
    private val STRING_CHAR_AT = NativeOp("__native_mut_string_char_at",
        listOf(Param("s", SlotKind.OBJECT), Param("i", SlotKind.LONG)), SlotKind.OBJECT,
    ) { a ->
        val ch = mutStr(a, "__native_mut_string_char_at").charAt(a.long(1).toInt())
        if (ch == '\u0000') MezzeVariant.none() else MezzeVariant.some(ch.toString())
    }

    private val STRING_SET_CHAR_AT = NativeOp("__native_mut_string_set_char_at",
        listOf(
            Param("s", SlotKind.OBJECT),
            Param("i", SlotKind.LONG),
            Param("c", SlotKind.OBJECT),
        ), SlotKind.OBJECT,
    ) { a ->
        val v = a.string(2)
        if (v.isEmpty()) mismatch("__native_mut_string_set_char_at", "a non-empty Char")
        mutStr(a, "__native_mut_string_set_char_at").setCharAt(a.long(1).toInt(), v[0])
        UNIT
    }

    // ==================== MutSeq ====================
    private val SEQ_PUSH = NativeOp("__native_mut_seq_push",
        listOf(Param("seq", SlotKind.OBJECT), Param("v", SlotKind.OBJECT)), SlotKind.OBJECT,
    ) { a -> mutSeq(a, "__native_mut_seq_push").push(a.obj(1)); UNIT }

    private val SEQ_POP = NativeOp("__native_mut_seq_pop",
        listOf(Param("seq", SlotKind.OBJECT)), SlotKind.OBJECT,
    ) { a -> option(mutSeq(a, "__native_mut_seq_pop").pop()) }

    private val SEQ_GET = NativeOp("__native_mut_seq_get",
        listOf(Param("seq", SlotKind.OBJECT), Param("i", SlotKind.LONG)), SlotKind.OBJECT,
    ) { a -> option(mutSeq(a, "__native_mut_seq_get").getSafe(a.long(1).toInt())) }

    /** `i` BEFORE `v` — swapped, this would index by the value. */
    private val SEQ_SET = NativeOp("__native_mut_seq_set",
        listOf(
            Param("seq", SlotKind.OBJECT),
            Param("i", SlotKind.LONG),
            Param("v", SlotKind.OBJECT),
        ), SlotKind.BOOLEAN,
    ) { a -> mutSeq(a, "__native_mut_seq_set").setSafe(a.long(1).toInt(), a.obj(2)) }

    private val SEQ_LEN = NativeOp("__native_mut_seq_len",
        listOf(Param("seq", SlotKind.OBJECT)), SlotKind.LONG,
    ) { a -> mutSeq(a, "__native_mut_seq_len").size().toLong() }

    private val SEQ_CLEAR = NativeOp("__native_mut_seq_clear",
        listOf(Param("seq", SlotKind.OBJECT)), SlotKind.OBJECT,
    ) { a -> mutSeq(a, "__native_mut_seq_clear").clear(); UNIT }

    private val SEQ_INSERT = NativeOp("__native_mut_seq_insert",
        listOf(
            Param("seq", SlotKind.OBJECT),
            Param("i", SlotKind.LONG),
            Param("v", SlotKind.OBJECT),
        ), SlotKind.BOOLEAN,
    ) { a -> mutSeq(a, "__native_mut_seq_insert").insert(a.long(1).toInt(), a.obj(2)) }

    private val SEQ_REMOVE = NativeOp("__native_mut_seq_remove",
        listOf(Param("seq", SlotKind.OBJECT), Param("i", SlotKind.LONG)), SlotKind.OBJECT,
    ) { a -> option(mutSeq(a, "__native_mut_seq_remove").remove(a.long(1).toInt())) }

    /** The record handler caught `IndexOutOfBoundsException` and returned
     *  false; that is behaviour, not an accident, so it is kept. */
    private val SEQ_SWAP = NativeOp("__native_mut_seq_swap",
        listOf(
            Param("seq", SlotKind.OBJECT),
            Param("i", SlotKind.LONG),
            Param("j", SlotKind.LONG),
        ), SlotKind.BOOLEAN,
    ) { a ->
        try {
            mutSeq(a, "__native_mut_seq_swap").swap(a.long(1).toInt(), a.long(2).toInt())
            true
        } catch (_: IndexOutOfBoundsException) {
            false
        }
    }

    private val SEQ_REVERSE = NativeOp("__native_mut_seq_reverse",
        listOf(Param("seq", SlotKind.OBJECT)), SlotKind.OBJECT,
    ) { a -> mutSeq(a, "__native_mut_seq_reverse").reverse(); UNIT }

    private val SEQ_SORT = NativeOp("__native_mut_seq_sort",
        listOf(Param("seq", SlotKind.OBJECT)), SlotKind.OBJECT,
    ) { a -> mutSeq(a, "__native_mut_seq_sort").sort(); UNIT }

    private val SEQ_FILL = NativeOp("__native_mut_seq_fill",
        listOf(Param("seq", SlotKind.OBJECT), Param("v", SlotKind.OBJECT)), SlotKind.OBJECT,
    ) { a -> mutSeq(a, "__native_mut_seq_fill").fill(a.obj(1)); UNIT }

    private val SEQ_FILL_RANGE = NativeOp("__native_mut_seq_fill_range",
        listOf(
            Param("seq", SlotKind.OBJECT),
            Param("start", SlotKind.LONG),
            Param("end", SlotKind.LONG),
            Param("v", SlotKind.OBJECT),
        ), SlotKind.OBJECT,
    ) { a ->
        mutSeq(a, "__native_mut_seq_fill_range")
            .fillRange(a.long(1).toInt(), a.long(2).toInt(), a.obj(3))
        UNIT
    }

    /** A constructor: no cell to read, so parameter 0 is the capacity. */
    private val SEQ_WITH_CAPACITY = NativeOp("__native_mut_seq_with_capacity",
        listOf(Param("capacity", SlotKind.LONG)), SlotKind.OBJECT,
    ) { a -> MutableSeq.withCapacity(a.long(0).toInt()) }

    // ==================== MutSet ====================
    // The element's field is `elem` here, where the immutable set natives
    // spell it `x`.
    private val SET_ADD = NativeOp("__native_mut_set_add",
        listOf(Param("set", SlotKind.OBJECT), Param("elem", SlotKind.OBJECT)), SlotKind.OBJECT,
    ) { a -> mutSet(a, "__native_mut_set_add").add(a.obj(1)); UNIT }

    private val SET_REMOVE = NativeOp("__native_mut_set_remove",
        listOf(Param("set", SlotKind.OBJECT), Param("elem", SlotKind.OBJECT)), SlotKind.OBJECT,
    ) { a -> mutSet(a, "__native_mut_set_remove").remove(a.obj(1)); UNIT }

    private val SET_CONTAINS = NativeOp("__native_mut_set_contains",
        listOf(Param("set", SlotKind.OBJECT), Param("elem", SlotKind.OBJECT)), SlotKind.BOOLEAN,
    ) { a -> mutSet(a, "__native_mut_set_contains").contains(a.obj(1)) }

    private val SET_SIZE = NativeOp("__native_mut_set_size",
        listOf(Param("set", SlotKind.OBJECT)), SlotKind.LONG,
    ) { a -> mutSet(a, "__native_mut_set_size").size().toLong() }

    private val SET_CLEAR = NativeOp("__native_mut_set_clear",
        listOf(Param("set", SlotKind.OBJECT)), SlotKind.OBJECT,
    ) { a -> mutSet(a, "__native_mut_set_clear").clear(); UNIT }

    private val SET_ELEMENTS = NativeOp("__native_mut_set_elements",
        listOf(Param("set", SlotKind.OBJECT)), SlotKind.OBJECT,
    ) { a -> MezzeSeq.fromArray(mutSet(a, "__native_mut_set_elements").elements()) }

    private val SET_TOGGLE = NativeOp("__native_mut_set_toggle",
        listOf(Param("set", SlotKind.OBJECT), Param("elem", SlotKind.OBJECT)), SlotKind.BOOLEAN,
    ) { a -> mutSet(a, "__native_mut_set_toggle").toggle(a.obj(1)) }

    private val SET_WITH_CAPACITY = NativeOp("__native_mut_set_with_capacity",
        listOf(Param("capacity", SlotKind.LONG)), SlotKind.OBJECT,
    ) { a -> MutableSet.withCapacity(a.long(0).toInt()) }

    // ==================== MutMap ====================
    // `key` then `value` — and `value` spelled in full, unlike seq's `v`.
    private val MAP_GET = NativeOp("__native_mut_map_get",
        listOf(Param("map", SlotKind.OBJECT), Param("key", SlotKind.OBJECT)), SlotKind.OBJECT,
    ) { a -> option(mutMap(a, "__native_mut_map_get").get(a.obj(1))) }

    private val MAP_PUT = NativeOp("__native_mut_map_put",
        listOf(
            Param("map", SlotKind.OBJECT),
            Param("key", SlotKind.OBJECT),
            Param("value", SlotKind.OBJECT),
        ), SlotKind.OBJECT,
    ) { a -> mutMap(a, "__native_mut_map_put").put(a.obj(1), a.obj(2)); UNIT }

    private val MAP_REMOVE = NativeOp("__native_mut_map_remove",
        listOf(Param("map", SlotKind.OBJECT), Param("key", SlotKind.OBJECT)), SlotKind.OBJECT,
    ) { a -> mutMap(a, "__native_mut_map_remove").remove(a.obj(1)); UNIT }

    private val MAP_CONTAINS = NativeOp("__native_mut_map_contains",
        listOf(Param("map", SlotKind.OBJECT), Param("key", SlotKind.OBJECT)), SlotKind.BOOLEAN,
    ) { a -> mutMap(a, "__native_mut_map_contains").containsKey(a.obj(1)) }

    private val MAP_SIZE = NativeOp("__native_mut_map_size",
        listOf(Param("map", SlotKind.OBJECT)), SlotKind.LONG,
    ) { a -> mutMap(a, "__native_mut_map_size").size().toLong() }

    private val MAP_CLEAR = NativeOp("__native_mut_map_clear",
        listOf(Param("map", SlotKind.OBJECT)), SlotKind.OBJECT,
    ) { a -> mutMap(a, "__native_mut_map_clear").clear(); UNIT }

    private val MAP_KEYS = NativeOp("__native_mut_map_keys",
        listOf(Param("map", SlotKind.OBJECT)), SlotKind.OBJECT,
    ) { a -> MezzeSeq.fromArray(mutMap(a, "__native_mut_map_keys").keys()) }

    private val MAP_VALUES = NativeOp("__native_mut_map_values",
        listOf(Param("map", SlotKind.OBJECT)), SlotKind.OBJECT,
    ) { a -> MezzeSeq.fromArray(mutMap(a, "__native_mut_map_values").values()) }

    private val MAP_ENTRIES = NativeOp("__native_mut_map_entries",
        listOf(Param("map", SlotKind.OBJECT)), SlotKind.OBJECT,
    ) { a -> MezzeSeq.fromArray(mutMap(a, "__native_mut_map_entries").entries()) }

    private val MAP_WITH_CAPACITY = NativeOp("__native_mut_map_with_capacity",
        listOf(Param("capacity", SlotKind.LONG)), SlotKind.OBJECT,
    ) { a -> MutableMap.withCapacity(a.long(0).toInt()) }

    // ==================== MutDeque ====================
    // The receiver's field is `deque`, not `d`.
    private val DEQUE_PUSH_FRONT = NativeOp("__native_mut_deque_push_front",
        listOf(Param("deque", SlotKind.OBJECT), Param("elem", SlotKind.OBJECT)), SlotKind.OBJECT,
    ) { a -> mutDeque(a, "__native_mut_deque_push_front").pushFront(a.obj(1)); UNIT }

    private val DEQUE_PUSH_BACK = NativeOp("__native_mut_deque_push_back",
        listOf(Param("deque", SlotKind.OBJECT), Param("elem", SlotKind.OBJECT)), SlotKind.OBJECT,
    ) { a -> mutDeque(a, "__native_mut_deque_push_back").pushBack(a.obj(1)); UNIT }

    private val DEQUE_POP_FRONT = NativeOp("__native_mut_deque_pop_front",
        listOf(Param("deque", SlotKind.OBJECT)), SlotKind.OBJECT,
    ) { a -> option(mutDeque(a, "__native_mut_deque_pop_front").popFront()) }

    private val DEQUE_POP_BACK = NativeOp("__native_mut_deque_pop_back",
        listOf(Param("deque", SlotKind.OBJECT)), SlotKind.OBJECT,
    ) { a -> option(mutDeque(a, "__native_mut_deque_pop_back").popBack()) }

    private val DEQUE_PEEK_FRONT = NativeOp("__native_mut_deque_peek_front",
        listOf(Param("deque", SlotKind.OBJECT)), SlotKind.OBJECT,
    ) { a -> option(mutDeque(a, "__native_mut_deque_peek_front").peekFront()) }

    private val DEQUE_PEEK_BACK = NativeOp("__native_mut_deque_peek_back",
        listOf(Param("deque", SlotKind.OBJECT)), SlotKind.OBJECT,
    ) { a -> option(mutDeque(a, "__native_mut_deque_peek_back").peekBack()) }

    private val DEQUE_SIZE = NativeOp("__native_mut_deque_size",
        listOf(Param("deque", SlotKind.OBJECT)), SlotKind.LONG,
    ) { a -> mutDeque(a, "__native_mut_deque_size").size().toLong() }

    private val DEQUE_IS_EMPTY = NativeOp("__native_mut_deque_is_empty",
        listOf(Param("deque", SlotKind.OBJECT)), SlotKind.BOOLEAN,
    ) { a -> mutDeque(a, "__native_mut_deque_is_empty").isEmpty }

    private val DEQUE_CLEAR = NativeOp("__native_mut_deque_clear",
        listOf(Param("deque", SlotKind.OBJECT)), SlotKind.OBJECT,
    ) { a -> mutDeque(a, "__native_mut_deque_clear").clear(); UNIT }

    private val DEQUE_REVERSE = NativeOp("__native_mut_deque_reverse",
        listOf(Param("deque", SlotKind.OBJECT)), SlotKind.OBJECT,
    ) { a -> mutDeque(a, "__native_mut_deque_reverse").reverse(); UNIT }

    private val DEQUE_TO_SEQ = NativeOp("__native_mut_deque_to_seq",
        listOf(Param("deque", SlotKind.OBJECT)), SlotKind.OBJECT,
    ) { a -> MezzeSeq.fromArray(mutDeque(a, "__native_mut_deque_to_seq").toArray()) }

    private val DEQUE_WITH_CAPACITY = NativeOp("__native_mut_deque_with_capacity",
        listOf(Param("capacity", SlotKind.LONG)), SlotKind.OBJECT,
    ) { a -> MutableDeque.withCapacity(a.long(0).toInt()) }

    // ==================== MutBytes ====================
    // A byte is carried as a LONG and narrowed by the handler, which is what
    // the record path did.
    private val BYTES_GET = NativeOp("__native_mut_bytes_get",
        listOf(Param("bytes", SlotKind.OBJECT), Param("i", SlotKind.LONG)), SlotKind.OBJECT,
    ) { a ->
        val v = mutBytes(a, "__native_mut_bytes_get").getSafe(a.long(1).toInt())
        if (v == -1) MezzeVariant.none() else MezzeVariant.some(v.toLong())
    }

    private val BYTES_SET = NativeOp("__native_mut_bytes_set",
        listOf(
            Param("bytes", SlotKind.OBJECT),
            Param("i", SlotKind.LONG),
            Param("v", SlotKind.LONG),
        ), SlotKind.BOOLEAN,
    ) { a ->
        mutBytes(a, "__native_mut_bytes_set")
            .setSafe(a.long(1).toInt(), a.long(2).toInt())
    }

    private val BYTES_LEN = NativeOp("__native_mut_bytes_len",
        listOf(Param("bytes", SlotKind.OBJECT)), SlotKind.LONG,
    ) { a -> mutBytes(a, "__native_mut_bytes_len").length().toLong() }

    private val BYTES_CLEAR = NativeOp("__native_mut_bytes_clear",
        listOf(Param("bytes", SlotKind.OBJECT)), SlotKind.OBJECT,
    ) { a -> mutBytes(a, "__native_mut_bytes_clear").clear(); UNIT }

    private val BYTES_REVERSE = NativeOp("__native_mut_bytes_reverse",
        listOf(Param("bytes", SlotKind.OBJECT)), SlotKind.OBJECT,
    ) { a -> mutBytes(a, "__native_mut_bytes_reverse").reverse(); UNIT }

    private val BYTES_FILL = NativeOp("__native_mut_bytes_fill",
        listOf(Param("bytes", SlotKind.OBJECT), Param("v", SlotKind.LONG)), SlotKind.OBJECT,
    ) { a -> mutBytes(a, "__native_mut_bytes_fill").fill(a.long(1).toByte()); UNIT }

    /** `start`, `end`, THEN the value — the value is last here and second in
     *  `fill`, which is exactly the kind of difference a positional ABI turns
     *  into a silent wrong answer. */
    private val BYTES_FILL_RANGE = NativeOp("__native_mut_bytes_fill_range",
        listOf(
            Param("bytes", SlotKind.OBJECT),
            Param("start", SlotKind.LONG),
            Param("end", SlotKind.LONG),
            Param("v", SlotKind.LONG),
        ), SlotKind.OBJECT,
    ) { a ->
        mutBytes(a, "__native_mut_bytes_fill_range")
            .fillRange(a.long(1).toInt(), a.long(2).toInt(), a.long(3).toByte())
        UNIT
    }

    /** `dest` is parameter 0 and `src` parameter 1, and then the offsets go
     *  SRC before DEST — the opposite of the receiver order. */
    private val BYTES_COPY = NativeOp("__native_mut_bytes_copy",
        listOf(
            Param("dest", SlotKind.OBJECT),
            Param("src", SlotKind.OBJECT),
            Param("src_offset", SlotKind.LONG),
            Param("dest_offset", SlotKind.LONG),
            Param("length", SlotKind.LONG),
        ), SlotKind.OBJECT,
    ) { a ->
        val src = a.obj(1) as? MutableBytes ?: mismatch("__native_mut_bytes_copy", "MutableBytes")
        mutBytes(a, "__native_mut_bytes_copy").copyFromMutable(
            src, a.long(2).toInt(), a.long(3).toInt(), a.long(4).toInt(),
        )
        UNIT
    }

    private val BYTES_RESIZE = NativeOp("__native_mut_bytes_resize",
        listOf(Param("bytes", SlotKind.OBJECT), Param("size", SlotKind.LONG)), SlotKind.OBJECT,
    ) { a -> mutBytes(a, "__native_mut_bytes_resize").resize(a.long(1).toInt()); UNIT }

    private val BYTES_SLICE = NativeOp("__native_mut_bytes_slice",
        listOf(
            Param("bytes", SlotKind.OBJECT),
            Param("start", SlotKind.LONG),
            Param("end", SlotKind.LONG),
        ), SlotKind.OBJECT,
    ) { a ->
        mutBytes(a, "__native_mut_bytes_slice").slice(a.long(1).toInt(), a.long(2).toInt())
    }

    private val BYTES_SWAP = NativeOp("__native_mut_bytes_swap",
        listOf(
            Param("bytes", SlotKind.OBJECT),
            Param("i", SlotKind.LONG),
            Param("j", SlotKind.LONG),
        ), SlotKind.BOOLEAN,
    ) { a ->
        mutBytes(a, "__native_mut_bytes_swap").swap(a.long(1).toInt(), a.long(2).toInt())
    }
}
