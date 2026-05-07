package com.mezze.language.typechecker.types.concurrent

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar

/**
 * Type definitions for Clojure-style atomic reference operations.
 *
 * Atoms provide thread-safe, lock-free state management using compare-and-swap.
 *
 * IMPORTANT: Each function gets its own fresh type variables!
 * This is critical because these types are registered as polymorphic and get
 * fresh instantiation on each use.
 */
object AtomTypes {

    /**
     * Register all atom native function types.
     */
    fun registerAll(context: MutableMap<String, MezzeType>) {
        // __native_atom_new: {value: 'a} -> Atom 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val atomType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("Atom", listOf(elementType))
            context["__native_atom_new"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "value" to elementType
                )),
                atomType
            )
        }

        // __native_atom_deref: {atom: Atom 'a} -> 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val atomType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("Atom", listOf(elementType))
            context["__native_atom_deref"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "atom" to atomType
                )),
                elementType
            )
        }

        // __native_atom_reset: {atom: Atom 'a, value: 'a} -> 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val atomType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("Atom", listOf(elementType))
            context["__native_atom_reset"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "atom" to atomType,
                    "value" to elementType
                )),
                elementType
            )
        }

        // __native_atom_swap: {atom: Atom 'a, f: {current: 'a} -> 'a} -> 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val atomType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("Atom", listOf(elementType))
            val funcType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("current" to elementType)),
                elementType
            )
            context["__native_atom_swap"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "atom" to atomType,
                    "f" to funcType
                )),
                elementType
            )
        }

        // __native_atom_swap_with: {atom: Atom 'a, f: {current: 'a, arg: 'b} -> 'a, arg: 'b} -> 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val argType = MezzeType.VarType(TypeVar("b"))
            val atomType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("Atom", listOf(elementType))
            val funcType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "current" to elementType,
                    "arg" to argType
                )),
                elementType
            )
            context["__native_atom_swap_with"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "atom" to atomType,
                    "f" to funcType,
                    "arg" to argType
                )),
                elementType
            )
        }

        // __native_atom_cas: {atom: Atom 'a, expected: 'a, new: 'a} -> Bool
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val atomType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("Atom", listOf(elementType))
            context["__native_atom_cas"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "atom" to atomType,
                    "expected" to elementType,
                    "new" to elementType
                )),
                MezzeType.BoolType
            )
        }

        // __native_atom_get_and_swap: {atom: Atom 'a, f: {current: 'a} -> 'a} -> {old: 'a, new: 'a}
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val atomType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("Atom", listOf(elementType))
            val funcType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("current" to elementType)),
                elementType
            )
            context["__native_atom_get_and_swap"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "atom" to atomType,
                    "f" to funcType
                )),
                MezzeType.RecordType(mapOf(
                    "old" to elementType,
                    "new" to elementType
                ))
            )
        }

        // __native_atom_add_watch: {atom: Atom 'a, key: String, f: {key: String, old: 'a, new: 'a} -> {}} -> {}
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val atomType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("Atom", listOf(elementType))
            val watchFuncType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "key" to MezzeType.StringType,
                    "old" to elementType,
                    "new" to elementType
                )),
                MezzeType.RecordType(emptyMap())  // Unit
            )
            context["__native_atom_add_watch"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "atom" to atomType,
                    "key" to MezzeType.StringType,
                    "f" to watchFuncType
                )),
                MezzeType.RecordType(emptyMap())  // Unit
            )
        }

        // __native_atom_remove_watch: {atom: Atom 'a, key: String} -> {}
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val atomType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("Atom", listOf(elementType))
            context["__native_atom_remove_watch"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "atom" to atomType,
                    "key" to MezzeType.StringType
                )),
                MezzeType.RecordType(emptyMap())  // Unit
            )
        }

        // __native_atom_new_validated: {value: 'a, validator: {val: 'a} -> Bool} -> Atom 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val atomType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("Atom", listOf(elementType))
            val validatorType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("val" to elementType)),
                MezzeType.BoolType
            )
            context["__native_atom_new_validated"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "value" to elementType,
                    "validator" to validatorType
                )),
                atomType
            )
        }

        // __native_atom_set_validator: {atom: Atom 'a, validator: {val: 'a} -> Bool} -> {}
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val atomType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("Atom", listOf(elementType))
            val validatorType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("val" to elementType)),
                MezzeType.BoolType
            )
            context["__native_atom_set_validator"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "atom" to atomType,
                    "validator" to validatorType
                )),
                MezzeType.RecordType(emptyMap())  // Unit
            )
        }

        // __native_atom_get_validator: {atom: Atom 'a} -> Option ({val: 'a} -> Bool)
        // Note: We can't express the full Option type here, so we use a generic return
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val atomType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("Atom", listOf(elementType))
            val validatorType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("val" to elementType)),
                MezzeType.BoolType
            )
            val optionType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("Option", listOf(validatorType))
            context["__native_atom_get_validator"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "atom" to atomType
                )),
                optionType
            )
        }
    }
}
