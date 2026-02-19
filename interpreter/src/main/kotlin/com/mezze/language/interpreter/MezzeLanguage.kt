package com.mezze.language.interpreter

import com.oracle.truffle.api.CallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.TruffleLanguage.Registration
import com.oracle.truffle.api.nodes.Node
import com.oracle.truffle.api.nodes.RootNode
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.frame.FrameDescriptor
import com.mezze.language.parser.MezzeParser
import com.mezze.language.ast.*
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.interpreter.nodes.ASTToTruffleConverter
import com.mezze.language.interpreter.nodes.MezzeNode

/**
 * The main Mezze language implementation for GraalVM Truffle.
 * This class serves as the entry point for the Mezze language runtime.
 */
@Registration(
    id = MezzeLanguage.ID,
    name = "Mezze",
    implementationName = "Mezze",
    version = "1.0.0",
    defaultMimeType = MezzeLanguage.MIME_TYPE,
    characterMimeTypes = [MezzeLanguage.MIME_TYPE],
    fileTypeDetectors = [MezzeFileDetector::class],
    interactive = true,
    internal = false
)
class MezzeLanguage : TruffleLanguage<MezzeContext>() {
    
    companion object {
        const val ID = "mezze"
        const val MIME_TYPE = "application/x-mezze"

        // Store the last execution context for test access
        @Volatile
        private var lastExecutionContextStore: MezzeContext? = null

        // Store the last execution result for test access (avoids polyglot conversion issues)
        @Volatile
        private var lastExecutionResultStore: MezzeValue? = null

        // ThreadLocal storage for pre-parsed programs (for direct AST execution)
        private val directExecutionProgram = ThreadLocal<Pair<Program, java.nio.file.Path>?>()

        // ThreadLocal storage for monomorphic instances (compiler-generated specializations)
        private val monomorphicInstances = ThreadLocal<Map<String, LetBinding>>()

        fun getCurrentLanguage(node: Node): MezzeLanguage {
            @Suppress("DEPRECATION")
            return getCurrentLanguage(MezzeLanguage::class.java)
        }

        fun getCurrentContext(node: Node): MezzeContext {
            @Suppress("DEPRECATION")
            return getCurrentContext(MezzeLanguage::class.java)
        }

        internal fun storeLastExecutionContext(context: MezzeContext) {
            lastExecutionContextStore = context
        }

        internal fun storeLastExecutionResult(result: MezzeValue) {
            lastExecutionResultStore = result
        }

        fun getLastExecutionResult(): MezzeValue? {
            return lastExecutionResultStore
        }

        fun getLastExecutionContextGlobals(): Map<String, MezzeValue> {
            val context = lastExecutionContextStore ?: return emptyMap()
            val result = mutableMapOf<String, MezzeValue>()
            result.putAll(context.getAllGlobals())
            result.putAll(context.locals as Map<String, MezzeValue>)
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
    
    override fun createContext(env: Env): MezzeContext {
        // Store the Truffle environment in ThreadLocal for cross-thread execution
        TruffleContextHolder.setEnv(env)
        return MezzeContext(this, env)
    }

    /**
     * Allow multi-threaded access to Mezze contexts.
     * This is required for Loom concurrency support where virtual threads
     * need to access the same context concurrently.
     */
    override fun isThreadAccessAllowed(thread: Thread, singleThreaded: Boolean): Boolean {
        // Always allow multi-threaded access for Loom concurrency
        return true
    }

    /**
     * Initialize multi-threading support for a context.
     * Called before the context is accessed from multiple threads simultaneously.
     * This allows the language to disable single-threaded optimizations.
     */
    override fun initializeMultiThreading(context: MezzeContext) {
        // No single-threaded optimizations to disable currently
        // Future optimizations that assume single-threaded access should be disabled here
    }

    /**
     * Initialize thread-local state before a context is accessed from a new thread.
     * Called for each new thread (including virtual threads created via Loom).
     */
    override fun initializeThread(context: MezzeContext, thread: Thread) {
        // Store the Truffle environment for this thread
        TruffleContextHolder.setEnv(context.env)
    }

    /**
     * Clean up thread-local state when a thread stops accessing the context.
     */
    override fun disposeThread(context: MezzeContext, thread: Thread) {
        // Clear the thread-local environment
        TruffleContextHolder.clearEnv()
    }
    
    override fun parse(request: ParsingRequest): CallTarget {
        val source = request.source
        val sourceCode = source.characters.toString()

        try {
            // Check if this is a direct AST execution (from tests)
            val directExecution = getAndClearDirectExecutionProgram()
            if (directExecution != null) {
                val (typedProgram, projectRoot) = directExecution
                val monomorphicInstances = getAndClearMonomorphicInstances()

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
                        else -> throw MezzeException("Unknown UsePath type: ${usePath::class.simpleName}")
                    }
                }

                val pubUsePaths = pubUseDeclarations.map { it.path }.map { usePath ->
                    when (usePath) {
                        is ModulePath -> usePath.segments.joinToString("::")
                        is SelectivePath -> usePath.module.joinToString("::")
                        else -> throw MezzeException("Unknown UsePath type: ${usePath::class.simpleName}")
                    }
                }

                val allImportedModules = (usePaths + pubUsePaths).distinct()

                // CRITICAL: Process modules BEFORE converting main program to accumulate handler mappings
                // Load cached typed modules and extract their handler mappings
                // Note: Hash slots are per-frame-descriptor, so cross-module lookups use context instead
                val accumulatedHandlerMapping = mutableMapOf<String, Pair<String, String>>()
                for (modulePath in allImportedModules) {
                    val typedModule = TypeChecker.getTypedModule(modulePath)
                    if (typedModule != null) {
                        val moduleConverter = ASTToTruffleConverter(projectRoot = projectRoot)
                        moduleConverter.convertProgram(typedModule)
                        // Extract and accumulate handler mappings
                        accumulatedHandlerMapping.putAll(moduleConverter.getHandlerMappings())
                    }
                }

                // Convert main program with accumulated handler mappings
                val converter = ASTToTruffleConverter(parentHandlerMapping = accumulatedHandlerMapping)
                val truffleNode = converter.convertProgram(programWithMonomorphicInstances)
                val frameDescriptor = converter.getFrameDescriptor()
                val effectDeclarations = converter.getEffectDeclarations()

                // Create execution root node with module loading support
                val rootNode = MezzeTruffleRootNode(this, truffleNode, frameDescriptor, sourceCode, allImportedModules, projectRoot, effectDeclarations)
                return rootNode.callTarget
            }

            // Parse the source code using ParserV2
            val parserImpl = MezzeParser()
            val ast = parserImpl.parse(sourceCode)

            // Extract UseDeclarations from the ORIGINAL AST before type-checking
            // Type-checking may transform or remove these declarations
            val useDeclarations: List<UseDeclaration> = ast.items.filterIsInstance<UseDeclaration>()
            val pubUseDeclarations: List<PubUseDeclaration> = ast.items.filterIsInstance<PubUseDeclaration>()

            // Determine project root for type-checking and module loading
            // Priority: 1) System property 2) Source file parent 3) Current directory
            val mezzeProjectRootProp = System.getProperty("mezze.project.root")

            val projectRoot = mezzeProjectRootProp?.let {
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
                    else -> throw MezzeException("Unknown UsePath type: ${usePath::class.simpleName}")
                }
            }

            val pubUsePaths = pubUseDeclarations.map { it.path }.map { usePath ->
                when (usePath) {
                    is ModulePath -> usePath.segments.joinToString("::")
                    is SelectivePath -> usePath.module.joinToString("::")
                    else -> throw MezzeException("Unknown UsePath type: ${usePath::class.simpleName}")
                }
            }

            val allImportedModules = (usePaths + pubUsePaths).distinct()

            // CRITICAL: Process modules BEFORE converting main program to accumulate handler mappings
            // Load cached typed modules and extract their handler mappings
            // Note: Hash slots are per-frame-descriptor, so cross-module lookups use context instead
            val accumulatedHandlerMapping = mutableMapOf<String, Pair<String, String>>()
            for (modulePath in allImportedModules) {
                val typedModule = TypeChecker.getTypedModule(modulePath)
                if (typedModule != null) {
                    val moduleConverter = ASTToTruffleConverter(projectRoot = projectRoot)
                    moduleConverter.convertProgram(typedModule)
                    // Extract and accumulate handler mappings
                    accumulatedHandlerMapping.putAll(moduleConverter.getHandlerMappings())
                }
            }

            // Convert main program with accumulated handler mappings
            val converter = ASTToTruffleConverter(parentHandlerMapping = accumulatedHandlerMapping)
            val truffleNode = converter.convertProgram(typedProgram)
            val frameDescriptor = converter.getFrameDescriptor()
            val effectDeclarations = converter.getEffectDeclarations()

            // Create optimized Truffle execution root node with module loading support
            val rootNode = MezzeTruffleRootNode(this, truffleNode, frameDescriptor, sourceCode, allImportedModules, projectRoot, effectDeclarations)
            return rootNode.callTarget
        } catch (e: Exception) {
            throw MezzeException("Parse error: ${e.message}", e)
        }
    }
    
    // Note: isObjectOfLanguage method signature may vary by Truffle version
    // For now, we'll comment this out and add it back when we have the correct signature
    // override fun isObjectOfLanguage(`object`: Any): Boolean {
    //     return `object` is MezzeValue
    // }
}

