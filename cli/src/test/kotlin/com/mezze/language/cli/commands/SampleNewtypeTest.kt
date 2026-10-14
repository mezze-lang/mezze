package com.mezze.language.cli.commands

import com.mezze.language.ast.MezzeType
import com.mezze.language.langservice.sample.SampleGen
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * `mez sample` on a function whose parameters hold a newtype with no `Arbitrary`.
 *
 * A newtype never derives `Arbitrary` — only its author knows which values are
 * valid — so `{ a: Age }`, `{ u: User }` with an `age: Age` field, and `{ xs: [Age] }`
 * all stopped with "does not implement 'Arbitrary'". The command now draws such a
 * newtype from its representation through a sample-local impl, and says so; the
 * user's program and property tests still refuse.
 */
class SampleNewtypeTest {

    private val source = """
        use std::nat::{ Nat }
        newtype Age = Int
        type User = { name: Str, age: Age }
        pub let direct = { a: Age } -> 1
        pub let inside = { u: User } -> 1
        pub let listed = { xs: [Age] } -> 1
        pub let natural = { n: Nat } -> 1
        pub let mixed = { a: Age, n: Nat } -> 1
    """.trimIndent()

    private fun project(dir: Path): Pair<File, File> {
        val root = dir.toFile()
        val module = File(File(root, "src").apply { mkdirs() }, "app.mz").apply { writeText(source) }
        return root to module
    }

    private fun target(dir: Path, fn: String): SampleTarget.Result.Ready {
        val (root, module) = project(dir)
        return SampleTarget.resolve(root, module, fn) as SampleTarget.Result.Ready
    }

    @Test
    fun `a newtype with no instance is found wherever it sits`(@TempDir dir: Path) {
        for (fn in listOf("direct", "inside", "listed")) {
            assertEquals(listOf("Age"), target(dir, fn).unwrapped.map { it.name }, fn)
        }
    }

    /** `Nat`'s instance is the stdlib's: it must be used, not replaced. */
    @Test
    fun `a newtype that has an instance is left to it`(@TempDir dir: Path) {
        assertEquals(emptyList<String>(), target(dir, "natural").unwrapped.map { it.name })
        assertEquals(listOf("Age"), target(dir, "mixed").unwrapped.map { it.name })
    }

    /** The generated program compiles — it did not, for every one of these. */
    @Test
    fun `the generated program compiles for every shape`(@TempDir dir: Path) {
        val (root, module) = project(dir)
        for (fn in listOf("direct", "inside", "listed", "mixed")) {
            val t = SampleTarget.resolve(root, module, fn) as SampleTarget.Result.Ready
            val cmd = SampleCommand()
            val r = EvalClosure.compile(root, module, cmd.buildExpression(t, seed = 1, count = 3), cmd.imports(t), cmd.buildDecls(t))
            assertTrue(r is EvalClosure.Result.Compiled, "$fn: ${(r as? EvalClosure.Result.Failed)?.message}")
        }
    }

    /**
     * A newtype declared in ANOTHER of the user's modules is refused, saying where
     * its impl belongs: a newtype's constructor is private to its module, which is
     * its encapsulation, and sample does not get around it.
     */
    @Test
    fun `a newtype from another module is refused with where to write its impl`(@TempDir dir: Path) {
        val root = dir.toFile()
        val src = File(root, "src").apply { mkdirs() }
        File(src, "types.mz").writeText("""
            pub newtype Age = Int
            pub type Person = | Adult { age: Age }, Kid |
        """.trimIndent())
        val module = File(src, "app.mz").apply { writeText("""
            use types::{ Age, Person }
            pub let label = { p: Person } -> match p is
              Adult { age } -> "adult"
              Kid -> "kid"
        """.trimIndent()) }
        val r = SampleTarget.resolve(root, module, "label")
        val msg = (r as? SampleTarget.Result.Refused)?.message ?: "not refused: $r"
        assertTrue("`Age` has no `Arbitrary`" in msg && "private to `types`" in msg && "impl Arbitrary for Age" in msg, msg)
    }

    /** A LOCAL newtype inside a pinned variant arm goes through the branch reader. */
    @Test
    fun `a local newtype inside a pinned arm compiles`(@TempDir dir: Path) {
        val root = dir.toFile()
        val module = File(File(root, "src").apply { mkdirs() }, "app.mz").apply { writeText("""
            newtype Age = Int
            type Person = | Adult { age: Age }, Kid |
            pub let label = { p: Person } -> match p is
              Adult { age } -> "adult"
              Kid -> "kid"
        """.trimIndent()) }
        val t = SampleTarget.resolve(root, module, "label") as SampleTarget.Result.Ready
        assertEquals(listOf("Age"), t.unwrapped.map { it.name })
        assertEquals(2, t.branches.size, "one pinned row per arm")
        val cmd = SampleCommand()
        val r = EvalClosure.compile(root, module, cmd.buildExpression(t, seed = 1, count = 3), cmd.imports(t), cmd.buildDecls(t))
        assertTrue(r is EvalClosure.Result.Compiled, (r as? EvalClosure.Result.Failed)?.message ?: "")
    }

    /**
     * A GENERIC newtype, used at two instantiations, and a RECURSIVE variant holding
     * a newtype — the recursive helper's payloads go through the same unwrapping.
     */
    @Test
    fun `generic newtypes and recursive variants holding a newtype compile`(@TempDir dir: Path) {
        val root = dir.toFile()
        val module = File(File(root, "src").apply { mkdirs() }, "app.mz").apply { writeText("""
            newtype Age = Int
            newtype Box 'a = ['a]
            type Tree = | Leaf, Node { left: Tree, age: Age, right: Tree } |
            pub let boxes = { a: Box Int, b: Box Str } -> 1
            pub let depth = { t: Tree } -> match t is
              Leaf -> 0
              Node { left, age, right } -> 1
        """.trimIndent()) }
        val cmd = SampleCommand()
        for ((fn, expected) in listOf("boxes" to listOf("Box"), "depth" to listOf("Age"))) {
            val t = SampleTarget.resolve(root, module, fn) as SampleTarget.Result.Ready
            assertEquals(expected, t.unwrapped.map { it.name }, fn)
            val r = EvalClosure.compile(root, module, cmd.buildExpression(t, seed = 1, count = 3), cmd.imports(t), cmd.buildDecls(t))
            assertTrue(r is EvalClosure.Result.Compiled, "$fn: " + ((r as? EvalClosure.Result.Failed)?.message ?: ""))
        }
    }

    @Test
    fun `the note names the newtype and its representation`(@TempDir dir: Path) {
        val note = SampleCommand().unwrappedNote(target(dir, "direct")).single()
        assertTrue("`Age` has no `Arbitrary`" in note && "`Int` representation" in note, note)
    }

    /** What still cannot be generated is named where it sits, not by its outer type. */
    @Test
    fun `a blocker is named by its path`() {
        val user = MezzeType.RecordType(mapOf(
            "name" to MezzeType.StringType,
            "callback" to MezzeType.FunctionType(MezzeType.RecordType(emptyMap()), MezzeType.IntType),
        ))
        val found = SampleGen.blockers("u", user)
        assertEquals(1, found.size, "$found")
        assertTrue(found.single().startsWith("`u.callback` is a function"), found.single())
        assertEquals(emptyList<String>(), SampleGen.blockers("f", MezzeType.FunctionType(MezzeType.RecordType(emptyMap()), MezzeType.IntType)),
            "a TOP-LEVEL function parameter is generated, so it is no blocker")
    }
}
