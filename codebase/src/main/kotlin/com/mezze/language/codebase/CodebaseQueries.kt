package com.mezze.language.codebase

import com.mezze.language.ast.MezzeType
import com.mezze.language.store.DeclKind
import com.mezze.language.store.DeclRow
import com.mezze.language.store.Definition
import com.mezze.language.store.DefinitionIndex
import com.mezze.language.store.MezzeStore
import com.mezze.language.store.SignatureIndex
import com.mezze.language.store.Visibility
import com.mezze.language.typedast.Hash
import java.io.File

/**
 * The questions a program may ask about its own codebase, answered from a
 * [MezzeStore] — the abstraction, never an implementation of it.
 *
 * This is what the `Codebase` effect bottoms out in (introspection §9). Every
 * answer is keyed by declaration HASH: a name a person typed is resolved once, by
 * [resolve], and everything after that follows identities, so two declarations
 * that share a name are never confused.
 *
 * Every list comes back in one fixed order — module, then name, then hash — so
 * a build that asks the same question twice gets the same answer twice, and a
 * cached result stays valid.
 */
class CodebaseQueries(private val store: MezzeStore) {

    /** A declaration, by identity, with the names a person reads it by. */
    data class DeclRef(val hash: Hash, val name: String, val module: String, val kind: DeclKind)

    /** Where something is written: 1-based lines and columns, the end column inclusive. */
    data class Span(val file: String?, val startLine: Int, val startCol: Int, val endLine: Int, val endCol: Int)

    data class DeclInfo(
        val ref: DeclRef,
        val isPublic: Boolean,
        val doc: String?,
        val span: Span,
        /** The `#{…}` block written on the declaration, as raw JSON; null when there is none. */
        val metadata: String?,
        /** Whether it comes from this project, a dependency or the standard library. */
        val origin: com.mezze.language.store.StoreTier?,
    )

    /** An ability impl. [targets] are type hashes, receiver LAST. */
    data class ImplInfo(val hash: Hash, val ability: DeclRef?, val abilityHash: Hash, val targets: List<Hash>, val module: String?)

    /** An effect as it appears in a row: its name, and its declaration when known. */
    data class EffectRef(val name: String, val decl: DeclRef?)

    // ── looking things up ────────────────────────────────────────────────

    /** The declaration [name] in [module], or null. The one place a name becomes an identity. */
    fun resolve(module: String, name: String): DeclRef? =
        store.resolveName(module, name)?.let { ref(it) }

    /** The declaration with hash [hash], or null when the store has no row for it. */
    fun ref(hash: Hash): DeclRef? = store.findDeclByHash(hash)?.toRef()

    fun get(hash: Hash): DeclInfo? {
        val row = store.findDeclByHash(hash) ?: return null
        val module = store.getModule(row.modulePath)
        return DeclInfo(
            ref = row.toRef(),
            isPublic = store.getBlob(hash)?.visibility == Visibility.PUBLIC,
            doc = row.doc,
            span = Span(module?.filePath, row.startLine, row.startCol, row.endLine, row.endCol),
            metadata = row.metadata,
            origin = store.getModuleTier(row.modulePath),
        )
    }

    /** Every declaration in [module]. */
    fun declsIn(module: String): List<DeclRef> = store.getDeclsForModule(module).map { it.toRef() }.sorted()

    /** The module's own documentation (its leading `##` block), or null. */
    fun moduleDoc(module: String): String? = store.getModuleDoc(module)

    /** Every module whose path starts with [prefix] (all of them for an empty prefix). */
    fun modules(prefix: String = ""): List<String> =
        store.getAllModules().map { it.path }.filter { it.startsWith(prefix) }.distinct().sorted()

    // ── what a declaration is ────────────────────────────────────────────

    /** The type a caller sees, for a `let`; null for anything without one. */
    fun signature(hash: Hash): MezzeType? {
        val rows = store.getSignature(hash)
        return if (rows.isEmpty) null else SignatureIndex.rebuild(rows)
    }

