package com.mezze.language.typechecker.validation

import com.mezze.language.parsedast.Loc
import com.mezze.language.parsedast.ParsedAbilityDecl
import com.mezze.language.parsedast.ParsedEffectDecl
import com.mezze.language.parsedast.ParsedLetDecl
import com.mezze.language.parsedast.ParsedMetadata
import com.mezze.language.parsedast.ParsedModule
import com.mezze.language.parsedast.ParsedPerformerDecl
import com.mezze.language.parsedast.ParsedTypeDecl
import com.mezze.language.parsedast.diagnostics.WarningCode
import com.mezze.language.typedast.BindingRef
import com.mezze.language.typedast.Hash
import com.mezze.language.typedast.TypedExpr
import com.mezze.language.typedast.TypedLetDecl
import com.mezze.language.typedast.TypedModule
import com.mezze.language.typedast.TypedPerform
import com.mezze.language.typedast.TypedPerformOp
import com.mezze.language.typedast.TypedVar
import com.mezze.language.typedast.forEachChild

/**
 * [WarningCode.W0501_DEPRECATED]: a use of a declaration its author marked
 * `#{ deprecated: True }`, at the use.
 *
 * The editor already struck the declaration through where it is written; the
 * person who needs telling is the one calling it, usually in another module.
 * A use names its target by hash, so the target's metadata is found by hash —
 * imported declarations through [com.mezze.language.typechecker.module.ModuleImportContext.metadataByHash],
 * this module's own through their stamped identities.
 *
 * Reads: a value or function (`f {}`), a performer in `perform P in`, an
 * effect's operation (`E.op {}`). A use inside a deprecated declaration is not
 * reported — deprecated code calling deprecated code is the same migration.
 */
object DeprecationLinter {

    data class Finding(val message: String, val loc: Loc, val code: WarningCode)

    fun lint(
        typed: TypedModule,
        parsed: ParsedModule,
        imported: Map<Hash, ParsedMetadata>,
        effectName: (Hash) -> String?,
    ): List<Finding> {
        val deprecated = (imported + localMetadata(parsed)).filterValues { it.isDeprecated }
        if (deprecated.isEmpty()) return emptyList()

        val findings = mutableListOf<Finding>()
        fun report(hash: Hash, name: String, loc: Loc) {
            val metadata = deprecated[hash] ?: return
            val message = buildString {
                append("`$name` is deprecated")
                metadata.since?.let { append(" since $it") }
                metadata.note?.let { append(": $it") }
            }
            findings += Finding(message, loc, WarningCode.W0501_DEPRECATED)
        }
        fun walk(e: TypedExpr) {
            when (e) {
                is TypedVar -> (e.binding as? BindingRef.ByHash)?.let { report(it.hash, e.name, e.loc) }
                is TypedPerform -> for (h in e.handlers) report(h.performerHash, h.performerName, h.loc)
                is TypedPerformOp -> report(e.effectHash, effectName(e.effectHash) ?: e.operationName, e.loc)
                else -> Unit
            }
            e.forEachChild { walk(it) }
        }

        for (decl in typed.decls) {
            if (decl !is TypedLetDecl || decl.hash in deprecated) continue
            walk(decl.value)
        }
        for (impl in typed.abilityImpls) impl.operations.values.forEach { walk(it) }
        for (performer in typed.performerDecls) {
            if (performer.hash in deprecated) continue
            performer.operations.values.forEach { walk(it) }
        }
        return findings
    }

    /** The `#{ … }` metadata of [module]'s own declarations, by their stamped identity. */
    fun localMetadata(module: ParsedModule): Map<Hash, ParsedMetadata> = module.decls.mapNotNull { decl ->
        val hash = when (decl) {
            is ParsedLetDecl -> decl.contentHash
            is ParsedTypeDecl -> decl.contentHash
            is ParsedAbilityDecl -> decl.contentHash
            is ParsedEffectDecl -> decl.contentHash
            is ParsedPerformerDecl -> decl.contentHash
            else -> null
        }
        val metadata = decl.metadata
        if (hash != null && metadata != null) hash to metadata else null
    }.toMap()
}
