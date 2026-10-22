package com.mezze.language.store

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.RowVar
import com.mezze.language.ast.SignatureNormalizer
import com.mezze.language.ast.TypeIdentity
import com.mezze.language.ast.TypeVar
import com.mezze.language.typedast.Hash

/**
 * A declaration's signature, as TABLES.
 *
 * The first version of this index stored three TEXT columns on `decls`: the
 * signature as a rendered string, and its effects and ability constraints as
 * JSON arrays. Every question you actually want to ask then becomes string
 * surgery — and a rendered signature is a dead end, because you cannot turn
 * `{ xs: [Int] } -> Int` back into a type to unify against. It could only ever
 * be displayed or substring-matched, and substring matching is the worse of the
 * two failure modes: `LIKE '%Log%'` also finds `Logger`, and a search that
 * quietly over-matches gives the caller no way to tell.
 *
 * So the shape is stored as what it is — a tree — in adjacency-list form, one
 * row per node, with effects and constraints as junction rows keyed on the node
 * they belong to. Structure is queryable by join, identity is queryable by hash,
 * and [rebuild] turns the rows back into a [MezzeType].
 *
 * ## What is indexed, and what is not
 *
 * Indexed: the shape and the identity — arrows with their effect rows and
 * constraints, record fields, variant constructors, applied types with their
 * content hashes, type variables, primitives, newtypes. That is what "what does
 * this function look like" means.
 *
 * NOT indexed, deliberately: `ascriptionWall` (whether an author wrote a closed
 * inline variant as a hard wall), `caughtByAncestor` (which effects an enclosing
 * function absorbs), and an effect instance's associated-type bindings. These are
 * solver and author-ascription bookkeeping rather than signature shape, and the
 * full type remains available from the declaration's own stored blob, which is
 * keyed by the same hash. [rebuild] is therefore a faithful reconstruction of the
 * INDEXED PROJECTION, not of every field — and `SignatureIndexRoundTripTest`
 * states that boundary as tests rather than leaving it as a comment.
 */

/** Which structural position a node occupies under its parent. */
enum class TypeSlot(val dbValue: String) {
    /** The whole signature. Exactly one node per decl has this. */
    ROOT("root"),
    PARAM("param"),
    RETURN("return"),
    /** A record field; `label` is the field name. */
    FIELD("field"),
    /** A variant constructor's payload; `label` is the constructor name. */
    CTOR("ctor"),
    /** An argument of an applied type; `ordinal` is its position. */
    ARG("arg"),
    /** A newtype's wrapped type. */
    WRAPPED("wrapped"),

    // Slots that only DEFINITIONS use (`DefinitionIndex`); a signature never has them.

    /** A declared type parameter; `label` is its name, `ordinal` its position. */
    TYPE_PARAM("type_param"),
    /** An ability's receiver parameter, the one after `for`; `label` is its name. */
    RECEIVER("receiver"),
    /** An associated type an ability or effect declares; `label` is its name. */
    ASSOC("assoc"),
    /** An ability another ability requires; an APP node naming it by hash. */
    REQUIRES("requires"),
    /** An operation of an ability or effect; `slotLabel` is its name. */
    OP("op"),
    /** A type alias's target. */
    ALIASED("aliased");

    companion object {
        private val byDb = entries.associateBy { it.dbValue }
        fun fromDb(value: String): TypeSlot = byDb[value]
            ?: error("unknown type-node slot '$value' — schema and TypeSlot disagree")
    }
}

/**
 * What kind of type a node is.
 *
 * A payload-less variant constructor is represented by its parent's CTOR entry
 * with no child, which is why there is no "nothing" kind.
 */
enum class TypeNodeKind(val dbValue: String) {
    FN("fn"),
    RECORD("record"),
    VARIANT("variant"),
    /** An applied type; `label` is the constructor, `typeHash` its identity. */
    APP("app"),
    /** A type variable; `label` is its name. */
    VAR("var"),
    NEWTYPE("newtype"),
    INT("int"),
    FLOAT("float"),
    STR("str"),
    CHAR("char"),
    FRAC("frac"),
    BYTES("bytes"),

