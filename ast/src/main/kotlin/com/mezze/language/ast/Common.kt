@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.mezze.language.ast

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
data class TypeVar(val name: String, val level: Int = 0, val id: Int) {
    /**
     * True when this variable stands for "the compiler did not determine a type
     * here" — NOT for "this position is polymorphic".
     *
     * The two were the same value until now: a pass that could not find a type
     * minted a fresh variable, and every pass downstream read it as ordinary
     * polymorphism. That is how a VARIANT comparison came to be rewritten to
     * `Eq Int`'s operation — mono treats a type variable as "the specialization's
     * evidence decides", and the specialization happened to carry `Eq Int`.
     *
     * DERIVED from the name rather than stored, because a type variable is rebuilt
     * from its name in several passes (instantiation, freshening, substitution) and
     * a stored flag is silently dropped by every one of them. [UNKNOWN_NAME] cannot
     * collide with a program's own variable: those always carry the leading `'`,
     * which is load-bearing elsewhere (see [freshWithName]).
     *
     * A consumer that would GUESS from a variable — pick an impl, an operation, a
     * representation — must ask this first and refuse. A consumer that legitimately
     * defers to one (unification, generalization) should not ask.
     */
    val isPlaceholder: Boolean get() = name == UNKNOWN_NAME

    /**
     * True when this variable stands for a polyglot FOREIGN handle — the associated
     * type of the `Polyglot` effect (`having 'f`). Such a variable is still fully
     * flexible for inference (it unifies with `JvmForeign`/`JsForeign`/… — the
     * `newtype … = Foreign` handles — so `x.attr{}.call{}` chains and providers need
     * ZERO annotations), but it is NOT a blank slate: it may NOT be bound to a
     * STRUCTURAL type (`Result`, a record, a non-foreign variant, a primitive). That
     * is the gate that stops a provider op declared `-> Result XForeign` from
     * silently laundering `eval_raw`'s bare foreign into a `Result` and crashing a
     * caller's `match … is Ok/Err` at runtime (see Unification.unifyVar).
     *
     * DERIVED from the name for the same reason as [isPlaceholder]: the flag would
     * be dropped by instantiation/freshening/substitution, but the name survives
     * (the leading `'` keeps `freshWithName` from replacing it). [FOREIGN_KIND_NAME]
     * carries a `~`, which a source type variable cannot, so it never collides.
     */
    val isForeignKinded: Boolean get() = name == FOREIGN_KIND_NAME

