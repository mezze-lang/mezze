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

                // DEBUG: Print monomorphic instances
                if (monomorphicInstances.isNotEmpty()) {
                    System.err.println("DEBUG: Monomorphic instances being added:")
                    for ((name, binding) in monomorphicInstances) {
                        System.err.println("  - $name: ${binding.inferredType?.let { prettyPrintType(it) }}")
                    }
                }

                // CRITICAL FIX: Add monomorphic instances to the program
                // Monomorphic instances are specialized versions of polymorphic functions
                // (e.g., displayValue$Int is the Int-specialized version of displayValue)
                // IMPORTANT: Prepend them BEFORE other items so they're registered before being used
                val programWithMonomorphicInstances = if (monomorphicInstances.isNotEmpty()) {
                    // PREPEND monomorphic instances so they're executed BEFORE call sites
                    Program(monomorphicInstances.values.toList() + typedProgram.items)
                } else {
                    typedProgram
                }

                // Extract UseDeclarations from the program to support module loading
                val useDeclarations: List<UseDeclaration> = programWithMonomorphicInstances.items.filterIsInstance<UseDeclaration>()
                val pubUseDeclarations: List<PubUseDeclaration> = programWithMonomorphicInstances.items.filterIsInstance<PubUseDeclaration>()

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

                // CRITICAL: Process modules BEFORE converting main program to accumulate handler mappings
                // Load cached typed modules and extract their handler mappings
                val accumulatedHandlerMapping = mutableMapOf<String, Pair<String, String>>()
                for (modulePath in allImportedModules) {
                    val typedModule = TypeChecker.getTypedModule(modulePath)
                    if (typedModule != null) {
                        // Convert module to extract handler mappings (don't execute yet)
                        val moduleConverter = ASTToTruffleConverter(projectRoot = projectRoot)
                        moduleConverter.convertProgram(typedModule)
                        // Extract handler mappings from the module converter
                        accumulatedHandlerMapping.putAll(moduleConverter.getHandlerMappings())
                    }
                }

                // Convert main program with accumulated handler mappings from all modules
                val converter = ASTToTruffleConverter(parentHandlerMapping = accumulatedHandlerMapping)
                val truffleNode = converter.convertProgram(programWithMonomorphicInstances)
                val frameDescriptor = converter.getFrameDescriptor()
                val effectDeclarations = converter.getEffectDeclarations()

                // Create execution root node with module loading support
                val rootNode = FunkTruffleRootNode(this, truffleNode, frameDescriptor, sourceCode, allImportedModules, projectRoot, effectDeclarations)
                return rootNode.callTarget
            }

            // Parse the source code using ParserV2
            val parserImpl = FunkParser()
            val ast = parserImpl.parse(sourceCode)

            // Extract UseDeclarations from the ORIGINAL AST before type-checking
            // Type-checking may transform or remove these declarations
            val useDeclarations: List<UseDeclaration> = ast.items.filterIsInstance<UseDeclaration>()
            val pubUseDeclarations: List<PubUseDeclaration> = ast.items.filterIsInstance<PubUseDeclaration>()

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

            // CRITICAL: Process modules BEFORE converting main program to accumulate handler mappings
            // Load cached typed modules and extract their handler mappings
            val accumulatedHandlerMapping = mutableMapOf<String, Pair<String, String>>()
            for (modulePath in allImportedModules) {
                val typedModule = TypeChecker.getTypedModule(modulePath)
                if (typedModule != null) {
                    // Convert module to extract handler mappings (don't execute yet)
                    val moduleConverter = ASTToTruffleConverter(projectRoot = projectRoot)
                    moduleConverter.convertProgram(typedModule)
                    // Extract handler mappings from the module converter
                    accumulatedHandlerMapping.putAll(moduleConverter.getHandlerMappings())
                }
            }

            // Convert main program with accumulated handler mappings from all modules
            val converter = ASTToTruffleConverter(parentHandlerMapping = accumulatedHandlerMapping)
            val truffleNode = converter.convertProgram(typedProgram)
            val frameDescriptor = converter.getFrameDescriptor()
            val effectDeclarations = converter.getEffectDeclarations()

            // Create optimized Truffle execution root node with module loading support
            val rootNode = FunkTruffleRootNode(this, truffleNode, frameDescriptor, sourceCode, allImportedModules, projectRoot, effectDeclarations)
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
    private val projectRoot: java.nio.file.Path,
    private val effectDeclarations: List<EffectDeclaration>
) : RootNode(language, frameDescriptor) {

    // Lazy-initialized module cache to avoid reloading modules on every execution
    private val loadedModules = mutableSetOf<String>()

    // Track whether effects have been registered
    private var effectsRegistered = false

    override fun execute(frame: VirtualFrame): Any {
        return try {
            // Get the context for registering module functions
            val context = FunkLanguage.getCurrentContext(this)

            // Register effect declarations collected during conversion (only once)
            if (!effectsRegistered) {
                val effectHandler = context.effectHandler
                for (effectDecl in effectDeclarations) {
                    // Create a record with all operations as FunkNull placeholders
                    // This allows createSuspendedEffectCall to validate operations exist
                    val operationsMap = effectDecl.operations.associate { op ->
                        op.name to FunkNull
                    }
                    val effectRecord = FunkRecord(operationsMap)
                    effectHandler.registerEffect(effectDecl.name, effectRecord)
                }
                effectsRegistered = true
            }

            // Load and execute all imported modules before running the main program
            // This ensures module functions are registered in the context
            loadModules(importedModules, context, frame)

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
            // Print full exception chain for debugging
            System.err.println("ERROR: Exception in FunkTruffleRootNode.execute:")
            System.err.println("  Message: ${e.message}")
            System.err.println("  Class: ${e.javaClass.name}")
            System.err.println("  Cause: ${e.cause}")
            e.printStackTrace(System.err)
            throw FunkException("Execution error in Truffle node: ${e.message}", e)
        }
    }

    /**
     * Load and execute all modules imported via UseDeclarations
     * This registers module functions in the context so they can be called
     */
    private fun loadModules(modulePaths: List<String>, context: FunkContext, frame: VirtualFrame) {
        for (modulePath in modulePaths) {
            loadModuleRecursively(modulePath, context, frame)
        }
    }

    /**
     * Load a module and all its dependencies recursively
     */
    private fun loadModuleRecursively(modulePath: String, context: FunkContext, frame: VirtualFrame) {
        // Skip if already loaded
        if (modulePath in loadedModules) {
            return
        }

        try {
            // Load the module using ModuleResolver
            val moduleFile = resolveModulePath(modulePath)

            if (!moduleFile.exists()) {
                throw FunkException("Module not found: $modulePath at ${moduleFile.absolutePath}")
            }

            // Retrieve the already-typed module from cache (avoids re-parsing/re-type-checking)
            // The CLI type-checker already processed and cached all modules
            val typedModule = TypeChecker.getTypedModule(modulePath)
                ?: throw FunkException("Module $modulePath not found in typed cache. Ensure the module was type-checked by the CLI.")

            // CRITICAL: Load all dependencies FIRST before loading this module
            // Extract UseDeclarations to find dependencies
            val dependencies = mutableSetOf<String>()
            for (item in typedModule.items) {
                when (item) {
                    is UseDeclaration -> {
                        val depPath = when (val path = item.path) {
                            is ModulePath -> path.segments.joinToString("::")
                            is SelectivePath -> path.module.joinToString("::")
                            else -> null
                        }
                        if (depPath != null) dependencies.add(depPath)
                    }
                    is PubUseDeclaration -> {
                        val depPath = when (val path = item.path) {
                            is ModulePath -> path.segments.joinToString("::")
                            is SelectivePath -> path.module.joinToString("::")
                            else -> null
                        }
                        if (depPath != null) dependencies.add(depPath)
                    }
                    else -> {
                        // Ignore other program items (LetBinding, ImplDeclaration, etc.)
                    }
                }
            }

            // Recursively load all dependencies first
            for (dependency in dependencies) {
                loadModuleRecursively(dependency, context, frame)
            }

            // Now load this module
            // Convert module to Truffle nodes
            val moduleConverter = ASTToTruffleConverter(context, projectRoot)
            val moduleNode = moduleConverter.convertProgram(typedModule)
            val moduleFrameDescriptor = moduleConverter.getFrameDescriptor()

            // Execute the module to register its functions
            // Create a frame with 2-element arguments array per Truffle convention
            val args = arrayOf<Any?>(FunkRecord(emptyMap()), emptyMap<String, FunkValue>())
            val moduleFrame: VirtualFrame = com.oracle.truffle.api.Truffle.getRuntime()
                .createMaterializedFrame(args, moduleFrameDescriptor)

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

            // ALSO export impl declarations (effect handlers/performers)
            // NOTE: ImplDeclaration doesn't have a visibility field, so we export all of them
            // In practice, only pub impl declarations should be imported
            val publicPerformers = typedModule.items.filterIsInstance<ImplDeclaration>()
                .map { it.performerName }
                .toSet()

            val allExports = publicExports + publicPerformers

            // Register public exports with qualified names
            for (exportName in allExports) {
                val exportValue = moduleExports[exportName]
                if (exportValue != null) {
                    val qualifiedName = "$modulePrefix::$exportName"
                    context.defineGlobal(qualifiedName, exportValue)
                }
            }

            // Mark as loaded
            loadedModules.add(modulePath)

        } catch (e: Exception) {
            throw FunkException("Failed to load module '$modulePath': ${e.message}", e)
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
