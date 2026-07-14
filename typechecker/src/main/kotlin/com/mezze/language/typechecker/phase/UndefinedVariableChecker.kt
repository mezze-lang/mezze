package com.mezze.language.typechecker.phase

import com.mezze.language.ast.ModulePath
import com.mezze.language.ast.debug.ContentHashRegistry
import com.mezze.language.parsedast.*
import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.parsedast.diagnostics.FixHint
import com.mezze.language.typechecker.PrecompiledStdlib
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.typechecker.abilities.BuiltInAbilities
import com.mezze.language.typechecker.constraints.ConstraintResult
import com.mezze.language.typechecker.context.DeclarationRegistry
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.module.ModuleImportContext
import com.mezze.language.typechecker.module.TypedModule
import com.mezze.language.typechecker.validation.TypeValidator

/**
 * Check for undefined variables in constraint results and produce
 * detailed error messages with suggestions.
 */
internal fun checkUndefinedVariables(
    constraintResults: Map<String, ConstraintResult>,
    context: TypingContext,
    registry: DeclarationRegistry,
    implDeclarations: Map<String, ParsedPerformerDecl>,
    loadedModules: Map<ModulePath, TypedModule>,
    importContext: ModuleImportContext?,
): List<TypeChecker.TypeCheckError> {
    val undefinedVars = constraintResults.values.flatMap { it.undefinedVariables }
    if (undefinedVars.isEmpty()) return emptyList()

    val validator = TypeValidator(
        context,
        registry,
        implDeclarations
    )
    val allVariables = context.getAllBindings()
    val peerBindingNames = constraintResults.keys.filterNot { it.startsWith("_") }

    return undefinedVars.map { undefinedVar ->
        var privateItemForFix: PrivateItemResult? = null
        val isBuiltInAbility = BuiltInAbilities.isBuiltInAbility(undefinedVar.name)
        val isUserDefinedAbility = registry.lookupAbility(undefinedVar.name) != null
        val isEffect = registry.lookupEffect(undefinedVar.name) != null

        val matchingAbilityOp = registry.abilityDeclarations.entries.firstOrNull { (_, ability) ->
            ability.operations.any { it.name == undefinedVar.name }
        }

        val matchingEffectOp = registry.effectDeclarations.entries.firstOrNull { (_, effect) ->
            effect.operations.any { it.name == undefinedVar.name }
        }

        val message = if (isBuiltInAbility || isUserDefinedAbility) {
            "Ability '${undefinedVar.name}' cannot be used as a value. " +
            "Did you mean to access a method like ${undefinedVar.name}.method_name?"
        } else if (isEffect) {
            "Effect '${undefinedVar.name}' cannot be used as a value. " +
            "Did you mean to access an operation like ${undefinedVar.name}.operation_name?"
        } else if (matchingAbilityOp != null) {
            val abilityName = matchingAbilityOp.key
            "Undefined variable: ${undefinedVar.name}. " +
            "Did you mean to call an ability method? Use: $abilityName.${undefinedVar.name}"
        } else if (matchingEffectOp != null) {
            val effectName = matchingEffectOp.key
            "Undefined variable: ${undefinedVar.name}. " +
            "Did you mean to call an effect operation? Use: $effectName.${undefinedVar.name}"
        } else {
            privateItemForFix = checkIfPrivateQualifiedItem(undefinedVar, loadedModules, importContext)

            if (privateItemForFix != null) {
                privateItemForFix!!.message
            } else {
                val candidateNames = allVariables.keys.map { key -> key.toKeyString() } + peerBindingNames
                val similarNames = validator.findSimilarNames(undefinedVar.name, candidateNames)
                val preludeMethodCarriers = findPreludeMethodCarriers(undefinedVar.name)
                val freeFunctionCarriers =
                    if (!undefinedVar.isQualified) findStdlibFreeFunctionCarriers(undefinedVar.name)
                    else emptyList()
                buildString {
                    append("Undefined variable or function: ${undefinedVar.name}")
                    if (similarNames.isNotEmpty()) {
                        append(". Did you mean: ${similarNames.joinToString()}")
                    }
                    if (preludeMethodCarriers.isNotEmpty()) {
                        val carriers = preludeMethodCarriers.take(3).joinToString(", ")
                        append(". '${undefinedVar.name}' is available as a method on $carriers")
                        append(" — try `<receiver>.${undefinedVar.name} {…}` or import the function explicitly")
                    }
                    if (freeFunctionCarriers.isNotEmpty() && preludeMethodCarriers.isEmpty()) {
                        val first = freeFunctionCarriers.first()
                        append(". '${undefinedVar.name}' is exported from $first")
                        if (freeFunctionCarriers.size > 1) {
                            append(" (also: ${freeFunctionCarriers.drop(1).joinToString(", ")})")
                        }
                        append(" — try `use $first::{${undefinedVar.name}}`")
                    }
                }
            }
        }
        // Emit structured FixHints so the LSP can offer one-click QuickFix
        val undefFixHints: List<FixHint> = buildList {
            if (undefinedVar.loc != null && privateItemForFix != null) {
                add(FixHint.AddPub(
                    undefinedVar.loc!!,
                    privateItemForFix!!.declModulePath,
                    privateItemForFix!!.declName,
                ))
            }
            if (undefinedVar.loc != null && privateItemForFix == null &&
                !isBuiltInAbility && !isUserDefinedAbility && !isEffect &&
                matchingAbilityOp == null && matchingEffectOp == null) {
                val candidateNames = allVariables.keys.map { key -> key.toKeyString() } + peerBindingNames
                val similarNames = validator.findSimilarNames(undefinedVar.name, candidateNames)
                if (similarNames.isNotEmpty()) {
                    add(FixHint.Rename(
                        undefinedVar.loc!!, similarNames.take(3)
                    ))
                }
            }
        }
        TypeChecker.TypeCheckError(
            phase = "Validation",
            message = message,
            loc = undefinedVar.loc,
            code = ErrorCode.V0702_UNDEFINED_VARIABLE,
            fixHints = undefFixHints,
        )
    }
}