    companion object {
        /**
         * Ids are unique across threads — a variable's identity is its id, and
         * modules are checked in parallel. Levels and the per-scope counters that
         * name variables (`'a`, `'b`, …) belong to the check a thread is doing.
         */
        private val ids = java.util.concurrent.atomic.AtomicInteger()
        private class Scopes {
            var level = 0
            val counters = mutableMapOf<Int, Int>()
        }
        private val scopes = ThreadLocal.withInitial { Scopes() }
        private val thisLevel: Int get() = scopes.get().level
        private val scopeCounters: MutableMap<Int, Int> get() = scopes.get().counters

        fun fresh(level: Int = thisLevel): TypeVar {
            // Get or initialize counter for this level, starting from 0
            val scopeCounter = scopeCounters.getOrPut(level) { 0 }

            // Generate name: 'a', 'b', ..., 'z', 'a1', 'b1', ..., 'z1', 'a2', etc.
            val letterIndex = scopeCounter % 26
            val suffix = if (scopeCounter >= 26) (scopeCounter / 26).toString() else ""
            val name = "'${('a' + letterIndex)}$suffix"

            // Increment counters
            scopeCounters[level] = scopeCounter + 1
            val id = ids.getAndIncrement()

            return TypeVar(name, level, id)
        }

        fun freshNamed(baseName: String, level: Int = thisLevel): TypeVar {
            val scopeCounter = scopeCounters.getOrPut(level) { 0 }
            val name = "'${baseName}${scopeCounter}"
            scopeCounters[level] = scopeCounter + 1
            val id = ids.getAndIncrement()
            return TypeVar(name, level, id)
        }

        /**
         * Fresh TypeVar with a caller-chosen name, stored VERBATIM (unlike
         * [fresh]/[freshNamed], which always prepend `'`). The leading `'` is a
         * load-bearing tag, not decoration: instantiation branches on it
         * (ExpressionConstraints — `if (name.startsWith("'")) freshWithName(name)
         * else fresh()`), so a name's `'`-prefix decides whether its identity is
         * preserved across instantiation.
         *
         * Two intents share this one function, distinguished only by the name:
         *  - Real / associated type variables (`'a`, `'it`) — pass a `'`-prefixed
         *    name (usually straight from source or a type param) to keep identity.
         *  - Internal placeholders (`"unknown"`, `"ret"`, `"elem"`, `"pat"`, …) —
         *    pass a BARE name so instantiation treats them as replaceable.
         * Keep this split: giving an internal placeholder a `'` (or dropping the
         * `'` from a real type var) silently changes instantiation behavior.
         */
        fun freshWithName(name: String, level: Int = thisLevel): TypeVar {
            val id = ids.getAndIncrement()
            return TypeVar(name, level, id)
        }

        /**
         * A fresh variable for a position the compiler invented — a return slot, a
         * seq element, a pattern's type. NOT a type variable the user wrote.
         *
         * This is [freshWithName]'s bare-name half, split out so the invariant is
         * checked instead of remembered. The leading `'` decides whether
         * instantiation preserves a variable's identity or replaces it, so a `'`
         * that slips onto an internal name silently changes inference somewhere
         * far away, and the only thing standing between those two meanings was
         * the discipline to spell the argument right.
         *
         * The other half CANNOT be split out the same way: the sites that pass
         * `annotation.name`, `decl.name` or `constraint.typeParam` take a name
         * from source, where the `'` is data rather than a choice. Those keep
         * calling [freshWithName], which is the honest signature for "the tick
         * comes from the input".
         */
        fun freshInternal(name: String, level: Int = thisLevel): TypeVar {
            require(!name.startsWith("'")) {
                "freshInternal is for compiler-invented positions, which must NOT carry " +
                    "the leading ' that marks an identity-preserving type variable; got '$name'. " +
                    "If this really is a variable the user wrote, call freshWithName."
            }
            require(name != UNKNOWN_NAME) {
                "Use TypeVar.unknown(reason) to mint the \"could not determine a type\" " +
                    "placeholder — it is the one that isPlaceholder recognises and that the " +
                    "placeholder ratchet counts."
            }
            return freshWithName(name, level)
        }

        /**
         * The type a pass reaches for when it could NOT determine one.
         *
         * Every such site used to write `?: VarType(freshWithName("unknown"))`
         * inline, which made "I don't know" indistinguishable from "this is
         * polymorphic" the moment it left the function. There is one way to mint
         * one now, it is marked, and [isPlaceholder] is how a consumer that would
         * otherwise guess can tell.
         *
         * [reason] is for diagnostics and for the gate that counts these; it does
         * not affect identity.
         */
        /** The reserved name marking a placeholder. A program's own variables all
         *  begin with `'`, so this cannot be one of them. */
        const val UNKNOWN_NAME = "unknown"

        fun unknown(reason: String, level: Int = thisLevel): TypeVar {
            val id = ids.getAndIncrement()
            unknownMinted.add(reason)
            return TypeVar(UNKNOWN_NAME, level, id)
        }

        /** The reserved name marking a polyglot foreign-handle variable (see
         *  [isForeignKinded]). The leading `'` makes instantiation preserve it;
         *  the `~` cannot appear in a source type variable, so it never collides. */
        const val FOREIGN_KIND_NAME = "'~foreign"

        /** The stdlib effect whose associated type IS the foreign boundary:
         *  `std::polyglot::core`'s `Polyglot`. Its binding is minted
         *  foreign-kinded (see [foreignKinded]) because the handler's
         *  `having 'f = 'g` binding is abstract — there is no structural signal
         *  to key on, so the effect NAME is the key. Named here (not a bare
         *  literal in ContextBuilder) so the compiler↔stdlib contract is
         *  explicit and greppable. */
        const val POLYGLOT_EFFECT_NAME = "Polyglot"

        /** A fresh foreign-kinded variable — the `Polyglot` effect's associated
         *  type at a handler binding / call site. Flexible for inference but gated
         *  against structural types in unification. */
        fun foreignKinded(level: Int = thisLevel): TypeVar =
            freshWithName(FOREIGN_KIND_NAME, level)

        /** Reasons an unknown placeholder was minted, for the ratchet that bounds
         *  them. Per thread: test classes run in parallel. */
        private val minted = ThreadLocal.withInitial { LinkedHashSet<String>() }
        val unknownMinted: MutableSet<String> get() = minted.get()

        /** Restart this thread's naming. Ids are never reset: one reused would alias two variables. */
        fun resetCounter() {
            scopes.get().level = 0
            scopeCounters.clear()
        }

        fun resetScope(level: Int = 0) {
            // Reset counter for a specific scope level (useful for per-function reset)
            scopeCounters[level] = 0
        }

        fun enterScope(): Int {
            val s = scopes.get()
            s.level++
            // Each new scope starts fresh with 'a
            s.counters[s.level] = 0
            return s.level
        }

        fun exitScope() {
            val s = scopes.get()
            if (s.level > 0) {
                s.counters.remove(s.level)
                s.level--
            }
        }

        fun getCurrentLevel(): Int = thisLevel

        fun resetScopeCounter(level: Int = thisLevel) {
            scopeCounters[level] = 0
        }
    }
}

