package com.mezze.language.interpreter.stdlib.concurrent

import com.mezze.language.interpreter.*
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
    fun registerAll(context: MutableMap<String, MezzeValue>) {
        registerNew(context)
        registerNewValidated(context)
        registerDeref(context)
        registerReset(context)
        registerSwap(context)
        registerSwapWith(context)
        registerCas(context)
        registerGetAndSwap(context)
        registerAddWatch(context)
        registerRemoveWatch(context)
        registerSetValidator(context)
        registerGetValidator(context)
    }

    /**
     * __native_atom_new: {value: 'a} -> Atom 'a
     * Creates a new atom with the given initial value.
     */
    private fun registerNew(context: MutableMap<String, MezzeValue>) {
        context["__native_atom_new"] = NativeMezzeFunction("__native_atom_new") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_atom_new expects MezzeRecord")

            val value = record.fields["value"]
                ?: throw MezzeException("__native_atom_new expects 'value' field")

            MezzeAtom(AtomicReference(value))
        }
    }

    /**
     * __native_atom_new_validated: {value: 'a, validator: {val: 'a} -> Bool} -> Atom 'a
     * Creates a new atom with the given initial value and validator.
     * Throws if the initial value doesn't pass the validator.
     */
    private fun registerNewValidated(context: MutableMap<String, MezzeValue>) {
        context["__native_atom_new_validated"] = NativeMezzeFunction("__native_atom_new_validated") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_atom_new_validated expects MezzeRecord")

            val value = record.fields["value"]
                ?: throw MezzeException("__native_atom_new_validated expects 'value' field")

            val validator = record.fields["validator"]
                ?: throw MezzeException("__native_atom_new_validated expects 'validator' field")

            // Validate the initial value
            if (!runValidator(validator, value)) {
                throw MezzeException("Atom validator rejected initial value")
            }

            val atom = MezzeAtom(AtomicReference(value))
            atom.validator.set(validator)
            atom
        }
    }

    /**
     * __native_atom_deref: {atom: Atom 'a} -> 'a
     * Reads the current value of the atom (non-blocking).
     */
    private fun registerDeref(context: MutableMap<String, MezzeValue>) {
        context["__native_atom_deref"] = NativeMezzeFunction("__native_atom_deref") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_atom_deref expects MezzeRecord")

            val atom = record.fields["atom"] as? MezzeAtom
                ?: throw MezzeException("__native_atom_deref expects 'atom' field of type Atom")

            atom.ref.get()
        }
    }

    /**
     * __native_atom_reset: {atom: Atom 'a, value: 'a} -> 'a
     * Sets the atom to a new value, returns the new value.
     * Validates the new value if a validator is set.
     * Notifies all watches of the change.
     */
    private fun registerReset(context: MutableMap<String, MezzeValue>) {
        context["__native_atom_reset"] = NativeMezzeFunction("__native_atom_reset") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_atom_reset expects MezzeRecord")

            val atom = record.fields["atom"] as? MezzeAtom
                ?: throw MezzeException("__native_atom_reset expects 'atom' field of type Atom")

            val value = record.fields["value"]
                ?: throw MezzeException("__native_atom_reset expects 'value' field")

            // Validate before updating
            validateOrThrow(atom, value)

            val oldValue = atom.ref.getAndSet(value)
            notifyWatches(atom, oldValue, value)
            value
        }
    }

    /**
     * __native_atom_swap: {atom: Atom 'a, f: 'a -> 'a} -> 'a
     * Atomically applies function f to the atom's value using CAS retry loop.
     * Returns the new value after successful update.
     * Validates the new value if a validator is set (throws if invalid).
     * Notifies all watches of the change.
     *
     * WARNING: f may be called multiple times if there's contention!
     */
    private fun registerSwap(context: MutableMap<String, MezzeValue>) {
        context["__native_atom_swap"] = NativeMezzeFunction("__native_atom_swap") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_atom_swap expects MezzeRecord")

            val atom = record.fields["atom"] as? MezzeAtom
                ?: throw MezzeException("__native_atom_swap expects 'atom' field of type Atom")

            val f = record.fields["f"]
                ?: throw MezzeException("__native_atom_swap expects 'f' field")

            // CAS retry loop
            var oldValue: MezzeValue
            var result: MezzeValue
            while (true) {
                val current = atom.ref.get()
                val newValue = callFunction(f, current)

                // Validate before CAS
                validateOrThrow(atom, newValue)

                if (atom.ref.compareAndSet(current, newValue)) {
                    oldValue = current
                    result = newValue
                    break
                }
                // Value changed, retry with new current value
            }
            notifyWatches(atom, oldValue, result)
            result
        }
    }

    /**
     * __native_atom_swap_with: {atom: Atom 'a, f: {'a, 'b} -> 'a, arg: 'b} -> 'a
     * Like swap but passes an additional argument to the function.
     * Validates the new value if a validator is set (throws if invalid).
     * Notifies all watches of the change.
     */
    private fun registerSwapWith(context: MutableMap<String, MezzeValue>) {
        context["__native_atom_swap_with"] = NativeMezzeFunction("__native_atom_swap_with") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_atom_swap_with expects MezzeRecord")

            val atom = record.fields["atom"] as? MezzeAtom
                ?: throw MezzeException("__native_atom_swap_with expects 'atom' field of type Atom")

            val f = record.fields["f"]
                ?: throw MezzeException("__native_atom_swap_with expects 'f' field")

            val arg = record.fields["arg"]
                ?: throw MezzeException("__native_atom_swap_with expects 'arg' field")

            // CAS retry loop - runs until CAS succeeds
            var oldValue: MezzeValue
            var current = atom.ref.get()
            var newValue = callFunction2(f, current, arg)

            // Validate before CAS
            validateOrThrow(atom, newValue)

            while (!atom.ref.compareAndSet(current, newValue)) {
                // Value changed, retry with new current value
                current = atom.ref.get()
                newValue = callFunction2(f, current, arg)
                // Re-validate on retry
                validateOrThrow(atom, newValue)
            }
            oldValue = current
            notifyWatches(atom, oldValue, newValue)
            newValue
        }
    }

    /**
     * __native_atom_cas: {atom: Atom 'a, expected: 'a, new: 'a} -> Bool
     * Low-level compare-and-set. Returns true if successful.
     * Validates the new value if a validator is set (throws if invalid).
     *
     * Note: Uses value equality (equals) not reference equality, since
     * MezzeInt(10) != MezzeInt(10) by reference but should be equal by value.
     */
    private fun registerCas(context: MutableMap<String, MezzeValue>) {
        context["__native_atom_cas"] = NativeMezzeFunction("__native_atom_cas") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_atom_cas expects MezzeRecord")

            val atom = record.fields["atom"] as? MezzeAtom
                ?: throw MezzeException("__native_atom_cas expects 'atom' field of type Atom")

            val expected = record.fields["expected"]
                ?: throw MezzeException("__native_atom_cas expects 'expected' field")

            val newValue = record.fields["new"]
                ?: throw MezzeException("__native_atom_cas expects 'new' field")

            // Validate before CAS
            validateOrThrow(atom, newValue)

            // CAS with value equality check instead of reference equality
            var result = false
            while (true) {
                val current = atom.ref.get()
                if (current != expected) {
                    // Value doesn't match expected, CAS fails
                    result = false
                    break
                }
                if (atom.ref.compareAndSet(current, newValue)) {
                    result = true
                    break
                }
                // Reference changed but might still equal expected, retry
            }
            MezzeBool(result)
        }
    }

    /**
     * __native_atom_get_and_swap: {atom: Atom 'a, f: 'a -> 'a} -> {old: 'a, new: 'a}
     * Like swap but returns both old and new values.
     * Validates the new value if a validator is set (throws if invalid).
     * Notifies all watches of the change.
     */
    private fun registerGetAndSwap(context: MutableMap<String, MezzeValue>) {
        context["__native_atom_get_and_swap"] = NativeMezzeFunction("__native_atom_get_and_swap") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_atom_get_and_swap expects MezzeRecord")

            val atom = record.fields["atom"] as? MezzeAtom
                ?: throw MezzeException("__native_atom_get_and_swap expects 'atom' field of type Atom")

            val f = record.fields["f"]
                ?: throw MezzeException("__native_atom_get_and_swap expects 'f' field")

            // CAS retry loop - runs until CAS succeeds
            var current = atom.ref.get()
            var newValue = callFunction(f, current)

            // Validate before CAS
            validateOrThrow(atom, newValue)

            while (!atom.ref.compareAndSet(current, newValue)) {
                // Value changed, retry with new current value
                current = atom.ref.get()
                newValue = callFunction(f, current)
                // Re-validate on retry
                validateOrThrow(atom, newValue)
            }
            notifyWatches(atom, current, newValue)
            MezzeRecord(mapOf(
                "old" to current,
                "new" to newValue
            ))
        }
    }

    /**
     * Helper to call a Mezze function with the current atom value.
     * Wraps the value in a record {current: value} as expected by Mezze function syntax.
     */
    private fun callFunction(f: MezzeValue, arg: MezzeValue): MezzeValue {
        // Mezze functions take a single record argument
        val argsRecord = MezzeRecord(mapOf("current" to arg))
        return when (f) {
            is MezzeTruffleFunction -> {
                f.callTarget.call(
                    argsRecord,
                    f.closureVariables,
                    f.lexicalScope,
                    f.lexicalScopeSlotMap
                ) as MezzeValue
            }
            is NativeMezzeFunction -> {
                f.call(listOf(argsRecord))
            }
            else -> throw MezzeException("Expected function, got: ${f::class.simpleName}")
        }
    }

    /**
     * Helper to call a Mezze function with two arguments (as a record).
     */
    private fun callFunction2(f: MezzeValue, arg1: MezzeValue, arg2: MezzeValue): MezzeValue {
        // Mezze functions take a single record argument, so we wrap both args
        val argsRecord = MezzeRecord(mapOf("current" to arg1, "arg" to arg2))
        return when (f) {
            is MezzeTruffleFunction -> {
                f.callTarget.call(
                    argsRecord,
                    f.closureVariables,
                    f.lexicalScope,
                    f.lexicalScopeSlotMap
                ) as MezzeValue
            }
            is NativeMezzeFunction -> {
                f.call(listOf(argsRecord))
            }
            else -> throw MezzeException("Expected function, got: ${f::class.simpleName}")
        }
    }

    /**
     * __native_atom_add_watch: {atom: Atom 'a, key: String, f: {key: String, old: 'a, new: 'a} -> {}} -> {}
     * Adds a watch function that will be called whenever the atom's value changes.
     */
    private fun registerAddWatch(context: MutableMap<String, MezzeValue>) {
        context["__native_atom_add_watch"] = NativeMezzeFunction("__native_atom_add_watch") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_atom_add_watch expects MezzeRecord")

            val atom = record.fields["atom"] as? MezzeAtom
                ?: throw MezzeException("__native_atom_add_watch expects 'atom' field of type Atom")

            val key = (record.fields["key"] as? MezzeString)?.value
                ?: throw MezzeException("__native_atom_add_watch expects 'key' field of type String")

            val f = record.fields["f"]
                ?: throw MezzeException("__native_atom_add_watch expects 'f' field")

            atom.watches[key] = f
            MezzeUnit
        }
    }

    /**
     * __native_atom_remove_watch: {atom: Atom 'a, key: String} -> {}
     * Removes the watch with the given key.
     */
    private fun registerRemoveWatch(context: MutableMap<String, MezzeValue>) {
        context["__native_atom_remove_watch"] = NativeMezzeFunction("__native_atom_remove_watch") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_atom_remove_watch expects MezzeRecord")

            val atom = record.fields["atom"] as? MezzeAtom
                ?: throw MezzeException("__native_atom_remove_watch expects 'atom' field of type Atom")

            val key = (record.fields["key"] as? MezzeString)?.value
                ?: throw MezzeException("__native_atom_remove_watch expects 'key' field of type String")

            atom.watches.remove(key)
            MezzeUnit
        }
    }

    /**
     * Helper to notify all watches of a value change.
     */
    private fun notifyWatches(atom: MezzeAtom, oldValue: MezzeValue, newValue: MezzeValue) {
        if (atom.watches.isEmpty()) return

        for ((key, watchFn) in atom.watches) {
            try {
                val argsRecord = MezzeRecord(mapOf(
                    "key" to MezzeString(key),
                    "old" to oldValue,
                    "new" to newValue
                ))
                when (watchFn) {
                    is MezzeTruffleFunction -> {
                        watchFn.callTarget.call(
                            argsRecord,
                            watchFn.closureVariables,
                            watchFn.lexicalScope,
                            watchFn.lexicalScopeSlotMap
                        )
                    }
                    is NativeMezzeFunction -> {
                        watchFn.call(listOf(argsRecord))
                    }
                }
            } catch (e: Exception) {
                // Watches should not throw, but if they do, we ignore it
                // to not break the atom operation
            }
        }
    }

    /**
     * __native_atom_set_validator: {atom: Atom 'a, validator: {val: 'a} -> Bool} -> {}
     * Sets a validator function on the atom.
     * Future updates will be rejected if the validator returns false.
     * Note: Does NOT validate the current value.
     */
    private fun registerSetValidator(context: MutableMap<String, MezzeValue>) {
        context["__native_atom_set_validator"] = NativeMezzeFunction("__native_atom_set_validator") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_atom_set_validator expects MezzeRecord")

            val atom = record.fields["atom"] as? MezzeAtom
                ?: throw MezzeException("__native_atom_set_validator expects 'atom' field of type Atom")

            val validator = record.fields["validator"]
                ?: throw MezzeException("__native_atom_set_validator expects 'validator' field")

            atom.validator.set(validator)
            MezzeUnit
        }
    }

    /**
     * __native_atom_get_validator: {atom: Atom 'a} -> Option ({val: 'a} -> Bool)
     * Returns the current validator if one is set, or None.
     */
    private fun registerGetValidator(context: MutableMap<String, MezzeValue>) {
        context["__native_atom_get_validator"] = NativeMezzeFunction("__native_atom_get_validator") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_atom_get_validator expects MezzeRecord")

            val atom = record.fields["atom"] as? MezzeAtom
                ?: throw MezzeException("__native_atom_get_validator expects 'atom' field of type Atom")

            val validator = atom.validator.get()
            if (validator != null) {
                MezzeVariant("Some", MezzeRecord(mapOf("val" to validator)))
            } else {
                MezzeVariant("None", MezzeUnit)
            }
        }
    }

    /**
     * Helper to run a validator function on a value.
     * Returns true if valid, false otherwise.
     */
    private fun runValidator(validator: MezzeValue, value: MezzeValue): Boolean {
        val argsRecord = MezzeRecord(mapOf("val" to value))
        val result = when (validator) {
            is MezzeTruffleFunction -> {
                validator.callTarget.call(
                    argsRecord,
                    validator.closureVariables,
                    validator.lexicalScope,
                    validator.lexicalScopeSlotMap
                ) as MezzeValue
            }
            is NativeMezzeFunction -> {
                validator.call(listOf(argsRecord))
            }
            else -> throw MezzeException("Validator must be a function")
        }
        return (result as? MezzeBool)?.value
            ?: throw MezzeException("Validator must return Bool, got: ${result::class.simpleName}")
    }

    /**
     * Helper to validate a new value against the atom's validator (if set).
     * Throws MezzeException if validation fails.
     */
    private fun validateOrThrow(atom: MezzeAtom, newValue: MezzeValue) {
        val validator = atom.validator.get()
        if (validator != null && !runValidator(validator, newValue)) {
            throw MezzeException("Atom validator rejected value")
        }
    }
}

/**
 * MezzeAtom - wraps an AtomicReference for Clojure-style atomic state.
 * Now includes support for watches (callbacks on state change) and validators.
 */
data class MezzeAtom(
    val ref: AtomicReference<MezzeValue>,
    val watches: java.util.concurrent.ConcurrentHashMap<String, MezzeValue> = java.util.concurrent.ConcurrentHashMap(),
    val validator: AtomicReference<MezzeValue?> = AtomicReference(null)
) : MezzeValue() {
    override fun getDisplayString(): String = "<atom:${ref.get().getDisplayString()}>"
}
