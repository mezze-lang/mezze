package com.mezze.language.codebase

import com.mezze.language.ast.*
import com.mezze.language.typechecker.TypeCheckResult
import com.mezze.language.ast.hashing.MezzeHash

/**
 * Populates a Codebase from type-checked programs.
 *
 * This bridges the TypeChecker output to the Codebase storage.
 * Uses v2 TypedModule for definitions with types, falling back to ParsedModule
 * for structural info.
 */
object CodebasePopulator {

    /**
     * Populate a codebase with a main program and its modules.
     *
     * @param codebase The codebase to populate
     * @param typeCheckResult The TypeCheckResult containing the typedModule
     * @param modules Map of module path to ParsedModule
     * @param mainModulePath Module path for the main program (null for scripts)
     */
    fun populate(
        codebase: Codebase,
        typeCheckResult: TypeCheckResult,
        modules: Map<String, com.mezze.language.parsedast.ParsedModule> = emptyMap(),
        mainModulePath: String? = null
    ) {
        codebase.beginTransaction()
        try {
            // Store all modules
            for ((modulePath, _) in modules) {
                codebase.putModule(StoredModule(
                    path = modulePath,
                    filePath = null,
                    sourceHash = null,
                    isStdlib = modulePath.startsWith("std")
                ))
            }

            // Store main program's typed declarations
            val typedModule = typeCheckResult.typedModule
            if (typedModule != null) {
                storeTypedModule(codebase, typedModule, mainModulePath)
            }

            // Register main module
            if (mainModulePath != null) {
                codebase.putModule(StoredModule(
                    path = mainModulePath,
                    filePath = null,
                    sourceHash = null,
                    isStdlib = false
                ))
            }

            codebase.commit()
        } catch (e: Exception) {
            codebase.rollback()
            throw e
        }
    }

    /**
     * Store typed declarations from a TypedModule into the codebase.
     */
    private fun storeTypedModule(
        codebase: Codebase,
        typedModule: com.mezze.language.typedast.TypedModule,
        modulePath: String?
    ) {
        // Store let bindings
        for (decl in typedModule.decls.filterIsInstance<com.mezze.language.typedast.TypedLetDecl>()) {
            val hash = MezzeHash.fromHex(decl.hash.hex)
            val visibility = if (decl.isPublic) Visibility.PUBLIC else Visibility.PRIVATE
            val type = decl.scheme.type

            codebase.putDefinition(StoredDefinition(
                hash = hash,
                kind = DefinitionKind.FUNCTION,
                name = decl.name,
                modulePath = modulePath,
                visibility = visibility,
                astBlob = CodebaseSerialization.serializeType(type)
            ))

            codebase.putName(modulePath, decl.name, hash)
            codebase.putType(hash, type, prettyPrintType(type))

            if (decl.isPublic && modulePath != null) {
                codebase.putExport(modulePath, decl.name, hash, DefinitionKind.FUNCTION)
            }
        }

        // Store ability declarations
        for (decl in typedModule.abilityDecls) {
            val hash = MezzeHash.fromHex(decl.hash.hex)

            codebase.putDefinition(StoredDefinition(
                hash = hash,
                kind = DefinitionKind.ABILITY,
                name = decl.name,
                modulePath = modulePath,
                visibility = Visibility.PUBLIC,
                astBlob = ByteArray(0) // Ability structure stored in TypedModule
            ))

            codebase.putName(modulePath, decl.name, hash)

            if (modulePath != null) {
                codebase.putExport(modulePath, decl.name, hash, DefinitionKind.ABILITY)
            }
        }

        // Store effect declarations
        for (decl in typedModule.effectDecls) {
            val hash = MezzeHash.fromHex(decl.hash.hex)

            codebase.putDefinition(StoredDefinition(
                hash = hash,
                kind = DefinitionKind.EFFECT,
                name = decl.name,
                modulePath = modulePath,
                visibility = Visibility.PUBLIC,
                astBlob = ByteArray(0) // Effect structure stored in TypedModule
            ))

            codebase.putName(modulePath, decl.name, hash)

            if (modulePath != null) {
                codebase.putExport(modulePath, decl.name, hash, DefinitionKind.EFFECT)
            }
        }
    }
}
