package com.mezze.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A Mezze value as a TREE, not as a rendered string.
 *
 * ## Why a tree
 *
 * Every surface that shows a value wants a different presentation of the same
 * information: a REPL prints one line, a value browser navigates, an editor's
 * inline hint shows one level, a web page or an LLM wants it serialised. A
 * renderer that only produces `String` forces each of those to re-parse text
 * that was never meant to be parsed. So the tree is the primitive and text is
 * one fold over it ([toText]).
 *
 * ## What the nodes are
 *
 * The vocabulary mirrors `PrintType` — the compiler's static description of a
 * value's shape — because that is what the walk is driven by: a runtime value
 * alone cannot tell `Nat` from `Int` (both are `Long`), cannot see a newtype
 * (erased to its payload), and cannot tell a one-character `Str` from a `Char`.
 * Mirrored rather than shared: `PrintType` lives in `:ast` and is a
 * compiler-internal type, and this is a published wire contract. Coupling them
 * would make every compiler refactor a protocol break.
 *
 * Deliberately NOT one node per collection type. `PrintType` distinguishes six
 * ordered collections and two maps; a client renders all six identically — a
 * delimiter and a list of items — so they collapse into [Sequence] and
 * [Mapping] with a `kind` tag. Eight node types to express two behaviours would
 * push a `when` with eight arms into every client for no gain.
 *
 * ## Bounds, and what [Elided] means
 *
 * A tree is built to a depth and an item limit, and anything beyond becomes an
 * [Elided] node naming its own path. This is the laziness half of the design:
 * the first reply stays small, and a client that wants a particular branch asks
 * for that branch ([Expand]).
 *
 * [Elided] is unrelated to `PrintType.Ref`, which is about a cyclic TYPE and is
 * resolved during the walk — it never reaches this tree. [Elided] is about a
 * large or deep VALUE.
 */
@Serializable
sealed interface WireValue {

    /**
     * A leaf, already formatted. [kind] says which scalar it was ([Kind]); [text]
     * is what it renders as.
     *
     * The text is baked at build time rather than reconstructed by the client
     * because the formatting rules are the compiler's, not the client's:
     * `to_dbg` quotes a string where `to_str` does not, a `Nat` takes an `n`
     * suffix, `Bytes` renders as hex. A client re-deriving that would drift from
     * what the language itself prints.
     */
    @Serializable
    @SerialName("scalar")
    data class Scalar(val kind: String, val text: String) : WireValue {
        object Kind {
            const val INT = "int"
            const val NAT = "nat"
            const val FLOAT = "float"
            const val STR = "str"
            const val BOOL = "bool"
            const val CHAR = "char"
            const val BYTES = "bytes"

            /** A rational, printed `n//d`. */
            const val FRAC = "frac"

            /**
             * A value at a position with no resolved static type — an
             * unconstrained type parameter. Rendered from the runtime value
             * alone, which is correct for every runtime-distinguishable type.
             */
            const val DYNAMIC = "dynamic"
        }
    }

    /** A newtype, erased at runtime; prints `Name.(inner)`. */
    @Serializable
    @SerialName("newtype")
    data class Newtype(val name: String, val inner: WireValue) : WireValue

    /** A record, in declared field order. */
    @Serializable
    @SerialName("record")
    data class Record(val fields: List<Field> = emptyList()) : WireValue

    /** One record field. Not a [WireValue] itself — a label paired with one. */
    @Serializable
    data class Field(val name: String, val value: WireValue)

    /** A tagged union arm. [payload] is null for a payload-less constructor. */
    @Serializable
    @SerialName("variant")
    data class Variant(val constructor: String, val payload: WireValue? = null) : WireValue

    /**
     * An ordered collection: list, seq, set, sorted set, deque, non-empty list.
     *
     * [omitted] is how many elements the item limit dropped, so a client can say
     * "3 more" rather than silently showing a short collection.
     */
    @Serializable
    @SerialName("seq")
    data class Sequence(
        val kind: String,
        val items: List<WireValue> = emptyList(),
        val omitted: Int = 0,
    ) : WireValue {
        object Kind {
            const val LIST = "list"
            const val SEQ = "seq"
            const val SET = "set"
            const val SORTED_SET = "sorted_set"
            const val DEQUE = "deque"
            const val NON_EMPTY_LIST = "non_empty_list"
        }
    }

    /** A map or sorted map. [omitted] as in [Sequence]. */
    @Serializable
    @SerialName("map")
    data class Mapping(
        val kind: String,
        val entries: List<Entry> = emptyList(),
        val omitted: Int = 0,
    ) : WireValue {
        object Kind {
            const val MAP = "map"
            const val SORTED_MAP = "sorted_map"
        }
    }

    /** One map entry. */
    @Serializable
    data class Entry(val key: WireValue, val value: WireValue)

    /** A function value. [name] is null for an anonymous one. */
    @Serializable
    @SerialName("fn")
    data class Fn(val name: String? = null) : WireValue