    /** What a type, ability or effect defines; null for anything else. */
    fun definition(hash: Hash): Definition? {
        val rows = store.getDefinition(hash)
        return if (rows.isEmpty) null else DefinitionIndex.rebuild(rows)
    }

    /**
     * The declaration's source text, read from its file by its recorded span.
     * Null when the declaration has no file, or the file has changed so the span
     * no longer fits it.
     */
    fun source(hash: Hash): String? {
        val info = get(hash) ?: return null
        val file = info.span.file?.let { File(it) }?.takeIf { it.isFile } ?: return null
        val lines = file.readLines()
        val s = info.span
        if (s.startLine < 1 || s.endLine > lines.size || s.startLine > s.endLine) return null
        if (s.startLine == s.endLine) {
            val line = lines[s.startLine - 1]
            if (s.endCol > line.length) return null
            return line.substring(s.startCol - 1, s.endCol)
        }
        val first = lines[s.startLine - 1]
        val last = lines[s.endLine - 1]
        if (s.startCol - 1 > first.length || s.endCol > last.length) return null
        return buildString {
            append(first.substring(s.startCol - 1)).append('\n')
            for (i in s.startLine until s.endLine - 1) append(lines[i]).append('\n')
            append(last.substring(0, s.endCol))
        }
    }

    // ── abilities and effects ────────────────────────────────────────────

    /** Every impl of the ability declared at [abilityHash]. */
    fun implsOf(abilityHash: Hash): List<ImplInfo> =
        store.getAbilityImpls(abilityHash).map { implInfo(it.hash, it.abilityHash, it.module) }.sortedImpls()

    /**
     * Every impl WRITTEN for [type] as its receiver. [type] is either the
     * receiver's own type hash or — what a program holds — the DECLARATION of the
     * type (`Ticket`): a declared type's tree records that declaration as its
     * identity, which is how the two are matched.
     */
    fun implsFor(type: Hash): List<ImplInfo> =
        implRowsFor(type).map { implInfo(it.hash, it.abilityHash, it.module) }.sortedImpls()

    private fun implRowsFor(type: Hash): List<com.mezze.language.store.AbilityImplRow> {
        val byReceiver = store.findImplsForType(type)
        val byDeclaration = store.getAllAbilityImpls().filter { row ->
            val receiver = store.getImplTargets(row.hash).lastOrNull() ?: return@filter false
            store.getSignature(receiver).nodes.firstOrNull { it.parentId == null }?.typeHash == type
        }
        return (byReceiver + byDeclaration).distinctBy { it.hash }
    }

    /** The effects a call to [hash] leaves its caller holding (its residual row). */
    fun effectsOf(hash: Hash): List<EffectRef> {
        val rows = store.getSignature(hash)
        if (rows.isEmpty) return emptyList()
        val residual = rows.nodes.filter { it.isResidual }.map { it.nodeId }.toSet()
        return rows.effects.filter { it.nodeId in residual }
            .map { e -> EffectRef(e.effectName, e.effectHash?.let { ref(it) }) }
            .distinct()
            .sortedWith(compareBy({ it.name }, { it.decl?.hash?.hex }))
    }

    /** Declarations that perform the effect declared at [effectHash]. */
    fun performing(effectHash: Hash): List<DeclRef> = store.findDeclsPerforming(effectHash).map { it.toRef() }.sorted()

    /** Declarations that require the ability declared at [abilityHash]. */
    fun requiring(abilityHash: Hash): List<DeclRef> = store.findDeclsRequiring(abilityHash).map { it.toRef() }.sorted()

    /** The performers (handlers) of the effect declared at [effectHash]. */
    fun performersOf(effectHash: Hash): List<DeclRef> =
        store.getEffectPerformers(effectHash).mapNotNull { ref(it.hash) }.sorted()

    // ── the call graph ───────────────────────────────────────────────────
    //
    // From SOURCE references (`usages`), never from `edges`. The edge graph is
    // built from lowered code, where a small helper has been inlined into its
    // callers and has no inbound call left: "who calls `shout`" answered nothing
    // for a `twice` that calls it twice.

