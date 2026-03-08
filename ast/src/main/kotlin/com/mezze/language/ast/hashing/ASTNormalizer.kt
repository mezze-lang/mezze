package com.mezze.language.ast.hashing

import com.mezze.language.ast.*

/**
 * Normalizes AST nodes for content-addressable hashing.
 *
 * This normalizer removes metadata that should not affect the hash:
 * - Source locations (file, line, column)
 * - Inferred types (added by typechecker)
 * - Type substitutions (monomorphization metadata)
 * - Resolved abilities/handlers (resolution metadata)
 *
 * It PRESERVES:
 * - All structural information
 * - Parameter names (in Mezze, they are part of the API)
 * - Type annotations (explicit user annotations)
 * - Effect information
 * - Ability constraints
 *
 * ## Important: Mezze Named Parameters
 *
 * In Mezze, function parameters are record fields (named parameters).
 * Parameter names ARE part of the API:
 * - `{x, y} -> x + y` and `{a, b} -> a + b` have DIFFERENT hashes
 * - Only internal bindings (let/match) are alpha-renamed (see InternalBindingRenamer)
 *
 * ## Thread Safety
 *
 * This normalizer is stateless and thread-safe.
 */
object ASTNormalizer {

    // =========================================================================
    // EXPRESSION NORMALIZATION
    // =========================================================================

    /**
     * Normalize an expression by stripping metadata fields.
     */
    fun normalizeExpression(expr: Expression): Expression = when (expr) {
        is IntLiteral -> expr.copy(
            inferredType = null,
            sourceLocation = null
        )

        is NatLiteral -> expr.copy(
            inferredType = null,
            sourceLocation = null
        )

        is FloatLiteral -> expr.copy(
            inferredType = null,
            sourceLocation = null
        )

        is StringLiteral -> expr.copy(
            parts = expr.parts?.map { normalizeStringPart(it) },
            inferredType = null,
            sourceLocation = null
        )

        is BooleanLiteral -> expr.copy(
            inferredType = null,
            sourceLocation = null
        )

        is FractionLiteral -> expr.copy(
            inferredType = null,
            sourceLocation = null
        )

        is ArrayLiteral -> expr.copy(
            elements = expr.elements.map { normalizeArrayElement(it) },
            inferredType = null,
            sourceLocation = null
        )

        is ArrayAccess -> expr.copy(
            array = normalizeExpression(expr.array),
            index = normalizeExpression(expr.index),
            inferredType = null,
            sourceLocation = null
        )

        is Identifier -> expr.copy(
            inferredType = null,
            sourceLocation = null
        )

        is PlaceholderExpression -> expr.copy(
            inferredType = null,
            sourceLocation = null
        )

        is ParenthesizedExpression -> expr.copy(
            expression = normalizeExpression(expr.expression),
            inferredType = null,
            sourceLocation = null
        )

        is RecordLiteral -> expr.copy(
            entries = expr.entries.map { normalizeRecordEntry(it) },
            inferredType = null,
            sourceLocation = null
        )

        is MemberAccess -> expr.copy(
            receiver = normalizeExpression(expr.receiver),
            inferredType = null,
            sourceLocation = null
        )

        is RecordUpdate -> expr.copy(
            record = normalizeExpression(expr.record),
            updates = expr.updates.map { RecordEntry(it.name, it.value?.let { v -> normalizeExpression(v) }) },
            inferredType = null,
            sourceLocation = null
        )

        is RecordUpdateExpression -> expr.copy(
            baseRecord = normalizeExpression(expr.baseRecord),
            updates = expr.updates.map {
                RecordUpdateEntry(it.fieldPath, normalizeExpression(it.value))
            },
            inferredType = null,
            sourceLocation = null
        )

        is VariantConstruction -> expr.copy(
            value = expr.value?.let { normalizeExpression(it) },
            inferredType = null,
            sourceLocation = null,
            resolution = ConstructorResolution.Unresolved  // Clear resolution metadata
        )

        is LambdaExpression -> expr.copy(
            parameters = normalizeParameters(expr.parameters),
            body = normalizeExpression(expr.body),
            // Keep returnType and abilityConstraints - they're part of the signature
            inferredType = null,
            sourceLocation = null
        )

        is FunctionCall -> expr.copy(
            function = normalizeExpression(expr.function),
            arguments = normalizeExpression(expr.arguments),
            inferredType = null,
            sourceLocation = null,
            resolvedAbilities = emptyMap(),  // Clear resolution metadata
            resolvedFunctionName = null,
            typeSubstitution = emptyMap(),
            instantiatedFunctionType = null,
            isTailCall = false  // TCO marker is optimization metadata
        )

        is IfExpression -> expr.copy(
            condition = normalizeExpression(expr.condition),
            thenBranch = normalizeExpression(expr.thenBranch),
            elseBranch = normalizeExpression(expr.elseBranch),
            inferredType = null,
            sourceLocation = null
        )

        is MatchExpression -> expr.copy(
            expression = normalizeExpression(expr.expression),
            cases = expr.cases.map { normalizeMatchCase(it) },
            inferredType = null,
            sourceLocation = null
        )

        is DoExpression -> expr.copy(
            statements = expr.statements.map { normalizeStatement(it) },
            result = normalizeExpression(expr.result),
            inferredType = null,
            sourceLocation = null
        )

        is PerformExpression -> expr.copy(
            expression = normalizeExpression(expr.expression),
            handlers = expr.handlers.map { normalizePerformHandler(it) },
            inferredType = null,
            sourceLocation = null,
            resolvedHandlers = emptyMap()  // Clear resolution metadata
        )

        is EffectCallExpression -> expr.copy(
            arguments = normalizeExpression(expr.arguments),
            inferredType = null,
            sourceLocation = null
        )

        is StateMachineExpression -> expr.copy(
            states = expr.states.map { normalizeStateMachineState(it) },
            inferredType = null,
            sourceLocation = null
        )
    }

