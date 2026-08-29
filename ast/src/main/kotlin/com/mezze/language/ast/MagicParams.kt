package com.mezze.language.ast

/**
 * Compiler-injected parameters that aren't part of the user's
 * surface-level function signature. The typechecker generates these names
 * during lowering; everywhere else strips them before comparing record
 * shapes, displaying signatures, or counting expected arguments.
 *
 * One source of truth for "what is a magic param?" — every site that
 * needed to recognize compiler-injected params used to hardcode the
 * `__dict_` prefix string. They now go through this object so the
 * convention can evolve (more kinds: implicits, evidence, continuations)
 * without scattering literals across modules.
 */
object MagicParams {

    /**
     * Hidden dictionary params for ability dispatch. Carry the resolved
     * impl function for a generic ability call. Named like `__dict_to_str`
     * for the `to_str` method.
     */
    const val DICT_PREFIX = "__dict_"

    /** True if [name] is a compiler-injected parameter of any kind. */
    fun isMagic(name: String): Boolean = name.startsWith(DICT_PREFIX)

    /** True if [name] is a dict param specifically. */
    fun isDict(name: String): Boolean = name.startsWith(DICT_PREFIX)

    /** Build the dict-param name for [methodName]. */
    fun dictParamName(methodName: String): String = "$DICT_PREFIX$methodName"

    /** Strip the prefix off a dict-param name, returning the method name. */
    fun dictMethodOf(paramName: String): String? =
        if (paramName.startsWith(DICT_PREFIX)) paramName.removePrefix(DICT_PREFIX) else null

    // ── Compiler-internal magic IDENTIFIER prefixes / sentinels ──────────────
    // Same "one source of truth" intent as the params above: these are a private
    // typechecker↔interpreter naming contract (no user rename), but the contract
    // used to be enforced by scattered `startsWith("__native_")` / `== "crash"`
    // string literals across ~6 files. Routing them through these predicates keeps
    // the convention in one place so a change can't silently desync copies.

    /** Native FFI-bridge symbols, e.g. `__native_int_add`. Given a fresh/bottom
     *  type and treated as an intrinsic (no undefined-variable error). */
    const val NATIVE_PREFIX = "__native_"

    /** The `crash` intrinsic — like a native symbol, returns bottom. */
    const val CRASH = "crash"

    /** Record-spread sentinel field: a `{...rest}` entry lowers to a field named
     *  `__spread__` carrying the spread source (recognised at record-build/merge). */
    const val SPREAD_FIELD = "__spread__"

    /** Synthetic top-level bare-expression decl, e.g. `__toplevel_expr_0`. */
    const val TOPLEVEL_EXPR_PREFIX = "__toplevel_expr_"

    /** The compiled entry wrapper: a synthetic fn that binds every top-level
     *  `let` as a global (via RegisterGlobal). Minted by EntryFunctionBuilder,
     *  recognized at run in PipelineRunner. */
    const val ENTRY_FN = "__entry__"

    /** The conventional user entry-point name (an app's `main`). */
    const val MAIN_FN = "main"

    /** Anonymous-lambda placeholder function name. */
    const val LAMBDA_FN = "lambda"

    /** Internal solver-injected type-var name prefix (invariant checks skip these). */
    const val INTERNAL_PREFIX = "__internal_"

    /** Synthetic scrutinee binder a record-destructuring `let { a, b } = e` lowers
     *  to, holding the whole record while per-field bindings extract from it.
     *  `__destr_N` in a do-block, `__destructured_N` at top level. Never a user
     *  name — tooling (hover/goto-def) must not surface it. */
    const val DESTRUCTURE_PREFIX = "__destr_"
    const val DESTRUCTURE_DECL_PREFIX = "__destructured_"

    /** True if [name] is a compiler-injected record-destructure scrutinee/temp
     *  binding (not a user-written binder). */
    fun isDestructureTemp(name: String): Boolean =
        name.startsWith(DESTRUCTURE_PREFIX) || name.startsWith(DESTRUCTURE_DECL_PREFIX)

    /** True if [name] is an intrinsic symbol the FFI bridge owns (native or crash),
     *  which the typechecker types as fresh/bottom and exempts from undefined-var. */
    fun isIntrinsicSymbol(name: String): Boolean =
        name.startsWith(NATIVE_PREFIX) || name == CRASH
}
