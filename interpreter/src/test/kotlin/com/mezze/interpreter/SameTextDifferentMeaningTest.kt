package com.mezze.interpreter

import com.mezze.interpreter.runtime.MezzeLanguage
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.typechecker.module.ModuleTypeChecker
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.Source
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Two modules write the same function, and their names mean different things.
 *
 * `caller` reads `{} -> helper {}` in both, and each module's `helper` is its
 * own. Compiled functions are keyed by identity, and identity used to be the
 * text alone — so both `caller`s were one function and the program printed
 * `a=1 b=1`. This runs the program and reads what it actually computes.
 */
class SameTextDifferentMeaningTest {

    @TempDir
    lateinit var root: Path

    private fun write(module: String, body: String) {
        val file = root.resolve("src").resolve("$module.mz")
        Files.createDirectories(file.parent)
        Files.writeString(file, body.trimIndent())
    }

    /** Typecheck against [root] and run the lowered program — the way a build does. */
    private fun run(main: String): Any? {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        ModuleTypeChecker.clearCache()
        ModuleTypeChecker.clearTypeCheckingStack()
        write("main", main)
        val parsed = MezzeParser().parseParsedModule(main.trimIndent(), root.resolve("src/main.mz").toString())
        val checked = TypeChecker().typeCheck(parsed, root.resolve("src"), true, null)

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

    @Test
    @Timeout(180)
    fun `each module's caller calls its own helper`() {
        write("a", """
            let helper = {} -> 1

            pub let caller = {} -> helper {}
        """)
        write("b", """
            let helper = {} -> 2

            pub let caller = {} -> helper {}
        """)
        val result = run("""
            use a::{ caller as from_a }
            use b::{ caller as from_b }

            "a=$((from_a {}).to_str {}) b=$((from_b {}).to_str {})"
        """)
        assertEquals("a=1 b=2", result.toString())
    }
}
