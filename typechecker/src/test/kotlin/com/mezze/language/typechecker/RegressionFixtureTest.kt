package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertNotNull

class RegressionFixtureTest {

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
    @MethodSource("regressionFixtures")
    fun regressionFixtureTypeChecks(fixtureName: String, fixturePath: Path) {
        val source = Files.readString(fixturePath)
        val parsedModule = parser.parseParsedModule(source, fixturePath.fileName.toString())
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))
        assertNotNull(result)
    }

    companion object {
        @JvmStatic
        fun regressionFixtures(): List<Array<Any>> {
            val dir = Paths.get("src/test/resources/fixtures/regression")
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