    // Definition roots (`DefinitionIndex`). Not types: the type reader refuses them.

    ALIAS("alias"),

    /**
     * A declared record or variant inside a larger type, by reference: `label` is
     * its name, `typeHash` its identity, and `refOwner` the key its structure is
     * stored under, once, however many signatures mention it.
     */
    REF("ref"),
    ABILITY("ability"),
    EFFECT("effect");

    companion object {
        private val byDb = entries.associateBy { it.dbValue }
        fun fromDb(value: String): TypeNodeKind = byDb[value]
            ?: error("unknown type-node kind '$value' — schema and TypeNodeKind disagree")
    }
}

/**
 * One node of one declaration's signature tree.
 *
 * [nodeId] is assigned in pre-order with the root at 0, so a node's id is stable
 * for a given type and the tree can be rebuilt by grouping on [parentId].
 */
data class TypeNodeRow(
    /**
     * WHOSE type this is. A declaration's content hash when the tree is that
     * declaration's signature; a TYPE's own structural hash when the tree is a
     * standalone type — an ability impl's target, say.
     *
     * Named `ownerHash` rather than `declHash` because it is not always a decl:
     * reading it as one would send a query joining to `decls` and finding nothing,
     * which is exactly the dead end `ability_impls.target_type` already was.
     */
    val ownerHash: Hash,
    val nodeId: Int,
    /** Null for the root only. */
    val parentId: Int?,
    val slot: TypeSlot,
    /** Position within [slot]; 0 unless the slot repeats (ARG, FIELD, CTOR). */
    val ordinal: Int,
    /**
     * The name this node carries WITHIN its parent: a record field name, or a
     * variant constructor name. Null in every other slot.
     *
     * Separate from [label] because they are different facts about different
     * types, and one column holding both is a bug rather than a saving: the
     * child's own name overwrote the field name it was stored under, so
     * `{ x: 'a }` came back as `{ 'a: 'a }`.
     */
    val slotLabel: String? = null,
    val kind: TypeNodeKind,
    /**
     * The node's OWN name: a type variable's name, an applied type's
     * constructor, a newtype's name, or a declared record/variant's name.
     */
    val label: String? = null,
    /** Content hash of the referenced declaration — nominal identity, not a name. */
    val typeHash: Hash? = null,
    /**
     * For RECORD/VARIANT: the row is open (`{ a: Int, ... }`).
     * For FN: the effect row is polymorphic (`<Log, ...>`).
     * Openness is part of the type: a closed record is a different type from an
     * open one, not a more precise rendering of it.
     */
    val isOpen: Boolean = false,
    /** FN only: the effect row carries the `*` performed marker. */
    val performed: Boolean = false,
    /**
     * FN only: this arrow's effect row is the declaration's RESIDUAL row — what a
     * caller is left holding after applying every argument. For a curried
     * signature that is the innermost arrow, not the outermost, which is why this
     * is marked at write time rather than guessed by a query.
     */
    val isResidual: Boolean = false,
    /** REF only: the owner its structure's rows are stored under (see [TypeNodeKind.REF]). */
    val refOwner: Hash? = null,
)

/** One effect on one arrow's effect row. */
data class TypeNodeEffectRow(
    val ownerHash: Hash,
    val nodeId: Int,
    val effectName: String,
    /** The effect declaration's content hash, when known. */
    val effectHash: Hash? = null,
)

/** One ability constraint on one arrow (`with Ord 'a`). */
data class TypeNodeConstraintRow(
    val ownerHash: Hash,
    val nodeId: Int,
    val abilityName: String,
    val abilityHash: Hash? = null,
    /** Written order, so the constraint list rebuilds as written. */
    val ordinal: Int = 0,
)

