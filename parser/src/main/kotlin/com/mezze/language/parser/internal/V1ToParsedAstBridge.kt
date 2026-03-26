package com.mezze.language.parser.internal

import com.mezze.language.ast.*
import com.mezze.language.parsedast.*
import com.mezze.language.parsedast.Visibility as PVisibility

/**
 * Bridge: converts ParsedAST (ParsedModule) back to v1 AST (Program).
 *
 * This is the reverse bridge used by parse() for backward compatibility
 * while the rest of the codebase still expects Program.
 *
 * Once the typechecker and interpreter consume ParsedModule directly,
 * this function and parse() can be removed.
 */
internal fun parsedModuleToV1Program(module: ParsedModule, comments: List<Comment> = emptyList()): Program {
    val items = module.decls.mapNotNull { decl -> parsedDeclToV1Item(decl, module.path) }
    return Program(items, comments)
}

private fun parsedDeclToV1Item(decl: ParsedDecl, file: String): ProgramItem? {
    return when (decl) {
        is ParsedLetDecl -> {
            val loc = parsedLocToV1(decl.loc)
            LetBinding(
                pattern = parsedPatternToV1(decl.pattern, file),
                visibility = parsedVisibilityToV1(decl.visibility),
                typeAnnotation = decl.typeAnnotation?.let { parsedTypeAnnotationToV1(it, file) },
                inferredType = null,
                typeScheme = null,
                expression = parsedExprToV1Expr(decl.value, file)
            )
        }
        is ParsedTypeDecl -> {
            val loc = parsedLocToV1(decl.loc)
            TypeDeclaration(
                name = decl.name,
                visibility = parsedVisibilityToV1(decl.visibility),
                typeParams = decl.typeParams,
                definition = parsedTypeDefToV1(decl.definition, file),
                inferredType = null,
                sourceLocation = loc
            )
        }
        is ParsedEffectDecl -> {
            val loc = parsedLocToV1(decl.loc)
            EffectDeclaration(
                name = decl.name,
                operations = decl.operations.map { op ->
                    EffectOperation(
                        name = op.name,
                        paramType = parsedTypeAnnotationToV1(op.paramType, file),
                        returnType = parsedTypeAnnotationToV1(op.returnType, file),
                        effects = op.effects
                    )
                },
                associatedTypes = decl.associatedTypes,
                inferredType = null,
                sourceLocation = loc
            )
        }
        is ParsedPerformerDecl -> {
            val loc = parsedLocToV1(decl.loc)
            ImplDeclaration(
                effect = QualifiedRef(
                    localName = decl.effect.localName,
                    qualifier = decl.effect.qualifier?.let { ModulePath(it.segments) },
                    resolvedContentHash = null,
                    sourceLocation = parsedLocToV1(decl.effect.loc)
                ),
                performerName = decl.performerName,
                performerParams = decl.params.map { p ->
                    PerformerParameter(p.name, parsedTypeAnnotationToV1(p.type, file))
                },
                associatedTypeBindings = decl.associatedTypeBindings.mapValues { (_, v) -> parsedTypeAnnotationToV1(v, file) },
                operations = decl.operations.map { op ->
                    PerformerOperation(
                        name = op.name,
                        paramType = op.paramType?.let { parsedTypeAnnotationToV1(it, file) },
                        returnType = op.returnType?.let { parsedTypeAnnotationToV1(it, file) },
                        implementation = parsedExprToV1Expr(op.implementation, file),
                        effects = op.effects?.let { v1EffectRowFromParsed(it) }
                    )
                },
                inferredType = null,
                sourceLocation = loc
            )
        }
        is ParsedAbilityDecl -> {
            val loc = parsedLocToV1(decl.loc)
            AbilityDeclaration(
                name = decl.name,
                typeParams = decl.typeParams,
                receiverTypeParam = decl.receiverTypeParam,
                associatedTypes = decl.associatedTypes,
                constraints = decl.constraints.map { c ->
                    AbilityConstraint(
                        abilityName = c.abilityName,
                        typeParam = c.typeParam,
                        typeArgs = c.typeArgs.map { ta ->
                            val name = if (ta is ParsedNamedType) ta.name else ta.toString()
                            MezzeType.VarType(TypeVar(name))
                        }
                    )
                },
                dependencies = decl.dependencies.map { d ->
                    AbilityDependency(
                        abilityName = d.abilityName,
                        typeArgs = d.typeArgs.map { parsedTypeAnnotationToV1(it, file) }
                    )
                },
                operations = decl.operations.map { op ->
                    AbilityOperation(
                        name = op.name,
                        paramType = parsedTypeAnnotationToV1(op.paramType, file),
                        returnType = parsedTypeAnnotationToV1(op.returnType, file),
                        effects = op.effects?.let { v1EffectRowFromParsed(it) },
                        abilityConstraints = op.abilityConstraints.map { c ->
                            FunctionAbilityConstraint(
                                abilityName = c.abilityName,
                                typeArgs = c.typeArgs.map { parsedTypeAnnotationToV1(it, file) },
                                associatedTypeBindings = c.associatedTypeBindings.mapValues { (_, v) -> parsedTypeAnnotationToV1(v, file) }
                            )
                        }
                    )
                },
                inferredType = null,
                sourceLocation = loc
            )
        }
        is ParsedImplAbilityDecl -> {
            val loc = parsedLocToV1(decl.loc)
            ImplAbilityDeclaration(
                target = when (val t = decl.target) {
                    is ParsedImplTarget.Named -> ImplTarget.Named(
                        QualifiedRef(
                            localName = t.ability.localName,
                            qualifier = t.ability.qualifier?.let { ModulePath(it.segments) },
                            resolvedContentHash = null,
                            sourceLocation = parsedLocToV1(t.ability.loc)
                        )
                    )
                    is ParsedImplTarget.Inherent -> ImplTarget.Inherent
                },
                targetTypes = decl.targetTypes.map { parsedTypeAnnotationToV1(it, file) },
                abilityTypeArgs = decl.abilityTypeArgs.map { parsedTypeAnnotationToV1(it, file) },
                forallVars = decl.forallVars,
                associatedTypeBindings = decl.associatedTypeBindings.mapValues { (_, v) -> parsedTypeAnnotationToV1(v, file) },
                operations = decl.operations.map { op ->
                    ImplAbilityOperation(
                        name = op.name,
                        paramType = op.paramType?.let { parsedTypeAnnotationToV1(it, file) },
                        returnType = op.returnType?.let { parsedTypeAnnotationToV1(it, file) },
                        implementation = parsedExprToV1Expr(op.implementation, file),
                        abilityConstraints = op.abilityConstraints.map { c ->
                            FunctionAbilityConstraint(
                                abilityName = c.abilityName,
                                typeArgs = c.typeArgs.map { parsedTypeAnnotationToV1(it, file) },
                                associatedTypeBindings = c.associatedTypeBindings.mapValues { (_, v) -> parsedTypeAnnotationToV1(v, file) }
                            )
                        }
                    )
                },
                inferredType = null,
                sourceLocation = loc
            )
        }
        is ParsedUseDecl -> {
            val loc = parsedLocToV1(decl.loc)
            UseDeclaration(
                path = parsedUsePathToV1(decl.path),
                alias = decl.alias,
                sourceLocation = loc
            )
        }
        is ParsedPubUseDecl -> {
            val loc = parsedLocToV1(decl.loc)
            PubUseDeclaration(
                path = parsedUsePathToV1(decl.path),
                alias = decl.alias,
                sourceLocation = loc
            )
        }
        is ParsedTopLevelExpr -> {
            TopLevelExpression(
                expression = parsedExprToV1Expr(decl.expression, file)
            )
        }
    }
}

