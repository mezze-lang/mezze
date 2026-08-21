package com.mezze.language.typechecker

import com.mezze.language.ast.*
import com.mezze.language.ast.debug.ContentHashRegistry
import com.mezze.language.ast.debug.ContentHashRegistry.DeclarationKind
import com.mezze.language.ast.hashing.MezzeHash
import com.mezze.language.ast.hashing.MezzeHashBuilder
import com.mezze.language.parser.MezzeParser
import com.mezze.language.parser.ParsedModuleCacheHolder
import com.mezze.language.parser.ParseException
import com.mezze.language.parsedast.*
import com.mezze.language.parsedast.Visibility as ParsedVisibility
import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.parsedast.diagnostics.FixHint
import com.mezze.language.parsedast.diagnostics.LevenshteinSuggest
import com.mezze.language.store.MezzeStore
import com.mezze.language.store.StoreSerialization
import com.mezze.language.typedast.Hash
import com.mezze.language.typechecker.debug.DebugLogger
import com.mezze.language.typechecker.debug.DebugLogger.Category
import com.mezze.language.typechecker.hashing.ContentHashComputer
import com.mezze.language.typechecker.util.StringDistance
import com.mezze.language.typechecker.util.TypeAnnotationConverter
import com.mezze.language.typechecker.util.parsedUsePathToCore
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

data class ProgramIndex(
    val typeDeclarations: Map<String, ParsedTypeDecl>,
    val abilityDeclarations: Map<String, ParsedAbilityDecl>,
    val effectDeclarations: Map<String, ParsedEffectDecl>,
    val performersByName: Map<String, ParsedPerformerDecl>,
    val performersByEffect: Map<String, List<ParsedPerformerDecl>>,
    val abilityImplsByAbility: Map<String, List<ParsedImplAbilityDecl>>,
    val abilityImplsByHash: Map<Hash, ParsedImplAbilityDecl>,
    val letBindings: Map<String, ParsedLetDecl>,
    val useDeclarations: List<ParsedUseDecl>,
    val pubUseDeclarations: List<ParsedPubUseDecl>
) {
    companion object {
        fun build(module: ParsedModule): ProgramIndex {
            val decls = module.decls

            val typeDecls = decls.filterIsInstance<ParsedTypeDecl>()
                .associateBy { it.name }
            val abilityDecls = decls.filterIsInstance<ParsedAbilityDecl>()
                .associateBy { it.name }
            val effectDecls = decls.filterIsInstance<ParsedEffectDecl>()
                .associateBy { it.name }

            val performerDecls = decls.filterIsInstance<ParsedPerformerDecl>()
            val abilityImplDecls = decls.filterIsInstance<ParsedImplAbilityDecl>()
            val letDecls = decls.filterIsInstance<ParsedLetDecl>()

            val hasher = ParsedDeclHasher
            val file = module.path

            val hashedEffects = effectDecls.mapValues { (_, decl) ->
                if (decl.contentHash != null) decl
                else {
                    val hash = hasher.hashEffectDecl(decl, file)
                    if (hash != null) decl.copy(contentHash = hash) else decl
                }
            }

            val hashedPerformers = performerDecls.map { perf ->
                if (perf.contentHash != null) perf
                else {
                    val hash = hasher.hashPerformerDecl(perf, file)
                    if (hash != null) perf.copy(contentHash = hash) else perf
                }
            }

            val hashedAbilityImpls = abilityImplDecls.map { impl ->
                if (impl.contentHash != null) impl
                else {
                    val hash = hasher.hashImplAbilityDecl(impl, file)
                    if (hash != null) impl.copy(contentHash = hash) else impl
                }
            }

            return ProgramIndex(
                typeDeclarations = typeDecls,
                abilityDeclarations = abilityDecls,
                effectDeclarations = hashedEffects,
                performersByName = hashedPerformers.associateBy { it.performerName },
                performersByEffect = hashedPerformers.groupBy { it.effect.localName },
                abilityImplsByAbility = hashedAbilityImpls
                    .filter { !it.isInherent }
                    .groupBy { it.abilityName!! },
                abilityImplsByHash = hashedAbilityImpls.associateBy { impl ->
                    impl.contentHash!!
                },
                letBindings = letDecls
                    .filter { it.isSimpleBinding }
                    .associateBy { it.variableName },
                useDeclarations = decls.filterIsInstance<ParsedUseDecl>(),
                pubUseDeclarations = decls.filterIsInstance<ParsedPubUseDecl>()
            )
        }

                val EMPTY = ProgramIndex(
            typeDeclarations = emptyMap(),
            abilityDeclarations = emptyMap(),
            effectDeclarations = emptyMap(),
            performersByName = emptyMap(),
            performersByEffect = emptyMap(),
            abilityImplsByAbility = emptyMap(),
            abilityImplsByHash = emptyMap(),
            letBindings = emptyMap(),
            useDeclarations = emptyList(),
            pubUseDeclarations = emptyList()
        )
    }
}

