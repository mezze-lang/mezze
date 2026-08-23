package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

class AssocTypeProbeTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun tc(src: String): TypeCheckResult =
        typeChecker.typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."))

    private fun err(src: String): String? =
        try { tc(src); null } catch (e: TypeCheckError) { e.message } catch (e: Exception) { "EX:" + e.message }

    // PROBE 1: associated type resolved via receiver, then flows into a HOF callback param.
    @Test fun probe1_assoc_into_hof_callback() {
        val e = err("""
            ability Container for 'c having 'item where {
                get: {it: 'c, index: Int} -> 'item,
            }
            impl Container for [Int] having 'item = Int where {
                get = {it, index} -> 0,
            }
            let arr: [Int] = [1, 2, 3]
            let apply2 = {f: {x: Int} -> Int, v: Int} -> f {x = v}
            let r = apply2 {f = {x} -> x, v = arr.get {index = 0}}
        """)
        println("PROBE1 (assoc into hof callback, should be null): $e")
    }

    // PROBE 1b: pass associated-typed value where a WRONG concrete type is expected -> should error.
    @Test fun probe1b_assoc_wrong_type_in_call() {
        val e = err("""
            ability Container for 'c having 'item where {
                get: {it: 'c, index: Int} -> 'item,
            }
            impl Container for [Int] having 'item = Int where {
                get = {it, index} -> 0,
            }
            let arr: [Int] = [1, 2, 3]
            let needStr = {s: Str} -> s
            let r = needStr {s = arr.get {index = 0}}
        """)
        println("PROBE1b (assoc Int passed to Str param, SHOULD error): $e")
    }

    // PROBE 2: associated type used as the receiver of ANOTHER ability method (projection chain).
    @Test fun probe2_assoc_as_receiver_of_other_ability() {
        val e = err("""
            ability Show for 't where { show: {it: 't} -> Str }
            impl Show for Int where { show = {it} -> "n" }
            ability Container for 'c having 'item where {
                get: {it: 'c, index: Int} -> 'item,
            }
            impl Container for [Int] having 'item = Int where {
                get = {it, index} -> 0,
            }
            let arr: [Int] = [1, 2, 3]
            let s = (arr.get {index = 0}).show {}
        """)
        println("PROBE2 (assoc value.show{}, should be null): $e")
    }

    // PROBE 3: multi-param `having` where associated type depends on a NON-receiver class arg.
    @Test fun probe3_multiparam_assoc() {
        val e = err("""
            ability Convert 'to for 'from having 'out where {
                conv: {it: 'from} -> 'out,
            }
            impl Convert Str for Int having 'out = Str where {
                conv = {it} -> "s",
            }
            let x: Int = 5
            let r = x.conv {}
        """)
        println("PROBE3 (multi-param class-arg having, should be null): $e")
    }

    // PROBE 4: associated type appears in an EFFECT ROW of an op signature.
    @Test fun probe4_assoc_in_effect_row() {
        val e = err("""
            effect Yielder 'a { yield: {it: 'a} -> {} }
            ability Streamable for 's having 'elem where {
                run: {it: 's} -> {} <Yielder 'elem>,
            }
            impl Streamable for [Int] having 'elem = Int where {
                run = {it} -> {},
            }
        """)
        println("PROBE4 (assoc in effect row of op sig, should be null): $e")
    }

    // PROBE 5: nested associated projection - 'item is itself a container with its own 'item.
    @Test fun probe5_nested_assoc_projection() {
        val e = err("""
            ability Container for 'c having 'item where {
                get: {it: 'c, index: Int} -> 'item,
            }
            impl Container for [Int] having 'item = Int where {
                get = {it, index} -> 0,
            }
            impl Container for [[Int]] having 'item = [Int] where {
                get = {it, index} -> [0],
            }
            let arr: [[Int]] = [[1], [2]]
            let inner = arr.get {index = 0}
            let n = inner.get {index = 0}
        """)
        val res = try { tc("""
            ability Container for 'c having 'item where {
                get: {it: 'c, index: Int} -> 'item,
            }
            impl Container for [Int] having 'item = Int where {
                get = {it, index} -> 0,
            }
            impl Container for [[Int]] having 'item = [Int] where {
                get = {it, index} -> [0],
            }
            let arr: [[Int]] = [[1], [2]]
            let inner = arr.get {index = 0}
            let n = inner.get {index = 0}
        """) } catch (ex: Exception) { null }
        println("PROBE5 (nested assoc projection chain, err should be null): $e")
        println("PROBE5 inner type = ${res?.getVariableType("inner")}, n type = ${res?.getVariableType("n")}")
    }

    // PROBE 6: generic function that itself constrains 'c with Container and returns the assoc type.
    @Test fun probe6_generic_fn_returns_assoc() {
        val res = try { tc("""
            ability Container for 'c having 'item where {
                get: {it: 'c, index: Int} -> 'item,
            }
            impl Container for [Int] having 'item = Int where {
                get = {it, index} -> 0,
            }
            let first = {c: 'c} -> c.get {index = 0}
            let arr: [Int] = [1,2,3]
            let r = first {c = arr}
        """) } catch (ex: Exception) { println("PROBE6 EX: ${ex.message}"); null }
        println("PROBE6 first type = ${res?.getVariableType("first")}")
        println("PROBE6 r type = ${res?.getVariableType("r")} (expect Int)")
    }
}