/** A signature as rows: the tree plus its effect and constraint junctions. */
data class SignatureRows(
    val nodes: List<TypeNodeRow>,
    val effects: List<TypeNodeEffectRow>,
    val constraints: List<TypeNodeConstraintRow>,
    /**
     * The structures this tree's REF nodes point at, by their key: written once,
     * and only if not already stored — a key is the structure's content, so a
     * stored one is the same one.
     */
    val shared: Map<Hash, SignatureRows> = emptyMap(),
) {
    val isEmpty: Boolean get() = nodes.isEmpty()

    companion object {
        val EMPTY = SignatureRows(emptyList(), emptyList(), emptyList())
    }
}

object SignatureIndex {

    /**
     * Flatten [type] into rows owned by [ownerHash].
     *
     * One walker, because six copies of a type walk is how this codebase's
     * annotation conversion went wrong — see the T-1 consolidation.
     */
    fun flatten(ownerHash: Hash, type: MezzeType): SignatureRows {
        // The arrow whose effect row a caller is left holding. Taken from the type
        // itself rather than re-derived here, so the index and
        // `FunctionType.residualEffects` cannot disagree about which arrow is last.
        // Compared by identity below, which is why it is resolved once up front.
        val residual: MezzeType.FunctionType? = (type as? MezzeType.FunctionType)?.residualArrow
        val writer = TypeTreeWriter(ownerHash, residual)
        writer.type(type, null, TypeSlot.ROOT, 0, null)
        return writer.rows()
    }

    /**
     * Rebuild the indexed projection of a signature from its rows.
     *
     * Fails loudly on rows that cannot describe a type — a missing root, a
     * dangling parent, an arrow without both halves. A signature index that
     * silently returns a partial type would hand every consumer something that
     * unifies more readily than the real signature, which is the whole failure
     * mode this index exists to avoid.
     */
    fun rebuild(rows: SignatureRows, load: (Hash) -> SignatureRows): MezzeType {
        require(rows.nodes.isNotEmpty()) { "cannot rebuild a signature from zero nodes" }
        val reader = TypeTreeReader(rows, load)
        reader.requireTree()
        return reader.type(reader.root)
    }

    /**
     * The canonical display form of a stored signature, or null when the
     * declaration has none indexed.
     *
     * The one place rows become text. Going through [rebuild] rather than storing
     * a rendering is the point of the whole change: the type is the stored fact
     * and the string is derived from it, so the two can never disagree.
     */
    fun render(rows: SignatureRows, load: (Hash) -> SignatureRows): String? =
        if (rows.isEmpty) null else SignatureNormalizer.render(rebuild(rows, load))
}

/**
 * Writes type trees as rows: the one walker, shared by signatures and by
 * definitions (`DefinitionIndex`). Six copies of a type walk is how this
 * codebase's annotation conversion went wrong; a second one here would start
 * the same drift.
 */