    private fun normalizeStringPart(part: StringInterpolationPart): StringInterpolationPart = when (part) {
        is StringPart -> part
        is ExpressionPart -> ExpressionPart(normalizeExpression(part.expression))
    }

    private fun normalizeArrayElement(elem: ArrayElement): ArrayElement = when (elem) {
        is ArrayRegularElement -> ArrayRegularElement(normalizeExpression(elem.expression))
        is ArraySpreadElement -> ArraySpreadElement(normalizeExpression(elem.expression))
    }

    private fun normalizeRecordEntry(entry: RecordLiteralEntry): RecordLiteralEntry = when (entry) {
        is RecordFieldEntry -> RecordFieldEntry(
            name = entry.name,
            value = entry.value?.let { normalizeExpression(it) }
        )
        is RecordSpreadEntry -> RecordSpreadEntry(normalizeExpression(entry.expression))
    }

    private fun normalizeParameters(params: LambdaParameters): LambdaParameters = when (params) {
        is TypedParameters -> params  // Keep type annotations
        is UntypedParameters -> params  // Keep parameter names (they're part of API in Mezze)
    }

    private fun normalizeMatchCase(case: MatchCase): MatchCase = MatchCase(
        patterns = case.patterns.map { normalizePattern(it) },
        guard = case.guard?.let { normalizeExpression(it) },
        expression = normalizeExpression(case.expression)
    )

    private fun normalizePerformHandler(handler: PerformHandler): PerformHandler = PerformHandler(
        name = handler.name,
        parameters = handler.parameters?.let { normalizeExpression(it) as RecordLiteral }
    )

    private fun normalizeStateMachineState(state: StateMachineState): StateMachineState = when (state) {
        is ComputeState -> ComputeState(
            id = state.id,
            computation = normalizeExpression(state.computation),
            nextStateId = state.nextStateId
        )
        is YieldState -> YieldState(
            id = state.id,
            valueExpression = normalizeExpression(state.valueExpression),
            capturedVariables = state.capturedVariables,
            nextStateId = state.nextStateId
        )
        is CompleteState -> CompleteState(
            id = state.id,
            resultExpression = normalizeExpression(state.resultExpression)
        )
    }

    // =========================================================================
    // STATEMENT NORMALIZATION
    // =========================================================================

    fun normalizeStatement(stmt: Statement): Statement = when (stmt) {
        is LetStatement -> LetStatement(
            pattern = normalizePattern(stmt.pattern),
            typeAnnotation = stmt.typeAnnotation,  // Keep type annotations
            expression = normalizeExpression(stmt.expression)
        )
        is ExpressionStatement -> ExpressionStatement(normalizeExpression(stmt.expression))
    }

    // =========================================================================
    // PATTERN NORMALIZATION
    // =========================================================================

    fun normalizePattern(pattern: Pattern): Pattern = when (pattern) {
        is WildcardPattern -> pattern
        is VariablePattern -> pattern  // Keep variable names - they're bindings
        is ConstructorPattern -> ConstructorPattern(
            constructor = pattern.constructor,
            recordPattern = pattern.recordPattern?.let { normalizePattern(it) as RecordPattern }
        )
        is RecordPattern -> RecordPattern(
            entries = pattern.entries.map { entry ->
                RecordPatternEntry(
                    name = entry.name,
                    pattern = entry.pattern?.let { normalizePattern(it) }
                )
            },
            isOpen = pattern.isOpen
        )
        is LiteralPattern -> pattern
        is ArrayPattern -> ArrayPattern(
            elements = pattern.elements.map { normalizePattern(it) },
            restPattern = pattern.restPattern
        )
        is StringInterpolationPattern -> pattern  // String pattern parts are already normalized (just strings and names)
    }

