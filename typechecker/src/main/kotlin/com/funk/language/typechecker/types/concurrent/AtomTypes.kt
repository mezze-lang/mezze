package com.funk.language.typechecker.types.concurrent

import com.funk.language.ast.FunkType
import com.funk.language.ast.TypeVar

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
    fun registerAll(context: MutableMap<String, FunkType>) {
        // __native_atom_new: {value: 'a} -> Atom 'a
        run {
            val elementType = FunkType.VarType(TypeVar("a"))
            val atomType = FunkType.TypeApplication("Atom", listOf(elementType))
            context["__native_atom_new"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "value" to elementType
                )),
                atomType
            )
        }

        // __native_atom_deref: {atom: Atom 'a} -> 'a
        run {
            val elementType = FunkType.VarType(TypeVar("a"))
            val atomType = FunkType.TypeApplication("Atom", listOf(elementType))
            context["__native_atom_deref"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "atom" to atomType
                )),
                elementType
            )
        }

        // __native_atom_reset: {atom: Atom 'a, value: 'a} -> 'a
        run {
            val elementType = FunkType.VarType(TypeVar("a"))
            val atomType = FunkType.TypeApplication("Atom", listOf(elementType))
            context["__native_atom_reset"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "atom" to atomType,
                    "value" to elementType
                )),
                elementType
            )
        }

        // __native_atom_swap: {atom: Atom 'a, f: {current: 'a} -> 'a} -> 'a
        run {
            val elementType = FunkType.VarType(TypeVar("a"))
            val atomType = FunkType.TypeApplication("Atom", listOf(elementType))
            val funcType = FunkType.FunctionType(
                FunkType.RecordType(mapOf("current" to elementType)),
                elementType
            )
            context["__native_atom_swap"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "atom" to atomType,
                    "f" to funcType
                )),
                elementType
            )
        }

        // __native_atom_swap_with: {atom: Atom 'a, f: {current: 'a, arg: 'b} -> 'a, arg: 'b} -> 'a
        run {
            val elementType = FunkType.VarType(TypeVar("a"))
            val argType = FunkType.VarType(TypeVar("b"))
            val atomType = FunkType.TypeApplication("Atom", listOf(elementType))
            val funcType = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "current" to elementType,
                    "arg" to argType
                )),
                elementType
            )
            context["__native_atom_swap_with"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "atom" to atomType,
                    "f" to funcType,
                    "arg" to argType
                )),
                elementType
            )
        }

        // __native_atom_cas: {atom: Atom 'a, expected: 'a, new: 'a} -> Bool
        run {
            val elementType = FunkType.VarType(TypeVar("a"))
            val atomType = FunkType.TypeApplication("Atom", listOf(elementType))
            context["__native_atom_cas"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "atom" to atomType,
                    "expected" to elementType,
                    "new" to elementType
                )),
                FunkType.BooleanType
            )
        }

        // __native_atom_get_and_swap: {atom: Atom 'a, f: {current: 'a} -> 'a} -> {old: 'a, new: 'a}
        run {
            val elementType = FunkType.VarType(TypeVar("a"))
            val atomType = FunkType.TypeApplication("Atom", listOf(elementType))
            val funcType = FunkType.FunctionType(
                FunkType.RecordType(mapOf("current" to elementType)),
                elementType
            )
            context["__native_atom_get_and_swap"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "atom" to atomType,
                    "f" to funcType
                )),
                FunkType.RecordType(mapOf(
                    "old" to elementType,
                    "new" to elementType
                ))
            )
        }

        // __native_atom_add_watch: {atom: Atom 'a, key: String, f: {key: String, old: 'a, new: 'a} -> {}} -> {}
        run {
            val elementType = FunkType.VarType(TypeVar("a"))
            val atomType = FunkType.TypeApplication("Atom", listOf(elementType))
            val watchFuncType = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "key" to FunkType.StringType,
                    "old" to elementType,
                    "new" to elementType
                )),
                FunkType.RecordType(emptyMap())  // Unit
            )
            context["__native_atom_add_watch"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "atom" to atomType,
                    "key" to FunkType.StringType,
                    "f" to watchFuncType
                )),
                FunkType.RecordType(emptyMap())  // Unit
            )
        }

        // __native_atom_remove_watch: {atom: Atom 'a, key: String} -> {}
        run {
            val elementType = FunkType.VarType(TypeVar("a"))
            val atomType = FunkType.TypeApplication("Atom", listOf(elementType))
            context["__native_atom_remove_watch"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "atom" to atomType,
                    "key" to FunkType.StringType
                )),
                FunkType.RecordType(emptyMap())  // Unit
            )
        }

        // __native_atom_new_validated: {value: 'a, validator: {val: 'a} -> Bool} -> Atom 'a
        run {
            val elementType = FunkType.VarType(TypeVar("a"))
            val atomType = FunkType.TypeApplication("Atom", listOf(elementType))
            val validatorType = FunkType.FunctionType(
                FunkType.RecordType(mapOf("val" to elementType)),
                FunkType.BooleanType
            )
            context["__native_atom_new_validated"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "value" to elementType,
                    "validator" to validatorType
                )),
                atomType
            )
        }

        // __native_atom_set_validator: {atom: Atom 'a, validator: {val: 'a} -> Bool} -> {}
        run {
            val elementType = FunkType.VarType(TypeVar("a"))
            val atomType = FunkType.TypeApplication("Atom", listOf(elementType))
            val validatorType = FunkType.FunctionType(
                FunkType.RecordType(mapOf("val" to elementType)),
                FunkType.BooleanType
            )
            context["__native_atom_set_validator"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "atom" to atomType,
                    "validator" to validatorType
                )),
                FunkType.RecordType(emptyMap())  // Unit
            )
        }

        // __native_atom_get_validator: {atom: Atom 'a} -> Option ({val: 'a} -> Bool)
        // Note: We can't express the full Option type here, so we use a generic return
        run {
            val elementType = FunkType.VarType(TypeVar("a"))
            val atomType = FunkType.TypeApplication("Atom", listOf(elementType))
            val validatorType = FunkType.FunctionType(
                FunkType.RecordType(mapOf("val" to elementType)),
                FunkType.BooleanType
            )
            val optionType = FunkType.TypeApplication("Option", listOf(validatorType))
            context["__native_atom_get_validator"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "atom" to atomType
                )),
                optionType
            )
        }
    }
}
