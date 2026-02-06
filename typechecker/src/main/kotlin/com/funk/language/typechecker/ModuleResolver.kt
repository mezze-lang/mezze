package com.funk.language.typechecker

import com.funk.language.ast.*
import com.funk.language.parser.FunkParser
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * Represents a loaded module with its exports
 */
data class Module(
    val name: String,
    val filePath: String,
    val program: Program,
    val exports: Map<String, ExportedItem>,
    val dependencies: List<ModuleDependency>
)

/**
 * Represents an exported item from a module
 */
data class ExportedItem(
    val name: String,
    val type: ExportedItemType,
    val funkType: FunkType,
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
    
    data class InternalModuleAccess(val fromModule: String, val toModule: String) :
        ModuleError("Module $fromModule cannot access internal module $toModule")
    
    data class ExportNotFound(val modulePath: String, val exportName: String) :
        ModuleError("Export '$exportName' not found in module '$modulePath'")
    
    data class DeprecatedCoreModule(val modulePath: String, val suggestion: String) :
        ModuleError("Deprecated core module: $modulePath. $suggestion")
}

/**
 * Find the project root by looking for stdlib directory
 */
private fun findProjectRoot(startPath: Path = Paths.get(".")): Path {
    var current = startPath.toAbsolutePath()

    if (System.getProperty("funk.typecheck.debug") == "true") {
        println("DEBUG: Finding project root, starting from: $current")
    }

    // Look for stdlib directory in current and parent directories
    for (i in 0..5) { // Check up to 5 levels up
        val stdlibPath = current.resolve("stdlib")
        if (System.getProperty("funk.typecheck.debug") == "true") {
            println("DEBUG: Checking for stdlib at: $stdlibPath (exists: ${Files.exists(stdlibPath)})")
        }
        if (Files.exists(stdlibPath) && Files.isDirectory(stdlibPath)) {
            if (System.getProperty("funk.typecheck.debug") == "true") {
                println("DEBUG: Found project root: $current")
            }
            return current
        }
        val parent = current.parent
        if (parent == null || parent == current) break
        current = parent
    }

    // Fallback to current directory
    if (System.getProperty("funk.typecheck.debug") == "true") {
        println("DEBUG: Using fallback project root: ${startPath.toAbsolutePath()}")
    }
    return startPath.toAbsolutePath()
}

