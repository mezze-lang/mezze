package com.funk.language.interpreter

import com.oracle.truffle.api.CallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.TruffleLanguage.Registration
import com.oracle.truffle.api.nodes.Node
import com.oracle.truffle.api.nodes.RootNode
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.frame.FrameDescriptor
import com.funk.language.parser.FunkParser
import com.funk.language.ast.*
import com.funk.language.typechecker.TypeChecker
import com.funk.language.interpreter.nodes.ASTToTruffleConverter
import com.funk.language.interpreter.nodes.FunkNode

/**
 * The main Funk language implementation for GraalVM Truffle.
 * This class serves as the entry point for the Funk language runtime.
 */
@Registration(
    id = FunkLanguage.ID,
    name = "Funk",
    implementationName = "Funk",
    version = "1.0.0",
    defaultMimeType = FunkLanguage.MIME_TYPE,
    characterMimeTypes = [FunkLanguage.MIME_TYPE],
    fileTypeDetectors = [FunkFileDetector::class],
    interactive = true,
    internal = false
)
class FunkLanguage : TruffleLanguage<FunkContext>() {
    
    companion object {
        const val ID = "funk"
        const val MIME_TYPE = "application/x-funk"

        // Store the last execution context for test access
        @Volatile
        private var lastExecutionContextStore: FunkContext? = null

        // Store the last execution result for test access (avoids polyglot conversion issues)
        @Volatile
        private var lastExecutionResultStore: FunkValue? = null

        // ThreadLocal storage for pre-parsed programs (for direct AST execution)
        private val directExecutionProgram = ThreadLocal<Pair<Program, java.nio.file.Path>?>()

        // ThreadLocal storage for monomorphic instances (compiler-generated specializations)
        private val monomorphicInstances = ThreadLocal<Map<String, LetBinding>>()

        fun getCurrentLanguage(node: Node): FunkLanguage {
            @Suppress("DEPRECATION")
            return getCurrentLanguage(FunkLanguage::class.java)
        }

        fun getCurrentContext(node: Node): FunkContext {
            @Suppress("DEPRECATION")
            return getCurrentContext(FunkLanguage::class.java)
        }

        internal fun storeLastExecutionContext(context: FunkContext) {
            lastExecutionContextStore = context
        }

        internal fun storeLastExecutionResult(result: FunkValue) {
            lastExecutionResultStore = result
        }

        fun getLastExecutionResult(): FunkValue? {
            return lastExecutionResultStore
        }

        fun getLastExecutionContextGlobals(): Map<String, FunkValue> {
            val context = lastExecutionContextStore ?: return emptyMap()
            val result = mutableMapOf<String, FunkValue>()
            result.putAll(context.getAllGlobals())
            result.putAll(context.locals as Map<String, FunkValue>)
            return result
        }

        fun setProgramForDirectExecution(program: Program, projectRoot: java.nio.file.Path) {
            directExecutionProgram.set(Pair(program, projectRoot))
        }

        internal fun getAndClearDirectExecutionProgram(): Pair<Program, java.nio.file.Path>? {
            val program = directExecutionProgram.get()
            directExecutionProgram.remove()
            return program
        }

        fun setMonomorphicInstances(instances: Map<String, LetBinding>) {
            monomorphicInstances.set(instances)
        }

        internal fun getAndClearMonomorphicInstances(): Map<String, LetBinding> {
            val instances = monomorphicInstances.get() ?: emptyMap()
            monomorphicInstances.remove()
            return instances
        }
    }
    
    override fun createContext(env: Env): FunkContext {
        return FunkContext(this, env)
    }
    
