package com.mezze.language.typechecker

import com.mezze.language.ast.*
import com.mezze.language.ast.debug.ContentHashRegistry
import com.mezze.language.ast.debug.ContentHashRegistry.DeclarationKind
import com.mezze.language.ast.hashing.MezzeHash
import com.mezze.language.ast.hashing.MezzeHashBuilder
import com.mezze.language.parser.MezzeParser
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.debug.DebugLogger
import com.mezze.language.typechecker.debug.DebugLogger.Category
import com.mezze.language.typechecker.hashing.ContentHashComputer
import com.mezze.language.typechecker.util.StringDistance
import com.mezze.language.typechecker.util.TypeAnnotationConverter
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
    val performersByName: Map<String, ParsedPerformerDecl>,
    val performersByEffect: Map<String, List<ParsedPerformerDecl>>,
    val abilityImplsByAbility: Map<String, List<ParsedImplAbilityDecl>>,
    val abilityImplsByHash: Map<String, ParsedImplAbilityDecl>,
    val letBindings: Map<String, ParsedLetDecl>,
    val useDeclarations: List<ParsedUseDecl>,
    val pubUseDeclarations: List<ParsedPubUseDecl>
) {
    /** @deprecated Use performersByName — kept for source compat during migration */
    @Deprecated("Use performersByName", ReplaceWith("performersByName"))
    val implsByPerformer: Map<String, ParsedPerformerDecl> get() = performersByName

    /** @deprecated Use performersByEffect — kept for source compat during migration */
    @Deprecated("Use performersByEffect", ReplaceWith("performersByEffect"))
    val implsByEffect: Map<String, List<ParsedPerformerDecl>> get() = performersByEffect

    companion object {
        private val syntheticLoc = Loc(file = "<index>", startLine = 0, startCol = 0, endLine = 0, endCol = 0)

        /**
         * Build an index from a ParsedModule (v2 AST).
         * Populates all fields directly from v2 types — no v1 conversion.
         */
        fun build(module: ParsedModule): ProgramIndex {
            val decls = module.decls

            val typeDecls = decls.filterIsInstance<ParsedTypeDecl>()
                .associateBy { it.name }
            val abilityDecls = decls.filterIsInstance<ParsedAbilityDecl>()
                .associateBy { it.name }
            val effectDecls = decls.filterIsInstance<ParsedEffectDecl>()
                .associateBy { it.name }

            val performerDecls = decls.filterIsInstance<ParsedPerformerDecl>()
            val abilityImplDecls = decls.filterIsInstance<ParsedImplAbilityDecl>()
            val letDecls = decls.filterIsInstance<ParsedLetDecl>()

            // Compute content hashes using ParsedDeclHasher (v2-native, Unison-style)
            val hasher = com.mezze.language.parsedast.ParsedDeclHasher
            val file = module.path

            val hashedEffects = effectDecls.mapValues { (_, decl) ->
                if (decl.contentHash != null) decl
                else {
                    val hash = hasher.hashEffectDecl(decl, file)
                    if (hash != null) decl.copy(contentHash = hash) else decl
                }
            }

            val hashedPerformers = performerDecls.map { perf ->
                if (perf.contentHash != null) perf
                else {
                    val hash = hasher.hashPerformerDecl(perf, file)
                    if (hash != null) perf.copy(contentHash = hash) else perf
                }
            }

            val hashedAbilityImpls = abilityImplDecls.map { impl ->
                if (impl.contentHash != null) impl
                else {
                    val hash = hasher.hashImplAbilityDecl(impl, file)
                    if (hash != null) impl.copy(contentHash = hash) else impl
                }
            }

            return ProgramIndex(
                typeDeclarations = typeDecls,
                abilityDeclarations = abilityDecls,
                effectDeclarations = hashedEffects,
                performersByName = hashedPerformers.associateBy { it.performerName },
                performersByEffect = hashedPerformers.groupBy { it.effect.localName },
                abilityImplsByAbility = hashedAbilityImpls
                    .filter { !it.isInherent }
                    .groupBy { it.abilityName!! },
                abilityImplsByHash = hashedAbilityImpls.associateBy { impl ->
                    impl.contentHash!!
                },
                letBindings = letDecls
                    .filter { it.isSimpleBinding }
                    .associateBy { it.variableName },
                useDeclarations = decls.filterIsInstance<ParsedUseDecl>(),
                pubUseDeclarations = decls.filterIsInstance<ParsedPubUseDecl>()
            )
        }

        /**
         * Empty index for built-in modules that have no declarations.
         */
        val EMPTY = ProgramIndex(
            typeDeclarations = emptyMap(),
            abilityDeclarations = emptyMap(),
            effectDeclarations = emptyMap(),
            performersByName = emptyMap(),
            performersByEffect = emptyMap(),
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
 * @property exports Map of exported items (functions, types, etc.)
 * @property dependencies List of module dependencies
 * @property sourceHash Content hash of the source file (for cache invalidation)
 * @property index Pre-computed index for O(1) declaration lookups
 */
data class Module(
    val path: ModulePath,
    val filePath: String,
    val exports: Map<String, ExportedItem>,
    val dependencies: List<ModuleDependency>,
    val sourceHash: String? = null,
    val index: ProgramIndex = ProgramIndex.EMPTY,
    /** V2 ParsedModule — the primary AST. */
    val parsedModule: com.mezze.language.parsedast.ParsedModule? = null
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
    val visibility: com.mezze.language.parsedast.Visibility = com.mezze.language.parsedast.Visibility.PUBLIC,
    val contentHash: com.mezze.language.typedast.Hash  // Content hash for content-addressed lookup (REQUIRED)
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
        val loc: com.mezze.language.parsedast.Loc? = null
    ) : ModuleError(
        if (fromModule.isEmpty())
            "Cannot access internal module '$toModule' from outside its package"
        else
            "Module '$fromModule' cannot access internal module '$toModule'"
    )
    
    data class ExportNotFound(
        val modulePath: String,
        val exportName: String,
        val loc: com.mezze.language.parsedast.Loc? = null,
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
        val loc: com.mezze.language.parsedast.Loc? = null
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
         * Optional MezzeStore for parsed module caching.
         * When set, ModuleResolver reads cached ParsedModules from the store
         * instead of going through ParsedModuleCacheHolder.
         * Set by CLI/LSP at startup via setStore().
         */
        @Volatile
        private var store: com.mezze.language.store.MezzeStore? = null

        @JvmStatic
        fun setStore(store: com.mezze.language.store.MezzeStore) {
            this.store = store
        }

        /**
         * Try loading a cached ParsedModule from the store.
         * Returns null on miss or any error — caller falls through to parser.
         */
        private fun tryLoadParsedFromStore(modulePath: String, sourceHash: String): com.mezze.language.parsedast.ParsedModule? {
            val s = store ?: return null
            return try {
                val blob = s.getParsedModule(modulePath, sourceHash) ?: return null
                com.mezze.language.store.StoreSerialization.deserializeParsedModule(blob)
            } catch (_: Exception) {
                null
            }
        }

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
         * Watch-mode targeted eviction: drop entries for the given module paths.
         * Stdlib + unchanged user modules stay cached.
         *
         * Also drops the corresponding content-hash entries so a future load with
         * the (now-changed) source content doesn't get a stale hit.
         */
        @JvmStatic
        fun invalidateModulePaths(modulePaths: Collection<String>) {
            synchronized(cacheLock) {
                for (path in modulePaths) {
                    val mod = moduleCache.remove(path)
                    mod?.sourceHash?.let { contentHashCache.remove(it) }
                    moduleLoadingLocks.remove(path)
                }
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
         * Public read-only snapshot of (modulePath -> Module) for tooling
         * (e.g. mez dev watch mode needs file paths to register watches).
         */
        @JvmStatic
        fun snapshot(): Map<String, Module> = synchronized(cacheLock) { moduleCache.toMap() }

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
        fun getAllModules(): Map<String, com.mezze.language.parsedast.ParsedModule> {
            return getAllParsedModules()
        }

        /**
         * Get all cached modules as ParsedModules.
         * Returns only modules that have a parsedModule stored (all modules loaded
         * through the normal loadModule path).
         */
        @JvmStatic
        fun getAllParsedModules(): Map<String, ParsedModule> {
            synchronized(cacheLock) {
                return moduleCache.mapNotNull { (key, module) ->
                    module.parsedModule?.let { key to it }
                }.toMap()
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
         * @return The ParsedTypeDecl if found in any cached module, null otherwise
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
                // Try the parsed-modules cache (store → fallback to ParsedModuleCacheHolder → parse).
                val cached = tryLoadParsedFromStore(modulePath, sourceHash)
                    ?: com.mezze.language.parser.ParsedModuleCacheHolder.get(filePath.toString(), sourceHash)
                val parsedModule = cached ?: run {
                    val parser = MezzeParser()
                    parser.parseParsedModule(source, file = filePath.toString())
                }

                // Extract exports from ParsedModule (v2 path)
                val parsedExports = extractExportsFromParsedModule(parsedModule, modulePath)

                // Extract dependencies from ParsedModule (v2 path)
                val dependencies = extractDependenciesFromParsedModule(parsedModule)

                // Build program index for O(1) declaration lookups (v2 path)
                val programIndex = ProgramIndex.build(parsedModule)

                // Create module representation
                val structuredPath = ModulePath.parse(modulePath)
                val module = Module(
                    path = structuredPath,
                    filePath = filePath.toString(),
                    exports = parsedExports,
                    dependencies = dependencies,
                    sourceHash = sourceHash,
                    index = programIndex,
                    parsedModule = parsedModule
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
            exports = emptyMap(),
            dependencies = emptyList(),
            sourceHash = null,
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
     * Extract exported items directly from a ParsedModule without needing a v1 Program.
     *
     * Walks [module.decls] and converts each public declaration to an [ExportedItem].
     * Content hashes are computed using ParsedDeclHasher which hashes directly from
     * parsed AST nodes without converting to v1.
     *
     * @param module The parsed module to extract exports from.
     * @param modulePath Optional qualified module path (e.g. "std::int") used only for
     *   registering the hash in [ContentHashRegistry] for debugging.
     * @param typeContext Optional type context from type-checking — maps binding names to
     *   their inferred MezzeType. When provided, overrides the annotation-derived types.
     */
    private fun extractExportsFromParsedModule(
        module: ParsedModule,
        modulePath: String? = null,
        typeContext: Map<String, MezzeType>? = null
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

                    // Only simple bindings have a stable single name to export
                    if (!decl.isSimpleBinding) continue
                    val name = decl.variableName

                    // Priority: 1) typeContext (inferred), 2) annotation, 3) fresh var
                    val fromContext = typeContext?.get(name)
                    val fromAnnotation = decl.typeAnnotation
                        ?.let { TypeAnnotationConverter.convert(it, faithful = false) }
                    val mezzeType: MezzeType = fromContext ?: fromAnnotation
                        ?: MezzeType.VarType(TypeVar.fresh())

                    // Compute content hash via ParsedDeclHasher (v2-native, Unison-style)
                    val contentHashStr = com.mezze.language.parsedast.ParsedDeclHasher.hashLetDecl(decl)
                        ?: "unknown_${name}_hash"
                    val contentHash = com.mezze.language.typedast.Hash(contentHashStr)

                    exports[name] = ExportedItem(
                        name = name,
                        type = ExportedItemType.FUNCTION,
                        mezzeType = mezzeType,
                        originalName = name,
                        contentHash = contentHash
                    )
                    val qualifiedName = if (modulePath != null) "$modulePath::$name" else name
                    ContentHashRegistry.register(
                        hash = contentHashStr,
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

                    val contentHashStr = com.mezze.language.parsedast.ParsedDeclHasher.hashTypeDecl(decl)
                    val contentHash = com.mezze.language.typedast.Hash(contentHashStr)

                    // Priority: 1) typeContext (inferred), 2) fresh var
                    val fromContext = typeContext?.get(decl.name)
                    val typeMezzeType = fromContext ?: MezzeType.VarType(TypeVar.fresh())

                    exports[decl.name] = ExportedItem(
                        name = decl.name,
                        type = ExportedItemType.TYPE,
                        mezzeType = typeMezzeType,
                        originalName = decl.name,
                        contentHash = contentHash
                    )
                    val qualifiedName = if (modulePath != null) "$modulePath::${decl.name}" else decl.name
                    ContentHashRegistry.register(
                        hash = contentHashStr,
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
                    val contentHashStr = com.mezze.language.parsedast.ParsedDeclHasher.hashEffectDecl(decl)
                    val contentHash = com.mezze.language.typedast.Hash(contentHashStr)

                    // Priority: 1) typeContext (inferred), 2) fresh var
                    val fromContext = typeContext?.get(decl.name)
                    val mezzeType = fromContext ?: MezzeType.VarType(TypeVar.fresh())

                    exports[decl.name] = ExportedItem(
                        name = decl.name,
                        type = ExportedItemType.EFFECT,
                        mezzeType = mezzeType,
                        originalName = decl.name,
                        contentHash = contentHash
                    )
                    val qualifiedName = if (modulePath != null) "$modulePath::${decl.name}" else decl.name
                    ContentHashRegistry.register(
                        hash = contentHashStr,
                        qualifiedName = qualifiedName,
                        modulePath = modulePath,
                        kind = DeclarationKind.EFFECT
                    )

                    // Also export each operation as a FUNCTION
                    for (op in decl.operations) {
                        val opType: MezzeType = MezzeType.FunctionType(
                            paramType = TypeAnnotationConverter.convert(op.paramType, faithful = false),
                            returnType = TypeAnnotationConverter.convert(op.returnType, faithful = false)
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
                    val contentHashStr = com.mezze.language.parsedast.ParsedDeclHasher.hashAbilityDecl(decl)
                    val contentHash = com.mezze.language.typedast.Hash(contentHashStr)

                    // Priority: 1) typeContext (inferred), 2) fresh var
                    val fromContext = typeContext?.get(decl.name)
                    val abilityMezzeType = fromContext ?: MezzeType.VarType(TypeVar.fresh())

                    exports[decl.name] = ExportedItem(
                        name = decl.name,
                        type = ExportedItemType.ABILITY,
                        mezzeType = abilityMezzeType,
                        originalName = decl.name,
                        contentHash = contentHash
                    )
                    val qualifiedName = if (modulePath != null) "$modulePath::${decl.name}" else decl.name
                    ContentHashRegistry.register(
                        hash = contentHashStr,
                        qualifiedName = qualifiedName,
                        modulePath = modulePath,
                        kind = DeclarationKind.ABILITY
                    )
                }

                // ----------------------------------------------------------------
                // Performer (effect implementation) declarations
                // ----------------------------------------------------------------
                is ParsedPerformerDecl -> {
                    val contentHashStr = com.mezze.language.parsedast.ParsedDeclHasher.hashPerformerDecl(decl)
                    val contentHash = com.mezze.language.typedast.Hash(contentHashStr)

                    // Don't overwrite an existing TYPE export with a HANDLER export
                    val existingExport = exports[decl.performerName]
                    if (existingExport?.type != ExportedItemType.TYPE) {
                        // Priority: 1) typeContext (inferred), 2) fresh var
                        val fromContext = typeContext?.get(decl.performerName)
                        val performerMezzeType = fromContext ?: MezzeType.VarType(TypeVar.fresh())

                        exports[decl.performerName] = ExportedItem(
                            name = decl.performerName,
                            type = ExportedItemType.FUNCTION, // handlers are treated as values
                            mezzeType = performerMezzeType,
                            originalName = decl.performerName,
                            contentHash = contentHash
                        )
                        val qualifiedName = if (modulePath != null) "$modulePath::${decl.performerName}" else decl.performerName
                        ContentHashRegistry.register(
                            hash = contentHashStr,
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
     * Extract module dependencies from use declarations
     */
    /**
     * Extract module dependencies from a ParsedModule's use declarations.
     */
    private fun extractDependenciesFromParsedModule(module: ParsedModule): List<ModuleDependency> {
        val dependencies = mutableListOf<ModuleDependency>()

        for (decl in module.decls) {
            when (decl) {
                is ParsedUseDecl -> {
                    val v1Path = parsedUsePathToV1(decl.path)
                    dependencies.add(ModuleDependency(
                        modulePath = pathToString(v1Path),
                        usePath = v1Path,
                        alias = decl.alias
                    ))
                }
                is ParsedPubUseDecl -> {
                    val v1Path = parsedUsePathToV1(decl.path)
                    dependencies.add(ModuleDependency(
                        modulePath = pathToString(v1Path),
                        usePath = v1Path,
                        alias = decl.alias
                    ))
                }
                else -> { /* Other items don't create dependencies */ }
            }
        }

        return dependencies
    }

    /** Convert v2 ParsedUsePath to v1 UsePath (for dependency extraction) */
    private fun parsedUsePathToV1(path: ParsedUsePath): UsePath {
        return when (path) {
            is ParsedUsePath.Module -> ModulePath(path.path.segments)
            is ParsedUsePath.Selective -> SelectivePath(
                module = path.modulePath.segments,
                items = path.items.map { ImportItem(name = it.name, alias = it.alias) }
            )
        }
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
     * Update a module's exports from a ParsedModule (v2 path).
     *
     * This is the v2 replacement for [updateModuleExports] — it works entirely from
     * ParsedModule + typeContext, avoiding the need for a v1 Program.
     *
     * When the module is already cached (normal case after loadModule), this method
     * updates only the export types from the typeContext while preserving the content
     * hashes and ProgramIndex from the original load. This ensures content-addressed
     * lookups remain consistent.
     *
     * When the module is NOT cached (rare — typically only during initial prelude load),
     * this falls back to extractExportsFromParsedModule which recomputes hashes.
     *
     * @param modulePath The module's qualified path (e.g. "std::int")
     * @param parsedModule The v2 ParsedModule
     * @param typeContext Optional context from type-checking with inferred MezzeTypes
     */
    fun updateModuleExportsV2(
        modulePath: String,
        parsedModule: ParsedModule,
        typeContext: Map<String, MezzeType>? = null
    ) {
        if (parsedModule.decls.isEmpty()) return
        DebugLogger.debug(Category.ModuleResolver) {
            "updateModuleExportsV2: modulePath=$modulePath, ${parsedModule.decls.size} decls"
        }

        val module = getFromCache(modulePath)

        if (module != null) {
            // Module already cached — update export types from typeContext and
            // rebuild ProgramIndex so abilityImplsByHash/abilityImplsByAbility
            // are populated (needed by import resolution).
            val updatedExports = if (typeContext != null && typeContext.isNotEmpty()) {
                module.exports.mapValues { (_, export) ->
                    val inferredType = typeContext[export.originalName]
                    if (inferredType != null) export.copy(mezzeType = inferredType) else export
                }
            } else {
                module.exports
            }

            val updatedIndex = ProgramIndex.build(parsedModule)

            val updatedModule = module.copy(
                exports = updatedExports,
                parsedModule = parsedModule,
                index = updatedIndex
            )
            putInCache(modulePath, updatedModule)

            DebugLogger.debug(Category.ModuleResolver) {
                "Cached module (v2, updated types+index) with ${updatedModule.exports.size} exports"
            }
        } else {
            // Module NOT in cache — compute exports from scratch using ParsedModule
            val updatedExports = extractExportsFromParsedModule(parsedModule, modulePath, typeContext)
            val updatedIndex = ProgramIndex.build(parsedModule)

            val newModule = Module(
                path = ModulePath.parse(modulePath),
                filePath = "",
                exports = updatedExports,
                dependencies = emptyList(),
                sourceHash = null,
                index = updatedIndex,
                parsedModule = parsedModule
            )
            putInCache(modulePath, newModule)

            DebugLogger.debug(Category.ModuleResolver) {
                "Cached module (v2, new) with ${newModule.exports.size} exports"
            }
        }
    }

}