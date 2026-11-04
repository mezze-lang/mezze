package com.mezze.language.typechecker.validation

import com.mezze.language.ast.BuiltinTypes
import com.mezze.language.ast.VariantAliases
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
 *  - qualified names (`mod::Type`) are now CHECKED. They were skipped, justified
 *    as "resolved by the import machinery" — which was written when no resolver
 *    knew a qualified name at all, so `std::syntax::NoSuchTypeHere` and even
 *    `no::such::module::Thing` type-checked: the annotation became a type
 *    application with no identity that unified only with itself, and the program
 *    was accepted until some concrete value had to match it. Now that a
 *    qualified spelling resolves to its declaration, a spelling that resolves to
 *    NOTHING is a dangling reference like any other.
 * Associated-type binding VALUES (`having 'x = T`) and constraint type-args ARE
 * type positions and are walked; the ability/effect NAME itself is not.
 */
internal class UnknownTypeValidator(
    private val registry: DeclarationRegistry,
) {
    /**
     * Which named group claims each tag IN THIS MODULE — see [checkInlineGroupClaims].
     * Set per `validate` run; empty outside one.
     */
    private var claimOwners: Map<String, String> = emptyMap()

    fun validate(module: ParsedModule): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        claimOwners = buildClaimOwners(module)
        for (decl in module.decls) {
            when (decl) {
                is ParsedLetDecl -> {
                    decl.typeAnnotation?.let { walk(it, errors) }
                    // A signature written as `{ c: Pink } -> 1` puts its parameter
                    // annotations on the LAMBDA, not on the let — and the lambda form
                    // is how Mezze normally writes one, so walking only
                    // `decl.typeAnnotation` missed almost every annotation in the
                    // language. `{ c: Pink }` with no `type Pink` anywhere was
                    // accepted, became an uninhabited opaque type, and the error then
                    // surfaced at every call site except the one with the typo.
                    walkLambdaSignatures(decl.value, errors)
                }
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
                checkQualified(annotation, errors)
                for (arg in annotation.args) walk(arg, errors)
            }
            is ParsedSeqType -> walk(annotation.elementType, errors)
            is ParsedRecordType -> for (e in annotation.entries) walk(e.type, errors)
            is ParsedVariantType -> {
                checkInlineGroupClaims(annotation, errors)
                for (c in annotation.constructors) c.dataType?.let { walk(it, errors) }
            }
            is ParsedFunctionType -> {
                walk(annotation.paramType, errors)
                walk(annotation.returnType, errors)
                annotation.effects?.let { walkEffectRow(it, errors) }
                for (c in annotation.abilityConstraints) walkConstraint(c, errors)
            }
        }
    }

    /**
     * A qualified spelling must name something.
     *
     * A BUILTIN is matched on its last segment, because a builtin belongs to no
     * module and `std::collections::map::Map` is the same type as `Map`.
     *
     * Otherwise the whole spelling is looked up: the registry indexes every
     * LOADED type under its qualified name. A module nothing imports is not
     * loaded, so a qualified reference does not itself pull one in — that would
     * be module-resolution work, and until it exists, refusing is the honest
     * answer rather than accepting a type that unifies with nothing.
     */
    private fun checkQualified(annotation: ParsedQualifiedType, errors: MutableList<ValidationError>) {
        if (annotation.typeName.isTypeVarName()) return
        // Globally nameable — a builtin belongs to no module, and a well-known
        // stdlib type resolves bare without an import, so a qualified spelling of
        // either names the same type. Matched on the LAST SEGMENT for exactly
        // that reason; see the converter's qualified arm for why the qualifier
        // goes unchecked here.
        if (BuiltinTypes.isBuiltinTypeName(annotation.typeName)) return
        if (WellKnownHashConstants.resolveTypeHash(annotation.typeName) != null) return
        val qualified = (annotation.modulePath + annotation.typeName).joinToString("::")
        if (registry.lookupType(qualified) != null) return
        if (registry.typeHashFor(qualified) != null) return
        if (WellKnownHashConstants.resolveTypeHash(qualified) != null) return
        errors.add(ValidationError.UnknownType(
            typeName = qualified,
            availableTypes = registry.typeNames.sorted(),
            loc = annotation.loc,
        ))
    }

    /**
     * Type annotations written in a lambda's own signature.
     *
     * Only the SIGNATURE is walked — parameter types, the return type, ability
     * constraints — never the body's expressions, which are not type positions.
     * Nested lambdas are reached through a let's value and through a lambda's
     * body, since a signature can sit at either depth.
     */
    private fun walkLambdaSignatures(expr: ParsedExpr, errors: MutableList<ValidationError>) {
        when (expr) {
            is ParsedLambda -> {
                when (val ps = expr.params) {
                    is ParsedLambdaParams.Typed -> for (f in ps.fields) walk(f.type, errors)
                    else -> {}
                }
                expr.returnType?.let { walk(it, errors) }
                for (c in expr.abilityConstraints) walkConstraint(c, errors)
                walkLambdaSignatures(expr.body, errors)
            }
            is ParsedDo -> {
                for (stmt in expr.statements) when (stmt) {
                    is ParsedStatement.Let -> {
                        stmt.typeAnnotation?.let { walk(it, errors) }
                        walkLambdaSignatures(stmt.value, errors)
                    }
                    is ParsedStatement.Expr -> walkLambdaSignatures(stmt.expr, errors)
                }
                walkLambdaSignatures(expr.result, errors)
            }
            is ParsedIf -> {
                walkLambdaSignatures(expr.condition, errors)
                walkLambdaSignatures(expr.thenBranch, errors)
                walkLambdaSignatures(expr.elseBranch, errors)
            }
            is ParsedMatch -> {
                walkLambdaSignatures(expr.scrutinee, errors)
                for (case in expr.cases) walkLambdaSignatures(case.body, errors)
            }
            is ParsedFunctionCall -> {
                walkLambdaSignatures(expr.function, errors)
                walkLambdaSignatures(expr.arguments, errors)
            }
            else -> {}
        }
    }

    /**
     * Spec 8.3, the inline half: an inline group may not hold a tag that a NAMED
     * group in scope already claims.
     *
     * The asymmetry is the point. A `type` name CLAIMS its tags for the module,
     * deciding what a bare tag means there; an inline group claims nothing,
     * because nothing could ever adopt it — adoption needs a name. So two inline
     * groups sharing a tag are always fine, while an inline group holding an
     * already-claimed tag contradicts a decision the module has made. Writing
     * `| Red |` once `T = | Red, Pink |` is in scope is refused on THAT line
     * rather than letting the call that later fails carry the blame.
     */
    private fun checkInlineGroupClaims(
        annotation: ParsedVariantType,
        errors: MutableList<ValidationError>,
    ) {
        if (annotation.isOpen) return
        val written = annotation.constructors.map { it.name }.toSet()
        for (ctor in annotation.constructors) {
            val owner = claimOwners[ctor.name] ?: continue
            // Writing the group's FULL arm set is writing that group, which is
            // fine — `| Red, Blue |` beside `type Color = | Red, Blue |` is
            // Color, and `| Some { val: 'a }, None |` IS Option. Only a
            // DIFFERENT set is a contradiction: `| Blue |` is Color with Red
            // missing, and `| True |` is Bool with False missing.
            //
            // Comparing arms rather than names is what makes this checkable at
            // all. Refusing any inline group that merely MENTIONED a claimed tag
            // refused every `| Some { val: 'a }, None |` the stdlib and
            // twenty-seven if-let tests legitimately write — 32 failures.
            if (written == armsOf(owner)) return
            errors.add(ValidationError.ConflictingInlineGroup(
                constructorName = ctor.name,
                owningType = owner,
                loc = annotation.loc,
            ))
            return
        }
    }

    /** The arm set of a group named [owner], builtin or declared. */
    private fun armsOf(owner: String): Set<String> {
        VariantAliases.byConstructorSet.entries
            .firstOrNull { it.value == owner }
            ?.let { return it.key.split(",").toSet() }
        val decl = registry.lookupType(owner) ?: return emptySet()
        return when (val def = decl.definition) {
            is ParsedVariantDef -> def.constructors.map { it.name }.toSet()
            is ParsedNewtypeDef -> setOf(def.constructor)
            else -> emptySet()
        }
    }

    /**
     * The groups whose names are in scope HERE: declared in this module, or
     * imported by name. Deliberately not `registry.typeDeclarations`, which is
     * the TRANSITIVE set of everything loaded.
     *
     * That distinction is the whole rule. Using the registry at large made
     * `Option` claim `Some`, so every inline `| Some { val: 'a }, None |` the
     * stdlib and twenty-seven if-let tests legitimately write was refused — 32
     * failures. "In scope by name" and "loaded" are different sets, and the
     * claim rules need the first: a group inside an imported function's
     * signature travels as structure, and only importing the NAME claims
     * anything.
     */
    private fun buildClaimOwners(module: ParsedModule): Map<String, String> = buildMap {
        fun claimAll(typeName: String, decl: ParsedTypeDecl) {
            when (val def = decl.definition) {
                is ParsedVariantDef -> for (c in def.constructors) put(c.name, typeName)
                is ParsedNewtypeDef -> put(def.constructor, typeName)
                else -> {}
            }
        }
        // Builtins are always in scope (spec 1.3), so they claim like any other
        // named group. Safe to include now that writing a group's full arm set
        // is allowed: `| Some { val: 'a }, None |` matches Option's arms and
        // passes, while `| True |` is Bool missing False and does not.
        putAll(BuiltinTypes.reservedConstructorOwner)
        for (decl in module.decls.filterIsInstance<ParsedTypeDecl>()) claimAll(decl.name, decl)
        for (decl in module.decls.filterIsInstance<ParsedUseDecl>()) {
            val path = decl.path
            if (path !is ParsedUsePath.Selective) continue
            for (item in path.items) {
                val local = item.alias ?: item.name
                registry.lookupType(item.name)?.let { claimAll(local, it) }
            }
        }
    }

    private fun checkName(name: String, loc: Loc, errors: MutableList<ValidationError>) {
        if (name.isTypeVarName()) return
        if (BuiltinTypes.isBuiltinTypeName(name)) return
        if (registry.lookupType(name) != null) return
        if (registry.typeHashFor(name) != null) return          // import-scoped / transitive
        if (WellKnownHashConstants.resolveTypeHash(name) != null) return  // stdlib well-known
        // An ABILITY or EFFECT name is a declared thing in another namespace.
        // Whether one may stand in a type position is its own question and not
        // one this validator decides: widening the walk to lambda signatures
        // brought `{ x: ToUrl }` into view, where naming the ability is how a
        // test proves the import resolved. Reporting it as an UNKNOWN type would
        // be wrong twice over — it is known, and it is not a type.
        if (registry.abilityDeclarations.containsKey(name)) return
        if (registry.effectDeclarations.containsKey(name)) return
        errors.add(ValidationError.UnknownType(
            typeName = name,
            availableTypes = registry.typeNames.sorted(),
            loc = loc,
        ))
    }
}
