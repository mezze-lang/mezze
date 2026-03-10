package com.mezze.language.codebase

import com.mezze.language.ast.*
import com.mezze.language.ast.hashing.MezzeHash

/**
 * Populates a Codebase from type-checked programs.
 *
 * This bridges the TypeChecker output to the Codebase storage.
 */
object CodebasePopulator {

    /**
     * Populate a codebase with a main program and its modules.
     *
     * @param codebase The codebase to populate
     * @param mainProgram The main program (entry point)
     * @param modules Map of module path to program
     * @param mainModulePath Module path for the main program (null for scripts)
     */
    fun populate(
        codebase: Codebase,
        mainProgram: Program,
        modules: Map<String, Program>,
        mainModulePath: String? = null
    ) {
        codebase.beginTransaction()
        try {
            // Store all modules first
            for ((modulePath, program) in modules) {
                storeModule(codebase, modulePath, program, isStdlib = modulePath.startsWith("std"))
            }

            // Store main program
            storeProgram(codebase, mainProgram, mainModulePath)

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

    private fun storeModule(
        codebase: Codebase,
        modulePath: String,
        program: Program,
        isStdlib: Boolean
    ) {
        // Register module
        codebase.putModule(StoredModule(
            path = modulePath,
            filePath = null,
            sourceHash = null,
            isStdlib = isStdlib
        ))

        // Store all definitions in the module
        storeProgram(codebase, program, modulePath)
    }

    private fun storeProgram(
        codebase: Codebase,
        program: Program,
        modulePath: String?
    ) {
        for (item in program.items) {
            storeItem(codebase, item, modulePath)
        }
    }

    private fun storeItem(
        codebase: Codebase,
        item: ProgramItem,
        modulePath: String?
    ) {
        when (item) {
            is LetBinding -> storeLetBinding(codebase, item, modulePath)
            is TypeDeclaration -> storeTypeDeclaration(codebase, item, modulePath)
            is AbilityDeclaration -> storeAbilityDeclaration(codebase, item, modulePath)
            is EffectDeclaration -> storeEffectDeclaration(codebase, item, modulePath)
            is ImplDeclaration -> storeImplDeclaration(codebase, item, modulePath)
            is ImplAbilityDeclaration -> storeImplAbilityDeclaration(codebase, item, modulePath)
            // Skip items that don't have content hashes
            is UseDeclaration, is PubUseDeclaration, is TopLevelExpression -> {}
        }
    }

    private fun storeLetBinding(
        codebase: Codebase,
        binding: LetBinding,
        modulePath: String?
    ) {
        val hashString = binding.contentHash ?: return // Skip if no hash
        val hash = MezzeHash.fromHex(hashString)

        val visibility = convertVisibility(binding.visibility)

        codebase.putDefinition(StoredDefinition(
            hash = hash,
            kind = DefinitionKind.FUNCTION,
            name = binding.variableName,
            modulePath = modulePath,
            visibility = visibility,
            astBlob = CodebaseSerialization.serializeLetBinding(binding)
        ))

        // Register name
        codebase.putName(modulePath, binding.variableName, hash)

        // Store type if available
        binding.inferredType?.let { type ->
            codebase.putType(hash, type, prettyPrintType(type))
        }

        // Register export if public
        if (binding.visibility == com.mezze.language.ast.Visibility.PUBLIC && modulePath != null) {
            codebase.putExport(modulePath, binding.variableName, hash, DefinitionKind.FUNCTION)
        }
    }

    private fun storeTypeDeclaration(
        codebase: Codebase,
        decl: TypeDeclaration,
        modulePath: String?
    ) {
        val hashString = decl.contentHash ?: return
        val hash = MezzeHash.fromHex(hashString)

        val visibility = convertVisibility(decl.visibility)
        val kind = when (decl.definition) {
            is RecordDefinition -> DefinitionKind.TYPE_RECORD
            is VariantDefinition -> DefinitionKind.TYPE_VARIANT
            is NewtypeDefinition -> DefinitionKind.TYPE_NEWTYPE
        }

        codebase.putDefinition(StoredDefinition(
            hash = hash,
            kind = kind,
            name = decl.name,
            modulePath = modulePath,
            visibility = visibility,
            astBlob = CodebaseSerialization.serializeTypeDeclaration(decl)
        ))

        codebase.putName(modulePath, decl.name, hash)

        if (decl.visibility == com.mezze.language.ast.Visibility.PUBLIC && modulePath != null) {
            codebase.putExport(modulePath, decl.name, hash, kind)
        }
    }

    private fun storeAbilityDeclaration(
        codebase: Codebase,
        decl: AbilityDeclaration,
        modulePath: String?
    ) {
        val hashString = decl.contentHash ?: return
        val hash = MezzeHash.fromHex(hashString)

        // Abilities are always public
        codebase.putDefinition(StoredDefinition(
            hash = hash,
            kind = DefinitionKind.ABILITY,
            name = decl.name,
            modulePath = modulePath,
            visibility = Visibility.PUBLIC,
            astBlob = CodebaseSerialization.serializeAbilityDeclaration(decl)
        ))

        codebase.putName(modulePath, decl.name, hash)

        // Abilities are always exported
        if (modulePath != null) {
            codebase.putExport(modulePath, decl.name, hash, DefinitionKind.ABILITY)
        }
    }

    private fun storeEffectDeclaration(
        codebase: Codebase,
        decl: EffectDeclaration,
        modulePath: String?
    ) {
        val hashString = decl.contentHash ?: return
        val hash = MezzeHash.fromHex(hashString)

        // Effects are always public
        codebase.putDefinition(StoredDefinition(
            hash = hash,
            kind = DefinitionKind.EFFECT,
            name = decl.name,
            modulePath = modulePath,
            visibility = Visibility.PUBLIC,
            astBlob = CodebaseSerialization.serializeEffectDeclaration(decl)
        ))

        codebase.putName(modulePath, decl.name, hash)

        // Effects are always exported
        if (modulePath != null) {
            codebase.putExport(modulePath, decl.name, hash, DefinitionKind.EFFECT)
        }
    }

    private fun storeImplDeclaration(
        codebase: Codebase,
        decl: ImplDeclaration,
        modulePath: String?
    ) {
        val hashString = decl.contentHash ?: return
        val hash = MezzeHash.fromHex(hashString)

        // Effect impls are typically public
        val visibility = Visibility.PUBLIC

        codebase.putDefinition(StoredDefinition(
            hash = hash,
            kind = DefinitionKind.EFFECT_IMPL,
            name = "${decl.effect.localName}::${decl.performerName}",
            modulePath = modulePath,
            visibility = visibility,
            astBlob = CodebaseSerialization.serializeImplDeclaration(decl)
        ))

        // Register by performer name for lookup
        codebase.putName(modulePath, decl.performerName, hash)
    }

    private fun storeImplAbilityDeclaration(
        codebase: Codebase,
        decl: ImplAbilityDeclaration,
        modulePath: String?
    ) {
        val hashString = decl.contentHash ?: return
        val hash = MezzeHash.fromHex(hashString)

        val visibility = Visibility.PUBLIC

        // Create a name that includes ability and types for uniqueness
        val typeNames = decl.targetTypes.joinToString(",") { printTypeAnnotation(it) }
        val implName = "${decl.ability.localName}::[$typeNames]"

        codebase.putDefinition(StoredDefinition(
            hash = hash,
            kind = DefinitionKind.ABILITY_IMPL,
            name = implName,
            modulePath = modulePath,
            visibility = visibility,
            astBlob = CodebaseSerialization.serializeImplAbilityDeclaration(decl)
        ))

        codebase.putName(modulePath, implName, hash)
    }

    /**
     * Convert AST Visibility to Codebase Visibility.
     */
    private fun convertVisibility(astVisibility: com.mezze.language.ast.Visibility): Visibility {
        return when (astVisibility) {
            com.mezze.language.ast.Visibility.PUBLIC -> Visibility.PUBLIC
            com.mezze.language.ast.Visibility.PRIVATE -> Visibility.PRIVATE
        }
    }

    /**
     * Simple type annotation printer for impl names.
     */
    private fun printTypeAnnotation(ann: TypeAnnotation): String {
        return when (ann) {
            is IdentifierTypeAnnotation -> ann.name
            is QualifiedTypeAnnotation -> "${ann.modulePath.joinToString("::")}::${ann.typeName}"
            is TypeApplicationAnnotation -> "${ann.constructor}[${ann.args.joinToString(",") { printTypeAnnotation(it) }}]"
            is FunctionTypeAnnotation -> "Fn"
            is RecordTypeAnnotation -> "Record"
            is VariantTypeAnnotation -> "Variant"
            is SeqTypeAnnotation -> "Seq[${printTypeAnnotation(ann.elementType)}]"
            is EffectRowAnnotation -> "<${ann.effects.joinToString(",")}>"
        }
    }
}
