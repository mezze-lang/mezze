package com.mezze.language.typechecker

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.prettyPrintType
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * What an importer sees must be the signature that was WRITTEN.
 *
 * The export path has two sources for a public `let`'s type: the solver's
 * inferred type, and the type derived from the written annotation. It prefers
 * the annotation whenever that annotation declares ability constraints,
 * because the inferred type does not carry them (#213) — exporting the inferred
 * one there would drop a `with Ord 'a` obligation and let an importer construct
 * `SortedSet Color` with no `Ord Color` impl.
 *
 * The conversion behind that preference used to run in a mode (`faithful =
 * false`) that replaced every inline variant, applied type and qualified type in
 * the signature with a FRESH TYPE VARIABLE — on the path chosen precisely for
 * being more faithful. A variable unifies with anything, so a signature reaching
 * that mode is exported strictly weaker than written.
 *
 * These tests pass against the old code too: the export of a module that
 * typechecks normally is built elsewhere, and I could not construct a program
 * that routes through that mode — so this is NOT a demonstration of a live
 * defect. They are the guard that makes removing the mode safe and keeps it
 * removed: what an importer binds must be the written shape AND the written
 * constraints, never one at the cost of the other.
 */
class ExportedSignatureFaithfulTest {

    private val parser = MezzeParser()
    private lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        tempDir = Files.createTempDirectory("mezze_export_faithful")
    }

    private fun check(src: String) =
        TypeChecker().typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), tempDir, enablePrelude = true)

    /** The record-shaped parameter of an ability-constrained export survives. */
    @Test
    fun `an ability-constrained export keeps its record parameter`() {
        Files.writeString(tempDir.resolve("lib.mz"), """
            ability Tag for 'a where { tag: {it: 'a} -> Str }
            pub let describe: { rec: { a: 'a } } with Tag 'a -> Str =
                {rec} -> Tag.tag {it = rec.a}
        """.trimIndent())

        val result = check("""
            use lib::{describe}
            let f = describe
        """)

        val type = result.getVariableType("f")
        assertTrue(type is MezzeType.FunctionType,
            "an exported function must import as a function, got ${prettyPrintType(type!!)}")
        val param = (type as MezzeType.FunctionType).paramType
        assertTrue(param is MezzeType.RecordType,
            "the written parameter `{ rec: { a: 'a } }` must survive export as a record — " +
                "a type variable here accepts any argument at all, got ${prettyPrintType(param)}")
        assertTrue((param as MezzeType.RecordType).fields.containsKey("rec"),
            "the parameter's own field must survive, got ${prettyPrintType(param)}")
    }

    /**
     * The constraints must still be there — the reason this branch is preferred
     * in the first place. Asserted alongside the shape so a future change cannot
     * trade one for the other, which is exactly what the old mode did.
     */
    @Test
    fun `an ability-constrained export keeps its constraints`() {
        Files.writeString(tempDir.resolve("lib.mz"), """
            ability Tag for 'a where { tag: {it: 'a} -> Str }
            pub let describe: { rec: { a: 'a } } with Tag 'a -> Str =
                {rec} -> Tag.tag {it = rec.a}
        """.trimIndent())

        val result = check("""
            use lib::{describe}
            let f = describe
        """)

        val type = result.getVariableType("f") as? MezzeType.FunctionType
        assertTrue(type?.abilityConstraints?.any { it.abilityName == "Tag" } == true,
            "the written `with Tag 'a` is why the annotation is exported at all: " +
                "${type?.abilityConstraints}")
    }

    /** An applied type (`Result Int Str`) is not a type variable either. */
    @Test
    fun `an ability-constrained export keeps an applied return type`() {
        Files.writeString(tempDir.resolve("lib.mz"), """
            ability Tag for 'a where { tag: {it: 'a} -> Str }
            pub type Box 'a = { held: 'a }
            pub let wrap: { v: 'a } with Tag 'a -> Box 'a = {v} -> { held = v }
        """.trimIndent())

        val result = check("""
            use lib::{wrap}
            let f = wrap
        """)

        val type = result.getVariableType("f") as? MezzeType.FunctionType
        val ret = type?.returnType
        assertTrue(ret != null && ret !is MezzeType.VarType,
            "`Box 'a` must survive export as an applied type, not collapse to a " +
                "variable: ${ret?.let { prettyPrintType(it) }}")
    }
}
