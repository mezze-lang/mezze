package com.mezze.language.typechecker.phase

import com.mezze.language.ast.BindingKey
import com.mezze.language.typechecker.context.ContextBuilder
import com.mezze.language.typechecker.context.DeclarationRegistry
import com.mezze.language.typechecker.context.TypeScheme
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.ast.solving.collectFreeTypeVars
import com.mezze.language.parsedast.ParsedAbilityDecl
import com.mezze.language.parsedast.ParsedEffectDecl
import com.mezze.language.parsedast.ParsedEffectRow
import com.mezze.language.parsedast.ParsedFunctionType
import com.mezze.language.parsedast.ParsedModule
import com.mezze.language.parsedast.ParsedNamedType
import com.mezze.language.parsedast.ParsedQualifiedType
import com.mezze.language.parsedast.ParsedRecordType
import com.mezze.language.parsedast.ParsedSeqType
import com.mezze.language.parsedast.ParsedTypeAnnotation
import com.mezze.language.parsedast.ParsedTypeApp
import com.mezze.language.parsedast.ParsedVariantType
import com.mezze.language.typechecker.module.ModuleImportContext
import com.mezze.language.typechecker.module.ModuleTypeChecker
import com.mezze.language.typechecker.solving.AliasStripper
import com.mezze.language.typechecker.util.AbilityImplFactory

internal fun mergeImportContext(
    baseContext: TypingContext,
    importContext: ModuleImportContext,
    inScopeAliasNames: Set<String> = emptySet()
): TypingContext {
    var context = baseContext

    // #148: strip variant-alias labels from imported binding types when the
    // alias name isn't in scope at this use site. The result is that values
    // crossing the module boundary lose their nominal identity (becoming
    // structural arm sets) for aliases the caller didn't `use foo::{Alias}`.
    // Newtypes and records stay nominal (intentional barriers).
    // typeDeclMap below merges importedTypes (which carries auto-imports for
    // expansion) so the stripper can resolve any TypeApplication it sees.
    val typeDeclMap = importContext.importedTypes

    for ((name, binding) in importContext.importedBindings) {
        val bindingKey = BindingKey.parse(name)
        // #148: strip alias labels not in scope before registering.
        val bindingType = if (inScopeAliasNames.isNotEmpty()) {
            AliasStripper.strip(
                binding.type, inScopeAliasNames, DeclarationRegistry.from(typeDeclMap, emptyMap(), emptyMap())
            )
        } else binding.type
        val freeVars = collectFreeTypeVars(bindingType)
        if (freeVars.isEmpty()) {
            context = context.extendMono(bindingKey, bindingType, binding.modulePath, binding.contentHash)
        } else {
            val scheme = TypeScheme(
                quantifiedVars = freeVars.toList(),
                constraints = emptyList(),
                type = bindingType
            )
            context = context.extendPoly(bindingKey, scheme, binding.modulePath, binding.contentHash)
        }
    }

    val contextBuilder = ContextBuilder()

    // Collect ALL effect decls transitively used by any imported effect/ability
    // by walking their op signatures. Needed because a user may import effect X
    // that transitively references effect Y in its op signatures — if the user
    // doesn't ALSO import Y, we still need Y in scope while re-processing X.
    val extendedEffects = mutableMapOf<String, ParsedEffectDecl>()
    extendedEffects.putAll(importContext.importedEffects)
    for ((_, effectDecl) in importContext.importedEffects) {
        collectReferencedEffects(effectDecl, importContext, extendedEffects)
    }
    for ((_, abilityDecl) in importContext.importedAbilities) {
        collectReferencedEffectsFromAbility(abilityDecl, importContext, extendedEffects)
    }

    for ((effectName, effectDecl) in importContext.importedEffects) {
        val tempModule = ParsedModule("<import-effect>", listOf(effectDecl))
        val tempBuildResult = contextBuilder.build(
            tempModule, context,
            importedEffects = extendedEffects
        )
        context = tempBuildResult.context
    }

    for ((abilityName, abilityDecl) in importContext.importedAbilities) {
        val tempModule = ParsedModule("<import-ability>", listOf(abilityDecl))
        val tempBuildResult = contextBuilder.build(
            tempModule, context,
            importedEffects = extendedEffects
        )
        context = tempBuildResult.context
    }

    for ((typeName, typeDecl) in importContext.importedTypes) {
        context = contextBuilder.addParsedTypeConstructors(context, typeDecl, isImported = true)
    }

    for ((implKey, implDecl) in importContext.importedAbilityImpls) {
        if (implDecl.isInherent) {
            val opReturnTypes = importContext.inherentImplOpReturnTypes[implKey].orEmpty()
            val opParamTypes = importContext.inherentImplOpParamTypes[implKey].orEmpty()
            val opEffects = importContext.inherentImplOpEffects[implKey].orEmpty()
            context = contextBuilder.registerImportedInherentImpl(
                context, implDecl, implKey,
                opReturnTypes = opReturnTypes,
                opParamTypes = opParamTypes,
                opEffects = opEffects
            )
        } else {
            val abilityDeclHashForImpl = implDecl.abilityName?.let { name ->
                importContext.importedAbilities[name]?.contentHash
            }
            val implInfo = AbilityImplFactory.build(
                implDecl, abilityDeclHashForImpl
            )
            context = context.extendWithImpl(implKey, implInfo)
        }
    }

    return context
}

