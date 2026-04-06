package com.mezze.language.typechecker

import com.mezze.language.ast.*
import com.mezze.language.ast.hashing.MezzeHash
import com.mezze.language.parsedast.*
import com.mezze.language.parsedast.Visibility as PVisibility
import com.mezze.language.typedast.*
import com.mezze.language.typedast.Hash
import com.mezze.language.typedast.TypeScheme as TypedTypeScheme
import com.mezze.language.typechecker.module.ModuleImportContext
import java.nio.file.Path
import java.nio.file.Paths

/**
 * TypeCheckerV2 — adapts the existing typechecker to consume ParsedAST and emit TypedAST.
 *
 * Strategy:
 * 1. Convert ParsedModule → v1 Program (reverse bridge)
 * 2. Run existing 7-phase typechecker
 * 3. Convert TypeCheckResult → TypedModule (with proper hashes)
 *
 * This is the migration path. Eventually the internal phases are rewritten
 * to work with TypedAST directly and the v1 bridge is removed.
 */
class TypeCheckerV2 {
    private val v1TypeChecker = TypeChecker()

    sealed class Result {
        data class Success(
            val module: TypedModule,
            val loadedModules: Map<String, com.mezze.language.typechecker.module.TypedModule> = emptyMap()
        ) : Result()
        data class Failure(val errors: List<TypeChecker.TypeCheckError>) : Result()
    }

    /**
     * Type-check a ParsedModule and produce a TypedModule.
     *
     * All declarations in the output have content hashes.
     * All cross-references use hashes, not strings.
     */
    fun check(
        module: ParsedModule,
        currentPath: Path = Paths.get("."),
        enablePrelude: Boolean = false
    ): Result {
        // Step 1: Convert ParsedModule → v1 Program
        val v1Program = parsedModuleToV1Program(module)

        // Step 2: Run existing typechecker
        val v1Result = v1TypeChecker.checkProgram(
            v1Program,
            currentPath = currentPath,
            currentModulePath = null,
            enablePrelude = enablePrelude
        )

        // Step 3: Convert result
        return when (v1Result) {
            is TypeChecker.TypeCheckResult.Success -> {
                val typedModule = v1ResultToTypedModule(
                    module.path,
                    v1Result.program,
                    v1Result.monomorphicInstances,
                    v1Result.importContext
                )
                Result.Success(typedModule, v1Result.loadedModules)
            }
            is TypeChecker.TypeCheckResult.Failure -> {
                Result.Failure(v1Result.errors)
            }
        }
    }

    /**
     * Convert a v1 TypedModule (from module system) to v2 TypedModule.
     * Used to convert loaded dependency modules for the monomorphizer.
     */
    fun convertV1TypedModule(
        path: String,
        v1Module: com.mezze.language.typechecker.module.TypedModule
    ): TypedModule {
        return v1ResultToTypedModule(
            path,
            v1Module.program,
            v1Module.monomorphicInstances,
            null
        )
    }

    // ========================================================================
    // ParsedModule → v1 Program (reverse bridge)
    // ========================================================================

    /** Convert ParsedModule to v1 Program — public for TypeChecker.checkParsedModuleWithDeps */
    fun parsedModuleToV1(module: ParsedModule): Program = parsedModuleToV1Program(module)

    private fun parsedModuleToV1Program(module: ParsedModule): Program {
        val items = module.decls.mapNotNull { decl -> parsedDeclToV1Item(decl) }
        return Program(items)
    }