    /**
     * A branch the build stopped at, addressable with [Expand].
     *
     * [path] is this node's own location in the tree (see [WireValue.Path]), so
     * a client echoes it back rather than having to track where it was. [hint]
     * names what was cut — `"record"`, `"seq"`, `"map"`, `"variant"` — so a UI
     * can show something more useful than an ellipsis without another round
     * trip.
     */
    @Serializable
    @SerialName("elided")
    data class Elided(val path: String, val hint: String) : WireValue

    /**
     * How an [Elided] node names its own position.
     *
     * A `/`-separated list of steps from the root, which is the empty string.
     * Each step is a one-letter kind and, where it needs one, an argument:
     *
     *   - `f:<name>`  a record field
     *   - `i:<n>`     an element of a [Sequence]
     *   - `k:<n>` / `v:<n>`  the key / value of a [Mapping] entry
     *   - `p`         a [Variant]'s payload
     *   - `n`         a [Newtype]'s inner value
     *
     * An index is a position in the collection, counted from zero. It coincides
     * with the position in [Sequence.items], because an item limit only ever
     * drops a SUFFIX; the two would part company if a build sampled or filtered
     * instead of truncating.
     *
     * Field names are not escaped, and a `/` or `:` in one would be ambiguous.
     * That is sound rather than lucky: a Mezze field name is an identifier, so
     * neither character can occur. [field] enforces it rather than trusting it,
     * because a silently ambiguous path would resolve to the wrong node.
     */
    object Path {
        const val ROOT = ""

        fun field(parent: String, name: String): String {
            require('/' !in name && ':' !in name) {
                "field name is not a valid path step: '$name' — a Mezze field name " +
                    "cannot contain '/' or ':', so this indicates a corrupted shape"
            }
            return step(parent, "f:$name")
        }

        fun index(parent: String, i: Int): String = step(parent, "i:$i")
        fun key(parent: String, i: Int): String = step(parent, "k:$i")
        fun value(parent: String, i: Int): String = step(parent, "v:$i")
        fun payload(parent: String): String = step(parent, "p")
        fun inner(parent: String): String = step(parent, "n")

        private fun step(parent: String, s: String): String =
            if (parent.isEmpty()) s else "$parent/$s"
    }

    companion object {
        /** Marks an elided branch in text output. */
        const val ELLIPSIS = "…"
    }
}

/**
 * Render the tree the way the language itself prints values.
 *
 * This reproduces `InspectNatives.render` exactly, including where it is
 * inconsistent: an ordered collection pads nothing (`Set{1, 2}`) while a map pads
 * inside its braces (`Map{ 1 = 2 }`). Reproduced rather than tidied, because that
 * output is what existing golden tests and every printed Mezze program already
 * say; changing it belongs in its own change, not smuggled in behind a new tree
 * type.
 *
 * The leaves are not re-formatted here — [WireValue.Scalar.text] was baked by
 * the builder, which is what keeps `to_str` and a REPL from drifting.
 */
fun WireValue.toText(): String = when (this) {
    is WireValue.Scalar -> text
    is WireValue.Newtype -> "$name.(${inner.toText()})"

    is WireValue.Record ->
        if (fields.isEmpty()) "{}"
        else fields.joinToString(", ", "{ ", " }") { "${it.name} = ${it.value.toText()}" }

    is WireValue.Variant ->
        if (payload == null) constructor else "$constructor ${payload.toText()}"

    is WireValue.Sequence -> {
        val (open, close) = when (kind) {
            WireValue.Sequence.Kind.LIST -> "List(" to ")"
            WireValue.Sequence.Kind.SEQ -> "[" to "]"
            WireValue.Sequence.Kind.SET -> "Set{" to "}"
            WireValue.Sequence.Kind.SORTED_SET -> "SortedSet{" to "}"
            WireValue.Sequence.Kind.DEQUE -> "Deque[" to "]"
            WireValue.Sequence.Kind.NON_EMPTY_LIST -> "NonEmptyList(" to ")"
            // An unknown kind is a peer newer than this code. Show the kind
            // rather than guessing a delimiter and printing something that
            // looks like a different collection.
            else -> "$kind(" to ")"
        }
        (items.map { it.toText() } + omittedNote(omitted)).joinToString(", ", open, close)
    }

    is WireValue.Mapping -> {
        val tag = when (kind) {
            WireValue.Mapping.Kind.MAP -> "Map"
            WireValue.Mapping.Kind.SORTED_MAP -> "SortedMap"
            else -> kind
        }
        if (entries.isEmpty() && omitted == 0) "$tag{}"
        else (entries.map { "${it.key.toText()} = ${it.value.toText()}" } + omittedNote(omitted))
            .joinToString(", ", "$tag{ ", " }")
    }

    is WireValue.Fn -> if (name != null) "<function:$name>" else "<function>"
    is WireValue.Elided -> WireValue.ELLIPSIS
}

private fun omittedNote(omitted: Int): List<String> =
    if (omitted > 0) listOf("${WireValue.ELLIPSIS}$omitted more") else emptyList()
