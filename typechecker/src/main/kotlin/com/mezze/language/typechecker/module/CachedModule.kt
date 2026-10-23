package com.mezze.language.typechecker.module

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.solving.Substitution
import com.mezze.language.parsedast.ParsedModule
import com.mezze.language.parsedast.Visibility
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typedast.Hash
import com.mezze.language.typedast.TypedModule
import kotlinx.serialization.Serializable

/**
 * A checked module as it can be kept outside the process that checked it: every
 * part of a [TypedModuleResult] a module importing it reads, and nothing else.
 *
 * Not kept, because no importer reads them: the module's own input context, its
 * solver substitution and its dependency graph. A module read back gets empty
 * ones, as [ModuleTypeChecker.cacheTypedModule] already gives a stored module.
 *
 * It is reused only while it is what checking would produce now: the cache it
 * goes into already compares [identity] with the module's, and the identity
 * covers everything the module's declarations resolve to.
 */
@Serializable
data class CachedModule(
    val modulePath: String,
    /** The module's [TypedModule], by its hash in the table it is kept beside. */
    val typedModule: Hash,
    val parsedModule: ParsedModule?,
    val exports: List<Export>,
    val typeContext: Map<String, MezzeType>,
    val filePath: String?,
    val sourceHash: Hash?,
    val identity: Hash?,
    val loweredFunctions: List<FunctionIR>,
    val loweredPerformers: List<List<String>>,
    /** What importers read off its inherent impls, so importing it never reads its body. */
    val inherentOps: InherentImplOps? = null,
    val buildTimeSummary: com.mezze.language.typechecker.comptime.BuildTimeSummary? = null,
    val performerEffects: com.mezze.language.typechecker.context.ImportedPerformerEffects.Summary? = null,
    /** Its type, ability and effect declarations (see TypedModuleResult.declarationHeaders). */
    val headers: List<com.mezze.language.parsedast.ParsedDecl>? = null,
) {
    @Serializable
    data class Export(
        val key: String,
        val name: String,
        val type: ExportedItemType,
        val mezzeType: MezzeType,
        val originalName: String,
        val visibility: Visibility,
        val contentHash: Hash,
    )

    /** The result an importer reads, with [typed] the module [typedModule] names. */
    fun toResult(typed: TypedModule): TypedModuleResult = toResult(lazyOf(typed to parsedModule))

    /** The result an importer reads, its body read from [body] when first asked for. */
    fun toResult(body: Lazy<Pair<TypedModule?, ParsedModule?>>): TypedModuleResult = TypedModuleResult(
        modulePath = modulePath,
        exports = exports.associate { e ->
            e.key to ExportedItem(e.name, e.type, e.mezzeType, e.originalName, e.visibility, e.contentHash)
        },
        context = TypingContext.empty,
        substitution = Substitution.empty,
        body = body,
        typeContext = typeContext,
        filePath = filePath,
        sourceHash = sourceHash,
        identity = identity,
        loweredFunctions = loweredFunctions,
        loweredPerformers = loweredPerformers.map { it.toTypedArray() },
        inherentOps = inherentOps,
        buildTimeSummary = buildTimeSummary,
        performerEffects = performerEffects,
        headers = headers,
    )

    companion object {
        /** [result] to keep, or null when it has no typed module to keep. */
        fun of(result: TypedModuleResult): CachedModule? {
            val typed = result.typedModule ?: return null
            return CachedModule(
                modulePath = result.modulePath,
                typedModule = typed.hash,
                parsedModule = result.parsedModule,
                exports = result.exports.map { (k, e) ->
                    Export(k, e.name, e.type, e.mezzeType, e.originalName, e.visibility, e.contentHash)
                },
                typeContext = result.typeContext,
                filePath = result.filePath,
                sourceHash = result.sourceHash,
                identity = result.identity,
                loweredFunctions = result.loweredFunctions,
                loweredPerformers = result.loweredPerformers.map { it.toList() },
                inherentOps = harvestInherentImplOps(typed, result.exports),
                buildTimeSummary = result.buildTimeSummary,
                performerEffects = result.performerEffects,
                headers = result.declarationHeaders,
            )
        }
    }
}
