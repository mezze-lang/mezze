package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AddFunctionDebugTest {

    @Test
    fun testAddFunctionTypeInference() {
        // Enable debug output to see what's happening
        System.setProperty("mezze.debug.constraints", "true")
        System.setProperty("mezze.typecheck.debug", "true")

        val source = """
            let add = {x, y} -> x + y
        """.trimIndent()


        val parser = MezzeParser()
        val parsedModule = parser.parseParsedModule(source, "test.mz")


        val typeChecker = TypeChecker()
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))
        val typeAliases = typeChecker.getTypeAliases()


        // Extract the type of 'add' from typedModule
        val addRawType = result.getVariableType("add")

        val addType = addRawType?.let {
            prettyPrintTypeWithAliases(it, typeAliases, showExpansion = false)
        } ?: "unknown"

        // Under the class-parameter NumAdd ability (`NumAdd 'r for 'l having 'out`),
        // `+` is heterogeneous, so an unannotated `{x, y} -> x + y` now infers the
        // MORE GENERAL shape where the two params may differ:
        //   { x: 'a, y: 'b } with NumAdd 'b 'a having 'out = 'c -> 'c
        // (letters are assigned positionally; the load-bearing facts are: distinct
        // param vars, a NumAdd constraint with a `having 'out` result binding, and a
        // polymorphic return equal to that `out` binding).
        val expectedType = "{ x: 'a, y: 'b } with NumAdd 'b 'a having 'out = 'c -> 'c"

        // Extract the (now distinct) param type vars to assert they are NOT unified.
        val paramVars = Regex("""x: ('\w+), y: ('\w+)""").find(addType)
        val hasDistinctParams = paramVars != null &&
            paramVars.groupValues[1] != paramVars.groupValues[2]
        // The heterogeneous NumAdd constraint carries a `having 'out` result binding.
        val hasNumAddHaving = Regex("""with NumAdd .*having 'out""").containsMatchIn(addType)
        // The return type stays polymorphic (the `out` result type var).
        val hasPolymorphicReturn = Regex("""-> '\w+$""").containsMatchIn(addType.trim())

        // Accept either the exact expected string or any rendering that carries
        // the three load-bearing facts (distinct param vars, a NumAdd `having 'out`
        // constraint, and a polymorphic return) — variable letters aren't stable.
        if (!(hasDistinctParams && hasPolymorphicReturn && hasNumAddHaving)) {
            assertEquals(expectedType, addType, "Type mismatch for add function")
        }
    }
}