    override fun parse(request: ParsingRequest): CallTarget {
        val source = request.source
        val sourceCode = source.characters.toString()

        // Clear native global store for new program execution
        // This ensures clean state for each program run
        NativeGlobalStore.clear()

        try {
            // Check if this is a direct AST execution (from tests)
            val directExecution = getAndClearDirectExecutionProgram()
            if (directExecution != null) {
                val (typedProgram, projectRoot) = directExecution
                val monomorphicInstances = getAndClearMonomorphicInstances()

                // Extract UseDeclarations from the program to support module loading
                val useDeclarations: List<UseDeclaration> = typedProgram.items.filterIsInstance<UseDeclaration>()
                val pubUseDeclarations: List<PubUseDeclaration> = typedProgram.items.filterIsInstance<PubUseDeclaration>()

                // Extract module paths
                val usePaths = useDeclarations.map { it.path }.map { usePath ->
                    when (usePath) {
                        is ModulePath -> usePath.segments.joinToString("::")
                        is SelectivePath -> usePath.module.joinToString("::")
                        else -> throw FunkException("Unknown UsePath type: ${usePath::class.simpleName}")
                    }
                }

                val pubUsePaths = pubUseDeclarations.map { it.path }.map { usePath ->
                    when (usePath) {
                        is ModulePath -> usePath.segments.joinToString("::")
                        is SelectivePath -> usePath.module.joinToString("::")
                        else -> throw FunkException("Unknown UsePath type: ${usePath::class.simpleName}")
                    }
                }

                val allImportedModules = (usePaths + pubUsePaths).distinct()

                println("DEBUG FunkLanguage.parse (direct execution): Found ${allImportedModules.size} imported modules: $allImportedModules")

                // Convert to Truffle nodes
                val converter = ASTToTruffleConverter()
                val truffleNode = converter.convertProgram(typedProgram)
                val frameDescriptor = converter.getFrameDescriptor()

                // Create execution root node with module loading support
                val rootNode = FunkTruffleRootNode(this, truffleNode, frameDescriptor, sourceCode, allImportedModules, projectRoot)
                return rootNode.callTarget
            }

            // Parse the source code using ParserV2
            val parserImpl = FunkParser()
            val ast = parserImpl.parse(sourceCode)

            println("DEBUG FunkLanguage.parse: AST has ${ast.items.size} items")
            println("DEBUG FunkLanguage.parse: Item types: ${ast.items.map { it::class.simpleName }}")

            // Extract UseDeclarations from the ORIGINAL AST before type-checking
            // Type-checking may transform or remove these declarations
            val useDeclarations: List<UseDeclaration> = ast.items.filterIsInstance<UseDeclaration>()
            val pubUseDeclarations: List<PubUseDeclaration> = ast.items.filterIsInstance<PubUseDeclaration>()

            println("DEBUG FunkLanguage.parse: Found ${useDeclarations.size} UseDeclarations and ${pubUseDeclarations.size} PubUseDeclarations")

            // Determine project root for type-checking and module loading
            // Priority: 1) System property 2) Source file parent 3) Current directory
            val projectRoot = System.getProperty("funk.project.root")?.let {
                java.nio.file.Paths.get(it)
            } ?: if (source.path != null) {
                val sourcePath = java.nio.file.Paths.get(source.path)
                sourcePath.parent ?: sourcePath
            } else {
                java.nio.file.Paths.get(".") // Fallback to current directory
            }

            // Type check the program
            val typeChecker = TypeChecker()
            val typeCheckResult = typeChecker.typeCheck(ast, projectRoot)

            val typedProgram = typeCheckResult.program

            // Extract module paths from both types of declarations
            val usePaths = useDeclarations.map { it.path }.map { usePath ->
                when (usePath) {
                    is ModulePath -> usePath.segments.joinToString("::")
                    is SelectivePath -> usePath.module.joinToString("::")
                    else -> throw FunkException("Unknown UsePath type: ${usePath::class.simpleName}")
                }
            }

            val pubUsePaths = pubUseDeclarations.map { it.path }.map { usePath ->
                when (usePath) {
                    is ModulePath -> usePath.segments.joinToString("::")
                    is SelectivePath -> usePath.module.joinToString("::")
                    else -> throw FunkException("Unknown UsePath type: ${usePath::class.simpleName}")
                }
            }

            val allImportedModules = (usePaths + pubUsePaths).distinct()

            println("DEBUG FunkLanguage.parse: Found ${allImportedModules.size} imported modules: $allImportedModules")

            // Always use optimized Truffle nodes for all programs (including modules)
            val converter = ASTToTruffleConverter()
            val truffleNode = converter.convertProgram(typedProgram)
            val frameDescriptor = converter.getFrameDescriptor()

            // Create optimized Truffle execution root node with module loading support
            val rootNode = FunkTruffleRootNode(this, truffleNode, frameDescriptor, sourceCode, allImportedModules, projectRoot)
            return rootNode.callTarget
        } catch (e: Exception) {
            throw FunkException("Parse error: ${e.message}", e)
        }
    }
    
