package com.mezze.language.typechecker.module

import com.mezze.language.ast.ModulePathConventions
import com.mezze.language.parsedast.ParsedModule
import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.typechecker.World
import java.util.concurrent.Executors

/**
 * Checks a program's project modules before its entry does, many at a time.
 *
 * A module is checked once every project module it imports is: the modules
 * that do not import each other run in parallel. Each is checked exactly as an
 * import would check it, into the world's module cache — so when the entry
 * then resolves its imports one by one, it finds them checked.
 *
 * Only what can be checked ahead safely is: a module that fails, or is part of
 * an import cycle, is left — with everything that imports it — to the entry's
 * own pass, which checks it in order and reports what is wrong exactly as it
 * always has.
 *
 * Off unless a command sets [threads]: `mez check` does.
 */
object ParallelModuleChecker {
    /** How many modules to check at once; 1 or less is off. */
    @Volatile
    var threads: Int = 0

    /** How many modules the last [checkAhead] checked. */
    @Volatile
    var lastCheckedAhead: Int = 0
        private set

    /** Each worker's stack: checking recurses once per level of an expression. */
    private const val STACK_BYTES = 1L shl 30

    internal fun checkAhead(entry: ParsedModule, world: World, loader: ModuleLoader, checker: () -> ModuleTypeChecker) {
        val n = threads
        lastCheckedAhead = 0
        if (n <= 1) return
        val modules = ModuleResolver.snapshot(world)

        // The project modules the entry reaches, each with the project modules it
        // imports. The stdlib is precompiled, and a dependency package resolves
        // through its own root — both are left to the import path.
        fun isProject(path: String): Boolean {
            val m = modules[path] ?: return false
            // A member of an import cycle is an error the entry's pass reports.
            return !ModulePathConventions.isStdlib(path) && "/.deps/" !in m.filePath.replace('\\', '/') &&
                m.groupSources.isEmpty()
        }
        val imports = LinkedHashMap<String, Set<String>>()
        val queue = ArrayDeque(ModuleIdentity.dependencies(entry).map { it.joinToString("::") }.filter(::isProject))
        while (queue.isNotEmpty()) {
            val path = queue.removeFirst()
            if (path in imports) continue
            val module = modules.getValue(path)
            val deps = module.stampedAgainst.keys.filter(::isProject).toSet()
            imports[path] = deps
            queue.addAll(deps)
        }
        if (imports.size < 2) return
        // Every module looks itself up in the cache store first: open it now,
        // beside the work, rather than with every worker waiting on it.
        ModuleResolver.cacheStore?.let { store -> Thread { store.value }.apply { isDaemon = true }.start() }

        val waitingOn = HashMap<String, Int>()
        val importers = HashMap<String, MutableList<String>>()
        for ((path, deps) in imports) {
            waitingOn[path] = deps.size
            for (d in deps) importers.getOrPut(d) { ArrayList() } += path
        }

        val pool = Executors.newFixedThreadPool(n) { r ->
            Thread(null, r, "module-checker", STACK_BYTES).apply { isDaemon = true }
        }
        val lock = Object()
        var inFlight = 0
        fun start(path: String) {
            inFlight++
            pool.execute {
                val ok = try {
                    checkOne(path, loader, checker)
                } catch (e: Throwable) {
                    // Left to the entry's pass, which reports it where it belongs.
                    false
                }
                synchronized(lock) {
                    // A module that failed holds back everything importing it.
                    if (ok) lastCheckedAhead++
                    if (ok) for (importer in importers[path].orEmpty()) {
                        val left = waitingOn.getValue(importer) - 1
                        waitingOn[importer] = left
                        if (left == 0) start(importer)
                    }
                    inFlight--
                    lock.notifyAll()
                }
            }
        }
        try {
            synchronized(lock) {
                // Modules in an import cycle never reach zero: they wait for each
                // other, and stay with the entry's pass.
                for ((path, count) in waitingOn) if (count == 0) start(path)
                while (inFlight > 0) lock.wait()
            }
        } finally {
            pool.shutdown()
        }
    }

    private fun checkOne(path: String, loader: ModuleLoader, checker: () -> ModuleTypeChecker): Boolean =
        TypeChecker.asPartOfCheck {
            val loaded = (loader.loadModule(path) as? ModuleLoadResult.Success)?.module ?: return@asPartOfCheck false
            checker().typeCheckModule(loaded) is ModuleTypeCheckResult.Success
        }
}
