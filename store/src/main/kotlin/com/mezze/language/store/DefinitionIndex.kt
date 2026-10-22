package com.mezze.language.store

import com.mezze.language.ast.MezzeType
import com.mezze.language.typedast.Hash
import com.mezze.language.typedast.TypedAbilityDecl
import com.mezze.language.typedast.TypedAliasDef
import com.mezze.language.typedast.TypedEffectDecl
import com.mezze.language.typedast.TypedNewtypeDef
import com.mezze.language.typedast.TypedRecordDef
import com.mezze.language.typedast.TypedTypeDecl
import com.mezze.language.typedast.TypedVariantDef

/**
 * What a type, ability or effect declaration DEFINES, as data.
 *
 * A `let` has a signature, the type a caller sees. These declarations have none,
 * but they have something richer: the fields and constructors of a type, the
 * operations of an ability or effect. Before this, that existed only inside each
 * declaration's typed blob, so asking "what fields does `Point` have" meant
 * decoding the compiler's serialized AST. Code that generates code (derive,
 * codecs, mocks, docs) asks that question constantly.
 *
 * Every member keeps the order it was WRITTEN in, unlike a signature's record,
 * which is sorted: a derive that emits fields, or a doc page, must follow the
 * author.
 */
sealed interface Definition {
    /** Declared type parameters, in written order (`'a`, `'b`). */
    val typeParams: List<String>

    data class Record(
        override val typeParams: List<String>,
        val fields: List<Field>,
        val isOpen: Boolean,
    ) : Definition

    data class Variant(
        override val typeParams: List<String>,
        val constructors: List<Constructor>,
        val isOpen: Boolean,
    ) : Definition

    data class Newtype(
        override val typeParams: List<String>,
        val constructor: String,
        val wrapped: MezzeType,
    ) : Definition

    data class Alias(
        override val typeParams: List<String>,
        val aliased: MezzeType,
    ) : Definition

    data class Ability(
        /** Class parameters first; the receiver, when there is one, is [receiver]. */
        override val typeParams: List<String>,
        val receiver: String?,
        val associatedTypes: List<String>,
        val requires: List<Requirement>,
        val operations: List<Operation>,
    ) : Definition

    data class Effect(
        val associatedTypes: List<String>,
        val operations: List<Operation>,
    ) : Definition {
        override val typeParams: List<String> get() = emptyList()
    }

    data class Field(val name: String, val type: MezzeType)

    /** A constructor; [payload] is null for one that carries nothing (`None`). */
    data class Constructor(val name: String, val payload: MezzeType?)

    data class Operation(val name: String, val param: MezzeType, val result: MezzeType)

    /** An ability this one requires, by declaration hash, with its type arguments. */
    data class Requirement(val abilityHash: Hash, val typeArgs: List<MezzeType>)
}

/**
 * Definitions as rows, and back.
 *
 * Stored in tables of their own (`def_nodes` and its junctions), shaped like the
 * signature tables, and written and read by the same walker
 * ([TypeTreeWriter] / [TypeTreeReader]). Separate tables, not more rows in the
 * signature ones: an impl's target type is indexed under its structural hash in
 * those, and a definition sharing an owner hash with a signature would make
 * `getSignature` hand back rows that are not a type.
 *
 * Tree shape. The root is the declaration:
 *  - a record or variant is its RECORD / VARIANT node, with FIELD / CTOR children;
 *  - a newtype is a NEWTYPE node with a WRAPPED child;
 *  - an alias is an ALIAS node with an ALIASED child;
 *  - an ability or effect is an ABILITY / EFFECT node with OP children (each an
 *    arrow), ASSOC children, and for an ability RECEIVER and REQUIRES children.
 * Type parameters are TYPE_PARAM children of the root, in every case.
 */
object DefinitionIndex {

    fun flatten(decl: TypedTypeDecl): SignatureRows {
        val w = TypeTreeWriter(decl.hash, residual = null)
        val name = decl.name
        val root = when (val def = decl.definition) {
            is TypedRecordDef -> {
                val id = w.node(TypeNodeKind.RECORD, null, TypeSlot.ROOT, label = name, typeHash = decl.hash, isOpen = def.isOpen)
                def.fields.entries.forEachIndexed { i, (field, type) -> w.type(type, id, TypeSlot.FIELD, i, field) }
                id
            }
            is TypedVariantDef -> {
                val id = w.node(TypeNodeKind.VARIANT, null, TypeSlot.ROOT, label = name, typeHash = decl.hash, isOpen = def.isOpen)
                def.constructors.entries.forEachIndexed { i, (ctor, payload) ->
                    // A constructor that carries nothing is a CTOR slot with no child,
                    // the convention the signature tables already use.
                    if (payload == null) w.node(TypeNodeKind.VARIANT, id, TypeSlot.CTOR, i, slotLabel = ctor)
                    else w.type(payload, id, TypeSlot.CTOR, i, ctor)
                }
                id
            }
            is TypedNewtypeDef -> {
                val id = w.node(TypeNodeKind.NEWTYPE, null, TypeSlot.ROOT, label = def.constructor, typeHash = decl.hash)
                w.type(def.wrappedType, id, TypeSlot.WRAPPED, 0, null)
                id
            }
            is TypedAliasDef -> {
                val id = w.node(TypeNodeKind.ALIAS, null, TypeSlot.ROOT, label = name, typeHash = decl.hash)
                w.type(def.aliasedType, id, TypeSlot.ALIASED, 0, null)
                id
            }
        }
        decl.typeParams.forEachIndexed { i, p -> w.node(TypeNodeKind.VAR, root, TypeSlot.TYPE_PARAM, i, label = p.name) }
        return w.rows()
    }

