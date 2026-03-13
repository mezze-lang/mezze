package com.mezze.language.ast.hashing

import com.mezze.language.ast.*

/**
 * Main hasher for computing content-addressable hashes of AST nodes.
 *
 * ## Hashing Process
 *
 * 1. **Normalize**: Strip metadata (source locations, inferred types)
 * 2. **Rename**: Alpha-rename internal bindings to De Bruijn indices
 * 3. **Serialize**: Convert to deterministic byte representation
 * 4. **Hash**: Compute SHA3-512 hash
 *
 * ## What Affects the Hash
 *
 * Included:
 * - Expression structure
 * - Parameter names (in Mezze, they're part of the API)
 * - Type annotations
 * - Effect rows
 * - Ability constraints
 * - Dependencies (hashes of referenced definitions)
 *
 * Excluded:
 * - Source locations
 * - Inferred types
 * - Internal binding names (alpha-renamed)
 * - Comments and documentation
 *
 * ## Thread Safety
 *
 * This hasher is stateless and thread-safe.
 */
object ASTHasher {

    // =========================================================================
    // HIGH-LEVEL API
    // =========================================================================

    /**
     * Compute hash for a let binding.
     */
    fun hashLetBinding(binding: LetBinding): MezzeHash {
        val normalized = ASTNormalizer.normalizeLetBinding(binding)
        val renamed = InternalBindingRenamer.renameLetBinding(normalized)
        return hashNormalizedLetBinding(renamed)
    }

    /**
     * Compute hash for a type declaration.
     */
    fun hashTypeDeclaration(decl: TypeDeclaration): MezzeHash {
        val normalized = ASTNormalizer.normalizeTypeDeclaration(decl)
        return hashNormalizedTypeDeclaration(normalized)
    }

    /**
     * Compute hash for an effect declaration.
     */
    fun hashEffectDeclaration(decl: EffectDeclaration): MezzeHash {
        val normalized = ASTNormalizer.normalizeEffectDeclaration(decl)
        return hashNormalizedEffectDeclaration(normalized)
    }

    /**
     * Compute hash for an effect handler implementation.
     */
    fun hashImplDeclaration(decl: ImplDeclaration): MezzeHash {
        val normalized = ASTNormalizer.normalizeImplDeclaration(decl)
        return hashNormalizedImplDeclaration(normalized)
    }

    /**
     * Compute hash for an ability declaration.
     */
    fun hashAbilityDeclaration(decl: AbilityDeclaration): MezzeHash {
        val normalized = ASTNormalizer.normalizeAbilityDeclaration(decl)
        return hashNormalizedAbilityDeclaration(normalized)
    }

    /**
     * Compute hash for an ability implementation.
     */
    fun hashImplAbilityDeclaration(decl: ImplAbilityDeclaration): MezzeHash {
        val normalized = ASTNormalizer.normalizeImplAbilityDeclaration(decl)
        return hashNormalizedImplAbilityDeclaration(normalized)
    }

    /**
     * Compute hash for any program item.
     */
    fun hashProgramItem(item: ProgramItem): MezzeHash = when (item) {
        is LetBinding -> hashLetBinding(item)
        is TypeDeclaration -> hashTypeDeclaration(item)
        is EffectDeclaration -> hashEffectDeclaration(item)
        is ImplDeclaration -> hashImplDeclaration(item)
        is AbilityDeclaration -> hashAbilityDeclaration(item)
        is ImplAbilityDeclaration -> hashImplAbilityDeclaration(item)
        is TopLevelExpression -> hashExpression(item.expression)
        is UseDeclaration -> hashUsePath(item.path, item.alias)
        is PubUseDeclaration -> hashUsePath(item.path, item.alias)
    }

    /**
     * Compute hash for a standalone expression.
     */
    fun hashExpression(expr: Expression): MezzeHash {
        val normalized = ASTNormalizer.normalizeExpression(expr)
        val renamed = InternalBindingRenamer.renameBindings(normalized)
        return hashNormalizedExpression(renamed)
    }

    // =========================================================================
    // EXPRESSION HASHING
    // =========================================================================

    private fun hashNormalizedExpression(expr: Expression): MezzeHash {
        val builder = MezzeHashBuilder()
        hashExpressionInto(expr, builder)
        return builder.build()
    }