data class Module(
    val path: ModulePath,
    val filePath: String,
    val exports: Map<String, ExportedItem>,
    val dependencies: List<ModuleDependency>,
    val sourceHash: Hash? = null,
    val index: ProgramIndex = ProgramIndex.EMPTY,
    val parsedModule: ParsedModule? = null
) {
    val name: String get() = path.fullPath
}

data class ExportedItem(
    val name: String,
    val type: ExportedItemType,
    val mezzeType: MezzeType,
    val originalName: String,
    val visibility: ParsedVisibility = ParsedVisibility.PUBLIC,
    val contentHash: Hash  // Content hash for content-addressed lookup (REQUIRED)
)

enum class ExportedItemType {
    FUNCTION,
    TYPE,
    CONSTRUCTOR,
    ABILITY,
    EFFECT
}

data class ModuleDependency(
    val modulePath: String,
    val usePath: UsePath,
    val alias: String? = null
)

sealed class ModuleError(message: String) : Exception(message) {
    open val code: ErrorCode =
        ErrorCode.UNCATEGORIZED

    open val errorLoc: Loc? = null

    open val fixHints: List<FixHint> = emptyList()

    data class ModuleNotFound(
        val modulePath: String,
        val searchPaths: List<String>,
        val suggestion: String? = null,
    ) :
        ModuleError(
            "Module not found: $modulePath." +
                (if (suggestion != null) " Did you mean '$suggestion'?" else "") +
                " Searched in: ${searchPaths.joinToString(", ")}"
        ) {
        override val code = ErrorCode.M0801_MODULE_NOT_FOUND
    }

    data class CircularDependency(val cycle: List<String>) :
        ModuleError("Circular dependency detected: ${cycle.joinToString(" -> ")}") {
        override val code = ErrorCode.M0802_CIRCULAR_DEPENDENCY
    }

    data class ParseError(val modulePath: String, val error: String) :
        ModuleError("Failed to parse module $modulePath: $error") {
        override val code = ErrorCode.M0804_MODULE_HAS_ERRORS
    }

    data class InternalModuleAccess(
        val fromModule: String,
        val toModule: String,
        val loc: Loc? = null
    ) : ModuleError(
        if (fromModule.isEmpty())
            "Cannot access internal module '$toModule' from outside its package"
        else
            "Module '$fromModule' cannot access internal module '$toModule'"
    ) {
        override val code = ErrorCode.M0803_NOT_EXPORTED
        override val errorLoc get() = loc
    }

    data class ExportNotFound(
        val modulePath: String,
        val exportName: String,
        val loc: Loc? = null,
        val availableExports: List<String> = emptyList()
    ) : ModuleError(buildString {
        append("Export '$exportName' not found in module '$modulePath'")
        if (availableExports.isNotEmpty()) {
            append(". ")
            append(StringDistance.suggestOrList(exportName, availableExports, "exports"))
        }
    }) {
        override val code = ErrorCode.M0803_NOT_EXPORTED
        override val errorLoc get() = loc
        // Did-you-mean: emit a Rename FixHint when an export close to
        // `exportName` exists — turns the typo into a one-click fix.
        override val fixHints
            get(): List<FixHint> {
                if (loc == null || availableExports.isEmpty()) return emptyList()
                val close = LevenshteinSuggest.closestN(exportName, availableExports, 3)
                return if (close.isNotEmpty())
                    listOf(FixHint.Rename(loc, close))
                else emptyList()
            }
    }

        data class PrivateExport(
        val modulePath: String,
        val exportName: String,
        val loc: Loc? = null
    ) : ModuleError("'$exportName' exists in module '$modulePath' but is not public. Add 'pub' to export it.") {
        override val code = ErrorCode.M0803_NOT_EXPORTED
        override val errorLoc get() = loc
        // Surface an AddPub fix so the lightbulb can offer one-click cross-
        // module visibility change once we have a real cross-file edit.
        override val fixHints
            get(): List<FixHint> =
                if (loc != null)
                    listOf(FixHint.AddPub(loc, modulePath, exportName))
                else emptyList()
    }

