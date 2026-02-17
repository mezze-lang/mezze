package com.mezze.language.interpreter

import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context

/**
 * Thread-local holder for the current Truffle environment and polyglot context.
 *
 * This is used to pass context information from the main execution thread
 * to spawned virtual threads in Loom concurrency.
 *
 * CRITICAL: Truffle enforces strict context isolation. When spawning a virtual thread,
 * we must properly manage the polyglot context to avoid AST sharing violations.
 */
object TruffleContextHolder {
    private val envThreadLocal = ThreadLocal<TruffleLanguage.Env?>()
    private val polyglotContextThreadLocal = ThreadLocal<Context?>()

    /**
     * Set the current Truffle environment for this thread.
     * Called automatically during Truffle execution.
     */
    fun setEnv(env: TruffleLanguage.Env?) {
        envThreadLocal.set(env)
    }

    /**
     * Get the current Truffle environment for this thread.
     * Returns null if not in a Truffle execution context.
     */
    fun getEnv(): TruffleLanguage.Env? {
        return envThreadLocal.get()
    }

    /**
     * Set the current polyglot context for this thread.
     */
    fun setPolyglotContext(context: Context?) {
        polyglotContextThreadLocal.set(context)
    }

    /**
     * Get the current polyglot context for this thread.
     */
    fun getPolyglotContext(): Context? {
        return polyglotContextThreadLocal.get()
    }

    /**
     * Clear the environment and context for this thread.
     * Should be called when leaving Truffle execution.
     */
    fun clearEnv() {
        envThreadLocal.remove()
        polyglotContextThreadLocal.remove()
    }
}
