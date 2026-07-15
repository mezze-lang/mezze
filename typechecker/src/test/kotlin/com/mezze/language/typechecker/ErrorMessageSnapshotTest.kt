package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.parser.ParseException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals

class ErrorMessageSnapshotTest {

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

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidFixtures")
    fun errorMessageMatchesSnapshot(fixtureName: String, fixturePath: Path) {
        val source = Files.readString(fixturePath)

        val (errorCode, errorMessage) = try {
            val parsedModule = parser.parseParsedModule(source, fixturePath.fileName.toString())
            typeChecker.typeCheck(parsedModule, Paths.get("."))
            "NO_ERROR" to "Type checking succeeded (expected failure)"
        } catch (e: TypeCheckError) {
            e.code.name to (e.message ?: "null")
        } catch (e: ParseException) {
            "PARSE_ERROR" to (e.message ?: "null")
        } catch (e: SpreadSyntaxError) {
            e.javaClass.simpleName to (e.message ?: "null")
        }

        val snapshotPath = fixturePath.resolveSibling("$fixtureName.expected")
        val actual = "$errorCode\n$errorMessage"

        if (!Files.exists(snapshotPath)) {
            Files.writeString(snapshotPath, normalizeRowVars(actual))
            println("GENERATED snapshot: $snapshotPath")
            return
        }

        val expected = Files.readString(snapshotPath).trimEnd()
        assertEquals(normalizeRowVars(expected), normalizeRowVars(actual.trimEnd()),
            "Error message snapshot mismatch for $fixtureName. " +
            "If the change is intentional, delete $snapshotPath and re-run to regenerate."
        )
    }

    private fun normalizeRowVars(s: String): String =
        s.replace(Regex("row\\d+"), "row_")

    companion object {
        @JvmStatic
        fun invalidFixtures(): List<Array<Any>> {
            val dir = Paths.get("src/test/resources/fixtures/invalid")
            if (!Files.isDirectory(dir)) return emptyList()
            return Files.list(dir)
                .filter { it.toString().endsWith(".mz") }
                .sorted()
                .map { path ->
                    val name = path.fileName.toString().removeSuffix(".mz")
                    arrayOf<Any>(name, path)
                }
                .toList()
        }
    }
}