internal class TypeTreeWriter(
    private val ownerHash: Hash,
    /** The arrow whose row is the residual one; null when the tree is not a signature. */
    private val residual: MezzeType.FunctionType?,
    /** Structures written once and referenced (see [TypeNodeKind.REF]), shared by every writer of one flatten. */
    private val shared: MutableMap<Hash, SignatureRows> = LinkedHashMap(),
    /** Each structure's key, by the very type object, so a type met twice is keyed once. */
    private val keys: java.util.IdentityHashMap<MezzeType, Hash> = java.util.IdentityHashMap(),
) {
    private val nodes = mutableListOf<TypeNodeRow>()
    private val effects = mutableListOf<TypeNodeEffectRow>()
    private val constraints = mutableListOf<TypeNodeConstraintRow>()

    /** A node that is not itself a type — a definition's root, a type parameter. Returns its id. */
    fun node(
        kind: TypeNodeKind,
        parentId: Int?,
        slot: TypeSlot,
        ordinal: Int = 0,
        slotLabel: String? = null,
        label: String? = null,
        typeHash: Hash? = null,
        isOpen: Boolean = false,
    ): Int {
        val id = nodes.size
        nodes.add(
            TypeNodeRow(
                ownerHash = ownerHash, nodeId = id, parentId = parentId, slot = slot,
                ordinal = ordinal, slotLabel = slotLabel, kind = kind, label = label,
                typeHash = typeHash, isOpen = isOpen,
            )
        )
        return id
    }

    fun type(node: MezzeType, parentId: Int?, slot: TypeSlot, ordinal: Int, slotLabel: String?): Int {
        val id = nodes.size
        fun add(
            kind: TypeNodeKind,
            nodeLabel: String? = null,
            typeHash: Hash? = null,
            isOpen: Boolean = false,
            performed: Boolean = false,
            isResidual: Boolean = false,
        ) {
            nodes.add(
                TypeNodeRow(
                    ownerHash = ownerHash, nodeId = id, parentId = parentId, slot = slot,
                    ordinal = ordinal, slotLabel = slotLabel, kind = kind, label = nodeLabel,
                    typeHash = typeHash, isOpen = isOpen, performed = performed,
                    isResidual = isResidual,
                )
            )
        }

        // A declared record or variant below the root is stored once, by content,
        // and referenced: written inline it was copied whole into every signature
        // that mentions it — 25,000 rows for each function over `std::syntax`'s
        // `Expr`, and more for each macro.
        if (parentId != null && (node is MezzeType.RecordType || node is MezzeType.VariantType)) {
            val identity = when (node) {
                is MezzeType.RecordType -> node.identity
                is MezzeType.VariantType -> node.identity
                else -> null
            }
            val declared = identity?.hashOrNull()
            if (identity != null && declared != null) {
                add(TypeNodeKind.REF, nodeLabel = identity.nameOrNull(), typeHash = declared)
                nodes[id] = nodes[id].copy(refOwner = keyOf(node))
                return id
            }
        }

        when (node) {
            is MezzeType.FunctionType -> {
                val row = node.effects
                add(
                    TypeNodeKind.FN,
                    isOpen = row?.rowVar != null,
                    performed = row?.performed == true,
                    isResidual = node === residual,
                )
                row?.let { r ->
                    // allEffectNames is the right set: it unions bare effects
                    // with instanced ones and excludes the open-row tail, which
                    // is a variable rather than an effect.
                    for (name in r.allEffectNames) {
                        effects.add(TypeNodeEffectRow(ownerHash, id, name, r.effectHashes[name]))
                    }
                }
                node.abilityConstraints.forEachIndexed { i, c ->
                    constraints.add(
                        TypeNodeConstraintRow(ownerHash, id, c.abilityName, c.abilityHash, i)
                    )
                }
                type(node.paramType, id, TypeSlot.PARAM, 0, null)
                type(node.returnType, id, TypeSlot.RETURN, 0, null)
            }

            is MezzeType.RecordType -> {
                add(TypeNodeKind.RECORD, nodeLabel = node.identity.nameOrNull(),
                    typeHash = node.identity.hashOrNull(), isOpen = node.rowVar != null)
                // Sorted, so one type produces one row set regardless of the
                // order a map happened to iterate in.
                node.fields.entries.sortedBy { it.key }.forEachIndexed { i, (fieldName, fieldType) ->
                    type(fieldType, id, TypeSlot.FIELD, i, fieldName)
                }
            }

            is MezzeType.VariantType -> {
                add(TypeNodeKind.VARIANT, nodeLabel = node.identity.nameOrNull(),
                    typeHash = node.identity.hashOrNull(), isOpen = node.rowVar != null)
                node.constructors.entries.sortedBy { it.key }.forEachIndexed { i, (ctorName, payload) ->
                    // A payload-less constructor is a CTOR slot with no child.
                    if (payload == null) {
                        nodes.add(
                            TypeNodeRow(
                                ownerHash = ownerHash, nodeId = nodes.size, parentId = id,
                                slot = TypeSlot.CTOR, ordinal = i, slotLabel = ctorName,
                                kind = TypeNodeKind.VARIANT,
                            )
                        )
                    } else {
                        type(payload, id, TypeSlot.CTOR, i, ctorName)
                    }
                }
            }

            is MezzeType.TypeApplication -> {
                add(TypeNodeKind.APP, nodeLabel = node.constructor, typeHash = node.identity.hashOrNull())
                node.args.forEachIndexed { i, arg -> type(arg, id, TypeSlot.ARG, i, null) }
            }

            is MezzeType.NewtypeType -> {
                add(TypeNodeKind.NEWTYPE, nodeLabel = node.name, typeHash = node.identity.hashOrNull())
                type(node.wrappedType, id, TypeSlot.WRAPPED, 0, null)
            }

            is MezzeType.VarType -> add(TypeNodeKind.VAR, nodeLabel = node.variable.name)
            is MezzeType.IntType -> add(TypeNodeKind.INT)
            is MezzeType.FloatType -> add(TypeNodeKind.FLOAT)
            is MezzeType.StringType -> add(TypeNodeKind.STR)
            is MezzeType.CharType -> add(TypeNodeKind.CHAR)
            is MezzeType.FracType -> add(TypeNodeKind.FRAC)
            is MezzeType.BytesType -> add(TypeNodeKind.BYTES)

            // An effect row is reached as a FunctionType's `effects`, never as
            // a type in its own right, and is stored in the junction table.
            is MezzeType.EffectRow -> add(TypeNodeKind.VAR, nodeLabel = null)
        }
        return id
    }

    fun rows(): SignatureRows = SignatureRows(nodes.toList(), effects.toList(), constraints.toList(), shared.toMap())

    /** The key [node]'s structure is stored under: a hash of its rows, so equal structures share one. */
    private fun keyOf(node: MezzeType): Hash = keys.getOrPut(node) {
        val sub = TypeTreeWriter(PENDING, null, shared, keys)
        sub.type(node, null, TypeSlot.ROOT, 0, null)
        val rows = sub.rows()
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        fun put(vararg parts: Any?) { parts.forEach { digest.update((it?.toString() ?: "\u0000").toByteArray()); digest.update(0) } }
        for (n in rows.nodes) put(n.nodeId, n.parentId, n.slot.dbValue, n.ordinal, n.slotLabel, n.kind.dbValue,
            n.label, n.typeHash?.hex, n.isOpen, n.performed, n.refOwner?.hex)
        for (e in rows.effects) put("e", e.nodeId, e.effectName, e.effectHash?.hex)
        for (c in rows.constraints) put("c", c.nodeId, c.abilityName, c.abilityHash?.hex, c.ordinal)
        val key = Hash(digest.digest().joinToString("") { "%02x".format(it) })
        shared[key] = SignatureRows(
            rows.nodes.map { it.copy(ownerHash = key) },
            rows.effects.map { it.copy(ownerHash = key) },
            rows.constraints.map { it.copy(ownerHash = key) },
        )
        key
    }

    private companion object {
        /** The owner a structure is written under until its key is known. */
        val PENDING = Hash("0".repeat(64))
    }
}