/**
 * Module resolution system for Funk
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

        /**
         * Clear the module cache. This is useful for tests to ensure clean state between runs.
         */
        @JvmStatic
        fun clearCache() {
            synchronized(cacheLock) {
                moduleCache.clear()
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
    }

    // Track modules currently being loaded to detect circular dependencies (per-instance to track call stack)
    private val loadingStack = mutableSetOf<String>()
    
    // Core modules that define built-in types - these should be skipped
    // since built-in types are now available directly in the typechecker
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
        "std::collections::arraylist", // ArrayList operations (now built-in as List)
        "std::collections::map",     // Map operations (now built-in)
        "std::collections::set"      // Set operations (now built-in)
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
            if (System.getProperty("funk.migration.warnings", "true") == "true") {
            }
            
            // In strict migration mode, throw an error
            if (System.getProperty("funk.migration.strict", "false") == "true") {
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
        
        // Check cache first (thread-safe)
        getFromCache(modulePath)?.let {
            if (System.getProperty("funk.typecheck.debug") == "true") {
                println("DEBUG: Found cached module: $modulePath")
            }
            return it
        }
        
        // Check for deprecated core modules and provide migration warnings
        checkForDeprecatedCoreModule(modulePath)
        
        // Check for circular dependency
        if (modulePath in loadingStack) {
            throw ModuleError.CircularDependency(loadingStack.toList() + modulePath)
        }
        
        // Find the file for this module
        val filePath = resolveModuleFile(modulePath)
            ?: throw ModuleError.ModuleNotFound(modulePath, getSearchPaths(modulePath))
        
        // Mark as loading
        loadingStack.add(modulePath)
        
        try {
            // Parse the module with file name for better error messages
            // Create fresh parser for thread safety
            val source = Files.readString(filePath)
            val program = FunkParser().parse(source, file = filePath.toString())

            // Don't type-check during module resolution to avoid circular dependencies
            // The module will be type-checked when it's actually used
            val typedProgram = program

            // Extract dependencies first
            val dependencies = extractDependencies(typedProgram)
            
            // Create module representation
            val module = Module(
                name = modulePath,
                filePath = filePath.toString(),
                program = typedProgram,
                exports = extractExports(typedProgram),
                dependencies = dependencies
            )

            // Cache the module immediately so updateModuleExports can find and update it (thread-safe)
            // The exports may be incomplete (based on annotations only), but will be updated later
            putInCache(modulePath, module)
            return module
            
        } catch (e: com.funk.language.parser.ParseException) {
            // Preserve the formatted error message with code snippets
            throw ModuleError.ParseError(modulePath, e.message ?: "Unknown parse error")
        } catch (e: Exception) {
            // Catch other unexpected errors
            throw ModuleError.ParseError(modulePath, "Unexpected error: ${e.message}")
        } finally {
            loadingStack.remove(modulePath)
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
            dependencies = emptyList()
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
            val relativeFilePath = "stdlib/std/" + stdSegments.joinToString("/") + ".funk"

            // First, try to load from embedded resources (works in JAR and native binary)
            val resourceContent = loadStdlibFromResource(relativeFilePath)
            if (resourceContent != null) {
                // Write to temp file and return path
                val tempFile = Files.createTempFile("funk-stdlib-", ".funk")
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
            val relativeFilePath = "stdlib/std/${segments[0]}.funk"
            val resourceContent = loadStdlibFromResource(relativeFilePath)
            if (resourceContent != null) {
                val tempFile = Files.createTempFile("funk-stdlib-", ".funk")
                Files.writeString(tempFile, resourceContent)
                tempFile.toFile().deleteOnExit()
                return tempFile
            }

            // Fall back to filesystem for development
            // Note: relativeFilePath already contains "stdlib/std/..." prefix
            val flatStdlibPath = projectRoot.resolve(relativeFilePath)
            if (System.getProperty("funk.typecheck.debug") == "true") {
                println("DEBUG: Checking flat stdlib path: $flatStdlibPath (exists: ${Files.exists(flatStdlibPath)})")
            }
            if (Files.exists(flatStdlibPath) && Files.isRegularFile(flatStdlibPath)) {
                return flatStdlibPath
            }
        }
        
        // Regular module resolution for non-std modules
        val searchRoots = listOf(
            projectRoot.resolve("src"),
            projectRoot.resolve("lib"),
            projectRoot
        )
        
        for (root in searchRoots) {
            val filePath = root.resolve(segments.joinToString("/") + ".funk")
            if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
                return filePath
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
                "stdlib/std/${stdSegments.joinToString("/")}.funk"
            )
        }
        
        // For flat stdlib modules, include stdlib directory
        if (segments.size == 1) {
            return listOf(
                "stdlib/${segments[0]}.funk",
                "src/${segments.joinToString("/")}.funk",
                "lib/${segments.joinToString("/")}.funk",
                "${segments.joinToString("/")}.funk"
            )
        }
        
        // Regular search paths
        return listOf(
            "src/${segments.joinToString("/")}.funk",
            "lib/${segments.joinToString("/")}.funk",
            "${segments.joinToString("/")}.funk"
        )
    }
    
    /**
     * Extract exported items from a program
     * @param typeContext Optional context from TypeChecker containing inferred types
     */
    private fun extractExports(program: Program, typeContext: Map<String, FunkType>? = null): Map<String, ExportedItem> {
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

                        val funkType = fromContext ?: fromAnnotation ?: fromInferred ?: FunkType.VarType(TypeVar.fresh())

                        // DEBUG key items (only for specific debugging)
                        if (System.getProperty("funk.typecheck.debug") == "true" && item.variableName == "not") {
                            println("\n=== extractExports DEBUG for 'not' ===")
                            println("Variable name: ${item.variableName}")
                            println("fromContext: $fromContext")
                            println("fromAnnotation: $fromAnnotation")
                            println("fromInferred: $fromInferred")
                            println("Final funkType: $funkType")
                        }

                        exports[item.variableName] = ExportedItem(
                            name = item.variableName,
                            type = ExportedItemType.FUNCTION,
                            funkType = funkType,
                            originalName = item.variableName
                        )
                    }
                }
                is TypeDeclaration -> {
                    if (item.visibility == Visibility.PUBLIC) {
                        exports[item.name] = ExportedItem(
                            name = item.name,
                            type = ExportedItemType.TYPE,
                            funkType = item.inferredType ?: FunkType.VarType(TypeVar.fresh()),
                            originalName = item.name
                        )
                    }
                }
                is EffectDeclaration -> {
                    if (item.visibility == Visibility.PUBLIC) {
                        // Priority: 1) TypeChecker context, 2) inferredType from AST, 3) fresh var
                        val fromContext = typeContext?.get(item.name)
                        val fromInferred = item.inferredType
                        val funkType = fromContext ?: fromInferred ?: FunkType.VarType(TypeVar.fresh())

                        // Export the effect type so it can be referenced (e.g., Log.info)
                        exports[item.name] = ExportedItem(
                            name = item.name,
                            type = ExportedItemType.EFFECT,
                            funkType = funkType,
                            originalName = item.name
                        )
                    }
                }
                is ImplDeclaration -> {
                    // Export handler names (performer names) from impl declarations
                    // Note: ImplDeclaration doesn't have a visibility field yet, so we always export them
                    // The type will be updated later when updateModuleExports is called with typeContext
                    val performerName = item.performerName
                    val fromContext = typeContext?.get(performerName)

                    // Always export the performer - if typeContext is not available yet,
                    // use a placeholder type that will be updated later
                    val funkType = fromContext ?: item.inferredType ?: FunkType.VarType(TypeVar.fresh())

                    exports[performerName] = ExportedItem(
                        name = performerName,
                        type = ExportedItemType.FUNCTION, // Handlers are treated as values
                        funkType = funkType,
                        originalName = performerName
                    )
                }
                else -> {
                    // Other items are not exported for now
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
     * Convert TypeAnnotation to FunkType (simplified version)
     */
    private fun convertTypeAnnotation(annotation: TypeAnnotation): FunkType {
        return when (annotation) {
            is IdentifierTypeAnnotation -> {
                when (annotation.name) {
                    "Int" -> FunkType.IntType
                    "Float" -> FunkType.FloatType
                    "String" -> FunkType.StringType
                    "Boolean" -> FunkType.VariantType(
                        constructors = mapOf("True" to null, "False" to null),
                        rowVar = null
                    )
                    "Bool" -> FunkType.VariantType(
                        constructors = mapOf("True" to null, "False" to null),
                        rowVar = null
                    )
                    "Nat" -> FunkType.NatType
                    "Frac" -> FunkType.FracType
                    else -> FunkType.VarType(TypeVar.fresh())
                }
            }
            is FunctionTypeAnnotation -> {
                val effectRow = annotation.effects?.let { effectRowAnnotation ->
                    // If the effect row is polymorphic (<...> or <Effect, ...>), create a fresh row variable
                    val rowVar = if (effectRowAnnotation.isPolymorphic) RowVar.fresh() else null
                    FunkType.EffectRow(effectRowAnnotation.effects.toSet(), rowVar)
                }
                FunkType.FunctionType(
                    paramType = convertTypeAnnotation(annotation.paramType),
                    returnType = convertTypeAnnotation(annotation.returnType),
                    effects = effectRow
                )
            }
            is RecordTypeAnnotation -> {
                val fields = annotation.entries.associate { entry ->
                    entry.name to convertTypeAnnotation(entry.type)
                }
                FunkType.RecordType(fields)
            }
            is ArrayTypeAnnotation -> {
                FunkType.ArrayType(convertTypeAnnotation(annotation.elementType))
            }
            else -> {
                FunkType.VarType(TypeVar.fresh())
            }
        }
    }
    
    /**
     * Check if a module can access internal modules of another module
     */
    fun canAccessInternal(fromModule: String, toModule: String): Boolean {
        // Internal modules can only be accessed by modules in the same parent hierarchy
        // For example: math::internal::helpers can only be accessed by modules under math::

        if (!toModule.contains("::internal::")) {
            return true // Not an internal module, access allowed
        }

        // Extract the parent module path from the internal module
        val internalIndex = toModule.indexOf("::internal::")
        val parentModule = toModule.substring(0, internalIndex)

        // Empty fromModule means a top-level script, which CANNOT access internal modules
        if (fromModule.isEmpty()) {
            return false
        }

        // Allow access if the fromModule is within the same parent hierarchy
        return fromModule.startsWith("$parentModule::")
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
    fun updateModuleExports(modulePath: String, typedProgram: Program, typeContext: Map<String, FunkType>? = null) {
        if (System.getProperty("funk.typecheck.debug") == "true") {
            println("\nDEBUG updateModuleExports: modulePath=$modulePath")
            println("DEBUG updateModuleExports: Program has ${typedProgram.items.size} items")

            // Check if identifiers are qualified
            typedProgram.items.forEach { item ->
                if (item is LetBinding) {
                    println("DEBUG updateModuleExports: LetBinding '${item.variableName}' expression type: ${item.expression.javaClass.simpleName}")
                    // Check for identifiers in the expression
                    checkIdentifiersInExpression(item.expression, "  ")
                }
            }
        }

        val module = getFromCache(modulePath)
        val updatedModule = if (module != null) {
            module.copy(
                program = typedProgram,
                exports = extractExports(typedProgram, typeContext)
            )
        } else {
            // Module not in cache yet, create it
            Module(
                name = modulePath,
                filePath = "", // Will be updated if needed
                program = typedProgram,
                exports = extractExports(typedProgram, typeContext),
                dependencies = emptyList()
            )
        }
        putInCache(modulePath, updatedModule)

        if (System.getProperty("funk.typecheck.debug") == "true") {
            println("DEBUG updateModuleExports: Cached module with ${updatedModule.exports.size} exports")
        }
    }

    private fun checkIdentifiersInExpression(expr: Expression, indent: String) {
        when (expr) {
            is Identifier -> {
                println("${indent}Identifier: '${expr.name}'")
            }
            is FunctionCall -> {
                println("${indent}FunctionCall:")
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
                elements = expr.elements.map { qualifyExpression(it, moduleNames, prefix) }
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