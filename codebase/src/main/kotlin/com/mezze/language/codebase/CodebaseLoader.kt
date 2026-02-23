package com.mezze.language.codebase

import com.mezze.language.ast.*

/**
 * Loads programs from a Codebase for execution.
 *
 * This is used at runtime to reconstruct the Program AST from stored definitions.
 */
object CodebaseLoader {

    /**
     * Load all programs from the codebase.
     *
     * Returns a map of module path to Program, suitable for TypeChecker.preloadModules().
     * Also returns the main program separately.
     *
     * @param codebase The codebase to load from
     * @return Pair of (main program, module map)
     */
    fun loadAll(codebase: Codebase): Pair<Program, Map<String, Program>> {
        val modules = codebase.getAllModules()
        val modulePrograms = mutableMapOf<String, Program>()
        var mainProgram: Program? = null

        for (module in modules) {
            val program = loadModule(codebase, module.path)

            if (module.path.isEmpty() || module.path == "main") {
                mainProgram = program
            } else {
                modulePrograms[module.path] = program
            }
        }

        // If no explicit main module, create one from definitions without module path
        if (mainProgram == null) {
            mainProgram = loadDefinitionsWithoutModule(codebase)
        }

        return Pair(mainProgram, modulePrograms)
    }

    /**
     * Load a single module as a Program.
     */
    fun loadModule(codebase: Codebase, modulePath: String): Program {
        val definitions = codebase.getDefinitionsByModule(modulePath)
        val items = definitions.mapNotNull { def ->
            deserializeDefinition(def)
        }
        return Program(items)
    }

    /**
     * Load definitions that don't belong to any module (main script).
     */
    private fun loadDefinitionsWithoutModule(codebase: Codebase): Program {
        val allHashes = codebase.getAllDefinitionHashes()
        val items = mutableListOf<ProgramItem>()

        for (hash in allHashes) {
            val def = codebase.getDefinition(hash) ?: continue
            if (def.modulePath == null || def.modulePath.isEmpty()) {
                deserializeDefinition(def)?.let { items.add(it) }
            }
        }

        return Program(items)
    }

    /**
     * Deserialize a stored definition back to a ProgramItem.
     */
    private fun deserializeDefinition(def: StoredDefinition): ProgramItem? {
        return try {
            when (def.kind) {
                DefinitionKind.FUNCTION ->
                    CodebaseSerialization.deserializeLetBinding(def.astBlob)
                DefinitionKind.TYPE_RECORD, DefinitionKind.TYPE_VARIANT, DefinitionKind.TYPE_NEWTYPE ->
                    CodebaseSerialization.deserializeTypeDeclaration(def.astBlob)
                DefinitionKind.ABILITY ->
                    CodebaseSerialization.deserializeAbilityDeclaration(def.astBlob)
                DefinitionKind.EFFECT ->
                    CodebaseSerialization.deserializeEffectDeclaration(def.astBlob)
                DefinitionKind.EFFECT_IMPL ->
                    CodebaseSerialization.deserializeImplDeclaration(def.astBlob)
                DefinitionKind.ABILITY_IMPL ->
                    CodebaseSerialization.deserializeImplAbilityDeclaration(def.astBlob)
                DefinitionKind.MACRO, DefinitionKind.FOREIGN ->
                    null // Not yet supported
            }
        } catch (e: Exception) {
            System.err.println("Warning: Failed to deserialize definition ${def.name}: ${e.message}")
            null
        }
    }

    /**
     * Load modules map suitable for TypeChecker.preloadModules().
     */
    fun loadModulesForTypeChecker(codebase: Codebase): Map<String, Program> {
        val modules = codebase.getAllModules()
        return modules
            .filter { it.path.isNotEmpty() && it.path != "main" }
            .associate { it.path to loadModule(codebase, it.path) }
    }
}