/**
 * Root node that uses MezzeInterpreter for module-based programs
 */
class MezzeInterpreterRootNode(
    language: MezzeLanguage,
    private val program: Program,
    private val projectRoot: java.nio.file.Path,
    sourceCode: String
) : RootNode(language, FrameDescriptor.newBuilder().build()) {
    
    override fun execute(frame: VirtualFrame): Any {
        return try {
            val interpreter = MezzeInterpreter(skipParserSetup = true)
            val result = interpreter.execute(program, projectRoot)
            
            // Convert MezzeValue to Truffle-compatible value
            when (result) {
                is com.mezze.language.interpreter.MezzeInt -> result.value as Any
                is com.mezze.language.interpreter.MezzeFloat -> result.value as Any
                is com.mezze.language.interpreter.MezzeString -> result.value as Any
                is com.mezze.language.interpreter.MezzeBool -> result.value as Any
                is com.mezze.language.interpreter.MezzeNull -> null as Any?
                else -> result.toString() as Any
            } ?: "null"
        } catch (e: Exception) {
            throw MezzeException("Execution error: ${e.message}", e)
        }
    }
}

/**
 * Optimized Truffle root node for program execution using specialized nodes
 * Handles module loading and execution before running the main program
 */
class MezzeTruffleRootNode(
    language: MezzeLanguage,
    @field:Child private var programNode: MezzeNode,
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
            val context = MezzeLanguage.getCurrentContext(this)

            // Register effect declarations collected during conversion (only once)
            if (!effectsRegistered) {
                val effectHandler = context.effectHandler
                for (effectDecl in effectDeclarations) {
                    // Create a record with all operations as MezzeNull placeholders
                    // This allows createSuspendedEffectCall to validate operations exist
                    val operationsMap = effectDecl.operations.associate { op ->
                        op.name to MezzeNull
                    }
                    val effectRecord = MezzeRecord(operationsMap)
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
            MezzeLanguage.storeLastExecutionContext(context)
            MezzeLanguage.storeLastExecutionResult(result)

            // Convert MezzeValue to appropriate return type for Truffle interop
            when (result) {
                is MezzeInt -> result.value
                is MezzeFloat -> result.value
                is MezzeString -> result.value
                is MezzeBool -> result.value
                is MezzeNull -> "null" // Return string "null" instead of actual null
                else -> result.getDisplayString()
            }
        } catch (e: Exception) {
            // Print full exception chain for debugging
            System.err.println("ERROR: Exception in MezzeTruffleRootNode.execute:")
            System.err.println("  Message: ${e.message}")
            System.err.println("  Class: ${e.javaClass.name}")
            System.err.println("  Cause: ${e.cause}")
            e.printStackTrace(System.err)
            throw MezzeException("Execution error in Truffle node: ${e.message}", e)
        }
    }

    /**
     * Load and execute all modules imported via UseDeclarations
     * This registers module functions in the context so they can be called
     */
    private fun loadModules(modulePaths: List<String>, context: MezzeContext, frame: VirtualFrame) {
        for (modulePath in modulePaths) {
            loadModuleRecursively(modulePath, context, frame)
        }
    }

    /**
     * Load a module and all its dependencies recursively
     */
    private fun loadModuleRecursively(modulePath: String, context: MezzeContext, frame: VirtualFrame) {
        // Skip if already loaded
        if (modulePath in loadedModules) {
            return
        }

        try {
            // Load the module using ModuleResolver
            val moduleFile = resolveModulePath(modulePath)

            if (!moduleFile.exists()) {
                throw MezzeException("Module not found: $modulePath at ${moduleFile.absolutePath}")
            }

            // Retrieve the already-typed module from cache (avoids re-parsing/re-type-checking)
            // The CLI type-checker already processed and cached all modules
            val typedModule = TypeChecker.getTypedModule(modulePath)
                ?: throw MezzeException("Module $modulePath not found in typed cache. Ensure the module was type-checked by the CLI.")

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
            val args = arrayOf<Any?>(MezzeRecord(emptyMap()), emptyMap<String, MezzeValue>())
            val moduleFrame: VirtualFrame = com.oracle.truffle.api.Truffle.getRuntime()
                .createMaterializedFrame(args, moduleFrameDescriptor)

            moduleNode.execute(moduleFrame)

            // After module execution, its functions are registered with unqualified names
            // We need to also register them with qualified names for imports
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

            // Note: Ability impls (ImplAbilityDeclaration) are NOT exported by name here.
            // They are registered by content hash in ASTToTruffleConverter.convertImplAbilityDeclaration
            // and looked up by hash at runtime via ResolvedAbility.implementationHash.

            val allExports = publicExports + publicPerformers

            // Register public exports with BOTH full qualified names AND short qualified names
            // Full path (e.g., "std::types::int::abs") - for selective imports that use full path
            // Short path (e.g., "int::abs") - for module-level imports like `use std::int`
            val shortPrefix = modulePath.split("::").last()  // e.g., "int" from "std::int"
            for (exportName in allExports) {
                val exportValue = moduleExports[exportName]
                if (exportValue != null) {
                    // Register with full qualified name (prevents collision, used by selective imports)
                    val fullQualifiedName = "$modulePath::$exportName"
                    context.defineGlobal(fullQualifiedName, exportValue)

                    // Register with short qualified name (for backward compat with module-level imports)
                    val shortQualifiedName = "$shortPrefix::$exportName"
                    context.defineGlobal(shortQualifiedName, exportValue)
                }
            }

            // Mark as loaded
            loadedModules.add(modulePath)

        } catch (e: Exception) {
            throw MezzeException("Failed to load module '$modulePath': ${e.message}", e)
        }
    }

    /**
     * Resolve a module path to a file
     * Example: "std::collections::array" -> "stdlib/std/collections/array.mz"
     */
    private fun resolveModulePath(modulePath: String): java.io.File {
        val parts = modulePath.split("::")

        // Check if it's a stdlib module (starts with "std")
        if (parts.firstOrNull() == "std") {
            // Try to load from embedded resources first (for native binary / JAR)
            val relativePath = "stdlib/${parts.joinToString("/")}.mz"
            val resourceStream = javaClass.classLoader.getResourceAsStream(relativePath)
            if (resourceStream != null) {
                // Write to temp file
                val tempFile = java.nio.file.Files.createTempFile("mezze-stdlib-", ".mz")
                java.nio.file.Files.writeString(tempFile, resourceStream.bufferedReader().use { it.readText() })
                tempFile.toFile().deleteOnExit()
                return tempFile.toFile()
            }

            // Fall back to filesystem (for development)
            return projectRoot.resolve(relativePath).toFile()
        } else {
            // User modules: search in src/, lib/, root, and .deps/mezze/
            // (consistent with type checker's ModuleResolver)
            val relativePath = parts.joinToString("/") + ".mz"

            // Find the actual project root by looking for Mezze.toml
            val actualProjectRoot = findUserProjectRoot(projectRoot)

            // Search in local project directories first
            val localSearchPaths = listOf(
                actualProjectRoot.resolve("src").resolve(relativePath),
                actualProjectRoot.resolve("lib").resolve(relativePath),
                actualProjectRoot.resolve(relativePath)
            )

            for (path in localSearchPaths) {
                val file = path.toFile()
                if (file.exists() && file.isFile) {
                    return file
                }
            }

            // Search in .deps/mezze/ for downloaded dependencies
            // For a module path like "json::parser", we look in:
            //   .deps/mezze/json-*/src/parser.mz
            //   .deps/mezze/json-*/parser.mz
            if (parts.isNotEmpty()) {
                val packageName = parts[0]
                val restOfPath = if (parts.size > 1) parts.drop(1).joinToString("/") + ".mz" else "$packageName.mz"
                val depsDir = actualProjectRoot.resolve(".deps/mezze")

                if (java.nio.file.Files.exists(depsDir) && java.nio.file.Files.isDirectory(depsDir)) {
                    // Find directories matching {packageName}-* (e.g., json-1.2.0)
                    java.nio.file.Files.list(depsDir).use { stream ->
                        val matchingDirs = stream
                            .filter { java.nio.file.Files.isDirectory(it) }
                            .filter { it.fileName.toString().startsWith("$packageName-") }
                            .toList()

                        for (depDir in matchingDirs) {
                            val depSearchPaths = listOf(
                                depDir.resolve("src").resolve(restOfPath),
                                depDir.resolve(restOfPath)
                            )

                            for (path in depSearchPaths) {
                                if (java.nio.file.Files.exists(path) && java.nio.file.Files.isRegularFile(path)) {
                                    return path.toFile()
                                }
                            }
                        }
                    }
                }
            }

            // Return the first local path for error message
            return localSearchPaths.last().toFile()
        }
    }

    /**
     * Find the user project root by looking for Mezze.toml in current and parent directories.
     */
    private fun findUserProjectRoot(startPath: java.nio.file.Path): java.nio.file.Path {
        var current = startPath.toAbsolutePath()

        // Look for Mezze.toml in current and parent directories (up to 10 levels)
        for (i in 0..10) {
            val mezzeToml = current.resolve("Mezze.toml")
            if (java.nio.file.Files.exists(mezzeToml) && java.nio.file.Files.isRegularFile(mezzeToml)) {
                return current
            }

            val parent = current.parent
            if (parent == null || parent == current) break
            current = parent
        }

        // Fallback to the original start path
        return startPath.toAbsolutePath()
    }

    override fun getName(): String = "mezze-program"

    override fun toString(): String = "MezzeTruffleRootNode"
}

/**
 * Legacy root node for backward compatibility with existing interpreter
 */
class MezzeProgramRootNode(
    language: MezzeLanguage,
    private val program: Program,
    private val interpreter: MezzeInterpreter
) : RootNode(language) {
    
    override fun execute(frame: VirtualFrame): Any {
        // Execute the program using the Mezze interpreter with shared AST
        return try {
            val result = interpreter.execute(program)
            // Convert MezzeValue to appropriate return type for Truffle
            when (result) {
                is MezzeInt -> result.value
                is MezzeFloat -> result.value
                is MezzeString -> result.value
                is MezzeBool -> result.value
                is MezzeNull -> "null" // Return string "null" instead of actual null
                else -> result.getDisplayString()
            }
        } catch (e: Exception) {
            throw MezzeException("Execution error: ${e.message}", e)
        }
    }
}