private fun parsedLocToV1(loc: Loc): SourceLocation {
    return SourceLocation(loc.startLine, loc.startCol, loc.file)
}

private fun parsedVisibilityToV1(v: PVisibility): com.mezze.language.ast.Visibility {
    return when (v) {
        PVisibility.PUBLIC -> com.mezze.language.ast.Visibility.PUBLIC
        PVisibility.PRIVATE -> com.mezze.language.ast.Visibility.PRIVATE
    }
}

private fun parsedTypeDefToV1(def: ParsedTypeDef, file: String): TypeDefinition {
    return when (def) {
        is ParsedRecordDef -> RecordDefinition(
            def.fields.map { f -> RecordFieldDef(f.name, parsedTypeAnnotationToV1(f.type, file)) },
            def.isOpen
        )
        is ParsedVariantDef -> VariantDefinition(
            def.constructors.map { c ->
                VariantConstructorDef(c.name, c.dataType?.let { parsedTypeAnnotationToV1(it, file) })
            },
            def.isOpen
        )
        is ParsedNewtypeDef -> NewtypeDefinition(
            def.constructor,
            parsedTypeAnnotationToV1(def.wrappedType, file)
        )
    }
}

private fun parsedUsePathToV1(path: ParsedUsePath): UsePath {
    return when (path) {
        is ParsedUsePath.Module -> ModulePath(path.path.segments)
        is ParsedUsePath.Selective -> SelectivePath(
            path.modulePath.segments,
            path.items.map { item -> ImportItem(item.name, item.alias) }
        )
    }
}

