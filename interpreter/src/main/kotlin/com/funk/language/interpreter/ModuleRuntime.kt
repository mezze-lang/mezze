package com.funk.language.interpreter

import com.funk.language.parser.*
import com.funk.language.typechecker.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Runtime module system for Funk
 * Handles module loading, caching, and dependency resolution
 */
class ModuleRuntime {
    
    private val loadedModules = ConcurrentHashMap<String, LoadedModule>()
    private val moduleSearchPaths = mutableListOf<String>()
    
    init {
        // Add default search paths
        moduleSearchPaths.add(".")
        moduleSearchPaths.add("./modules")
        moduleSearchPaths.add("./lib")
    }
    
    /**
     * Load and execute a module by name
     */
    fun loadModule(moduleName: String, context: FunkContext): LoadedModule {
        // Check if already loaded
        loadedModules[moduleName]?.let { return it }
        
        // Find module file
        val moduleFile = findModuleFile(moduleName)
            ?: throw FunkException.runtimeError("Module '$moduleName' not found in search paths: $moduleSearchPaths")
        
        // Parse and type check the module
        val parser = FunkParserImpl()
        val typeChecker = TypeChecker()
        
        val source = moduleFile.readText()
        val ast = parser.parse(source)
        
        // Type check with module environment
        val moduleEnv = ModuleEnvironment(moduleName)
        val typedProgram = typeChecker.typeCheck(ast)
        
        // Create module context
        val moduleContext = FunkContext(context.language, context.env)
        
        // Execute module statements
        val exports = mutableMapOf<String, FunkValue>()
        val interpreter = FunkInterpreter()
        
        for (item in typedProgram.items) {
            when (item) {
                is TypedLetBinding -> {
                    val value = interpreter.evaluateExpression(item.expression.expression, moduleContext)
                    moduleContext.defineGlobal(item.variableName, value)
                    
                    // Check if this binding should be exported
                    // For now, export all top-level bindings (would be refined with expose declarations)
                    exports[item.variableName] = value
                }
                
                is TypedModuleDeclaration -> {
                    // Handle nested modules (not implemented yet)
                    throw FunkException.runtimeError("Nested modules not yet implemented")
                }
                
                is TypedTypeDeclaration -> {
                    // Type declarations don't produce runtime values but could be exported
                }
                
                is TypedEffectDeclaration -> {
                    // Effect declarations for the effect system
                }
            }
        }
        
        val loadedModule = LoadedModule(
            name = moduleName,
            path = moduleFile.absolutePath,
            exports = exports,
            context = moduleContext
        )
        
        loadedModules[moduleName] = loadedModule
        return loadedModule
    }
    
    /**
     * Resolve an access to a module item (Module.item)
     */
    fun resolveAccess(moduleName: String, itemName: String, context: FunkContext): FunkValue {
        val module = loadModule(moduleName, context)
        return module.exports[itemName]
            ?: throw FunkException.runtimeError("Item '$itemName' not exported from module '$moduleName'")
    }
    
    /**
     * Add a search path for modules
     */
    fun addSearchPath(path: String) {
        moduleSearchPaths.add(path)
    }
    
    /**
     * Find a module file in the search paths
     */
    private fun findModuleFile(moduleName: String): File? {
        val possibleNames = listOf(
            "$moduleName.funk",
            "$moduleName/main.funk",
            "$moduleName/index.funk"
        )
        
        for (searchPath in moduleSearchPaths) {
            for (fileName in possibleNames) {
                val file = File(searchPath, fileName)
                if (file.exists() && file.isFile) {
                    return file
                }
            }
        }
        
        return null
    }
    
    /**
     * Clear all loaded modules (for testing/development)
     */
    fun clearCache() {
        loadedModules.clear()
    }
}

/**
 * Represents a loaded module with its exports and context
 */
data class LoadedModule(
    val name: String,
    val path: String,
    val exports: Map<String, FunkValue>,
    val context: FunkContext
)

/**
 * Module access expression node for Truffle
 */
class FunkModuleAccessNode(
    private val moduleName: String,
    private val itemName: String,
    private val moduleRuntime: ModuleRuntime
) : com.funk.language.interpreter.nodes.FunkExpressionNode() {
    
    override fun executeGeneric(frame: com.oracle.truffle.api.frame.VirtualFrame): FunkValue {
        val context = FunkLanguage.getCurrentContext(this)
        return moduleRuntime.resolveAccess(moduleName, itemName, context)
    }
}
