import com.funk.language.parser.FunkParserImpl
import com.funk.language.ast.*

fun main() {
    val parser = FunkParserImpl()
    val source = "5 |> negate {x = @}"
    
    try {
        val ast = parser.parse(source)
        println("Parsed AST: $ast")
    } catch (e: Exception) {
        println("Parse error: ${e.message}")
        e.printStackTrace()
    }
}
