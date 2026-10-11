package com.mezze.language.typechecker

import com.mezze.language.typechecker.module.ModuleIdentity

import com.mezze.language.ast.*
import com.mezze.language.ast.debug.ContentHashRegistry
import com.mezze.language.ast.debug.ContentHashRegistry.DeclarationKind
import com.mezze.language.ast.hashing.MezzeHash
import java.security.MessageDigest
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

            // Declarations arrive stamped (DeclIdentity): an index is built only
            // from a module whose names have been resolved.
            return ProgramIndex(
                typeDeclarations = typeDecls,
                abilityDeclarations = abilityDecls,
                effectDeclarations = effectDecls,
                performersByName = performerDecls.associateBy { it.performerName },
                performersByEffect = performerDecls.groupBy { it.effect.localName },
                abilityImplsByAbility = abilityImplDecls
                    .filter { !it.isInherent }
                    .groupBy { it.abilityName!! },
                abilityImplsByHash = abilityImplDecls.associateBy { it.identity },
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
    val parsedModule: ParsedModule? = null,
    /** Fingerprint of every identity this module declares. */
    val identity: Hash? = null,
    /**
     * The modules its identities were computed against — each import, and the
     * prelude — with the fingerprint each had then (null: it would not load).
     * Unchanged source is not enough to reuse a module: a declaration's identity
     * includes what its names resolve to, so a changed dependency changes it.
     */
    val stampedAgainst: Map<String, Hash?> = emptyMap(),
    /**
     * The module as stamped, before the typechecker transforms it — what a
     * restamp and an importer's scope read. [parsedModule] is replaced by the
     * typechecked module once it has been checked.
     */
    val source: ParsedModule? = null,
    /** Modules stamped together with this one (they import each other), with their source hashes. */
    val groupSources: Map<String, Hash> = emptyMap(),
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

/**
 * A dependency's library entry file, from `[lib] path = "…"` in its `Mezze.toml`
 * — so `use <pkg>` maps to the package's public library module rather than
 * requiring the internal file name. Dependency-free line scan (callers in
 * :typechecker and :lsp can't see :cli's MezzeToml). Null if there is no `[lib]`
 * section. Shared by the resolver and the LSP's go-to-definition.
 */
fun depLibEntryFile(depDir: Path): Path? {
    val toml = depDir.resolve("Mezze.toml")
    if (!Files.exists(toml) || !Files.isRegularFile(toml)) return null
    var inLib = false
    for (raw in runCatching { Files.readAllLines(toml) }.getOrDefault(emptyList())) {
        val line = raw.trim()
        if (line.startsWith("[")) { inLib = line == "[lib]"; continue }
        if (inLib) {
            Regex("""^path\s*=\s*"([^"]+)"\s*$""").find(line)?.let {
                return depDir.resolve(it.groupValues[1])
            }
        }
    }
    return null
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
    currentPath: Path = Paths.get("."),
    /**
     * Which view of the codebase this resolver reads and writes.
     *
     * Defaults to [World.DEFAULT] so a caller with no opinion keeps the
     * single-world behaviour the caches had before they were separable.
     *
     * Readable so a collaborator can verify it shares this resolver's world —
     * see ModuleTypeChecker's init block.
     */
    internal val world: World = World.DEFAULT,
) {
    private val projectRoot: Path = findProjectRoot(currentPath)

    companion object {
        /**
         * One world's caches — the `(world, module)` key that replaced a bare path.
         *
         * Separable, not merely thread-safe: the maps were already synchronised, but
         * two views of a codebase that both contain `app::handler` collided on one
         * entry, so a speculative version had nowhere to live.
         */
        private class Caches {
            val modules = mutableMapOf<String, Module>()
            /** Held for a whole stamping pass, which may stamp many modules at once. */
            val stampLock = Any()
            val lock = Any()

            /**
             * Module source this world should read INSTEAD of the file on disk.
             *
             * The overlay that makes a speculative change possible: a world holding
             * `app::handler` here type-checks against that text while the file on
             * disk is untouched, and a world without it reads the file as always.
             * Entries may name a module with NO file yet — a changeset that adds one.
             */
            val sourceOverlay = mutableMapOf<String, String>()
        }

        private val worlds = java.util.concurrent.ConcurrentHashMap<World, Caches>()

        private fun caches(world: World): Caches = worlds.computeIfAbsent(world) { Caches() }

                @Volatile
        private var store: MezzeStore? = null

        @JvmStatic
        fun setStore(store: MezzeStore) {
            this.store = store
        }

        /**
         * SHA-256 hex of source text — MUST match the scheme StorePopulator /
         * StoreParsedModuleCache.sha256Hex use to WRITE the parsed_modules /
         * typed_modules `source_hash` column. Previously this side computed a
         * '#'-prefixed, 16-byte-truncated SHA3-512 (MezzeHash.toString()), so the
         * persisted parse cache could NEVER be read back (every getParsedModule
         * lookup missed → cold starts always re-parsed). #317 sweep. Kept a private
         * mirror rather than importing store-sqlite's copy — typechecker depends on
         * the :store interface, not the :store-sqlite impl.
         */
        private fun sha256Hex(source: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            return md.digest(source.toByteArray()).joinToString("") { "%02x".format(it) }
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
                DebugLogger.debug(DebugLogger.Category.ModuleResolver) {
                    "[ModuleResolver] failed to deserialize parsed module $modulePath@${sourceHash.hex.take(8)}: " +
                    "${e.javaClass.simpleName}: ${e.message}. Re-parsing."
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
                DebugLogger.debug(DebugLogger.Category.ModuleResolver) {
                    "[ModuleResolver] readStdlibFile($path) failed: " +
                    "${e.javaClass.simpleName}: ${e.message}"
                }
                null
            }
        }

                @JvmStatic
        @JvmOverloads
        fun clearCache(world: World = World.DEFAULT) {
            val c = caches(world)
            synchronized(c.lock) {
                c.modules.clear()
            }
        }

        /**
         * Make [world] read [source] for [modulePath] instead of the file on disk.
         *
         * Invalidates any cached module for that path in this world, because the
         * cache was built from different text. The default world accepts an overlay
         * too — nothing prevents it — but a caller wanting a change it can throw
         * away should use `World.fresh(...)`, which is the whole point.
         */
        @JvmStatic
        fun overlaySource(world: World, modulePath: String, source: String) {
            val c = caches(world)
            synchronized(c.lock) {
                c.sourceOverlay[modulePath] = source
                c.modules.remove(modulePath)
            }
        }

        /** What [world] reads for [modulePath], or null to read the file. */
        private fun overlayFor(world: World, modulePath: String): String? {
            val c = caches(world)
            synchronized(c.lock) { return c.sourceOverlay[modulePath] }
        }

        /** Remove every overlay in [world], so it reads the files again. */
        @JvmStatic
        fun clearOverlay(world: World) {
            val c = caches(world)
            synchronized(c.lock) {
                for (path in c.sourceOverlay.keys) {
                    c.modules.remove(path)
                }
                c.sourceOverlay.clear()
            }
        }

        /** The module paths [world] currently overlays. */
        @JvmStatic
        fun overlaidPaths(world: World): Set<String> {
            val c = caches(world)
            synchronized(c.lock) { return c.sourceOverlay.keys.toSet() }
        }

        /** Drop [world]'s caches entirely, so nothing is retained for it. */
        @JvmStatic
        fun forgetWorld(world: World) {
            require(world != World.DEFAULT) {
                "the default world cannot be forgotten — use clearCache() to empty it"
            }
            worlds.remove(world)
        }

                @JvmStatic
        @JvmOverloads
        fun invalidateModulePaths(modulePaths: Collection<String>, world: World = World.DEFAULT) {
            val c = caches(world)
            synchronized(c.lock) {
                for (path in modulePaths) {
                    c.modules.remove(path)
                }
            }
        }

                private fun stampLock(world: World): Any = caches(world).stampLock

                internal fun getFromCache(world: World, modulePath: String): Module? {
            val c = caches(world)
            synchronized(c.lock) { return c.modules[modulePath] }
        }

                @JvmStatic
        @JvmOverloads
        fun snapshot(world: World = World.DEFAULT): Map<String, Module> {
            val c = caches(world)
            return synchronized(c.lock) { c.modules.toMap() }
        }

                internal fun putInCache(world: World, modulePath: String, module: Module) {
            val c = caches(world)
            synchronized(c.lock) {
                c.modules[modulePath] = module
            }
        }

                        @JvmStatic
        @JvmOverloads
        fun getAllParsedModules(world: World = World.DEFAULT): Map<String, ParsedModule> {
            val c = caches(world)
            synchronized(c.lock) {
                return c.modules.mapNotNull { (key, module) ->
                    module.parsedModule?.let { key to it }
                }.toMap()
            }
        }

                @JvmStatic
        @JvmOverloads
        fun isModuleStale(modulePath: String, sourceContent: String, world: World = World.DEFAULT): Boolean {
            val cachedModule = getFromCache(world, modulePath) ?: return true  // Not cached = stale
            val cachedHash = cachedModule.sourceHash ?: return true     // No hash = stale
            val currentHash = Hash(sha256Hex(sourceContent))
            return cachedHash != currentHash
        }

                        @JvmStatic
        @JvmOverloads
        fun lookupTypeDeclaration(typeName: String, world: World = World.DEFAULT): ParsedTypeDecl? {
            val c = caches(world)
            synchronized(c.lock) {
                for ((_, module) in c.modules) {
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

        /** The modules every non-stdlib module sees implicitly, from `_prelude.mz`. */
        private val PRELUDE_MODULES: List<List<String>> by lazy {
            val source = readStdlibFile("stdlib/std/_prelude.mz") ?: readStdlibFile("std/_prelude.mz")
                ?: error("Compiler internal error: stdlib/std/_prelude.mz not found on the classpath")
            MezzeParser().parseParsedModule(source, file = "<prelude>").decls
                .filterIsInstance<ParsedUseDecl>()
                .map { ModuleIdentity.segments(it.path) }
        }
    }

    
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
            // Reviewed in #380 and intentionally retained: `mezze.migration.strict`
            // still gates a live behaviour — turning an import of a now-built-in core
            // module into a hard DeprecatedCoreModule error (off by default; warn-only
            // otherwise). The dead empty `mezze.migration.warnings` branch was removed.
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
        checkForDeprecatedCoreModule(modulePath)

        // One stamping pass at a time per world: a pass stamps whole groups of
        // modules, so per-module locks could not keep two passes apart.
        synchronized(stampLock(world)) {
            readSource(modulePath) ?: throw ModuleError.ModuleNotFound(
                modulePath,
                getSearchPaths(modulePath),
                suggestion = suggestModulePath(modulePath),
            )
            getFromCache(world, modulePath)?.let { cached ->
                if (isCurrent(modulePath, HashMap())) return cached
                DebugLogger.debug(Category.ModuleResolver) {
                    "$modulePath or something it was stamped against changed; re-stamping"
                }
            }
            stampFrom(modulePath)
            return getFromCache(world, modulePath)
                ?: error("internal: stamping $modulePath did not cache it")
        }
    }

    /** A module's text, wherever it comes from. */
    private class Source(val modulePath: String, val filePath: String, val text: String, val hash: Hash)

    /** Read [modulePath] without loading anything, or null when there is nothing to read. */
    private fun readSource(modulePath: String): Source? {
        // The overlay is consulted BEFORE the file is resolved, not after it is
        // read: a changeset that ADDS a module has no file to find.
        val overlaid = overlayFor(world, modulePath)
        val resolvedFile = resolveModuleFile(modulePath)
        if (resolvedFile == null && overlaid == null) return null
        // An overlaid module with no file still needs a path: it names where the
        // module WOULD live, so diagnostics and FunctionIR.sourceFile point
        // somewhere a reader recognises rather than at a synthetic blank.
        val filePath = resolvedFile
            ?: Paths.get(projectRoot.toString(), "src", modulePath.replace("::", "/") + ".mz")
        val text = overlaid ?: Files.readString(filePath)
        return Source(modulePath, filePath.toString(), text, Hash(sha256Hex(text)))
    }

    private fun parse(src: Source): ParsedModule = try {
        tryLoadParsedFromStore(src.modulePath, src.hash)
            ?: ParsedModuleCacheHolder.get(src.filePath, src.hash.hex)
            ?: MezzeParser().parseParsedModule(src.text, file = src.filePath)
    } catch (e: ParseException) {
        throw ModuleError.ParseError(src.modulePath, e.message ?: "Unknown parse error")
    } catch (e: Exception) {
        throw ModuleError.ParseError(src.modulePath, "Unexpected error: ${e.message}")
    }

    /**
     * Whether the cached [modulePath] still has the identities it would get now:
     * its source and its group-mates' are unchanged, and so is everything it was
     * stamped against. Reads, never loads — so checking cannot start a stamping
     * pass inside another.
     */
    private fun isCurrent(modulePath: String, memo: HashMap<String, Boolean>): Boolean {
        memo[modulePath]?.let { return it }
        memo[modulePath] = true   // a group-mate checking back; the group is judged as a whole below
        val cached = getFromCache(world, modulePath)
        val current = cached?.source != null && run {
            val via = resolverFor(cached.filePath)
            via.readSource(modulePath)?.hash == cached.sourceHash &&
                cached.groupSources.all { (mate, hash) -> via.readSource(mate)?.hash == hash } &&
                cached.stampedAgainst.all { (dep, was) ->
                    if (was == null) via.readSource(dep) == null      // still absent
                    else getFromCache(world, dep)?.identity == was && via.isCurrent(dep, memo)
                }
        }
        memo[modulePath] = current
        return current
    }

    /** What a stamping pass works on: a module's source, parsed, and who resolves its imports. */
    private class Raw(val source: Source, val parsed: ParsedModule, val resolver: ModuleResolver)

    private fun dependencyPaths(raw: Raw): List<String> {
        val imports = ModuleIdentity.dependencies(raw.parsed)
        val prelude = if (ModulePathConventions.isStdlib(raw.source.modulePath)) emptyList() else PRELUDE_MODULES
        return (imports + prelude).map { it.joinToString("::") }.filter { it !in coreTypeModules }.distinct()
    }

    /**
     * Stamp [root] and everything it depends on that is not current.
     *
     * Modules that import each other are stamped together ([IdentityGroup]), so
     * this finds them: Tarjan over the modules that need stamping, every group
     * stamped after the groups it imports. A current module is a leaf — its
     * identities are already final. A dependency that cannot be loaded is no
     * module at all; the typechecker reports it when it processes the import.
     */
    private fun stampFrom(root: String) {
        val memo = HashMap<String, Boolean>()
        val raws = HashMap<String, Raw>()
        val stampedNow = HashSet<String>()

        fun load(path: String, via: ModuleResolver, isRoot: Boolean): Raw? {
            raws[path]?.let { return it }
            val src = via.readSource(path) ?: return null
            val parsed = try { via.parse(src) } catch (e: ModuleError.ParseError) { if (isRoot) throw e else return null }
            return Raw(src, parsed, via.resolverFor(src.filePath)).also { raws[path] = it }
        }
        fun needsStamp(path: String, via: ModuleResolver): Boolean =
            getFromCache(world, path) == null || !via.isCurrent(path, memo)

        load(root, this, isRoot = true)
        val index = HashMap<String, Int>()
        val low = HashMap<String, Int>()
        val onStack = HashSet<String>()
        val stack = ArrayDeque<String>()
        val successors = HashMap<String, List<String>>()
        fun succ(path: String): List<String> = successors.getOrPut(path) {
            val raw = raws.getValue(path)
            dependencyPaths(raw).filter { dep -> needsStamp(dep, raw.resolver) && load(dep, raw.resolver, false) != null }
        }

        // Iterative, so a deep import chain cannot overflow the stack.
        val frames = ArrayDeque<Pair<String, Int>>()
        fun enter(v: String) {
            index[v] = index.size; low[v] = index.getValue(v)
            stack.addLast(v); onStack.add(v)
            frames.addLast(v to 0)
        }
        enter(root)
        while (frames.isNotEmpty()) {
            val (v, at) = frames.removeLast()
            val next = succ(v)
            if (at < next.size) {
                frames.addLast(v to at + 1)
                val w = next[at]
                if (w !in index) enter(w)
                else if (w in onStack) low[v] = minOf(low.getValue(v), index.getValue(w))
                continue
            }
            frames.lastOrNull()?.let { (parent, _) -> low[parent] = minOf(low.getValue(parent), low.getValue(v)) }
            if (low[v] == index[v]) {
                val group = ArrayList<String>()
                do {
                    val w = stack.removeLast()
                    onStack.remove(w)
                    group.add(w)
                } while (w != v)
                stampGroup(group.sorted(), raws, memo, stampedNow)
            }
        }
    }

    /** Stamp modules that import each other, as one group, and cache each. */
    private fun stampGroup(
        members: List<String>,
        raws: Map<String, Raw>,
        memo: Map<String, Boolean>,
        stampedNow: MutableSet<String>,
    ) {
        val group = IdentityGroup(members.map { raws.getValue(it).parsed })
        val memberIndex = members.withIndex().associate { it.value to it.index }
        val against = LinkedHashMap<String, Hash?>()
        val loader = ModuleIdentity.Loader { segments ->
            val path = segments.joinToString("::")
            memberIndex[path]?.let { return@Loader ModuleIdentity.Visible(raws.getValue(path).parsed, it) }
            // Outside the group: stamped earlier in this pass, or found current.
            val module = getFromCache(world, path)
                ?.takeIf { path in stampedNow || memo[path] == true }
                ?.takeIf { path !in coreTypeModules }
            against[path] = module?.identity
            module?.source?.let { ModuleIdentity.Visible(it, null) }
        }
        val scopes = members.map { path ->
            val prelude = if (ModulePathConventions.isStdlib(path)) emptyList()
                else PRELUDE_MODULES.mapNotNull { loader.load(it) }
            ModuleIdentity.scope(raws.getValue(path).parsed, loader, prelude, group)
        }
        val stamped = group.stamp(scopes, members)
        val sources = members.associateWith { raws.getValue(it).source.hash }

        members.forEachIndexed { i, path ->
            val module = stamped[i]
            if (ModulePathConventions.isStdlib(path)) com.mezze.language.typechecker.StdlibIdentities.register(module)
            putInCache(world, path, Module(
                path = ModulePath.parse(path),
                filePath = raws.getValue(path).source.filePath,
                exports = extractExportsFromParsedModule(module, path),
                dependencies = extractDependenciesFromParsedModule(module),
                sourceHash = raws.getValue(path).source.hash,
                index = ProgramIndex.build(module),
                parsedModule = module,
                source = module,
                identity = ModuleIdentity.fingerprint(module),
                stampedAgainst = against.toMap(),
                groupSources = sources - path,
            ))
            stampedNow.add(path)
        }
    }

    /**
     * Stamp an ENTRY module — one handed to the typechecker directly rather than
     * loaded through an import. A module that arrives stamped came through this
     * resolver, stamped together with any module it imports back, and is kept.
     * [modulePath] salts its nominal types; an entry with none is the program.
     */
    fun stampEntry(parsed: ParsedModule, modulePath: String?): ParsedModule {
        if (ModuleIdentity.isStamped(parsed)) return parsed
        return DeclIdentity.stamp(parsed, scopeOf(parsed, modulePath), modulePath ?: "")
    }

    /** What each written name in a STAMPED module resolves to — see [DeclIdentity.NameResolver]. */
    fun nameResolver(stamped: ParsedModule, modulePath: String?): DeclIdentity.NameResolver =
        DeclIdentity.NameResolver(stamped, scopeOf(stamped, modulePath))

    /** The scope a module sees once everything it imports is stamped and cached. */
    private fun scopeOf(parsed: ParsedModule, modulePath: String?): IdentityScope {
        val loader = ModuleIdentity.Loader { segments ->
            // A module that will not load contributes no names; the typechecker
            // reports it when it processes the import.
            val module = try { resolveModuleInternal(segments.joinToString("::")) } catch (_: ModuleError) { null }
            module?.source?.let { ModuleIdentity.Visible(it, null) }
        }
        val prelude = if (ModulePathConventions.isStdlib(modulePath)) emptyList()
            else PRELUDE_MODULES.mapNotNull { loader.load(it) }
        return ModuleIdentity.scope(parsed, loader, prelude, null)
    }

    /** The resolver a module's own imports go through: rooted at its own project. */
    private fun resolverFor(filePath: String): ModuleResolver {
        val dir = Paths.get(filePath).toAbsolutePath().parent ?: return this
        return if (findProjectRoot(dir) == projectRoot) this else ModuleResolver(dir, world)
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
     * Layout: `<tmpdir>/mezze-stdlib-cache/<resource-path>` (directory structure
     * PRESERVED, not flattened). Same resource → same path on every compile, every
     * JVM, every host. Idempotent: skip write if file already exists with matching bytes.
     */
    private fun materializeStdlibTempFile(relativeFilePath: String, content: String): Path {
        val normalized = relativeFilePath.replace('\\', '/')
        val cacheDir = Paths.get(System.getProperty("java.io.tmpdir"), "mezze-stdlib-cache")
        val target = cacheDir.resolve(normalized).normalize()
        Files.createDirectories(target.parent)
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

            // FLAT deps: every dep (direct + transitive) lives in the TOP
            // project's single `.deps/mezze`. When resolving from inside a dep
            // (this module's path contains `/.deps/`), the nearest project root is
            // the dep itself, whose `.deps` no longer exists — so search the
            // TOP-LEVEL project's `.deps` (walk up past the `.deps` segment). Also
            // try the nearest `projectRoot`'s `.deps` for backward-compat with any
            // not-yet-resynced tree.
            val depsDirs = buildList {
                add(projectRoot.resolve(".deps/mezze"))
                topProjectDepsDir()?.let { if (it != projectRoot.resolve(".deps/mezze")) add(it) }
            }

            for (depsDir in depsDirs) {
              if (Files.exists(depsDir) && Files.isDirectory(depsDir)) {
                // Find directories matching {packageName}-* (e.g., json-1.2.0)
                Files.list(depsDir).use { stream ->
                    val matchingDirs = stream
                        .filter { Files.isDirectory(it) }
                        .filter { it.fileName.toString().startsWith("$packageName-") }
                        .toList()

                    for (depDir in matchingDirs) {
                        val searchPaths = buildList {
                            // A bare `use <pkg>` (no submodule) resolves to the
                            // dependency's [lib] entry from its Mezze.toml, so a
                            // consumer writes `use greet::{example}` rather than
                            // needing the internal file name `use greet::lib::{…}`.
                            if (segments.size == 1) depLibEntryFile(depDir)?.let { add(it) }
                            add(depDir.resolve("src").resolve(restOfPath))
                            add(depDir.resolve(restOfPath))
                        }

                        for (path in searchPaths) {
                            if (Files.exists(path) && Files.isRegularFile(path)) {
                                return path
                            }
                        }
                    }
                }
              }
            }
        }

        return null
    }

    /**
     * For a module being resolved from INSIDE a dep (its path contains a
     * `.deps` segment), the top-level project's `.deps/mezze` — i.e. walk up
     * past the outermost `.deps` to the enclosing project. Null when this module
     * is not inside any `.deps` tree (then projectRoot's own `.deps` is enough).
     */
    private fun topProjectDepsDir(): Path? {
        val cur = try { projectRoot.toAbsolutePath() } catch (_: Exception) { return null }
        val parts = (0 until cur.nameCount).map { cur.getName(it).toString() }
        val depsIdx = parts.indexOf(".deps")
        if (depsIdx <= 0) return null
        // The dir just above the outermost `.deps` is the top project root.
        var top = cur.root ?: return null
        for (i in 0 until depsIdx) top = top.resolve(parts[i])
        return top.resolve(".deps").resolve("mezze")
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
                        ?.let { TypeAnnotationConverter.convert(it) }
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
                    val contentHash = decl.identity

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
                    val contentHash = decl.identity
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
                    val contentHash = decl.identity
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
                    val contentHash = decl.identity
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
                    val contentHash = decl.identity
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
                    // Re-exported items are not surfaced into this exports map, so a
                    // glob `use mid` can't see what `mid` re-exports. The consumer-side
                    // resolver (ImportResolver.resolvePubUseReexport) handles only
                    // selective imports of re-exported *functions*; types/abilities/
                    // effects/impls stay invisible downstream. Tracked as task #275.
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
    
        fun updateModuleExports(
        modulePath: String,
        parsedModule: ParsedModule,
        typeContext: Map<String, MezzeType>? = null
    ) {
        if (parsedModule.decls.isEmpty()) return
        DebugLogger.debug(Category.ModuleResolver) {
            "updateModuleExports: modulePath=$modulePath, ${parsedModule.decls.size} decls"
        }

        val module = getFromCache(world, modulePath)

        if (module != null) {
            // Re-derived, not patched: the module handed back is the one the
            // typechecker resolved, and its exports carry its identities.
            val updatedExports = extractExportsFromParsedModule(parsedModule, modulePath, typeContext)

            val updatedIndex = ProgramIndex.build(parsedModule)

            val updatedModule = module.copy(
                exports = updatedExports,
                parsedModule = parsedModule,
                index = updatedIndex
            )
            putInCache(world, modulePath, updatedModule)

            DebugLogger.debug(Category.ModuleResolver) {
                "Cached module (updated types+index) with ${updatedModule.exports.size} exports"
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
            putInCache(world, modulePath, newModule)

            DebugLogger.debug(Category.ModuleResolver) {
                "Cached module (new) with ${newModule.exports.size} exports"
            }
        }
    }

}