private fun v1EffectRowFromParsed(row: ParsedEffectRow): EffectRowAnnotation {
    return EffectRowAnnotation(
        effects = row.effects,
        isPolymorphic = row.isPolymorphic,
        isPerformed = row.isPerformed
    )
}

/**
 * Bridge: converts v1 AST (Program) to ParsedAST (ParsedModule).
 *
 * This is temporary — deleted once the parser emits ParsedAST directly.
 */
internal fun v1ProgramToParsedModule(program: Program, file: String): ParsedModule {
    val decls = program.items.mapNotNull { item -> v1ItemToParsedDecl(item, file) }
    val orphanComments = program.comments.map { c ->
        ParsedComment(
            text = c.text.removePrefix("#").removePrefix("#").trim(),
            isDoc = c.text.startsWith("##"),
            loc = Loc(file, c.line, c.column, c.line, c.column + c.text.length - 1)
        )
    }
    return ParsedModule(file, decls, orphanComments)
}

private fun v1ItemToParsedDecl(item: ProgramItem, file: String): ParsedDecl? {
    return when (item) {
        is LetBinding -> v1LetBindingToDecl(item, file)
        is TypeDeclaration -> v1TypeDeclToDecl(item, file)
        is AbilityDeclaration -> v1AbilityDeclToDecl(item, file)
        is ImplAbilityDeclaration -> v1ImplAbilityToDecl(item, file)
        is EffectDeclaration -> v1EffectDeclToDecl(item, file)
        is ImplDeclaration -> v1PerformerDeclToDecl(item, file)
        is UseDeclaration -> v1UseDeclToDecl(item, file)
        is PubUseDeclaration -> v1PubUseDeclToDecl(item, file)
        is TopLevelExpression -> v1TopLevelExprToDecl(item, file)
    }
}

// ============================================================================
// LET BINDING
// ============================================================================

private fun v1LetBindingToDecl(lb: LetBinding, file: String): ParsedLetDecl {
    val loc = v1SourceLocToLoc(lb.expression.sourceLocation, file)
    return ParsedLetDecl(
        pattern = v1PatternToParsed(lb.pattern, file),
        typeAnnotation = lb.typeAnnotation?.let { v1TypeAnnotationToParsed(it, file) },
        value = v1ExprToParsedExpr(lb.expression, file),
        visibility = v1Visibility(lb.visibility),
        loc = loc
    )
}

// ============================================================================
// TYPE DECLARATION
// ============================================================================

private fun v1TypeDeclToDecl(td: TypeDeclaration, file: String): ParsedTypeDecl {
    val loc = v1SourceLocToLoc(td.sourceLocation, file)
    val def = when (val d = td.definition) {
        is RecordDefinition -> ParsedRecordDef(
            d.fields.map { f -> ParsedRecordFieldDef(f.name, v1TypeAnnotationToParsed(f.type, file), loc) },
            d.isOpen,
            loc
        )
        is VariantDefinition -> ParsedVariantDef(
            d.constructors.map { c -> ParsedVariantCtorDef(c.name, c.dataType?.let { v1TypeAnnotationToParsed(it, file) }, loc) },
            d.isOpen,
            loc
        )
        is NewtypeDefinition -> ParsedNewtypeDef(d.constructor, v1TypeAnnotationToParsed(d.wrappedType, file), loc)
    }
    return ParsedTypeDecl(
        name = td.name,
        typeParams = td.typeParams,
        definition = def,
        visibility = v1Visibility(td.visibility),
        loc = loc
    )
}

