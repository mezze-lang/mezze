package com.mezze.language.codebase

/**
 * Loads programs from a Codebase for execution.
 *
 * This is used at runtime to reconstruct typed modules from stored definitions.
 * V1 Program types have been deleted — use ParsedModule/TypedModule instead.
 */
object CodebaseLoader {

    /**
     * Load all modules from the codebase as ParsedModules.
     *
     * Returns a map of module path to ParsedModule, suitable for TypeChecker.preloadModules().
     *
     * @param codebase The codebase to load from
     * @return Map of module path to ParsedModule
     */
    fun loadAllParsedModules(codebase: Codebase): Map<String, com.mezze.language.parsedast.ParsedModule> {
        val modules = codebase.getAllModules()
        val moduleMap = mutableMapOf<String, com.mezze.language.parsedast.ParsedModule>()

        for (module in modules) {
            if (module.path.isNotEmpty() && module.path != "main") {
                // Create minimal ParsedModule — actual exports are in ModuleResolver cache
                moduleMap[module.path] = com.mezze.language.parsedast.ParsedModule(
                    path = module.path,
                    decls = emptyList()
                )
            }
        }

        return moduleMap
    }

    /**
     * Load modules map suitable for TypeChecker.preloadModules().
     */
    fun loadModulesForTypeChecker(codebase: Codebase): Map<String, com.mezze.language.parsedast.ParsedModule> {
        return loadAllParsedModules(codebase)
    }
}
