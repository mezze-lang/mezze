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

    /** Hash a ParsedTypeDecl by converting to v1 and using ASTHasher. */
    fun hashTypeDecl(decl: ParsedTypeDecl, file: String): String? {
        return try {
            val v1 = com.mezze.language.typechecker.context.ContextBuilder().parsedTypeDeclToV1(decl, file)
            ASTHasher.hashTypeDeclaration(v1).toFullHexString()
        } catch (_: Exception) { null }
    }

    /** Hash a ParsedAbilityDecl by converting to v1 and using ASTHasher. */
    fun hashAbilityDecl(decl: ParsedAbilityDecl, file: String): String? {
        return try {
            val v1 = com.mezze.language.typechecker.context.ContextBuilder().parsedAbilityDeclToV1(decl, file)
            ASTHasher.hashAbilityDeclaration(v1).toFullHexString()
        } catch (_: Exception) { null }
    }

    /** Hash a ParsedEffectDecl. */
    fun hashEffectDecl(decl: ParsedEffectDecl, file: String): String? {
        return try {
            val v1 = com.mezze.language.ast.EffectDeclaration(
                name = decl.name,
                operations = decl.operations.map { op ->
                    com.mezze.language.ast.EffectOperation(
                        name = op.name,
                        paramType = MezzeParser.toV1TypeAnnotation(op.paramType, file),
                        returnType = MezzeParser.toV1TypeAnnotation(op.returnType, file),
                        effects = op.effects
                    )
                },
                associatedTypes = decl.associatedTypes
            )
            ASTHasher.hashEffectDeclaration(v1).toFullHexString()
        } catch (_: Exception) { null }
    }

    /** Hash a ParsedPerformerDecl (effect impl). */
    fun hashPerformerDecl(decl: ParsedPerformerDecl, file: String): String? {
        return try {
            val v1 = com.mezze.language.ast.ImplDeclaration(
                effect = com.mezze.language.ast.QualifiedRef(
                    localName = decl.effect.localName,
                    qualifier = decl.effect.qualifier?.let { com.mezze.language.ast.ModulePath(it.segments) }
                ),
                performerName = decl.performerName,
                operations = decl.operations.map { op ->
                    com.mezze.language.ast.PerformerOperation(
                        name = op.name,
                        paramType = op.paramType?.let { MezzeParser.toV1TypeAnnotation(it, file) },
                        returnType = op.returnType?.let { MezzeParser.toV1TypeAnnotation(it, file) },
                        implementation = MezzeParser.toV1Expr(op.implementation, file)
                    )
                }
            )
            ASTHasher.hashImplDeclaration(v1).toFullHexString()
        } catch (_: Exception) { null }
    }

    /** Hash a ParsedImplAbilityDecl. */
    fun hashImplAbilityDecl(decl: ParsedImplAbilityDecl, file: String): String? {
        return try {
            val v1 = com.mezze.language.typechecker.context.ContextBuilder().parsedImplAbilityDeclToV1(decl, file)
            ASTHasher.hashImplAbilityDeclaration(v1).toFullHexString()
        } catch (_: Exception) { null }
    }

    /**
     * Compute ALL content hashes for a ParsedModule.
     * Returns a new module with contentHash populated on all declarations.
     */
    fun computeAllHashes(module: ParsedModule): ParsedModule {
        val file = module.path
        val hashedDecls = module.decls.map { decl ->
            when (decl) {
                is ParsedLetDecl -> decl // LetDecl doesn't have contentHash field
                is ParsedTypeDecl -> if (decl.contentHash != null) decl else {
                    val hash = hashTypeDecl(decl, file)
                    if (hash != null) decl.copy(contentHash = hash) else decl
                }
                is ParsedAbilityDecl -> if (decl.contentHash != null) decl else {
                    val hash = hashAbilityDecl(decl, file)
                    if (hash != null) decl.copy(contentHash = hash) else decl
                }
                is ParsedEffectDecl -> if (decl.contentHash != null) decl else {
                    val hash = hashEffectDecl(decl, file)
                    if (hash != null) decl.copy(contentHash = hash) else decl
                }
                is ParsedPerformerDecl -> if (decl.contentHash != null) decl else {
                    val hash = hashPerformerDecl(decl, file)
                    if (hash != null) decl.copy(contentHash = hash) else decl
                }
                is ParsedImplAbilityDecl -> if (decl.contentHash != null) decl else {
                    val hash = hashImplAbilityDecl(decl, file)
                    if (hash != null) decl.copy(contentHash = hash) else decl
                }
                else -> decl
            }
        }
        return module.copy(decls = hashedDecls)
    }
}
