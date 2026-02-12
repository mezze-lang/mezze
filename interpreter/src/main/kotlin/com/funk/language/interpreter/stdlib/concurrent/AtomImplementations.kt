package com.funk.language.interpreter.stdlib.concurrent

import com.funk.language.interpreter.*
import java.util.concurrent.atomic.AtomicReference

/**
 * Native implementations for Clojure-style atomic references.
 *
 * Atoms provide thread-safe, lock-free state management using compare-and-swap (CAS).
 * The swap operation automatically retries if the value changed during computation.
 *
 * Based on Clojure's atom semantics:
 * - deref: Read current value (always instant)
 * - reset: Set new value (not atomic read-modify-write)
 * - swap: Apply function atomically with CAS retry
 * - compare_and_set: Low-level CAS operation
 */
object AtomImplementations {

    /**
     * Register all atom-related native functions.
     */
    fun registerAll(context: MutableMap<String, FunkValue>) {
        registerNew(context)
        registerDeref(context)
        registerReset(context)
        registerSwap(context)
        registerSwapWith(context)
        registerCas(context)
        registerGetAndSwap(context)
    }

    /**
     * __native_atom_new: {value: 'a} -> Atom 'a
     * Creates a new atom with the given initial value.
     */
    private fun registerNew(context: MutableMap<String, FunkValue>) {
        context["__native_atom_new"] = NativeFunkFunction("__native_atom_new") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw FunkException("__native_atom_new expects FunkRecord")

            val value = record.fields["value"]
                ?: throw FunkException("__native_atom_new expects 'value' field")

            FunkAtom(AtomicReference(value))
        }
    }

    /**
     * __native_atom_deref: {atom: Atom 'a} -> 'a
     * Reads the current value of the atom (non-blocking).
     */
    private fun registerDeref(context: MutableMap<String, FunkValue>) {
        context["__native_atom_deref"] = NativeFunkFunction("__native_atom_deref") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw FunkException("__native_atom_deref expects FunkRecord")

            val atom = record.fields["atom"] as? FunkAtom
                ?: throw FunkException("__native_atom_deref expects 'atom' field of type Atom")

            atom.ref.get()
        }
    }

    /**
     * __native_atom_reset: {atom: Atom 'a, value: 'a} -> 'a
     * Sets the atom to a new value, returns the new value.
     */
    private fun registerReset(context: MutableMap<String, FunkValue>) {
        context["__native_atom_reset"] = NativeFunkFunction("__native_atom_reset") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw FunkException("__native_atom_reset expects FunkRecord")

            val atom = record.fields["atom"] as? FunkAtom
                ?: throw FunkException("__native_atom_reset expects 'atom' field of type Atom")

            val value = record.fields["value"]
                ?: throw FunkException("__native_atom_reset expects 'value' field")

            atom.ref.set(value)
            value
        }
    }

    /**
     * __native_atom_swap: {atom: Atom 'a, f: 'a -> 'a} -> 'a
     * Atomically applies function f to the atom's value using CAS retry loop.
     * Returns the new value after successful update.
     *
     * WARNING: f may be called multiple times if there's contention!
     */
    private fun registerSwap(context: MutableMap<String, FunkValue>) {
        context["__native_atom_swap"] = NativeFunkFunction("__native_atom_swap") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw FunkException("__native_atom_swap expects FunkRecord")

            val atom = record.fields["atom"] as? FunkAtom
                ?: throw FunkException("__native_atom_swap expects 'atom' field of type Atom")

            val f = record.fields["f"]
                ?: throw FunkException("__native_atom_swap expects 'f' field")

            // CAS retry loop
            var result: FunkValue
            while (true) {
                val current = atom.ref.get()
                val newValue = callFunction(f, current)

                if (atom.ref.compareAndSet(current, newValue)) {
                    result = newValue
                    break
                }
                // Value changed, retry with new current value
            }
            result
        }
    }

    /**
     * __native_atom_swap_with: {atom: Atom 'a, f: {'a, 'b} -> 'a, arg: 'b} -> 'a
     * Like swap but passes an additional argument to the function.
     */
    private fun registerSwapWith(context: MutableMap<String, FunkValue>) {
        context["__native_atom_swap_with"] = NativeFunkFunction("__native_atom_swap_with") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw FunkException("__native_atom_swap_with expects FunkRecord")

            val atom = record.fields["atom"] as? FunkAtom
                ?: throw FunkException("__native_atom_swap_with expects 'atom' field of type Atom")

            val f = record.fields["f"]
                ?: throw FunkException("__native_atom_swap_with expects 'f' field")

            val arg = record.fields["arg"]
                ?: throw FunkException("__native_atom_swap_with expects 'arg' field")

            // CAS retry loop - runs until CAS succeeds
            var current = atom.ref.get()
            var newValue = callFunction2(f, current, arg)
            while (!atom.ref.compareAndSet(current, newValue)) {
                // Value changed, retry with new current value
                current = atom.ref.get()
                newValue = callFunction2(f, current, arg)
            }
            newValue
        }
    }

    /**
     * __native_atom_cas: {atom: Atom 'a, expected: 'a, new: 'a} -> Bool
     * Low-level compare-and-set. Returns true if successful.
     */
    private fun registerCas(context: MutableMap<String, FunkValue>) {
        context["__native_atom_cas"] = NativeFunkFunction("__native_atom_cas") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw FunkException("__native_atom_cas expects FunkRecord")

            val atom = record.fields["atom"] as? FunkAtom
                ?: throw FunkException("__native_atom_cas expects 'atom' field of type Atom")

            val expected = record.fields["expected"]
                ?: throw FunkException("__native_atom_cas expects 'expected' field")

            val new = record.fields["new"]
                ?: throw FunkException("__native_atom_cas expects 'new' field")

            FunkBool(atom.ref.compareAndSet(expected, new))
        }
    }

    /**
     * __native_atom_get_and_swap: {atom: Atom 'a, f: 'a -> 'a} -> {old: 'a, new: 'a}
     * Like swap but returns both old and new values.
     */
    private fun registerGetAndSwap(context: MutableMap<String, FunkValue>) {
        context["__native_atom_get_and_swap"] = NativeFunkFunction("__native_atom_get_and_swap") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw FunkException("__native_atom_get_and_swap expects FunkRecord")

            val atom = record.fields["atom"] as? FunkAtom
                ?: throw FunkException("__native_atom_get_and_swap expects 'atom' field of type Atom")

            val f = record.fields["f"]
                ?: throw FunkException("__native_atom_get_and_swap expects 'f' field")

            // CAS retry loop - runs until CAS succeeds
            var current = atom.ref.get()
            var newValue = callFunction(f, current)
            while (!atom.ref.compareAndSet(current, newValue)) {
                // Value changed, retry with new current value
                current = atom.ref.get()
                newValue = callFunction(f, current)
            }
            FunkRecord(mapOf(
                "old" to current,
                "new" to newValue
            ))
        }
    }

    /**
     * Helper to call a Funk function with one argument.
     */
    private fun callFunction(f: FunkValue, arg: FunkValue): FunkValue {
        return when (f) {
            is FunkTruffleFunction -> {
                f.callTarget.call(
                    arg,
                    f.closureVariables,
                    f.lexicalScope,
                    f.lexicalScopeSlotMap
                ) as FunkValue
            }
            is NativeFunkFunction -> {
                f.call(listOf(arg))
            }
            else -> throw FunkException("Expected function, got: ${f::class.simpleName}")
        }
    }

    /**
     * Helper to call a Funk function with two arguments (as a record).
     */
    private fun callFunction2(f: FunkValue, arg1: FunkValue, arg2: FunkValue): FunkValue {
        // Funk functions take a single record argument, so we wrap both args
        val argsRecord = FunkRecord(mapOf("current" to arg1, "arg" to arg2))
        return when (f) {
            is FunkTruffleFunction -> {
                f.callTarget.call(
                    argsRecord,
                    f.closureVariables,
                    f.lexicalScope,
                    f.lexicalScopeSlotMap
                ) as FunkValue
            }
            is NativeFunkFunction -> {
                f.call(listOf(argsRecord))
            }
            else -> throw FunkException("Expected function, got: ${f::class.simpleName}")
        }
    }
}

/**
 * FunkAtom - wraps an AtomicReference for Clojure-style atomic state.
 */
data class FunkAtom(
    val ref: AtomicReference<FunkValue>
) : FunkValue() {
    override fun getDisplayString(): String = "<atom:${ref.get().getDisplayString()}>"
}
