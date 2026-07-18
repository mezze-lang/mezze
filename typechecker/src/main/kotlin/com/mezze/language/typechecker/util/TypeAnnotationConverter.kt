package com.mezze.language.typechecker.util

import com.mezze.language.ast.*
import com.mezze.language.parsedast.*
import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.parsedast.diagnostics.FixHint
import com.mezze.language.parsedast.diagnostics.LevenshteinSuggest
import com.mezze.language.store.DeclKind
import com.mezze.language.typechecker.PrecompiledStdlib
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.typechecker.WellKnownHashConstants
import com.mezze.language.typechecker.context.ContextBuilder
import com.mezze.language.typedast.Hash

object TypeAnnotationConverter {

    data class ConversionConfig(
        val resolveTypeHash: (String) -> Hash? = { null },
        val resolveAbilityHash: (String) -> Hash? = { null },
        val validateEffects: ((ParsedEffectRow) -> Unit)? = null,
        val resolveEffectHashes: (Set<String>) -> Map<String, Hash> = { emptyMap() },
        val resolveCollectionTypes: Boolean = true,
        val honorOpenRowVars: Boolean = false,
        val inlineVariantNaming: Boolean = false,
        val typeParamSubst: Map<String, MezzeType>? = null,
        val convertEffectAnnotations: Boolean = false,
        val stripTickFromTypeVars: Boolean = false,
    )

    fun convertWith(
        annotation: ParsedTypeAnnotation,
        config: ConversionConfig,
        typeVarCache: MutableMap<String, TypeVar> = mutableMapOf(),
        sharedRowVar: SharedRowVar = SharedRowVar()
    ): MezzeType {
        return convertInternal(annotation, config, typeVarCache, sharedRowVar)
    }

