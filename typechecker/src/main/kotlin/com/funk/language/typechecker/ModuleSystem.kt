package com.funk.language.typechecker

import com.funk.language.parser.*

/**
 * Module system with type checking, dependency resolution, and privacy
 */

// Module metadata and type information
data class ModuleInfo(
    val name: String,
    val path: String,
    val exports: Map<String, ExportInfo> = emptyMap(),
    val imports: Map<String, ImportInfo> = emptyMap(),
    val dependencies: Set<String> = emptySet(),
    val hash: String? = null
) {
    fun isExported(name: String): Boolean = exports.containsKey(name)
    fun getExportType(name: String): FunkType? = exports[name]?.type
}

// Export information
data class ExportInfo(
    val name: String,
    val type: FunkType,
    val kind: ExportKind,
    val visibility: Visibility = Visibility.PUBLIC
)

enum class ExportKind {
    VALUE, TYPE, EFFECT, MODULE
}

enum class Visibility {
    PUBLIC, PRIVATE, INTERNAL
}

// Import information
data class ImportInfo(
    val moduleName: String,
    val importedName: String,
    val localName: String,
    val kind: ExportKind
)

// Module environment for type checking
data class ModuleEnvironment(
    val currentModule: String,
    val modules: Map<String, ModuleInfo> = emptyMap(),
    val moduleTypes: Map<String, TypeEnvironment> = emptyMap()
) {
    fun getModule(name: String): ModuleInfo? = modules[name]
    fun getModuleTypes(name: String): TypeEnvironment? = moduleTypes[name]
    
    fun withModule(module: ModuleInfo): ModuleEnvironment =
        copy(modules = modules + (module.name to module))
    
    fun withModuleTypes(moduleName: String, env: TypeEnvironment): ModuleEnvironment =
        copy(moduleTypes = moduleTypes + (moduleName to env))
}

/**
 * Module system type checker
 */
class ModuleSystemChecker {
    
    fun checkModule(
        module: ModuleDeclaration, 
        moduleEnv: ModuleEnvironment
    ): Pair<ModuleInfo, TypeEnvironment> {
        val context = TypeContext(env = TypeEnvironment())
        val exports = mutableMapOf<String, ExportInfo>()
        val imports = mutableMapOf<String, ImportInfo>()
        val dependencies = mutableSetOf<String>()
        
        // Process module statements
        for (statement in module.statements) {
            when (statement) {
                is ExposeDeclaration -> {
                    processExposeDeclaration(statement, context, exports)
                }
                
                is AccessDeclaration -> {
                    processAccessDeclaration(statement, moduleEnv, context, imports, dependencies)
                }
                
                is LetBinding -> {
                    val type = inferExpression(statement.expression, context)
                    val scheme = generalize(context.env, type)
                    context.env.extend(statement.variableName, scheme)
                }
                
                is TypeDeclaration -> {
                    val type = convertTypeDefinition(statement.definition)
                    context.env.copy(typeAliases = context.env.typeAliases + (statement.name to type))
                }
                
                is EffectDeclaration -> {
                    val inputType = convertTypeAnnotation(statement.inputType)
                    val resultType = convertTypeAnnotation(statement.resultType)
                    val effect = Effect(statement.name)
                    context.env.copy(effects = context.env.effects + (statement.name to effect))
                }
                
                is AbilityDeclaration -> {
                    processAbilityDeclaration(statement, context)
                }
            }
        }
        
        val moduleInfo = ModuleInfo(
            name = module.name,
            path = "", // Would be set from file path
            exports = exports,
            imports = imports,
            dependencies = dependencies,
            hash = computeModuleHash(module)
        )
        
        return moduleInfo to context.env
    }
    
    private fun processExposeDeclaration(
        expose: ExposeDeclaration,
        context: TypeContext,
        exports: MutableMap<String, ExportInfo>
    ) {
        for (item in expose.items) {
            val type = when (item.kind) {
                ExposeKind.TYPE -> {
                    context.env.lookupType(item.name) 
                        ?: throw StructuredTypeError(
                            TypeErrorInfo(
                                TypeErrorKind.UnboundVariable(item.name),
                                null,
                                "Type '${item.name}' not found for export"
                            )
                        )
                }
                
                ExposeKind.EFFECT -> {
                    val effect = context.env.lookupEffect(item.name)
                        ?: throw StructuredTypeError(
                            TypeErrorInfo(
                                TypeErrorKind.EffectNotFound(item.name),
                                null,
                                "Effect '${item.name}' not found for export"
                            )
                        )
                    // Convert effect to type representation
                    FunkType.TypeConstructor(effect.name, emptyList())
                }
                
                else -> {
                    val scheme = context.env.lookup(item.name)
                        ?: throw StructuredTypeError(
                            TypeErrorInfo(
                                TypeErrorKind.UnboundVariable(item.name),
                                null,
                                "Value '${item.name}' not found for export"
                            )
                        )
                    scheme.type
                }
            }
            
            exports[item.name] = ExportInfo(
                name = item.name,
                type = type,
                kind = when (item.kind) {
                    ExposeKind.TYPE -> ExportKind.TYPE
                    ExposeKind.EFFECT -> ExportKind.EFFECT
                    null -> ExportKind.VALUE
                    else -> ExportKind.VALUE
                }
            )
        }
    }
    