    data class DeprecatedCoreModule(val modulePath: String, val suggestion: String) :
        ModuleError("Deprecated core module: $modulePath. $suggestion") {
        override val code = ErrorCode.M0801_MODULE_NOT_FOUND
    }
}

private fun findProjectRoot(startPath: Path = Paths.get(".")): Path {
    var current = startPath.toAbsolutePath()

    DebugLogger.debug(Category.ModuleResolver) { "Finding project root, starting from: $current" }

    for (i in 0..10) {
        val mezzeToml = current.resolve("Mezze.toml")
        if (Files.exists(mezzeToml) && Files.isRegularFile(mezzeToml)) {
            DebugLogger.debug(Category.ModuleResolver) { "Found project root (Mezze.toml): $current" }
            return current
        }

        val parent = current.parent
        if (parent == null || parent == current) break
        current = parent
    }

    DebugLogger.debug(Category.ModuleResolver) { "No Mezze.toml found, using fallback project root: ${startPath.toAbsolutePath()}" }
    return startPath.toAbsolutePath()
}

class ModuleResolver(
    currentPath: Path = Paths.get(".")
) {
    private val projectRoot: Path = findProjectRoot(currentPath)

    companion object {
        private val moduleCache = mutableMapOf<String, Module>()
        private val contentHashCache = mutableMapOf<Hash, Module>()
        private val cacheLock = Any()
        private val moduleLoadingLocks = mutableMapOf<String, Any>()

                @Volatile
        private var store: MezzeStore? = null

        @JvmStatic
        fun setStore(store: MezzeStore) {
            this.store = store
        }

                private fun tryLoadParsedFromStore(modulePath: String, sourceHash: Hash): ParsedModule? {
            val s = store ?: return null
            return try {
                // SQLite is the wire boundary — unwrap Hash to hex String here.
                val blob = s.getParsedModule(modulePath, sourceHash.hex) ?: return null
                StoreSerialization.deserializeParsedModule(blob)
            } catch (e: Exception) {
                // Cache hit but blob can't be deserialized — almost always
                // serialized-shape mismatch (older cache from a different
                // build). Surface under MEZZE_DEBUG; falling through to a
                // fresh parse is the right runtime behavior. (#308)
                if (System.getenv("MEZZE_DEBUG") != null) {
                    System.err.println(
                        "[ModuleResolver] failed to deserialize parsed module $modulePath@${sourceHash.hex.take(8)}: " +
                        "${e.javaClass.simpleName}: ${e.message}. Re-parsing."
                    )
                }
                null
            }
        }

                @JvmStatic
        fun readStdlibFile(path: String): String? {
            return try {
                val resourceStream = ModuleResolver::class.java.classLoader.getResourceAsStream(path)
                resourceStream?.bufferedReader()?.use { it.readText() }
            } catch (e: Exception) {
                // IOException reading from JAR resource — extremely rare, but
                // worth noting. Caller has fallback paths so don't throw. (#308)
                if (System.getenv("MEZZE_DEBUG") != null) {
                    System.err.println(
                        "[ModuleResolver] readStdlibFile($path) failed: " +
                        "${e.javaClass.simpleName}: ${e.message}"
                    )
                }
                null
            }
        }

                @JvmStatic
        fun clearCache() {
            synchronized(cacheLock) {
                moduleCache.clear()
                contentHashCache.clear()
                moduleLoadingLocks.clear()
            }
        }

                @JvmStatic
        fun invalidateModulePaths(modulePaths: Collection<String>) {
            synchronized(cacheLock) {
                for (path in modulePaths) {
                    val mod = moduleCache.remove(path)
                    mod?.sourceHash?.let { contentHashCache.remove(it) }
                    moduleLoadingLocks.remove(path)
                }
            }
        }

                private fun getModuleLock(modulePath: String): Any {
            synchronized(cacheLock) {
                return moduleLoadingLocks.getOrPut(modulePath) { Any() }
            }
        }

                internal fun getFromCache(modulePath: String): Module? {
            synchronized(cacheLock) {
                return moduleCache[modulePath]
            }
        }

                @JvmStatic
        fun snapshot(): Map<String, Module> = synchronized(cacheLock) { moduleCache.toMap() }

                internal fun getFromCacheByContentHash(contentHash: Hash): Module? {
            synchronized(cacheLock) {
                return contentHashCache[contentHash]
            }
        }

                internal fun putInCache(modulePath: String, module: Module) {
            synchronized(cacheLock) {
                moduleCache[modulePath] = module
                module.sourceHash?.let { hash ->
                    contentHashCache[hash] = module
                }
            }
        }

                        @JvmStatic
        fun getAllParsedModules(): Map<String, ParsedModule> {
            synchronized(cacheLock) {
                return moduleCache.mapNotNull { (key, module) ->
                    module.parsedModule?.let { key to it }
                }.toMap()
            }
        }

                @JvmStatic
        fun isModuleStale(modulePath: String, sourceContent: String): Boolean {
            val cachedModule = getFromCache(modulePath) ?: return true  // Not cached = stale
            val cachedHash = cachedModule.sourceHash ?: return true     // No hash = stale
            val currentHash = Hash(
                MezzeHashBuilder().append(sourceContent).build().toString()
            )
            return cachedHash != currentHash
        }

                        @JvmStatic
        fun lookupTypeDeclaration(typeName: String): ParsedTypeDecl? {
            synchronized(cacheLock) {
                for ((_, module) in moduleCache) {
                    val typeDecl = module.index.typeDeclarations[typeName]
                    if (typeDecl != null) {
                        return typeDecl
                    }
                }
                return null
            }
        }

                @JvmStatic
        fun create(): ModuleResolver {
            return ModuleResolver()
        }
    }

    // Track modules currently being loaded to detect circular dependencies (per-instance to track call stack)
    private val loadingStack = mutableSetOf<String>()
    
    // Only skip modules where ALL functionality is built-in and there's no stdlib wrapper.
    // Collection modules with stdlib wrappers (map.mz, set.mz) are NOT skipped.
    private val coreTypeModules = setOf(
        "std::core::primitives",
        "std::core::abilities",
        "std::core::operators",
        "std::core::option",
        "std::core::result",
        "std::types::int",
        "std::types::float",
        "std::types::string",
        "std::types::boolean",
        "std::types::nat",
        "std::types::frac"
    )
    
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
            if (System.getProperty("mezze.migration.warnings", "true") == "true") {
            }
            
            // In strict migration mode, throw an error
            if (System.getProperty("mezze.migration.strict", "false") == "true") {
                throw ModuleError.DeprecatedCoreModule(modulePath, suggestion)
            }
        }
    }

        fun resolveModule(path: ModulePath, fromModule: ModulePath? = null): Module {
        return resolveModuleInternal(path.fullPath, fromModule?.fullPath)
    }

        fun resolveModule(modulePath: String, fromModule: String? = null): Module {
        return resolveModuleInternal(modulePath, fromModule)
    }

        private fun resolveModuleInternal(modulePath: String, fromModule: String? = null): Module {
        if (modulePath in coreTypeModules) {
            return createEmptyModule(modulePath, "Core module skipped - types are now built-in")
        }

        val moduleLock = getModuleLock(modulePath)
        synchronized(moduleLock) {
            checkForDeprecatedCoreModule(modulePath)

            if (modulePath in loadingStack) {
                throw ModuleError.CircularDependency(loadingStack.toList() + modulePath)
            }

            val filePath = resolveModuleFile(modulePath)
                ?: throw ModuleError.ModuleNotFound(
                    modulePath,
                    getSearchPaths(modulePath),
                    suggestion = suggestModulePath(modulePath),
                )

            val source = Files.readString(filePath)
            val sourceHash = Hash(
                MezzeHashBuilder().append(source).build().toString()
            )

            getFromCacheByContentHash(sourceHash)?.let { cachedModule ->
                DebugLogger.debug(Category.ModuleResolver) {
                    "Found module with same content hash at different path: $modulePath (reusing ${cachedModule.name})"
                }
                putInCache(modulePath, cachedModule)
                return cachedModule
            }

            getFromCache(modulePath)?.let { cachedModule ->
                if (cachedModule.sourceHash == sourceHash) {
                    DebugLogger.debug(Category.ModuleResolver) {
                        "Found cached module with matching content hash: $modulePath"
                    }
                    return cachedModule
                } else {
                    DebugLogger.debug(Category.ModuleResolver) {
                        "Cached module has stale content hash, reloading: $modulePath"
                    }
                }
            }

            loadingStack.add(modulePath)

            try {
                val cached = tryLoadParsedFromStore(modulePath, sourceHash)
                    ?: ParsedModuleCacheHolder.get(filePath.toString(), sourceHash.hex)
                val parsedModule = cached ?: run {
                    val parser = MezzeParser()
                    parser.parseParsedModule(source, file = filePath.toString())
                }

                val parsedExports = extractExportsFromParsedModule(parsedModule, modulePath)
                val dependencies = extractDependenciesFromParsedModule(parsedModule)
                val programIndex = ProgramIndex.build(parsedModule)
                val structuredPath = ModulePath.parse(modulePath)
                val module = Module(
                    path = structuredPath,
                    filePath = filePath.toString(),
                    exports = parsedExports,
                    dependencies = dependencies,
                    sourceHash = sourceHash,
                    index = programIndex,
                    parsedModule = parsedModule
                )

                putInCache(modulePath, module)
                return module

            } catch (e: ParseException) {
                throw ModuleError.ParseError(modulePath, e.message ?: "Unknown parse error")
            } catch (e: Exception) {
                throw ModuleError.ParseError(modulePath, "Unexpected error: ${e.message}")
            } finally {
                loadingStack.remove(modulePath)
            }
        }
    }
    
        private fun createEmptyModule(modulePath: String, reason: String): Module {
        return Module(
            path = ModulePath.parse(modulePath),
            filePath = "built-in",
            exports = emptyMap(),
            dependencies = emptyList(),
            sourceHash = null,
            index = ProgramIndex.EMPTY
        )
    }
    
        private fun loadStdlibFromResource(relativeFilePath: String): String? {
        return try {
            val resourceStream = javaClass.classLoader.getResourceAsStream(relativeFilePath)
            resourceStream?.bufferedReader()?.use { it.readText() }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Write stdlib source to a STABLE temp-file path (derived from the
     * resource path, not random). The rest of the pipeline reads source
     * via `Files.readString(filePath)` and FunctionIR carries the path
     * as metadata — using `Files.createTempFile("mezze-stdlib-", ".mz")`
     * embeds a fresh random suffix into the IR every compile, which
     * blocks any content-addressed caching downstream (#224).
     *
     * Layout: `<tmpdir>/mezze-stdlib-cache/<flattened-resource-path>`.
     * Same resource → same path on every compile, every JVM, every host.
     * Idempotent: skip write if file already exists with matching bytes.
     */
    private fun materializeStdlibTempFile(relativeFilePath: String, content: String): Path {
        val flat = relativeFilePath.replace('/', '-').replace('\\', '-')
        val cacheDir = Paths.get(System.getProperty("java.io.tmpdir"), "mezze-stdlib-cache")
        Files.createDirectories(cacheDir)
        val target = cacheDir.resolve(flat)
        // Only write if missing or stale. Avoids racing concurrent JVMs
        // and keeps the file mtime stable across runs.
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (!Files.exists(target) || Files.size(target) != bytes.size.toLong() ||
            !Files.readAllBytes(target).contentEquals(bytes)) {
            Files.write(target, bytes)
        }
        return target
    }

        private fun resolveModuleFile(modulePath: String): Path? {
        return resolveModuleFile(ModulePath.parse(modulePath))
    }

    /** The stdlib grouping directories a leaf module may live under. Probed when a
     *  bare `std::<leaf>` path fails, so `std::list` suggests `std::collections::list`. */
    private val stdlibCategories = listOf("collections", "effects", "abilities", "codec", "math")

    /**
     * Best-effort "did you mean" for an unresolved module path. Grounded — every
     * candidate is verified with [resolveModuleFile], so we never suggest a path
     * that doesn't exist. Handles the common mistake of omitting the grouping
     * directory (`std::list` → `std::collections::list`).
     */
    private fun suggestModulePath(modulePath: String): String? {
        val segments = ModulePath.parse(modulePath).segments
        // Only bare `std::<leaf>` (exactly two segments) is a category-omission
        // candidate; deeper paths are the user's own layout, not stdlib grouping.
        if (segments.size == 2 && segments[0] == "std") {
            val leaf = segments[1]
            for (category in stdlibCategories) {
                val candidate = "std::$category::$leaf"
                if (resolveModuleFile(candidate) != null) return candidate
            }
        }
        return null
    }

        private fun resolveModuleFile(path: ModulePath): Path? {
        val segments = path.segments

        if (segments.isNotEmpty() && segments[0] == "std") {
            val stdSegments = segments.drop(1)
            val relativeFilePath = "stdlib/std/" + stdSegments.joinToString("/") + ".mz"

            val resourceContent = loadStdlibFromResource(relativeFilePath)
            if (resourceContent != null) {
                return materializeStdlibTempFile(relativeFilePath, resourceContent)
            }

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

        if (segments.size == 1) {
            val relativeFilePath = "stdlib/std/${segments[0]}.mz"
            val resourceContent = loadStdlibFromResource(relativeFilePath)
            if (resourceContent != null) {
                return materializeStdlibTempFile(relativeFilePath, resourceContent)
            }

            val flatStdlibPath = projectRoot.resolve(relativeFilePath)
            DebugLogger.debug(Category.ModuleResolver) { "Checking flat stdlib path: $flatStdlibPath (exists: ${Files.exists(flatStdlibPath)})" }
            if (Files.exists(flatStdlibPath) && Files.isRegularFile(flatStdlibPath)) {
                return flatStdlibPath
            }
        }
        
        val localSearchRoots = listOf(
            projectRoot.resolve("src"),
            projectRoot.resolve("lib"),
            projectRoot
        )

        for (root in localSearchRoots) {
            val filePath = root.resolve(segments.joinToString("/") + ".mz")
            if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
                return filePath
            }
        }

        if (segments.isNotEmpty()) {
            val packageName = segments[0]
            val restOfPath = if (segments.size > 1) segments.drop(1).joinToString("/") + ".mz" else "$packageName.mz"
            val depsDir = projectRoot.resolve(".deps/mezze")

            if (Files.exists(depsDir) && Files.isDirectory(depsDir)) {
                // Find directories matching {packageName}-* (e.g., json-1.2.0)
                Files.list(depsDir).use { stream ->
                    val matchingDirs = stream
                        .filter { Files.isDirectory(it) }
                        .filter { it.fileName.toString().startsWith("$packageName-") }
                        .toList()

                    for (depDir in matchingDirs) {
                        val searchPaths = listOf(
                            depDir.resolve("src").resolve(restOfPath),
                            depDir.resolve(restOfPath)
                        )

                        for (path in searchPaths) {
                            if (Files.exists(path) && Files.isRegularFile(path)) {
                                return path
                            }
                        }
                    }
                }
            }
        }

        return null
    }
    
        private fun getSearchPaths(modulePath: String): List<String> {
        return getSearchPaths(ModulePath.parse(modulePath))
    }

        private fun getSearchPaths(path: ModulePath): List<String> {
        val segments = path.segments
        
        if (segments.isNotEmpty() && segments[0] == "std") {
            val stdSegments = segments.drop(1)
            return listOf(
                "stdlib/std/${stdSegments.joinToString("/")}.mz"
            )
        }
        
        if (segments.size == 1) {
            return listOf(
                "stdlib/${segments[0]}.mz",
                "src/${segments.joinToString("/")}.mz",
                "lib/${segments.joinToString("/")}.mz",
                "${segments.joinToString("/")}.mz"
            )
        }
        
        val packageName = segments[0]
        return listOf(
            "src/${segments.joinToString("/")}.mz",
            "lib/${segments.joinToString("/")}.mz",
            "${segments.joinToString("/")}.mz",
            ".deps/mezze/$packageName-*/src/...",
            ".deps/mezze/$packageName-*/..."
        )
    }
    
    private fun extractExportsFromParsedModule(
        module: ParsedModule,
        modulePath: String? = null,
        typeContext: Map<String, MezzeType>? = null
    ): Map<String, ExportedItem> {
        val exports = mutableMapOf<String, ExportedItem>()
        val file = module.path

        for (decl in module.decls) {
            when (decl) {

                is ParsedLetDecl -> {
                    if (decl.visibility != Visibility.PUBLIC) continue
                    if (!decl.isSimpleBinding) continue
                    val name = decl.variableName

                    val fromContext = typeContext?.get(name)
                    val fromAnnotation = decl.typeAnnotation
                        ?.let { TypeAnnotationConverter.convert(it, faithful = false) }
                    // #213: the solver-inferred `fromContext` type does NOT carry the
                    // written `with Ord 'a` ability constraints, so exporting it drops
                    // them — importers then see a constraint-free scheme and accept
                    // `SortedSet Color` (no `Ord Color` obligation) → runtime crash.
                    // When the annotation declares ability constraints, that annotation
                    // is the authoritative signature (its type-vars are self-consistent),
                    // so export the annotation-derived type instead of the inferred one.
                    val annotationHasAbilityConstraints =
                        (fromAnnotation as? MezzeType.FunctionType)
                            ?.abilityConstraints?.isNotEmpty() == true
                    val mezzeType: MezzeType =
                        if (annotationHasAbilityConstraints) fromAnnotation!!
                        else fromContext
                            ?: fromAnnotation
                            ?: MezzeType.VarType(TypeVar.fresh())
                    val contentHash = ParsedDeclHasher.hashLetDecl(decl)

                    exports[name] = ExportedItem(
                        name = name,
                        type = ExportedItemType.FUNCTION,
                        mezzeType = mezzeType,
                        originalName = name,
                        contentHash = contentHash
                    )
                    val qualifiedName = if (modulePath != null) "$modulePath::$name" else name
                    ContentHashRegistry.register(
                        hash = contentHash,
                        qualifiedName = qualifiedName,
                        modulePath = modulePath,
                        kind = if (mezzeType is MezzeType.FunctionType) DeclarationKind.FUNCTION
                               else DeclarationKind.CONSTANT
                    )
                }

                is ParsedTypeDecl -> {
                    if (decl.visibility != Visibility.PUBLIC) continue
                    val contentHash = ParsedDeclHasher.hashTypeDecl(decl)
                    val fromContext = typeContext?.get(decl.name)
                    val typeMezzeType = fromContext ?: MezzeType.VarType(TypeVar.fresh())

                    exports[decl.name] = ExportedItem(
                        name = decl.name,
                        type = ExportedItemType.TYPE,
                        mezzeType = typeMezzeType,
                        originalName = decl.name,
                        contentHash = contentHash
                    )
                    val qualifiedName = if (modulePath != null) "$modulePath::${decl.name}" else decl.name
                    ContentHashRegistry.register(
                        hash = contentHash,
                        qualifiedName = qualifiedName,
                        modulePath = modulePath,
                        kind = DeclarationKind.TYPE
                    )

                    if (decl.definition is ParsedVariantDef) {
                        for (ctor in (decl.definition as ParsedVariantDef).constructors) {
                            exports[ctor.name] = ExportedItem(
                                name = ctor.name,
                                type = ExportedItemType.CONSTRUCTOR,
                                mezzeType = MezzeType.VarType(TypeVar.fresh()),
                                originalName = ctor.name,
                                contentHash = contentHash
                            )
                        }
                    }
                }

                is ParsedEffectDecl -> {
                    val contentHash = ParsedDeclHasher.hashEffectDecl(decl)
                    val fromContext = typeContext?.get(decl.name)
                    val mezzeType = fromContext ?: MezzeType.VarType(TypeVar.fresh())

                    exports[decl.name] = ExportedItem(
                        name = decl.name,
                        type = ExportedItemType.EFFECT,
                        mezzeType = mezzeType,
                        originalName = decl.name,
                        contentHash = contentHash
                    )
                    val qualifiedName = if (modulePath != null) "$modulePath::${decl.name}" else decl.name
                    ContentHashRegistry.register(
                        hash = contentHash,
                        qualifiedName = qualifiedName,
                        modulePath = modulePath,
                        kind = DeclarationKind.EFFECT
                    )

                    // Effect operations are NOT exported as standalone functions.
                    // They are only callable through their effect: `Environment.process_args {}`.
                    // Importing `process_args` standalone was a footgun — the call
                    // didn't go through ability-method dispatch and crashed at runtime.
                }

                is ParsedAbilityDecl -> {
                    val contentHash = ParsedDeclHasher.hashAbilityDecl(decl)
                    val fromContext = typeContext?.get(decl.name)
                    val abilityMezzeType = fromContext ?: MezzeType.VarType(TypeVar.fresh())

                    exports[decl.name] = ExportedItem(
                        name = decl.name,
                        type = ExportedItemType.ABILITY,
                        mezzeType = abilityMezzeType,
                        originalName = decl.name,
                        contentHash = contentHash
                    )
                    val qualifiedName = if (modulePath != null) "$modulePath::${decl.name}" else decl.name
                    ContentHashRegistry.register(
                        hash = contentHash,
                        qualifiedName = qualifiedName,
                        modulePath = modulePath,
                        kind = DeclarationKind.ABILITY
                    )
                }

                is ParsedPerformerDecl -> {
                    val contentHash = ParsedDeclHasher.hashPerformerDecl(decl)
                    val existingExport = exports[decl.performerName]
                    // Don't overwrite a TYPE export with a HANDLER export
                    if (existingExport?.type != ExportedItemType.TYPE) {
                        val fromContext = typeContext?.get(decl.performerName)
                        val performerMezzeType = fromContext ?: MezzeType.VarType(TypeVar.fresh())

                        exports[decl.performerName] = ExportedItem(
                            name = decl.performerName,
                            type = ExportedItemType.FUNCTION, // handlers are treated as values
                            mezzeType = performerMezzeType,
                            originalName = decl.performerName,
                            contentHash = contentHash
                        )
                        val qualifiedName = if (modulePath != null) "$modulePath::${decl.performerName}" else decl.performerName
                        ContentHashRegistry.register(
                            hash = contentHash,
                            qualifiedName = qualifiedName,
                            modulePath = modulePath,
                            kind = DeclarationKind.EFFECT_HANDLER
                        )
                    }
                }

                // Ability impls imported via ProgramIndex.abilityImplsByHash, not exports map
                is ParsedImplAbilityDecl -> {}

                is ParsedPubUseDecl -> {
                    // TODO: re-export the transitive items from the imported module
                }

                else -> {}
            }
        }

        return exports
    }

    private fun extractDependenciesFromParsedModule(module: ParsedModule): List<ModuleDependency> =
        buildList {
            for (decl in module.decls) {
                val (path, alias) = when (decl) {
                    is ParsedUseDecl -> decl.path to decl.alias
                    is ParsedPubUseDecl -> decl.path to decl.alias
                    else -> continue
                }
                val corePath = parsedUsePathToCore(path)
                add(ModuleDependency(
                    modulePath = pathToString(corePath),
                    usePath = corePath,
                    alias = alias
                ))
            }
        }

    private fun pathToString(path: UsePath): String = when (path) {
        is ModulePath -> path.fullPath
        is SelectivePath -> ModulePath(path.module).fullPath
    }
    
    
        fun canAccessInternal(fromModule: String, toModule: String): Boolean {
        val from = if (fromModule.isEmpty()) null else ModulePath.parse(fromModule)
        val to = ModulePath.parse(toModule)
        return canAccessInternal(from, to)
    }

        fun canAccessInternal(fromModule: ModulePath?, toModule: ModulePath): Boolean {
        val toSegments = toModule.segments
        val internalMarkers = setOf("internal", "private")

        // Find the first internal/private segment in the path
        val markerIndex = toSegments.indexOfFirst { it in internalMarkers }

        // Check if the module path STARTS with internal/ or private/
        // This handles top-level internal modules
        if (toSegments.isNotEmpty() && toSegments[0] in internalMarkers) {
            // Top-level internal modules cannot be accessed from outside
            return false
        }

        if (markerIndex < 0) {
            return true // Not an internal module, access allowed
        }

        // Extract the parent module path (everything before the internal/private marker)
        val parentSegments = toSegments.take(markerIndex)

        // Null fromModule means a top-level script, which CANNOT access internal modules
        if (fromModule == null) {
            return false
        }

        val fromSegments = fromModule.segments

        // Allow access if the fromModule is within the same parent hierarchy
        // e.g., "math/ops" can access "math/internal/helpers"
        // e.g., "math/internal/other" can access "math/internal/helpers"
        return fromSegments.take(parentSegments.size) == parentSegments
    }
    
        fun updateModuleExportsV2(
        modulePath: String,
        parsedModule: ParsedModule,
        typeContext: Map<String, MezzeType>? = null
    ) {
        if (parsedModule.decls.isEmpty()) return
        DebugLogger.debug(Category.ModuleResolver) {
            "updateModuleExportsV2: modulePath=$modulePath, ${parsedModule.decls.size} decls"
        }

        val module = getFromCache(modulePath)

        if (module != null) {
            val updatedExports = if (typeContext != null && typeContext.isNotEmpty()) {
                module.exports.mapValues { (_, export) ->
                    val inferredType = typeContext[export.originalName]
                    if (inferredType != null) export.copy(mezzeType = inferredType) else export
                }
            } else {
                module.exports
            }

            val updatedIndex = ProgramIndex.build(parsedModule)

            val updatedModule = module.copy(
                exports = updatedExports,
                parsedModule = parsedModule,
                index = updatedIndex
            )
            putInCache(modulePath, updatedModule)

            DebugLogger.debug(Category.ModuleResolver) {
                "Cached module (v2, updated types+index) with ${updatedModule.exports.size} exports"
            }
        } else {
            val updatedExports = extractExportsFromParsedModule(parsedModule, modulePath, typeContext)
            val updatedIndex = ProgramIndex.build(parsedModule)

            val newModule = Module(
                path = ModulePath.parse(modulePath),
                filePath = "",
                exports = updatedExports,
                dependencies = emptyList(),
                sourceHash = null,
                index = updatedIndex,
                parsedModule = parsedModule
            )
            putInCache(modulePath, newModule)

            DebugLogger.debug(Category.ModuleResolver) {
                "Cached module (v2, new) with ${newModule.exports.size} exports"
            }
        }
    }

}