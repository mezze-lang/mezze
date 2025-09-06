@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package com.funk.language.typechecker

import com.funk.language.parser.*
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.*

/**
 * Rust-style module resolver for Funk
 * 
 * Module Resolution Rules (like Rust):
 * 1. `mod foo` looks for:
 *    - `foo.funk` (file module)
 *    - `foo/mod.funk` (directory module with mod.funk)
 * 
 * 2. Nested modules `foo::bar::baz`:
 *    - `foo/bar.funk` 
 *    - `foo/bar/mod.funk`
 *    - `foo/bar/baz.funk`
 * 
 * 3. Search paths: src/, lib/, current directory
 */
class RustModuleResolver(
    private val sourcePaths: List<Path> = listOf(
        Paths.get("src"), 
        Paths.get("lib"), 
        Paths.get(".")
    ),
    private val parser: FunkParserImpl = FunkParserImpl()
) {
    
    private val moduleCache = mutableMapOf<String, ResolvedModule>()
    
    /**
     * Resolve a module declaration like `mod foo`
     */
    fun resolveModule(moduleName: String, currentPath: Path = Paths.get(".")): ResolvedModule? {
        val cacheKey = "${currentPath.toAbsolutePath()}::$moduleName"
        moduleCache[cacheKey]?.let { return it }
        
        val resolvedPath = findModuleFile(moduleName, currentPath)
            ?: return null
            
        val module = loadModuleFromFile(resolvedPath, moduleName)
            ?: return null
            
        moduleCache[cacheKey] = module
        return module
    }
    
    /**
     * Resolve a module path like `foo::bar::baz`
     */
    fun resolveModulePath(modulePath: List<String>, currentPath: Path = Paths.get(".")): ResolvedModule? {
        if (modulePath.isEmpty()) return null
        
        // Start with the first module
        var currentModule = resolveModule(modulePath[0], currentPath) ?: return null
        var currentModulePath = currentModule.filePath.parent ?: currentPath
        
        // Navigate through the path
        for (i in 1 until modulePath.size) {
            val nextModuleName = modulePath[i]
            currentModule = resolveModule(nextModuleName, currentModulePath) ?: return null
            currentModulePath = currentModule.filePath.parent ?: currentModulePath
        }
        
        return currentModule
    }
    
    /**
     * Find module file following Rust conventions
     */
    private fun findModuleFile(moduleName: String, currentPath: Path): Path? {
        val searchPaths = if (currentPath == Paths.get(".")) {
            sourcePaths
        } else {
            listOf(currentPath) + sourcePaths
        }
        
        for (searchPath in searchPaths) {
            // Try foo.funk
            val fileModule = searchPath / "$moduleName.funk"
            if (fileModule.exists() && fileModule.isRegularFile()) {
                return fileModule
            }
            
            // Try foo/mod.funk
            val dirModule = searchPath / moduleName / "mod.funk"
            if (dirModule.exists() && dirModule.isRegularFile()) {
                return dirModule
            }
        }
        
        return null
    }
    
    /**
     * Load and parse module from file
     */
    private fun loadModuleFromFile(filePath: Path, moduleName: String): ResolvedModule? {
        return try {
            val source = filePath.readText()
            val program = parser.parse(source)
            
            ResolvedModule(
                name = moduleName,
                filePath = filePath,
                program = program,
                exports = extractExports(program),
                submodules = extractSubmodules(program)
            )
        } catch (e: Exception) {
            println("Error loading module $moduleName from $filePath: ${e.message}")
            null
        }
    }
    
    /**
     * Extract exported items from a program
     */
    private fun extractExports(program: Program): Map<String, ExportedItem> {
        val exports = mutableMapOf<String, ExportedItem>()
        
        for (item in program.items) {
            when (item) {
                is LetBinding -> {
                    if (item.isExposed) {
                        exports[item.variableName] = ExportedItem.Value(
                            name = item.variableName,
                            binding = item
                        )
                    }
                }
                is TypeDeclaration -> {
                    if (item.isExposed) {
                        exports[item.name] = ExportedItem.Type(
                            name = item.name,
                            declaration = item
                        )
                    }
                }
                is EffectDeclaration -> {
                    if (item.isExposed) {
                        exports[item.name] = ExportedItem.Effect(
                            name = item.name,
                            declaration = item
                        )
                    }
                }
                is AbilityDeclaration -> {
                    if (item.isExposed) {
                        exports[item.name] = ExportedItem.Ability(
                            name = item.name,
                            declaration = item
                        )
                    }
                }
                is AccessModuleDeclaration -> {
                    if (item.isExposed) {
                        // Re-export: expose access foo::bar
                        val exportName = item.alias ?: item.modulePath.last()
                        exports[exportName] = ExportedItem.ReExport(
                            name = exportName,
                            modulePath = item.modulePath
                        )
                    }
                }
                is ModDeclaration -> {
                    // Module declarations don't export items directly
                }
                is ModuleDeclaration -> {
                    // Legacy module declarations
                }
                is ExpressionStatement -> {
                    // Expression statements don't export items
                }
                is ResumeStatement -> {
                    // Resume statements don't export items
                }
            }
        }
        
        return exports
    }
    
    /**
     * Extract submodule declarations from a program
     */
    private fun extractSubmodules(program: Program): Set<String> {
        return program.items
            .filterIsInstance<ModDeclaration>()
            .map { it.moduleName }
            .toSet()
    }
    
    /**
     * Resolve an access declaration like `access foo::bar::baz`
     */
    fun resolveAccess(
        modulePath: List<String>, 
        itemName: String? = null,
        currentPath: Path = Paths.get(".")
    ): AccessResolution? {
        val targetModule = resolveModulePath(modulePath, currentPath) ?: return null
        
        val targetItem = itemName ?: modulePath.last()
        val exportedItem = targetModule.exports[targetItem] ?: return null
        
        return AccessResolution(
            module = targetModule,
            item = exportedItem,
            resolvedName = targetItem
        )
    }
    
    /**
     * Get all modules in a directory (for discovery)
     */
    fun discoverModules(searchPath: Path = Paths.get("src")): Map<String, Path> {
        if (!searchPath.exists() || !searchPath.isDirectory()) {
            return emptyMap()
        }
        
        val modules = mutableMapOf<String, Path>()
        
        searchPath.listDirectoryEntries().forEach { entry ->
            when {
                // foo.funk -> module foo
                entry.isRegularFile() && entry.extension == "funk" -> {
                    val moduleName = entry.nameWithoutExtension
                    modules[moduleName] = entry
                }
                // foo/mod.funk -> module foo
                entry.isDirectory() -> {
                    val modFile = entry / "mod.funk"
                    if (modFile.exists() && modFile.isRegularFile()) {
                        modules[entry.name] = modFile
                    }
                }
            }
        }
        
        return modules
    }
}

/**
 * Resolved module with all metadata
 */
data class ResolvedModule(
    val name: String,
    val filePath: Path,
    val program: Program,
    val exports: Map<String, ExportedItem>,
    val submodules: Set<String>
)

/**
 * Exported items from a module
 */
sealed class ExportedItem {
    abstract val name: String
    
    data class Value(
        override val name: String,
        val binding: LetBinding
    ) : ExportedItem()
    
    data class Type(
        override val name: String,
        val declaration: TypeDeclaration
    ) : ExportedItem()
    
    data class Effect(
        override val name: String,
        val declaration: EffectDeclaration
    ) : ExportedItem()
    
    data class Ability(
        override val name: String,
        val declaration: AbilityDeclaration
    ) : ExportedItem()
    
    data class ReExport(
        override val name: String,
        val modulePath: List<String>
    ) : ExportedItem()
}

/**
 * Result of resolving an access declaration
 */
data class AccessResolution(
    val module: ResolvedModule,
    val item: ExportedItem,
    val resolvedName: String
)