    private fun convertInternal(
        annotation: ParsedTypeAnnotation,
        config: ConversionConfig,
        typeVarCache: MutableMap<String, TypeVar>,
        sharedRowVar: SharedRowVar
    ): MezzeType {
        fun recurse(ann: ParsedTypeAnnotation) = convertInternal(ann, config, typeVarCache, sharedRowVar)

        return when (annotation) {
            is ParsedNamedType -> {
                PrimitiveTypes.fromName(annotation.name)?.let { return it }
                val name = annotation.name
                if (name.isTypeVarName()) {
                    val varName = if (config.stripTickFromTypeVars) name.removePrefix("'") else name
                    config.typeParamSubst?.let { subst ->
                        subst[varName] ?: subst[name.removePrefix("'")] ?: subst["'" + name.removePrefix("'")]
                    }?.let { return it }
                    val typeVar = typeVarCache.getOrPut(varName) { TypeVar.freshWithName(varName) }
                    MezzeType.VarType(typeVar)
                } else {
                    WellKnownTypeApp.of(name, emptyList(), config.resolveTypeHash(name))
                }
            }

            is ParsedFunctionType -> {
                // #274-B: give each FunctionType its own SharedRowVar
                // scope, so nested `<...>` rows (e.g. on a callback param)
                // don't share a row var with the outer signature's row.
                // Otherwise binding the outer row to the body's extra
                // effects would leak into the callback's expected row too.
                val innerSharedRowVar = SharedRowVar()
                fun recurseInner(ann: ParsedTypeAnnotation) = convertInternal(ann, config, typeVarCache, innerSharedRowVar)
                val effectRow = annotation.effects?.let { parsedRow ->
                    config.validateEffects?.invoke(parsedRow)
                    val rowVar = if (parsedRow.isPolymorphic) {
                        innerSharedRowVar.getOrCreate()
                    } else null
                    val hashes = config.resolveEffectHashes(parsedRow.effects.toSet())
                    if (config.convertEffectAnnotations && parsedRow.effectAnnotations.isNotEmpty()) {
                        val effectInstances = parsedRow.effectAnnotations.map { ea ->
                            MezzeType.EffectInstance(
                                name = ea.name,
                                associatedTypes = ea.associatedTypes.mapValues { (_, ta) -> recurse(ta) }
                            )
                        }.toSet()
                        MezzeType.EffectRow(
                            effects = parsedRow.effects.toSet(),
                            effectInstances = effectInstances,
                            rowVar = rowVar,
                            performed = parsedRow.isPerformed,
                            effectHashes = hashes
                        )
                    } else {
                        MezzeType.EffectRow.simple(parsedRow.effects.toSet(), rowVar, parsedRow.isPerformed, hashes)
                    }
                }
                val abilityConstraints = annotation.abilityConstraints.map { constraint ->
                    MezzeType.FunctionAbilityConstraint(
                        abilityName = constraint.abilityName,
                        typeArgs = constraint.typeArgs.map { recurse(it) },
                        associatedTypeBindings = constraint.associatedTypeBindings.mapValues { (_, ta) -> recurse(ta) },
                        abilityHash = config.resolveAbilityHash(constraint.abilityName)
                    )
                }
                MezzeType.FunctionType(
                    paramType = recurseInner(annotation.paramType),
                    returnType = recurseInner(annotation.returnType),
                    effects = effectRow,
                    abilityConstraints = abilityConstraints
                )
            }

            is ParsedRecordType -> {
                MezzeType.RecordType(
                    fields = annotation.entries.associate { e -> e.name to recurse(e.type) },
                    rowVar = if (config.honorOpenRowVars && annotation.isOpen) RowVar.userOpen() else null
                )
            }

            is ParsedVariantType -> {
                MezzeType.VariantType(
                    constructors = annotation.constructors.associate { ctor ->
                        ctor.name to ctor.dataType?.let { recurse(it) }
                    },
                    rowVar = if (config.honorOpenRowVars && annotation.isOpen) RowVar.userOpen() else null,
                    typeName = if (config.inlineVariantNaming && !annotation.isOpen) "<inline>" else null,
                    nominalWall = config.inlineVariantNaming && !annotation.isOpen
                )
            }

            is ParsedTypeApp -> {
                val args = annotation.args.map { recurse(it) }
                if (config.resolveCollectionTypes) {
                    resolveWellKnownTypeApp(annotation.constructor, args, config.resolveTypeHash(annotation.constructor))
                } else {
                    WellKnownTypeApp.of(
                        constructor = annotation.constructor,
                        args = args,
                        typeHash = config.resolveTypeHash(annotation.constructor)
                    )
                }
            }

            is ParsedSeqType -> MezzeType.SeqType(recurse(annotation.elementType))

            is ParsedEffectRow -> MezzeType.EffectRow(annotation.effects.toSet())

            is ParsedQualifiedType -> {
                val qualifiedName = (annotation.modulePath + annotation.typeName).joinToString("::")
                WellKnownTypeApp.of(
                    constructor = qualifiedName,
                    args = annotation.args.map { recurse(it) },
                    typeHash = config.resolveTypeHash(qualifiedName)
                )
            }
        }
    }

        fun convert(
        annotation: ParsedTypeAnnotation,
        faithful: Boolean = true,
        includeAbilityConstraints: Boolean = true,
        typeVarCache: MutableMap<String, TypeVar>? = null
    ): MezzeType {
        return when (annotation) {
            is ParsedNamedType -> {
                PrimitiveTypes.fromName(annotation.name)?.let { return it }
                if (faithful) {
                    MezzeType.VarType(
                        if (typeVarCache != null) {
                            typeVarCache.getOrPut(annotation.name) { TypeVar.freshWithName(annotation.name) }
                        } else {
                            TypeVar.freshWithName(annotation.name)
                        }
                    )
                } else {
                    MezzeType.VarType(TypeVar.fresh())
                }
            }
            is ParsedFunctionType -> {
                val effectRow = annotation.effects?.let { row ->
                    val rowVar = if (row.isPolymorphic) RowVar.userOpen() else null
                    MezzeType.EffectRow.simple(row.effects.toSet(), rowVar, row.isPerformed)
                }
                val abilityConstraints = if (includeAbilityConstraints) {
                    annotation.abilityConstraints.map { c ->
                        MezzeType.FunctionAbilityConstraint(
                            abilityName = c.abilityName,
                            typeArgs = c.typeArgs.map { convert(it, faithful, includeAbilityConstraints, typeVarCache) },
                            abilityHash = WellKnownHashConstants.resolveAbilityHash(c.abilityName)
                        )
                    }
                } else {
                    emptyList()
                }
                MezzeType.FunctionType(
                    paramType = convert(annotation.paramType, faithful, includeAbilityConstraints, typeVarCache),
                    returnType = convert(annotation.returnType, faithful, includeAbilityConstraints, typeVarCache),
                    effects = effectRow,
                    abilityConstraints = abilityConstraints
                )
            }
            is ParsedRecordType -> {
                MezzeType.RecordType(annotation.entries.associate { e ->
                    e.name to convert(e.type, faithful, includeAbilityConstraints, typeVarCache)
                })
            }
            is ParsedSeqType -> MezzeType.SeqType(convert(annotation.elementType, faithful, includeAbilityConstraints, typeVarCache))
            is ParsedVariantType -> {
                if (faithful) {
                    MezzeType.VariantType(
                        constructors = annotation.constructors.associate {
                            it.name to it.dataType?.let { dt -> convert(dt, faithful, includeAbilityConstraints, typeVarCache) }
                        },
                        rowVar = null
                    )
                } else {
                    MezzeType.VarType(TypeVar.fresh())
                }
            }
            is ParsedTypeApp -> {
                if (faithful) {
                    MezzeType.TypeApplication(
                        annotation.constructor,
                        annotation.args.map { convert(it, faithful, includeAbilityConstraints, typeVarCache) }
                    )
                } else {
                    MezzeType.VarType(TypeVar.fresh())
                }
            }
            is ParsedQualifiedType -> {
                MezzeType.VarType(TypeVar.fresh())
            }
        }
    }

