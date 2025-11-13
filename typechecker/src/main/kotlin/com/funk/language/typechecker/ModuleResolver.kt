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
 *
Module resolution system for Funk
 * Handles loading, parsing, and caching of modules
 */
class ModuleResolver(
    currentPath: Path = Paths.get("."),
    private val parser: FunkParser = FunkParser()
) {
    private val projectRoot: Path = findProjectRoot(currentPath)

    companion object {
        // Cache for parsed modules to avoid reprocessing (shared across all instances)
        private val moduleCache = mutableMapOf<String, Module>()
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
        
        // Check cache first
        moduleCache[modulePath]?.let { 
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
            // Parse the module
            val source = Files.readString(filePath)
            val program = parser.parse(source)
            
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

            // Cache the module immediately so updateModuleExports can find and update it
            // The exports may be incomplete (based on annotations only), but will be updated later
            moduleCache[modulePath] = module
            return module
            
        } catch (e: Exception) {
            throw ModuleError.ParseError(modulePath, e.message ?: "Unknown parse error")
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
            val relativeFilePath = "std/" + stdSegments.joinToString("/") + ".funk"

            // First, try to load from JAR resources (for packaged stdlib)
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
            val possibleRoots = listOf(
                projectRoot,
                Paths.get("."),
                Paths.get(".."),
                Paths.get(".").toAbsolutePath().parent ?: Paths.get(".")
            )

            for (root in possibleRoots) {
                val stdlibPath = root.resolve("stdlib").resolve(relativeFilePath)
                if (Files.exists(stdlibPath) && Files.isRegularFile(stdlibPath)) {
                    return stdlibPath
                }
            }

            return null
        }
        
        // For flat stdlib modules (int, string, float, etc.), check resources first, then filesystem
        if (segments.size == 1) {
            // Try to load from resources first (for stdlib modules in JAR/native binary)
            val relativeFilePath = "std/${segments[0]}.funk"
            val resourceContent = loadStdlibFromResource(relativeFilePath)
            if (resourceContent != null) {
                val tempFile = Files.createTempFile("funk-stdlib-", ".funk")
                Files.writeString(tempFile, resourceContent)
                tempFile.toFile().deleteOnExit()
                return tempFile
            }

            // Fall back to filesystem for development
            val flatStdlibPath = projectRoot.resolve("stdlib").resolve("${segments[0]}.funk")
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
                    // Export handler names (performer names) from public impl declarations
                    // Note: ImplDeclaration doesn't have a visibility field yet, so we treat them as public if marked with 'pub' in source
                    // For now, we'll check if there's a type in the context for the performer name
                    val performerName = item.performerName
                    val fromContext = typeContext?.get(performerName)

                    // If the performer is in the context, export it
                    // The type will be the handler type (which is essentially a marker)
                    if (fromContext != null) {
                        exports[performerName] = ExportedItem(
                            name = performerName,
                            type = ExportedItemType.FUNCTION, // Handlers are treated as values
                            funkType = fromContext,
                            originalName = performerName
                        )
                    }
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
                    "Boolean" -> FunkType.BooleanType
                    "Nat" -> FunkType.NatType
                    "Frac" -> FunkType.FracType
                    else -> FunkType.VarType(TypeVar.fresh())
                }
            }
            is FunctionTypeAnnotation -> {
                val effectRow = annotation.effects?.let { effectRowAnnotation ->
                    FunkType.EffectRow(effectRowAnnotation.effects.toSet())
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
     * TODO: Implement proper access control logic
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
        
        // Allow access if the fromModule is within the same parent hierarchy
        // or if fromModule is empty (top-level module)
        return fromModule.isEmpty() || fromModule.startsWith("$parentModule::")
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
        val module = moduleCache[modulePath]
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
        moduleCache[modulePath] = updatedModule
    }

    /**
     * Clear the module cache (useful for testing)
     */
    fun clearCache() {
        moduleCache.clear()
        loadingStack.clear()
    }
}