@Serializable
data class RowVar(val name: String) {
    companion object {
        /** A row variable IS its name, so the number must be unique across threads. */
        private val counter = java.util.concurrent.atomic.AtomicInteger()

        // May be closed by closeGeneratedRowVars during finalization.
        fun fresh(): RowVar = RowVar("row${counter.getAndIncrement()}")

        fun userOpen(): RowVar = RowVar("userOpen_row${counter.getAndIncrement()}")
    }

    fun isUserSpecified(): Boolean = name.startsWith("userOpen_")
}


// NOT part of the AST or content hash.
object SyntaxMetadata {
    private val operatorMetadata = ThreadLocal.withInitial<MutableMap<com.mezze.language.parsedast.Loc, String>> {
        mutableMapOf()
    }

    fun markAsOperator(location: com.mezze.language.parsedast.Loc, operatorSyntax: String) {
        operatorMetadata.get()[location] = operatorSyntax
    }

    fun getOperatorSyntax(location: com.mezze.language.parsedast.Loc): String? {
        return operatorMetadata.get()[location]
    }

    fun clear() {
        operatorMetadata.get().clear()
    }

}


/**
 * Typealias to the canonical Visibility enum in parsedast.Loc.
 */
typealias Visibility = com.mezze.language.parsedast.Visibility

@Serializable
@JsonClassDiscriminator("usePathKind")
sealed interface UsePath

// Structured module path — eliminates string-based `path.split("::")`.
@Serializable
data class ModulePath(val segments: List<String>) : UsePath {
    val fullPath: String get() = segments.joinToString("::")

    val shortName: String get() = segments.lastOrNull() ?: ""

    val isQualified: Boolean get() = segments.size > 1

    val parent: ModulePath? get() = if (segments.size > 1) {
        ModulePath(segments.dropLast(1))
    } else null

    fun endsWith(suffix: ModulePath): Boolean {
        if (suffix.segments.size > segments.size) return false
        val offset = segments.size - suffix.segments.size
        return suffix.segments.withIndex().all { (i, segment) ->
            segments[offset + i] == segment
        }
    }

    companion object {
        // The ONLY place that should parse :: from strings (except the parser).
        fun parse(path: String): ModulePath = ModulePath(path.split("::"))

    }
}

@Serializable
data class SelectivePath(
    val module: List<String>,
    val items: List<ImportItem>
) : UsePath

@Serializable
enum class ImportItemKind {
    VALUE,
    TYPE,
    ABILITY,
    EFFECT
}

