package com.mezze.language.typechecker.validation

import com.mezze.language.ast.BuiltinTypes
import com.mezze.language.ast.isTypeVarName
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.WellKnownHashConstants
import com.mezze.language.typechecker.context.DeclarationRegistry

/**
 * Rejects dangling type references — an uppercase name in a genuine TYPE position
 * that resolves to no declaration, import, or builtin (e.g. `type Box = | Wrap {
 * val: Gadget } |` with no `type Gadget`). Before this pass such a name was
 * silently accepted (its annotation became a structurally-anonymous type), a
 * soundness hole.
 *
 * The declaration registry is COMPLETE before this pass runs (buildFullRegistry
 * covers forward refs), so a name that fails to resolve is genuinely undeclared,
 * not transiently-unresolved.
 *
 * SCOPING — only genuine TYPE positions are walked. OUT of scope (never flagged):
 *  - effect names in `<...>` rows and effect-op effect lists (separate namespace),
 *  - ability names in `with`/`having` constraint position (separate namespace),
 *  - type variables (`'a`, lowercase-initial — `isTypeVarName`),
 *  - qualified names (`mod::Type`) — carry an explicit module path resolved by the
 *    import machinery, a different namespace; skipped to avoid false positives
 *    (their args ARE walked).
 * Associated-type binding VALUES (`having 'x = T`) and constraint type-args ARE
 * type positions and are walked; the ability/effect NAME itself is not.
 */
internal class UnknownTypeValidator(
    private val registry: DeclarationRegistry,
) {
    fun validate(module: ParsedModule): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        for (decl in module.decls) {
            when (decl) {
                is ParsedLetDecl -> decl.typeAnnotation?.let { walk(it, errors) }
                is ParsedTypeDecl -> walkTypeDef(decl.definition, errors)
                is ParsedAbilityDecl -> for (op in decl.operations) {
                    walk(op.paramType, errors)
                    walk(op.returnType, errors)
                    op.effects?.let { walkEffectRow(it, errors) }
                    for (c in op.abilityConstraints) walkConstraint(c, errors)
                }
                is ParsedEffectDecl -> for (op in decl.operations) {
                    walk(op.paramType, errors)
                    walk(op.returnType, errors)
                }
                else -> {}
            }
        }
        return errors
    }

    private fun walkTypeDef(def: ParsedTypeDef, errors: MutableList<ValidationError>) {
        when (def) {
            is ParsedRecordDef -> for (f in def.fields) walk(f.type, errors)
            is ParsedVariantDef -> for (c in def.constructors) c.dataType?.let { walk(it, errors) }
            is ParsedNewtypeDef -> walk(def.wrappedType, errors)
            is ParsedTypeAliasDef -> walk(def.aliasedType, errors)
        }
    }

    private fun walkConstraint(c: ParsedFnAbilityConstraint, errors: MutableList<ValidationError>) {
        // The ability NAME is not a type reference; its type-args and having-values ARE.
        for (arg in c.typeArgs) walk(arg, errors)
        for (v in c.associatedTypeBindings.values) walk(v, errors)
    }

    private fun walkEffectRow(row: ParsedEffectRow, errors: MutableList<ValidationError>) {
        // Effect NAMES are a separate namespace; only associated-type VALUES are types.
        for (ann in row.effectAnnotations) for (v in ann.associatedTypes.values) walk(v, errors)
    }

    private fun walk(annotation: ParsedTypeAnnotation, errors: MutableList<ValidationError>) {
        when (annotation) {
            is ParsedNamedType -> checkName(annotation.name, annotation.loc, errors)
            is ParsedTypeApp -> {
                checkName(annotation.constructor, annotation.loc, errors)
                for (arg in annotation.args) walk(arg, errors)
            }
            is ParsedQualifiedType -> {
                // Qualified name: explicit module path, resolved by import machinery
                // (separate namespace). Skip the constructor name; still walk args.
                for (arg in annotation.args) walk(arg, errors)
            }
            is ParsedSeqType -> walk(annotation.elementType, errors)
            is ParsedRecordType -> for (e in annotation.entries) walk(e.type, errors)
            is ParsedVariantType -> for (c in annotation.constructors) c.dataType?.let { walk(it, errors) }
            is ParsedFunctionType -> {
                walk(annotation.paramType, errors)
                walk(annotation.returnType, errors)
                annotation.effects?.let { walkEffectRow(it, errors) }
                for (c in annotation.abilityConstraints) walkConstraint(c, errors)
            }
        }
    }

    private fun checkName(name: String, loc: Loc, errors: MutableList<ValidationError>) {
        if (name.isTypeVarName()) return
        if (BuiltinTypes.isBuiltinTypeName(name)) return
        if (registry.lookupType(name) != null) return
        if (registry.typeHashFor(name) != null) return          // import-scoped / transitive
        if (WellKnownHashConstants.resolveTypeHash(name) != null) return  // stdlib well-known
        errors.add(ValidationError.UnknownType(
            typeName = name,
            availableTypes = registry.typeNames.sorted(),
            loc = loc,
        ))
    }
}