    private fun parsedDeclToV1Item(decl: ParsedDecl): ProgramItem? {
        return when (decl) {
            is ParsedLetDecl -> LetBinding(
                pattern = parsedPatternToV1(decl.pattern),
                visibility = parsedVisToV1(decl.visibility),
                typeAnnotation = decl.typeAnnotation?.let { parsedTypeToV1(it) },
                expression = parsedExprToV1(decl.value)
            )
            is ParsedTypeDecl -> TypeDeclaration(
                name = decl.name,
                visibility = parsedVisToV1(decl.visibility),
                typeParams = decl.typeParams,
                definition = parsedTypeDefToV1(decl.definition),
                sourceLocation = locToV1(decl.loc)
            )
            is ParsedAbilityDecl -> AbilityDeclaration(
                name = decl.name,
                typeParams = decl.typeParams,
                receiverTypeParam = decl.receiverTypeParam,
                associatedTypes = decl.associatedTypes,
                constraints = decl.constraints.map { c ->
                    AbilityConstraint(c.abilityName, c.typeParam, emptyList())
                },
                dependencies = decl.dependencies.map { d ->
                    AbilityDependency(d.abilityName, d.typeArgs.map { parsedTypeToV1(it) })
                },
                operations = decl.operations.map { op ->
                    AbilityOperation(
                        name = op.name,
                        paramType = parsedTypeToV1(op.paramType),
                        returnType = parsedTypeToV1(op.returnType),
                        effects = op.effects?.let { parsedEffectRowToV1(it) },
                        abilityConstraints = op.abilityConstraints.map { parsedFnConstraintToV1(it) }
                    )
                },
                sourceLocation = locToV1(decl.loc)
            )
            is ParsedImplAbilityDecl -> {
                val target = when (val t = decl.target) {
                    is ParsedImplTarget.Named -> ImplTarget.Named(
                        QualifiedRef(
                            t.ability.localName,
                            t.ability.qualifier?.let { ModulePath(it.segments) }
                        )
                    )
                    is ParsedImplTarget.Inherent -> ImplTarget.Inherent
                }
                ImplAbilityDeclaration(
                    target = target,
                    targetTypes = decl.targetTypes.map { parsedTypeToV1(it) },
                    abilityTypeArgs = decl.abilityTypeArgs.map { parsedTypeToV1(it) },
                    forallVars = decl.forallVars,
                    associatedTypeBindings = decl.associatedTypeBindings.mapValues { (_, v) -> parsedTypeToV1(v) },
                    operations = decl.operations.map { op ->
                        ImplAbilityOperation(
                            name = op.name,
                            paramType = op.paramType?.let { parsedTypeToV1(it) },
                            returnType = op.returnType?.let { parsedTypeToV1(it) },
                            implementation = parsedExprToV1(op.implementation),
                            abilityConstraints = op.abilityConstraints.map { parsedFnConstraintToV1(it) }
                        )
                    },
                    sourceLocation = locToV1(decl.loc)
                )
            }
            is ParsedEffectDecl -> EffectDeclaration(
                name = decl.name,
                operations = decl.operations.map { op ->
                    EffectOperation(
                        name = op.name,
                        paramType = parsedTypeToV1(op.paramType),
                        returnType = parsedTypeToV1(op.returnType),
                        effects = op.effects
                    )
                },
                associatedTypes = decl.associatedTypes,
                sourceLocation = locToV1(decl.loc)
            )
            is ParsedPerformerDecl -> ImplDeclaration(
                effect = QualifiedRef(
                    decl.effect.localName,
                    decl.effect.qualifier?.let { ModulePath(it.segments) }
                ),
                performerName = decl.performerName,
                performerParams = decl.params.map { p ->
                    PerformerParameter(p.name, parsedTypeToV1(p.type))
                },
                associatedTypeBindings = decl.associatedTypeBindings.mapValues { (_, v) -> parsedTypeToV1(v) },
                operations = decl.operations.map { op ->
                    PerformerOperation(
                        name = op.name,
                        paramType = op.paramType?.let { parsedTypeToV1(it) },
                        returnType = op.returnType?.let { parsedTypeToV1(it) },
                        implementation = parsedExprToV1(op.implementation),
                        effects = op.effects?.let { parsedEffectRowToV1(it) }
                    )
                },
                sourceLocation = locToV1(decl.loc)
            )
            is ParsedUseDecl -> {
                val path = parsedUsePathToV1(decl.path)
                UseDeclaration(path, decl.alias, locToV1(decl.loc))
            }
            is ParsedPubUseDecl -> {
                val path = parsedUsePathToV1(decl.path)
                PubUseDeclaration(path, decl.alias, locToV1(decl.loc))
            }
            is ParsedTopLevelExpr -> TopLevelExpression(parsedExprToV1(decl.expression))
        }
    }

    // ========================================================================
    // Expression conversion: ParsedExpr → v1 Expression
    // ========================================================================