// Walk an effect decl's op signatures and pull in any cross-module effect
// referenced by name. Uses the loaded modules snapshot on ModuleTypeChecker to
// find the parsed decl for a referenced-but-not-imported effect.
private fun collectReferencedEffects(
    effectDecl: ParsedEffectDecl,
    importContext: ModuleImportContext,
    into: MutableMap<String, ParsedEffectDecl>,
) {
    for (op in effectDecl.operations) {
        collectFromAnnotation(op.paramType, importContext, into)
        collectFromAnnotation(op.returnType, importContext, into)
    }
}

private fun collectReferencedEffectsFromAbility(
    abilityDecl: ParsedAbilityDecl,
    importContext: ModuleImportContext,
    into: MutableMap<String, ParsedEffectDecl>,
) {
    for (op in abilityDecl.operations) {
        collectFromAnnotation(op.paramType, importContext, into)
        collectFromAnnotation(op.returnType, importContext, into)
        op.effects?.let { row -> collectFromEffectRow(row, importContext, into) }
    }
}

private fun collectFromAnnotation(
    annotation: ParsedTypeAnnotation,
    importContext: ModuleImportContext,
    into: MutableMap<String, ParsedEffectDecl>,
) {
    when (annotation) {
        is ParsedFunctionType -> {
            collectFromAnnotation(annotation.paramType, importContext, into)
            collectFromAnnotation(annotation.returnType, importContext, into)
            annotation.effects?.let { collectFromEffectRow(it, importContext, into) }
        }
        is ParsedRecordType -> {
            for (e in annotation.entries) collectFromAnnotation(e.type, importContext, into)
        }
        is ParsedVariantType -> {
            for (c in annotation.constructors) c.dataType?.let { collectFromAnnotation(it, importContext, into) }
        }
        is ParsedTypeApp -> for (a in annotation.args) collectFromAnnotation(a, importContext, into)
        is ParsedSeqType -> collectFromAnnotation(annotation.elementType, importContext, into)
        is ParsedQualifiedType -> for (a in annotation.args) collectFromAnnotation(a, importContext, into)
        is ParsedNamedType -> {}
    }
}

private fun collectFromEffectRow(
    row: ParsedEffectRow,
    importContext: ModuleImportContext,
    into: MutableMap<String, ParsedEffectDecl>,
) {
    for (name in row.allEffectNames) {
        if (name in into) continue
        val fromCtx = importContext.importedEffects[name]
        if (fromCtx != null) { into[name] = fromCtx; continue }
        // Try loaded modules snapshot for a decl by this name.
        val fromLoaded = findEffectDeclByNameInLoadedModules(name)
        if (fromLoaded != null) into[name] = fromLoaded
    }
}

private fun findEffectDeclByNameInLoadedModules(
    name: String,
): ParsedEffectDecl? {
    try {
        for ((_, cached) in ModuleTypeChecker.getAllCachedModules()) {
            val decl = cached.parsedModule?.decls?.filterIsInstance<ParsedEffectDecl>()
                ?.firstOrNull { it.name == name }
            if (decl != null) return decl
        }
    } catch (_: Exception) {}
    return null
}
