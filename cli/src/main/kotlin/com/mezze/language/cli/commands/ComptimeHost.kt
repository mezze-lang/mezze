package com.mezze.language.cli.commands

import com.mezze.language.cli.JvmLauncher
import com.mezze.language.comptime.Comptime
import com.mezze.language.comptime.ComptimeEvaluator
import com.mezze.language.comptime.ComptimeReduction
import com.mezze.language.truffleir.FunctionIR
import java.net.URLClassLoader

/**
 * Runs `comptime` blocks during a build.
 *
 * ## Why this has to happen in the CLI
 *
 * The runtime reduces too, and for `mez dev` and `mez exec` that is the only
 * place it can: those hand the runtime pre-lowered IR and never look at it
 * again. But a jar or a native binary is run many times, and reducing at
 * start-up would quietly redefine "compile time" as "every launch" — the work
 * repeated on each run, and an effectful block re-reading the world each time
 * instead of being baked in. A built artifact has to ship the constant.
 *
 * ## Why the evaluator is loaded by name
 *
 * `:interpreter` is deliberately off the CLI's compile class path: the CLI
 * names interpreter classes only as strings, when spawning a runtime child or
 * assembling native-image's main class, and keeping Truffle out of its own
 * process is what keeps `mez` starting quickly. So the evaluator is loaded the
 * same way everything else here reaches the runtime — from
 * `mezze-runtime.jar`, by name, through a child loader that delegates to this
 * one. `Comptime`, `FunctionIR` and the rest resolve from the parent, so both
 * sides agree on the types they exchange.
 *
 * No evaluator means the build stops, when — and only when — the program has
 * a `comptime` block. A block left alone would still compute a value at run
 * time through `eval {}`, but not the one a build promises, and a question to
 * the compiler (`std::codebase`) would have no answer at all.
 */
internal object ComptimeHost {

    /**
     * Fold every comptime block in [functions].
     *
     * Throws [ComptimeReduction.ComptimeFailure] when a block raises or calls
     * `compile_error` — the author's error, to be reported like any other.
     */
    fun reduce(
        functions: List<FunctionIR>,
        performers: List<Array<String>>,
        codebase: com.mezze.language.comptime.CodebaseHost?,
    ): List<FunctionIR> {
        val ir = com.mezze.language.comptime.ComptimeIr()
        if (functions.none { ir.hasSite(it.body) }) return functions

        if (org.graalvm.nativeimage.ImageInfo.inImageRuntimeCode()) {
            // The native `mez` carries the interpreter in its own image (a class
            // loader cannot add one at run time there), and finds it as a service.
            ensureInstalledFromImage()
            return ComptimeReduction.reduceOrThrow(functions, performers, codebase)
        }

        val loader = runtimeLoader() ?: throw ComptimeReduction.ComptimeFailure(
            "this program has `comptime` blocks, but this `mez` cannot evaluate them: " +
                "mezze-runtime.jar was not found. Folding at build time is what " +
                "`comptime` means, so the build stops rather than ship them unevaluated."
        )
        // Truffle discovers languages through the CONTEXT class loader, not the
        // one that loaded the evaluator. Without this the engine comes up with
        // no languages at all and every thunk fails with "a language with id
        // 'mezze' is not available".
        val previous = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = loader
        try {
            return ComptimeReduction.reduceOrThrow(functions, performers, codebase)
        } finally {
            Thread.currentThread().contextClassLoader = previous
        }
    }

    /**
     * Inside the native image the evaluator is part of the binary. It used to be
     * loaded from the runtime jar here too, which a native image cannot do: the
     * load failed, was swallowed, and every `comptime` block silently ran at run
     * time instead of during the build.
     */
    private fun ensureInstalledFromImage() {
        if (Comptime.get() != null) return
        val evaluator = java.util.ServiceLoader.load(ComptimeEvaluator::class.java).firstOrNull()
            ?: throw ComptimeReduction.ComptimeFailure(
                "this `mez` binary was built without the Mezze evaluator, so it cannot fold " +
                    "`comptime` blocks. Rebuild it with :interpreter on the native-image class path."
            )
        Comptime.install(evaluator)
    }

    private var attempted = false
    private var loader: ClassLoader? = null

    private fun runtimeLoader(): ClassLoader? {
        if (attempted) return loader
        attempted = true
        val jar = JvmLauncher.findRuntimeJar() ?: return null
        try {
            val child = URLClassLoader(arrayOf(jar.toURI().toURL()), javaClass.classLoader)
            Class.forName(EVALUATOR, true, child).getMethod("install").invoke(null)
            loader = child
        } catch (e: ReflectiveOperationException) {
            // A runtime jar too old to have an evaluator, or one that failed to
            // initialise: reported by `reduce`, which knows whether it mattered.
        }
        return loader
    }

    private const val EVALUATOR = "com.mezze.interpreter.comptime.TruffleComptimeEvaluator"
}
