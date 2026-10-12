package com.mezze.language.cli.commands

import com.mezze.language.ast.MezzeType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * A generic function is sampled at `Int`.
 *
 * Left as a type variable, nothing chose the type to generate: `pick { xs: ['a] }`
 * produced a call that checked clean and died on every row at run time, asking
 * for a dictionary for `'a`. Property testers instantiate at a concrete type, and
 * so does sample.
 */
class SampleTargetGenericTest {

    @Test
    fun `a type variable in a parameter becomes Int`(@TempDir dir: Path) {
        val module = dir.resolve("app.mz").toFile()
        module.writeText("""
            pub let pick = { xs: ['a], fallback: 'a } -> match xs is
              [] -> fallback
              [h, ...rest] -> h
        """.trimIndent())
        val target = SampleTarget.resolve(dir.toFile(), module, "pick") as SampleTarget.Result.Ready
        assertEquals(MezzeType.IntType, target.fields["fallback"])
        assertEquals(MezzeType.IntType, (target.fields["xs"] as MezzeType.TypeApplication).args.single())
    }
}