    private fun hashExpressionInto(expr: Expression, builder: MezzeHashBuilder) {
        when (expr) {
            is IntLiteral -> {
                builder.append(byteArrayOf(HashTags.INTEGER_LITERAL))
                builder.appendLong(expr.value)
            }

            is NatLiteral -> {
                builder.append(byteArrayOf(HashTags.INTEGER_LITERAL))  // Same as Int structurally
                builder.appendLong(expr.value)
                builder.appendBoolean(true)  // Mark as Nat
            }

            is FloatLiteral -> {
                builder.append(byteArrayOf(HashTags.FLOAT_LITERAL))
                builder.appendLong(expr.value.toRawBits())
            }

            is StringLiteral -> {
                builder.append(byteArrayOf(HashTags.STRING_LITERAL))
                if (expr.parts != null) {
                    builder.appendBoolean(true)  // interpolated
                    builder.appendList(expr.parts) { part ->
                        hashStringPartInto(part, this)
                    }
                } else {
                    builder.appendBoolean(false)  // simple
                    builder.append(expr.value)
                }
            }

            is FractionLiteral -> {
                builder.append(byteArrayOf(HashTags.FLOAT_LITERAL))  // Similar structure
                builder.appendLong(expr.numerator)
                builder.appendLong(expr.denominator)
            }

            is SeqLiteral -> {
                builder.append(byteArrayOf(HashTags.ARRAY))
                builder.appendList(expr.elements) { elem ->
                    hashSeqElementInto(elem, this)
                }
            }

            is Identifier -> {
                builder.append(byteArrayOf(HashTags.IDENTIFIER))
                // Hash the structured identifier data, not the reconstructed string
                builder.append(expr.localName)
                if (expr.qualifier != null) {
                    for (segment in expr.qualifier.segments) {
                        builder.append(segment)
                    }
                }
            }

            is PlaceholderExpression -> {
                // Shouldn't appear in final AST, but hash it for completeness
                builder.append(byteArrayOf(0xFF.toByte()))
            }

            is ParenthesizedExpression -> {
                builder.append("paren:".toByteArray())
                hashExpressionInto(expr.expression, builder)
            }

            is RecordLiteral -> {
                builder.append(byteArrayOf(HashTags.RECORD))
                // Sort entries by name for deterministic hashing
                val sortedEntries = expr.entries.filterIsInstance<RecordFieldEntry>().sortedBy { it.name }
                builder.appendList(sortedEntries) { entry ->
                    append(entry.name)
                    appendNullable(entry.value) { hashExpressionInto(it, this) }
                }
                // Spread entries in order
                val spreadEntries = expr.entries.filterIsInstance<RecordSpreadEntry>()
                builder.appendList(spreadEntries) { entry ->
                    hashExpressionInto(entry.expression, this)
                }
                builder.appendNullable(expr.restField) { append(it) }
            }

            is MemberAccess -> {
                builder.append(byteArrayOf(HashTags.FIELD_ACCESS))
                hashExpressionInto(expr.receiver, builder)
                builder.append(expr.member)
            }

            is VariantConstruction -> {
                builder.append(byteArrayOf(HashTags.VARIANT_CONSTRUCTOR))
                builder.append(expr.constructor)
                builder.appendNullable(expr.value) { hashExpressionInto(it, this) }
            }

            is LambdaExpression -> {
                builder.append(byteArrayOf(HashTags.LAMBDA))
                hashParametersInto(expr.parameters, builder)
                hashExpressionInto(expr.body, builder)
                builder.appendNullable(expr.returnType) { hashTypeAnnotationInto(it, this) }
                builder.appendList(expr.abilityConstraints) { constraint ->
                    append(constraint.abilityName)
                    appendList(constraint.typeArgs) { hashTypeAnnotationInto(it, this) }
                }
            }

            is FunctionCall -> {
                builder.append(byteArrayOf(HashTags.FUNCTION_CALL))
                hashExpressionInto(expr.function, builder)
                hashExpressionInto(expr.arguments, builder)
            }

            is IfExpression -> {
                builder.append(byteArrayOf(HashTags.IF_ELSE))
                hashExpressionInto(expr.condition, builder)
                hashExpressionInto(expr.thenBranch, builder)
                hashExpressionInto(expr.elseBranch, builder)
            }

            is MatchExpression -> {
                builder.append(byteArrayOf(HashTags.MATCH))
                hashExpressionInto(expr.expression, builder)
                builder.appendList(expr.cases) { case ->
                    appendList(case.patterns) { hashPatternInto(it, this) }
                    appendNullable(case.guard) { hashExpressionInto(it, this) }
                    hashExpressionInto(case.expression, this)
                }
            }

            is DoExpression -> {
                builder.append(byteArrayOf(HashTags.BLOCK))
                builder.appendList(expr.statements) { stmt ->
                    hashStatementInto(stmt, this)
                }
                hashExpressionInto(expr.result, builder)
            }

            is PerformExpression -> {
                builder.append(byteArrayOf(HashTags.PERFORM))
                hashExpressionInto(expr.expression, builder)
                builder.appendList(expr.handlers) { handler ->
                    // Hash the QualifiedRef - include both qualifier and localName
                    append(handler.performer.localName)
                    handler.performer.qualifier?.let { q ->
                        appendList(q.segments) { append(it) }
                    }
                    appendNullable(handler.parameters) { hashExpressionInto(it, this) }
                }
            }

            is EffectCallExpression -> {
                builder.append(byteArrayOf(HashTags.PERFORM))
                // Hash the operation reference (localName + qualifier segments)
                builder.append(expr.operation.localName)
                expr.operation.qualifier?.let { q ->
                    builder.appendList(q.segments) { append(it) }
                }
                hashExpressionInto(expr.arguments, builder)
            }

            is StateMachineExpression -> {
                // State machines are compiler-generated, but we still hash them
                builder.append(byteArrayOf(HashTags.BLOCK))
                builder.appendBoolean(true)  // is state machine
                builder.appendInt(expr.initialState)
                builder.appendList(expr.states) { state ->
                    appendInt(state.id)
                    appendInt(state.nextStateId)
                    when (state) {
                        is ComputeState -> {
                            appendInt(0)  // tag
                            hashExpressionInto(state.computation, this)
                        }
                        is YieldState -> {
                            appendInt(1)  // tag
                            hashExpressionInto(state.valueExpression, this)
                            appendList(state.capturedVariables) { append(it) }
                        }
                        is CompleteState -> {
                            appendInt(2)  // tag
                            hashExpressionInto(state.resultExpression, this)
                        }
                    }
                }
            }
        }
    }

