package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.parser.ParseException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.RepeatedTest
import java.nio.file.Paths
import kotlin.random.Random

class TypeCheckerFuzzTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        System.setProperty("mezze.typecheck.strict", "true")
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    @RepeatedTest(50)
    fun `random program does not crash the typechecker`() {
        val rng = Random(System.nanoTime())
        val source = generateRandomProgram(rng)

        try {
            val parsed = parser.parseParsedModule(source, "fuzz_${rng.nextInt()}.mz")
            typeChecker.typeCheck(parsed, Paths.get("."))
        } catch (_: TypeCheckError) {
            // Expected — random programs can have type errors
        } catch (_: ParseException) {
            // Expected — random programs can have parse errors
        } catch (_: SpreadSyntaxError) {
            // Expected
        } catch (e: StackOverflowError) {
            throw AssertionError("StackOverflowError on program:\n$source", e)
        } catch (e: OutOfMemoryError) {
            throw AssertionError("OutOfMemoryError on program:\n$source", e)
        }
    }

    private fun generateRandomProgram(rng: Random): String {
        val sb = StringBuilder()
        val numBindings = rng.nextInt(1, 8)

        for (i in 0 until numBindings) {
            sb.appendLine(generateBinding(rng, i))
        }
        sb.appendLine("x0")

        return sb.toString()
    }

    private fun generateBinding(rng: Random, index: Int): String {
        val name = "x$index"
        val expr = generateExpr(rng, depth = 0, maxDepth = 3, availableVars = (0 until index).map { "x$it" })
        return "let $name = $expr"
    }

    private fun generateExpr(rng: Random, depth: Int, maxDepth: Int, availableVars: List<String>): String {
        if (depth >= maxDepth) return generateLeaf(rng, availableVars)

        return when (rng.nextInt(10)) {
            0 -> generateLeaf(rng, availableVars)
            1 -> generateRecord(rng, depth, maxDepth, availableVars)
            2 -> generateList(rng, depth, maxDepth, availableVars)
            3 -> generateLambda(rng, depth, maxDepth, availableVars)
            4 -> generateBinaryOp(rng, depth, maxDepth, availableVars)
            5 -> generateFieldAccess(rng, depth, maxDepth, availableVars)
            6 -> generateMatch(rng, depth, maxDepth, availableVars)
            7 -> generateStringInterp(rng, depth, maxDepth, availableVars)
            8 -> generateFunctionCall(rng, depth, maxDepth, availableVars)
            else -> generateLeaf(rng, availableVars)
        }
    }

    private fun generateLeaf(rng: Random, availableVars: List<String>): String {
        if (availableVars.isNotEmpty() && rng.nextBoolean()) {
            return availableVars[rng.nextInt(availableVars.size)]
        }
        return when (rng.nextInt(4)) {
            0 -> rng.nextInt(-100, 100).toString()
            1 -> "\"${randomString(rng)}\""
            2 -> if (rng.nextBoolean()) "True" else "False"
            else -> rng.nextInt(0, 50).toString()
        }
    }

    private fun generateRecord(rng: Random, depth: Int, maxDepth: Int, availableVars: List<String>): String {
        val numFields = rng.nextInt(1, 4)
        val fields = (0 until numFields).joinToString(", ") { i ->
            val fieldName = "f${('a' + i)}"
            val value = generateExpr(rng, depth + 1, maxDepth, availableVars)
            "$fieldName = $value"
        }
        return "{ $fields }"
    }

    private fun generateList(rng: Random, depth: Int, maxDepth: Int, availableVars: List<String>): String {
        val numElements = rng.nextInt(0, 4)
        if (numElements == 0) return "[1]"
        val elements = (0 until numElements).joinToString(", ") {
            generateLeaf(rng, availableVars)
        }
        return "[$elements]"
    }

    private fun generateLambda(rng: Random, depth: Int, maxDepth: Int, availableVars: List<String>): String {
        val paramName = "p${rng.nextInt(100)}"
        val body = generateExpr(rng, depth + 1, maxDepth, availableVars + paramName)
        return "{$paramName} -> $body"
    }

    private fun generateBinaryOp(rng: Random, depth: Int, maxDepth: Int, availableVars: List<String>): String {
        val ops = listOf("+", "-", "*", "==", ">", "<", ">=", "<=")
        val op = ops[rng.nextInt(ops.size)]
        val left = generateLeaf(rng, availableVars)
        val right = generateLeaf(rng, availableVars)
        return "$left $op $right"
    }

    private fun generateFieldAccess(rng: Random, depth: Int, maxDepth: Int, availableVars: List<String>): String {
        val fields = listOf("fa", "fb", "fc")
        val field = fields[rng.nextInt(fields.size)]
        val record = generateRecord(rng, depth + 1, maxDepth, availableVars)
        return "$record.$field"
    }

    private fun generateMatch(rng: Random, depth: Int, maxDepth: Int, availableVars: List<String>): String {
        val scrutinee = generateLeaf(rng, availableVars)
        val trueBranch = generateLeaf(rng, availableVars)
        val falseBranch = generateLeaf(rng, availableVars)
        return "match $scrutinee > 0 is\n  True -> $trueBranch\n  False -> $falseBranch"
    }

    private fun generateStringInterp(rng: Random, depth: Int, maxDepth: Int, availableVars: List<String>): String {
        val inner = generateLeaf(rng, availableVars)
        return "\"value: \\($inner)\""
    }

    private fun generateFunctionCall(rng: Random, depth: Int, maxDepth: Int, availableVars: List<String>): String {
        val lambda = generateLambda(rng, depth + 1, maxDepth, availableVars)
        val arg = generateExpr(rng, depth + 1, maxDepth, availableVars)
        val paramName = lambda.substringAfter("{").substringBefore("}").trim()
        return "($lambda) { $paramName = $arg }"
    }

    private fun randomString(rng: Random): String {
        val len = rng.nextInt(0, 10)
        return (0 until len).map { ('a' + rng.nextInt(26)).toChar() }.joinToString("")
    }
}
