package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.typedast.Hash
import com.mezze.language.typedast.TypedLetDecl
import com.mezze.language.typedast.TypedModule
import com.mezze.language.typedast.TypedTypeDecl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * A declaration's identity hash must say what the declaration IS, which includes
 * what every name in it refers to — not just how its text reads.
 *
 * It used to be text only: a reference was hashed as the name written. So
 * `pub let caller = {} -> helper {}` had ONE hash in every module that wrote it,
 * whatever `helper` meant there, and since compiled functions are keyed by
 * hash, one module's `caller` silently ran the other's code (`a=1 b=1` where
 * `a=1 b=2` was meant). These tests pin the replacement: a reference hashes as
 * the identity of its target, so a dependency's change reaches its dependents,
 * while code that is genuinely the same — same text, same targets — still
 * shares a hash.
 */
class DeclIdentityTest {

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        com.mezze.language.typechecker.module.ModuleTypeChecker.clearCache()
        com.mezze.language.typechecker.module.ModuleTypeChecker.clearTypeCheckingStack()
    }

    private fun writeModule(relative: String, body: String) {
        val file = tempDir.resolve("src").resolve("$relative.mz")
        Files.createDirectories(file.parent)
        Files.writeString(file, body.trimIndent())
    }

    private fun check(main: String): TypeCheckResult {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        com.mezze.language.typechecker.module.ModuleTypeChecker.clearCache()
        com.mezze.language.typechecker.module.ModuleTypeChecker.clearTypeCheckingStack()
        writeModule("main", main)
        val parsed = MezzeParser().parseParsedModule(main.trimIndent(), "main.mz")
        return TypeChecker().typeCheck(parsed, tempDir)
    }

    private fun TypeCheckResult.module(file: String): TypedModule =
        loadedModules.values.singleOrNull { it.path.endsWith("/$file") || it.path == file }
            ?: error("no loaded module for $file; loaded: ${loadedModules.values.map { it.path }}")

    private fun TypedModule.let(name: String): Hash =
        decls.filterIsInstance<TypedLetDecl>().singleOrNull { it.name == name }?.hash
            ?: error("no let '$name' in $path")

    private fun TypedModule.type(name: String): Hash =
        decls.filterIsInstance<TypedTypeDecl>().singleOrNull { it.name == name }?.hash
            ?: error("no type '$name' in $path")

    private val twoCallers = """
        use a::{ caller as from_a }
        use b::{ caller as from_b }

        let main = {} -> from_a {} + from_b {}
    """

    @Test
    fun `same text calling different helpers is two declarations`() {
        writeModule("a", """
            let helper = {} -> 1

            pub let caller = {} -> helper {}
        """)
        writeModule("b", """
            let helper = {} -> 2

            pub let caller = {} -> helper {}
        """)
        val result = check(twoCallers)
        assertNotEquals(
            result.module("a.mz").let("caller"), result.module("b.mz").let("caller"),
            "each `caller` calls a different `helper`, so they are different functions",
        )
    }

    @Test
    fun `a changed dependency changes its dependents`() {
        writeModule("a", """
            let helper = {} -> 1

            pub let caller = {} -> helper {}
        """)
        writeModule("b", "pub let caller = {} -> 0")
        val before = check(twoCallers).module("a.mz").let("caller")

        writeModule("a", """
            let helper = {} -> 10

            pub let caller = {} -> helper {}
        """)
        val after = check(twoCallers).module("a.mz").let("caller")
        assertNotEquals(before, after, "`caller`'s text is unchanged but what it calls is not")
    }

    @Test
    fun `the same code in the same environment is still one declaration`() {
        writeModule("shared", "pub let base = {} -> 1")
        writeModule("a", """
            use shared::{ base }

            pub let caller = {} -> base {}
        """)
        writeModule("b", """
            use shared::{ base }

            pub let caller = {} -> base {}
        """)
        val result = check(twoCallers)
        assertEquals(
            result.module("a.mz").let("caller"), result.module("b.mz").let("caller"),
            "same text, and every name resolves to the same target: content addressing should share it",
        )
    }

    @Test
    fun `a type naming different same-named types is two types`() {
        writeModule("a", """
            type Point = { x: Int }

            pub type Box = | Full Point, Empty |
            pub let box = {} -> Full { x = 1 }
        """)
        writeModule("b", """
            type Point = { x: Str }

            pub type Box = | Full Point, Empty |
            pub let box = {} -> Full { x = "1" }
        """)
        val result = check("""
            use a::{ box as box_a }
            use b::{ box as box_b }

            let main = {} -> 0
        """)
        assertNotEquals(result.module("a.mz").type("Box"), result.module("b.mz").type("Box"))
    }

    @Test
    fun `newtypes in same-named files of different directories are distinct`() {
        writeModule("x/ids", """
            pub newtype Id = Str
            pub let make = { s: Str } -> Id.(s)
        """)
        writeModule("y/ids", """
            pub newtype Id = Str
            pub let make = { s: Str } -> Id.(s)
        """)
        val result = check("""
            use x::ids::{ make as make_x }
            use y::ids::{ make as make_y }

            let main = {} -> 0
        """)
        val x = result.loadedModules.values.single { it.path.endsWith("x/ids.mz") }
        val y = result.loadedModules.values.single { it.path.endsWith("y/ids.mz") }
        assertNotEquals(x.type("Id"), y.type("Id"), "a newtype is nominal: each declaration is its own type")
    }

    @Test
    fun `mutually recursive declarations get distinct, stable hashes`() {
        writeModule("parity", """
            pub let is_even = { n: Int } -> if n == 0 then True else is_odd { n = n - 1 }
            pub let is_odd = { n: Int } -> if n == 0 then False else is_even { n = n - 1 }
        """)
        val main = """
            use parity::{ is_even }

            let main = {} -> is_even { n = 4 }
        """
        val first = check(main).module("parity.mz")
        val second = check(main).module("parity.mz")
        assertNotEquals(first.let("is_even"), first.let("is_odd"))
        assertEquals(first.let("is_even"), second.let("is_even"))
        assertEquals(first.let("is_odd"), second.let("is_odd"))
    }

    @Test
    fun `a reload after a change two imports away re-checks what lies between`() {
        // main -> mid -> leaf. Only `leaf` changes, and only `leaf` is evicted —
        // exactly what `mez dev` and the LSP do on a save. `mid`'s source is
        // untouched, but what it calls changed, so its identity must too.
        writeModule("leaf", "pub let base = {} -> 1")
        writeModule("mid", """
            use leaf::{ base }

            pub let middle = {} -> base {}
        """)
        val main = """
            use mid::{ middle }

            let main = {} -> middle {}
        """
        writeModule("main", main)
        val parse = { com.mezze.language.parser.MezzeParser().parseParsedModule(main.trimIndent(), "main.mz") }
        val before = TypeChecker().typeCheck(parse(), tempDir).module("mid.mz").let("middle")

        writeModule("leaf", "pub let base = {} -> 2")
        ModuleResolver.invalidateModulePaths(listOf("leaf"))
        com.mezze.language.typechecker.module.ModuleTypeChecker.invalidateModulePaths(listOf("leaf"))
        val after = TypeChecker().typeCheck(parse(), tempDir).module("mid.mz").let("middle")

        assertNotEquals(before, after, "`mid` was served from a cache keyed on its own source alone")
    }

    @Test
    fun `a typed module read back from a store is reused only while its identities hold`() {
        // `mez build` hydrates project modules from the store to skip checking
        // them. One persisted before a dependency changed has the old identities.
        writeModule("leaf", "pub let base = {} -> 1")
        writeModule("mid", """
            use leaf::{ base }

            pub let middle = {} -> base {}
        """)
        val main = """
            use mid::{ middle }

            let main = {} -> middle {}
        """
        writeModule("main", main)
        val parse = { com.mezze.language.parser.MezzeParser().parseParsedModule(main.trimIndent(), "main.mz") }
        val first = TypeChecker().typeCheck(parse(), tempDir)
        val persisted = first.module("mid.mz")
        val before = persisted.let("middle")

        writeModule("leaf", "pub let base = {} -> 2")
        for (stored in listOf(persisted, persisted.copy(sourceIdentity = null))) {
            setup()
            com.mezze.language.typechecker.module.ModuleTypeChecker.cacheTypedModule("mid", stored)
            val after = TypeChecker().typeCheck(parse(), tempDir).module("mid.mz").let("middle")
            assertNotEquals(before, after, "hydrated `mid` (sourceIdentity=${stored.sourceIdentity?.hex?.take(8)}) was reused stale")
        }
    }

    @Test
    fun `an imported alias means what its names meant where it was declared`() {
        // `Ticker` mentions `Tick`, which the importer never imports. The alias's
        // row must resolve `Tick` in `lib`, where it was written — it used to be
        // looked up here and fail ("Effect `Tick` is not defined or not imported").
        writeModule("lib", """
            effect Tick where {
              tick: {} -> Int <Tick>
            }

            pub type Ticker = {} -> Int <Tick>
        """)
        val result = check("""
            use lib::{ Ticker }

            pub let call_it = { f: Ticker } -> 1
        """)
        assertTrue(result.typedModule != null)
    }
}
