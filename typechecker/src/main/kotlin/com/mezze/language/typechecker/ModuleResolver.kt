package com.mezze.language.typechecker

import com.mezze.language.ast.*
import com.mezze.language.ast.hashing.MezzeHash
import com.mezze.language.ast.hashing.MezzeHashBuilder
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.debug.DebugLogger
import com.mezze.language.typechecker.debug.DebugLogger.Category
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
    val typeDeclarations: Map<String, TypeDeclaration>,
    val abilityDeclarations: Map<String, AbilityDeclaration>,
    val effectDeclarations: Map<String, EffectDeclaration>,
    val implsByPerformer: Map<String, ImplDeclaration>,
    val implsByEffect: Map<String, List<ImplDeclaration>>,
    val abilityImplsByAbility: Map<String, List<ImplAbilityDeclaration>>,
    val abilityImplsByHash: Map<String, ImplAbilityDeclaration>,
    val letBindings: Map<String, LetBinding>,
    val useDeclarations: List<UseDeclaration>,
    val pubUseDeclarations: List<PubUseDeclaration>
) {
    companion object {
        /**
         * Build an index from a program.
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
                typeDeclarations = typeDecls.associateBy { it.name },
                abilityDeclarations = abilityDecls.associateBy { it.name },
                effectDeclarations = effectDecls.associateBy { it.name },
                implsByPerformer = implDecls.associateBy { it.performerName },
                implsByEffect = implDecls.groupBy { it.effectName },
                abilityImplsByAbility = abilityImplDecls.groupBy { it.abilityName },
                abilityImplsByHash = abilityImplDecls.mapNotNull { impl ->
                    impl.contentHash?.let { hash -> hash to impl }
                }.toMap(),
                letBindings = letBindingDecls
                    .filter { it.isSimpleBinding }
                    .associateBy { it.variableName },
                useDeclarations = items.filterIsInstance<UseDeclaration>(),
                pubUseDeclarations = items.filterIsInstance<PubUseDeclaration>()
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
 * @property name The module path (e.g., "std::int")
 * @property filePath The file system path to the source file
 * @property program The parsed and type-checked program
 * @property exports Map of exported items (functions, types, etc.)
 * @property dependencies List of module dependencies
 * @property sourceHash Content hash of the source file (for cache invalidation)
 * @property index Pre-computed index for O(1) declaration lookups
 */
data class Module(
    val name: String,
    val filePath: String,
    val program: Program,
    val exports: Map<String, ExportedItem>,
    val dependencies: List<ModuleDependency>,
    val sourceHash: String? = null,
    val index: ProgramIndex = ProgramIndex.EMPTY
)

/**
 * Represents an exported item from a module
 */
data class ExportedItem(
    val name: String,
    val type: ExportedItemType,
    val mezzeType: MezzeType,
    val originalName: String,
    val visibility: Visibility = Visibility.PUBLIC
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
    ) : ModuleError("Export '$exportName' not found in module '$modulePath'")
    
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
        // Cache for parsed modules to avoid reprocessing (shared across all instances)
        // Access is synchronized for thread safety
        private val moduleCache = mutableMapOf<String, Module>()
        private val cacheLock = Any()
        // Per-module locks to prevent duplicate loading of the same module
        private val moduleLoadingLocks = mutableMapOf<String, Any>()

        /**
         * Clear the module cache. This is useful for tests to ensure clean state between runs.
         */
        @JvmStatic
        fun clearCache() {
            synchronized(cacheLock) {
                moduleCache.clear()
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
         * Get a module from the cache (thread-safe).
         */
        internal fun getFromCache(modulePath: String): Module? {
            synchronized(cacheLock) {
                return moduleCache[modulePath]
            }
        }

        /**
         * Put a module in the cache (thread-safe).
         */
        internal fun putInCache(modulePath: String, module: Module) {
            synchronized(cacheLock) {
                moduleCache[modulePath] = module
            }
        }

        /**
         * Get all cached modules as a map of module paths to Programs.
         * Used for bundling modules into compiled JARs.
         */
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
        "std::types::frac",          // Fraction operations (now built-in)
        "std::collections::arraylist" // ArrayList operations (no stdlib file, fully built-in as array.mz)
        // NOTE: std::collections::map and std::collections::set are NOT skipped - they have stdlib wrapper files
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
     * Resolve and load a module by its path
     */
    fun resolveModule(modulePath: String, fromModule: String? = null): Module {
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
            // This enables content-based cache validation
            val source = Files.readString(filePath)
            val sourceHash = MezzeHashBuilder().append(source).build().toString()

            // Check cache with content-based validation
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
                val program = MezzeParser().parse(source, file = filePath.toString())

                // Don't type-check during module resolution to avoid circular dependencies
                // The module will be type-checked when it's actually used
                val typedProgram = program

                // Extract dependencies first
                val dependencies = extractDependencies(typedProgram)

                // Build program index for O(1) declaration lookups
                val programIndex = ProgramIndex.build(typedProgram)

                // Create module representation with source hash for cache validation
                val module = Module(
                    name = modulePath,
                    filePath = filePath.toString(),
                    program = typedProgram,
                    exports = extractExports(typedProgram),
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
            name = modulePath,
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
     * Resolve a module file path from module name
     */
    private fun resolveModuleFile(modulePath: String): Path? {
        val segments = modulePath.split("::")

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
        val segments = modulePath.split("::")
        
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
     * @param typeContext Optional context from TypeChecker containing inferred types
     */
    private fun extractExports(program: Program, typeContext: Map<String, MezzeType>? = null): Map<String, ExportedItem> {
        val exports = mutableMapOf<String, ExportedItem>()

        for (item in program.items) {
            when (item) {
                is LetBinding -> {
                    if (item.visibility == Visibility.PUBLIC) {
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

                        exports[item.variableName] = ExportedItem(
                            name = item.variableName,
                            type = ExportedItemType.FUNCTION,
                            mezzeType = mezzeType,
                            originalName = item.variableName
                        )
                    }
                }
                is TypeDeclaration -> {
                    if (item.visibility == Visibility.PUBLIC) {
                        // Export the type alias - constructors are globally available (Roc-style)
                        exports[item.name] = ExportedItem(
                            name = item.name,
                            type = ExportedItemType.TYPE,
                            mezzeType = item.inferredType ?: MezzeType.VarType(TypeVar.fresh()),
                            originalName = item.name
                        )
                    }
                }
                is EffectDeclaration -> {
                    // Effects are ALWAYS public - no visibility check needed
                    // Priority: 1) TypeChecker context, 2) inferredType from AST, 3) fresh var
                    val fromContext = typeContext?.get(item.name)
                    val fromInferred = item.inferredType
                    val mezzeType = fromContext ?: fromInferred ?: MezzeType.VarType(TypeVar.fresh())

                    // Export the effect type so it can be referenced (e.g., Log.info)
                    exports[item.name] = ExportedItem(
                        name = item.name,
                        type = ExportedItemType.EFFECT,
                        mezzeType = mezzeType,
                        originalName = item.name
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

                    exports[performerName] = ExportedItem(
                        name = performerName,
                        type = ExportedItemType.FUNCTION, // Handlers are treated as values
                        mezzeType = mezzeType,
                        originalName = performerName
                    )
                }
                is AbilityDeclaration -> {
                    // Abilities are ALWAYS public - no visibility check needed
                    // Export ability declarations so they can be imported by other modules
                    // This enables qualified ability names like `codec::EncodeFormat` in impl blocks
                    val fromContext = typeContext?.get(item.name)
                    val fromInferred = item.inferredType
                    val mezzeType = fromContext ?: fromInferred ?: MezzeType.VarType(TypeVar.fresh())

                    exports[item.name] = ExportedItem(
                        name = item.name,
                        type = ExportedItemType.ABILITY,
                        mezzeType = mezzeType,
                        originalName = item.name
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
     * Convert UsePath to string representation
     */
    private fun pathToString(path: UsePath): String {
        return when (path) {
            is ModulePath -> path.segments.joinToString("::")
            is SelectivePath -> path.module.joinToString("::")
        }
    }
    
    /**
     * Convert TypeAnnotation to MezzeType (simplified version)
     */
    private fun convertTypeAnnotation(annotation: TypeAnnotation): MezzeType {
        return when (annotation) {
            is IdentifierTypeAnnotation -> {
                when (annotation.name) {
                    "Int" -> MezzeType.IntType
                    "Float" -> MezzeType.FloatType
                    "String" -> MezzeType.StringType
                    "Boolean" -> MezzeType.VariantType(
                        constructors = mapOf("True" to null, "False" to null),
                        rowVar = null
                    )
                    "Bool" -> MezzeType.VariantType(
                        constructors = mapOf("True" to null, "False" to null),
                        rowVar = null
                    )
                    "Nat" -> MezzeType.NatType
                    "Frac" -> MezzeType.FracType
                    "Char" -> MezzeType.CharType
                    "Bytes" -> MezzeType.BytesType
                    "ByteBuffer" -> MezzeType.ByteBufferType
                    "Ordering" -> MezzeType.OrderingType
                    else -> MezzeType.VarType(TypeVar.fresh())
                }
            }
            is FunctionTypeAnnotation -> {
                val effectRow = annotation.effects?.let { effectRowAnnotation ->
                    // If the effect row is polymorphic (<...> or <Effect, ...>), create a fresh row variable
                    val rowVar = if (effectRowAnnotation.isPolymorphic) RowVar.fresh() else null
                    MezzeType.EffectRow(effectRowAnnotation.effects.toSet(), rowVar, effectRowAnnotation.isPerformed)
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
            is ArrayTypeAnnotation -> {
                MezzeType.ArrayType(convertTypeAnnotation(annotation.elementType))
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
     * - This is recursive: `foo::internal::bar` can only be accessed by modules under `foo::`
     *
     * Examples:
     * - `math::internal::helpers` can only be accessed by modules under `math::`
     * - `mylib::private::utils` can only be accessed by modules under `mylib::`
     * - `app::internal::deep::nested` can only be accessed by modules under `app::`
     */
    fun canAccessInternal(fromModule: String, toModule: String): Boolean {
        // Check for internal or private folder markers in the module path
        val internalMarkers = listOf("::internal::", "::private::", "::internal", "::private")

        // Find the first internal/private marker in the path
        var markerIndex = -1
        var markerLength = 0
        for (marker in internalMarkers) {
            val idx = toModule.indexOf(marker)
            if (idx >= 0 && (markerIndex < 0 || idx < markerIndex)) {
                markerIndex = idx
                markerLength = marker.length
            }
        }

        // Also check if the module path STARTS with internal/ or private/
        // This handles top-level internal modules
        if (toModule.startsWith("internal::") || toModule.startsWith("private::")) {
            // Top-level internal modules cannot be accessed from outside
            return false
        }

        if (markerIndex < 0) {
            return true // Not an internal module, access allowed
        }

        // Extract the parent module path (everything before the internal/private marker)
        val parentModule = toModule.substring(0, markerIndex)

        // Empty fromModule means a top-level script, which CANNOT access internal modules
        if (fromModule.isEmpty()) {
            return false
        }

        // Allow access if the fromModule is within the same parent hierarchy
        // e.g., "math::ops" can access "math::internal::helpers"
        // e.g., "math::internal::other" can access "math::internal::helpers"
        return fromModule.startsWith("$parentModule::") || fromModule == parentModule
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
                exports = extractExports(typedProgram, typeContext),
                index = updatedIndex
            )
        } else {
            // Module not in cache yet, create it
            // Note: sourceHash will be null here since we don't have access to the source
            // This path is typically used when type-checking imported modules
            Module(
                name = modulePath,
                filePath = "", // Will be updated if needed
                program = typedProgram,
                exports = extractExports(typedProgram, typeContext),
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
                DebugLogger.debug(Category.ModuleResolver) { "${indent}Identifier: '${expr.name}'" }
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
     * @param modulePath The module path (e.g., "std::collections::array")
     * @return A transformed program with qualified variable names
     */
    private fun qualifyModuleVariables(program: Program, modulePath: String): Program {
        // Convert module path to qualified prefix (e.g., "std::collections::array" -> "std__collections__array")
        val qualifiedPrefix = modulePath.replace("::", "__") + "__"

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
                if (expr.name in moduleNames) {
                    expr.copy(name = prefix + expr.name)
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
            is ArrayLiteral -> expr.copy(
                elements = expr.elements.map { element ->
                    when (element) {
                        is ArrayRegularElement -> ArrayRegularElement(qualifyExpression(element.expression, moduleNames, prefix))
                        is ArraySpreadElement -> ArraySpreadElement(qualifyExpression(element.expression, moduleNames, prefix))
                    }
                }
            )
            // Literal expressions don't need qualification
            is IntLiteral, is NatLiteral, is FloatLiteral, is StringLiteral,
            is BooleanLiteral -> expr
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