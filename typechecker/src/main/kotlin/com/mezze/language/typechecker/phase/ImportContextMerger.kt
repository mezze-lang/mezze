package com.mezze.language.typechecker.phase

import com.mezze.language.ast.BindingKey
import com.mezze.language.typechecker.context.ContextBuilder
import com.mezze.language.typechecker.context.DeclarationRegistry
import com.mezze.language.typechecker.context.TypeScheme
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.ast.solving.collectFreeTypeVars
import com.mezze.language.parsedast.ParsedModule
import com.mezze.language.typechecker.module.ModuleImportContext
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
    for ((effectName, effectDecl) in importContext.importedEffects) {
        val tempModule = ParsedModule("<import-effect>", listOf(effectDecl))
        val tempBuildResult = contextBuilder.build(tempModule, context)
        context = tempBuildResult.context
    }

    for ((abilityName, abilityDecl) in importContext.importedAbilities) {
        val tempModule = ParsedModule("<import-ability>", listOf(abilityDecl))
        val tempBuildResult = contextBuilder.build(tempModule, context)
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