    // =========================================================================
    // DECLARATION NORMALIZATION
    // =========================================================================

    /**
     * Normalize a LetBinding for hashing.
     */
    fun normalizeLetBinding(binding: LetBinding): LetBinding = LetBinding(
        pattern = normalizePattern(binding.pattern),
        visibility = binding.visibility,  // Keep visibility
        typeAnnotation = binding.typeAnnotation,  // Keep type annotations
        inferredType = null,  // Clear inferred types
        typeScheme = null,  // Clear type scheme
        expression = normalizeExpression(binding.expression),
        isMonomorphicInstance = false,  // Clear monomorphization metadata
        originalFunctionName = null,
        monomorphicTypeArgs = emptyMap()
    )

    /**
     * Normalize a TypeDeclaration for hashing.
     */
    fun normalizeTypeDeclaration(decl: TypeDeclaration): TypeDeclaration = TypeDeclaration(
        name = decl.name,  // Keep name for external reference
        visibility = decl.visibility,
        typeParams = decl.typeParams,
        definition = normalizeTypeDefinition(decl.definition),
        inferredType = null,
        sourceLocation = null
    )

    private fun normalizeTypeDefinition(def: TypeDefinition): TypeDefinition = when (def) {
        is RecordDefinition -> def  // Field names and types are structural
        is VariantDefinition -> def  // Constructor names and types are structural
        is NewtypeDefinition -> def  // Constructor and wrapped type are structural
    }

    /**
     * Normalize an EffectDeclaration for hashing.
     */
    fun normalizeEffectDeclaration(decl: EffectDeclaration): EffectDeclaration = EffectDeclaration(
        name = decl.name,
        operations = decl.operations,  // Operation signatures are structural
        associatedTypes = decl.associatedTypes,
        inferredType = null,
        sourceLocation = null
    )

    /**
     * Normalize an ImplDeclaration (effect handler) for hashing.
     */
    fun normalizeImplDeclaration(decl: ImplDeclaration): ImplDeclaration = ImplDeclaration(
        effectName = decl.effectName,
        performerName = decl.performerName,
        performerParams = decl.performerParams,
        associatedTypeBindings = decl.associatedTypeBindings,
        operations = decl.operations.map { op ->
            PerformerOperation(
                name = op.name,
                paramType = op.paramType,
                returnType = op.returnType,
                implementation = normalizeExpression(op.implementation),
                effects = op.effects
            )
        },
        inferredType = null,
        sourceLocation = null
    )

    /**
     * Normalize an AbilityDeclaration for hashing.
     */
    fun normalizeAbilityDeclaration(decl: AbilityDeclaration): AbilityDeclaration = AbilityDeclaration(
        name = decl.name,
        typeParams = decl.typeParams,
        associatedTypes = decl.associatedTypes,
        constraints = decl.constraints,
        dependencies = decl.dependencies,
        operations = decl.operations,  // Operation signatures are structural
        inferredType = null,
        sourceLocation = null
    )

    /**
     * Normalize an ImplAbilityDeclaration for hashing.
     */
    fun normalizeImplAbilityDeclaration(decl: ImplAbilityDeclaration): ImplAbilityDeclaration = ImplAbilityDeclaration(
        abilityName = decl.abilityName,
        abilityModulePath = decl.abilityModulePath,
        targetTypes = decl.targetTypes,
        forallVars = decl.forallVars,
        associatedTypeBindings = decl.associatedTypeBindings,
        operations = decl.operations.map { op ->
            ImplAbilityOperation(
                name = op.name,
                paramType = op.paramType,
                returnType = op.returnType,
                implementation = normalizeExpression(op.implementation)
            )
        },
        inferredType = null,
        sourceLocation = null
    )

    /**
     * Normalize a ProgramItem for hashing.
     */
    fun normalizeProgramItem(item: ProgramItem): ProgramItem = when (item) {
        is LetBinding -> normalizeLetBinding(item)
        is TypeDeclaration -> normalizeTypeDeclaration(item)
        is EffectDeclaration -> normalizeEffectDeclaration(item)
        is ImplDeclaration -> normalizeImplDeclaration(item)
        is AbilityDeclaration -> normalizeAbilityDeclaration(item)
        is ImplAbilityDeclaration -> normalizeImplAbilityDeclaration(item)
        is TopLevelExpression -> TopLevelExpression(normalizeExpression(item.expression))
        is UseDeclaration -> item  // Keep module path
        is PubUseDeclaration -> item  // Keep module path
    }

    /**
     * Normalize an entire program for hashing.
     */
    fun normalizeProgram(program: Program): Program = Program(
        items = program.items.map { normalizeProgramItem(it) }
    )
}
