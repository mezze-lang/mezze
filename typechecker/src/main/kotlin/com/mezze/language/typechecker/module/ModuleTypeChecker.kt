package com.mezze.language.typechecker.module

import com.mezze.language.ast.ModulePathConventions

import com.mezze.language.ast.BindingKey
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.World
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.context.TypeScheme
import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.solving.Substitution
import com.mezze.language.ast.solving.collectFreeTypeVars
import com.mezze.language.typechecker.ModuleError
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.PrecompiledStdlib
import com.mezze.language.typedast.TypedModule

class ModuleTypeChecker(
    private val baseContext: TypingContext = TypingContext.empty,
    private val moduleResolver: ModuleResolver? = null,
    private val strictTopLevel: Boolean = false,
    /**
     * Which view of the codebase this checker reads and writes.
     *
     * Defaults to [World.DEFAULT] so a caller with no opinion keeps the
     * single-world behaviour the caches had before they were separable.
     */
    private val world: World = World.DEFAULT,
) {
    init {
        // A checker in one world holding a resolver in another would split its
        // caches: typed modules land in `world` while the parsed modules they were
        // built from land somewhere else. Nothing would fail — the two halves would
        // just disagree about what the codebase contains, which is the kind of bug
        // that surfaces as an inexplicable stale result much later.
        val resolverWorld = moduleResolver?.world
        require(resolverWorld == null || resolverWorld == world) {
            "ModuleTypeChecker is in $world but its ModuleResolver is in $resolverWorld — " +
                "both halves of the cache must share one world"
        }
    }

    companion object {
        /**
         * One world's caches. Keyed by module path WITHIN a world, which together
         * make the `(world, module)` key that replaced the bare path.
         */
        private class Caches {
            val modules = mutableMapOf<String, TypedModuleResult>()
            val modulesLock = Any()

            /**
             * Every effect and ability declared by a cached module, by name, kept
             * as modules are added: a registry needs them all, and collecting
             * them by walking every cached module on every check made checking
             * a project quadratic in its number of modules. Rebuilt from
             * [modules] after a removal ([headersStale]).
             */
            val effects = HashMap<String, com.mezze.language.parsedast.ParsedEffectDecl>()
            val abilities = HashMap<String, com.mezze.language.parsedast.ParsedAbilityDecl>()
            var headersStale = false

            /**
             * The modules THIS THREAD is checking in this world, innermost last:
             * the chain of imports it is inside, which is what an import cycle is.
             *
             * Per thread, because modules are checked in parallel: a module another
             * thread is checking is not a cycle. Shared, it reported two concurrent
             * checks that both reach a module as each other's import cycle.
             */
            val checking: ThreadLocal<LinkedHashSet<String>> = ThreadLocal.withInitial { LinkedHashSet() }
        }

        private val worlds = java.util.concurrent.ConcurrentHashMap<World, Caches>()

        private fun caches(world: World): Caches = worlds.computeIfAbsent(world) { Caches() }

        /**
         * Modules shared by every world: the precompiled standard library.
         *
         * Separate from the per-world caches deliberately. Stdlib typed modules are
         * immutable and identical in every world, and there are a great many of
         * them — a copy per world would multiply the largest thing in memory by the
         * number of worlds for no gain, and would make creating a world expensive
         * enough to discourage using one.
         *
         * Only ever written by [preloadIntoGlobalCache], and never invalidated:
         * nothing a world does can change the stdlib.
         */
        private val sharedBase = mutableMapOf<String, TypedModuleResult>()
        private val sharedBaseLock = Any()

        /**
         * Forget everything [world] has checked.
         *
         * The shared base is left alone — it holds the stdlib, which a clear was
         * never meant to discard. Before worlds existed this method cleared the one
         * cache and then re-preloaded the stdlib into it, so the observable
         * behaviour is unchanged; the re-preload is now just unnecessary.
         */
        @JvmStatic
        @JvmOverloads
        fun clearCache(world: World = World.DEFAULT) {
            val c = caches(world)
            synchronized(c.modulesLock) { c.modules.clear(); c.effects.clear(); c.abilities.clear(); c.headersStale = false }
            // The catch here read "PrecompiledStdlib not initialized yet". It is an
            // object: touching it initialises it. Swallowing the failure left the
            // global cache empty after a clear, so the next check re-typechecked the
            // stdlib instead of reusing it. Measured at zero firings.
            val stdlib = PrecompiledStdlib
            for ((modulePath, module) in stdlib.loadedModules) {
                preloadIntoGlobalCache(modulePath.fullPath, module)
            }
        }

        /** Drop [world]'s caches entirely, so nothing is retained for it. */
        @JvmStatic
        fun forgetWorld(world: World) {
            require(world != World.DEFAULT) {
                "the default world cannot be forgotten — use clearCache() to empty it"
            }
            worlds.remove(world)
        }

        /**
         * A module as [world] sees it: its own first, then the shared base.
         *
         * The order is what makes a world an OVERLAY — a world that has checked its
         * own `std::…` variant sees that, and one that has not falls through to the
         * shared stdlib.
         */
        private fun getFromGlobalCache(world: World, modulePath: String): TypedModuleResult? {
            val c = caches(world)
            synchronized(c.modulesLock) { c.modules[modulePath] }?.let { return it }
            synchronized(sharedBaseLock) { return sharedBase[modulePath] }
        }

        private fun putInGlobalCache(world: World, modulePath: String, module: TypedModuleResult) {
            val c = caches(world)
            synchronized(c.modulesLock) {
                if (c.modules.put(modulePath, module) != null) c.headersStale = true
                else addHeaders(c, module)
            }
        }

        private fun addHeaders(c: Caches, module: TypedModuleResult) {
            for (d in module.declarationHeaders) when (d) {
                is com.mezze.language.parsedast.ParsedEffectDecl -> c.effects[d.name] = d
                is com.mezze.language.parsedast.ParsedAbilityDecl -> c.abilities[d.name] = d
                else -> {}
            }
        }

        /** Every effect and ability declared by a module cached in [world] or the shared base, by name. */
        @JvmStatic
        @JvmOverloads
        fun cachedEffectsAndAbilities(world: World = World.DEFAULT):
            Pair<Map<String, com.mezze.language.parsedast.ParsedEffectDecl>, Map<String, com.mezze.language.parsedast.ParsedAbilityDecl>> {
            val base = synchronized(sharedBaseLock) { baseHeaders() }
            val c = caches(world)
            val own = synchronized(c.modulesLock) {
                if (c.headersStale) {
                    c.effects.clear(); c.abilities.clear()
                    c.modules.values.forEach { addHeaders(c, it) }
                    c.headersStale = false
                }
                HashMap(c.effects) to HashMap(c.abilities)
            }
            return (base.effects + own.first) to (base.abilities + own.second)
        }

        private val sharedHeaders = Caches()
        private fun baseHeaders(): Caches {
            if (sharedHeaders.headersStale) {
                sharedHeaders.effects.clear(); sharedHeaders.abilities.clear()
                sharedBase.values.forEach { addHeaders(sharedHeaders, it) }
                sharedHeaders.headersStale = false
            }
            return sharedHeaders
        }

        /** Everything [world] can see, with its own entries shadowing the base. */
        @JvmStatic
        @JvmOverloads
        fun getAllCachedModules(world: World = World.DEFAULT): Map<String, TypedModuleResult> {
            val c = caches(world)
            val base = synchronized(sharedBaseLock) { sharedBase.toMap() }
            val own = synchronized(c.modulesLock) { c.modules.toMap() }
            return base + own
        }

        /**
         * Install a WORLD-INDEPENDENT precompiled module into the shared base.
         *
         * For artifacts that are the same in every world — the standard library.
         * Project code belongs in a world; use [hydrateFromV2Module], which takes
         * one.
         */
        @JvmStatic
        fun preloadIntoGlobalCache(modulePath: String, module: TypedModuleResult) {
            synchronized(sharedBaseLock) {
                if (sharedBase.put(modulePath, module) != null) sharedHeaders.headersStale = true
                else addHeaders(sharedHeaders, module)
            }
        }

/**
         * Install an already-typed module into [world]'s cache.
         *
         * World-local, unlike [preloadIntoGlobalCache]: this is PROJECT code read
         * back from the store, and a speculative world wants its own version of it.
         */
        @JvmStatic
        @JvmOverloads
        fun cacheTypedModule(
            modulePath: String,
            typedModule: TypedModule,
            world: World = World.DEFAULT,
        ) {
            val typeContext = mutableMapOf<String, MezzeType>()
            for ((name, export) in typedModule.exports) {
                typeContext[name] = export.type
            }
            val wrapper = TypedModuleResult(
                modulePath = modulePath,
                identity = typedModule.sourceIdentity,
                // The checker-side ExportedItem is a different shape from the typed
                // AST's ExportEntry, so exports cannot be carried across here.
                exports = emptyMap(),
                context = TypingContext.empty,
                substitution = Substitution.empty,
                typedModule = typedModule,
                parsedModule = null,
                typeContext = typeContext
            )
            putInGlobalCache(world, modulePath, wrapper)
        }

        @JvmStatic
        @JvmOverloads
        fun clearTypeCheckingStack(world: World = World.DEFAULT) {
            caches(world).checking.get().clear()
        }

        /**
         * Forget [modulePaths] in [world].
         *
         * The shared base is untouched: it holds the stdlib, and a file-watcher
         * event about project code has no business evicting it.
         */
        @JvmStatic
        @JvmOverloads
        fun invalidateModulePaths(modulePaths: Collection<String>, world: World = World.DEFAULT) {
            val c = caches(world)
            synchronized(c.modulesLock) {
                for (path in modulePaths) c.modules.remove(path)
                c.headersStale = true
            }
            c.checking.get().removeAll(modulePaths.toSet())
        }

        private fun isTypeChecking(world: World, modulePath: String): Boolean {
            val c = caches(world)
            return modulePath in c.checking.get()
        }

        private fun startTypeChecking(world: World, modulePath: String) {
            val c = caches(world)
            c.checking.get().add(modulePath)
        }

        private fun finishTypeChecking(world: World, modulePath: String) {
            val c = caches(world)
            c.checking.get().remove(modulePath)
        }

        private fun getTypeCheckingStack(world: World): List<String> {
            val c = caches(world)
            return c.checking.get().toList()
        }

        fun create() = ModuleTypeChecker()
        fun withContext(context: TypingContext) = ModuleTypeChecker(context)
        fun withResolver(resolver: ModuleResolver) = ModuleTypeChecker(moduleResolver = resolver)
        @JvmOverloads
        fun withContextAndResolver(
            context: TypingContext,
            resolver: ModuleResolver,
            world: World = World.DEFAULT,
        ) = ModuleTypeChecker(context, resolver, world = world)
    }

    fun typeCheckModule(
        module: LoadedModule,
        importContext: ModuleImportContext = ModuleImportContext.empty()
    ): ModuleTypeCheckResult {
        // Reused only while it was checked against what the module is NOW: a
        // dependency's change re-stamps this module without touching its source.
        // The precompiled stdlib carries no fingerprint and never changes.
        getFromGlobalCache(world, module.modulePath)
            ?.takeIf { cached ->
                if (cached.identity != null) cached.identity == module.identity
                // No fingerprint: only the precompiled stdlib, which never changes.
                else ModulePathConventions.isStdlib(module.modulePath)
            }
            ?.let { cached ->
            val typeContext = cached.typeContext
            // A result read back from the checked-module cache keeps its body
            // unread: the parse the resolver already has serves, as on that path.
            val cachedParsedModule = (if (cached.bodyRead) cached.parsedModule else null) ?: module.parsedModule
            if (cachedParsedModule != null) {
                moduleResolver?.updateModuleExports(
                    cached.modulePath, cachedParsedModule, typeContext
                )
            }
            return ModuleTypeCheckResult.Success(cached)
        }

        // Checked in an earlier run and unchanged since: read back, not checked.
        // Only the default world — a speculative one (the editor's unsaved text)
        // is never what was checked before.
        // A kept module carries no IR (checking does not lower), so where IR is
        // wanted — a macro's module, about to run — it is checked instead.
        if (world == World.DEFAULT) CheckedModuleCache.load(module)
            ?.takeIf { !com.mezze.language.typechecker.Lowering.wanted() || it.loweredFunctions.isNotEmpty() }
            ?.let { cached ->
            putInGlobalCache(world, module.modulePath, cached)
            // The parse the resolver already has, not the kept one: reading that
            // would read the whole body for the exports' sake.
            module.parsedModule?.let { moduleResolver?.updateModuleExports(cached.modulePath, it, cached.typeContext) }
            return ModuleTypeCheckResult.Success(cached)
        }

        if (isTypeChecking(world, module.modulePath)) {
            val cycle = getTypeCheckingStack(world) + module.modulePath
            throw ModuleError.CircularDependency(cycle)
        }

        startTypeChecking(world, module.modulePath)

        try {
            val moduleContext = buildModuleContext(importContext)

            val typeChecker = TypeChecker.withContext(moduleContext, strictTopLevel)
            val modulePath = java.nio.file.Paths.get(module.filePath).parent ?: java.nio.file.Paths.get(".")
            val parsedModule = module.parsedModule
                ?: throw IllegalStateException("LoadedModule for '${module.modulePath}' has no parsedModule")
            val result = typeChecker.checkParsedModule(parsedModule, modulePath, module.modulePath)

            return when (result) {
                is TypeChecker.TypeCheckResult.Success -> {
                    val typeContext = result.typeContext
                    val updatedExports = if (typeContext.isNotEmpty()) {
                        module.exports.mapValues { (_, export) ->
                            val inferredType = typeContext[export.originalName]
                            if (inferredType != null) export.copy(mezzeType = inferredType) else export
                        }
                    } else module.exports

                    val resolvedModule = result.resolvedParsedModule ?: parsedModule

                    val typedModule = TypedModuleResult(
                        modulePath = module.modulePath,
                        exports = updatedExports,
                        context = moduleContext,
                        substitution = result.substitution,
                        typedModule = result.typedModule?.copy(sourceIdentity = module.identity),
                        parsedModule = resolvedModule,
                        typeContext = typeContext,
                        filePath = module.filePath,
                        sourceHash = module.sourceHash,
                        identity = module.identity,
                        loweredFunctions = result.loweredFunctions.orEmpty(),
                        loweredPerformers = result.loweredPerformers,
                        buildTimeSummary = result.buildTimeSummary,
                        performerEffects = result.performerEffects,
                    )

                    putInGlobalCache(world, module.modulePath, typedModule)
                    if (world == World.DEFAULT) CheckedModuleCache.save(module, typedModule)

                    moduleResolver?.updateModuleExports(module.modulePath, resolvedModule, typeContext)

                    ModuleTypeCheckResult.Success(typedModule)
                }
                is TypeChecker.TypeCheckResult.Failure -> {
                    val errors = result.errors.map { error ->
                        ModuleTypeCheckResult.TypeCheckError(
                            modulePath = module.modulePath,
                            phase = error.phase,
                            message = error.message,
                            loc = error.loc
                        )
                    }
                    ModuleTypeCheckResult.Failure(errors)
                }
            }
        } finally {
            finishTypeChecking(world, module.modulePath)
        }
    }

    private fun buildModuleContext(
        importContext: ModuleImportContext
    ): TypingContext {
        var context = baseContext
        for ((name, binding) in importContext.importedBindings) {
            val bindingKey = BindingKey.parse(name)
            val freeVars = collectFreeTypeVars(binding.type)
            if (freeVars.isEmpty()) {
                context = context.extendMono(bindingKey, binding.type, binding.modulePath, binding.contentHash)
            } else {
                val scheme = TypeScheme(
                    quantifiedVars = freeVars.toList(),
                    constraints = emptyList(),
                    type = binding.type
                )
                context = context.extendPoly(bindingKey, scheme, binding.modulePath, binding.contentHash)
            }
        }

        return context
    }

}
