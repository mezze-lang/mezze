package com.mezze.language.typechecker

import com.mezze.language.ast.*
import com.mezze.language.ast.debug.ContentHashRegistry
import com.mezze.language.ast.debug.ContentHashRegistry.DeclarationKind
import com.mezze.language.ast.hashing.ASTHasher
import com.mezze.language.ast.hashing.MezzeHash
import com.mezze.language.ast.hashing.MezzeHashBuilder
import com.mezze.language.parser.MezzeParser
import com.mezze.language.parser.MezzeParser as ParserBridge
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.debug.DebugLogger
import com.mezze.language.typechecker.debug.DebugLogger.Category
import com.mezze.language.typechecker.hashing.ContentHashComputer
import com.mezze.language.typechecker.util.StringDistance
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * Pre-computed index for O(1) lookup of program declarations.
 *
 * Built once per module during loading, eliminates O(N) filterIsInstance().find() scans
 * throughout the codebase - particularly in ImportResolver.
 *
 * @property typeDeclarations Type declarations by name
 * @property abilityDeclarations Ability declarations by name
 * @property effectDeclarations Effect declarations by name
 * @property implsByPerformer Impl declarations indexed by performer name
 * @property implsByEffect Impl declarations grouped by effect name
 * @property abilityImplsByAbility Ability impls grouped by ability name
 * @property letBindings Let bindings by variable name (simple bindings only)
 * @property useDeclarations All use declarations
 * @property pubUseDeclarations All pub use declarations
 */
data class ProgramIndex(
    val typeDeclarations: Map<String, ParsedTypeDecl>,
    val abilityDeclarations: Map<String, ParsedAbilityDecl>,
    val effectDeclarations: Map<String, ParsedEffectDecl>,
    val implsByPerformer: Map<String, ImplDeclaration>,
    val implsByEffect: Map<String, List<ImplDeclaration>>,
    val abilityImplsByAbility: Map<String, List<ImplAbilityDeclaration>>,
    val abilityImplsByHash: Map<String, ImplAbilityDeclaration>,
    val letBindings: Map<String, LetBinding>,
    val useDeclarations: List<UseDeclaration>,
    val pubUseDeclarations: List<PubUseDeclaration>
) {
    companion object {
        private val syntheticLoc = Loc(file = "<index>", startLine = 0, startCol = 0, endLine = 0, endCol = 0)

        /**
         * Build an index from a ParsedModule (v2 AST).
         * Populates v2 fields directly and converts to v1 for remaining fields.
         */
        fun build(module: ParsedModule): ProgramIndex {
            val program = com.mezze.language.parser.MezzeParser().parsedModuleToProgram(module)
            return build(program)
        }

        /**
         * Build an index from a program (v1 AST — converts to v2 types).
         * Called once during module loading, all subsequent lookups are O(1).
         */
        fun build(program: Program): ProgramIndex {
            val items = program.items

            // Pre-filter by type once
            val typeDecls = items.filterIsInstance<TypeDeclaration>()
            val abilityDecls = items.filterIsInstance<AbilityDeclaration>()
            val effectDecls = items.filterIsInstance<EffectDeclaration>()
            val implDecls = items.filterIsInstance<ImplDeclaration>()
            val abilityImplDecls = items.filterIsInstance<ImplAbilityDeclaration>()
            val letBindingDecls = items.filterIsInstance<LetBinding>()

            return ProgramIndex(
                typeDeclarations = typeDecls.associate { it.name to v1TypeDeclToParsed(it) },
                abilityDeclarations = abilityDecls.associate { it.name to v1AbilityDeclToParsed(it) },
                effectDeclarations = effectDecls.associate { it.name to v1EffectDeclToParsed(it) },
                implsByPerformer = implDecls.associateBy { it.performerName },
                implsByEffect = implDecls.groupBy { it.effect.localName },
                abilityImplsByAbility = abilityImplDecls.filter { !it.isInherent }.groupBy { it.abilityName!! },
                abilityImplsByHash = abilityImplDecls.associate { impl ->
                    val hash = impl.contentHash
                        ?: com.mezze.language.ast.hashing.ASTHasher.hashImplAbilityDeclaration(impl).toFullHexString()
                    hash to (if (impl.contentHash != null) impl else impl.copy(contentHash = hash))
                },
                letBindings = letBindingDecls
                    .filter { it.isSimpleBinding }
                    .associateBy { it.variableName },
                useDeclarations = items.filterIsInstance<UseDeclaration>(),
                pubUseDeclarations = items.filterIsInstance<PubUseDeclaration>()
            )
        }

        /** Convert v1 TypeAnnotation to v2 ParsedTypeAnnotation. */
        private fun v1TypeAnnotationToParsed(ta: TypeAnnotation): ParsedTypeAnnotation = when (ta) {
            is IdentifierTypeAnnotation -> ParsedNamedType(ta.name, syntheticLoc)
            is TypeApplicationAnnotation -> ParsedTypeApp(ta.constructor, ta.args.map { v1TypeAnnotationToParsed(it) }, syntheticLoc)
            is RecordTypeAnnotation -> ParsedRecordType(
                ta.entries.map { ParsedRecordTypeEntry(it.name, v1TypeAnnotationToParsed(it.type), syntheticLoc) }, ta.isOpen, syntheticLoc
            )
            is VariantTypeAnnotation -> ParsedVariantType(
                ta.constructors.map { ParsedVariantTypeCtor(it.name, it.dataType?.let { v1TypeAnnotationToParsed(it) }, syntheticLoc) }, ta.isOpen, syntheticLoc
            )
            is FunctionTypeAnnotation -> ParsedFunctionType(
                v1TypeAnnotationToParsed(ta.paramType), v1TypeAnnotationToParsed(ta.returnType),
                ta.effects?.let { era -> ParsedEffectRow(era.effects, era.effectAnnotations.map { ParsedEffectAnnotation(it.name, it.associatedTypes.mapValues { (_, v) -> v1TypeAnnotationToParsed(v) }, syntheticLoc) }, era.isPolymorphic, era.isPerformed, syntheticLoc) },
                ta.abilityConstraints.map { c -> ParsedFnAbilityConstraint(c.abilityName, c.typeArgs.map { v1TypeAnnotationToParsed(it) }, c.associatedTypeBindings.mapValues { (_, v) -> v1TypeAnnotationToParsed(v) }, syntheticLoc) },
                syntheticLoc
            )
            is SeqTypeAnnotation -> ParsedSeqType(v1TypeAnnotationToParsed(ta.elementType), syntheticLoc)
            is QualifiedTypeAnnotation -> ParsedQualifiedType(ta.modulePath, ta.typeName, ta.args.map { v1TypeAnnotationToParsed(it) }, syntheticLoc)
            is EffectRowAnnotation -> ParsedNamedType("EffectRow", syntheticLoc)
        }

        private fun v1TypeDeclToParsed(td: TypeDeclaration): ParsedTypeDecl {
            val def: ParsedTypeDef = when (val d = td.definition) {
                is RecordDefinition -> ParsedRecordDef(d.fields.map { ParsedRecordFieldDef(it.name, v1TypeAnnotationToParsed(it.type), syntheticLoc) }, d.isOpen, syntheticLoc)
                is VariantDefinition -> ParsedVariantDef(d.constructors.map { ParsedVariantCtorDef(it.name, it.dataType?.let { v1TypeAnnotationToParsed(it) }, syntheticLoc) }, d.isOpen, syntheticLoc)
                is NewtypeDefinition -> ParsedNewtypeDef(d.constructor, v1TypeAnnotationToParsed(d.wrappedType), syntheticLoc)
            }
            return ParsedTypeDecl(
                name = td.name, typeParams = td.typeParams, definition = def,
                visibility = if (td.visibility == com.mezze.language.ast.Visibility.PUBLIC) com.mezze.language.parsedast.Visibility.PUBLIC else com.mezze.language.parsedast.Visibility.PRIVATE,
                loc = td.sourceLocation?.let { Loc(it.file ?: "<unknown>", it.line, it.column, it.line, it.column + (it.length ?: 0)) } ?: syntheticLoc,
                contentHash = td.contentHash
            )
        }

        private fun v1AbilityDeclToParsed(ad: AbilityDeclaration): ParsedAbilityDecl {
            return ParsedAbilityDecl(
                name = ad.name, typeParams = ad.typeParams, receiverTypeParam = ad.receiverTypeParam,
                associatedTypes = ad.associatedTypes,
                constraints = ad.constraints.map { ParsedAbilityConstraint(it.abilityName, it.typeParam, it.typeArgs.map { t -> ParsedNamedType(t.toString(), syntheticLoc) }, syntheticLoc) },
                dependencies = ad.dependencies.map { ParsedAbilityDep(it.abilityName, it.typeArgs.map { v1TypeAnnotationToParsed(it) }, syntheticLoc) },
                operations = ad.operations.map { op ->
                    ParsedAbilityOp(
                        name = op.name,
                        paramType = v1TypeAnnotationToParsed(op.paramType),
                        returnType = v1TypeAnnotationToParsed(op.returnType),
                        effects = op.effects?.let { era -> ParsedEffectRow(era.effects, era.effectAnnotations.map { ParsedEffectAnnotation(it.name, it.associatedTypes.mapValues { (_, v) -> v1TypeAnnotationToParsed(v) }, syntheticLoc) }, era.isPolymorphic, era.isPerformed, syntheticLoc) },
                        abilityConstraints = op.abilityConstraints.map { c -> ParsedFnAbilityConstraint(c.abilityName, c.typeArgs.map { v1TypeAnnotationToParsed(it) }, c.associatedTypeBindings.mapValues { (_, v) -> v1TypeAnnotationToParsed(v) }, syntheticLoc) },
                        loc = syntheticLoc)
                },
                visibility = com.mezze.language.parsedast.Visibility.PUBLIC,
                loc = ad.sourceLocation?.let { Loc(it.file ?: "<unknown>", it.line, it.column, it.line, it.column + (it.length ?: 0)) } ?: syntheticLoc,
                contentHash = ad.contentHash
            )
        }

        private fun v1EffectDeclToParsed(ed: EffectDeclaration): ParsedEffectDecl {
            return ParsedEffectDecl(
                name = ed.name,
                operations = ed.operations.map { op -> ParsedEffectOp(name = op.name, paramType = v1TypeAnnotationToParsed(op.paramType), returnType = v1TypeAnnotationToParsed(op.returnType), effects = op.effects, loc = syntheticLoc) },
                associatedTypes = ed.associatedTypes,
                visibility = com.mezze.language.parsedast.Visibility.PUBLIC,
                loc = ed.sourceLocation?.let { Loc(it.file ?: "<unknown>", it.line, it.column, it.line, it.column + (it.length ?: 0)) } ?: syntheticLoc,
                contentHash = ed.contentHash
            )
        }

        /**
         * Empty index for built-in modules that have no declarations.
         */
        val EMPTY = ProgramIndex(
            typeDeclarations = emptyMap(),
            abilityDeclarations = emptyMap(),
            effectDeclarations = emptyMap(),
            implsByPerformer = emptyMap(),
            implsByEffect = emptyMap(),
            abilityImplsByAbility = emptyMap(),
            abilityImplsByHash = emptyMap(),
            letBindings = emptyMap(),
            useDeclarations = emptyList(),
            pubUseDeclarations = emptyList()
        )
    }
}