    private fun processAccessDeclaration(
        access: AccessDeclaration,
        moduleEnv: ModuleEnvironment,
        context: TypeContext,
        imports: MutableMap<String, ImportInfo>,
        dependencies: MutableSet<String>
    ) {
        val targetModule = moduleEnv.getModule(access.moduleName)
            ?: throw StructuredTypeError(
                TypeErrorInfo(
                    TypeErrorKind.ModuleError(
                        access.moduleName,
                        ModuleErrorKind.NOT_FOUND
                    ),
                    null,
                    "Module '${access.moduleName}' not found"
                )
            )
        
        dependencies.add(access.moduleName)
        
        for (item in access.items) {
            val exportInfo = targetModule.exports[item.name]
                ?: throw StructuredTypeError(
                    TypeErrorInfo(
                        TypeErrorKind.ModuleError(
                            access.moduleName,
                            ModuleErrorKind.PRIVATE_ACCESS
                        ),
                        null,
                        "Item '${item.name}' not exported from module '${access.moduleName}'"
                    )
                )
            
            val localName = item.alias ?: item.name
            
            // Add to local environment
            when (exportInfo.kind) {
                ExportKind.VALUE -> {
                    val scheme = TypeScheme.monomorphic(exportInfo.type)
                    context.env.extend(localName, scheme)
                }
                
                ExportKind.TYPE -> {
                    context.env.copy(typeAliases = context.env.typeAliases + (localName to exportInfo.type))
                }
                
                ExportKind.EFFECT -> {
                    val effect = Effect(localName)
                    context.env.copy(effects = context.env.effects + (localName to effect))
                }
                
                ExportKind.MODULE -> {
                    // Handle nested module imports
                }
            }
            
            imports[localName] = ImportInfo(
                moduleName = access.moduleName,
                importedName = item.name,
                localName = localName,
                kind = exportInfo.kind
            )
        }
    }
    
    private fun processAbilityDeclaration(
        ability: AbilityDeclaration,
        context: TypeContext
    ) {
        // Process ability as a collection of effects
        val effects = mutableMapOf<String, Effect>()
        
        for (effectSig in ability.effects) {
            val inputType = convertTypeAnnotation(effectSig.inputType)
            val resultType = convertTypeAnnotation(effectSig.resultType)
            val effect = Effect(effectSig.name)
            effects[effectSig.name] = effect
        }
        
        // Add ability to context
        context.env.copy(effects = context.env.effects + effects)
    }
    
    /**
     * Check module dependencies for cycles
     */
    fun checkDependencyCycles(modules: Map<String, ModuleInfo>): Result<List<String>> {
        val visited = mutableSetOf<String>()
        val recursionStack = mutableSetOf<String>()
        val topologicalOrder = mutableListOf<String>()
        
        fun dfs(moduleName: String): Boolean {
            if (recursionStack.contains(moduleName)) {
                return false // Cycle detected
            }
            
            if (visited.contains(moduleName)) {
                return true
            }
            
            visited.add(moduleName)
            recursionStack.add(moduleName)
            
            val module = modules[moduleName] ?: return true
            
            for (dependency in module.dependencies) {
                if (!dfs(dependency)) {
                    return false
                }
            }
            
            recursionStack.remove(moduleName)
            topologicalOrder.add(0, moduleName) // Add to front for reverse topological order
            
            return true
        }
        
        for (moduleName in modules.keys) {
            if (!visited.contains(moduleName)) {
                if (!dfs(moduleName)) {
                    return Result.failure(
                        StructuredTypeError(
                            TypeErrorInfo(
                                TypeErrorKind.ModuleError(
                                    moduleName,
                                    ModuleErrorKind.CIRCULAR_DEPENDENCY
                                ),
                                null,
                                "Circular dependency detected involving module '$moduleName'"
                            )
                        )
                    )
                }
            }
        }
        
        return Result.success(topologicalOrder)
    }
    
    /**
     * Resolve qualified identifiers (Module.identifier)
     */
    fun resolveQualifiedIdentifier(
        identifier: String,
        moduleEnv: ModuleEnvironment,
        context: TypeContext
    ): FunkType {
        val parts = identifier.split(".")
        
        return when (parts.size) {
            1 -> {
                // Simple identifier - look up locally
                val scheme = context.env.lookup(parts[0])
                    ?: throw StructuredTypeError.unboundVariable(parts[0])
                instantiate(scheme)
            }
            
            2 -> {
                // Qualified identifier - Module.item
                val (moduleName, itemName) = parts
                val module = moduleEnv.getModule(moduleName)
                    ?: throw StructuredTypeError(
                        TypeErrorInfo(
                            TypeErrorKind.ModuleError(moduleName, ModuleErrorKind.NOT_FOUND),
                            null,
                            "Module '$moduleName' not found"
                        )
                    )
                
                val exportInfo = module.exports[itemName]
                    ?: throw StructuredTypeError(
                        TypeErrorInfo(
                            TypeErrorKind.ModuleError(moduleName, ModuleErrorKind.PRIVATE_ACCESS),
                            null,
                            "Item '$itemName' not exported from module '$moduleName'"
                        )
                    )
                
                exportInfo.type
            }
            
            else -> {
                // Nested module access - would be implemented for deeper nesting
                throw StructuredTypeError(
                    TypeErrorInfo(
                        TypeErrorKind.UnboundVariable(identifier),
                        null,
                        "Nested module access not yet supported: $identifier"
                    )
                )
            }
        }
    }
    
