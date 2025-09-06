package com.funk.language.typechecker

import com.funk.language.parser.*
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

/**
 * Rust-style module manager for Funk
 * Handles module loading, dependency resolution, and cross-module type checking
 */
class RustModuleManager(
    private val resolver: RustModuleResolver = RustModuleResolver()
) {
    
    // Lazy initialization to avoid circular dependency
    private val typeChecker: TypeChecker by lazy { TypeChecker() }
    
    private val compiledModules = ConcurrentHashMap<String, CompiledFunkModule>()
    private val loadingModules = ConcurrentHashMap<String, Boolean>()
    
    /**
     * Compile a module and all its dependencies
     */
    fun compileModule(
        moduleName: String, 
        currentPath: Path = Paths.get(".")
    ): CompiledFunkModule {
        val moduleKey = "${currentPath.toAbsolutePath()}::$moduleName"
        
        // Check cache
        compiledModules[moduleKey]?.let { return it }
        
        // Prevent circular dependencies
        if (loadingModules[moduleKey] == true) {
            throw RustModuleException.CircularDependency("Circular dependency detected for module: $moduleName")
        }
        
        loadingModules[moduleKey] = true
        
        try {
            // Resolve the module
            val resolvedModule = resolver.resolveModule(moduleName, currentPath)
                ?: throw RustModuleException.ModuleNotFound("Module not found: $moduleName")
            
            // Compile dependencies first
            val compiledDependencies = compileDependencies(resolvedModule)
            
            // Create module environment with dependencies
            val moduleEnv = createModuleEnvironment(compiledDependencies)
            
            // Type check the module
            val typedProgram = typeChecker.typeCheck(resolvedModule.program)
            
            // Create compiled module
            val compiledModule = CompiledFunkModule(
                name = moduleName,
                filePath = resolvedModule.filePath,
                resolvedModule = resolvedModule,
                typedProgram = typedProgram,
                dependencies = compiledDependencies,
                moduleEnv = moduleEnv
            )
            
            // Cache and return
            compiledModules[moduleKey] = compiledModule
            return compiledModule
            
        } finally {
            loadingModules.remove(moduleKey)
        }
    }
    
    /**
     * Compile all dependencies of a module
     */
    private fun compileDependencies(module: ResolvedModule): Map<String, CompiledFunkModule> {
        val dependencies = mutableMapOf<String, CompiledFunkModule>()
        val modulePath = module.filePath.parent ?: Paths.get(".")
        
        // Compile submodules declared with `mod foo`
        for (submoduleName in module.submodules) {
            dependencies[submoduleName] = compileModule(submoduleName, modulePath)
        }
        
        // Compile modules referenced in `access` declarations
        for (item in module.program.items) {
            if (item is AccessModuleDeclaration) {
                val targetModulePath = item.modulePath
                if (targetModulePath.isNotEmpty()) {
                    val rootModule = targetModulePath[0]
                    if (rootModule !in dependencies) {
                        dependencies[rootModule] = compileModule(rootModule, modulePath)
                    }
                }
            }
        }
        
        return dependencies
    }
    
    /**
     * Create module environment for type checking
     */
    private fun createModuleEnvironment(dependencies: Map<String, CompiledFunkModule>): ModuleEnvironment {
        val modules = dependencies.mapValues { (_, compiledModule) ->
            ModuleInfo(
                name = compiledModule.name,
                path = compiledModule.filePath.toString(),
                exports = extractModuleExports(compiledModule),
                dependencies = compiledModule.dependencies.keys
            )
        }
        
        return ModuleEnvironment(
            currentModule = "",
            modules = modules
        )
    }
    
    /**
     * Extract exports from compiled module for type checking
     */
    private fun extractModuleExports(compiledModule: CompiledFunkModule): Map<String, ExportInfo> {
        val exports = mutableMapOf<String, ExportInfo>()
        
        for ((name, exportedItem) in compiledModule.resolvedModule.exports) {
            when (exportedItem) {
                is ExportedItem.Value -> {
                    // Find the typed version of this binding
                    val typedBinding = compiledModule.typedProgram.items
                        .filterIsInstance<TypedLetBinding>()
                        .find { it.variableName == name }
                    
                    if (typedBinding != null) {
                        exports[name] = ExportInfo(
                            name = name,
                            type = typedBinding.inferredType,
                            kind = ExportKind.VALUE
                        )
                    }
                }
                is ExportedItem.Type -> {
                    // Find the typed version of this type declaration
                    val typedType = compiledModule.typedProgram.items
                        .filterIsInstance<TypedTypeDeclaration>()
                        .find { it.name == name }
                    
                    if (typedType != null) {
                        exports[name] = ExportInfo(
                            name = name,
                            type = typedType.type,
                            kind = ExportKind.TYPE
                        )
                    }
                }
                is ExportedItem.Effect -> {
                    // Find the typed version of this effect
                    val typedEffect = compiledModule.typedProgram.items
                        .filterIsInstance<TypedEffectDeclaration>()
                        .find { it.name == name }
                    
                    if (typedEffect != null) {
                        exports[name] = ExportInfo(
                            name = name,
                            type = FunkType.FunctionType(
                                typedEffect.inputType,
                                typedEffect.resultType
                            ),
                            kind = ExportKind.EFFECT
                        )
                    }
                }
                is ExportedItem.Ability -> {
                    // Find the typed version of this ability
                    val typedAbility = compiledModule.typedProgram.items
                        .filterIsInstance<TypedAbilityDeclaration>()
                        .find { it.name == name }
                    
                    if (typedAbility != null) {
                        exports[name] = ExportInfo(
                            name = name,
                            type = typedAbility.abilityType,
                            kind = ExportKind.EFFECT
                        )
                    }
                }
                is ExportedItem.ReExport -> {
                    // Handle re-exports by resolving the original item
                    val resolution = resolver.resolveAccess(
                        exportedItem.modulePath,
                        currentPath = compiledModule.filePath.parent ?: Paths.get(".")
                    )
                    
                    if (resolution != null) {
                        // Recursively get the export info from the target module
                        val targetModule = compileModule(
                            resolution.module.name,
                            resolution.module.filePath.parent ?: Paths.get(".")
                        )
                        val targetExports = extractModuleExports(targetModule)
                        targetExports[resolution.resolvedName]?.let { exportInfo ->
                            exports[name] = exportInfo.copy(name = name)
                        }
                    }
                }
            }
        }
        
        return exports
    }
    
    /**
     * Resolve an import statement
     */
    fun resolveImport(
        modulePath: List<String>,
        itemName: String? = null,
        currentPath: Path = Paths.get(".")
    ): ImportResolution? {
        val resolution = resolver.resolveAccess(modulePath, itemName, currentPath)
            ?: return null
        
        val compiledModule = compileModule(
            resolution.module.name,
            resolution.module.filePath.parent ?: Paths.get(".")
        )
        
        return ImportResolution(
            compiledModule = compiledModule,
            exportedItem = resolution.item,
            resolvedName = resolution.resolvedName
        )
    }
    
    /**
     * Compile a program with module resolution
     */
    fun compileProgram(
        program: Program,
        currentPath: Path = Paths.get(".")
    ): TypedProgram {
        // First, resolve all module dependencies
        val dependencies = mutableMapOf<String, CompiledFunkModule>()
        
        for (item in program.items) {
            when (item) {
                is ModDeclaration -> {
                    dependencies[item.moduleName] = compileModule(item.moduleName, currentPath)
                }
                is AccessModuleDeclaration -> {
                    if (item.modulePath.isNotEmpty()) {
                        val rootModule = item.modulePath[0]
                        if (rootModule !in dependencies) {
                            dependencies[rootModule] = compileModule(rootModule, currentPath)
                        }
                    }
                }
                is LetBinding -> { /* No dependencies */ }
                is TypeDeclaration -> { /* No dependencies */ }
                is EffectDeclaration -> { /* No dependencies */ }
                is AbilityDeclaration -> { /* No dependencies */ }
                is ModuleDeclaration -> { /* Legacy - no dependencies */ }
                is ExpressionStatement -> { /* No dependencies */ }
                is ResumeStatement -> { /* No dependencies */ }
            }
        }
        
        // Create enhanced program with resolved imports
        val enhancedProgram = resolveImports(program, dependencies, currentPath)
        
        // Type check with module environment
        val moduleEnv = createModuleEnvironment(dependencies)
        return typeChecker.typeCheck(enhancedProgram)
    }
    
    /**
     * Resolve imports in a program
     */
    private fun resolveImports(
        program: Program,
        dependencies: Map<String, CompiledFunkModule>,
        currentPath: Path
    ): Program {
        val resolvedItems = mutableListOf<ProgramItem>()
        
        for (item in program.items) {
            when (item) {
                is AccessModuleDeclaration -> {
                    // For access utils::add, modulePath = ["utils", "add"]
                    // We need to split this: module = ["utils"], item = "add"
                    if (item.modulePath.isNotEmpty()) {
                        val modulePath = item.modulePath.dropLast(1)  // ["utils"]
                        val itemName = item.modulePath.last()         // "add"
                        val resolution = resolveImport(modulePath, itemName, currentPath)
                        if (resolution != null) {
                        when (val exportedItem = resolution.exportedItem) {
                            is ExportedItem.Value -> {
                                // Add the imported function/value as a local binding
                                val localName = item.alias ?: resolution.resolvedName
                                resolvedItems.add(
                                    LetBinding(
                                        variableName = localName,
                                        typeAnnotation = null,
                                        expression = exportedItem.binding.expression,
                                        isExposed = item.isExposed
                                    )
                                )
                            }
                            is ExportedItem.Type -> {
                                // Add the imported type as a local type declaration
                                val localName = item.alias ?: resolution.resolvedName
                                resolvedItems.add(
                                    TypeDeclaration(
                                        name = localName,
                                        typeParams = exportedItem.declaration.typeParams,
                                        definition = exportedItem.declaration.definition,
                                        isExposed = item.isExposed
                                    )
                                )
                            }
                            is ExportedItem.Effect -> {
                                // Add the imported effect
                                val localName = item.alias ?: resolution.resolvedName
                                resolvedItems.add(
                                    EffectDeclaration(
                                        name = localName,
                                        inputType = exportedItem.declaration.inputType,
                                        resultType = exportedItem.declaration.resultType,
                                        isExposed = item.isExposed
                                    )
                                )
                            }
                            is ExportedItem.Ability -> {
                                // Add the imported ability
                                val localName = item.alias ?: resolution.resolvedName
                                resolvedItems.add(
                                    AbilityDeclaration(
                                        name = localName,
                                        effects = exportedItem.declaration.effects,
                                        isExposed = item.isExposed
                                    )
                                )
                            }
                            is ExportedItem.ReExport -> {
                                // Handle nested re-exports
                            }
                        }
                        }
                    }
                }
                is LetBinding -> resolvedItems.add(item)
                is TypeDeclaration -> resolvedItems.add(item)
                is EffectDeclaration -> resolvedItems.add(item)
                is AbilityDeclaration -> resolvedItems.add(item)
                is ModDeclaration -> resolvedItems.add(item)
                is ModuleDeclaration -> resolvedItems.add(item)
                is ExpressionStatement -> resolvedItems.add(item)
                is ResumeStatement -> resolvedItems.add(item)
            }
        }
        
        return Program(resolvedItems)
    }
    
    /**
     * Get compiled module by name
     */
    fun getCompiledModule(moduleName: String, currentPath: Path = Paths.get(".")): CompiledFunkModule? {
        val moduleKey = "${currentPath.toAbsolutePath()}::$moduleName"
        return compiledModules[moduleKey]
    }
    
    /**
     * Clear module cache
     */
    fun clearCache() {
        compiledModules.clear()
        loadingModules.clear()
    }
}

/**
 * Compiled module with type information
 */
data class CompiledFunkModule(
    val name: String,
    val filePath: Path,
    val resolvedModule: ResolvedModule,
    val typedProgram: TypedProgram,
    val dependencies: Map<String, CompiledFunkModule>,
    val moduleEnv: ModuleEnvironment
)

/**
 * Result of resolving an import
 */
data class ImportResolution(
    val compiledModule: CompiledFunkModule,
    val exportedItem: ExportedItem,
    val resolvedName: String
)

/**
 * Rust-style module system exceptions
 */
sealed class RustModuleException(message: String) : Exception(message) {
    data class CircularDependency(val details: String) : RustModuleException(details)
    data class ModuleNotFound(val details: String) : RustModuleException(details)
    data class ImportError(val details: String) : RustModuleException(details)
}