    // Note: isObjectOfLanguage method signature may vary by Truffle version
    // For now, we'll comment this out and add it back when we have the correct signature
    // override fun isObjectOfLanguage(`object`: Any): Boolean {
    //     return `object` is FunkValue
    // }
}

/**
 * Root node that uses FunkInterpreter for module-based programs
 */
class FunkInterpreterRootNode(
    language: FunkLanguage,
    private val program: Program,
    private val projectRoot: java.nio.file.Path,
    sourceCode: String
) : RootNode(language, FrameDescriptor.newBuilder().build()) {
    
    override fun execute(frame: VirtualFrame): Any {
        return try {
            val interpreter = FunkInterpreter(skipParserSetup = true)
            val result = interpreter.execute(program, projectRoot)
            
            // Convert FunkValue to Truffle-compatible value
            when (result) {
                is com.funk.language.interpreter.FunkInt -> result.value as Any
                is com.funk.language.interpreter.FunkFloat -> result.value as Any
                is com.funk.language.interpreter.FunkString -> result.value as Any
                is com.funk.language.interpreter.FunkBool -> result.value as Any
                is com.funk.language.interpreter.FunkNull -> null as Any?
                else -> result.toString() as Any
            } ?: "null"
        } catch (e: Exception) {
            throw FunkException("Execution error: ${e.message}", e)
        }
    }
}

/**
 * Optimized Truffle root node for program execution using specialized nodes
 * Handles module loading and execution before running the main program
 */