    private fun resolveWellKnownTypeApp(constructor: String, args: List<MezzeType>, typeHash: Hash? = null): MezzeType {
        return when {
            constructor == "Seq" && args.size == 1 -> MezzeType.SeqType(args[0])
            constructor == "List" && args.size == 1 -> MezzeType.ListType(args[0])
            constructor == "Set" && args.size == 1 -> MezzeType.SetType(args[0])
            constructor == "SortedSet" && args.size == 1 -> MezzeType.SortedSetType(args[0])
            constructor == "Map" && args.size == 2 -> MezzeType.MapType(args[0], args[1])
            constructor == "SortedMap" && args.size == 2 -> MezzeType.SortedMapType(args[0], args[1])
            else -> WellKnownTypeApp.of(constructor, args, typeHash)
        }
    }

    class SharedRowVar {
        private var rowVar: RowVar? = null
        // #274-B: user-written `<...>` must survive closeGeneratedRowVars
        // so callers of an annotated binding see the polymorphic
        // signature. Use userOpen() rather than fresh().
        fun getOrCreate(): RowVar = rowVar ?: RowVar.userOpen().also { rowVar = it }
        fun reset() { rowVar = null }
    }

    fun validateUnknownEffects(
        effectRow: ParsedEffectRow,
        knownEffects: Set<String>,
    ) {
        ContextBuilder.validateHermeticEffects(effectRow.allEffectNames)
        val unknownEffects = (effectRow.effects + effectRow.effectAnnotations.map { it.name })
            .filter { it !in knownEffects }
        if (unknownEffects.isEmpty()) return

        val name = unknownEffects.first()
        val suggestion = LevenshteinSuggest.closest(name, knownEffects)
        val didYouMean = suggestion?.let { ". Did you mean `$it`?" } ?: ""

        val candidateModules: List<String> = try {
            val store = PrecompiledStdlib.getStore()
            store?.getDeclsByKind(DeclKind.EFFECT)
                ?.filter { it.declName == name }
                ?.map { it.modulePath }
                ?.distinct()
                ?: emptyList()
        } catch (_: Exception) { emptyList() }

        val hints = if (candidateModules.isNotEmpty()) {
            candidateModules.take(3).map { "import it: `use ${it}::{$name}`" }
        } else listOf(
            "declare `effect $name { ... }` in this module, or",
            "import it: `use some_module::$name`",
        )

        val fixHints = buildList {
            if (suggestion != null) {
                add(FixHint.Rename(effectRow.loc, listOf(suggestion)))
            }
            if (candidateModules.isNotEmpty()) {
                add(FixHint.Import(effectRow.loc, name, candidateModules))
            }
        }

        throw TypeCheckError(
            "Effect `$name` is not defined or not imported$didYouMean",
            effectRow.loc,
            code = ErrorCode.V0505_EFFECT_NOT_DEFINED,
            hints = hints,
            fixHints = fixHints,
        )
    }
}