    private fun parsedExprToV1(expr: ParsedExpr): Expression {
        val loc = locToV1(expr.loc)
        return when (expr) {
            is ParsedIntLit -> IntLiteral(expr.value, sourceLocation = loc)
            is ParsedNatLit -> NatLiteral(expr.value, sourceLocation = loc)
            is ParsedFloatLit -> FloatLiteral(expr.value, sourceLocation = loc)
            is ParsedFracLit -> FractionLiteral(expr.numerator, expr.denominator, sourceLocation = loc)
            is ParsedCharLit -> IntLiteral(expr.value.code.toLong(), sourceLocation = loc) // v1 has no CharLiteral
            is ParsedStringLit -> {
                val isInterpolated = expr.parts.size > 1 || expr.parts.any { it is ParsedStringPart.Interpolation }
                if (isInterpolated) {
                    val parts = expr.parts.map { part ->
                        when (part) {
                            is ParsedStringPart.Literal -> StringPart(part.value) as StringInterpolationPart
                            is ParsedStringPart.Interpolation -> ExpressionPart(parsedExprToV1(part.expr))
                        }
                    }
                    val fullText = expr.parts.filterIsInstance<ParsedStringPart.Literal>().joinToString("") { it.value }
                    StringLiteral(fullText, parts, sourceLocation = loc)
                } else {
                    val text = (expr.parts.firstOrNull() as? ParsedStringPart.Literal)?.value ?: ""
                    StringLiteral(text, sourceLocation = loc)
                }
            }
            is ParsedIdentifier -> Identifier(
                localName = expr.localName,
                qualifier = expr.qualifier?.let { ModulePath(it.segments) },
                sourceLocation = loc
            )
            is ParsedLambda -> {
                val params = when (val p = expr.params) {
                    is ParsedLambdaParams.Typed -> TypedParameters(
                        RecordTypeAnnotation(p.fields.map { f ->
                            RecordTypeEntry(f.name, parsedTypeToV1(f.type))
                        })
                    )
                    is ParsedLambdaParams.Untyped -> UntypedParameters(p.names, p.isOpen)
                }
                LambdaExpression(
                    parameters = params,
                    body = parsedExprToV1(expr.body),
                    returnType = expr.returnType?.let { parsedTypeToV1(it) },
                    abilityConstraints = expr.abilityConstraints.map { parsedFnConstraintToV1(it) },
                    sourceLocation = loc
                )
            }
            is ParsedFunctionCall -> FunctionCall(
                function = parsedExprToV1(expr.function),
                arguments = parsedExprToV1(expr.arguments),
                sourceLocation = loc
            )
            is ParsedPipe -> PipeExpression(
                left = parsedExprToV1(expr.left),
                right = parsedExprToV1(expr.right),
                sourceLocation = loc
            )
            is ParsedPipePlaceholder -> PipePlaceholder(sourceLocation = loc)
            is ParsedRecordLit -> RecordLiteral(
                entries = expr.entries.map { entry ->
                    when (entry) {
                        is ParsedRecordEntry.Field -> RecordFieldEntry(
                            entry.name,
                            entry.value?.let { parsedExprToV1(it) }
                        )
                        is ParsedRecordEntry.Spread -> RecordSpreadEntry(parsedExprToV1(entry.expr))
                    }
                },
                sourceLocation = loc
            )
            is ParsedMemberAccess -> MemberAccess(
                receiver = parsedExprToV1(expr.receiver),
                member = expr.member,
                sourceLocation = loc
            )
            is ParsedVariantConstruct -> VariantConstruction(
                constructor = expr.constructor,
                value = expr.value?.let { parsedExprToV1(it) },
                sourceLocation = loc
            )
            is ParsedIf -> IfExpression(
                condition = parsedExprToV1(expr.condition),
                thenBranch = parsedExprToV1(expr.thenBranch),
                elseBranch = parsedExprToV1(expr.elseBranch),
                sourceLocation = loc
            )
            is ParsedMatch -> MatchExpression(
                expression = parsedExprToV1(expr.scrutinee),
                cases = expr.cases.map { c ->
                    MatchCase(
                        patterns = c.patterns.map { parsedPatternToV1(it) },
                        guard = c.guard?.let { parsedExprToV1(it) },
                        expression = parsedExprToV1(c.body),
                        ifLetBindings = c.ifLetBindings.map { g ->
                            IfLetGuard(
                                parsedPatternToV1(g.pattern),
                                parsedExprToV1(g.expr),
                                locToV1(g.loc)
                            )
                        }
                    )
                },
                sourceLocation = loc
            )
            is ParsedDo -> DoExpression(
                statements = expr.statements.map { stmt ->
                    when (stmt) {
                        is ParsedStatement.Let -> LetStatement(
                            pattern = parsedPatternToV1(stmt.pattern),
                            typeAnnotation = stmt.typeAnnotation?.let { parsedTypeToV1(it) },
                            expression = parsedExprToV1(stmt.value)
                        )
                        is ParsedStatement.Expr -> ExpressionStatement(parsedExprToV1(stmt.expr))
                    }
                },
                result = parsedExprToV1(expr.result),
                sourceLocation = loc
            )
            is ParsedSeqLit -> SeqLiteral(
                elements = expr.elements.map { elem ->
                    when (elem) {
                        is ParsedSeqElement.Regular -> SeqRegularElement(parsedExprToV1(elem.expr))
                        is ParsedSeqElement.Spread -> SeqSpreadElement(parsedExprToV1(elem.expr))
                    }
                },
                sourceLocation = loc
            )
            is ParsedPerform -> PerformExpression(
                expression = parsedExprToV1(expr.body),
                handlers = expr.handlers.map { h ->
                    PerformHandler(
                        performer = QualifiedRef(
                            h.performer.localName,
                            h.performer.qualifier?.let { ModulePath(it.segments) }
                        ),
                        parameters = h.params?.let { parsedExprToV1(it) as? RecordLiteral }
                    )
                },
                sourceLocation = loc
            )
            is ParsedEffectCall -> EffectCallExpression(
                operation = QualifiedRef(
                    expr.operation.localName,
                    expr.operation.qualifier?.let { ModulePath(it.segments) }
                ),
                arguments = parsedExprToV1(expr.arguments),
                sourceLocation = loc
            )
            is ParsedParens -> ParenthesizedExpression(
                expression = parsedExprToV1(expr.expr),
                sourceLocation = loc
            )
        }
    }

    // ========================================================================
    // Pattern conversion
    // ========================================================================