    // Helper functions
    
    private fun computeModuleHash(module: ModuleDeclaration): String {
        // Simplified hash - would use proper content-based hashing
        return "hash_${module.hashCode()}"
    }
    
    private fun convertTypeAnnotation(annotation: TypeAnnotation): FunkType {
        // Delegate to main type checker
        return FunkType.VarType(TypeVar.fresh()) // Simplified
    }
    
    private fun convertTypeDefinition(definition: TypeDefinition): FunkType {
        // Delegate to main type checker
        return FunkType.VarType(TypeVar.fresh()) // Simplified
    }
    
    private fun inferExpression(expr: Expression, context: TypeContext): FunkType {
        // Delegate to main type checker
        return FunkType.VarType(TypeVar.fresh()) // Simplified
    }
    
    private fun generalize(env: TypeEnvironment, type: FunkType): TypeScheme {
        return TypeScheme.monomorphic(type) // Simplified
    }
    
    private fun instantiate(scheme: TypeScheme): FunkType {
        return scheme.type // Simplified
    }
}

/**
 * Module dependency resolver
 */
object ModuleDependencyResolver {
    
    fun resolveDependencies(
        modules: Map<String, ModuleInfo>
    ): Result<List<String>> {
        val checker = ModuleSystemChecker()
        return checker.checkDependencyCycles(modules)
    }
    
    fun buildDependencyGraph(
        modules: Map<String, ModuleInfo>
    ): Map<String, Set<String>> {
        return modules.mapValues { (_, module) -> module.dependencies }
    }
    
    fun findTransitiveDependencies(
        moduleName: String,
        modules: Map<String, ModuleInfo>
    ): Set<String> {
        val visited = mutableSetOf<String>()
        val dependencies = mutableSetOf<String>()
        
        fun collectDependencies(name: String) {
            if (visited.contains(name)) return
            visited.add(name)
            
            val module = modules[name] ?: return
            for (dep in module.dependencies) {
                dependencies.add(dep)
                collectDependencies(dep)
            }
        }
        
        collectDependencies(moduleName)
        return dependencies
    }
}

/**
 * Content-addressable module system
 */
object ContentAddressableModules {
    
    fun computeModuleHash(module: ModuleDeclaration, dependencies: Map<String, String>): String {
        // Content-based hash that includes:
        // 1. Module's own content
        // 2. Hashes of all dependencies
        val contentHash = module.hashCode().toString()
        val depHashes = dependencies.values.sorted().joinToString("")
        return "mod_${(contentHash + depHashes).hashCode()}"
    }
    
    fun isModuleCompatible(
        oldHash: String,
        newHash: String,
        compatibilityRules: Map<String, (String, String) -> Boolean>
    ): Boolean {
        // Check if module changes are backward compatible
        return oldHash == newHash || compatibilityRules.values.any { it(oldHash, newHash) }
    }
    
    fun evolveModule(
        oldModule: ModuleInfo,
        newModule: ModuleInfo
    ): ModuleEvolution {
        val addedExports = newModule.exports.keys - oldModule.exports.keys
        val removedExports = oldModule.exports.keys - newModule.exports.keys
        val changedExports = mutableMapOf<String, Pair<FunkType, FunkType>>()
        
        for (exportName in oldModule.exports.keys intersect newModule.exports.keys) {
            val oldType = oldModule.exports[exportName]!!.type
            val newType = newModule.exports[exportName]!!.type
            if (oldType != newType) {
                changedExports[exportName] = oldType to newType
            }
        }
        
        return ModuleEvolution(
            oldHash = oldModule.hash ?: "",
            newHash = newModule.hash ?: "",
            addedExports = addedExports,
            removedExports = removedExports,
            changedExports = changedExports
        )
    }
}

data class ModuleEvolution(
    val oldHash: String,
    val newHash: String,
    val addedExports: Set<String>,
    val removedExports: Set<String>,
    val changedExports: Map<String, Pair<FunkType, FunkType>>
) {
    val isBackwardCompatible: Boolean
        get() = removedExports.isEmpty() && changedExports.all { (_, change) ->
            isTypeCompatible(change.first, change.second)
        }
    
    private fun isTypeCompatible(oldType: FunkType, newType: FunkType): Boolean {
        // Simplified compatibility check
        return try {
            Unification.unify(oldType, newType)
            true
        } catch (e: StructuredTypeError) {
            false
        }
    }
}