class FunkTruffleRootNode(
    language: FunkLanguage,
    @field:Child private var programNode: FunkNode,
    frameDescriptor: FrameDescriptor,
    private val sourceCode: String,
    private val importedModules: List<String>,
    private val projectRoot: java.nio.file.Path
) : RootNode(language, frameDescriptor) {

    // Lazy-initialized module cache to avoid reloading modules on every execution
    private val loadedModules = mutableSetOf<String>()

    override fun execute(frame: VirtualFrame): Any {
        println("DEBUG FunkTruffleRootNode.execute: Starting execution")
        return try {
            // Get the context for registering module functions
            val context = FunkLanguage.getCurrentContext(this)
            println("DEBUG FunkTruffleRootNode.execute: Got context, about to load modules")

            // Load and execute all imported modules before running the main program
            // This ensures module functions are registered in the context
            loadModules(importedModules, context, frame)
            println("DEBUG FunkTruffleRootNode.execute: Modules loaded, executing main program")

            // Now execute the main program
            val result = programNode.execute(frame)

            // Store execution context and result for test access
            FunkLanguage.storeLastExecutionContext(context)
            FunkLanguage.storeLastExecutionResult(result)

            // Convert FunkValue to appropriate return type for Truffle interop
            when (result) {
                is FunkInt -> result.value
                is FunkFloat -> result.value
                is FunkString -> result.value
                is FunkBool -> result.value
                is FunkNull -> "null" // Return string "null" instead of actual null
                else -> result.getDisplayString()
            }
        } catch (e: Exception) {
            throw FunkException("Execution error in Truffle node: ${e.message}", e)
        }
    }

    /**
     * Load and execute all modules imported via UseDeclarations
     * This registers module functions in the context so they can be called
     */
    private fun loadModules(modulePaths: List<String>, context: FunkContext, frame: VirtualFrame) {
        println("DEBUG loadModules: Starting, ${modulePaths.size} modules to load")
        println("DEBUG loadModules: Module paths: $modulePaths")

        for (modulePath in modulePaths) {
            // Skip if already loaded
            if (modulePath in loadedModules) {
                println("DEBUG loadModules: Module '$modulePath' already loaded, skipping")
                continue
            }

            println("DEBUG loadModules: Loading module '$modulePath'")

            try {
                // Load the module using ModuleResolver
                val moduleFile = resolveModulePath(modulePath)

                if (!moduleFile.exists()) {
                    throw FunkException("Module not found: $modulePath at ${moduleFile.absolutePath}")
                }

                println("DEBUG loadModules: Module file: ${moduleFile.absolutePath}")

                // Parse and type-check the module
                val parserImpl = FunkParser()
                val moduleSource = moduleFile.readText()
                val moduleAst = parserImpl.parse(moduleSource)

                val typeChecker = TypeChecker()
                val moduleTypeCheckResult = typeChecker.typeCheck(moduleAst, projectRoot)
                val typedModule = moduleTypeCheckResult.program

                println("DEBUG loadModules: Type-checked module, ${typedModule.items.size} items")

                // Convert module to Truffle nodes
                val moduleConverter = ASTToTruffleConverter(context, projectRoot)
                val moduleNode = moduleConverter.convertProgram(typedModule)
                val moduleFrameDescriptor = moduleConverter.getFrameDescriptor()

                println("DEBUG loadModules: Converted module to Truffle nodes")

                // Execute the module to register its functions
                // We need to create a frame for the module execution
                // MaterializedFrame extends VirtualFrame, so we can use it directly
                val moduleFrame: VirtualFrame = com.oracle.truffle.api.Truffle.getRuntime()
                    .createMaterializedFrame(emptyArray(), moduleFrameDescriptor)

                println("DEBUG loadModules: Executing module to register functions")
                moduleNode.execute(moduleFrame)

                // After module execution, its functions are registered with unqualified names
                // We need to also register them with qualified names (e.g., "int::abs")
                val modulePrefix = modulePath.split("::").last() // e.g., "int" from "std::int"
                val moduleExports = context.locals.toMap() // Get all locally defined values

                // Identify which exports came from this module (pub let declarations)
                val publicExports = typedModule.items.filterIsInstance<LetBinding>()
                    .filter { it.visibility == Visibility.PUBLIC }
                    .map { it.variableName }
                    .toSet()

                // Register public exports with qualified names
                for (exportName in publicExports) {
                    val exportValue = moduleExports[exportName]
                    if (exportValue != null) {
                        val qualifiedName = "$modulePrefix::$exportName"
                        println("DEBUG loadModules: Registering '$qualifiedName' = $exportValue")
                        context.defineGlobal(qualifiedName, exportValue)
                    }
                }

                println("DEBUG loadModules: Module '$modulePath' loaded successfully, registered ${publicExports.size} qualified exports")

                // Mark as loaded
                loadedModules.add(modulePath)

            } catch (e: Exception) {
                throw FunkException("Failed to load module '$modulePath': ${e.message}", e)
            }
        }
    }

    /**
     * Resolve a module path to a file
     * Example: "std::collections::array" -> "stdlib/std/collections/array.funk"
     */
    private fun resolveModulePath(modulePath: String): java.io.File {
        val parts = modulePath.split("::")

        // Check if it's a stdlib module (starts with "std")
        if (parts.firstOrNull() == "std") {
            // stdlib modules are in stdlib/std/...
            val relativePath = "stdlib/${parts.joinToString("/")}.funk"
            return projectRoot.resolve(relativePath).toFile()
        } else {
            // User modules are relative to project root
            val relativePath = parts.joinToString("/") + ".funk"
            return projectRoot.resolve(relativePath).toFile()
        }
    }

    override fun getName(): String = "funk-program"

    override fun toString(): String = "FunkTruffleRootNode"
}

/**
 * Legacy root node for backward compatibility with existing interpreter
 */
class FunkProgramRootNode(
    language: FunkLanguage,
    private val program: Program,
    private val interpreter: FunkInterpreter
) : RootNode(language) {
    
    override fun execute(frame: VirtualFrame): Any {
        // Execute the program using the Funk interpreter with shared AST
        return try {
            val result = interpreter.execute(program)
            // Convert FunkValue to appropriate return type for Truffle
            when (result) {
                is FunkInt -> result.value
                is FunkFloat -> result.value
                is FunkString -> result.value
                is FunkBool -> result.value
                is FunkNull -> "null" // Return string "null" instead of actual null
                else -> result.getDisplayString()
            }
        } catch (e: Exception) {
            throw FunkException("Execution error: ${e.message}", e)
        }
    }
}
