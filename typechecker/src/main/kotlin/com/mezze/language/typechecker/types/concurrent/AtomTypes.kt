package com.mezze.language.typechecker.types.concurrent

import com.mezze.language.ast.MezzeType
import com.mezze.language.typechecker.types.*
import com.mezze.language.typechecker.util.WellKnownTypeApp

/**
 * Type definitions for Clojure-style atomic reference operations.
 *
 * Every native carries `<Async>` because `Atom 'a` is an Async-family
 * primitive — its construction and every operation observes or mutates
 * hidden state. See #170 for the design.
 *
 * IMPORTANT: Each function gets its own fresh type variables!
 * This is critical because these types are registered as polymorphic and get
 * fresh instantiation on each use.
 */
object AtomTypes {

    fun registerAll(context: MutableMap<String, MezzeType>) = natives(context) {
        // __native_atom_new: {val: 'a} -> Atom 'a <Async>
        fn("__native_atom_new") {
            val a = tvar("a")
            param("val" to a)
            returns(WellKnownTypeApp.of("Atom", listOf(a)))
            effects("Async")
        }

        // __native_atom_deref: {atom: Atom 'a} -> 'a <Async>
        fn("__native_atom_deref") {
            val a = tvar("a")
            param("atom" to WellKnownTypeApp.of("Atom", listOf(a)))
            returns(a)
            effects("Async")
        }

        // __native_atom_reset: {atom: Atom 'a, val: 'a} -> 'a <Async>
        fn("__native_atom_reset") {
            val a = tvar("a")
            param("atom" to WellKnownTypeApp.of("Atom", listOf(a)), "val" to a)
            returns(a)
            effects("Async")
        }

        // __native_atom_swap: {atom: Atom 'a, f: {current: 'a} -> 'a} -> 'a <Async>
        fn("__native_atom_swap") {
            val a = tvar("a")
            val atomType = WellKnownTypeApp.of("Atom", listOf(a))
            val funcType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("current" to a)),
                a
            )
            param("atom" to atomType, "f" to funcType)
            returns(a)
            effects("Async")
        }

        // __native_atom_swap_with: {atom: Atom 'a, f: {current: 'a, arg: 'b} -> 'a, arg: 'b} -> 'a <Async>
        fn("__native_atom_swap_with") {
            val a = tvar("a")
            val b = tvar("b")
            val atomType = WellKnownTypeApp.of("Atom", listOf(a))
            val funcType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("current" to a, "arg" to b)),
                a
            )
            param("atom" to atomType, "f" to funcType, "arg" to b)
            returns(a)
            effects("Async")
        }

        // __native_atom_cas: {atom: Atom 'a, expected: 'a, new: 'a} -> Bool <Async>
        fn("__native_atom_cas") {
            val a = tvar("a")
            param("atom" to WellKnownTypeApp.of("Atom", listOf(a)), "expected" to a, "new" to a)
            returns(bool)
            effects("Async")
        }

        // __native_atom_get_and_swap: {atom: Atom 'a, f: {current: 'a} -> 'a} -> {old: 'a, new: 'a} <Async>
        fn("__native_atom_get_and_swap") {
            val a = tvar("a")
            val atomType = WellKnownTypeApp.of("Atom", listOf(a))
            val funcType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("current" to a)),
                a
            )
            param("atom" to atomType, "f" to funcType)
            returns(rec("old" to a, "new" to a))
            effects("Async")
        }

        // __native_atom_add_watch: {atom: Atom 'a, key: String, f: {key: String, old: 'a, new: 'a} -> {}} -> {} <Async>
        fn("__native_atom_add_watch") {
            val a = tvar("a")
            val atomType = WellKnownTypeApp.of("Atom", listOf(a))
            val watchFuncType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("key" to str, "old" to a, "new" to a)),
                unit
            )
            param("atom" to atomType, "key" to str, "f" to watchFuncType)
            returns(unit)
            effects("Async")
        }

        // __native_atom_remove_watch: {atom: Atom 'a, key: String} -> {} <Async>
        fn("__native_atom_remove_watch") {
            val a = tvar("a")
            param("atom" to WellKnownTypeApp.of("Atom", listOf(a)), "key" to str)
            returns(unit)
            effects("Async")
        }

        // __native_atom_new_validated: {val: 'a, validator: {val: 'a} -> Bool} -> Atom 'a <Async>
        fn("__native_atom_new_validated") {
            val a = tvar("a")
            val validatorType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("val" to a)),
                bool
            )
            param("val" to a, "validator" to validatorType)
            returns(WellKnownTypeApp.of("Atom", listOf(a)))
            effects("Async")
        }

        // __native_atom_set_validator: {atom: Atom 'a, validator: {val: 'a} -> Bool} -> {} <Async>
        fn("__native_atom_set_validator") {
            val a = tvar("a")
            val validatorType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("val" to a)),
                bool
            )
            param("atom" to WellKnownTypeApp.of("Atom", listOf(a)), "validator" to validatorType)
            returns(unit)
            effects("Async")
        }

        // __native_atom_get_validator: {atom: Atom 'a} -> Option ({val: 'a} -> Bool) <Async>
        fn("__native_atom_get_validator") {
            val a = tvar("a")
            val validatorType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("val" to a)),
                bool
            )
            param("atom" to WellKnownTypeApp.of("Atom", listOf(a)))
            returns(WellKnownTypeApp.of("Option", listOf(validatorType)))
            effects("Async")
        }
    }
}