/** Result of a private-qualified-item check: message + the (modulePath, declName) needed for an AddPub fix. */
internal data class PrivateItemResult(val message: String, val declModulePath: String, val declName: String)

/**
 * Check if a qualified name (like "module::function") refers to an item
 * that exists in the module but isn't public.
 *
 * @return A [PrivateItemResult] if the item exists but isn't public, null otherwise.
 */
internal fun checkIfPrivateQualifiedItem(
    undefinedVar: ConstraintResult.UndefinedVariable,
    loadedModules: Map<ModulePath, TypedModule>,
    importContext: ModuleImportContext?
): PrivateItemResult? {
    if (!undefinedVar.isQualified) return null

    val qualifierPath = undefinedVar.qualifier ?: return null
    val itemName = undefinedVar.name.substringAfterLast("::")

    val moduleByPath = loadedModules.values.find { module ->
        val modulePath = ModulePath.parse(module.modulePath)
        modulePath == qualifierPath || modulePath.endsWith(qualifierPath)
    }

    if (moduleByPath != null) {
        val existsButNotExported = checkIfItemExistsInModule(moduleByPath, itemName)
        if (existsButNotExported) {
            return PrivateItemResult(
                "'$itemName' exists in module '${qualifierPath.fullPath}' but is not public. Add 'pub' to export it.",
                moduleByPath.modulePath,
                itemName,
            )
        }
    }

    val matchingBinding = importContext?.importedBindings?.entries?.find { (bindingName, _) ->
        val bindingPath = ModulePath.parse(bindingName.substringBeforeLast("::"))
        bindingPath == qualifierPath || bindingPath.endsWith(qualifierPath)
    }

    if (matchingBinding != null) {
        val modulePath = ModulePath.parse(matchingBinding.value.modulePath)
        val module = loadedModules[modulePath]
        if (module != null) {
            val existsButNotExported = checkIfItemExistsInModule(module, itemName)
            if (existsButNotExported) {
                return PrivateItemResult(
                    "'$itemName' exists in module '${qualifierPath.fullPath}' but is not public. Add 'pub' to export it.",
                    module.modulePath,
                    itemName,
                )
            }
        }
    }

    return null
}

/**
 * Check if an item exists in a module's program but isn't exported.
 */
private fun checkIfItemExistsInModule(module: TypedModule, itemName: String): Boolean {
    if (module.exports.containsKey(itemName)) {
        return false
    }

    val parsedModule = module.parsedModule
    if (parsedModule != null) {
        for (decl in parsedModule.decls) {
            when (decl) {
                is ParsedLetDecl -> {
                    if (decl.isSimpleBinding && decl.variableName == itemName) return true
                }
                is ParsedTypeDecl -> if (decl.name == itemName) return true
                is ParsedAbilityDecl -> if (decl.name == itemName) return true
                is ParsedEffectDecl -> if (decl.name == itemName) return true
                is ParsedPerformerDecl -> if (decl.performerName == itemName) return true
                else -> {}
            }
        }
        return false
    }

    return false
}

/**
 * For an unresolved bare name, find stdlib modules that export a
 * top-level binding with the same name. Powers the "available in
 * std::xxx -- try `use std::xxx::{name}`" hint.
 */
internal fun findStdlibFreeFunctionCarriers(name: String): List<String> {
    val modules = LinkedHashSet<String>()
    for ((hash, qualifiedName) in ContentHashRegistry.allMappings()) {
        val sepIdx = qualifiedName.lastIndexOf("::")
        if (sepIdx <= 0) continue
        val simpleName = qualifiedName.substring(sepIdx + 2)
        if (simpleName != name) continue
        val kind = ContentHashRegistry.kindForHash(hash)
        if (kind != ContentHashRegistry.DeclarationKind.FUNCTION &&
            kind != ContentHashRegistry.DeclarationKind.CONSTANT) continue
        val modulePath = qualifiedName.substring(0, sepIdx)
        modules.add(modulePath)
        if (modules.size >= 3) break
    }
    return modules.toList()
}

/**
 * For an unresolved name, return the type-constructor names of any
 * prelude inherent impls whose operations include that name.
 */
internal fun findPreludeMethodCarriers(name: String): List<String> {
    val precompiled = try { PrecompiledStdlib } catch (_: Exception) { return emptyList() }
    val ctx = try { precompiled.preludeImportContext } catch (_: Throwable) { return emptyList() }
    @Suppress("SENSELESS_COMPARISON") if (ctx == null) return emptyList()
    val carriers = mutableListOf<String>()
    for ((_, impl) in ctx.importedAbilityImpls) {
        if (!impl.isInherent) continue
        if (impl.operations.none { it.name == name }) continue
        val target = impl.targetTypes.firstOrNull() ?: continue
        val targetName = when (target) {
            is ParsedNamedType -> target.name
            is ParsedTypeApp -> target.constructor
            else -> null
        } ?: continue
        if (targetName !in carriers) carriers.add(targetName)
    }
    return carriers
}
