package com.mezze.language.codebase

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeIdentity
import com.mezze.language.comptime.CodebaseHost
import com.mezze.language.comptime.HostValue
import com.mezze.language.comptime.HostValue.Bool
import com.mezze.language.comptime.HostValue.Companion.record
import com.mezze.language.comptime.HostValue.IntV
import com.mezze.language.comptime.HostValue.Opt
import com.mezze.language.comptime.HostValue.Seq
import com.mezze.language.comptime.HostValue.Str
import com.mezze.language.comptime.HostValue.Variant
import com.mezze.language.store.DeclKind
import com.mezze.language.store.Definition
import com.mezze.language.typedast.Hash

/**
 * [CodebaseQueries], answered in the shapes `std/codebase.mz` declares.
 *
 * Every type name, constructor and field below is one in that file. The two
 * must agree exactly, and the end-to-end tests (`CodebaseEffectTest`) decode
 * these answers with the Mezze types to prove it.
 */
class CodebaseHostAdapter(
    private val queries: CodebaseQueries,
    /**
     * Run once, before the first question is answered: bringing the store up to
     * date with the whole codebase is worth doing only for a build that asks.
     */
    prepare: () -> Unit = {},
) : CodebaseHost {

    private val prepared by lazy(prepare)
    private val q: CodebaseQueries get() { prepared; return queries }

    override fun resolve(module: String, name: String) = Opt(q.resolve(module, name)?.let { declRef(it) })
    override fun info(hash: String) = Opt(q.get(Hash(hash))?.let { declInfo(it) })
    override fun decls(module: String) = Seq(q.declsIn(module).map { declRef(it) })
    override fun modules(prefix: String) = Seq(q.modules(prefix).map { Str(it) })
    override fun moduleDoc(module: String) = HostValue.str(q.moduleDoc(module))
    override fun signature(hash: String) = Opt(q.signature(Hash(hash))?.let { typeShape(it) })
    override fun definition(hash: String) = Opt(q.definition(Hash(hash))?.let { definition(it) })
    override fun source(hash: String) = HostValue.str(q.source(Hash(hash)))
    override fun implsOf(abilityHash: String) = Seq(q.implsOf(Hash(abilityHash)).map { implInfo(it) })
    override fun implsFor(typeHash: String) = Seq(q.implsFor(Hash(typeHash)).map { implInfo(it) })
    override fun effectsOf(hash: String) = Seq(q.effectsOf(Hash(hash)).map { effectRef(it) })
    override fun performing(effectHash: String) = refs(q.performing(Hash(effectHash)))
    override fun requiring(abilityHash: String) = refs(q.requiring(Hash(abilityHash)))
    override fun performersOf(effectHash: String) = refs(q.performersOf(Hash(effectHash)))
    override fun callers(hash: String) = refs(q.callers(Hash(hash)))
    override fun callees(hash: String) = refs(q.callees(Hash(hash)))
    override fun references(hash: String) = refs(q.references(Hash(hash)))
    override fun usages(hash: String) = Seq(q.usages(Hash(hash)).map { span(it) })
    override fun ref(functionHash: String) = Opt(q.refOfFunction(Hash(functionHash))?.let { declRef(it) })
    override fun abilitiesOf(typeHash: String) = Seq(q.abilitiesOf(Hash(typeHash)).map { a ->
        record(
            "ability_decl" to Opt(a.ability?.let { declRef(it) }),
            "ability_hash" to Str(a.abilityHash.hex),
            "impl_hash" to Str(a.implHash.hex),
            "via" to Variant(when (a.via) {
                CodebaseQueries.Via.WRITTEN -> "Written"
                CodebaseQueries.Via.DERIVED -> "Derived"
                CodebaseQueries.Via.GENERIC -> "Generic"
            }, null),
        )
    })
    override fun search(query: HostValue) = refs(q.search(decodeQuery(query)))
    override fun syntax(hash: String) = Opt(q.written(Hash(hash))?.let(::syntaxDecl))
    override fun moduleSyntax(module: String) = Seq(q.writtenIn(module).map(::syntaxDecl))

    override fun occurrences(kind: String, symbol: String?): HostValue {
        val rows = q.occurrences(kind, symbol)
        // One encoder per declaration: its typed tree is read once, for all of
        // its occurrences' facts.
        val joins = HashMap<Hash, SyntaxFacts?>()
        return Seq(rows.mapNotNull { r ->
            val decl = q.ref(r.ownerHash) ?: return@mapNotNull null
            val join = joins.getOrPut(r.ownerHash) { q.written(r.ownerHash)?.let { factsOf(it) } }
            record(
                "decl" to declRef(decl),
                "kind" to Str(r.kind),
                "symbol" to HostValue.str(r.symbol),
                "span" to record(
                    "file" to HostValue.str(r.filePath),
                    "start_line" to IntV(r.startLine.toLong()),
                    "start_col" to IntV(r.startCol.toLong()),
                    "end_line" to IntV(r.endLine.toLong()),
                    "end_col" to IntV(r.endCol.toLong()),
                ),
                "facts" to (join?.factsAt(r.startLine, r.startCol, r.endLine, r.endCol) ?: Opt(null)),
            )
        })
    }

    private fun factsOf(w: CodebaseQueries.Written) = SyntaxFacts(::typeShape, ::declRef, q::reached, q::dispatched, w.typed)

    private fun syntaxDecl(w: CodebaseQueries.Written) =
        factsOf(w).encoder().decl(w.module, w.parsed, Opt(w.ref?.let(::declRef)))

    // ── reading a `DeclQuery` record ─────────────────────────────────────────

    private fun decodeQuery(v: HostValue): CodebaseQueries.Query {
        val fields = (v as? HostValue.Record)?.fields?.toMap()
            ?: throw IllegalStateException("std::codebase search: the query is not a record")
        fun field(name: String) = fields[name] ?: throw IllegalStateException("std::codebase search: the query has no `$name`")
        fun optStr(name: String): String? = when (val f = field(name)) {
            is HostValue.Opt -> (f.value as? Str)?.value
            else -> throw IllegalStateException("std::codebase search: `$name` is not an Option Str")
        }
        fun bool(name: String): Boolean = (field(name) as? Bool)?.value
            ?: throw IllegalStateException("std::codebase search: `$name` is not a Bool")
        val kind = (field("kind") as? HostValue.Opt)?.value?.let { k ->
            when ((k as? Variant)?.tag) {
                "LetDecl" -> DeclKind.LET
                "TypeDecl" -> DeclKind.TYPE
                "AbilityDecl" -> DeclKind.ABILITY
                "EffectDecl" -> DeclKind.EFFECT
                "ImplDecl" -> DeclKind.IMPL
                "PerformerDecl" -> DeclKind.PERFORMER
                else -> throw IllegalStateException("std::codebase search: unknown DeclKind $k")
            }
        }
        val order = when ((field("order") as? Variant)?.tag) {
            "ByName" -> CodebaseQueries.Order.BY_NAME
            "ByModule" -> CodebaseQueries.Order.BY_MODULE
            else -> throw IllegalStateException("std::codebase search: unknown DeclOrder")
        }
        val limit = ((field("limit") as? HostValue.Opt)?.value as? IntV)?.value?.toInt()
        return CodebaseQueries.Query(
            kind = kind,
            name = optStr("name"),
            namePrefix = optStr("name_prefix"),
            text = optStr("text"),
            modulePrefix = optStr("module_prefix"),
            publicOnly = bool("public_only"),
            tagged = optStr("tagged"),
            deprecatedOnly = bool("deprecated_only"),
            documentedOnly = bool("documented_only"),
            performs = optStr("performs")?.let { Hash(it) },
            pureOnly = bool("pure_only"),
            requires = optStr("requires")?.let { Hash(it) },
            mentions = optStr("mentions")?.let { Hash(it) },
            shape = optStr("shape"),
            unusedOnly = bool("unused_only"),
            uses = ((field("uses") as? HostValue.Opt)?.value as? HostValue.Record)?.fields?.toMap()?.let { u ->
                ((u["kind"] as? Str)?.value ?: throw IllegalStateException("std::codebase search: `uses` has no kind")) to
                    ((u["symbol"] as? HostValue.Opt)?.value as? Str)?.value
            },
            order = order,
            limit = limit,
        )
    }

    // ── the shapes in std/codebase.mz ────────────────────────────────────

    private fun refs(list: List<CodebaseQueries.DeclRef>) = Seq(list.map { declRef(it) })

    private fun declKind(kind: DeclKind): HostValue = Variant(
        when (kind) {
            DeclKind.LET -> "LetDecl"
            DeclKind.TYPE -> "TypeDecl"
            DeclKind.ABILITY -> "AbilityDecl"
            DeclKind.EFFECT -> "EffectDecl"
            DeclKind.PERFORMER -> "PerformerDecl"
            DeclKind.IMPL -> "ImplDecl"
        },
        null,
    )

    private fun declRef(r: CodebaseQueries.DeclRef) = record(
        "hash" to Str(r.hash.hex),
        "name" to Str(r.name),
        "module" to Str(r.module),
        "kind" to declKind(r.kind),
    )

    private fun span(s: CodebaseQueries.Span) = record(
        "file" to HostValue.str(s.file),
        "start_line" to IntV(s.startLine.toLong()),
        "start_col" to IntV(s.startCol.toLong()),
        "end_line" to IntV(s.endLine.toLong()),
        "end_col" to IntV(s.endCol.toLong()),
    )

    private fun declInfo(i: CodebaseQueries.DeclInfo) = record(
        "decl" to declRef(i.ref),
        "is_public" to Bool(i.isPublic),
        "doc" to HostValue.str(i.doc),
        "span" to span(i.span),
        "metadata" to HostValue.str(i.metadata),
        "origin" to Opt(i.origin?.let { Variant(
            when (it) {
                com.mezze.language.store.StoreTier.PROJECT -> "FromProject"
                com.mezze.language.store.StoreTier.DEPENDENCY -> "FromDependency"
                com.mezze.language.store.StoreTier.STDLIB -> "FromStdlib"
            }, null) }),
    )

    private fun implInfo(i: CodebaseQueries.ImplInfo) = record(
        "hash" to Str(i.hash.hex),
        "ability_decl" to Opt(i.ability?.let { declRef(it) }),
        "ability_hash" to Str(i.abilityHash.hex),
        "targets" to Seq(i.targets.map { Str(it.hex) }),
        "module" to HostValue.str(i.module),
    )

    private fun effectRef(e: CodebaseQueries.EffectRef) = record(
        "name" to Str(e.name),
        "decl" to Opt(e.decl?.let { declRef(it) }),
    )

    private fun identityName(id: TypeIdentity): HostValue = HostValue.str(id.displayNameOrNull())
    private fun identityHash(id: TypeIdentity): HostValue = HostValue.str((id as? TypeIdentity.Declared)?.hash?.hex)

    private fun field(name: String, type: MezzeType) = record("name" to Str(name), "ty" to typeShape(type, nested = true))

    private fun constructor(name: String, payload: MezzeType?) =
        record("name" to Str(name), "payload" to Opt(payload?.let { typeShape(it, nested = true) }))

    /**
     * A [MezzeType] as `TypeShape`.
     *
     * A declared record or variant is spelled out only as the whole [type]; inside
     * it, one is named — `TApp` with its name and hash — and `Codebase.definition`
     * gives its structure. Spelled out at every depth, a recursive type like
     * `std::syntax::Expr` was copied whole into the facts of every node of that
     * type, which ran a test out of memory.
     */
    fun typeShape(type: MezzeType, nested: Boolean = false): HostValue = when {
        nested && type is MezzeType.RecordType && type.identity is TypeIdentity.Declared -> declaredRef(type.identity)
        nested && type is MezzeType.VariantType && type.identity is TypeIdentity.Declared -> declaredRef(type.identity)
        else -> spelled(type)
    }

    private fun declaredRef(identity: TypeIdentity) = Variant("TApp", record(
        "name" to Str(identity.displayNameOrNull() ?: ""),
        "hash" to identityHash(identity),
        "args" to Seq(emptyList()),
    ))

    private fun spelled(type: MezzeType): HostValue = when (type) {
        MezzeType.IntType -> Variant("TInt", null)
        MezzeType.FloatType -> Variant("TFloat", null)
        MezzeType.StringType -> Variant("TStr", null)
        MezzeType.CharType -> Variant("TChar", null)
        MezzeType.FracType -> Variant("TFrac", null)
        MezzeType.BytesType -> Variant("TBytes", null)
        is MezzeType.VarType -> Variant("TVar", record("name" to Str(type.variable.name)))
        is MezzeType.RecordType -> Variant("TRecord", record(
            "name" to identityName(type.identity),
            "hash" to identityHash(type.identity),
            "fields" to Seq(type.fields.map { (k, v) -> field(k, v) }),
            "is_open" to Bool(type.isOpen),
        ))
        is MezzeType.VariantType -> Variant("TVariant", record(
            "name" to identityName(type.identity),
            "hash" to identityHash(type.identity),
            "constructors" to Seq(type.constructors.map { (k, v) -> constructor(k, v) }),
            "is_open" to Bool(type.isOpen),
        ))
        is MezzeType.TypeApplication -> Variant("TApp", record(
            "name" to Str(type.constructor),
            "hash" to identityHash(type.identity),
            "args" to Seq(type.args.map { typeShape(it, nested = true) }),
        ))
        is MezzeType.NewtypeType -> Variant("TNewtype", record(
            "name" to Str(type.name),
            "hash" to identityHash(type.identity),
            "wrapped" to typeShape(type.wrappedType, nested = true),
        ))
        is MezzeType.FunctionType -> Variant("TFn", record(
            "param" to typeShape(type.paramType, nested = true),
            "result" to typeShape(type.returnType, nested = true),
            "effects" to Seq(type.effects?.allEffectNames.orEmpty().sorted().map { name ->
                effectRef(CodebaseQueries.EffectRef(name, type.effects?.effectHashes?.get(name)?.let { q.ref(it) }))
            }),
            "requires" to Seq(type.abilityConstraints.map { c ->
                record("name" to Str(c.abilityName), "hash" to HostValue.str(c.abilityHash?.hex))
            }),
            "open_effects" to Bool(type.effects?.rowVar != null),
        ))
        // A row is reached as an arrow's `effects`, never as a type of its own.
        is MezzeType.EffectRow -> error("an effect row is not a type: $type")
    }

    private fun typeParams(names: List<String>) = Seq(names.map { Str(it) })

    private fun operation(op: Definition.Operation) = record(
        "name" to Str(op.name),
        "param" to typeShape(op.param),
        "result" to typeShape(op.result),
    )

    /** A [Definition] as `Definition`. */
    fun definition(def: Definition): HostValue = when (def) {
        is Definition.Record -> Variant("RecordDef", record(
            "type_params" to typeParams(def.typeParams),
            "fields" to Seq(def.fields.map { field(it.name, it.type) }),
            "is_open" to Bool(def.isOpen),
        ))
        is Definition.Variant -> Variant("VariantDef", record(
            "type_params" to typeParams(def.typeParams),
            "constructors" to Seq(def.constructors.map { constructor(it.name, it.payload) }),
            "is_open" to Bool(def.isOpen),
        ))
        is Definition.Newtype -> Variant("NewtypeDef", record(
            "type_params" to typeParams(def.typeParams),
            "constructor" to Str(def.constructor),
            "wrapped" to typeShape(def.wrapped),
        ))
        is Definition.Alias -> Variant("AliasDef", record(
            "type_params" to typeParams(def.typeParams),
            "aliased" to typeShape(def.aliased),
        ))
        is Definition.Ability -> Variant("AbilityDef", record(
            "type_params" to typeParams(def.typeParams),
            "receiver" to HostValue.str(def.receiver),
            "associated" to Seq(def.associatedTypes.map { Str(it) }),
            "requires" to Seq(def.requires.map { r ->
                record("ability_hash" to Str(r.abilityHash.hex), "args" to Seq(r.typeArgs.map { typeShape(it) }))
            }),
            "ops" to Seq(def.operations.map { operation(it) }),
        ))
        is Definition.Effect -> Variant("EffectDef", record(
            "associated" to Seq(def.associatedTypes.map { Str(it) }),
            "ops" to Seq(def.operations.map { operation(it) }),
        ))
    }
}