    /** Declarations [hash] calls directly, as written in its source. */
    fun callees(hash: Hash): List<DeclRef> =
        store.getUsagesFrom(hash).filter { it.isCall }.mapNotNull { ref(it.targetHash) }.distinct().sorted()

    /** Declarations whose source calls [hash] directly. */
    fun callers(hash: Hash): List<DeclRef> =
        store.getUsages(hash).filter { it.isCall }.mapNotNull { ref(it.fromHash) }.distinct().sorted()

    /** Declarations whose source mentions [hash] at all — calling it, or passing it as a value. */
    fun references(hash: Hash): List<DeclRef> =
        store.getUsages(hash).mapNotNull { ref(it.fromHash) }.distinct().sorted()

    /** Every place [hash] is written in source, in file and line order. */
    fun usages(hash: Hash): List<Span> =
        store.getUsages(hash)
            .map { Span(it.filePath, it.startLine, it.startCol, it.endLine, it.endCol) }
            .distinct()
            .sortedWith(compareBy({ it.file }, { it.startLine }, { it.startCol }))

    /**
     * A `let`'s body as the compiler typed it, from its stored blob; null for any
     * other declaration, or one stored without its typed tree.
     */
    fun body(hash: Hash): com.mezze.language.typedast.TypedExpr? {
        val blob = store.getBlob(hash) ?: return null
        if (blob.kind != com.mezze.language.store.BlobKind.FUNCTION) return null
        val typed = blob.typed ?: return null
        return com.mezze.language.store.StoreSerialization.deserializeTypedLetDecl(typed).value
    }

    // ── a function value, and what applies to a type ─────────────────────

    /**
     * The declaration whose function has [functionHash]. A call site may hold a
     * monomorphised copy, whose hash is not the declaration's; it maps back
     * through the store's specialisation table.
     */
    fun refOfFunction(functionHash: Hash): DeclRef? =
        ref(functionHash) ?: store.getMonoSpecialization(functionHash)?.let { ref(it.genericHash) }

    enum class Via { WRITTEN, DERIVED, GENERIC }

    data class AbilityInfo(val ability: DeclRef?, val abilityHash: Hash, val implHash: Hash, val via: Via)

    /**
     * Every ability that applies to the type [typeHash] (its hash or its
     * declaration, as for [implsFor]): impls whose
     * receiver is exactly it (written by a person, or derived by the compiler for
     * this codebase), and impls whose receiver is a bare type variable
     * (`impl Inspect for 'a`), which apply to every type.
     *
     * Not answered here: a generic impl over a CONTAINER (`impl Eq for ['a]`)
     * applies to `[Point]`, not to `Point`, so it is not an ability of `Point`.
     */
    fun abilitiesOf(typeHash: Hash): List<AbilityInfo> {
        val exact = implRowsFor(typeHash).map { row ->
            val derived = store.getBlob(row.hash)?.typed
                ?.let { com.mezze.language.store.StoreSerialization.deserializeTypedAbilityImpl(it).derived } == true
            AbilityInfo(ref(row.abilityHash), row.abilityHash, row.hash, if (derived) Via.DERIVED else Via.WRITTEN)
        }
        val generic = store.getAllAbilityImpls().filter { row ->
            val receiver = store.getImplTargets(row.hash).lastOrNull() ?: return@filter false
            store.getSignature(receiver).nodes.firstOrNull { it.parentId == null }?.kind == com.mezze.language.store.TypeNodeKind.VAR
        }.map { AbilityInfo(ref(it.abilityHash), it.abilityHash, it.hash, Via.GENERIC) }
        return (exact + generic).distinctBy { it.implHash }
            .sortedWith(compareBy({ it.ability?.module }, { it.ability?.name }, { it.implHash.hex }))
    }

    // ── search ───────────────────────────────────────────────────────────

    enum class Order { BY_NAME, BY_MODULE }