    private fun hashStringPartInto(part: StringInterpolationPart, builder: MezzeHashBuilder) {
        when (part) {
            is StringPart -> {
                builder.appendBoolean(false)  // not expression
                builder.append(part.value)
            }
            is ExpressionPart -> {
                builder.appendBoolean(true)  // is expression
                hashExpressionInto(part.expression, builder)
            }
        }
    }

    private fun hashSeqElementInto(elem: SeqElement, builder: MezzeHashBuilder) {
        when (elem) {
            is SeqRegularElement -> {
                builder.appendBoolean(false)  // not spread
                hashExpressionInto(elem.expression, builder)
            }
            is SeqSpreadElement -> {
                builder.appendBoolean(true)  // is spread
                hashExpressionInto(elem.expression, builder)
            }
        }
    }

    private fun hashParametersInto(params: LambdaParameters, builder: MezzeHashBuilder) {
        when (params) {
            is UntypedParameters -> {
                builder.appendBoolean(false)  // untyped
                builder.appendList(params.names) { append(it) }
                builder.appendBoolean(params.isOpen)
            }
            is TypedParameters -> {
                builder.appendBoolean(true)  // typed
                hashRecordTypeAnnotationInto(params.recordType, builder)
            }
        }
    }

    // =========================================================================
    // STATEMENT HASHING
    // =========================================================================

    private fun hashStatementInto(stmt: Statement, builder: MezzeHashBuilder) {
        when (stmt) {
            is LetStatement -> {
                builder.append(byteArrayOf(HashTags.LET_IN))
                hashPatternInto(stmt.pattern, builder)
                builder.appendNullable(stmt.typeAnnotation) { hashTypeAnnotationInto(it, this) }
                hashExpressionInto(stmt.expression, builder)
            }
            is ExpressionStatement -> {
                builder.append(byteArrayOf(0x00))  // expression statement tag
                hashExpressionInto(stmt.expression, builder)
            }
        }
    }

    // =========================================================================
    // PATTERN HASHING
    // =========================================================================

    /**
     * Hash a pattern, including variable binding names.
     * Used for patterns where the names are part of the API (like record field patterns).
     */
    private fun hashPatternInto(pattern: Pattern, builder: MezzeHashBuilder) {
        // Use binding pattern hasher with names excluded (alpha-renamed)
        hashBindingPatternInto(pattern, builder)
    }

