package com.mezze.language.typechecker.emit

import com.mezze.language.ast.*
import com.mezze.language.parsedast.Loc
import com.mezze.language.typedast.*
import com.mezze.language.typedast.TypeScheme as V2TypeScheme
import com.mezze.language.typechecker.module.ModuleImportContext
import java.security.MessageDigest

/**
 * Emits v2 TypedAST directly from the typechecker's fully processed v1 Program.
 *
 * Called AFTER all typechecker phases complete (types inferred, abilities resolved,
 * content hashes computed, monomorphization done). Has access to all resolved data
 * on v1 AST nodes — no information loss.
 *
 * Key data preserved that the bridge lost:
 * - FunctionCall.resolvedFunctionContentHash → BindingRef.ByHash
 * - FunctionCall.resolvedAbilities → AbilityImplRef by hash
 * - FunctionCall.isTailCall → TypedCall.isTailCall
 * - Identifier.resolvedContentHash → BindingRef.ByHash
 * - LetBinding.contentHash → TypedLetDecl.hash
 * - All declaration contentHash fields → Hash
 */
object TypedAstEmitter {

    /**
     * All known ability impls by abilityName → list of (contentHash, targetTypes).
     */
    private var abilityImplIndex: Map<String, List<AbilityImplEntry>> = emptyMap()

    /**
     * Local binding name → contentHash. For resolving local function calls.
     */
    private var localBindingHashes: Map<String, String> = emptyMap()

    /**
     * Names of local bindings that are functions (lambdas).
     * These need ByHash binding; non-function bindings use Local.
     */
    private var localFunctionNames: Set<String> = emptySet()

    data class AbilityImplEntry(
        val contentHash: String,
        val targetTypes: List<String>
    )

