package com.mezze.language.interpreter

import com.oracle.truffle.api.CallTarget
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
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
    name = "Mezze V1",
    implementationName = "Mezze V1",
    version = "1.0.0",
    defaultMimeType = MezzeLanguage.MIME_TYPE,
    characterMimeTypes = [MezzeLanguage.MIME_TYPE],
    fileTypeDetectors = [MezzeFileDetector::class],
    interactive = true,
    internal = false
)
class MezzeLanguage : TruffleLanguage<MezzeContext>() {

    companion object {
        const val ID = "mezze-v1"
        const val MIME_TYPE = "application/x-mezze"

        /**
         * Flag to enable interpreterv2's optimized execution.
         * Set via system property: -Dmezze.use.v2=true
         * Default: false (use v1 for backward compatibility)
         */
        @JvmStatic
        val USE_V2: Boolean = System.getProperty("mezze.use.v2", "false").toBoolean()

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

        @JvmStatic
        fun storeLastExecutionContext(context: MezzeContext) {
            lastExecutionContextStore = context
        }

        @JvmStatic
        fun storeLastExecutionResult(result: MezzeValue) {
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
                // NOTE: USE_V2 integration is disabled for now.
                // V2 converter requires its own MezzeContext and runtime, not just an adapter.
                // To enable V2: implement proper V2 language context initialization.
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
            // NOTE: USE_V2 integration is disabled for now.
            // V2 converter requires its own MezzeContext and runtime, not just an adapter.
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

// NOTE: MezzeTruffleRootNode has been moved to Java for JIT compatibility.
// See: com.mezze.language.interpreter.MezzeTruffleRootNode (Java)
// Module loading logic is in MezzeTruffleRootNodeHelper.kt

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