    /**
     * Hash a binding pattern, excluding variable names (alpha-invariant).
     *
     * For content-addressable hashing:
     * - Variable binding names are NOT included in the hash
     * - Pattern structure IS included
     * - Field names in record patterns ARE included (they're part of the API)
     * - Constructor names ARE included (structural)
     * - Literal values ARE included (structural)
     */
    private fun hashBindingPatternInto(pattern: Pattern, builder: MezzeHashBuilder) {
        when (pattern) {
            is WildcardPattern -> {
                builder.append(byteArrayOf(HashTags.WILDCARD_PATTERN))
            }
            is VariablePattern -> {
                // Don't include the variable name - it's an internal binding
                // Just indicate that this is a variable binding
                builder.append(byteArrayOf(HashTags.VARIABLE_PATTERN))
                // Name is excluded for alpha-invariance
            }
            is ConstructorPattern -> {
                builder.append(byteArrayOf(HashTags.CONSTRUCTOR_PATTERN))
                // Constructor name IS structural - it determines which variant we're matching
                builder.append(pattern.constructor)
                builder.appendNullable(pattern.recordPattern) { hashBindingPatternInto(it, this) }
            }
            is RecordPattern -> {
                builder.append(byteArrayOf(HashTags.RECORD_PATTERN))
                // Sort entries by name for determinism
                // Field names ARE included - they're part of the record structure
                val sortedEntries = pattern.entries.sortedBy { it.name }
                builder.appendList(sortedEntries) { entry ->
                    append(entry.name)  // Field name is structural
                    appendNullable(entry.pattern) { hashBindingPatternInto(it, this) }
                }
                builder.appendBoolean(pattern.isOpen)
            }
            is LiteralPattern -> {
                builder.append(byteArrayOf(HashTags.LITERAL_PATTERN))
                hashLiteralValueInto(pattern.value, builder)
            }
            is SeqPattern -> {
                builder.append(byteArrayOf(HashTags.SEQ_PATTERN))
                builder.appendList(pattern.elements) { hashBindingPatternInto(it, this) }
                // Rest pattern name is excluded (alpha-renamed)
                builder.appendBoolean(pattern.restPattern != null)
            }
            is StringInterpolationPattern -> {
                builder.append(byteArrayOf(HashTags.STRING_INTERPOLATION_PATTERN))
                // Hash the parts - literal values are structural, capture variable names are excluded
                builder.appendList(pattern.parts) { part ->
                    when (part) {
                        is StringPatternLiteral -> {
                            appendBoolean(false)  // not a capture
                            append(part.value)
                        }
                        is StringPatternCapture -> {
                            appendBoolean(true)  // is a capture
                            // Variable name is excluded for alpha-invariance
                        }
                    }
                }
            }
        }
    }

    private fun hashLiteralValueInto(value: LiteralValue, builder: MezzeHashBuilder) {
        when (value) {
            is IntLiteralValue -> {
                builder.appendInt(0)
                builder.appendLong(value.value)
            }
            is NatLiteralValue -> {
                builder.appendInt(1)
                builder.appendLong(value.value)
            }
            is FloatLiteralValue -> {
                builder.appendInt(2)
                builder.appendLong(value.value.toRawBits())
            }
            is StringLiteralValue -> {
                builder.appendInt(3)
                builder.append(value.value)
            }
            is BooleanLiteralValue -> {
                builder.appendInt(4)
                builder.appendBoolean(value.value)
            }
        }
    }

    // =========================================================================
    // TYPE ANNOTATION HASHING
    // =========================================================================

    private fun hashTypeAnnotationInto(annotation: TypeAnnotation, builder: MezzeHashBuilder) {
        when (annotation) {
            is IdentifierTypeAnnotation -> {
                builder.appendInt(0)
                builder.append(annotation.name)
            }
            is TypeApplicationAnnotation -> {
                builder.appendInt(1)
                builder.append(annotation.constructor)
                builder.appendList(annotation.args) { hashTypeAnnotationInto(it, this) }
            }
            is FunctionTypeAnnotation -> {
                builder.appendInt(2)
                hashTypeAnnotationInto(annotation.paramType, builder)
                hashTypeAnnotationInto(annotation.returnType, builder)
                builder.appendNullable(annotation.effects) { hashEffectRowAnnotationInto(it, this) }
            }
            is RecordTypeAnnotation -> {
                builder.appendInt(3)
                hashRecordTypeAnnotationInto(annotation, builder)
            }
            is VariantTypeAnnotation -> {
                builder.appendInt(4)
                // Sort constructors by name for determinism
                val sorted = annotation.constructors.sortedBy { it.name }
                builder.appendList(sorted) { ctor ->
                    append(ctor.name)
                    appendNullable(ctor.dataType) { hashTypeAnnotationInto(it, this) }
                }
            }
            is SeqTypeAnnotation -> {
                builder.appendInt(5)
                hashTypeAnnotationInto(annotation.elementType, builder)
            }
            is EffectRowAnnotation -> {
                builder.appendInt(6)
                hashEffectRowAnnotationInto(annotation, builder)
            }
            is QualifiedTypeAnnotation -> {
                builder.appendInt(7)
                builder.appendList(annotation.modulePath) { append(it) }
                builder.append(annotation.typeName)
                builder.appendList(annotation.args) { hashTypeAnnotationInto(it, this) }
            }
        }
    }