// contentHash populated by typechecker during import resolution for O(1) content-addressed lookups.
@Serializable
data class ImportItem(
    val name: String,
    val alias: String? = null,
    val contentHash: String? = null,
    val kind: ImportItemKind = ImportItemKind.VALUE  // Default to VALUE for backward compatibility
) {
    val needsRuntimeBinding: Boolean get() = kind == ImportItemKind.VALUE
}


@Serializable
data class BindingKey(
    val qualifier: String?,
    val localName: String
) {
    // The `::` in this string is an implementation detail, not user-facing syntax.
    fun toKeyString(): String = if (qualifier != null && qualifier.isNotEmpty()) {
        "$qualifier::$localName"
    } else {
        localName
    }

    companion object {
        fun unqualified(name: String): BindingKey = BindingKey(qualifier = null, localName = name)

        fun qualified(qualifier: String, name: String): BindingKey = BindingKey(qualifier = qualifier, localName = name)

        // Internal use only — converts legacy string keys. New code should construct BindingKey directly.
        fun parse(keyString: String): BindingKey {
            val lastSeparator = keyString.lastIndexOf("::")
            return if (lastSeparator == -1) {
                unqualified(keyString)
            } else {
                val qualifier = keyString.substring(0, lastSeparator)
                val localName = keyString.substring(lastSeparator + 2)
                qualified(qualifier, localName)
            }
        }
    }
}


object ModulePathConventions {
    const val STDLIB_PREFIX = "std::"

    /** External polyglot-provider packages follow the `mezze_<lang>` module
     *  convention (see `SyncCommand.inferProviderLanguages`). Their source
     *  files legitimately call `__native_*` intrinsics — they're the only
     *  code paths, outside stdlib, allowed to. */
    const val POLYGLOT_PROVIDER_PREFIX = "mezze_"

    // Null means "no module context" → never stdlib.
    fun isStdlib(modulePath: String?): Boolean =
        modulePath != null && modulePath.startsWith(STDLIB_PREFIX)

    /** Returns true for stdlib and for `mezze_<lang>` provider-package
     *  modules — the two source families permitted to reference
     *  `__native_*` intrinsics. See `ExprLiteralConstraints` (#109). */
    fun isNativeCapable(modulePath: String?): Boolean {
        if (modulePath == null) return false
        return modulePath.startsWith(STDLIB_PREFIX) ||
            modulePath.startsWith(POLYGLOT_PROVIDER_PREFIX)
    }
}

object Keywords {
    // Implicit receiver in ability impl bodies; TypedAstBuilder desugars `x.method {…}` into `method({it = x, …})`.
    const val IT = "it"
    // Right operand field for binary op desugaring; consumed by TruffleCompiler's DirectAddNode etc.
    const val R = "r"
    const val TO_DBG = "to_dbg"
    const val TO_STR = "to_str"
}

// Reverse-lookup constructor-set → builtin type name for ability-impl matching
// (e.g. an anonymous `| Some, None |` value matching `impl Sized for Option`).
// Used by TypedASTCompiler and MonoTypeOps — keeping the table here ensures both agree.
//
// DERIVED from [BuiltinTypes.reservedConstructorOwner] (the single source for
// "which builtin owns this reserved constructor"), so the ctor→owner mapping is
// never re-spelled here as raw literals. An anonymous variant whose FULL
// constructor set is exactly a builtin's reserved constructors IS that builtin
// structurally — that is the only case this maps. (Formerly a hand-written table
// that duplicated the ownership map and even carried a `Just,Nothing → Maybe`
// entry for a type that is not a builtin at all.)
object VariantAliases {
    // constructor-set key ("None,Some") → builtin type name ("Option"),
    // built by grouping reservedConstructorOwner by owner and requiring the
    // full reserved-constructor set of that owner.
    val byConstructorSet: Map<String, String> =
        BuiltinTypes.reservedConstructorOwner.entries
            .groupBy({ it.value }, { it.key })
            .entries
            .associate { (owner, ctors) -> ctors.sorted().joinToString(",") to owner }

    fun aliasFor(constructors: Set<String>): String? =
        byConstructorSet[constructors.sorted().joinToString(",")]
}

