package com.funk.language.interpreter

import com.oracle.truffle.api.CallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.TruffleLanguage.Registration
import com.oracle.truffle.api.nodes.Node
import com.funk.language.parser.FunkParserImpl
import com.funk.language.parser.Program
import com.funk.language.typechecker.TypeChecker
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import com.funk.language.parser.generated.FunkLexer
import com.funk.language.parser.generated.FunkParser

/**
 * The main Funk language implementation for GraalVM Truffle.
 * This class serves as the entry point for the Funk language runtime.
 */
@Registration(
    id = FunkLanguage.ID,
    name = "Funk",
    implementationName = "Funk",
    version = "1.0.0",
    defaultMimeType = FunkLanguage.MIME_TYPE,
    characterMimeTypes = [FunkLanguage.MIME_TYPE],
    fileTypeDetectors = [FunkFileDetector::class],
    interactive = true,
    internal = false
)
class FunkLanguage : TruffleLanguage<FunkContext>() {
    
    companion object {
        const val ID = "funk"
        const val MIME_TYPE = "application/x-funk"
        
        fun getCurrentLanguage(node: Node): FunkLanguage {
            return getCurrentLanguage(FunkLanguage::class.java)
        }
        
        fun getCurrentContext(node: Node): FunkContext {
            return getCurrentContext(FunkLanguage::class.java)
        }
    }
    
    override fun createContext(env: Env): FunkContext {
        return FunkContext(this, env)
    }
    
    override fun parse(request: ParsingRequest): CallTarget {
        val source = request.source
        val sourceCode = source.characters.toString()
        
        // Clear native global store for new program execution
        // This ensures clean state for each program run
        NativeGlobalStore.clear()
        
        try {
            // Parse the source code using our existing ANTLR parser
            val lexer = FunkLexer(CharStreams.fromString(sourceCode))
            val tokens = CommonTokenStream(lexer)
            val parser = FunkParser(tokens)
            
            val parserImpl = FunkParserImpl()
            val ast = parserImpl.parse(sourceCode)
            
            // Type check the program
            val typeChecker = TypeChecker()
            val typedProgram = typeChecker.typeCheck(ast)
            
            // Convert AST to Truffle nodes
            val converter = ASTToTruffleConverter()
            val rootNode = converter.convertProgram(typedProgram, this)
            
            return rootNode.callTarget
        } catch (e: Exception) {
            throw FunkException("Parse error: ${e.message}", e)
        }
    }
    
    // Note: isObjectOfLanguage method signature may vary by Truffle version
    // For now, we'll comment this out and add it back when we have the correct signature
    // override fun isObjectOfLanguage(`object`: Any): Boolean {
    //     return `object` is FunkValue
    // }
}