    private fun parsedPatternToV1(pattern: ParsedPattern): Pattern {
        return when (pattern) {
            is ParsedVariablePattern -> VariablePattern(pattern.name, locToV1(pattern.loc))
            is ParsedWildcardPattern -> WildcardPattern(sourceLocation = locToV1(pattern.loc))
            is ParsedConstructorPattern -> ConstructorPattern(
                pattern.constructor,
                pattern.payload?.let { parsedPatternToV1(it) as? RecordPattern },
                locToV1(pattern.loc)
            )
            is ParsedRecordPattern -> RecordPattern(
                pattern.entries.map { e ->
                    RecordPatternEntry(e.name, e.pattern?.let { parsedPatternToV1(it) }, locToV1(e.loc))
                },
                pattern.isOpen,
                locToV1(pattern.loc)
            )
            is ParsedLiteralPattern -> LiteralPattern(
                when (val v = pattern.value) {
                    is ParsedLiteralValue.IntValue -> IntLiteralValue(v.value)
                    is ParsedLiteralValue.NatValue -> NatLiteralValue(v.value)
                    is ParsedLiteralValue.FloatValue -> FloatLiteralValue(v.value)
                    is ParsedLiteralValue.StringValue -> StringLiteralValue(v.value)
                    is ParsedLiteralValue.BoolValue -> BooleanLiteralValue(v.value)
                },
                locToV1(pattern.loc)
            )
            is ParsedSeqPattern -> SeqPattern(
                pattern.elements.map { parsedPatternToV1(it) },
                pattern.restPattern,
                locToV1(pattern.loc)
            )
            is ParsedStringInterpPattern -> StringInterpolationPattern(
                pattern.parts.map { part ->
                    when (part) {
                        is ParsedStringPatternPart.Literal -> StringPatternLiteral(part.value)
                        is ParsedStringPatternPart.Capture -> StringPatternCapture(part.variableName)
                    }
                },
                locToV1(pattern.loc)
            )
        }
    }

    // ========================================================================
    // Type annotation conversion
    // ========================================================================

    private fun parsedTypeToV1(type: ParsedTypeAnnotation): TypeAnnotation {
        return when (type) {
            is ParsedNamedType -> IdentifierTypeAnnotation(type.name)
            is ParsedQualifiedType -> QualifiedTypeAnnotation(type.modulePath, type.typeName, type.args.map { parsedTypeToV1(it) })
            is ParsedTypeApp -> TypeApplicationAnnotation(type.constructor, type.args.map { parsedTypeToV1(it) })
            is ParsedRecordType -> RecordTypeAnnotation(
                type.entries.map { e -> RecordTypeEntry(e.name, parsedTypeToV1(e.type)) },
                type.isOpen
            )
            is ParsedVariantType -> VariantTypeAnnotation(
                type.constructors.map { c -> VariantTypeConstructor(c.name, c.dataType?.let { parsedTypeToV1(it) }) },
                type.isOpen
            )
            is ParsedFunctionType -> FunctionTypeAnnotation(
                parsedTypeToV1(type.paramType),
                parsedTypeToV1(type.returnType),
                type.effects?.let { parsedEffectRowToV1(it) },
                type.abilityConstraints.map { parsedFnConstraintToV1(it) }
            )
            is ParsedSeqType -> SeqTypeAnnotation(parsedTypeToV1(type.elementType))
        }
    }

    private fun parsedEffectRowToV1(row: ParsedEffectRow): EffectRowAnnotation {
        return EffectRowAnnotation(
            effects = row.effects,
            effectAnnotations = row.effectAnnotations.map { ea ->
                EffectAnnotation(ea.name, ea.associatedTypes.mapValues { (_, v) -> parsedTypeToV1(v) })
            },
            isPolymorphic = row.isPolymorphic,
            isPerformed = row.isPerformed
        )
    }

    private fun parsedFnConstraintToV1(c: ParsedFnAbilityConstraint): FunctionAbilityConstraint {
        return FunctionAbilityConstraint(
            c.abilityName,
            c.typeArgs.map { parsedTypeToV1(it) },
            c.associatedTypeBindings.mapValues { (_, v) -> parsedTypeToV1(v) }
        )
    }

    private fun parsedTypeDefToV1(def: ParsedTypeDef): TypeDefinition {
        return when (def) {
            is ParsedRecordDef -> RecordDefinition(
                def.fields.map { f -> RecordFieldDef(f.name, parsedTypeToV1(f.type)) },
                def.isOpen
            )
            is ParsedVariantDef -> VariantDefinition(
                def.constructors.map { c -> VariantConstructorDef(c.name, c.dataType?.let { parsedTypeToV1(it) }) },
                def.isOpen
            )
            is ParsedNewtypeDef -> NewtypeDefinition(def.constructor, parsedTypeToV1(def.wrappedType))
        }
    }

    private fun parsedUsePathToV1(path: ParsedUsePath): UsePath {
        return when (path) {
            is ParsedUsePath.Module -> ModulePath(path.path.segments)
            is ParsedUsePath.Selective -> SelectivePath(
                path.modulePath.segments,
                path.items.map { ImportItem(it.name, it.alias) }
            )
        }
    }

    // ========================================================================
    // Loc conversion
    // ========================================================================

    private fun locToV1(loc: Loc): SourceLocation {
        return SourceLocation(loc.startLine, loc.startCol, loc.file, loc.endCol - loc.startCol + 1)
    }

    private fun parsedVisToV1(vis: PVisibility): Visibility {
        return when (vis) {
            PVisibility.PUBLIC -> Visibility.PUBLIC
            PVisibility.PRIVATE -> Visibility.PRIVATE
        }
    }

    // ========================================================================
    // v1 TypeCheckResult → TypedModule
    // ========================================================================

