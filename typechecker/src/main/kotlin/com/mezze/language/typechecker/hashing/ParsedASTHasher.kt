package com.mezze.language.typechecker.hashing

import com.mezze.language.ast.LetBinding
import com.mezze.language.ast.Visibility
import com.mezze.language.ast.hashing.ASTHasher
import com.mezze.language.parsedast.*
import com.mezze.language.parser.MezzeParser

/**
 * Content hash computation for ParsedModule declarations.
 *
 * Uses ParserBridge to convert v2 AST → v1 AST for hashing (internally),
 * producing hashes identical to the v1 pipeline. This ensures hash
 * compatibility between modules compiled via different pipeline paths.
 */
object ParsedASTHasher {

    /**
     * Compute content hash for a ParsedLetDecl.
     * Returns null if hashing fails (e.g., unsupported expression types).
     */
    fun hashLetDecl(decl: ParsedLetDecl, file: String): String? {
        return try {
            val v1Expr = MezzeParser.toV1Expr(decl.value, file)
            val v1Pattern = MezzeParser.toV1Pattern(decl.pattern, file)
            val v1TypeAnn = decl.typeAnnotation?.let { MezzeParser.toV1TypeAnnotation(it, file) }
            val v1Binding = LetBinding(
                pattern = v1Pattern,
                expression = v1Expr,
                typeAnnotation = v1TypeAnn,
                visibility = if (decl.visibility == com.mezze.language.parsedast.Visibility.PUBLIC) Visibility.PUBLIC else Visibility.PRIVATE
            )
            ASTHasher.hashLetBinding(v1Binding).toFullHexString()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Compute content hashes for all simple let declarations in a module.
     * Returns a map of binding name → content hash.
     */
    fun hashAllLetDecls(module: ParsedModule): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (decl in module.decls) {
            if (decl is ParsedLetDecl && decl.isSimpleBinding) {
                val hash = hashLetDecl(decl, module.path)
                if (hash != null) {
                    result[decl.variableName] = hash
                }
            }
        }
        return result
    }
}
