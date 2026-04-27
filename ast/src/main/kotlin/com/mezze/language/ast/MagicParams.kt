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
}