/**
 * Represents a loaded module with its exports
 *
 * @property path The module path as structured ModulePath
 * @property filePath The file system path to the source file
 * @property program The parsed and type-checked program
 * @property exports Map of exported items (functions, types, etc.)
 * @property dependencies List of module dependencies
 * @property sourceHash Content hash of the source file (for cache invalidation)
 * @property index Pre-computed index for O(1) declaration lookups
 */
data class Module(
    val path: ModulePath,
    val filePath: String,
    val program: Program,
    val exports: Map<String, ExportedItem>,
    val dependencies: List<ModuleDependency>,
    val sourceHash: String? = null,
    val index: ProgramIndex = ProgramIndex.EMPTY
) {
    /** Legacy accessor for module name as string. Use path.fullPath instead. */
    val name: String get() = path.fullPath
}

/**
 * Represents an exported item from a module
 */
data class ExportedItem(
    val name: String,
    val type: ExportedItemType,
    val mezzeType: MezzeType,
    val originalName: String,
    val visibility: com.mezze.language.ast.Visibility = com.mezze.language.ast.Visibility.PUBLIC,
    val contentHash: String  // Content hash hex string for content-addressed lookup (REQUIRED)
)

/**
 * Types of exported items
 */
enum class ExportedItemType {
    FUNCTION,
    TYPE,
    CONSTRUCTOR,
    ABILITY,
    EFFECT
}

/**
 * Represents a module dependency
 */
data class ModuleDependency(
    val modulePath: String,
    val usePath: UsePath,
    val alias: String? = null
)

/**
 * Module-related errors
 */
sealed class ModuleError(message: String) : Exception(message) {
    data class ModuleNotFound(val modulePath: String, val searchPaths: List<String>) : 
        ModuleError("Module not found: $modulePath. Searched in: ${searchPaths.joinToString(", ")}")
    
    data class CircularDependency(val cycle: List<String>) : 
        ModuleError("Circular dependency detected: ${cycle.joinToString(" -> ")}")
    
    data class ParseError(val modulePath: String, val error: String) : 
        ModuleError("Failed to parse module $modulePath: $error")
    
    data class InternalModuleAccess(
        val fromModule: String,
        val toModule: String,
        val sourceLocation: com.mezze.language.ast.SourceLocation? = null
    ) : ModuleError(
        if (fromModule.isEmpty())
            "Cannot access internal module '$toModule' from outside its package"
        else
            "Module '$fromModule' cannot access internal module '$toModule'"
    )
    
    data class ExportNotFound(
        val modulePath: String,
        val exportName: String,
        val sourceLocation: com.mezze.language.ast.SourceLocation? = null,
        val availableExports: List<String> = emptyList()
    ) : ModuleError(buildString {
        append("Export '$exportName' not found in module '$modulePath'")
        if (availableExports.isNotEmpty()) {
            append(". ")
            append(StringDistance.suggestOrList(exportName, availableExports, "exports"))
        }
    })

    /**
     * Thrown when trying to import an item that exists but is not public.
     */
    data class PrivateExport(
        val modulePath: String,
        val exportName: String,
        val sourceLocation: com.mezze.language.ast.SourceLocation? = null
    ) : ModuleError("'$exportName' exists in module '$modulePath' but is not public. Add 'pub' to export it.")

    data class DeprecatedCoreModule(val modulePath: String, val suggestion: String) :
        ModuleError("Deprecated core module: $modulePath. $suggestion")
}

/**
 * Find the project root for user module resolution.
 *
 * This function looks for Mezze.toml (user projects) to determine the project root.
 * It does NOT look for stdlib - stdlib resolution is handled separately.
 *
 * @param startPath The path to start searching from (typically the source file's directory)
 * @return The project root path (directory containing Mezze.toml), or the startPath if not found
 */
private fun findProjectRoot(startPath: Path = Paths.get(".")): Path {
    var current = startPath.toAbsolutePath()

    DebugLogger.debug(Category.ModuleResolver) { "Finding project root, starting from: $current" }

    // Look for Mezze.toml in current and parent directories
    // This is how user projects are identified
    for (i in 0..10) { // Check up to 10 levels up
        val mezzeToml = current.resolve("Mezze.toml")
        if (Files.exists(mezzeToml) && Files.isRegularFile(mezzeToml)) {
            DebugLogger.debug(Category.ModuleResolver) { "Found project root (Mezze.toml): $current" }
            return current
        }

        val parent = current.parent
        if (parent == null || parent == current) break
        current = parent
    }

    // Fallback to start path if no Mezze.toml found
    DebugLogger.debug(Category.ModuleResolver) { "No Mezze.toml found, using fallback project root: ${startPath.toAbsolutePath()}" }
    return startPath.toAbsolutePath()
}

/**
 * Module resolution system for Mezze
 * Handles loading, parsing, and caching of modules
 *
 * ## Thread Safety
 *
 * **THREAD-SAFE FOR PARALLEL MODULE LOADING**: Each module parse creates a fresh parser instance.
 * The module cache uses synchronized access for thread-safe concurrent module resolution.
 *
 * Safe parallel usage:
 * ```kotlin
 * val resolver = ModuleResolver()
 * val modules = modulePaths.parallelStream().map { path ->
 *     resolver.resolveModule(path)  // Safe - thread-safe cache + fresh parsers
 * }.toList()
 * ```
 */
