package com.mezze.language.typechecker.module

import com.mezze.language.ast.prettyPrintType
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.Lowering
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.typedast.TypedLetDecl
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Checking a program's modules ahead, in parallel, changes nothing about the
 * result: every module's types, and every error, are what checking them one
 * at a time gives.
 */
class ParallelModuleCheckerTest {

    @TempDir
    lateinit var project: Path

    @BeforeEach
    fun setUp() {
        TypeChecker.clearCache()
        Lowering.enabled = false
        // A diamond and a chain: `top` needs `left` and `right`, both need
        // `base`; `leaf` stands alone. Polymorphism, abilities and records
        // cross every edge.
        project.resolve("base.mz").writeText(
            """
            use std::effects::log::{ println }
            pub type Pair 'a = { first: 'a, second: 'a }
            pub let pair = { a } -> { first = a, second = a }
            pub let swap: { p: Pair 'a } -> Pair 'a = { p } -> { first = p.second, second = p.first }
            pub let shout = { msg } -> println { msg = msg }
            """.trimIndent(),
        )
        project.resolve("left.mz").writeText(
            """
            use base::{ Pair, pair, swap }
            pub let lefty = { n: Int } -> swap { p = pair { a = n } }
            pub let twice = { f, x } -> f { x = f { x = x } }
            """.trimIndent(),
        )
        project.resolve("right.mz").writeText(
            """
            use base::{ pair, shout }
            pub let righty = { s: Str } -> pair { a = s }
            pub let loud = { s: Str } -> shout { msg = s }
            """.trimIndent(),
        )
        project.resolve("top.mz").writeText(
            """
            use left::{ lefty, twice }
            use right::{ righty, loud }
            pub let both = { n: Int, s: Str } -> { l = lefty { n = n }, r = righty { s = s } }
            pub let inc4 = { n: Int } -> twice { f = { x } -> x + 2, x = n }
            """.trimIndent(),
        )
        project.resolve("leaf.mz").writeText(
            """
            pub let id = { x } -> x
            pub let size = { xs: [Int] } -> 3
            """.trimIndent(),
        )
    }

    @AfterEach
    fun tearDown() {
        ParallelModuleChecker.threads = 0
        Lowering.enabled = true
        TypeChecker.clearCache()
    }

    private val app = """
        use top::{ both, inc4 }
        use leaf::{ id, size }
        use right::{ loud }

        let a = both { n = 1, s = "x" }
        let b = inc4 { n = 3 }
        let c = id { x = size { xs = [1, 2] } }
        let d = loud
    """.trimIndent()

    /** Every module's declarations' types, and the program's, after checking with [threads]. */
    private fun check(threads: Int, source: String = app): Pair<Map<String, String>, Any> {
        TypeChecker.clearCache()
        ParallelModuleChecker.threads = threads
        val parsed = MezzeParser().parseParsedModule(source, "app.mz")
        val result = try {
            TypeChecker().checkParsedModule(parsed, project, enablePrelude = true)
        } catch (e: ModuleError) {
            return emptyMap<String, String>() to "${e::class.simpleName}: ${e.message}"
        }
        if (result !is TypeChecker.TypeCheckResult.Success) return emptyMap<String, String>() to result.toString()
        val types = HashMap<String, String>()
        val modules = ModuleTypeChecker.getAllCachedModules().filterKeys { !it.startsWith("std::") }
        for ((path, m) in modules) {
            for (d in m.typedModule!!.decls.filterIsInstance<TypedLetDecl>()) types["$path.${d.name}"] = prettyPrintType(d.scheme.type)
        }
        for (d in result.typedModule!!.decls.filterIsInstance<TypedLetDecl>()) types["app.${d.name}"] = prettyPrintType(d.scheme.type)
        return types to "ok"
    }

    @Test
    fun `modules checked in parallel have the types they have checked one at a time`() {
        val (sequential, ok1) = check(threads = 1)
        val (parallel, ok2) = check(threads = 4)
        assertEquals(5, ParallelModuleChecker.lastCheckedAhead, "every project module was checked ahead")
        assertEquals("ok", ok1, "the program checks")
        assertEquals("ok", ok2)
        assertTrue(sequential.keys.any { it.startsWith("base.") } && sequential.keys.any { it.startsWith("top.") },
            "every module was checked: ${sequential.keys}")
        assertEquals(sequential, parallel)
    }

    @Test
    fun `a module that does not check is reported as it is when checked one at a time`() {
        project.resolve("left.mz").writeText(
            """
            use base::{ pair }
            pub let lefty = { n: Int } -> pair { a = n } + "nope"
            pub let twice = { f, x } -> f { x = f { x = x } }
            """.trimIndent(),
        )
        val (_, sequential) = check(threads = 1)
        val (_, parallel) = check(threads = 4)
        assertTrue(ParallelModuleChecker.lastCheckedAhead > 0, "the modules before the broken one were checked ahead")
        assertTrue(sequential != "ok", "the broken module is reported")
        assertEquals(sequential, parallel)
    }
}
