package com.mezze.language.typechecker.phase

import com.mezze.language.ast.BindingKey
import com.mezze.language.typechecker.context.ContextBuilder
import com.mezze.language.typechecker.context.DeclarationRegistry
import com.mezze.language.typechecker.context.TypeScheme
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.ast.solving.collectFreeTypeVars
import com.mezze.language.typechecker.module.ModuleImportContext

/**
 * Merge import context into typing context.
 *
 * This adds all imported bindings, types, abilities, and effects
 * to the typing context.
 */
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

    // Add imported bindings
    // Types with free type variables are registered as polymorphic
    // so they get instantiated with fresh type variables on each use
    for ((name, binding) in importContext.importedBindings) {
        // Parse the name to handle qualified names like "buffer::empty"
        val bindingKey = BindingKey.parse(name)
        // #148: strip alias labels not in scope before registering.
        val bindingType = if (inScopeAliasNames.isNotEmpty()) {
            com.mezze.language.typechecker.solving.AliasStripper.strip(
                binding.type, inScopeAliasNames, DeclarationRegistry.from(typeDeclMap, emptyMap(), emptyMap())
            )
        } else binding.type
        val freeVars = collectFreeTypeVars(bindingType)
        if (freeVars.isEmpty()) {
            // Monomorphic type - use directly
            context = context.extendMono(bindingKey, bindingType, binding.modulePath, binding.contentHash)
        } else {
            // Polymorphic type - wrap in TypeScheme for fresh instantiation
            val scheme = TypeScheme(
                quantifiedVars = freeVars.toList(),
                constraints = emptyList(),
                type = bindingType
            )
            context = context.extendPoly(bindingKey, scheme, binding.modulePath, binding.contentHash)
        }
    }

    // Add imported effects
    // Effects need to be registered with the _ability suffix so they can be looked up
    // during member access (e.g., Log.println)
    val contextBuilder = ContextBuilder()
    for ((effectName, effectDecl) in importContext.importedEffects) {
        val tempModule = com.mezze.language.parsedast.ParsedModule("<import-effect>", listOf(effectDecl))
        val tempBuildResult = contextBuilder.build(tempModule, context)
        context = tempBuildResult.context
    }

    // Add imported abilities
    for ((abilityName, abilityDecl) in importContext.importedAbilities) {
        val tempModule = com.mezze.language.parsedast.ParsedModule("<import-ability>", listOf(abilityDecl))
        val tempBuildResult = contextBuilder.build(tempModule, context)
        context = tempBuildResult.context
    }

    // Add imported types (newtype constructors are private -- not imported)
    for ((typeName, typeDecl) in importContext.importedTypes) {
        context = contextBuilder.addParsedTypeConstructors(context, typeDecl, isImported = true)
    }

    // Add imported ability implementations
    // These are needed for ability method dispatch (e.g., EncodeFormat.encode_int)
    for ((implKey, implDecl) in importContext.importedAbilityImpls) {
        if (implDecl.isInherent) {
            // Inherent impls need both AbilityImplInfo AND InherentMethodInfo registered.
            // Use registerImportedInherentImpl to bypass validation (already validated in home module).
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
            // Named ability impls only need AbilityImplInfo.
            val abilityDeclHashForImpl = implDecl.abilityName?.let { name ->
                importContext.importedAbilities[name]?.contentHash
            }
            val implInfo = com.mezze.language.typechecker.util.AbilityImplFactory.build(
                implDecl, abilityDeclHashForImpl
            )
            context = context.extendWithImpl(implKey, implInfo)
        }
    }

    return context
}
