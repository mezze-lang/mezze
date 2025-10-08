import com.funk.language.typechecker.TypeChecker
import com.funk.language.parser.FunkParserImpl
import com.funk.language.ast.LetBinding

fun main() {
    val source = """
        pub let test: { x: Int } -> Int = { x } -> x + 1
    """.trimIndent()

    val parser = FunkParserImpl()
    val program = parser.parse(source)

    println("Before type-checking:")
    program.items.forEach { item ->
        if (item is LetBinding) {
            println("  ${item.variableName}: typeAnnotation=${item.typeAnnotation}, inferredType=${item.inferredType}")
        }
    }

    val typeChecker = TypeChecker()
    val typedProgram = typeChecker.typeCheck(program, java.nio.file.Paths.get("."))

    println("\nAfter type-checking:")
    typedProgram.items.forEach { item ->
        if (item is LetBinding) {
            println("  ${item.variableName}: typeAnnotation=${item.typeAnnotation}, inferredType=${item.inferredType}")
        }
    }
}