    /** What `std::codebase`'s `DeclQuery` asks for. Every filter that is set must hold. */
    data class Query(
        val kind: DeclKind? = null,
        val name: String? = null,
        val namePrefix: String? = null,
        val text: String? = null,
        val modulePrefix: String? = null,
        val publicOnly: Boolean = false,
        val tagged: String? = null,
        val deprecatedOnly: Boolean = false,
        val documentedOnly: Boolean = false,
        val performs: Hash? = null,
        val pureOnly: Boolean = false,
        val requires: Hash? = null,
        val mentions: Hash? = null,
        val shape: String? = null,
        val unusedOnly: Boolean = false,
        val order: Order = Order.BY_MODULE,
        val limit: Int? = null,
    )

    /**
     * Declarations matching [q]. The candidates come from the most selective
     * INDEXED filter set; the rest are checked against them in memory.
     */
    fun search(q: Query): List<DeclRef> {
        val indexed: List<List<DeclRow>> = listOfNotNull(
            q.performs?.let { store.findDeclsPerforming(it) },
            q.requires?.let { store.findDeclsRequiring(it) },
            q.mentions?.let { store.findDeclsMentioningType(it) },
            q.tagged?.let { store.findDeclsByMetadata(it) },
            if (q.deprecatedOnly) store.findDeclsByMetadata("deprecated") else null,
            q.shape?.let { s -> TypeSearch.search(store, s, limit = Int.MAX_VALUE).map { it.decl } },
        )
        val candidates: List<DeclRow> = indexed.minByOrNull { it.size }
            ?: q.modulePrefix?.let { p -> modules(p).flatMap { store.getDeclsForModule(it) } }
            ?: q.namePrefix?.let { store.searchDeclsByName(it) }
            ?: modules().flatMap { store.getDeclsForModule(it) }
        val inEvery = indexed.map { list -> list.map { it.hash }.toSet() }

        val found = candidates.distinctBy { it.hash }.filter { d ->
            inEvery.all { d.hash in it } &&
                (q.kind == null || d.declKind == q.kind) &&
                (q.name == null || d.declName == q.name) &&
                (q.namePrefix == null || d.declName.startsWith(q.namePrefix)) &&
                (q.text == null || d.declName.contains(q.text, ignoreCase = true) || d.doc?.contains(q.text, ignoreCase = true) == true) &&
                (q.modulePrefix == null || d.modulePath.startsWith(q.modulePrefix)) &&
                (!q.publicOnly || store.getBlob(d.hash)?.visibility == Visibility.PUBLIC) &&
                (!q.documentedOnly || !d.doc.isNullOrBlank()) &&
                (!q.pureOnly || isPure(d.hash)) &&
                (!q.unusedOnly || store.getUsages(d.hash).none { it.fromHash != d.hash })
        }.map { it.toRef() }

        val ordered = when (q.order) {
            Order.BY_NAME -> found.sortedWith(compareBy({ it.name }, { it.module }, { it.hash.hex }))
            Order.BY_MODULE -> found.sorted()
        }
        return q.limit?.let { ordered.take(it) } ?: ordered
    }

    /** A declaration with a signature whose residual row performs nothing. */
    private fun isPure(hash: Hash): Boolean {
        val rows = store.getSignature(hash)
        if (rows.isEmpty) return false
        val residual = rows.nodes.filter { it.isResidual }.map { it.nodeId }.toSet()
        return rows.effects.none { it.nodeId in residual } && rows.nodes.none { it.isResidual && it.isOpen }
    }

    // ── internals ────────────────────────────────────────────────────────

    private fun DeclRow.toRef() = DeclRef(hash, declName, modulePath, declKind)

    private fun implInfo(hash: Hash, abilityHash: Hash, module: String?) =
        ImplInfo(hash, ref(abilityHash), abilityHash, store.getImplTargets(hash), module)

    private fun List<ImplInfo>.sortedImpls() =
        sortedWith(compareBy({ it.ability?.module }, { it.ability?.name }, { it.module }, { it.hash.hex }))

    private fun List<DeclRef>.sorted() = sortedWith(compareBy({ it.module }, { it.name }, { it.hash.hex }))
}
