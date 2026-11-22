package com.mezze.interpreter

import com.mezze.interpreter.runtime.MezzeLanguage
import com.mezze.language.parser.MezzeParser
import com.mezze.language.store.CodeHash
import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.TypeCheckResult
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.typechecker.module.ModuleTypeChecker
import com.mezze.language.typedast.Hash
import com.mezze.language.typedast.TypedLetDecl
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.Source
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Hashes across MODULES, in a program that is really split into files and run.
 *
 * One compile collapses every function of every module by hash, first one wins.
 * So this is where a hash that stands for two functions stops being a filing
 * question and becomes the wrong answer: module `b`'s function runs module
 * `a`'s code. Each test is two or three files, the build's own type check and
 * lowering over them, and the value the program returns — and, where the point
 * is sharing rather than separation, the hashes themselves.
 */
class CrossModuleCodeTest {

    @TempDir lateinit var root: Path

    private fun write(module: String, body: String) {
        val file = root.resolve("src").resolve("$module.mz")
        Files.createDirectories(file.parent)
        Files.writeString(file, body.trimIndent())
    }

    private fun check(main: String): TypeCheckResult {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        ModuleTypeChecker.clearCache()
        ModuleTypeChecker.clearTypeCheckingStack()
        write("main", main)
        val parsed = MezzeParser().parseParsedModule(main.trimIndent(), root.resolve("src/main.mz").toString())
        return TypeChecker().typeCheck(parsed, root.resolve("src"), true, null)
    }

    /** Type-check against [root] and run the lowered program, the way a build does. */
    private fun run(main: String): Any? {
        val checked = check(main)
        val engine = Engine.newBuilder()
            .allowExperimentalOptions(true)
            .option("engine.WarnInterpreterOnly", "false")
            .option("engine.Compilation", "false")
            .build()
        val context = Context.newBuilder(MezzeLanguage.ID)
            .engine(engine).allowAllAccess(true).allowExperimentalOptions(true).build()
        try {
            MezzeLanguage.clearLastResult()
            MezzeLanguage.setFunctionsForDirectExecution(checked.loweredFunctions, checked.performerRecords, root)
            context.eval(Source.newBuilder(MezzeLanguage.ID, "", "<test>").buildLiteral())
            return MezzeLanguage.getLastExecutionResult()
        } finally {
            try { context.close(true) } catch (_: Exception) {}
            try { engine.close() } catch (_: Exception) {}
        }
    }

    private fun TypeCheckResult.identity(file: String, name: String): Hash {
        val module = loadedModules.values.singleOrNull { it.path.endsWith("/$file") || it.path == file }
            ?: error("no loaded module for $file; loaded: ${loadedModules.values.map { it.path }}")
        return module.decls.filterIsInstance<TypedLetDecl>().singleOrNull { it.name == name }?.hash
            ?: error("no let '$name' in $file")
    }

    private fun TypeCheckResult.code(identity: Hash): Hash =
        CodeHash.of(loweredFunctions)[identity] ?: error("${identity.hex.take(10)} was not lowered")

    private val first = """
        let first = { xs: [Int] } -> match seq::first { xs = xs } is
          Some { val } -> val
          None -> 0
    """.trimIndent()

    // ---- separation: what must NOT be shared across modules ------------------

    @Test
    fun `each module's lambda calls its own module's local helper`() {
        fun module(step: Int) = """
            use std::collections::seq

            pub let bump = { xs: [Int] } -> do
              let g = { y } -> y + $step
              seq::map { xs = xs, f = { x } -> g { y = x } }
        """
        write("a", module(1))
        write("b", module(2))
        assertEquals(607L, run("""
            use std::collections::seq
            use a
            use b

            $first
            let main = {} -> first { xs = a::bump { xs = [5] } } * 100 + first { xs = b::bump { xs = [5] } }
            main {}
        """))
    }

    @Test
    fun `the same lambda text at another type in another module is another function`() {
        write("ints", """
            use std::collections::seq

            pub let keep = { xs: [Int] } -> seq::map { xs = xs, f = { x } -> x }
        """)
        write("flags", """
            use std::collections::seq

            pub let keep = { xs: [Bool] } -> seq::map { xs = xs, f = { x } -> x }
        """)
        assertEquals(71L, run("""
            use std::collections::seq
            use ints
            use flags

            let main = {} -> do
              let n = match seq::first { xs = ints::keep { xs = [7] } } is
                Some { val } -> val
                None -> 0
              let b = match seq::first { xs = flags::keep { xs = [True] } } is
                Some { val } -> if val then 1 else 0
                None -> 0
              n * 10 + b
            main {}
        """))
    }

