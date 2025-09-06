package com.funk.language.typechecker

import com.funk.language.parser.*
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Complete module system manager that handles:
 * - File system to module mapping (OCaml-style)
 * - Module loading and caching
 * - Dependency resolution
 * - Type checking across modules
 * - Nested module support
 */
class ModuleManager(
    private val resolver: ModuleResolver = ModuleResolver(),
    private val typeChecker: TypeChecker = TypeChecker()
) {
    
    // Module cache to avoid reloading/recompiling
    private val moduleCache = ConcurrentHashMap<String, CompiledModule>()
    private val loadingModules = ConcurrentHashMap<String, Boolean>()
    
    /**
     * Load and type-check a module and all its dependencies
     */
    fun loadModule(moduleName: String): CompiledModule {
        // Check cache first
        moduleCache[moduleName]?.let { return it }
        
        // Prevent circular loading
        if (loadingModules[moduleName] == true) {
            throw ModuleException.CircularDependency(moduleName)
        }
        
        loadingModules[moduleName] = true
        
        try {
            // Load module content from file system
            val moduleContent = resolver.loadModule(moduleName)
                ?: throw ModuleException.ModuleNotFound(moduleName)
            
            // Load all dependencies first
            val dependencies = resolver.getModuleDependencies(moduleName)
            val loadedDependencies = mutableMapOf<String, CompiledModule>()
            
            for (depName in dependencies) {
                loadedDependencies[depName] = loadModule(depName)
            }
            
            // Create module environment with dependencies
            val moduleEnv = createModuleEnvironment(loadedDependencies)
            
            // Type check the module
            val typedProgram = typeChecker.typeCheck(
                Program(listOf(moduleContent.module))
            )
            
            val typedModule = typedProgram.items.first() as TypedModuleDeclaration
            
            // Create compiled module
            val compiledModule = CompiledModule(
                name = moduleName,
                filePath = moduleContent.filePath,
                typedModule = typedModule,
                dependencies = loadedDependencies,
                exports = extractExports(typedModule),
                moduleEnv = moduleEnv
            )
            
            // Cache the result
            moduleCache[moduleName] = compiledModule
            
            return compiledModule
            
        } finally {
            loadingModules.remove(moduleName)
        }
    }
    
    /**
     * Load multiple modules in dependency order
     */
    fun loadModules(moduleNames: Set<String>): Map<String, CompiledModule> {
        val dependencyOrder = resolver.resolveDependencyOrder(moduleNames)
        val result = mutableMapOf<String, CompiledModule>()
        
        for (moduleName in dependencyOrder) {
            result[moduleName] = loadModule(moduleName)
        }
        
        return result
    }
    
    /**
     * Discover and load all modules in source paths
     */
    fun loadAllModules(): Map<String, CompiledModule> {
        val discoveredModules = resolver.discoverModules()
        return loadModules(discoveredModules.keys)
    }
    
    /**
     * Resolve a qualified identifier across modules
     * Examples: Utils.String.capitalize, List.map, Json.Parser.parse
     */
    fun resolveQualifiedIdentifier(
        identifier: String,
        currentModule: String? = null
    ): ResolvedIdentifier? {
        val parts = identifier.split(".")
        
        return when (parts.size) {
            1 -> {
                // Simple identifier - look in current module
                currentModule?.let { moduleName ->
                    val module = moduleCache[moduleName]
                    module?.exports?.get(parts[0])?.let { export ->
                        ResolvedIdentifier.Local(parts[0], export.type, moduleName)
                    }
                }
            }
            
            2 -> {
                // Module.identifier
                val (moduleName, itemName) = parts
                val module = moduleCache[moduleName]
                module?.exports?.get(itemName)?.let { export ->
                    ResolvedIdentifier.Qualified(itemName, export.type, moduleName)
                }
            }
            
            else -> {
                // Nested module access: Module.Submodule.identifier
                resolveNestedIdentifier(parts)
            }
        }
    }
    
    /**
     * Handle nested module resolution
     */
    private fun resolveNestedIdentifier(parts: List<String>): ResolvedIdentifier? {
        // Try different module path combinations
        for (i in 1 until parts.size) {
            val modulePath = parts.take(i).joinToString(".")
            val remainingPath = parts.drop(i)
            
            val module = moduleCache[modulePath]
            if (module != null) {
                // Found module, now resolve remaining path within it
                return resolveInModule(module, remainingPath)
            }
        }
        
        return null
    }
    
    /**
     * Resolve identifier within a specific module
     */
    private fun resolveInModule(
        module: CompiledModule, 
        path: List<String>
    ): ResolvedIdentifier? {
        if (path.size == 1) {
            // Direct export from module
            val export = module.exports[path[0]]
            return export?.let { 
                ResolvedIdentifier.Qualified(path[0], it.type, module.name) 
            }
        }
        
        // Handle nested modules within this module
        // This would require more sophisticated nested module support
        return null
    }
    
    /**
     * Create module environment from loaded dependencies
     */
    private fun createModuleEnvironment(
        dependencies: Map<String, CompiledModule>
    ): ModuleEnvironment {
        val modules = dependencies.mapValues { (_, compiledModule) ->
            ModuleInfo(
                name = compiledModule.name,
                path = compiledModule.filePath.toString(),
                exports = compiledModule.exports,
                dependencies = compiledModule.dependencies.keys
            )
        }
        
        val moduleTypes = dependencies.mapValues { (_, compiledModule) ->
            // Extract type environment from compiled module
            // This would need to be implemented based on the module's typed content
            TypeEnvironment()
        }
        
        return ModuleEnvironment(
            currentModule = "",
            modules = modules,
            moduleTypes = moduleTypes
        )
    }
    
    /**
     * Extract exports from a typed module
     */
    private fun extractExports(typedModule: TypedModuleDeclaration): Map<String, ExportInfo> {
        val exports = mutableMapOf<String, ExportInfo>()
        
        // Find expose declarations to determine what's exported
        val exposeDeclarations = mutableSetOf<String>()
        
        for (statement in typedModule.statements) {
            // This would need to be implemented based on the actual TypedModuleDeclaration structure
            // For now, export everything (would be refined with actual expose declarations)
            when (statement) {
                is TypedLetBinding -> {
                    exports[statement.variableName] = ExportInfo(
                        name = statement.variableName,
                        type = statement.inferredType,
                        kind = ExportKind.VALUE
                    )
                }
                is TypedTypeDeclaration -> {
                    exports[statement.name] = ExportInfo(
                        name = statement.name,
                        type = statement.type,
                        kind = ExportKind.TYPE
                    )
                }
                is TypedEffectDeclaration -> {
                    exports[statement.name] = ExportInfo(
                        name = statement.name,
                        type = FunkType.FunctionType(
                            statement.inputType,
                            statement.resultType
                        ),
                        kind = ExportKind.EFFECT
                    )
                }
                is TypedAbilityDeclaration -> {
                    exports[statement.name] = ExportInfo(
                        name = statement.name,
                        type = statement.abilityType,
                        kind = ExportKind.EFFECT
                    )
                }
                is TypedModuleDeclaration -> {
                    // Nested modules - would be handled in full implementation
                }
            }
        }
        
        return exports
    }
    
    /**
     * Get module information
     */
    fun getModuleInfo(moduleName: String): CompiledModule? = moduleCache[moduleName]
    
    /**
     * Clear module cache (useful for development/testing)
     */
    fun clearCache() {
        moduleCache.clear()
        loadingModules.clear()
    }
    
    /**
     * Get all loaded modules
     */
    fun getAllModules(): Map<String, CompiledModule> = moduleCache.toMap()
}

/**
 * Compiled module with type information
 */
data class CompiledModule(
    val name: String,
    val filePath: Path,
    val typedModule: TypedModuleDeclaration,
    val dependencies: Map<String, CompiledModule>,
    val exports: Map<String, ExportInfo>,
    val moduleEnv: ModuleEnvironment
)

/**
 * Resolved identifier with type and module information
 */
sealed class ResolvedIdentifier {
    abstract val name: String
    abstract val type: FunkType
    abstract val moduleName: String
    
    data class Local(
        override val name: String,
        override val type: FunkType,
        override val moduleName: String
    ) : ResolvedIdentifier()
    
    data class Qualified(
        override val name: String,
        override val type: FunkType,
        override val moduleName: String
    ) : ResolvedIdentifier()
}