// ============================================================================
// ABILITY DECLARATION
// ============================================================================

private fun v1AbilityDeclToDecl(ad: AbilityDeclaration, file: String): ParsedAbilityDecl {
    val loc = v1SourceLocToLoc(ad.sourceLocation, file)
    return ParsedAbilityDecl(
        name = ad.name,
        typeParams = ad.typeParams,
        receiverTypeParam = ad.receiverTypeParam,
        associatedTypes = ad.associatedTypes,
        constraints = ad.constraints.map { c ->
            ParsedAbilityConstraint(c.abilityName, c.typeParam, c.typeArgs.map { v1TypeAnnotationToParsed(v1MezzeTypeToAnnotation(it), file) }, loc)
        },
        dependencies = ad.dependencies.map { d ->
            ParsedAbilityDep(d.abilityName, d.typeArgs.map { v1TypeAnnotationToParsed(it, file) }, loc)
        },
        operations = ad.operations.map { op ->
            ParsedAbilityOp(
                name = op.name,
                paramType = v1TypeAnnotationToParsed(op.paramType, file),
                returnType = v1TypeAnnotationToParsed(op.returnType, file),
                effects = op.effects?.let { v1EffectRowToParsed(it, file) },
                abilityConstraints = op.abilityConstraints.map { c ->
                    ParsedFnAbilityConstraint(
                        c.abilityName,
                        c.typeArgs.map { v1TypeAnnotationToParsed(it, file) },
                        c.associatedTypeBindings.mapValues { (_, v) -> v1TypeAnnotationToParsed(v, file) },
                        loc
                    )
                },
                loc = loc
            )
        },
        loc = loc
    )
}

// ============================================================================
// ABILITY IMPL
// ============================================================================

private fun v1ImplAbilityToDecl(impl: ImplAbilityDeclaration, file: String): ParsedImplAbilityDecl {
    val loc = v1SourceLocToLoc(impl.sourceLocation, file)
    val target = when (val t = impl.target) {
        is ImplTarget.Named -> ParsedImplTarget.Named(
            ParsedQualifiedRef(
                t.ability.localName,
                t.ability.qualifier?.let { ParsedModulePath(it.segments) },
                v1SourceLocToLoc(t.ability.sourceLocation, file)
            )
        )
        is ImplTarget.Inherent -> ParsedImplTarget.Inherent
    }
    return ParsedImplAbilityDecl(
        target = target,
        targetTypes = impl.targetTypes.map { v1TypeAnnotationToParsed(it, file) },
        abilityTypeArgs = impl.abilityTypeArgs.map { v1TypeAnnotationToParsed(it, file) },
        forallVars = impl.forallVars,
        associatedTypeBindings = impl.associatedTypeBindings.mapValues { (_, v) -> v1TypeAnnotationToParsed(v, file) },
        operations = impl.operations.map { op ->
            ParsedImplOp(
                name = op.name,
                paramType = op.paramType?.let { v1TypeAnnotationToParsed(it, file) },
                returnType = op.returnType?.let { v1TypeAnnotationToParsed(it, file) },
                implementation = v1ExprToParsedExpr(op.implementation, file),
                abilityConstraints = op.abilityConstraints.map { c ->
                    ParsedFnAbilityConstraint(
                        c.abilityName,
                        c.typeArgs.map { v1TypeAnnotationToParsed(it, file) },
                        c.associatedTypeBindings.mapValues { (_, v) -> v1TypeAnnotationToParsed(v, file) },
                        loc
                    )
                },
                loc = loc
            )
        },
        loc = loc
    )
}

// ============================================================================
// EFFECT DECLARATION
// ============================================================================

private fun v1EffectDeclToDecl(ed: EffectDeclaration, file: String): ParsedEffectDecl {
    val loc = v1SourceLocToLoc(ed.sourceLocation, file)
    return ParsedEffectDecl(
        name = ed.name,
        operations = ed.operations.map { op ->
            ParsedEffectOp(
                name = op.name,
                paramType = v1TypeAnnotationToParsed(op.paramType, file),
                returnType = v1TypeAnnotationToParsed(op.returnType, file),
                effects = op.effects,
                loc = loc
            )
        },
        associatedTypes = ed.associatedTypes,
        loc = loc
    )
}

