package com.mezze.language.typechecker

import com.mezze.language.ast.BindingKey
import com.mezze.language.ast.MezzeType
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.context.ContextBuilder
import com.mezze.language.typechecker.context.TypeScheme

/**
 * Pre-compiled module interface — immutable, concrete type data.
 *
 * Unlike ModuleImportContext which contains v1 AST nodes with mutable type state,
 * this holds pre-processed entries ready to inject into a TypingContext.
 * Each entry is either a concrete (monomorphic) type or a TypeScheme template
 * that creates fresh TypeVars on each instantiation.
 *
 * This is the equivalent of a .rlib/.cmi file — the compiled public API.
 */
data class CompiledModuleInterface(
    /** Monomorphic bindings: name → (type, modulePath, contentHash) */
    val monoBindings: List<MonoBinding>,
    /** Polymorphic bindings: name → (scheme, modulePath, contentHash) */
    val polyBindings: List<PolyBinding>,
    /** Pre-built typing context additions from effects, abilities, types, impls */
    val contextExtensions: List<ContextExtension>
) {
    data class MonoBinding(
        val key: BindingKey,
        val type: MezzeType,
        val modulePath: String?,
        val contentHash: com.mezze.language.typedast.Hash?
    )

    data class PolyBinding(
        val key: BindingKey,
        val scheme: TypeScheme,
        val modulePath: String?,
        val contentHash: com.mezze.language.typedast.Hash?
    )

    /** A pre-computed context extension (effect, ability, type, or impl registration) */
    sealed interface ContextExtension {
        fun apply(context: TypingContext): TypingContext

        data class EffectRegistration(val effectDecl: com.mezze.language.parsedast.ParsedEffectDecl) : ContextExtension {
            override fun apply(context: TypingContext): TypingContext {
                val builder = ContextBuilder()
                val parsedModule = com.mezze.language.parsedast.ParsedModule(
                    path = "<effect-registration>",
                    decls = listOf(effectDecl)
                )
                return builder.build(parsedModule, context).context
            }
        }

        data class AbilityRegistration(val abilityDecl: com.mezze.language.parsedast.ParsedAbilityDecl) : ContextExtension {
            override fun apply(context: TypingContext): TypingContext {
                val builder = ContextBuilder()
                val parsedModule = com.mezze.language.parsedast.ParsedModule(
                    path = "<ability-registration>",
                    decls = listOf(abilityDecl)
                )
                return builder.build(parsedModule, context).context
            }
        }

        data class TypeRegistration(val typeDecl: com.mezze.language.parsedast.ParsedTypeDecl) : ContextExtension {
            override fun apply(context: TypingContext): TypingContext {
                val builder = ContextBuilder()
                val parsedModule = com.mezze.language.parsedast.ParsedModule(
                    path = "<type-registration>",
                    decls = listOf(typeDecl)
                )
                // Build adds type constructors to context
                return builder.build(parsedModule, context).context
            }
        }

        data class InherentImplRegistration(
            val implDecl: com.mezze.language.parsedast.ParsedImplAbilityDecl,
            val implKey: String,
            // Pre-inferred return types from the home module's typecheck.
            // See ModuleImportContext.inherentImplOpReturnTypes / issue #213.
            val opReturnTypes: Map<String, MezzeType> = emptyMap(),
            // Sibling for param-record types — issue #230.
            val opParamTypes: Map<String, MezzeType> = emptyMap()
        ) : ContextExtension {
            override fun apply(context: TypingContext): TypingContext {
                return ContextBuilder().registerImportedInherentImpl(
                    context, implDecl, implKey,
                    opReturnTypes = opReturnTypes,
                    opParamTypes = opParamTypes
                )
            }
        }

        data class NamedImplRegistration(val implInfo: TypingContext.AbilityImplInfo, val implKey: String) : ContextExtension {
            override fun apply(context: TypingContext): TypingContext {
                return context.extendWithImpl(com.mezze.language.typedast.Hash(implKey), implInfo)
            }
        }
    }

    /**
     * Merge this compiled interface into a TypingContext.
     * Fast — no ModuleLoader, no ImportResolver, no filesystem access.
     * Creates fresh TypeVars for polymorphic bindings on each call.
     */
    fun mergeInto(baseContext: TypingContext): TypingContext {
        var context = baseContext

        // Add monomorphic bindings (no TypeVars, direct)
        for (b in monoBindings) {
            context = context.extendMono(b.key, b.type, b.modulePath, b.contentHash)
        }

        // Add polymorphic bindings (TypeScheme template — instantiate creates fresh vars per use)
        for (b in polyBindings) {
            context = context.extendPoly(b.key, b.scheme, b.modulePath, b.contentHash)
        }

        // Apply context extensions — effects, abilities, types, impls
        // These use ContextBuilder internally which may create fresh TypeVars,
        // but the results are deterministic from the same input declarations.
        for (ext in contextExtensions) {
            context = ext.apply(context)
        }

        return context
    }

    companion object {
        /**
         * Build from a ModuleImportContext — done ONCE, result is reusable.
         */
        fun fromImportContext(
            importContext: com.mezze.language.typechecker.module.ModuleImportContext
        ): CompiledModuleInterface {
            val monoBindings = mutableListOf<MonoBinding>()
            val polyBindings = mutableListOf<PolyBinding>()
            val extensions = mutableListOf<ContextExtension>()

            // Process bindings
            for ((name, binding) in importContext.importedBindings) {
                val bindingKey = BindingKey.parse(name)
                val freeVars = com.mezze.language.typechecker.context.collectFreeTypeVars(binding.type)
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

            // Effects
            for ((_, effectDecl) in importContext.importedEffects) {
                extensions.add(ContextExtension.EffectRegistration(effectDecl))
            }

            // Abilities
            for ((_, abilityDecl) in importContext.importedAbilities) {
                extensions.add(ContextExtension.AbilityRegistration(abilityDecl))
            }

            // Types
            for ((_, typeDecl) in importContext.importedTypes) {
                extensions.add(ContextExtension.TypeRegistration(typeDecl))
            }

            // Ability impls
            val parserBridge = com.mezze.language.parser.MezzeParser
            for ((implKey, implDecl) in importContext.importedAbilityImpls) {
                if (implDecl.isInherent) {
                    val opReturnTypes = importContext.inherentImplOpReturnTypes[implKey].orEmpty()
                    val opParamTypes = importContext.inherentImplOpParamTypes[implKey].orEmpty()
                    extensions.add(ContextExtension.InherentImplRegistration(
                        implDecl, implKey, opReturnTypes, opParamTypes
                    ))
                } else {
                    val implTypeVars = implDecl.abilityTypeArgs
                        .filterIsInstance<com.mezze.language.parsedast.ParsedNamedType>()
                        .filter { it.name.startsWith("'") }
                        .map { it.name }
                        .toSet()
                    val implInfo = TypingContext.AbilityImplInfo(
                        abilityName = implDecl.abilityName,
                        abilityDeclHash = null,
                        targetTypes = implDecl.allTypeArgs.map { it.toString() },
                        targetTypeAnnotations = implDecl.allTypeArgs,
                        associatedTypeBindings = implDecl.associatedTypeBindings,
                        sourceModule = null,
                        contentHash = com.mezze.language.typedast.Hash(implKey),
                        typeVars = implTypeVars
                    )
                    extensions.add(ContextExtension.NamedImplRegistration(implInfo, implKey))
                }
            }

            return CompiledModuleInterface(monoBindings, polyBindings, extensions)
        }
    }
}