    private fun v1ResultToTypedModule(
        path: String,
        program: Program,
        monomorphicInstances: Map<String, LetBinding>,
        importContext: ModuleImportContext?
    ): TypedModule {
        val file = path

        // Convert declarations
        val decls = mutableListOf<TypedDecl>()
        val abilityDecls = mutableListOf<TypedAbilityDecl>()
        val abilityImpls = mutableListOf<TypedAbilityImpl>()
        val effectDecls = mutableListOf<TypedEffectDecl>()
        val performerDecls = mutableListOf<TypedPerformerDecl>()
        val exports = mutableMapOf<String, ExportEntry>()

        // Build name→contentHash lookup from this module's own bindings.
        // Used to resolve function references in impl operation bodies where
        // resolvedContentHash is null.
        currentModuleBindingHashes = mutableMapOf<String, String>().also { map ->
            for (item in program.items) {
                if (item is LetBinding && item.isSimpleBinding && item.contentHash != null) {
                    map[item.variableName] = item.contentHash!!
                }
            }
        }

        // Build effect name→hash lookup for detecting Effect.operation patterns
        currentModuleEffectHashes = mutableMapOf<String, String>().also { map ->
            for (item in program.items) {
                if (item is EffectDeclaration && item.contentHash != null) {
                    map[item.name] = item.contentHash!!
                }
            }
        }

        // Build performer name→(implHash, effectHash) lookup for PerformExpression→TypedPerform
        currentModulePerformerInfo = mutableMapOf<String, Pair<String, String>>().also { map ->
            for (item in program.items) {
                if (item is ImplDeclaration && item.contentHash != null) {
                    val effectName = item.effect.localName
                    val effectHash = currentModuleEffectHashes[effectName] ?: ""
                    map[item.performerName] = Pair(item.contentHash!!, effectHash)
                }
            }
        }

        for (item in program.items) {
            when (item) {
                is LetBinding -> {
                    val hash = v1HashToTypedHash(item.contentHash)
                    val name = if (item.isSimpleBinding) item.variableName else "<destructured>"
                    val type = item.inferredType ?: MezzeType.VarType(TypeVar("unknown"))
                    decls.add(TypedLetDecl(
                        hash = hash,
                        name = name,
                        typeParams = emptyList(), // TODO: extract from typeScheme
                        value = v1ExprToTyped(item.expression, file),
                        scheme = TypedTypeScheme(emptyList(), emptyList(), type),
                        isPublic = item.visibility == Visibility.PUBLIC,
                        loc = v1LocFromExpr(item.expression, file)
                    ))
                    if (item.visibility == Visibility.PUBLIC && item.isSimpleBinding) {
                        exports[name] = ExportEntry(name, hash, type, ExportKind.FUNCTION)
                    }
                }
                is TypeDeclaration -> {
                    val hash = v1HashToTypedHash(item.contentHash)
                    decls.add(TypedTypeDecl(
                        hash = hash,
                        name = item.name,
                        typeParams = emptyList(),
                        definition = v1TypeDefToTyped(item.definition),
                        isPublic = item.visibility == Visibility.PUBLIC,
                        loc = v1SrcLocToLoc(item.sourceLocation, file)
                    ))
                    if (item.visibility == Visibility.PUBLIC) {
                        exports[item.name] = ExportEntry(item.name, hash, MezzeType.VarType(TypeVar(item.name)), ExportKind.TYPE)
                    }
                }
                is AbilityDeclaration -> {
                    val hash = v1HashToTypedHash(item.contentHash)
                    abilityDecls.add(TypedAbilityDecl(
                        hash = hash,
                        name = item.name,
                        typeParams = emptyList(),
                        receiverTypeParam = item.receiverTypeParam,
                        associatedTypes = item.associatedTypes,
                        dependencies = emptyList(),
                        operations = item.operations.mapIndexed { idx, op ->
                            TypedAbilityOp(op.name, idx, convertTypeAnnotation(op.paramType), convertTypeAnnotation(op.returnType), v1SrcLocToLoc(item.sourceLocation, file))
                        },
                        loc = v1SrcLocToLoc(item.sourceLocation, file)
                    ))
                    exports[item.name] = ExportEntry(item.name, hash, MezzeType.VarType(TypeVar(item.name)), ExportKind.ABILITY)
                }
                is ImplAbilityDeclaration -> {
                    val hash = v1HashToTypedHash(item.contentHash)
                    val abilityHash = v1HashToTypedHash(null) // Would need ability hash lookup
                    abilityImpls.add(TypedAbilityImpl(
                        hash = hash,
                        abilityHash = abilityHash,
                        targetTypes = item.targetTypes.map { convertTypeAnnotation(it) },
                        forallVars = emptyList(),
                        associatedTypeBindings = item.associatedTypeBindings.mapValues { (_, v) -> convertTypeAnnotation(v) },
                        operations = item.operations.associate { op ->
                            op.name to v1ExprToTyped(op.implementation, file)
                        },
                        loc = v1SrcLocToLoc(item.sourceLocation, file)
                    ))
                }
                is EffectDeclaration -> {
                    val hash = v1HashToTypedHash(item.contentHash)
                    effectDecls.add(TypedEffectDecl(
                        hash = hash,
                        name = item.name,
                        operations = item.operations.mapIndexed { idx, op ->
                            TypedEffectOp(op.name, idx, convertTypeAnnotation(op.paramType), convertTypeAnnotation(op.returnType), v1SrcLocToLoc(item.sourceLocation, file))
                        },
                        associatedTypes = item.associatedTypes,
                        loc = v1SrcLocToLoc(item.sourceLocation, file)
                    ))
                    exports[item.name] = ExportEntry(item.name, hash, MezzeType.VarType(TypeVar(item.name)), ExportKind.EFFECT)
                }
                is ImplDeclaration -> {
                    val hash = v1HashToTypedHash(item.contentHash)
                    val effectHash = v1HashToTypedHash(null)
                    performerDecls.add(TypedPerformerDecl(
                        hash = hash,
                        effectHash = effectHash,
                        name = item.performerName,
                        params = item.performerParams.map { p ->
                            TypedPerformerParam(p.name, convertTypeAnnotation(p.type), v1SrcLocToLoc(item.sourceLocation, file))
                        },
                        associatedTypeBindings = item.associatedTypeBindings.mapValues { (_, v) -> convertTypeAnnotation(v) },
                        operations = item.operations.associate { op ->
                            op.name to v1ExprToTyped(op.implementation, file)
                        },
                        loc = v1SrcLocToLoc(item.sourceLocation, file)
                    ))
                    exports[item.performerName] = ExportEntry(item.performerName, hash, MezzeType.VarType(TypeVar(item.performerName)), ExportKind.PERFORMER)
                }
                is TopLevelExpression -> {
                    // Convert top-level expression to a synthetic __expr_N decl
                    val exprType = item.expression.inferredType ?: MezzeType.VarType(TypeVar("unknown"))
                    val exprHash = v1HashToTypedHash(null) // Synthetic hash
                    val exprLoc = v1LocFromExpr(item.expression, file)
                    decls.add(TypedLetDecl(
                        hash = computeModuleHash(listOf("__toplevel_expr_${decls.size}")),
                        name = "__toplevel_expr_${decls.size}",
                        typeParams = emptyList(),
                        value = v1ExprToTyped(item.expression, file),
                        scheme = TypedTypeScheme(emptyList(), emptyList(), exprType),
                        isPublic = false,
                        loc = exprLoc
                    ))
                }
                is UseDeclaration -> { /* skip — already resolved */ }
                is PubUseDeclaration -> { /* skip */ }
            }
        }

        // Compute module hash from all declaration hashes
        val allHashes = decls.map { it.hash.hex } +
            abilityDecls.map { it.hash.hex } +
            abilityImpls.map { it.hash.hex } +
            effectDecls.map { it.hash.hex } +
            performerDecls.map { it.hash.hex }
        val moduleHash = if (allHashes.isNotEmpty()) {
            computeModuleHash(allHashes)
        } else {
            Hash.ZERO
        }

        return TypedModule(
            path = path,
            hash = moduleHash,
            imports = emptyList(), // TODO: convert from importContext
            exports = exports,
            decls = decls,
            abilityDecls = abilityDecls,
            abilityImpls = abilityImpls,
            effectDecls = effectDecls,
            performerDecls = performerDecls,
            dependencies = emptyList() // TODO: compute from imports
        )
    }