// ============================================================================
// PERFORMER (EFFECT IMPL)
// ============================================================================

private fun v1PerformerDeclToDecl(impl: ImplDeclaration, file: String): ParsedPerformerDecl {
    val loc = v1SourceLocToLoc(impl.sourceLocation, file)
    return ParsedPerformerDecl(
        effect = ParsedQualifiedRef(
            impl.effect.localName,
            impl.effect.qualifier?.let { ParsedModulePath(it.segments) },
            v1SourceLocToLoc(impl.effect.sourceLocation, file)
        ),
        performerName = impl.performerName,
        params = impl.performerParams.map { p ->
            ParsedPerformerParam(p.name, v1TypeAnnotationToParsed(p.type, file), loc)
        },
        associatedTypeBindings = impl.associatedTypeBindings.mapValues { (_, v) -> v1TypeAnnotationToParsed(v, file) },
        operations = impl.operations.map { op ->
            ParsedPerformerOp(
                name = op.name,
                paramType = op.paramType?.let { v1TypeAnnotationToParsed(it, file) },
                returnType = op.returnType?.let { v1TypeAnnotationToParsed(it, file) },
                implementation = v1ExprToParsedExpr(op.implementation, file),
                effects = op.effects?.let { v1EffectRowToParsed(it, file) },
                loc = loc
            )
        },
        loc = loc
    )
}

// ============================================================================
// USE DECLARATIONS
// ============================================================================

private fun v1UseDeclToDecl(ud: UseDeclaration, file: String): ParsedUseDecl {
    val loc = v1SourceLocToLoc(ud.sourceLocation, file)
    return ParsedUseDecl(
        path = v1UsePathToParsed(ud.path, loc),
        alias = ud.alias,
        loc = loc
    )
}

private fun v1PubUseDeclToDecl(ud: PubUseDeclaration, file: String): ParsedPubUseDecl {
    val loc = v1SourceLocToLoc(ud.sourceLocation, file)
    return ParsedPubUseDecl(
        path = v1UsePathToParsed(ud.path, loc),
        alias = ud.alias,
        loc = loc
    )
}

private fun v1UsePathToParsed(path: UsePath, loc: Loc): ParsedUsePath {
    return when (path) {
        is ModulePath -> ParsedUsePath.Module(ParsedModulePath(path.segments), loc)
        is SelectivePath -> ParsedUsePath.Selective(
            ParsedModulePath(path.module),
            path.items.map { item ->
                ParsedImportItem(item.name, item.alias, loc)
            },
            loc
        )
    }
}

// ============================================================================
// TOP-LEVEL EXPRESSION
// ============================================================================

private fun v1TopLevelExprToDecl(tle: TopLevelExpression, file: String): ParsedTopLevelExpr {
    val loc = v1SourceLocToLoc(tle.expression.sourceLocation, file)
    return ParsedTopLevelExpr(
        expression = v1ExprToParsedExpr(tle.expression, file),
        loc = loc
    )
}

// ============================================================================
// HELPERS
// ============================================================================

private fun v1Visibility(v: com.mezze.language.ast.Visibility): PVisibility {
    return when (v) {
        com.mezze.language.ast.Visibility.PUBLIC -> PVisibility.PUBLIC
        com.mezze.language.ast.Visibility.PRIVATE -> PVisibility.PRIVATE
    }
}

/**
 * Convert a MezzeType back to a TypeAnnotation (for constraints that store MezzeType).
 * This is lossy but sufficient for the bridge.
 */
private fun v1MezzeTypeToAnnotation(type: MezzeType): TypeAnnotation {
    return when (type) {
        is MezzeType.IntType -> IdentifierTypeAnnotation("Int")
        is MezzeType.FloatType -> IdentifierTypeAnnotation("Float")
        is MezzeType.StringType -> IdentifierTypeAnnotation("Str")
        is MezzeType.BoolType -> IdentifierTypeAnnotation("Bool")
        is MezzeType.NatType -> IdentifierTypeAnnotation("Nat")
        is MezzeType.FracType -> IdentifierTypeAnnotation("Frac")
        is MezzeType.CharType -> IdentifierTypeAnnotation("Char")
        is MezzeType.VarType -> IdentifierTypeAnnotation(type.variable.name)
        else -> IdentifierTypeAnnotation(type.toString())
    }
}