    private fun hashRecordTypeAnnotationInto(record: RecordTypeAnnotation, builder: MezzeHashBuilder) {
        // Sort entries by name for determinism
        val sortedEntries = record.entries.sortedBy { it.name }
        builder.appendList(sortedEntries) { entry ->
            append(entry.name)
            hashTypeAnnotationInto(entry.type, this)
        }
        builder.appendNullable(record.restField) { append(it) }
    }

    private fun hashEffectRowAnnotationInto(effects: EffectRowAnnotation, builder: MezzeHashBuilder) {
        // Sort effects for determinism
        val sorted = effects.effects.sorted()
        builder.appendList(sorted) { append(it) }
        builder.appendBoolean(effects.isPolymorphic)
        builder.appendBoolean(effects.isPerformed)
    }

    // =========================================================================
    // DECLARATION HASHING
    // =========================================================================

    private fun hashNormalizedLetBinding(binding: LetBinding): MezzeHash {
        val builder = MezzeHashBuilder()
        builder.append(byteArrayOf(HashTags.LET_BINDING))
        // Don't hash the pattern name for simple variable bindings - the name is not part of the structure
        // For destructuring patterns, we hash the structure but not the binding names
        hashBindingPatternInto(binding.pattern, builder)
        builder.appendNullable(binding.typeAnnotation) { hashTypeAnnotationInto(it, this) }
        hashExpressionInto(binding.expression, builder)
        return builder.build()
    }

    private fun hashNormalizedTypeDeclaration(decl: TypeDeclaration): MezzeHash {
        val builder = MezzeHashBuilder()
        builder.append(byteArrayOf(HashTags.TYPE_DECLARATION))
        builder.appendList(decl.typeParams) { append(it) }
        hashTypeDefinitionInto(decl.definition, builder)
        return builder.build()
    }

    private fun hashTypeDefinitionInto(def: TypeDefinition, builder: MezzeHashBuilder) {
        when (def) {
            is RecordDefinition -> {
                builder.appendInt(0)
                // Sort fields by name for determinism
                val sortedFields = def.fields.sortedBy { it.name }
                builder.appendList(sortedFields) { field ->
                    append(field.name)
                    hashTypeAnnotationInto(field.type, this)
                }
                builder.appendBoolean(def.isOpen)
            }
            is VariantDefinition -> {
                builder.appendInt(1)
                // Sort constructors by name for determinism
                val sortedCtors = def.constructors.sortedBy { it.name }
                builder.appendList(sortedCtors) { ctor ->
                    append(ctor.name)
                    appendNullable(ctor.dataType) { hashTypeAnnotationInto(it, this) }
                }
                builder.appendBoolean(def.isOpen)
            }
            is NewtypeDefinition -> {
                builder.appendInt(2)
                builder.append(def.constructor)
                hashTypeAnnotationInto(def.wrappedType, builder)
            }
        }
    }

    private fun hashNormalizedEffectDeclaration(decl: EffectDeclaration): MezzeHash {
        val builder = MezzeHashBuilder()
        builder.append(byteArrayOf(HashTags.EFFECT_DECLARATION))
        // Sort operations by name for determinism
        val sortedOps = decl.operations.sortedBy { it.name }
        builder.appendList(sortedOps) { op ->
            append(op.name)
            hashTypeAnnotationInto(op.paramType, this)
            hashTypeAnnotationInto(op.returnType, this)
            appendList(op.effects.sorted()) { append(it) }
        }
        builder.appendList(decl.associatedTypes.sorted()) { append(it) }
        return builder.build()
    }

