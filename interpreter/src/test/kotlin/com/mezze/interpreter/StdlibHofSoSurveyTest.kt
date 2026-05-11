package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * Stress each stdlib HOF at N=2000 and N=10000 to find which (if any)
 * still recurse and StackOverflow. Each test reports its own status; failures
 * are recorded in a single output file rather than failing the test.
 */
class StdlibHofSoSurveyTest {

    private fun probe(name: String, code: String): String = try {
        val v = MezzeTestHelper.run(code)
        "OK  $name -> $v"
    } catch (t: Throwable) {
        val root = generateSequence(t) { it.cause }.last()
        "ERR $name: ${root::class.simpleName}: ${root.message?.take(120)}"
    }

    @Test
    @Timeout(900)
    fun `survey - all stdlib HOFs at N=50000`() {
        val n = 50000
        val results = listOf(
            probe("seq.map", """
                use std::collections::seq
                let build = {i: Int, n: Int, acc: [Int]} ->
                  if i == n then acc else build {i = i + 1, n = n, acc = seq::push {xs = acc, x = i}}
                let s = build {i = 0, n = $n, acc = []}
                let r = seq::map {xs = s, f = {x} -> x + 1}
                seq::size {xs = r}
            """.trimIndent()),

            probe("seq.filter", """
                use std::collections::seq
                let build = {i: Int, n: Int, acc: [Int]} ->
                  if i == n then acc else build {i = i + 1, n = n, acc = seq::push {xs = acc, x = i}}
                let s = build {i = 0, n = $n, acc = []}
                let r = seq::filter {xs = s, p = {x} -> True}
                seq::size {xs = r}
            """.trimIndent()),

            probe("seq.fold", """
                use std::collections::seq
                let build = {i: Int, n: Int, acc: [Int]} ->
                  if i == n then acc else build {i = i + 1, n = n, acc = seq::push {xs = acc, x = i}}
                let s = build {i = 0, n = $n, acc = []}
                seq::fold {xs = s, init = 0, f = {acc, x} -> acc + x}
            """.trimIndent()),

            probe("list.map", """
                use std::collections::list
                let build = {i: Int, n: Int, acc: List Int} ->
                  if i == n then acc else build {i = i + 1, n = n, acc = list::cons {head = i, tail = acc}}
                let l = build {i = 0, n = $n, acc = list::empty {}}
                let r = list::map {xs = l, f = {x} -> x + 1}
                list::size {xs = r}
            """.trimIndent()),

            probe("list.filter", """
                use std::collections::list
                let build = {i: Int, n: Int, acc: List Int} ->
                  if i == n then acc else build {i = i + 1, n = n, acc = list::cons {head = i, tail = acc}}
                let l = build {i = 0, n = $n, acc = list::empty {}}
                let r = list::filter {xs = l, p = {x} -> True}
                list::size {xs = r}
            """.trimIndent()),

            probe("list.fold_left", """
                use std::collections::list
                let build = {i: Int, n: Int, acc: List Int} ->
                  if i == n then acc else build {i = i + 1, n = n, acc = list::cons {head = i, tail = acc}}
                let l = build {i = 0, n = $n, acc = list::empty {}}
                list::fold_left {xs = l, init = 0, f = {acc, x} -> acc + x}
            """.trimIndent()),

            probe("list.fold_right", """
                use std::collections::list
                let build = {i: Int, n: Int, acc: List Int} ->
                  if i == n then acc else build {i = i + 1, n = n, acc = list::cons {head = i, tail = acc}}
                let l = build {i = 0, n = $n, acc = list::empty {}}
                list::fold_right {xs = l, init = 0, f = {acc, x} -> acc + x}
            """.trimIndent()),

            probe("map.fold", """
                use std::collections::map
                let build = {i: Int, n: Int, acc: Map Int Int} ->
                  if i == n then acc else build {i = i + 1, n = n, acc = map::insert {m = acc, k = i, v = i}}
                let m = build {i = 0, n = $n, acc = map::empty {}}
                map::fold {m = m, init = 0, f = {acc, k, v} -> acc + v}
            """.trimIndent()),

            probe("set.fold", """
                use std::collections::set
                let build = {i: Int, n: Int, acc: Set Int} ->
                  if i == n then acc else build {i = i + 1, n = n, acc = set::insert {s = acc, x = i}}
                let s = build {i = 0, n = $n, acc = set::empty {}}
                set::fold {s = s, init = 0, f = {acc, x} -> acc + x}
            """.trimIndent()),
        )

        val out = java.io.File("/tmp/stdlib_hof_so_survey_N${n}.log")
        out.writeText(results.joinToString("\n"))
        println("=== SURVEY N=$n ===")
        results.forEach { println("  $it") }
        println("Saved to: ${out.absolutePath}")
    }
}
