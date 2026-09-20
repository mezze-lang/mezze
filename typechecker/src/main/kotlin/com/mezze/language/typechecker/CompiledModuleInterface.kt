package com.mezze.language.typechecker

import com.mezze.language.ast.BindingKey
import com.mezze.language.ast.MezzeType
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.context.ContextBuilder
import com.mezze.language.typechecker.context.TypeScheme
import com.mezze.language.parsedast.*
import com.mezze.language.typedast.*
import com.mezze.language.typechecker.module.ModuleImportContext
import com.mezze.language.ast.solving.collectFreeTypeVars
import com.mezze.language.typechecker.util.AbilityImplFactory

data class CompiledModuleInterface(
    val monoBindings: List<MonoBinding>,
    val polyBindings: List<PolyBinding>,
    val contextExtensions: List<ContextExtension>
) {
    data class MonoBinding(
        val key: BindingKey,
        val type: MezzeType,
        val modulePath: String?,
        val contentHash: Hash?
    )

    data class PolyBinding(
        val key: BindingKey,
        val scheme: TypeScheme,
        val modulePath: String?,
        val contentHash: Hash?
    )

    sealed interface ContextExtension {
        fun apply(context: TypingContext): TypingContext

        data class EffectRegistration(val effectDecl: ParsedEffectDecl) : ContextExtension {
            override fun apply(context: TypingContext): TypingContext {
                val builder = ContextBuilder()
                val parsedModule = ParsedModule(
                    path = "<effect-registration>",
                    decls = listOf(effectDecl)
                )
                return builder.build(parsedModule, context).context
            }
        }

        data class AbilityRegistration(val abilityDecl: ParsedAbilityDecl) : ContextExtension {
            override fun apply(context: TypingContext): TypingContext {
                val builder = ContextBuilder()
                val parsedModule = ParsedModule(
                    path = "<ability-registration>",
                    decls = listOf(abilityDecl)
                )
                return builder.build(parsedModule, context).context
            }
        }

        data class TypeRegistration(val typeDecl: ParsedTypeDecl) : ContextExtension {
            override fun apply(context: TypingContext): TypingContext {
                val builder = ContextBuilder()
                val parsedModule = ParsedModule(
                    path = "<type-registration>",
                    decls = listOf(typeDecl)
                )
                return builder.build(parsedModule, context).context
            }
        }

        data class InherentImplRegistration(
            val implDecl: ParsedImplAbilityDecl,
            val implKey: Hash,
            // See ModuleImportContext.inherentImplOpReturnTypes / issue #213.
            val opReturnTypes: Map<String, MezzeType> = emptyMap(),
            // Sibling for param-record types — issue #230.
            val opParamTypes: Map<String, MezzeType> = emptyMap(),
            // Sibling for effect rows — #181.
            val opEffects: Map<String, MezzeType.EffectRow> = emptyMap()
        ) : ContextExtension {
            override fun apply(context: TypingContext): TypingContext {
                return ContextBuilder().registerImportedInherentImpl(
                    context, implDecl, implKey,
                    opReturnTypes = opReturnTypes,
                    opParamTypes = opParamTypes,
                    opEffects = opEffects
                )
            }
        }

        data class NamedImplRegistration(val implInfo: TypingContext.AbilityImplInfo, val implKey: Hash) : ContextExtension {
            override fun apply(context: TypingContext): TypingContext {
                return context.extendWithImpl(implKey, implInfo)
            }
        }
    }

    fun mergeInto(baseContext: TypingContext): TypingContext {
        var context = baseContext
        for (b in monoBindings) context = context.extendMono(b.key, b.type, b.modulePath, b.contentHash)
        for (b in polyBindings) context = context.extendPoly(b.key, b.scheme, b.modulePath, b.contentHash)
        for (ext in contextExtensions) context = ext.apply(context)
        return context
    }

    companion object {
        fun fromImportContext(importContext: ModuleImportContext): CompiledModuleInterface {
            val monoBindings = mutableListOf<MonoBinding>()
            val polyBindings = mutableListOf<PolyBinding>()
            val extensions = mutableListOf<ContextExtension>()

            for ((name, binding) in importContext.importedBindings) {
                val bindingKey = BindingKey.parse(name)
                val freeVars = collectFreeTypeVars(binding.type)
                if (freeVars.isEmpty()) {
                    monoBindings.add(MonoBinding(bindingKey, binding.type, binding.modulePath, binding.contentHash))
                } else {
                    val scheme = TypeScheme(
                        quantifiedVars = freeVars.toList(),
                        constraints = emptyList(),
                        type = binding.type
                    )
                    polyBindings.add(PolyBinding(bindingKey, scheme, binding.modulePath, binding.contentHash))
                }
            }

            for ((_, effectDecl) in importContext.importedEffects) extensions.add(ContextExtension.EffectRegistration(effectDecl))
            for ((_, abilityDecl) in importContext.importedAbilities) extensions.add(ContextExtension.AbilityRegistration(abilityDecl))
            for ((_, typeDecl) in importContext.importedTypes) extensions.add(ContextExtension.TypeRegistration(typeDecl))

            for ((implKey, implDecl) in importContext.importedAbilityImpls) {
                if (implDecl.isInherent) {
                    val opReturnTypes = importContext.inherentImplOpReturnTypes[implKey].orEmpty()
                    val opParamTypes = importContext.inherentImplOpParamTypes[implKey].orEmpty()
                    val opEffects = importContext.inherentImplOpEffects[implKey].orEmpty()
                    extensions.add(ContextExtension.InherentImplRegistration(
                        implDecl, implKey, opReturnTypes, opParamTypes, opEffects
                    ))
                } else {
                    // registry in TypingContext silently drifted (#304 follow-up,
                    // #307 consolidation).
                    // NOTE: this resolves the ability BY NAME in the importer's table, which
                    // is wrong when the importer imported a same-named ability from elsewhere.
                    // Pinning identity in the impl's own module was tried and reverted — it
                    // produced hashes that disagreed with the ones demands carry, which is
                    // itself the open defect (AbilityIdentityCrossModuleTest).
                    val abilityDeclHash = implDecl.abilityName?.let { name ->
                        importContext.importedAbilities[name]?.contentHash
                    }
                    val implInfo = AbilityImplFactory.build(
                        implDecl, abilityDeclHash
                    )
                    extensions.add(ContextExtension.NamedImplRegistration(implInfo, implKey))
                }
            }

            return CompiledModuleInterface(monoBindings, polyBindings, extensions)
        }
    }
}
