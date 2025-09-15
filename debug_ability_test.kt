import com.funk.language.parser.FunkParserImpl
import com.funk.language.typechecker.TypeChecker

fun main() {
    val parser = FunkParserImpl()
    val typeChecker = TypeChecker()
    
    val source = """
        ability Show 'a where {
          show : {val: 'a} -> String
        }
        
        impl Show Int where {
          show : {val: Int} -> String = {val} -> intToString {val = val}
        }
        
        let result = Show.show {val = 42}
    """.trimIndent()
    
    try {
        val program = parser.parse(source)
        println("Parsed successfully!")
        
        val typedProgram = typeChecker.typeCheck(program)
        println("Type checked successfully!")
    } catch (e: Exception) {
        println("Error: ${e.message}")
        e.printStackTrace()
    }
}