/**
 * Reads type trees back from rows: the inverse of [TypeTreeWriter], shared the
 * same way. One reader holds one variable table, so every occurrence of `'a` in
 * one tree — a signature, or a whole definition — is the same variable.
 */
internal class TypeTreeReader(
    private val rows: SignatureRows,
    /** The rows stored under a key, for a REF node's structure. */
    private val load: (Hash) -> SignatureRows,
    // ONE TypeVar per variable NAME across the whole type, structures read
    // through a REF included: `{ b: Box 'a } -> 'a` has one `'a`.
    val vars: MutableMap<String, TypeVar> = mutableMapOf(),
) {
    val byId = rows.nodes.associateBy { it.nodeId }
    val children = rows.nodes.filter { it.parentId != null }.groupBy { it.parentId!! }
    val effectsByNode = rows.effects.groupBy { it.nodeId }
    val constraintsByNode = rows.constraints.groupBy { it.nodeId }

    // ONE TypeVar per variable NAME, for this whole signature. `TypeVar`
    // carries an id, so minting per occurrence makes `{x: 'a} -> 'a` rebuild
    // as two unrelated variables — it stops being the identity function and
    // starts unifying with things it must not: `{x: 'a} -> Deque 'a` matched a
    // search for `{x: 'a} -> 'a`, which the occurs check exists to reject.
    // Instance seven of this codebase's most-repeated defect, introduced here.

    val root: TypeNodeRow = rows.nodes.singleOrNull { it.parentId == null }
        ?: error(
            "a signature must have exactly one root node, found " +
                "${rows.nodes.count { it.parentId == null }} for ${rows.nodes.first().ownerHash.hex}"
        )

    fun kidsOf(id: Int) = children[id].orEmpty().sortedBy { it.ordinal }
    fun kid(id: Int, slot: TypeSlot): TypeNodeRow? = children[id].orEmpty().firstOrNull { it.slot == slot }

    fun type(row: TypeNodeRow): MezzeType = when (row.kind) {
        TypeNodeKind.FN -> {
            val param = kid(row.nodeId, TypeSlot.PARAM)
                ?: error("arrow node ${row.nodeId} has no parameter")
            val ret = kid(row.nodeId, TypeSlot.RETURN)
                ?: error("arrow node ${row.nodeId} has no return type")
            val effectRows = effectsByNode[row.nodeId].orEmpty()
            MezzeType.FunctionType(
                paramType = type(param),
                returnType = type(ret),
                effects = if (effectRows.isEmpty() && !row.isOpen && !row.performed) null else {
                    MezzeType.EffectRow.simple(
                        effects = effectRows.map { it.effectName }.toSet(),
                        rowVar = if (row.isOpen) RowVar.userOpen() else null,
                        performed = row.performed,
                        effectHashes = effectRows.mapNotNull { e -> e.effectHash?.let { e.effectName to it } }.toMap(),
                    )
                },
                abilityConstraints = constraintsByNode[row.nodeId].orEmpty()
                    .sortedBy { it.ordinal }
                    .map { c ->
                        MezzeType.FunctionAbilityConstraint(
                            abilityName = c.abilityName,
                            // Type arguments are not indexed — see the file header.
                            typeArgs = emptyList(),
                            abilityHash = c.abilityHash,
                        )
                    },
            )
        }

        TypeNodeKind.RECORD -> MezzeType.RecordType(
            fields = kidsOf(row.nodeId).associate { field ->
                (field.slotLabel ?: error("record field node ${field.nodeId} has no name")) to type(field)
            },
            rowVar = if (row.isOpen) RowVar.userOpen() else null,
            identity = identityOf(row),
        )

        TypeNodeKind.VARIANT -> {
            // A VARIANT node in a CTOR slot with no children of its own is a
            // payload-less constructor rather than a nested variant.
            val kids = kidsOf(row.nodeId)
            if (row.slot == TypeSlot.CTOR && kids.isEmpty()) {
                error("unreachable: payload-less constructors are read by their parent")
            }
            MezzeType.VariantType(
                constructors = kids.associate { ctor ->
                    val name = ctor.slotLabel
                        ?: error("variant constructor node ${ctor.nodeId} has no name")
                    val payloadless = ctor.kind == TypeNodeKind.VARIANT &&
                        ctor.slot == TypeSlot.CTOR &&
                        children[ctor.nodeId].isNullOrEmpty()
                    name to if (payloadless) null else type(ctor)
                },
                rowVar = if (row.isOpen) RowVar.userOpen() else null,
                identity = identityOf(row),
            )
        }

        TypeNodeKind.APP -> MezzeType.TypeApplication(
            constructor = row.label ?: error("applied-type node ${row.nodeId} has no constructor"),
            args = kidsOf(row.nodeId).map { type(it) },
            identity = identityOf(row),
        )

        TypeNodeKind.NEWTYPE -> {
            val name = row.label ?: error("newtype node ${row.nodeId} has no name")
            MezzeType.NewtypeType(
                name = name,
                constructor = name,
                wrappedType = type(
                    kid(row.nodeId, TypeSlot.WRAPPED)
                        ?: error("newtype node ${row.nodeId} wraps nothing")
                ),
                identity = identityOf(row),
            )
        }

        // An unnamed variable stays distinct per occurrence: there is no name
        // to share by, and inventing sharing would be the opposite error.
        TypeNodeKind.VAR -> MezzeType.VarType(
            row.label?.let { name -> vars.getOrPut(name) { TypeVar.freshWithName(name) } }
                ?: TypeVar.fresh()
        )

        TypeNodeKind.REF -> {
            val key = row.refOwner ?: error("reference node ${row.nodeId} of ${row.ownerHash.hex} names no structure")
            val stored = rows.shared[key] ?: load(key)
            require(stored.nodes.isNotEmpty()) {
                "the structure ${key.hex} that node ${row.nodeId} of ${row.ownerHash.hex} refers to is not stored"
            }
            TypeTreeReader(stored, load, vars).let { it.requireTree(); it.type(it.root) }
        }

        TypeNodeKind.INT -> MezzeType.IntType
        TypeNodeKind.FLOAT -> MezzeType.FloatType
        TypeNodeKind.STR -> MezzeType.StringType
        TypeNodeKind.CHAR -> MezzeType.CharType
        TypeNodeKind.FRAC -> MezzeType.FracType
        TypeNodeKind.BYTES -> MezzeType.BytesType

        // The roots of DEFINITIONS (`DefinitionIndex`), never part of a type.
        // Reaching one here means a definition's rows were read as a signature.
        TypeNodeKind.ALIAS, TypeNodeKind.ABILITY, TypeNodeKind.EFFECT ->
            error("node ${row.nodeId} of ${row.ownerHash.hex} is a ${row.kind.dbValue} definition, not a type")
    }

    /** Fail unless every node hangs off the root. */
    fun requireTree() {
        // Every node must be reachable from the root: an orphan means the writer
        // and the reader disagree about the tree, and dropping it silently would
        // produce a smaller type than the one that was indexed.
        val reachable = mutableSetOf<Int>()
        fun mark(id: Int) {
            if (!reachable.add(id)) return
            children[id].orEmpty().forEach { mark(it.nodeId) }
        }
        mark(root.nodeId)
        val orphans = byId.keys - reachable
        require(orphans.isEmpty()) {
            "type-node rows ${orphans.sorted()} are unreachable from the root for " +
                "${root.ownerHash.hex}; the tree is not a tree"
        }
    }
}

/**
 * Rebuild a node's identity through THE canonical factory.
 *
 * There are three identities, not two: a declaration has a content hash, a
 * BUILTIN (`Bool`, `Seq`, `Map` — nothing to hash) has only a reserved name,
 * and anything else is anonymous. Reading only the hash collapsed the first two
 * together, so `Bool` — a builtin VARIANT — came back anonymous and rendered as
 * `?variant` in every signature that mentioned it.
 *
 * `derive` is where that decision already lives, so this asks it rather than
 * re-deciding and getting a different answer.
 */
internal fun identityOf(row: TypeNodeRow): TypeIdentity {
    val name = row.label ?: return TypeIdentity.Anonymous
    return TypeIdentity.derive(name, row.typeHash, allowBuiltin = true)
}

internal fun TypeIdentity.hashOrNull(): Hash? = (this as? TypeIdentity.Declared)?.hash

/**
 * The identity's NAME — display metadata, never an identity input (the hash is).
 * Covers Builtin as well as Declared: a builtin's name is the only signal it
 * has, so dropping it loses the type.
 */
internal fun TypeIdentity.nameOrNull(): String? = displayNameOrNull()