    // ========================================================================
    // v1 Expression → TypedExpr
    // ========================================================================

    /** Name→hash lookup for resolving function references in impl bodies */
    private var currentModuleBindingHashes: Map<String, String> = emptyMap()

    /** Stack of lambda parameter names — identifiers matching these are locals, not global functions */
    private val lambdaParamScopes = ArrayDeque<Set<String>>()

    /** Effect name→contentHash lookup for detecting Effect.operation patterns in v1→v2 conversion */
    private var currentModuleEffectHashes: Map<String, String> = emptyMap()

    /** Performer name→(implHash, effectHash) lookup for PerformExpression→TypedPerform */
    private var currentModulePerformerInfo: Map<String, Pair<String, String>> = emptyMap()


    private fun v1ExprToTyped(expr: Expression, file: String): TypedExpr {
        val type = expr.inferredType ?: MezzeType.VarType(TypeVar("unknown"))
        val loc = v1LocFromExpr(expr, file)

        return when (expr) {
            is IntLiteral -> TypedIntLit(expr.value, type, loc)
            is NatLiteral -> TypedNatLit(expr.value, type, loc)
            is FloatLiteral -> TypedFloatLit(expr.value, type, loc)
            is FractionLiteral -> TypedFracLit(expr.numerator, expr.denominator, type, loc)
            is StringLiteral -> {
                val parts = if (expr.parts != null) {
                    expr.parts!!.map { part ->
                        when (part) {
                            is StringPart -> TypedStringPart.Literal(part.value)
                            is ExpressionPart -> TypedStringPart.Interpolation(v1ExprToTyped(part.expression, file))
                        }
                    }
                } else {
                    listOf(TypedStringPart.Literal(expr.value))
                }
                TypedStringLit(parts, type, loc)
            }
            is Identifier -> {
                // Check if this identifier is a lambda parameter — local params shadow globals
                val isLambdaParam = lambdaParamScopes.any { it.contains(expr.localName) }
                val binding = if (isLambdaParam) {
                    BindingRef.Local(0, 0) // Lambda parameter — resolved by Resolver's SlotAllocator
                } else if (expr.resolvedContentHash != null) {
                    BindingRef.ByHash(v1HashToTypedHash(expr.resolvedContentHash))
                } else if (expr.localName.startsWith("__native_")) {
                    BindingRef.Native(expr.localName)
                } else {
                    // Try module-level binding lookup (for impl operation bodies where
                    // resolvedContentHash is not set)
                    val moduleHash = currentModuleBindingHashes[expr.localName]
                    if (moduleHash != null) {
                        BindingRef.ByHash(v1HashToTypedHash(moduleHash))
                    } else {
                        BindingRef.Local(0, 0) // Lambda parameter or truly unresolved
                    }
                }
                TypedVar(expr.localName, binding, type, loc)
            }
            is LambdaExpression -> {
                val params = when (val p = expr.parameters) {
                    is TypedParameters -> p.recordType.entries.map { e ->
                        com.mezze.language.typedast.TypedParam(e.name, convertTypeAnnotation(e.type), loc)
                    }
                    is UntypedParameters -> p.names.map { name ->
                        com.mezze.language.typedast.TypedParam(name, MezzeType.VarType(TypeVar(name)), loc)
                    }
                }
                // Track lambda param names so Identifier resolution prefers local over global
                lambdaParamScopes.addLast(params.map { it.name }.toSet())
                val body = v1ExprToTyped(expr.body, file)
                lambdaParamScopes.removeLast()
                TypedLambda(params, body, emptyList(), type, loc)
            }
            is FunctionCall -> {
                // Detect Effect.operation { args } pattern that the v1 typechecker
                // missed converting to EffectCallExpression. Emit TypedPerformOp
                // so the pipeline produces DynamicEffectCallNode instead of field access.
                val fn = expr.function
                if (fn is MemberAccess) {
                    val receiverName = (fn.receiver as? Identifier)?.localName
                        ?: (fn.receiver as? VariantConstruction)?.constructor
                    val effectHash = receiverName?.let { currentModuleEffectHashes[it] }
                    if (effectHash != null) {
                        return TypedPerformOp(
                            v1HashToTypedHash(effectHash),
                            fn.member,
                            v1ExprToTyped(expr.arguments, file),
                            type, loc
                        )
                    }
                }

                // If the function call has a resolved content hash, use it for a direct call
                // This handles operator desugaring (1 + 2 → NumAdd.add) where the function
                // is resolved to a specific ability impl method by the v1 typechecker
                val function = if (expr.resolvedFunctionContentHash != null) {
                    val hash = v1HashToTypedHash(expr.resolvedFunctionContentHash)
                    TypedVar("__resolved__", BindingRef.ByHash(hash), type, loc)
                } else {
                    v1ExprToTyped(expr.function, file)
                }
                TypedCall(
                    function,
                    v1ExprToTyped(expr.arguments, file),
                    null, // instantiation
                    expr.isTailCall,
                    type,
                    loc
                )
            }
            is IfExpression -> TypedIf(
                v1ExprToTyped(expr.condition, file),
                v1ExprToTyped(expr.thenBranch, file),
                v1ExprToTyped(expr.elseBranch, file),
                type,
                loc
            )
            is MatchExpression -> TypedMatch(
                v1ExprToTyped(expr.expression, file),
                expr.cases.map { c ->
                    val patterns = c.patterns.map { v1PatternToTyped(it, file) }
                    val bindingNames = c.patterns.flatMap { it.extractBindings() }
                    val bindings = bindingNames.map { name ->
                        PatternBinding(name, MezzeType.VarType(TypeVar(name)), loc)
                    }
                    // Push pattern bindings so case body sees them as locals
                    lambdaParamScopes.addLast(bindingNames.toSet())
                    val guard = c.guard?.let { v1ExprToTyped(it, file) }
                    val body = v1ExprToTyped(c.expression, file)
                    lambdaParamScopes.removeLast()
                    TypedCase(patterns, bindings, guard, body, loc)
                },
                type,
                loc
            )
            is DoExpression -> {
                // Collect let binding names so they shadow globals in subsequent expressions
                val letNames = mutableSetOf<String>()
                val stmts = expr.statements.map { stmt ->
                    when (stmt) {
                        is LetStatement -> {
                            val name = (stmt.pattern as? VariablePattern)?.name ?: "<pattern>"
                            val value = v1ExprToTyped(stmt.expression, file)
                            letNames.add(name)
                            // Add to current scope so subsequent stmts see this binding
                            if (lambdaParamScopes.isNotEmpty()) {
                                val current = lambdaParamScopes.removeLast()
                                lambdaParamScopes.addLast(current + name)
                            }
                            TypedStatement.Let(name, Hash.ZERO, MezzeType.VarType(TypeVar("unknown")), value, loc)
                        }
                        is ExpressionStatement -> TypedStatement.Expr(v1ExprToTyped(stmt.expression, file), loc)
                    }
                }
                val result = v1ExprToTyped(expr.result, file)
                TypedBlock(stmts, result, type, loc)
            }
            is RecordLiteral -> TypedRecord(
                expr.entries.map { entry ->
                    when (entry) {
                        is RecordFieldEntry -> TypedRecordEntry.Field(
                            entry.name,
                            v1ExprToTyped(entry.value ?: Identifier(entry.name), file),
                            loc
                        )
                        is RecordSpreadEntry -> TypedRecordEntry.Spread(v1ExprToTyped(entry.expression, file), loc)
                    }
                },
                type,
                loc
            )
            is MemberAccess -> TypedFieldAccess(
                v1ExprToTyped(expr.receiver, file),
                expr.member,
                0, // fieldIndex — would need type info
                type,
                loc
            )
            is VariantConstruction -> TypedVariantConstruct(
                expr.constructor,
                expr.value?.let { v1ExprToTyped(it, file) },
                type,
                loc
            )
            is SeqLiteral -> TypedSeq(
                expr.elements.map { elem ->
                    when (elem) {
                        is SeqRegularElement -> TypedSeqElement.Regular(v1ExprToTyped(elem.expression, file))
                        is SeqSpreadElement -> TypedSeqElement.Spread(v1ExprToTyped(elem.expression, file))
                    }
                },
                type,
                loc
            )
            is PipeExpression -> TypedPipe(v1ExprToTyped(expr.left, file), v1ExprToTyped(expr.right, file), type, loc)
            is PipePlaceholder -> TypedVar("@", BindingRef.Local(0, 0), type, loc)
            is ParenthesizedExpression -> v1ExprToTyped(expr.expression, file)
            is PerformExpression -> {
                val handlers = expr.handlers.map { h ->
                    val performerName = h.performer.localName
                    val info = currentModulePerformerInfo[performerName]
                    TypedHandlerRef(
                        v1HashToTypedHash(info?.first),
                        v1HashToTypedHash(info?.second),
                        performerName,
                        h.parameters?.let { v1ExprToTyped(it, file) },
                        loc
                    )
                }
                TypedPerform(handlers, v1ExprToTyped(expr.expression, file), type, loc)
            }
            is EffectCallExpression -> TypedPerformOp(
                v1HashToTypedHash(expr.operation.resolvedContentHash),
                expr.operation.localName,
                v1ExprToTyped(expr.arguments, file), type, loc
            )
            is StateMachineExpression -> TypedVar("__state_machine", BindingRef.Local(0, 0), type, loc)
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun v1HashToTypedHash(contentHash: String?): Hash {
        return Hash.fromV1(contentHash)
    }

    private fun v1SrcLocToLoc(sl: SourceLocation?, file: String): Loc {
        if (sl == null) return Loc(file, 1, 1, 1, 1)
        val endCol = if (sl.length != null) sl.column + sl.length!! - 1 else sl.column
        return Loc(sl.file ?: file, sl.line, sl.column, sl.line, endCol)
    }

    private fun v1LocFromExpr(expr: Expression, file: String): Loc {
        return v1SrcLocToLoc(expr.sourceLocation, file)
    }

    private fun v1TypeDefToTyped(def: TypeDefinition): TypedTypeDef {
        return when (def) {
            is RecordDefinition -> TypedRecordDef(
                def.fields.associate { f -> f.name to convertTypeAnnotation(f.type) },
                def.isOpen
            )
            is VariantDefinition -> TypedVariantDef(
                def.constructors.associate { c -> c.name to c.dataType?.let { convertTypeAnnotation(it) } },
                def.isOpen
            )
            is NewtypeDefinition -> TypedNewtypeDef(
                def.constructor,
                convertTypeAnnotation(def.wrappedType),
                emptyList()
            )
        }
    }

    private fun v1PatternToTyped(pattern: Pattern, file: String): TypedPattern {
        val unknownType = MezzeType.VarType(TypeVar("unknown"))
        val loc = Loc(file, 1, 1, 1, 1)
        return when (pattern) {
            is WildcardPattern -> TypedWildcardPattern(unknownType, loc)
            is VariablePattern -> TypedVarPattern(pattern.name, unknownType, loc)
            is ConstructorPattern -> {
                val payload = pattern.recordPattern?.let { v1PatternToTyped(it, file) }
                TypedVariantPattern(pattern.constructor, payload, unknownType, loc)
            }
            is RecordPattern -> TypedRecordPattern(
                pattern.entries.map { e ->
                    TypedFieldPattern(e.name, e.pattern?.let { v1PatternToTyped(it, file) }, loc)
                },
                pattern.isOpen, unknownType, loc
            )
            is LiteralPattern -> {
                val typedValue = when (val v = pattern.value) {
                    is IntLiteralValue -> TypedIntLit(v.value, MezzeType.IntType, loc)
                    is NatLiteralValue -> TypedIntLit(v.value, MezzeType.NatType, loc)
                    is FloatLiteralValue -> TypedFloatLit(v.value, MezzeType.FloatType, loc)
                    is StringLiteralValue -> TypedStringLit(
                        listOf(TypedStringPart.Literal(v.value)), MezzeType.StringType, loc
                    )
                    is BooleanLiteralValue -> TypedBoolLit(v.value, MezzeType.BoolType, loc)
                }
                TypedLiteralPattern(typedValue, unknownType, loc)
            }
            is StringInterpolationPattern -> {
                // String interpolation patterns — convert as wildcard for now
                TypedWildcardPattern(unknownType, loc)
            }
            is SeqPattern -> TypedSeqPattern(
                pattern.elements.map { v1PatternToTyped(it, file) },
                pattern.restPattern,
                unknownType, loc
            )
        }
    }

    private fun computeModuleHash(declHashes: List<String>): Hash {
        val combined = declHashes.sorted().joinToString("")
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(combined.toByteArray())
        val hex = bytes.joinToString("") { "%02x".format(it) }
        return Hash(hex)
    }
}
