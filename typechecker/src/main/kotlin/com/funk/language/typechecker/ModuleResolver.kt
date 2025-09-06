@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package com.funk.language.typechecker

import com.funk.language.parser.*
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.*

/**
 * OCaml-style module resolver that maps file system structure to module hierarchy
 * 
 * Examples:
 * - src/utils/string.funk -> Utils.String module
 * - src/data/list.funk -> Data.List module  
 * - src/core.funk -> Core module
 * - lib/json/parser.funk -> Json.Parser module
 */
class ModuleResolver(
    private val sourcePaths: List<Path> = listOf(Paths.get("src"), Paths.get("lib")),
    private val parser: FunkParserImpl = FunkParserImpl()
) {
    
    /**
     * Resolve a module name to its file path
     * Utils.String -> src/utils/string.funk or lib/utils/string.funk
     */
    fun resolveModuleToFile(moduleName: String): Path? {
        val parts = moduleName.split(".")
        val fileName = "${parts.last().lowercase()}.funk"
        val dirPath = if (parts.size > 1) {
            parts.dropLast(1).joinToString("/") { it.lowercase() }
        } else {
            ""
        }
        
        for (sourcePath in sourcePaths) {
            val fullPath = if (dirPath.isEmpty()) {
                sourcePath / fileName
            } else {
                sourcePath / dirPath / fileName
            }
            
            if (fullPath.exists() && fullPath.isRegularFile()) {
                return fullPath
            }
        }
        
        return null
    }
    
    /**
     * Convert file path to module name
     * src/utils/string.funk -> Utils.String
     * lib/json/parser.funk -> Json.Parser
     */
    fun fileToModuleName(filePath: Path): String? {
        val absolutePath = filePath.toAbsolutePath()
        
        for (sourcePath in sourcePaths) {
            val absoluteSourcePath = sourcePath.toAbsolutePath()
            
            if (absolutePath.startsWith(absoluteSourcePath)) {
                val relativePath = absoluteSourcePath.relativize(absolutePath)
                val pathParts = relativePath.pathString.split("/")
                
                // Remove .funk extension from last part
                val lastPart = pathParts.last()
                if (!lastPart.endsWith(".funk")) continue
                
                val moduleParts = pathParts.dropLast(1) + lastPart.removeSuffix(".funk")
                return moduleParts.joinToString(".") { it.capitalize() }
            }
        }
        
        return null
    }
    
    /**
     * Discover all modules in source paths
     */
    fun discoverModules(): Map<String, Path> {
        val modules = mutableMapOf<String, Path>()
        
        for (sourcePath in sourcePaths) {
            if (!sourcePath.exists() || !sourcePath.isDirectory()) continue
            
            sourcePath.walk()
                .filter { it.isRegularFile() && it.extension == "funk" }
                .forEach { filePath ->
                    val moduleName = fileToModuleName(filePath)
                    if (moduleName != null) {
                        modules[moduleName] = filePath
                    }
                }
        }
        
        return modules
    }
    
    /**
     * Load and parse a module from file
     */
    fun loadModule(moduleName: String): ModuleContent? {
        val filePath = resolveModuleToFile(moduleName) ?: return null
        
        return try {
            val source = filePath.readText()
            val program = parser.parse(source)
            
            // Convert top-level program items to module statements
            val moduleStatements = program.items.filterIsInstance<ModuleStatement>()
            val declarations = program.items.filterIsInstance<Declaration>()
            
            // If the file contains a module declaration, use it
            val moduleDecl = program.items.filterIsInstance<ModuleDeclaration>().firstOrNull()
            
            if (moduleDecl != null) {
                // File contains explicit module declaration
                ModuleContent.ExplicitModule(moduleDecl, filePath)
            } else {
                // File contains top-level declarations - create implicit module
                val implicitModule = ModuleDeclaration(
                    name = moduleName,
                    statements = declarations + moduleStatements
                )
                ModuleContent.ImplicitModule(implicitModule, filePath)
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Get all dependencies of a module (modules it imports)
     */
    fun getModuleDependencies(moduleName: String): Set<String> {
        val moduleContent = loadModule(moduleName) ?: return emptySet()
        val module = moduleContent.module
        
        val dependencies = mutableSetOf<String>()
        
        for (statement in module.statements) {
            if (statement is AccessDeclaration) {
                dependencies.add(statement.moduleName)
                
                // Handle nested module dependencies
                val nestedParts = statement.moduleName.split(".")
                if (nestedParts.size > 1) {
                    // Add parent modules as dependencies too
                    for (i in 1 until nestedParts.size) {
                        val parentModule = nestedParts.take(i).joinToString(".")
                        dependencies.add(parentModule)
                    }
                }
            }
        }
        
        return dependencies
    }
    
    /**
     * Resolve module dependencies in topological order
     */
    fun resolveDependencyOrder(modules: Set<String>): List<String> {
        val visited = mutableSetOf<String>()
        val visiting = mutableSetOf<String>()
        val result = mutableListOf<String>()
        
        fun visit(module: String) {
            if (module in visiting) {
                throw ModuleException.CircularDependency(module)
            }
            if (module in visited) return
            
            visiting.add(module)
            
            val dependencies = getModuleDependencies(module)
            for (dep in dependencies) {
                if (dep in modules) { // Only consider dependencies that are in our module set
                    visit(dep)
                }
            }
            
            visiting.remove(module)
            visited.add(module)
            result.add(module)
        }
        
        for (module in modules) {
            visit(module)
        }
        
        return result
    }
    
    /**
     * Create module search paths for nested modules
     * Utils.String -> [Utils, Utils.String]
     */
    fun getModuleSearchPath(moduleName: String): List<String> {
        val parts = moduleName.split(".")
        return (1..parts.size).map { i ->
            parts.take(i).joinToString(".")
        }
    }
}

/**
 * Module content loaded from file system
 */
sealed class ModuleContent {
    abstract val module: ModuleDeclaration
    abstract val filePath: Path
    
    data class ExplicitModule(
        override val module: ModuleDeclaration,
        override val filePath: Path
    ) : ModuleContent()
    
    data class ImplicitModule(
        override val module: ModuleDeclaration,
        override val filePath: Path
    ) : ModuleContent()
}

/**
 * Module system exceptions
 */
sealed class ModuleException(message: String) : Exception(message) {
    data class CircularDependency(val module: String) : 
        ModuleException("Circular dependency detected involving module: $module")
    
    data class ModuleNotFound(val module: String) : 
        ModuleException("Module not found: $module")
    
    data class InvalidModulePath(val path: String) : 
        ModuleException("Invalid module path: $path")
}

/**
 * Utility extension functions
 */
private fun String.capitalize(): String = 
    if (isEmpty()) this else first().uppercaseChar() + drop(1)