    @Test
    fun `each module's lambda calls through its own module's alias`() {
        write("picks", """
            use std::abilities::compare::{ Ord }

            pub let pick_hi: { a: 'a, b: 'a } with Ord 'a -> 'a = { a, b } -> if a >= b then a else b
            pub let pick_lo: { a: 'a, b: 'a } with Ord 'a -> 'a = { a, b } -> if a <= b then a else b
        """)
        fun module(which: String) = """
            use picks::{ $which }

            pub let run: {} -> Int = {} -> do
              let pick = $which
              let f = { x: Int } -> pick { a = x, b = 5 }
              f { x = 1 }
        """
        write("hi", module("pick_hi"))
        write("lo", module("pick_lo"))
        assertEquals(51L, run("""
            use hi
            use lo

            let main = {} -> hi::run {} * 10 + lo::run {}
            main {}
        """))
    }

    @Test
    fun `the same caller text in two modules calls each module's own helper`() {
        fun module(answer: Int) = """
            let helper = {} -> $answer

            pub let caller = {} -> helper {}
        """
        write("a", module(1))
        write("b", module(2))
        val main = """
            use a
            use b

            let main = {} -> a::caller {} * 10 + b::caller {}
            main {}
        """
        assertEquals(12L, run(main))
        val checked = check(main)
        assertNotEquals(checked.identity("a.mz", "caller"), checked.identity("b.mz", "caller"))
        assertNotEquals(
            checked.code(checked.identity("a.mz", "caller")), checked.code(checked.identity("b.mz", "caller")),
            "they call different code, though their own text is the same",
        )
    }

    // ---- sharing: what IS the same across modules ------------------------------

    @Test
    fun `the same function in two modules is one declaration, one code, and runs`() {
        val sum = "pub let sum = { a: Int, b: Int } -> a + b"
        write("a", sum)
        write("b", "# written again, further down\n\n\n$sum")
        val main = """
            use a
            use b

            let main = {} -> a::sum { a = 1, b = 2 } * 10 + b::sum { a = 3, b = 4 }
            main {}
        """
        assertEquals(37L, run(main))
        val checked = check(main)
        assertEquals(checked.identity("a.mz", "sum"), checked.identity("b.mz", "sum"))
        assertEquals(1, checked.loweredFunctions.count { it.hash == checked.identity("a.mz", "sum") }, "lowered once for both")
    }

    @Test
    fun `the same function under another name in another module is another declaration and the same code`() {
        write("a", "pub let sum = { a: Int, b: Int } -> a + b")
        write("b", "pub let add = { a: Int, b: Int } -> a + b")
        val main = """
            use a
            use b

            let main = {} -> a::sum { a = 1, b = 2 } * 10 + b::add { a = 3, b = 4 }
            main {}
        """
        assertEquals(37L, run(main))
        val checked = check(main)
        val sum = checked.identity("a.mz", "sum")
        val add = checked.identity("b.mz", "add")
        assertNotEquals(sum, add)
        assertEquals(checked.code(sum), checked.code(add))
    }

    @Test
    fun `importing a function under another name changes the caller's identity and not its code`() {
        write("lib", """
            pub let count = { n: Int, acc: Int } -> if n <= 0 then acc else count { n = n - 1, acc = acc + n }
        """)
        write("a", """
            use lib::{ count }

            pub let total = { n: Int } -> count { n = n, acc = 0 }
        """)
        write("b", """
            use lib::{ count as tally }

            pub let total = { n: Int } -> tally { n = n, acc = 0 }
        """)
        val main = """
            use a
            use b

            let main = {} -> a::total { n = 3 } * 100 + b::total { n = 4 }
            main {}
        """
        assertEquals(610L, run(main))
        val checked = check(main)
        assertEquals(
            checked.code(checked.identity("a.mz", "total")), checked.code(checked.identity("b.mz", "total")),
            "both call the same function; only what they call it differs",
        )
    }
}