    private fun hashNormalizedImplDeclaration(decl: ImplDeclaration): MezzeHash {
        val builder = MezzeHashBuilder()
        builder.append(byteArrayOf(HashTags.IMPL_DECLARATION))
        // Hash the effect reference (localName + qualifier segments)
        builder.append(decl.effect.localName)
        decl.effect.qualifier?.let { q ->
            builder.appendList(q.segments) { append(it) }
        }
        builder.append(decl.performerName)
        // Sort params by name
        val sortedParams = decl.performerParams.sortedBy { it.name }
        builder.appendList(sortedParams) { param ->
            append(param.name)
            hashTypeAnnotationInto(param.type, this)
        }
        // Sort bindings by key
        builder.appendMap(decl.associatedTypeBindings,
            keyAppender = { append(it) },
            valueAppender = { hashTypeAnnotationInto(it, this) }
        )
        // Sort operations by name
        val sortedOps = decl.operations.sortedBy { it.name }
        builder.appendList(sortedOps) { op ->
            val renamedOp = InternalBindingRenamer.renamePerformerOperation(op)
            append(renamedOp.name)
            appendNullable(renamedOp.paramType) { hashTypeAnnotationInto(it, this) }
            appendNullable(renamedOp.returnType) { hashTypeAnnotationInto(it, this) }
            hashExpressionInto(renamedOp.implementation, this)
            appendNullable(renamedOp.effects) { hashEffectRowAnnotationInto(it, this) }
        }
        return builder.build()
    }

    private fun hashNormalizedAbilityDeclaration(decl: AbilityDeclaration): MezzeHash {
        val builder = MezzeHashBuilder()
        builder.append(byteArrayOf(HashTags.ABILITY_DECLARATION))
        builder.appendList(decl.typeParams) { append(it) }
        builder.appendList(decl.associatedTypes.sorted()) { append(it) }
        // Sort constraints by ability name
        val sortedConstraints = decl.constraints.sortedBy { it.abilityName }
        builder.appendList(sortedConstraints) { constraint ->
            append(constraint.abilityName)
            append(constraint.typeParam)
        }
        // Sort dependencies
        val sortedDeps = decl.dependencies.sortedBy { it.abilityName }
        builder.appendList(sortedDeps) { dep ->
            append(dep.abilityName)
            appendList(dep.typeArgs) { hashTypeAnnotationInto(it, this) }
        }
        // Sort operations by name
        val sortedOps = decl.operations.sortedBy { it.name }
        builder.appendList(sortedOps) { op ->
            append(op.name)
            hashTypeAnnotationInto(op.paramType, this)
            hashTypeAnnotationInto(op.returnType, this)
        }
        return builder.build()
    }

    private fun hashNormalizedImplAbilityDeclaration(decl: ImplAbilityDeclaration): MezzeHash {
        val builder = MezzeHashBuilder()
        builder.append(byteArrayOf(HashTags.IMPL_ABILITY_DECLARATION))
        // Hash the ability reference (localName + qualifier segments)
        builder.append(decl.ability.localName)
        decl.ability.qualifier?.let { q ->
            builder.appendList(q.segments) { append(it) }
        }
        builder.appendList(decl.targetTypes) { hashTypeAnnotationInto(it, this) }
        builder.appendList(decl.forallVars) { append(it) }
        // Sort bindings by key
        builder.appendMap(decl.associatedTypeBindings,
            keyAppender = { append(it) },
            valueAppender = { hashTypeAnnotationInto(it, this) }
        )
        // Sort operations by name
        val sortedOps = decl.operations.sortedBy { it.name }
        builder.appendList(sortedOps) { op ->
            val renamedOp = InternalBindingRenamer.renameImplOperation(op)
            append(renamedOp.name)
            appendNullable(renamedOp.paramType) { hashTypeAnnotationInto(it, this) }
            appendNullable(renamedOp.returnType) { hashTypeAnnotationInto(it, this) }
            hashExpressionInto(renamedOp.implementation, this)
        }
        return builder.build()
    }

    private fun hashUsePath(path: UsePath, alias: String?): MezzeHash {
        val builder = MezzeHashBuilder()
        when (path) {
            is ModulePath -> {
                builder.appendInt(0)
                builder.appendList(path.segments) { append(it) }
            }
            is SelectivePath -> {
                builder.appendInt(1)
                builder.appendList(path.module) { append(it) }
                // Sort items by name
                val sortedItems = path.items.sortedBy { it.name }
                builder.appendList(sortedItems) { item ->
                    append(item.name)
                    appendNullable(item.alias) { append(it) }
                }
            }
        }
        builder.appendNullable(alias) { append(it) }
        return builder.build()
    }
}
