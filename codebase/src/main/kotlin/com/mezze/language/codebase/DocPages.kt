package com.mezze.language.codebase

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeIdentity
import com.mezze.language.store.DeclKind
import com.mezze.language.store.DeclRow
import com.mezze.language.store.EdgeKind
import com.mezze.language.store.EffectPerformerRow
import com.mezze.language.store.MezzeStore
import com.mezze.language.store.SignatureIndex
import com.mezze.language.store.UsageRow

/**
 * Everything the store knows about ONE declaration, in one value.
 *
 * Each field was already reachable, and separately: the signature from the type
 * index, the doc comment and `#{ }` metadata from `decls`, the effects from the
 * effect junctions, the impls from the ability tables, the references from
 * `usages`. Reading one declaration therefore meant six queries and knowing which
 * six — so nothing did, and the information sat unused.
 *
 * This is the shape a documentation browser renders, an editor shows on hover, and
 * an agent reads before editing. Assembling it here rather than in each of those
 * means they cannot disagree about what a declaration IS.
 *
 * Costs one round of queries per page and is not built for bulk: rendering a
 * thousand pages should batch, and [MezzeStore.getResidualEffectsFor] exists for
 * that reason. A single page is a human-scale request.
 */
data class DocPage(
    val decl: DeclRow,
    /** Canonical rendering of the type, or null for a declaration with no signature. */
    val signature: String?,
    val doc: String?,
    /** The `#{ }` metadata, as stored (JSON). */
    val metadata: String?,
    /** Source file, resolved through the module — `decls` does not carry it. */
    val file: String?,

    /** Effects on the declaration's own residual arrow. */
    val declaredEffects: Set<String>,
    /**
     * Effects reachable through the call graph, attributed to their nearest source.
     * A superset of [declaredEffects] in the ordinary case.
     */
    val reachedEffects: List<EffectReach.Attribution>,
    /** Ability constraints the signature requires of its caller. */
    val constraints: List<String>,

    val references: List<UsageRow>,
    val directCallers: List<DeclRow>,
    val directCallees: List<DeclRow>,

    /** For an `ability` declaration: every impl of it. Empty otherwise. */
    val impls: List<AbilitySearch.Impl>,
    /** For a `type` declaration: the abilities implemented for it. Empty otherwise. */
    val implementedAbilities: List<String>,
    /** For an `effect` declaration: its performers. Empty otherwise. */
    val performers: List<EffectPerformerRow>,
) {
    /** Effects reached but absent from the signature — see [EffectReach.undeclared]. */
    val undeclaredEffects: List<EffectReach.Attribution>
        get() = reachedEffects.filter { it.effect !in declaredEffects }

    val qualifiedName: String get() = "${decl.modulePath}::${decl.declName}"
}

object DocPages {

    fun of(store: MezzeStore, decl: DeclRow): DocPage {
        val rows = store.getSignature(decl.hash)
        val type = if (rows.isEmpty) null else SignatureIndex.rebuild(rows)

        // Constraints come from the residual arrow: a constraint on an inner arrow
        // binds whoever applies that arrow, not the caller of this declaration.
        val residualNodes = rows.nodes.filter { it.isResidual }.map { it.nodeId }.toSet()
        val constraints = rows.constraints
            .filter { it.nodeId in residualNodes }
            .sortedBy { it.ordinal }
            .map { it.abilityName }
            .distinct()

        return DocPage(
            decl = decl,
            signature = type?.let { com.mezze.language.ast.SignatureNormalizer.render(it) },
            doc = decl.doc,
            metadata = decl.metadata,
            file = store.getModule(decl.modulePath)?.filePath,
            declaredEffects = store.getResidualEffectsFor(listOf(decl.hash))[decl.hash].orEmpty(),
            reachedEffects = EffectReach.of(store, decl.hash),
            constraints = constraints,
            references = store.getUsages(decl.hash),
            directCallers = store.getEdgesTo(decl.hash)
                .filter { it.kind == EdgeKind.CALLS }
                .mapNotNull { store.findDeclByHash(it.fromHash) }
                .distinctBy { it.hash },
            directCallees = store.getEdgesFrom(decl.hash)
                .filter { it.kind == EdgeKind.CALLS }
                .mapNotNull { store.findDeclByHash(it.toHash) }
                .distinctBy { it.hash },
            impls = if (decl.declKind == DeclKind.ABILITY) {
                AbilitySearch.implsOf(store, decl.declName)
            } else emptyList(),
            implementedAbilities = if (decl.declKind == DeclKind.TYPE) {
                // The type as a TYPE, built from its own declaration: name for
                // display, hash for identity. That is what impl targets are keyed
                // on, so `Color` here means THIS `Color` and not another module's.
                val asType = MezzeType.TypeApplication(
                    constructor = decl.declName,
                    args = emptyList(),
                    identity = TypeIdentity.Declared(decl.hash, decl.declName),
                )
                AbilitySearch.abilitiesOf(store, asType).map { it.first }
            } else emptyList(),
            performers = if (decl.declKind == DeclKind.EFFECT) {
                store.findEffectPerformersByName(decl.declName)
            } else emptyList(),
        )
    }
}
