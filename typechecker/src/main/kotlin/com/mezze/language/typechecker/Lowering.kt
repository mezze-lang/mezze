package com.mezze.language.typechecker

/**
 * Whether checking a module also lowers it to IR.
 *
 * Lowering — monomorphising, compiling to IR — is for running code: `build`,
 * `dev`, `eval`. `check` and the editor only need the types, and lowering every
 * module they check made them pay for a compiler stage they never use. They
 * turn it off; everything else keeps it.
 *
 * One exception survives: a macro runs while its caller is checked, so its own
 * module is lowered on demand, with [forced].
 */
object Lowering {
    @Volatile
    var enabled: Boolean = true

    private val force = ThreadLocal.withInitial { false }

    /** Whether to lower the module being checked now. */
    fun wanted(): Boolean = enabled || force.get()

    /** Run [body] lowering whatever it checks, as running a macro requires. */
    fun <T> forced(body: () -> T): T {
        val outer = force.get()
        force.set(true)
        try {
            return body()
        } finally {
            force.set(outer)
        }
    }
}