    fun flatten(decl: TypedAbilityDecl): SignatureRows {
        val w = TypeTreeWriter(decl.hash, residual = null)
        val root = w.node(TypeNodeKind.ABILITY, null, TypeSlot.ROOT, label = decl.name, typeHash = decl.hash)
        decl.typeParams.forEachIndexed { i, p -> w.node(TypeNodeKind.VAR, root, TypeSlot.TYPE_PARAM, i, label = p.name) }
        decl.receiverTypeParam?.let { w.node(TypeNodeKind.VAR, root, TypeSlot.RECEIVER, label = it) }
        decl.associatedTypes.forEachIndexed { i, a -> w.node(TypeNodeKind.VAR, root, TypeSlot.ASSOC, i, label = a) }
        decl.dependencies.forEachIndexed { i, dep ->
            val id = w.node(TypeNodeKind.APP, root, TypeSlot.REQUIRES, i, typeHash = dep.abilityHash)
            dep.typeArgs.forEachIndexed { j, arg -> w.type(arg, id, TypeSlot.ARG, j, null) }
        }
        decl.operations.sortedBy { it.operationIndex }.forEachIndexed { i, op ->
            w.type(MezzeType.FunctionType(op.paramType, op.returnType), root, TypeSlot.OP, i, op.name)
        }
        return w.rows()
    }

    fun flatten(decl: TypedEffectDecl): SignatureRows {
        val w = TypeTreeWriter(decl.hash, residual = null)
        val root = w.node(TypeNodeKind.EFFECT, null, TypeSlot.ROOT, label = decl.name, typeHash = decl.hash)
        decl.associatedTypes.forEachIndexed { i, a -> w.node(TypeNodeKind.VAR, root, TypeSlot.ASSOC, i, label = a) }
        decl.operations.sortedBy { it.operationIndex }.forEachIndexed { i, op ->
            w.type(MezzeType.FunctionType(op.paramType, op.returnType), root, TypeSlot.OP, i, op.name)
        }
        return w.rows()
    }

    /**
     * Rebuild a definition from its rows. Fails loudly on rows that are not one:
     * a definition read back smaller than it was written would let a derive
     * silently skip a field.
     */
    fun rebuild(rows: SignatureRows, load: (com.mezze.language.typedast.Hash) -> SignatureRows): Definition {
        require(rows.nodes.isNotEmpty()) { "cannot rebuild a definition from zero nodes" }
        val r = TypeTreeReader(rows, load)
        r.requireTree()
        val root = r.root
        val kids = r.kidsOf(root.nodeId)
        fun inSlot(slot: TypeSlot) = kids.filter { it.slot == slot }
        fun names(slot: TypeSlot) = inSlot(slot).map { it.label ?: error("${slot.dbValue} node ${it.nodeId} has no name") }
        val typeParams = names(TypeSlot.TYPE_PARAM)
        fun ops() = inSlot(TypeSlot.OP).map { op ->
            val arrow = r.type(op) as? MezzeType.FunctionType
                ?: error("operation node ${op.nodeId} is not an arrow")
            Definition.Operation(op.slotLabel ?: error("operation node ${op.nodeId} has no name"), arrow.paramType, arrow.returnType)
        }
        return when (root.kind) {
            TypeNodeKind.RECORD -> Definition.Record(
                typeParams,
                inSlot(TypeSlot.FIELD).map { Definition.Field(it.slotLabel ?: error("field node ${it.nodeId} has no name"), r.type(it)) },
                root.isOpen,
            )
            TypeNodeKind.VARIANT -> Definition.Variant(
                typeParams,
                inSlot(TypeSlot.CTOR).map { ctor ->
                    val name = ctor.slotLabel ?: error("constructor node ${ctor.nodeId} has no name")
                    val payloadless = ctor.kind == TypeNodeKind.VARIANT && r.kidsOf(ctor.nodeId).isEmpty()
                    Definition.Constructor(name, if (payloadless) null else r.type(ctor))
                },
                root.isOpen,
            )
            TypeNodeKind.NEWTYPE -> Definition.Newtype(
                typeParams,
                root.label ?: error("newtype definition ${root.ownerHash.hex} has no constructor"),
                r.type(r.kid(root.nodeId, TypeSlot.WRAPPED) ?: error("newtype definition ${root.ownerHash.hex} wraps nothing")),
            )
            TypeNodeKind.ALIAS -> Definition.Alias(
                typeParams,
                r.type(r.kid(root.nodeId, TypeSlot.ALIASED) ?: error("alias definition ${root.ownerHash.hex} has no target")),
            )
            TypeNodeKind.ABILITY -> Definition.Ability(
                typeParams = typeParams,
                receiver = names(TypeSlot.RECEIVER).singleOrNull(),
                associatedTypes = names(TypeSlot.ASSOC),
                requires = inSlot(TypeSlot.REQUIRES).map { req ->
                    Definition.Requirement(
                        req.typeHash ?: error("requirement node ${req.nodeId} names no ability"),
                        r.kidsOf(req.nodeId).map { r.type(it) },
                    )
                },
                operations = ops(),
            )
            TypeNodeKind.EFFECT -> Definition.Effect(names(TypeSlot.ASSOC), ops())
            TypeNodeKind.FN, TypeNodeKind.APP, TypeNodeKind.VAR, TypeNodeKind.REF, TypeNodeKind.INT, TypeNodeKind.FLOAT,
            TypeNodeKind.STR, TypeNodeKind.CHAR, TypeNodeKind.FRAC, TypeNodeKind.BYTES ->
                error("root of ${root.ownerHash.hex} is a ${root.kind.dbValue}, which is not a definition")
        }
    }
}
