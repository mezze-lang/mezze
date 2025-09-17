import com.funk.language.typechecker.TypeChecker
import com.funk.language.parser.FunkParserImpl

fun main() {
    val code = """
        let simple = {color} -> match color {
          Red -> 1
          Blue {val} -> val
          _ -> 0
        }
        
        let result1 = simple {color = Red}
        let result2 = simple {color = Blue {val = 10}}
    """.trimIndent()
    
    println("Parsing...")
    val parser = FunkParserImpl()
    val program = parser.parse(code)
    println("Parsed successfully")
    
    println("Type checking...")
    val typeChecker = TypeChecker()
    try {
        val result = typeChecker.typeCheck(program)
        println("Type checking successful!")
        println("Result: $result")
    } catch (e: Exception) {
        println("Type checking failed: ${e.message}")
        e.printStackTrace()
    }
}