class ModuleResolver(
    currentPath: Path = Paths.get(".")
) {
    private val projectRoot: Path = findProjectRoot(currentPath)

    companion object {
        // Primary cache: module path -> Module (for lookup by path)
        // Access is synchronized for thread safety
        private val moduleCache = mutableMapOf<String, Module>()

        // Secondary cache: source content hash -> Module (for content-addressed deduplication)
        // Same content at different paths shares a single Module instance
        private val contentHashCache = mutableMapOf<String, Module>()

        private val cacheLock = Any()
        // Per-module locks to prevent duplicate loading of the same module
        private val moduleLoadingLocks = mutableMapOf<String, Any>()

        /**
         * Read a stdlib file from classpath resources.
         * Path is relative to resources root, e.g. "stdlib/std/abilities/numeric.mz".
         */
        @JvmStatic
        fun readStdlibFile(path: String): String? {
            return try {
                val resourceStream = ModuleResolver::class.java.classLoader.getResourceAsStream(path)
                resourceStream?.bufferedReader()?.use { it.readText() }
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Clear the module cache. This is useful for tests to ensure clean state between runs.
         */
        @JvmStatic
        fun clearCache() {
            synchronized(cacheLock) {
                moduleCache.clear()
                contentHashCache.clear()
                moduleLoadingLocks.clear()
            }
        }

        /**
         * Get or create a per-module lock for loading (thread-safe).
         */
        private fun getModuleLock(modulePath: String): Any {
            synchronized(cacheLock) {
                return moduleLoadingLocks.getOrPut(modulePath) { Any() }
            }
        }

        /**
         * Get a module from the cache by path (thread-safe).
         */
        internal fun getFromCache(modulePath: String): Module? {
            synchronized(cacheLock) {
                return moduleCache[modulePath]
            }
        }

        /**
         * Get a module from the cache by content hash (thread-safe).
         * This enables content-addressed deduplication - same content at different
         * paths returns the same Module instance.
         */
        internal fun getFromCacheByContentHash(contentHash: String): Module? {
            synchronized(cacheLock) {
                return contentHashCache[contentHash]
            }
        }

        /**
         * Put a module in the cache (thread-safe).
         * Adds to both path-based and content-hash-based caches.
         */
        internal fun putInCache(modulePath: String, module: Module) {
            synchronized(cacheLock) {
                moduleCache[modulePath] = module
                // Also add to content hash cache for deduplication
                module.sourceHash?.let { hash ->
                    contentHashCache[hash] = module
                }
            }
        }

        /**
         * Get all cached modules as a map of module paths to Programs.
         * Used for bundling modules into compiled JARs.
         */
        @JvmStatic
        fun getCacheKeys(): Set<String> {
            synchronized(cacheLock) {
                return moduleCache.keys.toSet()
            }
        }

        @JvmStatic
        fun getAllModules(): Map<String, Program> {
            synchronized(cacheLock) {
                return moduleCache.mapValues { (_, module) -> module.program }
            }
        }

        /**
         * Check if a cached module is stale (source file has changed).
         *
         * @param modulePath The module path to check
         * @param sourceContent The current source content to compare against
         * @return true if the module needs to be reloaded, false if cache is valid
         */
        @JvmStatic
        fun isModuleStale(modulePath: String, sourceContent: String): Boolean {
            val cachedModule = getFromCache(modulePath) ?: return true  // Not cached = stale
            val cachedHash = cachedModule.sourceHash ?: return true     // No hash = stale
            val currentHash = MezzeHashBuilder().append(sourceContent).build().toString()
            return cachedHash != currentHash
        }

        /**
         * Get the source hash for a cached module.
         *
         * @param modulePath The module path
         * @return The source hash, or null if not cached or no hash available
         */
        @JvmStatic
        fun getModuleSourceHash(modulePath: String): String? {
            return getFromCache(modulePath)?.sourceHash
        }

        /**
         * Look up a type declaration by name from all cached modules.
         *
         * This is used during unification when we need to expand a TypeApplication
         * (like `LogLevel`) that references a type from an imported module, even if
         * the type itself wasn't explicitly imported.
         *
         * @param typeName The simple name of the type to look up (e.g., "LogLevel")
         * @return The TypeDeclaration if found in any cached module, null otherwise
         */
        @JvmStatic
        fun lookupTypeDeclaration(typeName: String): ParsedTypeDecl? {
            synchronized(cacheLock) {
                for ((_, module) in moduleCache) {
                    val typeDecl = module.index.typeDeclarations[typeName]
                    if (typeDecl != null) {
                        return typeDecl
                    }
                }
                return null
            }
        }

        /**
         * Look up a type declaration that contains the given constructor from all cached modules.
         *
         * This is used during constraint solving when we encounter a constructor (like `Success`)
         * and need to find its declaring type (like `MyResult`) to get the full variant type.
         *
         * @param constructorName The constructor name to look up (e.g., "Success", "Info")
         * @return The ParsedTypeDecl containing that constructor, or null if not found
         */
        @JvmStatic
        fun lookupTypeDeclarationForConstructor(constructorName: String): ParsedTypeDecl? {
            synchronized(cacheLock) {
                for ((_, module) in moduleCache) {
                    for ((_, typeDecl) in module.index.typeDeclarations) {
                        val definition = typeDecl.definition
                        if (definition is ParsedVariantDef) {
                            val hasConstructor = definition.constructors.any { it.name == constructorName }
                            if (hasConstructor) {
                                return typeDecl
                            }
                        }
                    }
                }
                return null
            }
        }

        /**
         * Factory method to create a new ModuleResolver instance.
         */
        @JvmStatic
        fun create(): ModuleResolver {
            return ModuleResolver()
        }
    }

    // Track modules currently being loaded to detect circular dependencies (per-instance to track call stack)
    private val loadingStack = mutableSetOf<String>()
    
    // Core modules that define built-in types - these should be skipped
    // since built-in types are now available directly in the typechecker
    // NOTE: Only skip modules where ALL functionality is built-in and there's no stdlib wrapper file.
    // Do NOT skip collection modules with stdlib wrappers (map.mz, set.mz) - they provide wrapper functions
    private val coreTypeModules = setOf(
        "std::core::primitives",     // Primitive type implementations
        "std::core::abilities",      // Core ability definitions (NumAdd, Eq, etc.)
        "std::core::operators",      // Operator definitions
        "std::core::option",         // Option type definition
        "std::core::result",         // Result type definition
        "std::types::int",           // Int type operations (now built-in)
        "std::types::float",         // Float type operations (now built-in)
        "std::types::string",        // String type operations (now built-in)
        "std::types::boolean",       // Boolean type operations (now built-in)
        "std::types::nat",           // Natural number operations (now built-in)
        "std::types::frac"           // Fraction operations (now built-in)
        // NOTE: std::collections::seq, std::collections::map and std::collections::set are NOT skipped - they have stdlib files
    )
    
    /**
     * Check if a module path refers to a deprecated core module and provide migration guidance
     */
    private fun checkForDeprecatedCoreModule(modulePath: String) {
        val deprecatedModules = mapOf(
            "std/core/abilities" to "Abilities are now built-in. Remove this import - Eq, Ord, Inspect, etc. are always available.",
            "std/core/operators" to "Operators are now built-in. Remove this import - +, -, *, /, ==, <, etc. work automatically.",
            "std/core/primitives" to "Primitive types are now built-in. Remove this import - Int, Float, String, Boolean are always available.",
            "std/core/option" to "Option type is now built-in. For utility functions, use: import std/core/option",
            "std/core/result" to "Result type is now built-in. For utility functions, use: import std/core/result",
            "std/types/int" to "Int type is now built-in. For utility functions, use: import std/types/int",
            "std/types/float" to "Float type is now built-in. For utility functions, use: import std/types/float",
            "std/types/string" to "String type is now built-in. For utility functions, use: import std/types/string",
            "std/types/boolean" to "Boolean type is now built-in. For utility functions, use: import std/types/boolean",
            "std/types/nat" to "Nat type is now built-in. For utility functions, use: import std/types/nat"
        )
        
        deprecatedModules[modulePath]?.let { suggestion ->
            // For now, just print a warning. In strict mode, we could throw an error.
            if (System.getProperty("mezze.migration.warnings", "true") == "true") {
            }
            
            // In strict migration mode, throw an error
            if (System.getProperty("mezze.migration.strict", "false") == "true") {
                throw ModuleError.DeprecatedCoreModule(modulePath, suggestion)
            }
        }
    }

    /**
     * Resolve and load a module by its structured path.
     * This is the primary API - accepts ModulePath directly.
     */
    fun resolveModule(path: ModulePath, fromModule: ModulePath? = null): Module {
        return resolveModuleInternal(path.fullPath, fromModule?.fullPath)
    }

    /**
     * Resolve and load a module by its path string.
     * Prefer resolveModule(ModulePath) for structured paths.
     */
    fun resolveModule(modulePath: String, fromModule: String? = null): Module {
        return resolveModuleInternal(modulePath, fromModule)
    }

    /**
     * Internal implementation of module resolution.
     */
    private fun resolveModuleInternal(modulePath: String, fromModule: String? = null): Module {
        // Skip core type modules - they are now built-in
        if (modulePath in coreTypeModules) {
            return createEmptyModule(modulePath, "Core module skipped - types are now built-in")
        }

        // Use per-module lock to prevent duplicate loading of the same module
        // This implements proper double-checked locking
        val moduleLock = getModuleLock(modulePath)
        synchronized(moduleLock) {
            // Check for deprecated core modules and provide migration warnings
            checkForDeprecatedCoreModule(modulePath)

            // Check for circular dependency
            if (modulePath in loadingStack) {
                throw ModuleError.CircularDependency(loadingStack.toList() + modulePath)
            }

            // Find the file for this module
            val filePath = resolveModuleFile(modulePath)
                ?: throw ModuleError.ModuleNotFound(modulePath, getSearchPaths(modulePath))

            // Read source and compute content hash BEFORE checking cache
            // This enables content-based cache validation and deduplication
            val source = Files.readString(filePath)
            val sourceHash = MezzeHashBuilder().append(source).build().toString()

            // First, check content-hash cache for deduplication
            // If same content exists at a different path, reuse that module
            getFromCacheByContentHash(sourceHash)?.let { cachedModule ->
                DebugLogger.debug(Category.ModuleResolver) {
                    "Found module with same content hash at different path: $modulePath (reusing ${cachedModule.name})"
                }
                // Update path-based cache to point to this module
                putInCache(modulePath, cachedModule)
                return cachedModule
            }

            // Check path-based cache with content validation
            getFromCache(modulePath)?.let { cachedModule ->
                if (cachedModule.sourceHash == sourceHash) {
                    DebugLogger.debug(Category.ModuleResolver) {
                        "Found cached module with matching content hash: $modulePath"
                    }
                    return cachedModule
                } else {
                    DebugLogger.debug(Category.ModuleResolver) {
                        "Cached module has stale content hash, reloading: $modulePath"
                    }
                    // Continue to reload the module
                }
            }

            // Mark as loading
            loadingStack.add(modulePath)

            try {
                // Parse the module with file name for better error messages
                // Create fresh parser for thread safety
                // Parse to both ParsedModule (new) and v1 Program (still needed for some consumers)
                val parser = MezzeParser()
                val parsedModule = parser.parseParsedModule(source, file = filePath.toString())
                val program = parser.parse(source, file = filePath.toString())

                // Compute content hashes on v1 Program (needed for ProgramIndex and dependencies)
                val hashResult = ContentHashComputer.computeHashes(program)
                val typedProgram = hashResult.program

                // Extract exports from ParsedModule (no v1 Program dependency for exports)
                val parsedExports = extractExportsFromParsedModule(parsedModule, modulePath)
                if (parsedExports.isEmpty() && parsedModule.decls.isNotEmpty()) {
                    java.io.File("/tmp/empty_exports.txt").appendText("Module $modulePath: ${parsedModule.decls.size} decls but 0 exports. Decl types: ${parsedModule.decls.map { it::class.simpleName }}\n")
                }

                // Extract dependencies
                val dependencies = extractDependencies(typedProgram)

                // Build program index for O(1) declaration lookups
                val programIndex = ProgramIndex.build(typedProgram)

                // Create module representation
                val structuredPath = ModulePath.parse(modulePath)
                val module = Module(
                    path = structuredPath,
                    filePath = filePath.toString(),
                    program = typedProgram,
                    exports = parsedExports,
                    dependencies = dependencies,
                    sourceHash = sourceHash,
                    index = programIndex
                )

                // Cache the module (thread-safe)
                putInCache(modulePath, module)
                return module

            } catch (e: com.mezze.language.parser.ParseException) {
                // Preserve the formatted error message with code snippets
                throw ModuleError.ParseError(modulePath, e.message ?: "Unknown parse error")
            } catch (e: Exception) {
                // Catch other unexpected errors
                throw ModuleError.ParseError(modulePath, "Unexpected error: ${e.message}")
            } finally {
                loadingStack.remove(modulePath)
            }
        }
    }
    
    /**
     * Create an empty module for core modules that are now built-in
     */
    private fun createEmptyModule(modulePath: String, reason: String): Module {
        return Module(
            path = ModulePath.parse(modulePath),
            filePath = "built-in",
            program = Program(emptyList()),
            exports = emptyMap(),
            dependencies = emptyList(),
            sourceHash = null,  // Built-in modules have no source hash
            index = ProgramIndex.EMPTY
        )
    }
    
    /**
     * Load stdlib module from JAR resources
     */
    private fun loadStdlibFromResource(relativeFilePath: String): String? {
        return try {
            // Try to load from classpath resources (works in JAR and native binary)
            val resourceStream = javaClass.classLoader.getResourceAsStream(relativeFilePath)
            resourceStream?.bufferedReader()?.use { it.readText() }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Resolve a module file path from module path string.
     */
    private fun resolveModuleFile(modulePath: String): Path? {
        return resolveModuleFile(ModulePath.parse(modulePath))
    }

    /**
     * Resolve a module file path from structured ModulePath.
     */
    private fun resolveModuleFile(path: ModulePath): Path? {
        val segments = path.segments

        // Special handling for std:: modules - always look in stdlib directory
        if (segments.isNotEmpty() && segments[0] == "std") {
            val stdSegments = segments.drop(1) // Remove "std" prefix
            val relativeFilePath = "stdlib/std/" + stdSegments.joinToString("/") + ".mz"

            // First, try to load from embedded resources (works in JAR and native binary)
            val resourceContent = loadStdlibFromResource(relativeFilePath)
            if (resourceContent != null) {
                // Write to temp file and return path
                val tempFile = Files.createTempFile("mezze-stdlib-", ".mz")
                Files.writeString(tempFile, resourceContent)
                // Mark for deletion on JVM exit
                tempFile.toFile().deleteOnExit()
                return tempFile
            }

            // Fall back to filesystem (for development)
            // Note: relativeFilePath already contains "stdlib/std/..." prefix
            val possibleRoots = listOf(
                projectRoot,
                Paths.get("."),
                Paths.get(".."),
                Paths.get(".").toAbsolutePath().parent ?: Paths.get(".")
            )

            for (root in possibleRoots) {
                val stdlibPath = root.resolve(relativeFilePath)
                if (Files.exists(stdlibPath) && Files.isRegularFile(stdlibPath)) {
                    return stdlibPath
                }
            }

            return null
        }
        
        // For flat stdlib modules (int, string, float, etc.), check resources first, then filesystem
        if (segments.size == 1) {
            // Try to load from embedded resources first (works in JAR and native binary)
            val relativeFilePath = "stdlib/std/${segments[0]}.mz"
            val resourceContent = loadStdlibFromResource(relativeFilePath)
            if (resourceContent != null) {
                val tempFile = Files.createTempFile("mezze-stdlib-", ".mz")
                Files.writeString(tempFile, resourceContent)
                tempFile.toFile().deleteOnExit()
                return tempFile
            }

            // Fall back to filesystem for development
            // Note: relativeFilePath already contains "stdlib/std/..." prefix
            val flatStdlibPath = projectRoot.resolve(relativeFilePath)
            DebugLogger.debug(Category.ModuleResolver) { "Checking flat stdlib path: $flatStdlibPath (exists: ${Files.exists(flatStdlibPath)})" }
            if (Files.exists(flatStdlibPath) && Files.isRegularFile(flatStdlibPath)) {
                return flatStdlibPath
            }
        }
        
        // Regular module resolution for non-std modules
        // First, search in local project directories
        val localSearchRoots = listOf(
            projectRoot.resolve("src"),
            projectRoot.resolve("lib"),
            projectRoot
        )

        for (root in localSearchRoots) {
            val filePath = root.resolve(segments.joinToString("/") + ".mz")
            if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
                return filePath
            }
        }

        // Then, search in .deps/mezze/ for downloaded dependencies
        // For a module path like "json::parser", we look in:
        //   .deps/mezze/json-*/src/parser.mz
        //   .deps/mezze/json-*/parser.mz
        if (segments.isNotEmpty()) {
            val packageName = segments[0]
            val restOfPath = if (segments.size > 1) segments.drop(1).joinToString("/") + ".mz" else "$packageName.mz"
            val depsDir = projectRoot.resolve(".deps/mezze")

            if (Files.exists(depsDir) && Files.isDirectory(depsDir)) {
                // Find directories matching {packageName}-* (e.g., json-1.2.0)
                Files.list(depsDir).use { stream ->
                    val matchingDirs = stream
                        .filter { Files.isDirectory(it) }
                        .filter { it.fileName.toString().startsWith("$packageName-") }
                        .toList()

                    for (depDir in matchingDirs) {
                        // Search in src/ and root of the dependency
                        val searchPaths = listOf(
                            depDir.resolve("src").resolve(restOfPath),
                            depDir.resolve(restOfPath)
                        )

                        for (path in searchPaths) {
                            if (Files.exists(path) && Files.isRegularFile(path)) {
                                return path
                            }
                        }
                    }
                }
            }
        }

        return null
    }
    
    /**
     * Get possible search paths for error reporting
     */
    private fun getSearchPaths(modulePath: String): List<String> {
        return getSearchPaths(ModulePath.parse(modulePath))
    }

    /**
     * Get possible search paths for error reporting (structured path version)
     */
    private fun getSearchPaths(path: ModulePath): List<String> {
        val segments = path.segments
        
        // Special paths for std:: modules
        if (segments.isNotEmpty() && segments[0] == "std") {
            val stdSegments = segments.drop(1)
            return listOf(
                "stdlib/std/${stdSegments.joinToString("/")}.mz"
            )
        }
        
        // For flat stdlib modules, include stdlib directory
        if (segments.size == 1) {
            return listOf(
                "stdlib/${segments[0]}.mz",
                "src/${segments.joinToString("/")}.mz",
                "lib/${segments.joinToString("/")}.mz",
                "${segments.joinToString("/")}.mz"
            )
        }
        
        // Regular search paths (including .deps/mezze/)
        val packageName = segments[0]
        return listOf(
            "src/${segments.joinToString("/")}.mz",
            "lib/${segments.joinToString("/")}.mz",
            "${segments.joinToString("/")}.mz",
            ".deps/mezze/$packageName-*/src/...",
            ".deps/mezze/$packageName-*/..."
        )
    }
    
    /**
     * Extract exported items from a program
     * @param program The program to extract exports from
     * @param modulePath The module path for registry purposes (e.g., "std::int")
     * @param typeContext Optional context from TypeChecker containing inferred types
     */
    private fun extractExports(
        program: Program,
        modulePath: String? = null,
        typeContext: Map<String, MezzeType>? = null
    ): Map<String, ExportedItem> {
        val exports = mutableMapOf<String, ExportedItem>()

        for (item in program.items) {
            when (item) {
                is LetBinding -> {
                    if (item.visibility == com.mezze.language.ast.Visibility.PUBLIC) {
                        // Priority: 1) TypeChecker context, 2) explicit annotation, 3) inferredType from AST, 4) fresh var
                        val fromContext = typeContext?.get(item.variableName)
                        val fromAnnotation = item.typeAnnotation?.let { annotation ->
                            convertTypeAnnotation(annotation)
                        }
                        val fromInferred = item.inferredType

                        val mezzeType = fromContext ?: fromAnnotation ?: fromInferred ?: MezzeType.VarType(TypeVar.fresh())

                        // DEBUG key items (only for specific debugging)
                        if (item.variableName == "not") {
                            DebugLogger.debugSection(Category.ModuleResolver, "extractExports for 'not'") {
                                listOf(
                                    "Variable name: ${item.variableName}",
                                    "fromContext: $fromContext",
                                    "fromAnnotation: $fromAnnotation",
                                    "fromInferred: $fromInferred",
                                    "Final mezzeType: $mezzeType"
                                )
                            }
                        }

                        val contentHash = requireNotNull(item.contentHash) {
                            "LetBinding '${item.variableName}' missing content hash - ContentHashComputer.computeHashes() must be called before extractExports()"
                        }
                        exports[item.variableName] = ExportedItem(
                            name = item.variableName,
                            type = ExportedItemType.FUNCTION,
                            mezzeType = mezzeType,
                            originalName = item.variableName,
                            contentHash = contentHash
                        )
                        // Register hash for debugging
                        val qualifiedName = if (modulePath != null) "$modulePath::${item.variableName}" else item.variableName
                        ContentHashRegistry.register(
                            hash = contentHash,
                            qualifiedName = qualifiedName,
                            modulePath = modulePath,
                            kind = if (mezzeType is MezzeType.FunctionType) DeclarationKind.FUNCTION else DeclarationKind.CONSTANT
                        )
                    }
                }
                is TypeDeclaration -> {
                    if (item.visibility == com.mezze.language.ast.Visibility.PUBLIC) {
                        val contentHash = requireNotNull(item.contentHash) {
                            "TypeDeclaration '${item.name}' missing content hash - ContentHashComputer.computeHashes() must be called before extractExports()"
                        }
                        // Export the type alias - constructors are globally available (Roc-style)
                        exports[item.name] = ExportedItem(
                            name = item.name,
                            type = ExportedItemType.TYPE,
                            mezzeType = item.inferredType ?: MezzeType.VarType(TypeVar.fresh()),
                            originalName = item.name,
                            contentHash = contentHash
                        )
                        // Register hash for debugging
                        val qualifiedName = if (modulePath != null) "$modulePath::${item.name}" else item.name
                        ContentHashRegistry.register(
                            hash = contentHash,
                            qualifiedName = qualifiedName,
                            modulePath = modulePath,
                            kind = DeclarationKind.TYPE
                        )
                    }
                }
                is EffectDeclaration -> {
                    // Effects are ALWAYS public - no visibility check needed
                    // Priority: 1) TypeChecker context, 2) inferredType from AST, 3) fresh var
                    val fromContext = typeContext?.get(item.name)
                    val fromInferred = item.inferredType
                    val mezzeType = fromContext ?: fromInferred ?: MezzeType.VarType(TypeVar.fresh())
                    val contentHash = requireNotNull(item.contentHash) {
                        "EffectDeclaration '${item.name}' missing content hash - ContentHashComputer.computeHashes() must be called before extractExports()"
                    }

                    // Export the effect type so it can be referenced (e.g., Log.info)
                    exports[item.name] = ExportedItem(
                        name = item.name,
                        type = ExportedItemType.EFFECT,
                        mezzeType = mezzeType,
                        originalName = item.name,
                        contentHash = contentHash
                    )
                    // Register hash for debugging
                    val qualifiedName = if (modulePath != null) "$modulePath::${item.name}" else item.name
                    ContentHashRegistry.register(
                        hash = contentHash,
                        qualifiedName = qualifiedName,
                        modulePath = modulePath,
                        kind = DeclarationKind.EFFECT
                    )
                }
                is ImplDeclaration -> {
                    // Export handler names (performer names) from impl declarations
                    // Note: ImplDeclaration doesn't have a visibility field yet, so we always export them
                    // The type will be updated later when updateModuleExports is called with typeContext
                    val performerName = item.performerName
                    val fromContext = typeContext?.get(performerName)

                    // Always export the performer - if typeContext is not available yet,
                    // use a placeholder type that will be updated later
                    val mezzeType = fromContext ?: item.inferredType ?: MezzeType.VarType(TypeVar.fresh())
                    val contentHash = requireNotNull(item.contentHash) {
                        "ImplDeclaration '$performerName' missing content hash - ContentHashComputer.computeHashes() must be called before extractExports()"
                    }

                    // Don't overwrite TYPE exports with handler FUNCTION exports.
                    // This can happen when an ability impl is initially parsed as ImplDeclaration
                    // (e.g., `impl Show for Person` where Show is imported) — the performerName
                    // is the target type name and would overwrite the TypeDeclaration export.
                    val existingExport = exports[performerName]
                    if (existingExport?.type != ExportedItemType.TYPE) {
                        exports[performerName] = ExportedItem(
                            name = performerName,
                            type = ExportedItemType.FUNCTION, // Handlers are treated as values
                            mezzeType = mezzeType,
                            originalName = performerName,
                            contentHash = contentHash
                        )
                        // Register hash for debugging
                        val qualifiedName = if (modulePath != null) "$modulePath::$performerName" else performerName
                        ContentHashRegistry.register(
                            hash = contentHash,
                            qualifiedName = qualifiedName,
                            modulePath = modulePath,
                            kind = DeclarationKind.EFFECT_HANDLER
                        )
                    }
                }
                is AbilityDeclaration -> {
                    // Abilities are ALWAYS public - no visibility check needed
                    // Export ability declarations so they can be imported by other modules
                    // This enables qualified ability names like `codec::EncodeFormat` in impl blocks
                    val fromContext = typeContext?.get(item.name)
                    val fromInferred = item.inferredType
                    val mezzeType = fromContext ?: fromInferred ?: MezzeType.VarType(TypeVar.fresh())
                    val contentHash = requireNotNull(item.contentHash) {
                        "AbilityDeclaration '${item.name}' missing content hash - ContentHashComputer.computeHashes() must be called before extractExports()"
                    }

                    exports[item.name] = ExportedItem(
                        name = item.name,
                        type = ExportedItemType.ABILITY,
                        mezzeType = mezzeType,
                        originalName = item.name,
                        contentHash = contentHash
                    )
                    // Register hash for debugging
                    val qualifiedName = if (modulePath != null) "$modulePath::${item.name}" else item.name
                    ContentHashRegistry.register(
                        hash = contentHash,
                        qualifiedName = qualifiedName,
                        modulePath = modulePath,
                        kind = DeclarationKind.ABILITY
                    )
                }
                else -> {
                    // Other items (ImplAbilityDeclaration, TopLevelExpression, etc.) are not exported
                }
            }
        }

        return exports
    }

    /**
     * Extract exported items directly from a ParsedModule without needing a v1 Program.
     *
     * Walks [module.decls] and converts each public declaration to an [ExportedItem].
     * Content hashes are computed by converting each ParsedDecl to its v1 counterpart
     * (using ParserBridge) and then running the appropriate ASTHasher method — the
     * same pattern used by ContextBuilder and ContentHashComputer.
     *
     * @param module The parsed module to extract exports from.
     * @param modulePath Optional qualified module path (e.g. "std::int") used only for
     *   registering the hash in [ContentHashRegistry] for debugging.
     */
    private fun extractExportsFromParsedModule(
        module: ParsedModule,
        modulePath: String? = null
    ): Map<String, ExportedItem> {
        val exports = mutableMapOf<String, ExportedItem>()
        val file = module.path

        for (decl in module.decls) {
            when (decl) {

                // ----------------------------------------------------------------
                // Let bindings — only PUBLIC ones are exported
                // ----------------------------------------------------------------
                is ParsedLetDecl -> {
                    if (decl.visibility != com.mezze.language.parsedast.Visibility.PUBLIC) continue

                    // Derive MezzeType from the optional type annotation
                    val mezzeType: MezzeType = decl.typeAnnotation
                        ?.let { convertParsedTypeAnnotation(it) }
                        ?: MezzeType.VarType(TypeVar.fresh())

                    // Convert to v1 and compute content hash
                    val v1Binding = LetBinding(
                        pattern = ParserBridge.toV1Pattern(decl.pattern, file),
                        visibility = com.mezze.language.ast.Visibility.PUBLIC,
                        typeAnnotation = decl.typeAnnotation?.let {
                            ParserBridge.toV1TypeAnnotation(it, file)
                        },
                        expression = ParserBridge.toV1Expr(decl.value, file)
                    )
                    val contentHash = ASTHasher.hashLetBinding(v1Binding).toFullHexString()

                    // Only simple bindings have a stable single name to export
                    if (!decl.isSimpleBinding) continue
                    val name = decl.variableName

                    exports[name] = ExportedItem(
                        name = name,
                        type = ExportedItemType.FUNCTION,
                        mezzeType = mezzeType,
                        originalName = name,
                        contentHash = contentHash
                    )
                    val qualifiedName = if (modulePath != null) "$modulePath::$name" else name
                    ContentHashRegistry.register(
                        hash = contentHash,
                        qualifiedName = qualifiedName,
                        modulePath = modulePath,
                        kind = if (mezzeType is MezzeType.FunctionType) DeclarationKind.FUNCTION
                               else DeclarationKind.CONSTANT
                    )
                }

                // ----------------------------------------------------------------
                // Type declarations — only PUBLIC ones are exported
                // ----------------------------------------------------------------
                is ParsedTypeDecl -> {
                    if (decl.visibility != com.mezze.language.parsedast.Visibility.PUBLIC) continue

                    // Convert definition to v1
                    val v1Definition: TypeDefinition = when (val def = decl.definition) {
                        is ParsedRecordDef -> RecordDefinition(
                            fields = def.fields.map { f ->
                                RecordFieldDef(
                                    name = f.name,
                                    type = ParserBridge.toV1TypeAnnotation(f.type, file)
                                )
                            },
                            isOpen = def.isOpen
                        )
                        is ParsedVariantDef -> VariantDefinition(
                            constructors = def.constructors.map { c ->
                                VariantConstructorDef(
                                    name = c.name,
                                    dataType = c.dataType?.let {
                                        ParserBridge.toV1TypeAnnotation(it, file)
                                    }
                                )
                            },
                            isOpen = def.isOpen
                        )
                        is ParsedNewtypeDef -> NewtypeDefinition(
                            constructor = def.constructor,
                            wrappedType = ParserBridge.toV1TypeAnnotation(def.wrappedType, file)
                        )
                    }
                    val v1TypeDecl = TypeDeclaration(
                        name = decl.name,
                        visibility = com.mezze.language.ast.Visibility.PUBLIC,
                        typeParams = decl.typeParams,
                        definition = v1Definition
                    )
                    val contentHash = ASTHasher.hashTypeDeclaration(v1TypeDecl).toFullHexString()

                    exports[decl.name] = ExportedItem(
                        name = decl.name,
                        type = ExportedItemType.TYPE,
                        mezzeType = MezzeType.VarType(TypeVar.fresh()),
                        originalName = decl.name,
                        contentHash = contentHash
                    )
                    val qualifiedName = if (modulePath != null) "$modulePath::${decl.name}" else decl.name
                    ContentHashRegistry.register(
                        hash = contentHash,
                        qualifiedName = qualifiedName,
                        modulePath = modulePath,
                        kind = DeclarationKind.TYPE
                    )

                    // Also export each variant constructor
                    if (decl.definition is ParsedVariantDef) {
                        for (ctor in (decl.definition as ParsedVariantDef).constructors) {
                            exports[ctor.name] = ExportedItem(
                                name = ctor.name,
                                type = ExportedItemType.CONSTRUCTOR,
                                mezzeType = MezzeType.VarType(TypeVar.fresh()),
                                originalName = ctor.name,
                                contentHash = contentHash
                            )
                        }
                    }
                }

                // ----------------------------------------------------------------
                // Effect declarations — always public
                // ----------------------------------------------------------------
                is ParsedEffectDecl -> {
                    val v1Effect = EffectDeclaration(
                        name = decl.name,
                        operations = decl.operations.map { op ->
                            EffectOperation(
                                name = op.name,
                                paramType = ParserBridge.toV1TypeAnnotation(op.paramType, file),
                                returnType = ParserBridge.toV1TypeAnnotation(op.returnType, file),
                                effects = op.effects
                            )
                        },
                        associatedTypes = decl.associatedTypes
                    )
                    val contentHash = ASTHasher.hashEffectDeclaration(v1Effect).toFullHexString()

                    exports[decl.name] = ExportedItem(
                        name = decl.name,
                        type = ExportedItemType.EFFECT,
                        mezzeType = MezzeType.VarType(TypeVar.fresh()),
                        originalName = decl.name,
                        contentHash = contentHash
                    )
                    val qualifiedName = if (modulePath != null) "$modulePath::${decl.name}" else decl.name
                    ContentHashRegistry.register(
                        hash = contentHash,
                        qualifiedName = qualifiedName,
                        modulePath = modulePath,
                        kind = DeclarationKind.EFFECT
                    )

                    // Also export each operation as a FUNCTION
                    for (op in decl.operations) {
                        val opType: MezzeType = MezzeType.FunctionType(
                            paramType = convertParsedTypeAnnotation(op.paramType),
                            returnType = convertParsedTypeAnnotation(op.returnType)
                        )
                        exports[op.name] = ExportedItem(
                            name = op.name,
                            type = ExportedItemType.FUNCTION,
                            mezzeType = opType,
                            originalName = op.name,
                            contentHash = contentHash
                        )
                    }
                }

                // ----------------------------------------------------------------
                // Ability declarations — always public
                // ----------------------------------------------------------------
                is ParsedAbilityDecl -> {
                    val v1Ability = AbilityDeclaration(
                        name = decl.name,
                        typeParams = decl.typeParams,
                        receiverTypeParam = decl.receiverTypeParam,
                        associatedTypes = decl.associatedTypes,
                        constraints = decl.constraints.map { c ->
                            AbilityConstraint(
                                abilityName = c.abilityName,
                                typeParam = c.typeParam
                            )
                        },
                        dependencies = decl.dependencies.map { d ->
                            AbilityDependency(
                                abilityName = d.abilityName,
                                typeArgs = d.typeArgs.map { ParserBridge.toV1TypeAnnotation(it, file) }
                            )
                        },
                        operations = decl.operations.map { op ->
                            AbilityOperation(
                                name = op.name,
                                paramType = ParserBridge.toV1TypeAnnotation(op.paramType, file),
                                returnType = ParserBridge.toV1TypeAnnotation(op.returnType, file),
                                effects = op.effects?.let { row ->
                                    EffectRowAnnotation(
                                        effects = row.effects,
                                        effectAnnotations = row.effectAnnotations.map { ea ->
                                            EffectAnnotation(
                                                name = ea.name,
                                                associatedTypes = ea.associatedTypes.mapValues { (_, ann) ->
                                                    ParserBridge.toV1TypeAnnotation(ann, file)
                                                }
                                            )
                                        },
                                        isPolymorphic = row.isPolymorphic,
                                        isPerformed = row.isPerformed
                                    )
                                },
                                abilityConstraints = op.abilityConstraints.map { c ->
                                    FunctionAbilityConstraint(
                                        abilityName = c.abilityName,
                                        typeArgs = c.typeArgs.map { ParserBridge.toV1TypeAnnotation(it, file) },
                                        associatedTypeBindings = c.associatedTypeBindings.mapValues { (_, ann) ->
                                            ParserBridge.toV1TypeAnnotation(ann, file)
                                        }
                                    )
                                }
                            )
                        }
                    )
                    val contentHash = ASTHasher.hashAbilityDeclaration(v1Ability).toFullHexString()

                    exports[decl.name] = ExportedItem(
                        name = decl.name,
                        type = ExportedItemType.ABILITY,
                        mezzeType = MezzeType.VarType(TypeVar.fresh()),
                        originalName = decl.name,
                        contentHash = contentHash
                    )
                    val qualifiedName = if (modulePath != null) "$modulePath::${decl.name}" else decl.name
                    ContentHashRegistry.register(
                        hash = contentHash,
                        qualifiedName = qualifiedName,
                        modulePath = modulePath,
                        kind = DeclarationKind.ABILITY
                    )
                }

                // ----------------------------------------------------------------
                // Performer (effect implementation) declarations
                // ----------------------------------------------------------------
                is ParsedPerformerDecl -> {
                    val effectRef = QualifiedRef(
                        localName = decl.effect.localName,
                        qualifier = decl.effect.qualifier?.let { ModulePath(it.segments) }
                    )
                    val v1Impl = ImplDeclaration(
                        effect = effectRef,
                        performerName = decl.performerName,
                        performerParams = decl.params.map { p ->
                            PerformerParameter(
                                name = p.name,
                                type = ParserBridge.toV1TypeAnnotation(p.type, file)
                            )
                        },
                        associatedTypeBindings = decl.associatedTypeBindings.mapValues { (_, ann) ->
                            ParserBridge.toV1TypeAnnotation(ann, file)
                        },
                        operations = decl.operations.map { op ->
                            PerformerOperation(
                                name = op.name,
                                paramType = op.paramType?.let { ParserBridge.toV1TypeAnnotation(it, file) },
                                returnType = op.returnType?.let { ParserBridge.toV1TypeAnnotation(it, file) },
                                implementation = ParserBridge.toV1Expr(op.implementation, file),
                                effects = op.effects?.let { row ->
                                    EffectRowAnnotation(
                                        effects = row.effects,
                                        effectAnnotations = row.effectAnnotations.map { ea ->
                                            EffectAnnotation(
                                                name = ea.name,
                                                associatedTypes = ea.associatedTypes.mapValues { (_, ann) ->
                                                    ParserBridge.toV1TypeAnnotation(ann, file)
                                                }
                                            )
                                        },
                                        isPolymorphic = row.isPolymorphic,
                                        isPerformed = row.isPerformed
                                    )
                                }
                            )
                        }
                    )
                    val contentHash = ASTHasher.hashImplDeclaration(v1Impl).toFullHexString()

                    // Don't overwrite an existing TYPE export with a HANDLER export
                    val existingExport = exports[decl.performerName]
                    if (existingExport?.type != ExportedItemType.TYPE) {
                        exports[decl.performerName] = ExportedItem(
                            name = decl.performerName,
                            type = ExportedItemType.FUNCTION, // handlers are treated as values
                            mezzeType = MezzeType.VarType(TypeVar.fresh()),
                            originalName = decl.performerName,
                            contentHash = contentHash
                        )
                        val qualifiedName = if (modulePath != null) "$modulePath::${decl.performerName}" else decl.performerName
                        ContentHashRegistry.register(
                            hash = contentHash,
                            qualifiedName = qualifiedName,
                            modulePath = modulePath,
                            kind = DeclarationKind.EFFECT_HANDLER
                        )
                    }
                }

                // ----------------------------------------------------------------
                // Ability implementation declarations
                // ----------------------------------------------------------------
                is ParsedImplAbilityDecl -> {
                    // Ability impls are NOT exported through the exports map.
                    // They're imported via ProgramIndex.abilityImplsByHash instead.
                }

                // ----------------------------------------------------------------
                // Use / pub use declarations
                // ----------------------------------------------------------------
                is ParsedPubUseDecl -> {
                    // TODO: re-export the transitive items from the imported module
                }

                // ----------------------------------------------------------------
                // Everything else — not exported
                // ----------------------------------------------------------------
                else -> { /* ParsedUseDecl, ParsedTopLevelExpr */ }
            }
        }

        return exports
    }

    /**
     * Convert a [ParsedTypeAnnotation] to a [MezzeType].
     *
     * Handles the most common surface forms. Falls back to a fresh [TypeVar] for
     * anything complex that would require full type-checking to resolve.
     */
    private fun convertParsedTypeAnnotation(annotation: ParsedTypeAnnotation): MezzeType {
        return when (annotation) {
            is ParsedNamedType -> {
                PrimitiveTypes.fromName(annotation.name)?.let { return it }
                MezzeType.VarType(TypeVar.fresh())
            }
            is ParsedFunctionType -> {
                val effectRow = annotation.effects?.let { row ->
                    val rowVar = if (row.isPolymorphic) RowVar.fresh() else null
                    MezzeType.EffectRow.simple(row.effects.toSet(), rowVar, row.isPerformed)
                }
                MezzeType.FunctionType(
                    paramType = convertParsedTypeAnnotation(annotation.paramType),
                    returnType = convertParsedTypeAnnotation(annotation.returnType),
                    effects = effectRow
                )
            }
            is ParsedRecordType -> {
                val fields = annotation.entries.associate { e ->
                    e.name to convertParsedTypeAnnotation(e.type)
                }
                MezzeType.RecordType(fields)
            }
            is ParsedSeqType -> MezzeType.SeqType(convertParsedTypeAnnotation(annotation.elementType))
            is ParsedTypeApp -> MezzeType.VarType(TypeVar.fresh())
            is ParsedQualifiedType -> MezzeType.VarType(TypeVar.fresh())
            is ParsedVariantType -> MezzeType.VarType(TypeVar.fresh())
        }
    }

    /**
     * Extract module dependencies from use declarations
     */
    private fun extractDependencies(program: Program): List<ModuleDependency> {
        val dependencies = mutableListOf<ModuleDependency>()
        
        for (item in program.items) {
            when (item) {
                is UseDeclaration -> {
                    dependencies.add(ModuleDependency(
                        modulePath = pathToString(item.path),
                        usePath = item.path,
                        alias = item.alias
                    ))
                }
                is PubUseDeclaration -> {
                    dependencies.add(ModuleDependency(
                        modulePath = pathToString(item.path),
                        usePath = item.path,
                        alias = item.alias
                    ))
                }
                else -> {
                    // Other items don't create dependencies
                }
            }
        }
        
        return dependencies
    }
    
    /**
     * Convert UsePath to string representation for module loading.
     * Note: This uses `::` as the path separator, which is an internal convention
     * for the module loader API. User-facing code uses structured ModulePath.
     */
    private fun pathToString(path: UsePath): String {
        return when (path) {
            is ModulePath -> path.fullPath
            is SelectivePath -> ModulePath(path.module).fullPath
        }
    }
    
    /**
     * Convert TypeAnnotation to MezzeType (simplified version)
     */
    private fun convertTypeAnnotation(annotation: TypeAnnotation): MezzeType {
        return when (annotation) {
            is IdentifierTypeAnnotation -> {
                // Check primitive types first (includes Bool -> MezzeType.BoolType)
                PrimitiveTypes.fromName(annotation.name)?.let { return it }

                // Not a primitive type - treat as type variable
                MezzeType.VarType(TypeVar.fresh())
            }
            is FunctionTypeAnnotation -> {
                val effectRow = annotation.effects?.let { effectRowAnnotation ->
                    // If the effect row is polymorphic (<...> or <Effect, ...>), create a fresh row variable
                    val rowVar = if (effectRowAnnotation.isPolymorphic) RowVar.fresh() else null
                    MezzeType.EffectRow.simple(effectRowAnnotation.effects.toSet(), rowVar, effectRowAnnotation.isPerformed)
                }
                MezzeType.FunctionType(
                    paramType = convertTypeAnnotation(annotation.paramType),
                    returnType = convertTypeAnnotation(annotation.returnType),
                    effects = effectRow
                )
            }
            is RecordTypeAnnotation -> {
                val fields = annotation.entries.associate { entry ->
                    entry.name to convertTypeAnnotation(entry.type)
                }
                MezzeType.RecordType(fields)
            }
            is SeqTypeAnnotation -> {
                MezzeType.SeqType(convertTypeAnnotation(annotation.elementType))
            }
            else -> {
                MezzeType.VarType(TypeVar.fresh())
            }
        }
    }
    
    /**
     * Check if a module can access internal/private modules of another module.
     *
     * Convention (Gleam-inspired):
     * - `internal/` and `private/` folders are internal by default
     * - Internal modules can only be accessed by modules in the same parent hierarchy
     * - This is recursive: foo/internal/bar can only be accessed by modules under foo/
     *
     * Examples:
     * - math/internal/helpers can only be accessed by modules under math/
     * - mylib/private/utils can only be accessed by modules under mylib/
     * - app/internal/deep/nested can only be accessed by modules under app/
     */
    fun canAccessInternal(fromModule: String, toModule: String): Boolean {
        val from = if (fromModule.isEmpty()) null else ModulePath.parse(fromModule)
        val to = ModulePath.parse(toModule)
        return canAccessInternal(from, to)
    }

    /**
     * Check if a module can access internal/private modules of another module (structured version).
     */
    fun canAccessInternal(fromModule: ModulePath?, toModule: ModulePath): Boolean {
        val toSegments = toModule.segments
        val internalMarkers = setOf("internal", "private")

        // Find the first internal/private segment in the path
        val markerIndex = toSegments.indexOfFirst { it in internalMarkers }

        // Check if the module path STARTS with internal/ or private/
        // This handles top-level internal modules
        if (toSegments.isNotEmpty() && toSegments[0] in internalMarkers) {
            // Top-level internal modules cannot be accessed from outside
            return false
        }

        if (markerIndex < 0) {
            return true // Not an internal module, access allowed
        }

        // Extract the parent module path (everything before the internal/private marker)
        val parentSegments = toSegments.take(markerIndex)

        // Null fromModule means a top-level script, which CANNOT access internal modules
        if (fromModule == null) {
            return false
        }

        val fromSegments = fromModule.segments

        // Allow access if the fromModule is within the same parent hierarchy
        // e.g., "math/ops" can access "math/internal/helpers"
        // e.g., "math/internal/other" can access "math/internal/helpers"
        return fromSegments.take(parentSegments.size) == parentSegments
    }
    
    /**
     * Preload core stdlib modules for better performance
     * TODO: Implement proper stdlib module preloading
     */
    fun preloadCoreStdlibModules() {
        // For now, do nothing - stdlib modules will be loaded on demand
        // TODO: Implement preloading of essential stdlib modules
    }
    
    /**
     * Update a module's exports with typed program
     * Called after type-checking to populate exports with inferred types
     * @param typeContext Optional context from TypeChecker with inferred types for exports
     */
    fun updateModuleExports(modulePath: String, typedProgram: Program, typeContext: Map<String, MezzeType>? = null) {
        // Skip update if program is empty (new pipeline returns empty v1 Program)
        if (typedProgram.items.isEmpty()) return
        DebugLogger.debug(Category.ModuleResolver) { "updateModuleExports: modulePath=$modulePath, ${typedProgram.items.size} items" }

        if (DebugLogger.isCategoryEnabled(Category.ModuleResolver)) {
            // Check if identifiers are qualified
            typedProgram.items.forEach { item ->
                if (item is LetBinding) {
                    DebugLogger.debug(Category.ModuleResolver) {
                        "LetBinding '${item.variableName}' expression type: ${item.expression.javaClass.simpleName}"
                    }
                    // Check for identifiers in the expression
                    checkIdentifiersInExpression(item.expression, "  ")
                }
            }
        }

        val module = getFromCache(modulePath)
        // Rebuild index with the new typed program
        val updatedIndex = ProgramIndex.build(typedProgram)

        val updatedModule = if (module != null) {
            module.copy(
                program = typedProgram,
                exports = extractExports(typedProgram, modulePath, typeContext),
                index = updatedIndex
            )
        } else {
            // Module not in cache yet, create it
            // Note: sourceHash will be null here since we don't have access to the source
            // This path is typically used when type-checking imported modules
            Module(
                path = ModulePath.parse(modulePath),
                filePath = "", // Will be updated if needed
                program = typedProgram,
                exports = extractExports(typedProgram, modulePath, typeContext),
                dependencies = emptyList(),
                sourceHash = null,
                index = updatedIndex
            )
        }
        putInCache(modulePath, updatedModule)

        DebugLogger.debug(Category.ModuleResolver) { "Cached module with ${updatedModule.exports.size} exports" }
    }

    private fun checkIdentifiersInExpression(expr: Expression, indent: String) {
        when (expr) {
            is Identifier -> {
                DebugLogger.debug(Category.ModuleResolver) { "${indent}Identifier: '${expr.toBindingKey().toKeyString()}'" }
            }
            is FunctionCall -> {
                DebugLogger.debug(Category.ModuleResolver) { "${indent}FunctionCall:" }
                checkIdentifiersInExpression(expr.function, "$indent  ")
                checkIdentifiersInExpression(expr.arguments, "$indent  ")
            }
            is RecordLiteral -> {
                // Don't recurse into record literals for now
            }
            else -> {
                // Don't recurse into other expressions for now
            }
        }
    }

    /**
     * Qualify all unqualified variable references in a module's program.
     * This implements module lexical scoping: when a module function calls `add`,
     * it should resolve to the module's own `add` function, not one from another module.
     *
     * @param program The parsed program AST
     * @param modulePath The module path (e.g., "std::collections::seq")
     * @return A transformed program with qualified variable names
     */
    private fun qualifyModuleVariables(program: Program, modulePath: String): Program {
        // Convert module path to qualified prefix using structured path
        // e.g., "std::collections::seq" -> "std__collections__seq__"
        val pathSegments = ModulePath.parse(modulePath).segments
        val qualifiedPrefix = pathSegments.joinToString("__") + "__"

        // Collect all variable names defined in this module (excluding imports)
        val moduleDefinedNames = program.items.mapNotNull { item ->
            when (item) {
                is LetBinding -> item.variableName
                is TypeDeclaration -> item.name
                is EffectDeclaration -> item.name
                is ImplDeclaration -> item.performerName
                else -> null
            }
        }.toSet()

        // Transform all program items
        val qualifiedItems = program.items.map { item ->
            qualifyProgramItem(item, moduleDefinedNames, qualifiedPrefix)
        }

        return Program(qualifiedItems)
    }

    /**
     * Qualify a single program item
     */
    private fun qualifyProgramItem(item: ProgramItem, moduleNames: Set<String>, prefix: String): ProgramItem {
        return when (item) {
            is LetBinding -> item.copy(
                expression = qualifyExpression(item.expression, moduleNames, prefix)
            )
            // Other program items don't need qualification
            else -> item
        }
    }

    /**
     * Recursively qualify variable references in an expression
     */
    private fun qualifyExpression(expr: Expression, moduleNames: Set<String>, prefix: String): Expression {
        return when (expr) {
            is Identifier -> {
                // If this identifier refers to a module-defined function, qualify it
                if (expr.localName in moduleNames && expr.qualifier == null) {
                    // Convert prefix like "module__" to ModulePath
                    val qualifierSegments = prefix.trimEnd('_').split("__")
                    expr.copy(
                        qualifier = ModulePath(qualifierSegments)
                    )
                } else {
                    expr // Leave external references unchanged
                }
            }
            is FunctionCall -> expr.copy(
                function = qualifyExpression(expr.function, moduleNames, prefix),
                arguments = qualifyExpression(expr.arguments, moduleNames, prefix)
            )
            is LambdaExpression -> expr.copy(
                body = qualifyExpression(expr.body, moduleNames, prefix)
            )
            is DoExpression -> expr.copy(
                statements = expr.statements.map { stmt -> qualifyStatement(stmt, moduleNames, prefix) },
                result = qualifyExpression(expr.result, moduleNames, prefix)
            )
            is IfExpression -> expr.copy(
                condition = qualifyExpression(expr.condition, moduleNames, prefix),
                thenBranch = qualifyExpression(expr.thenBranch, moduleNames, prefix),
                elseBranch = qualifyExpression(expr.elseBranch, moduleNames, prefix)
            )
            is RecordLiteral -> expr.copy(
                entries = expr.entries.map { entry ->
                    when (entry) {
                        is RecordFieldEntry -> entry.copy(
                            value = entry.value?.let { qualifyExpression(it, moduleNames, prefix) }
                        )
                        is RecordSpreadEntry -> entry.copy(
                            expression = qualifyExpression(entry.expression, moduleNames, prefix)
                        )
                    }
                }
            )
            is MemberAccess -> expr.copy(
                receiver = qualifyExpression(expr.receiver, moduleNames, prefix)
            )
            is MatchExpression -> expr.copy(
                expression = qualifyExpression(expr.expression, moduleNames, prefix),
                cases = expr.cases.map { case ->
                    case.copy(
                        guard = case.guard?.let { qualifyExpression(it, moduleNames, prefix) },
                        expression = qualifyExpression(case.expression, moduleNames, prefix)
                    )
                }
            )
            is SeqLiteral -> expr.copy(
                elements = expr.elements.map { element ->
                    when (element) {
                        is SeqRegularElement -> SeqRegularElement(qualifyExpression(element.expression, moduleNames, prefix))
                        is SeqSpreadElement -> SeqSpreadElement(qualifyExpression(element.expression, moduleNames, prefix))
                    }
                }
            )
            // Literal expressions don't need qualification
            is IntLiteral, is NatLiteral, is FloatLiteral, is StringLiteral -> expr
            // Add more cases as needed
            else -> expr // Fallback: return unchanged
        }
    }

    /**
     * Qualify variable references in a statement
     */
    private fun qualifyStatement(stmt: Statement, moduleNames: Set<String>, prefix: String): Statement {
        return when (stmt) {
            is LetStatement -> stmt.copy(
                expression = qualifyExpression(stmt.expression, moduleNames, prefix)
            )
            is ExpressionStatement -> stmt.copy(
                expression = qualifyExpression(stmt.expression, moduleNames, prefix)
            )
        }
    }
}