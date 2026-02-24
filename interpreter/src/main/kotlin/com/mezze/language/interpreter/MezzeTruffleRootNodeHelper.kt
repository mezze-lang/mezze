package com.mezze.language.interpreter

import com.mezze.language.ast.*
import com.mezze.language.interpreter.nodes.ASTToTruffleConverter
import com.mezze.language.typechecker.TypeChecker
import java.nio.file.Path

/**
 * Helper object for MezzeTruffleRootNode to handle module loading.
 * This is in Kotlin because module loading uses complex Kotlin APIs and
 * doesn't need to be JIT-compiled.
 *
 * All methods are marked @TruffleBoundary in the Java caller.
 */
object MezzeTruffleRootNodeHelper {

    /**
     * Load a module and all its dependencies recursively
     */
    fun loadModuleRecursively(
        modulePath: String,
        context: MezzeContext,
        loadedModules: MutableSet<String>,
        projectRoot: Path
    ) {
        // Skip if already loaded
        if (modulePath in loadedModules) {
            return
        }

        try {
            // Load the module using ModuleResolver
            val moduleFile = resolveModulePath(modulePath, projectRoot)

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
                loadModuleRecursively(dependency, context, loadedModules, projectRoot)
            }

            // Now load this module
            // Convert module to Truffle nodes
            val moduleConverter = ASTToTruffleConverter(context, projectRoot)
            val moduleNode = moduleConverter.convertProgram(typedModule)
            val moduleFrameDescriptor = moduleConverter.getFrameDescriptor()

            // Execute the module to register its functions
            // Create a frame with 2-element arguments array per Truffle convention
            val args = arrayOf<Any?>(MezzeRecord(emptyMap()), emptyMap<String, MezzeValue>())
            val moduleFrame = com.oracle.truffle.api.Truffle.getRuntime()
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
            val publicPerformers = typedModule.items.filterIsInstance<ImplDeclaration>()
                .map { it.performerName }
                .toSet()

            val allExports = publicExports + publicPerformers

            // Register public exports with BOTH full qualified names AND short qualified names
            val shortPrefix = modulePath.split("::").last()
            for (exportName in allExports) {
                val exportValue = moduleExports[exportName]
                if (exportValue != null) {
                    // Register with full qualified name
                    val fullQualifiedName = "$modulePath::$exportName"
                    context.defineGlobal(fullQualifiedName, exportValue)

                    // Register with short qualified name
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
     */
    private fun resolveModulePath(modulePath: String, projectRoot: Path): java.io.File {
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
            if (parts.isNotEmpty()) {
                val packageName = parts[0]
                val restOfPath = if (parts.size > 1) parts.drop(1).joinToString("/") + ".mz" else "$packageName.mz"
                val depsDir = actualProjectRoot.resolve(".deps/mezze")

                if (java.nio.file.Files.exists(depsDir) && java.nio.file.Files.isDirectory(depsDir)) {
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
    private fun findUserProjectRoot(startPath: Path): Path {
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
}