    fun emit(
        program: Program,
        monomorphicInstances: Map<String, LetBinding>,
        importContext: ModuleImportContext?,
        modulePath: String,
        abilityImpls: Map<String, com.mezze.language.typechecker.context.TypingContext.AbilityImplInfo>? = null
    ): com.mezze.language.typedast.TypedModule {
        // Build ability impl index for looking up real impl hashes
        val index = mutableMapOf<String, MutableList<AbilityImplEntry>>()
        abilityImpls?.forEach { (hash, info) ->
            val name = info.abilityName ?: return@forEach
            index.getOrPut(name) { mutableListOf() }
                .add(AbilityImplEntry(hash, info.targetTypes))
        }
        abilityImplIndex = index

        // Build local binding hash index and track which are functions
        val hashes = mutableMapOf<String, String>()
        val funcNames = mutableSetOf<String>()
        for (item in program.items) {
            if (item is LetBinding && item.isSimpleBinding && item.contentHash != null) {
                hashes[item.variableName] = item.contentHash!!
                if (item.expression is LambdaExpression) {
                    funcNames.add(item.variableName)
                }
            }
        }
        // Also add imported bindings — these have content hashes from module loading
        importContext?.importedBindings?.forEach { (_, binding) ->
            hashes[binding.name] = binding.contentHash
            // All imported bindings are functions (or at least callable)
            funcNames.add(binding.name)
        }
        localBindingHashes = hashes
        localFunctionNames = funcNames

        val file = modulePath

        val decls = mutableListOf<TypedDecl>()
        val abilityDecls = mutableListOf<TypedAbilityDecl>()
        val abilityImpls = mutableListOf<TypedAbilityImpl>()
        val effectDecls = mutableListOf<TypedEffectDecl>()
        val performerDecls = mutableListOf<TypedPerformerDecl>()
        val exports = mutableMapOf<String, ExportEntry>()

        for (item in program.items) {
            when (item) {
                is LetBinding -> emitLetBinding(item, file, decls, exports)
                is TypeDeclaration -> emitTypeDecl(item, file, decls, exports)
                is AbilityDeclaration -> emitAbilityDecl(item, file, abilityDecls, exports)
                is ImplAbilityDeclaration -> emitAbilityImpl(item, file, abilityImpls)
                is EffectDeclaration -> emitEffectDecl(item, file, effectDecls, exports)
                is ImplDeclaration -> emitPerformerDecl(item, file, performerDecls, exports)
                is TopLevelExpression -> emitTopLevelExpr(item, file, decls)
                is UseDeclaration -> { /* resolved at import phase */ }
                is PubUseDeclaration -> { /* resolved at import phase */ }
            }
        }

        // Also emit monomorphic instances
        for ((name, binding) in monomorphicInstances) {
            emitLetBinding(binding, file, decls, exports)
        }

        // Build imports from import context
        val imports = buildImports(importContext, file)

        // Compute module hash
        val allHashes = decls.map { it.hash.hex } +
            abilityDecls.map { it.hash.hex } +
            abilityImpls.map { it.hash.hex } +
            effectDecls.map { it.hash.hex } +
            performerDecls.map { it.hash.hex }
        val moduleHash = computeHash(allHashes.sorted().joinToString(""))

        return com.mezze.language.typedast.TypedModule(
            path = modulePath,
            hash = moduleHash,
            imports = imports,
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
    // Let Binding
    // ========================================================================

    private fun emitLetBinding(
        binding: LetBinding,
        file: String,
        decls: MutableList<TypedDecl>,
        exports: MutableMap<String, ExportEntry>
    ) {
        val hash = hashFromV1(binding.contentHash)
        val name = if (binding.isSimpleBinding) binding.variableName else "__destructured__"
        val type = binding.inferredType ?: MezzeType.VarType(TypeVar("unknown"))
        val loc = locFromV1(binding.expression.sourceLocation, file)

        val value = emitExpr(binding.expression, file)

        val scheme = V2TypeScheme(emptyList(), emptyList(), type)

        decls.add(TypedLetDecl(
            hash = hash,
            name = name,
            typeParams = emptyList(),
            value = value,
            scheme = scheme,
            isPublic = binding.visibility == Visibility.PUBLIC,
            loc = loc
        ))

        if (binding.visibility == Visibility.PUBLIC && binding.isSimpleBinding) {
            exports[name] = ExportEntry(name, hash, type, ExportKind.FUNCTION)
        }
    }

    // ========================================================================
    // Expression Emission — preserves ALL resolved data
    // ========================================================================

    private fun emitExpr(expr: Expression, file: String): TypedExpr {
        val type = expr.inferredType ?: MezzeType.VarType(TypeVar("unknown"))
        val loc = locFromV1(expr.sourceLocation, file)

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
                            is ExpressionPart -> TypedStringPart.Interpolation(emitExpr(part.expression, file))
                        }
                    }
                } else {
                    listOf(TypedStringPart.Literal(expr.value))
                }
                TypedStringLit(parts, type, loc)
            }

            is Identifier -> {
                // Resolve binding:
                // 1. resolvedContentHash → imported/cross-module ref → ByHash
                // 2. local function binding → ByHash (needs global lookup for MonomorphicCallNode)
                // 3. local value binding → Local (stays in frame)
                val hash = expr.resolvedContentHash
                val localHash = localBindingHashes[expr.localName]
                val isLocalFunction = localFunctionNames.contains(expr.localName)

                val binding = if (hash != null) {
                    BindingRef.ByHash(hashFromV1(hash))
                } else if (localHash != null && isLocalFunction) {
                    BindingRef.ByHash(hashFromV1(localHash))
                } else {
                    BindingRef.Local(0, 0)
                }
                TypedVar(expr.localName, binding, type, loc)
            }

            is FunctionCall -> {
                // Resolve the function to call:
                // 1. resolvedFunctionContentHash — direct hash from monomorphization
                // 2. resolvedAbilities — ability impl hash (for operator calls like 1 + 2)
                // 3. fallback — emit the function expression as-is
                val function = when {
                    expr.resolvedFunctionContentHash != null -> {
                        // Direct resolved call (monomorphized function)
                        TypedVar("__resolved__", BindingRef.ByHash(hashFromV1(expr.resolvedFunctionContentHash)), type, loc)
                    }
                    expr.resolvedAbilities.isNotEmpty() && expr.function is MemberAccess -> {
                        // Ability method call (e.g., 1.add {r = 2}) — resolve to concrete function
                        // Only for MemberAccess calls — regular calls with resolvedAbilities
                        // just carry ability constraints for propagation
                        val firstAbility = expr.resolvedAbilities.values.firstOrNull()
                        val opName = when (val fn = expr.function) {
                            is MemberAccess -> fn.member
                            is Identifier -> fn.localName
                            else -> "unknown"
                        }

                        if (firstAbility?.implementationHash != null) {
                            // User-defined impl — derive operation hash from impl hash + op name
                            val opHash = com.mezze.language.ast.hashing.MezzeHash.operationHash(
                                firstAbility.implementationHash!!, opName
                            )
                            TypedVar("__ability_op__", BindingRef.ByHash(hashFromV1(opHash)), type, loc)
                        } else if (firstAbility?.isBuiltin == true) {
                            // "Builtin" ability — but it's actually in stdlib with a real impl.
                            // Look up the actual impl hash from the ability impl index.
                            val implHash = findAbilityImplHash(
                                firstAbility.abilityName, firstAbility.typeArgs
                            )
                            if (implHash != null) {
                                // Found the real impl — derive operation hash and restructure args
                                val opHash = com.mezze.language.ast.hashing.MezzeHash.operationHash(
                                    implHash, opName
                                )
                                // The operation function expects {it, ...} — add receiver as 'it'
                                return emitAbilityImplCall(expr, hashFromV1(opHash), file, type, loc)
                            } else {
                                // Fallback: try native function resolution
                                val nativeName = resolveBuiltinAbilityToNative(
                                    firstAbility.abilityName, opName, firstAbility.typeArgs
                                )
                                if (nativeName != null) {
                                    return emitBuiltinAbilityCall(expr, nativeName, file, type, loc)
                                } else {
                                    emitExpr(expr.function, file)
                                }
                            }
                        } else {
                            emitExpr(expr.function, file)
                        }
                    }
                    else -> emitExpr(expr.function, file)
                }

                TypedCall(
                    function = function,
                    arg = emitExpr(expr.arguments, file),
                    instantiation = null,
                    isTailCall = expr.isTailCall,
                    type = type,
                    loc = loc
                )
            }

            is LambdaExpression -> {
                val params = when (val p = expr.parameters) {
                    is TypedParameters -> p.recordType.entries.map { e ->
                        TypedParam(e.name, convertTypeAnnotation(e.type), loc)
                    }
                    is UntypedParameters -> p.names.map { name ->
                        TypedParam(name, MezzeType.VarType(TypeVar(name)), loc)
                    }
                }
                TypedLambda(params, emitExpr(expr.body, file), emptyList(), type, loc)
            }

            is IfExpression -> TypedIf(
                emitExpr(expr.condition, file),
                emitExpr(expr.thenBranch, file),
                emitExpr(expr.elseBranch, file),
                type, loc
            )

            is MatchExpression -> {
                // Check for boolean match (True/False patterns) → convert to if-else
                val isBoolMatch = expr.cases.size == 2 &&
                    expr.cases.any { c -> c.patterns.any { it is ConstructorPattern && it.constructor == "True" } } &&
                    expr.cases.any { c -> c.patterns.any { it is ConstructorPattern && it.constructor == "False" } }

                if (isBoolMatch) {
                    val trueCase = expr.cases.find { c -> c.patterns.any { it is ConstructorPattern && it.constructor == "True" } }
                    val falseCase = expr.cases.find { c -> c.patterns.any { it is ConstructorPattern && it.constructor == "False" } }
                    TypedIf(
                        emitExpr(expr.expression, file),
                        emitExpr(trueCase!!.expression, file),
                        emitExpr(falseCase!!.expression, file),
                        type, loc
                    )
                } else {
                    TypedMatch(
                        emitExpr(expr.expression, file),
                        expr.cases.map { c ->
                            TypedCase(
                                c.patterns.map { emitPattern(it, file) },
                                c.patterns.flatMap { it.extractBindings() }.map { name ->
                                    PatternBinding(name, MezzeType.VarType(TypeVar(name)), loc)
                                },
                                c.guard?.let { emitExpr(it, file) },
                                emitExpr(c.expression, file),
                                loc
                            )
                        },
                        type, loc
                    )
                }
            }

            is DoExpression -> TypedBlock(
                expr.statements.map { stmt ->
                    when (stmt) {
                        is LetStatement -> {
                            val stmtName = (stmt.pattern as? VariablePattern)?.name ?: "__pattern__"
                            TypedStatement.Let(
                                stmtName,
                                computeHash("do_let_$stmtName"),
                                stmt.expression.inferredType ?: MezzeType.VarType(TypeVar("unknown")),
                                emitExpr(stmt.expression, file),
                                loc
                            )
                        }
                        is ExpressionStatement -> TypedStatement.Expr(emitExpr(stmt.expression, file), loc)
                    }
                },
                emitExpr(expr.result, file),
                type, loc
            )

            is RecordLiteral -> TypedRecord(
                expr.entries.map { entry ->
                    when (entry) {
                        is RecordFieldEntry -> TypedRecordEntry.Field(
                            entry.name,
                            emitExpr(entry.value ?: Identifier(entry.name), file),
                            loc
                        )
                        is RecordSpreadEntry -> TypedRecordEntry.Spread(emitExpr(entry.expression, file), loc)
                    }
                },
                type, loc
            )

            is MemberAccess -> {
                if (expr.resolvedContentHash != null) {
                    TypedVar(expr.member, BindingRef.ByHash(hashFromV1(expr.resolvedContentHash)), type, loc)
                } else {
                    // Check receiver type: record → field access, else → method lookup
                    val receiverType = expr.receiver.inferredType
                    if (receiverType is MezzeType.RecordType && receiverType.fields.containsKey(expr.member)) {
                        TypedFieldAccess(emitExpr(expr.receiver, file), expr.member, 0, type, loc)
                    } else {
                        // Method call on non-record (ability dispatch) — emit receiver + member name
                        // The runtime will resolve via InvokeNode's ability dispatch
                        TypedFieldAccess(emitExpr(expr.receiver, file), expr.member, 0, type, loc)
                    }
                }
            }

            is VariantConstruction -> {
                // True/False are special — emit as boolean literals
                if (expr.constructor == "True" && expr.value == null) {
                    TypedBoolLit(true, MezzeType.BoolType, loc)
                } else if (expr.constructor == "False" && expr.value == null) {
                    TypedBoolLit(false, MezzeType.BoolType, loc)
                } else {
                    TypedVariantConstruct(expr.constructor, expr.value?.let { emitExpr(it, file) }, type, loc)
                }
            }

            is SeqLiteral -> TypedSeq(
                expr.elements.map { elem ->
                    when (elem) {
                        is SeqRegularElement -> TypedSeqElement.Regular(emitExpr(elem.expression, file))
                        is SeqSpreadElement -> TypedSeqElement.Spread(emitExpr(elem.expression, file))
                    }
                },
                type, loc
            )

            is PipeExpression -> TypedPipe(emitExpr(expr.left, file), emitExpr(expr.right, file), type, loc)
            is PipePlaceholder -> TypedVar("@", BindingRef.Local(0, 0), type, loc)
            is ParenthesizedExpression -> emitExpr(expr.expression, file)

            is PerformExpression -> {
                val handlers = expr.handlers.map { h ->
                    val resolved = expr.resolvedHandlers.values.find { r ->
                        r.performerName == h.performer.localName
                    } ?: expr.resolvedHandlers[h.performer.localName]
                      ?: expr.resolvedHandlers.values.firstOrNull()
                    val performerHash = hashFromV1(resolved?.implDeclarationHash)
                    val effectHash = hashFromV1(resolved?.effectDeclarationHash)
                    TypedHandlerRef(performerHash, effectHash, h.performer.localName, h.parameters?.let { emitExpr(it, file) }, loc)
                }
                TypedPerform(handlers, emitExpr(expr.expression, file), type, loc)
            }

            is EffectCallExpression -> {
                // Effect operation — runtime dispatch via handler stack
                val effectHash = hashFromV1(expr.operation.resolvedContentHash)
                TypedPerformOp(effectHash, expr.operation.localName, emitExpr(expr.arguments, file), type, loc)
            }

            is StateMachineExpression -> TypedVar("__state_machine__", BindingRef.Local(0, 0), type, loc)
        }
    }

    // ========================================================================
    // Pattern Emission
    // ========================================================================

    private fun emitPattern(pattern: Pattern, file: String): TypedPattern {
        val unknownType = MezzeType.VarType(TypeVar("unknown"))
        val loc = locFromV1(when (pattern) {
            is VariablePattern -> pattern.sourceLocation
            is WildcardPattern -> pattern.sourceLocation
            is ConstructorPattern -> pattern.sourceLocation
            is RecordPattern -> pattern.sourceLocation
            is LiteralPattern -> pattern.sourceLocation
            is SeqPattern -> pattern.sourceLocation
            is StringInterpolationPattern -> pattern.sourceLocation
        }, file)

        return when (pattern) {
            is VariablePattern -> TypedVarPattern(pattern.name, unknownType, loc)
            is WildcardPattern -> TypedWildcardPattern(unknownType, loc)
            is ConstructorPattern -> {
                // True/False are booleans
                if (pattern.constructor == "True" && pattern.recordPattern == null) {
                    TypedLiteralPattern(TypedBoolLit(true, MezzeType.BoolType, loc), MezzeType.BoolType, loc)
                } else if (pattern.constructor == "False" && pattern.recordPattern == null) {
                    TypedLiteralPattern(TypedBoolLit(false, MezzeType.BoolType, loc), MezzeType.BoolType, loc)
                } else {
                    // Variant pattern
                    val payload = pattern.recordPattern?.let { emitPattern(it, file) }
                    TypedVariantPattern(pattern.constructor, payload, unknownType, loc)
                }
            }
            is RecordPattern -> TypedRecordPattern(
                pattern.entries.map { e ->
                    TypedFieldPattern(e.name, e.pattern?.let { emitPattern(it, file) }, loc)
                },
                pattern.isOpen,
                unknownType, loc
            )
            is LiteralPattern -> {
                val value = when (val v = pattern.value) {
                    is IntLiteralValue -> TypedIntLit(v.value, MezzeType.IntType, loc)
                    is NatLiteralValue -> TypedNatLit(v.value, MezzeType.NatType, loc)
                    is FloatLiteralValue -> TypedFloatLit(v.value, MezzeType.FloatType, loc)
                    is StringLiteralValue -> TypedStringLit(listOf(TypedStringPart.Literal(v.value)), MezzeType.StringType, loc)
                    is BooleanLiteralValue -> TypedBoolLit(v.value, MezzeType.BoolType, loc)
                }
                TypedLiteralPattern(value, value.type, loc)
            }
            is SeqPattern -> TypedSeqPattern(
                pattern.elements.map { emitPattern(it, file) },
                pattern.restPattern,
                unknownType, loc
            )
            is StringInterpolationPattern -> TypedWildcardPattern(unknownType, loc) // TODO
        }
    }

    // ========================================================================
    // Other Declarations
    // ========================================================================

    private fun emitTypeDecl(item: TypeDeclaration, file: String, decls: MutableList<TypedDecl>, exports: MutableMap<String, ExportEntry>) {
        val hash = hashFromV1(item.contentHash)
        val loc = locFromV1(item.sourceLocation, file)
        val def = when (val d = item.definition) {
            is RecordDefinition -> TypedRecordDef(d.fields.associate { it.name to convertTypeAnnotation(it.type) }, d.isOpen)
            is VariantDefinition -> TypedVariantDef(d.constructors.associate { it.name to it.dataType?.let { convertTypeAnnotation(it) } }, d.isOpen)
            is NewtypeDefinition -> TypedNewtypeDef(d.constructor, convertTypeAnnotation(d.wrappedType), emptyList())
        }
        decls.add(TypedTypeDecl(hash, item.name, emptyList(), def, item.visibility == Visibility.PUBLIC, loc))
        if (item.visibility == Visibility.PUBLIC) {
            exports[item.name] = ExportEntry(item.name, hash, MezzeType.VarType(TypeVar(item.name)), ExportKind.TYPE)
        }
    }

    private fun emitAbilityDecl(item: AbilityDeclaration, file: String, decls: MutableList<TypedAbilityDecl>, exports: MutableMap<String, ExportEntry>) {
        val hash = hashFromV1(item.contentHash)
        val loc = locFromV1(item.sourceLocation, file)
        decls.add(TypedAbilityDecl(hash, item.name, emptyList(), item.receiverTypeParam, item.associatedTypes, emptyList(),
            item.operations.mapIndexed { idx, op -> TypedAbilityOp(op.name, idx, convertTypeAnnotation(op.paramType), convertTypeAnnotation(op.returnType), loc) }, loc))
        exports[item.name] = ExportEntry(item.name, hash, MezzeType.VarType(TypeVar(item.name)), ExportKind.ABILITY)
    }

    private fun emitAbilityImpl(item: ImplAbilityDeclaration, file: String, impls: MutableList<TypedAbilityImpl>) {
        val hash = hashFromV1(item.contentHash)
        val abilityHash = hashFromV1(item.abilityRef?.resolvedContentHash)
        val loc = locFromV1(item.sourceLocation, file)
        impls.add(TypedAbilityImpl(hash, abilityHash, item.targetTypes.map { convertTypeAnnotation(it) }, emptyList(),
            item.associatedTypeBindings.mapValues { (_, v) -> convertTypeAnnotation(v) },
            item.operations.associate { it.name to emitExpr(it.implementation, file) }, loc))
    }

    private fun emitEffectDecl(item: EffectDeclaration, file: String, decls: MutableList<TypedEffectDecl>, exports: MutableMap<String, ExportEntry>) {
        val hash = hashFromV1(item.contentHash)
        val loc = locFromV1(item.sourceLocation, file)
        decls.add(TypedEffectDecl(hash, item.name, item.operations.mapIndexed { idx, op ->
            TypedEffectOp(op.name, idx, convertTypeAnnotation(op.paramType), convertTypeAnnotation(op.returnType), loc)
        }, item.associatedTypes, loc))
        exports[item.name] = ExportEntry(item.name, hash, MezzeType.VarType(TypeVar(item.name)), ExportKind.EFFECT)
    }

    private fun emitPerformerDecl(item: ImplDeclaration, file: String, decls: MutableList<TypedPerformerDecl>, exports: MutableMap<String, ExportEntry>) {
        val hash = hashFromV1(item.contentHash)
        val effectHash = hashFromV1(item.effect.resolvedContentHash)
        val loc = locFromV1(item.sourceLocation, file)
        decls.add(TypedPerformerDecl(hash, effectHash, item.performerName,
            item.performerParams.map { TypedPerformerParam(it.name, convertTypeAnnotation(it.type), loc) },
            item.associatedTypeBindings.mapValues { (_, v) -> convertTypeAnnotation(v) },
            item.operations.associate { it.name to emitExpr(it.implementation, file) }, loc))
        exports[item.performerName] = ExportEntry(item.performerName, hash, MezzeType.VarType(TypeVar(item.performerName)), ExportKind.PERFORMER)
    }

    private fun emitTopLevelExpr(item: TopLevelExpression, file: String, decls: MutableList<TypedDecl>) {
        val type = item.expression.inferredType ?: MezzeType.VarType(TypeVar("unknown"))
        val loc = locFromV1(item.expression.sourceLocation, file)
        val hash = computeHash("__toplevel_expr_${decls.size}")
        decls.add(TypedLetDecl(hash, "__toplevel_expr_${decls.size}", emptyList(), emitExpr(item.expression, file),
            V2TypeScheme(emptyList(), emptyList(), type), false, loc))
    }

    // ========================================================================
    // Imports
    // ========================================================================

    private fun buildImports(importContext: ModuleImportContext?, file: String): List<TypedImport> {
        if (importContext == null) return emptyList()
        return importContext.importedBindings.map { (_, binding) ->
            TypedImport(
                localName = binding.name,
                originalName = binding.originalName,
                moduleHash = computeHash(binding.modulePath),
                declHash = hashFromV1(binding.contentHash),
                type = binding.type,
                kind = ExportKind.FUNCTION
            )
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun hashFromV1(hash: String?): Hash = Hash.fromV1(hash)

    private fun locFromV1(sl: SourceLocation?, file: String): Loc {
        if (sl == null) return Loc(file, 1, 1, 1, 1)
        val endCol = if (sl.length != null) sl.column + sl.length!! - 1 else sl.column
        return Loc(sl.file ?: file, sl.line, sl.column, sl.line, endCol)
    }

    private fun computeHash(input: String): Hash {
        val builder = com.mezze.language.ast.hashing.MezzeHashBuilder()
        builder.append(input)
        return hashFromV1(builder.build().toFullHexString())
    }

    // ========================================================================
    // Builtin Ability Resolution
    // ========================================================================

    /**
     * Find the content hash of an ability impl for the given ability + target types.
     * Searches the abilityImplIndex built from the typechecker context.
     */
    private fun findAbilityImplHash(abilityName: String, typeArgs: List<MezzeType>): String? {
        val impls = abilityImplIndex[abilityName] ?: return null
        val targetTypeName = typeArgs.firstOrNull()?.let { getTypeNameForLookup(it) } ?: return null

        // Find impl whose targetTypes match
        for (impl in impls) {
            if (impl.targetTypes.any { it == targetTypeName }) {
                return impl.contentHash
            }
        }
        return null
    }

    private fun getTypeNameForLookup(type: MezzeType): String {
        return when (type) {
            is MezzeType.IntType -> "Int"
            is MezzeType.FloatType -> "Float"
            is MezzeType.StringType -> "Str"
            is MezzeType.BoolType -> "Bool"
            is MezzeType.NatType -> "Nat"
            is MezzeType.FracType -> "Frac"
            is MezzeType.CharType -> "Char"
            else -> type.toString()
        }
    }

    /**
     * Map builtin ability + operation + type to a native function name.
     *
     * For `1 + 2`: abilityName="NumAdd", opName="add", typeArgs=[IntType]
     * → "__native_int_add"
     */
    private fun resolveBuiltinAbilityToNative(
        abilityName: String,
        opName: String,
        typeArgs: List<MezzeType>
    ): String? {
        val targetType = typeArgs.firstOrNull() ?: return null
        val typePrefix = when (targetType) {
            is MezzeType.IntType -> "int"
            is MezzeType.FloatType -> "float"
            is MezzeType.NatType -> "nat"
            is MezzeType.FracType -> "frac"
            is MezzeType.StringType -> "string"
            is MezzeType.BoolType -> "bool"
            else -> return null
        }

        return when (abilityName) {
            "NumAdd" -> when (opName) {
                "add" -> "__native_${typePrefix}_add"
                else -> null
            }
            "NumSub" -> when (opName) {
                "sub" -> "__native_${typePrefix}_subtract"
                "neg" -> "__native_${typePrefix}_neg"
                else -> null
            }
            "NumMul" -> when (opName) {
                "mul" -> "__native_${typePrefix}_multiply"
                else -> null
            }
            "NumDiv" -> when (opName) {
                "div" -> "__native_${typePrefix}_divide"
                else -> null
            }
            "NumMod" -> when (opName) {
                "mod" -> "__native_${typePrefix}_modulo"
                else -> null
            }
            "Eq" -> when (opName) {
                "eq" -> "__native_${typePrefix}_eq"
                "neq" -> "__native_${typePrefix}_neq"
                else -> null
            }
            "Ord" -> when (opName) {
                "lt" -> "__native_${typePrefix}_lt"
                "le" -> "__native_${typePrefix}_le"
                "gt" -> "__native_${typePrefix}_gt"
                "ge" -> "__native_${typePrefix}_ge"
                else -> null
            }
            "LogicalAnd" -> when (opName) {
                "and" -> "__native_${typePrefix}_and"
                else -> null
            }
            "LogicalOr" -> when (opName) {
                "or" -> "__native_${typePrefix}_or"
                else -> null
            }
            "LogicalNot" -> when (opName) {
                "not" -> "__native_${typePrefix}_not"
                else -> null
            }
            "Inspect" -> when (opName) {
                "inspect" -> "__native_${typePrefix}_inspect"
                "to_dbg_str" -> "__native_${typePrefix}_to_dbg_str"
                else -> null
            }
            else -> null
        }
    }

    /**
     * Emit an ability impl call — the operation function takes {it, ...} as parameters.
     * Extracts receiver from MemberAccess and adds it as 'it' to the argument record.
     */
    private fun emitAbilityImplCall(
        expr: FunctionCall,
        opHash: Hash,
        file: String,
        type: MezzeType,
        loc: Loc
    ): TypedExpr {
        val fn = expr.function
        val receiver = if (fn is MemberAccess) emitExpr(fn.receiver, file) else null
        val existingArgs = expr.arguments

        val opVar = TypedVar("__ability_op__", BindingRef.ByHash(opHash), type, loc)

        if (receiver != null && existingArgs is RecordLiteral) {
            // Add receiver as 'it' field
            val fields = mutableListOf<TypedRecordEntry>()
            fields.add(TypedRecordEntry.Field("it", receiver, loc))
            for (entry in existingArgs.entries) {
                when (entry) {
                    is RecordFieldEntry -> fields.add(TypedRecordEntry.Field(
                        entry.name,
                        emitExpr(entry.value ?: Identifier(entry.name), file),
                        loc
                    ))
                    is RecordSpreadEntry -> fields.add(TypedRecordEntry.Spread(emitExpr(entry.expression, file), loc))
                }
            }
            val argType = MezzeType.RecordType(
                fields.filterIsInstance<TypedRecordEntry.Field>().associate { it.name to MezzeType.IntType }
            )
            return TypedCall(opVar, TypedRecord(fields, argType, loc), null, false, type, loc)
        } else {
            return TypedCall(opVar, emitExpr(expr.arguments, file), null, false, type, loc)
        }
    }

    /**
     * Emit a builtin ability call as a direct native function call.
     *
     * `1.add {r = 2}` becomes `__native_int_add {l = 1, r = 2}`
     *
     * The desugared call has the receiver as `it` in the arguments record.
     * We need to restructure: the argument record already has {l = 1, r = 2}
     * (or {it = receiver, r = arg} depending on desugaring), so we just
     * emit a call to the native function with the same arguments.
     */
    private fun emitBuiltinAbilityCall(
        expr: FunctionCall,
        nativeName: String,
        file: String,
        type: MezzeType,
        loc: Loc
    ): TypedExpr {
        // Desugared operator: `1.add {r = 2}`
        // MemberAccess.receiver = 1 (the left operand / `it`)
        // FunctionCall.arguments = {r = 2} (the right operand)
        // Native function expects: {l = 1, r = 2}
        //
        // Restructure: extract receiver, combine into native arg record
        val fn = expr.function
        val receiver = if (fn is MemberAccess) {
            emitExpr(fn.receiver, file)
        } else null

        val existingArgs = expr.arguments
        val nativeVar = TypedVar(nativeName, BindingRef.Native(nativeName), type, loc)

        if (receiver != null && existingArgs is RecordLiteral) {
            // Add receiver as 'it' field (ability methods use {it, ...} parameter names)
            val fields = mutableListOf<TypedRecordEntry>()
            fields.add(TypedRecordEntry.Field("it", receiver, loc))
            for (entry in existingArgs.entries) {
                when (entry) {
                    is RecordFieldEntry -> fields.add(TypedRecordEntry.Field(
                        entry.name,
                        emitExpr(entry.value ?: Identifier(entry.name), file),
                        loc
                    ))
                    is RecordSpreadEntry -> fields.add(TypedRecordEntry.Spread(emitExpr(entry.expression, file), loc))
                }
            }
            val argType = MezzeType.RecordType(
                fields.filterIsInstance<TypedRecordEntry.Field>().associate { it.name to MezzeType.IntType }
            )
            val argRecord = TypedRecord(fields, argType, loc)
            return TypedCall(nativeVar, argRecord, null, false, type, loc)
        } else {
            // Fallback: pass args as-is
            return TypedCall(nativeVar, emitExpr(expr.arguments, file), null, false, type, loc)
        }
    }
}
