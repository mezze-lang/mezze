package com.mezze.language.typechecker.constraints

import com.mezze.language.ast.*
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.abilities.BuiltInAbilities
import com.mezze.language.typechecker.context.ContextBuilder
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.dependencies.DefinitionRef
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.typechecker.SpreadSyntaxError
import com.mezze.language.typechecker.debug.DebugLogger
import com.mezze.language.typechecker.debug.DebugLogger.Category
import com.mezze.language.typechecker.solving.collectFreeTypeVars
import com.mezze.language.typechecker.ErrorType
import com.mezze.language.typechecker.util.StringDistance
import com.mezze.language.ast.Keywords
import com.mezze.language.parsedast.diagnostics.ErrorCode

/**
 * Expression constraint generator - generates constraints for all expression types.
 *
 * This is the heart of constraint-based type inference.
 * For each expression type, we:
 * 1. Generate constraints for sub-expressions
 * 2. Create fresh type variables as needed
 * 3. Add equality/ability/effect constraints
 * 4. Return ConstraintResult with type + constraints
 *
 * Design:
 * - Pure functions (no mutation!)
 * - Exhaustive when on Expression sealed interface
 * - No catch-all else clauses
 *
 * Performance optimizations:
 * - TypeVar mapping cache for effect operations (avoids repeated structural traversal)
 * - Lazy effectInstance creation (only when in perform context with associated types)
 * - Short-circuit checks to avoid unnecessary work
 */
class ExpressionConstraintGenerator {

    // ========================================================================
    // CACHES FOR PERFORMANCE
    // ========================================================================

    /**
     * Cache for TypeVar mappings from original to instantiated types.
     * Key: Pair of (original type identity hashCode, instantiated type identity hashCode)
     * This avoids repeated structural traversal for the same type pairs.
     *
     * The cache is automatically cleared when it grows too large to prevent memory issues
     * in long-running sessions.
     */
    internal val typeVarMappingCache = mutableMapOf<Pair<Int, Int>, Map<TypeVar, TypeVar>>()

    /** Maximum cache size before automatic cleanup */
    internal val maxCacheSize = 1000

    /**
     * Types of let bindings inside do blocks, collected during constraint generation.
     * Keyed by binding name, value is the type variable (before substitution).
     * Used to resolve types of nested bindings
     * that don't have inferredType set on their expressions (e.g., effect calls).
     */
    val nestedBindingTypes = mutableMapOf<String, MezzeType>()


    /**
     * Generate constraints for an expression.
     *
     * Guarantees: the returned ConstraintResult.type is NEVER a SuspendedType.
     * Any effects wrapped in a SuspendedType are extracted into the result's
     * effects/effectInstances/performed fields. Downstream code (constraint
     * solver, unifier) never sees SuspendedType.
     */
    fun generate(expr: ParsedExpr, context: ConstraintContext): ConstraintResult {
        val raw = generateImpl(expr, context)
        return when (val t = raw.type) {
            is MezzeType.SuspendedType -> raw.copy(
                type = t.valueType,
                effects = raw.effects + t.effects.effects,
                effectInstances = raw.effectInstances + t.effects.effectInstances,
                performed = raw.performed || t.effects.performed
            )
            else -> raw
        }
    }

    private fun generateImpl(expr: ParsedExpr, context: ConstraintContext): ConstraintResult {
        return when (expr) {
            // Literals - simple types, no constraints
            is ParsedIntLit -> generateIntLiteral(expr, context)
            is ParsedNatLit -> generateNatLiteral(expr, context)
            is ParsedFloatLit -> generateFloatLiteral(expr, context)
            is ParsedFracLit -> generateFractionLiteral(expr, context)
            is ParsedStringLit -> generateStringLiteral(expr, context)
            is ParsedSeqLit -> generateSeqLiteral(expr, context)
            is ParsedCharLit -> ConstraintResult.simple(MezzeType.CharType)

            // Variables - lookup in context
            is ParsedIdentifier -> generateIdentifier(expr, context)

            // Pipe placeholder - should not reach constraint generation (desugared earlier)
            is ParsedPipePlaceholder -> generatePipePlaceholder(expr, context)

            // Pipe expression - should not reach constraint generation (desugared earlier)
            is ParsedPipe -> generatePipeExpression(expr, context)

            // Parenthesized expression - just delegate to inner
            is ParsedParens -> generate(expr.expr, context)

            // Records
            is ParsedRecordLit -> generateRecordLiteral(expr, context)
            is ParsedMemberAccess -> generateMemberAccess(expr, context)

            // Variants
            is ParsedVariantConstruct -> generateVariantConstruction(expr, context)

            // Functions
            is ParsedLambda -> generateLambda(expr, context)
            is ParsedFunctionCall -> generateFunctionCall(expr, context)

            // Control flow
            // ParsedIf is unified with ParsedMatch at the type-checking level.
            // This simplifies the typechecker (one code path for all conditionals) while
            // preserving ParsedIf in the AST for tooling and IfNode in the interpreter
            // for optimal runtime performance.
            is ParsedIf -> generateMatch(expr.toMatchExpr(), context)
            is ParsedMatch -> generateMatch(expr, context)

            // Blocks
            is ParsedDo -> generateDo(expr, context)

            // Operators — desugar to method calls for type checking
            is ParsedBinaryOp -> generateBinaryOp(expr, context)
            is ParsedUnaryOp -> generateUnaryOp(expr, context)

            // Effects
            is ParsedPerform -> generatePerform(expr, context)
            is ParsedEffectCall -> generateEffectCall(expr, context)
        }
    }

    // ========================================================================
    // CONSTRAINT COLLECTOR — eliminates repeated merge boilerplate
    // ========================================================================

    /**
     * Mutable accumulator for sub-expression constraint results.
     *
     * Many generator functions create 8-11 mutable collections, then call
     * `addAll`/`putAll` on each one for every sub-expression result. This
     * class centralises that pattern: call [merge] for each sub-result,
     * then [toResult] to build the final [ConstraintResult].
     */
    internal inner class ConstraintCollector {
        val constraints = mutableListOf<Constraint>()
        val undefinedVars = mutableListOf<ConstraintResult.UndefinedVariable>()
        val effects = mutableSetOf<String>()
        val effectInstances = mutableSetOf<MezzeType.EffectInstance>()
        var performed = false
        val abilityConstraints = mutableListOf<Constraint.AbilityRequired>()
        val associatedTypeConstraints = mutableListOf<Constraint.AssociatedTypeResolution>()
        val usedVars = mutableSetOf<String>()
        val referencedBindings = mutableSetOf<DefinitionRef.ByName>()
        val methodCallResolutions = mutableMapOf<Pair<Loc, String>, ConstraintResult.MethodCallInfo>()
        val callReturnTypes = mutableMapOf<Loc, MezzeType>()
        val duplicateBindings = mutableListOf<ConstraintResult.DuplicateBinding>()
        val tailCalls = mutableSetOf<Loc>()

        fun merge(result: ConstraintResult) {
            constraints.addAll(result.constraints)
            undefinedVars.addAll(result.undefinedVariables)
            effects.addAll(result.effects)
            effectInstances.addAll(result.effectInstances)
            performed = performed || result.performed
            abilityConstraints.addAll(result.abilityConstraints)
            associatedTypeConstraints.addAll(result.associatedTypeConstraints)
            usedVars.addAll(result.usedVariables)
            referencedBindings.addAll(result.referencedBindings)
            methodCallResolutions.putAll(result.methodCallResolutions)
            callReturnTypes.putAll(result.callReturnTypes)
            duplicateBindings.addAll(result.duplicateBindings)
            tailCalls.addAll(result.tailCalls)
        }

        fun toResult(type: MezzeType): ConstraintResult = ConstraintResult(
            type = type,
            constraints = constraints,
            effects = effects,
            effectInstances = effectInstances,
            performed = performed,
            abilityConstraints = abilityConstraints,
            associatedTypeConstraints = associatedTypeConstraints,
            undefinedVariables = undefinedVars,
            usedVariables = usedVars,
            referencedBindings = referencedBindings,
            methodCallResolutions = methodCallResolutions,
            callReturnTypes = callReturnTypes,
            duplicateBindings = duplicateBindings,
            tailCalls = tailCalls,
        )
    }

    /** Convert ParsedIf to ParsedMatch for unified type-checking. */
    private fun ParsedIf.toMatchExpr(): ParsedMatch = ParsedMatch(
        scrutinee = condition,
        cases = listOf(
            ParsedMatchCase(
                patterns = listOf(ParsedConstructorPattern("True", null, loc)),
                body = thenBranch,
                loc = loc
            ),
            ParsedMatchCase(
                patterns = listOf(ParsedConstructorPattern("False", null, loc)),
                body = elseBranch,
                loc = loc
            )
        ),
        loc = loc
    )

    internal fun instantiateTypeWithFreshVars(type: MezzeType): MezzeType {
        val freeVars = getFreeTypeVariables(type)
        if (freeVars.isEmpty()) {
            return type
        }

        // CRITICAL: Preserve names for associated type variables (those starting with ')
        // This allows them to be matched with performer bindings later.
        //
        // #183: Use `freshWithName` (not bare `TypeVar(name)`) so each
        // fresh-instantiation gets a unique id. Plain `TypeVar(name)`
        // uses the default `id = 0`, and `data class` equality then
        // collapses every `'it` across every imported impl onto the
        // SAME instance — chains like `c.get { p }.with_json { v }.send {}`
        // end up unifying the receivers of two different impls together
        // ("Type constructor mismatch: Req vs Client").
        val substitution = freeVars.associateWith { oldVar ->
            if (oldVar.name.startsWith("'")) {
                // Associated type variable - preserve the name but create new instance
                TypeVar.freshWithName(oldVar.name)
            } else {
                // Regular type variable - create fresh one
                TypeVar.fresh()
            }
        }
        return applyTypeVarSubstitution(type, substitution)
    }

    /**
     * Instantiate a type with fresh type variables, but preserve the names of associated types.
     * @param type The type to instantiate
     * @param associatedTypeNames List of associated type names (with or without ' prefix, e.g., ["'item"] or ["item"])
     */
    internal fun instantiateTypeWithFreshVarsPreservingAssociated(
        type: MezzeType,
        associatedTypeNames: List<String>
    ): MezzeType {
        return instantiateTypeWithFreshVarsPreservingAssociatedWithMapping(type, associatedTypeNames).first
    }

    /**
     * Same as instantiateTypeWithFreshVarsPreservingAssociated but also returns the mapping
     * from original type var names (normalized, without ') to the fresh TypeVar instances.
     */
    internal fun instantiateTypeWithFreshVarsPreservingAssociatedWithMapping(
        type: MezzeType,
        associatedTypeNames: List<String>
    ): Pair<MezzeType, Map<String, TypeVar>> {
        val freeVars = getFreeTypeVariables(type)
        if (freeVars.isEmpty()) {
            return type to emptyMap()
        }

        // Normalize associated type names (remove ' prefix if present)
        val normalizedAssociatedNames = associatedTypeNames.map { it.removePrefix("'") }.toSet()

        // Create substitution: fresh vars for type params, preserve names for associated types
        val substitution = freeVars.associateWith { oldVar ->
            val normalizedName = oldVar.name.removePrefix("'")
            if (normalizedAssociatedNames.contains(normalizedName)) {
                // Associated type variable - preserve the name but create new instance
                // Use the original name to ensure matching later
                TypeVar.freshWithName(oldVar.name)
            } else {
                // Regular type variable - create fresh one
                TypeVar.fresh()
            }
        }

        // Build the name mapping: original var name (normalized) -> fresh TypeVar
        val nameMapping = substitution.entries.associate { (oldVar, newVar) ->
            oldVar.name.removePrefix("'") to newVar
        }

        return applyTypeVarSubstitution(type, substitution) to nameMapping
    }

    /**
     * Instantiate type with fresh type variables, but preserve TypeVars that came from
     * performer bindings. These TypeVars are used to connect effect operation argument types
     * to the performer's associated type bindings, which then flow to Result type parameters.
     *
     * Unlike instantiateTypeWithFreshVarsPreservingAssociated which preserves based on
     * names starting with ', this function preserves based on an explicit list of names
     * (typically the TypeVars from performerType.fields.values).
     */
    internal fun instantiateTypeWithFreshVarsPreservingPerformerBindings(
        type: MezzeType,
        performerTypeVarNames: List<String>
    ): MezzeType {
        val freeVars = getFreeTypeVariables(type)
        if (freeVars.isEmpty()) {
            return type
        }

        // Create a set of names to preserve (handle both with and without ' prefix)
        val namesToPreserve = performerTypeVarNames.flatMap { name ->
            listOf(name, name.removePrefix("'"), "'$name")
        }.toSet()

        // Create substitution: fresh vars for regular type params, preserve performer bindings
        val substitution = freeVars.associateWith { oldVar ->
            if (namesToPreserve.contains(oldVar.name) || namesToPreserve.contains(oldVar.name.removePrefix("'"))) {
                // Performer binding TypeVar - keep the SAME instance so constraints connect
                oldVar
            } else {
                // Regular type variable - create fresh one
                TypeVar.fresh()
            }
        }
        return applyTypeVarSubstitution(type, substitution)
    }

    /**
     * Get all free type variables in a type.
     */
    internal fun getFreeTypeVariables(type: MezzeType): Set<TypeVar> {
        return when (type) {
            is MezzeType.VarType -> setOf(type.variable)
            is MezzeType.FunctionType -> {
                getFreeTypeVariables(type.paramType) + getFreeTypeVariables(type.returnType)
            }
            is MezzeType.RecordType -> {
                type.fields.values.flatMap { getFreeTypeVariables(it) }.toSet()
            }
            is MezzeType.VariantType -> {
                type.constructors.values.filterNotNull().flatMap { getFreeTypeVariables(it) }.toSet() +
                type.typeArgs.flatMap { getFreeTypeVariables(it) }.toSet()
            }
            is MezzeType.SeqType -> getFreeTypeVariables(type.elementType)
            is MezzeType.ListType -> getFreeTypeVariables(type.elementType)
            is MezzeType.SetType -> getFreeTypeVariables(type.elementType)
            is MezzeType.SortedMapType -> getFreeTypeVariables(type.keyType) + getFreeTypeVariables(type.valueType)
            is MezzeType.SortedSetType -> getFreeTypeVariables(type.elementType)
            is MezzeType.MapType -> getFreeTypeVariables(type.keyType) + getFreeTypeVariables(type.valueType)
            is MezzeType.SuspendedType -> getFreeTypeVariables(type.valueType)
            is MezzeType.TypeApplication -> type.args.flatMap { getFreeTypeVariables(it) }.toSet()
            is MezzeType.NewtypeType -> getFreeTypeVariables(type.wrappedType)
            // Primitive types have no variables
            is MezzeType.IntType, is MezzeType.FloatType, is MezzeType.StringType,
            is MezzeType.BoolType, is MezzeType.NatType, is MezzeType.FracType,
            is MezzeType.OrderingType, is MezzeType.BytesType, is MezzeType.ByteBufferType,
            is MezzeType.CharType, is MezzeType.EffectRow -> emptySet()
        }
    }

    /**
     * Apply type variable substitution to a type.
     */
    internal fun applyTypeVarSubstitution(type: MezzeType, substitution: Map<TypeVar, TypeVar>): MezzeType {
        return when (type) {
            is MezzeType.VarType -> {
                val newVar = substitution[type.variable]
                if (newVar != null) MezzeType.VarType(newVar) else type
            }
            is MezzeType.FunctionType -> MezzeType.FunctionType(
                paramType = applyTypeVarSubstitution(type.paramType, substitution),
                returnType = applyTypeVarSubstitution(type.returnType, substitution),
                effects = type.effects,
                // CRITICAL: Apply substitution to ability constraints too
                // This ensures that fresh type variables in the constraint are properly connected
                abilityConstraints = type.abilityConstraints.map { constraint ->
                    MezzeType.FunctionAbilityConstraint(
                        abilityName = constraint.abilityName,
                        typeArgs = constraint.typeArgs.map { applyTypeVarSubstitution(it, substitution) },
                        abilityHash = constraint.abilityHash
                    )
                }
            )
            is MezzeType.RecordType -> MezzeType.RecordType(
                fields = type.fields.mapValues { (_, fieldType) ->
                    applyTypeVarSubstitution(fieldType, substitution)
                },
                rowVar = type.rowVar,
                typeName = type.typeName,
                typeHash = type.typeHash
            )
            is MezzeType.VariantType -> MezzeType.VariantType(
                constructors = type.constructors.mapValues { (_, dataType) ->
                    dataType?.let { applyTypeVarSubstitution(it, substitution) }
                },
                rowVar = type.rowVar,
                typeName = type.typeName,
                typeArgs = type.typeArgs.map { applyTypeVarSubstitution(it, substitution) },
                typeHash = type.typeHash,
                nominalWall = type.nominalWall
            )
            is MezzeType.SeqType -> MezzeType.SeqType(
                elementType = applyTypeVarSubstitution(type.elementType, substitution)
            )
            is MezzeType.ListType -> MezzeType.ListType(
                elementType = applyTypeVarSubstitution(type.elementType, substitution)
            )
            is MezzeType.SetType -> MezzeType.SetType(
                elementType = applyTypeVarSubstitution(type.elementType, substitution)
            )
            is MezzeType.SortedMapType -> MezzeType.SortedMapType(
                keyType = applyTypeVarSubstitution(type.keyType, substitution),
                valueType = applyTypeVarSubstitution(type.valueType, substitution)
            )
            is MezzeType.SortedSetType -> MezzeType.SortedSetType(
                elementType = applyTypeVarSubstitution(type.elementType, substitution)
            )
            is MezzeType.MapType -> MezzeType.MapType(
                keyType = applyTypeVarSubstitution(type.keyType, substitution),
                valueType = applyTypeVarSubstitution(type.valueType, substitution)
            )
            is MezzeType.SuspendedType -> MezzeType.SuspendedType(
                valueType = applyTypeVarSubstitution(type.valueType, substitution),
                effects = type.effects
            )
            is MezzeType.TypeApplication -> com.mezze.language.typechecker.util.WellKnownTypeApp.of(
                constructor = type.constructor,
                args = type.args.map { applyTypeVarSubstitution(it, substitution) },
                typeHash = type.typeHash
            )
            is MezzeType.NewtypeType -> MezzeType.NewtypeType(
                type.name,
                type.constructor,
                applyTypeVarSubstitution(type.wrappedType, substitution)
            )
            // Primitive types unchanged
            is MezzeType.IntType, is MezzeType.FloatType, is MezzeType.StringType,
            is MezzeType.BoolType, is MezzeType.NatType, is MezzeType.FracType,
            is MezzeType.OrderingType, is MezzeType.BytesType, is MezzeType.ByteBufferType,
            is MezzeType.CharType, is MezzeType.EffectRow -> type
        }
    }

    /**
     * Extract type variables from a type in order.
     * Used for ability constraint generation.
     */
    internal fun extractTypeVariables(type: MezzeType): List<TypeVar> {
        // For function types, we want type variables from parameters first
        return when (type) {
            is MezzeType.VarType -> listOf(type.variable)
            is MezzeType.FunctionType -> {
                extractTypeVariables(type.paramType) + extractTypeVariables(type.returnType)
            }
            is MezzeType.RecordType -> {
                type.fields.values.flatMap { extractTypeVariables(it) }
            }
            is MezzeType.VariantType -> {
                type.constructors.values.filterNotNull().flatMap { extractTypeVariables(it) } +
                type.typeArgs.flatMap { extractTypeVariables(it) }
            }
            is MezzeType.SeqType -> extractTypeVariables(type.elementType)
            is MezzeType.ListType -> extractTypeVariables(type.elementType)
            is MezzeType.SetType -> extractTypeVariables(type.elementType)
            is MezzeType.SortedMapType -> extractTypeVariables(type.keyType) + extractTypeVariables(type.valueType)
            is MezzeType.SortedSetType -> extractTypeVariables(type.elementType)
            is MezzeType.MapType -> extractTypeVariables(type.keyType) + extractTypeVariables(type.valueType)
            is MezzeType.SuspendedType -> extractTypeVariables(type.valueType)
            is MezzeType.TypeApplication -> type.args.flatMap { extractTypeVariables(it) }
            // Primitive types have no variables
            else -> emptyList()
        }
    }

    /**
     * Find an ability implementation that matches the given type arguments.
     *
     * This implements bidirectional matching from OldTypeChecker.kt lines 16246-16300:
     * - Type variables in `typeArgs` can match concrete types in implementation
     * - This enables inference: Convert Int 'a can match impl Convert for Int String
     *
     * Uses structural type matching via targetTypeAnnotations for correctness,
     * falling back to string-based matching only when annotations are not available.
     *
     * @param abilityName The ability name (e.g., "Convert")
     * @param typeArgs The type arguments from the constraint (may contain type variables)
     * @param context The constraint context (for looking up implementations)
     * @return The matched implementation's type list, or null if no match
     */
    internal fun findAbilityImplementation(
        abilityName: String,
        typeArgs: List<MezzeType>,
        context: ConstraintContext
    ): List<String>? {
        // Get all implementations for this ability
        val impls = context.typingContext.getAbilityImpls(abilityName)

        if (impls.isEmpty()) {
            return null
        }

        // Find implementation that matches the type arguments using structural matching
        for (impl in impls) {
            val annotations = impl.targetTypeAnnotations

            // Prefer structural matching via targetTypeAnnotations
            if (annotations != null && annotations.size == typeArgs.size) {
                val allMatch = typeArgs.zip(annotations).all { (constraintType, annotation) ->
                    typeMatchesAnnotationBidirectional(constraintType, annotation)
                }
                if (allMatch) {
                    return impl.targetTypes
                }
            } else if (impl.targetTypes.size == typeArgs.size) {
                // Fallback to string matching only when annotations not available
                val allMatch = typeArgs.zip(impl.targetTypes).all { (constraintType, implTypeName) ->
                    typeMatchesStringBidirectional(constraintType, implTypeName)
                }
                if (allMatch) {
                    return impl.targetTypes
                }
            }
        }

        return null
    }

    /**
     * Check if a MezzeType matches a TypeAnnotation with bidirectional inference.
     * Type variables in constraintType can match any concrete type in the annotation.
     */
    internal fun typeMatchesAnnotationBidirectional(constraintType: MezzeType, annotation: com.mezze.language.parsedast.ParsedTypeAnnotation): Boolean {
        if (constraintType is MezzeType.VarType) return true
        return when (annotation) {
            is com.mezze.language.parsedast.ParsedNamedType -> {
                val b = PrimitiveTypes.matchesBuiltinType(annotation.name, constraintType); if (b != null) b
                else if (annotation.name.startsWith("'") || annotation.name.first().isLowerCase()) true
                else when (constraintType) { is MezzeType.TypeApplication -> constraintType.constructor == annotation.name && constraintType.args.isEmpty(); is MezzeType.RecordType -> constraintType.typeName == annotation.name; is MezzeType.VariantType -> constraintType.typeName == annotation.name; else -> false }
            }
            is com.mezze.language.parsedast.ParsedTypeApp -> when (constraintType) {
                is MezzeType.TypeApplication -> constraintType.constructor == annotation.constructor && constraintType.args.size == annotation.args.size && constraintType.args.zip(annotation.args).all { (a, b) -> typeMatchesAnnotationBidirectional(a, b) }
                is MezzeType.SeqType -> annotation.constructor == BuiltinTypes.SEQ && annotation.args.size == 1 && typeMatchesAnnotationBidirectional(constraintType.elementType, annotation.args[0])
                is MezzeType.ListType -> annotation.constructor == BuiltinTypes.LIST && annotation.args.size == 1 && typeMatchesAnnotationBidirectional(constraintType.elementType, annotation.args[0])
                is MezzeType.SetType -> annotation.constructor == BuiltinTypes.SET && annotation.args.size == 1 && typeMatchesAnnotationBidirectional(constraintType.elementType, annotation.args[0])
                is MezzeType.MapType -> annotation.constructor == BuiltinTypes.MAP && annotation.args.size == 2 && typeMatchesAnnotationBidirectional(constraintType.keyType, annotation.args[0]) && typeMatchesAnnotationBidirectional(constraintType.valueType, annotation.args[1])
                else -> false
            }
            is com.mezze.language.parsedast.ParsedRecordType -> constraintType is MezzeType.RecordType && annotation.entries.all { e -> constraintType.fields[e.name]?.let { typeMatchesAnnotationBidirectional(it, e.type) } ?: false }
            is com.mezze.language.parsedast.ParsedVariantType -> constraintType is MezzeType.VariantType && annotation.constructors.all { c -> val p = constraintType.constructors[c.name]; if (c.dataType == null) p == null else if (p == null) false else typeMatchesAnnotationBidirectional(p, c.dataType!!) }
            is com.mezze.language.parsedast.ParsedSeqType -> constraintType is MezzeType.SeqType && typeMatchesAnnotationBidirectional(constraintType.elementType, annotation.elementType)
            is com.mezze.language.parsedast.ParsedFunctionType -> constraintType is MezzeType.FunctionType && typeMatchesAnnotationBidirectional(constraintType.paramType, annotation.paramType) && typeMatchesAnnotationBidirectional(constraintType.returnType, annotation.returnType)
            is com.mezze.language.parsedast.ParsedQualifiedType -> constraintType is MezzeType.TypeApplication && constraintType.constructor == (annotation.modulePath + annotation.typeName).joinToString("::") && constraintType.args.size == annotation.args.size && constraintType.args.zip(annotation.args).all { (a, b) -> typeMatchesAnnotationBidirectional(a, b) }
        }
    }


    /**
     * Check if a MezzeType matches a type name string with bidirectional inference.
     * Used as fallback when targetTypeAnnotations are not available.
     *
     * NOTE: This is a legacy fallback. Prefer using structured type matching
     * via MezzeType pattern matching when possible.
     */
    internal fun typeMatchesStringBidirectional(constraintType: MezzeType, implTypeName: String): Boolean {
        // Type variable can match any concrete type
        if (constraintType is MezzeType.VarType) {
            return true
        }

        return when (constraintType) {
            // Primitive types: exact string match
            MezzeType.IntType -> implTypeName == "Int"
            MezzeType.FloatType -> implTypeName == "Float"
            MezzeType.StringType -> implTypeName == "Str"
            MezzeType.BoolType -> implTypeName == "Bool"
            MezzeType.NatType -> implTypeName == "Nat"
            MezzeType.FracType -> implTypeName == "Frac"
            MezzeType.CharType -> implTypeName == "Char"
            MezzeType.OrderingType -> implTypeName == "Ordering"
            MezzeType.BytesType -> implTypeName == "Bytes"
            MezzeType.ByteBufferType -> implTypeName == "ByteBuffer"

            // Type applications: match constructor or constructor_suffix pattern
            is MezzeType.TypeApplication ->
                implTypeName == constraintType.constructor ||
                implTypeName.startsWith("${constraintType.constructor}_")

            // Collection types: match "Type" or "Type_element" pattern
            // Using exact prefix + optional suffix to avoid false positives
            is MezzeType.SeqType -> matchesCollectionType(implTypeName, "Seq")
            is MezzeType.ListType -> matchesCollectionType(implTypeName, "List")
            is MezzeType.SetType -> matchesCollectionType(implTypeName, "Set")
            is MezzeType.SortedSetType -> matchesCollectionType(implTypeName, "SortedSet")
            is MezzeType.MapType -> matchesCollectionType(implTypeName, "Map")
            is MezzeType.SortedMapType -> matchesCollectionType(implTypeName, "SortedMap")

            // Structural types: exact name match
            is MezzeType.RecordType -> implTypeName == constraintType.typeName
            is MezzeType.VariantType -> implTypeName == constraintType.typeName

            else -> false
        }
    }

    /**
     * Match a collection type name with its canonical prefix.
     * Accepts "Seq", "Seq_Int", "Seq_String", etc.
     * Rejects "SeqIterator" or other types that happen to start with "Seq".
     */
    internal fun matchesCollectionType(implTypeName: String, prefix: String): Boolean {
        return implTypeName == prefix ||                           // Exact match: "Seq"
               implTypeName.startsWith("${prefix}_") ||            // Element suffix: "Seq_Int"
               implTypeName.startsWith("${prefix}<")               // Generic syntax: "Seq<Int>"
    }

    /**
     * Wrap a call's value type in `SuspendedType` if the callee's effect row
     * is non-empty / performed. Returns `(callType, declaredEffects,
     * declaredEffectInstances)`.
     *
     * Shared by ability method calls, inherent method calls, and regular
     * function calls — same logic in three places previously.
     */
    internal fun wrapCallResultWithEffects(
        valueType: MezzeType,
        effectRow: MezzeType.EffectRow?,
    ): Triple<MezzeType, Set<String>, Set<MezzeType.EffectInstance>> {
        if (effectRow == null) return Triple(valueType, emptySet(), emptySet())
        val hasUnhandled = effectRow.effects.isNotEmpty() ||
            effectRow.effectInstances.isNotEmpty() ||
            effectRow.rowVar != null
        val callType: MezzeType = if (hasUnhandled || effectRow.performed) {
            MezzeType.SuspendedType(valueType = valueType, effects = effectRow)
        } else {
            valueType
        }
        return Triple(callType, effectRow.effects, effectRow.effectInstances)
    }

    /**
     * Remove handled effects from a type and mark it as performed (`<*>`).
     *
     * For FunctionType: clears the handled effects from the effect row and
     * sets `performed = true`.
     * For SuspendedType: same on the underlying effect row.
     *
     * The performed marker fires for all `perform` blocks — both user-
     * written and synthesized by the do-bind desugar. The do-bind sugar
     * is intentionally semantically identical to the explicit form.
     */
    internal fun removeEffectsFromType(
        type: MezzeType,
        effectsToRemove: Set<String>,
    ): MezzeType {
        return when (type) {
            is MezzeType.FunctionType -> {
                val effects = type.effects
                if (effects == null) {
                    MezzeType.FunctionType(
                        paramType = type.paramType,
                        returnType = type.returnType,
                        effects = MezzeType.EffectRow.simple(emptySet(), null, performed = true),
                        abilityConstraints = type.abilityConstraints
                    )
                } else {
                    val remainingEffects = effects.effects - effectsToRemove
                    MezzeType.FunctionType(
                        paramType = type.paramType,
                        returnType = type.returnType,
                        effects = MezzeType.EffectRow.simple(remainingEffects, effects.rowVar, performed = true),
                        abilityConstraints = type.abilityConstraints
                    )
                }
            }
            is MezzeType.SuspendedType -> {
                val remainingEffects = type.effects.effects - effectsToRemove
                val remainingInstances = type.effects.effectInstances.filter { it.name !in effectsToRemove }.toSet()
                if (remainingEffects.isEmpty() && remainingInstances.isEmpty()) {
                    type.valueType
                } else {
                    MezzeType.SuspendedType(
                        valueType = type.valueType,
                        effects = MezzeType.EffectRow(
                            effects = remainingEffects,
                            effectInstances = remainingInstances,
                            rowVar = type.effects.rowVar,
                            performed = true
                        )
                    )
                }
            }
            else -> type
        }
    }


    // ========================================================================

    /** Convert ParsedTypeAnnotation to MezzeType for field type resolution.
     *  Delegates to [convertParsedTypeAnnotation] — the two paths were identical
     *  except for collection-type sugar, which is semantically equivalent. */
    internal fun convertParsedFieldType(annotation: ParsedTypeAnnotation): MezzeType =
        convertParsedTypeAnnotation(annotation)

    /**
     * Check if an expression is an access chain on a record literal binding.
     *
     * This recursively checks:
     * - Direct identifier: `data` where `data` is in recordLiteralBindings
     * - Nested access: `data.point` where `data` is in recordLiteralBindings
     * - Deep nested: `data.point.x` etc.
     *
     * Used to determine if field access should be strict (no accumulation).
     */
    internal fun isRecordLiteralAccess(expr: ParsedExpr, context: ConstraintContext): Boolean {
        return when (expr) {
            is ParsedIdentifier -> {
                val key = BindingKey(qualifier = expr.qualifier?.segments?.lastOrNull(), localName = expr.localName).toKeyString()
                key in context.recordLiteralBindings
            }
            is ParsedMemberAccess -> isRecordLiteralAccess(expr.receiver, context)
            else -> false
        }
    }

    /**
     * Find the closest matching name from a set of candidates using Levenshtein distance.
     * Returns null if no close match is found (distance > threshold).
     * Delegates to shared StringDistance utility.
     */
    internal fun findClosestMatch(name: String, candidates: Set<String>, threshold: Int = 3): String? {
        if (candidates.isEmpty()) return null
        val similar = StringDistance.findSimilar(name, candidates.toList(), threshold, maxResults = 1)
        return similar.firstOrNull()
    }

    /**
     * Extract a descriptive name for function arguments.
     *
     * For record literals, returns the first field name or a combined description.
     * For identifiers, returns the identifier name.
     * #312: harvest effects from any closure-typed fields in an argument record.
     *
     * When a function is called whose declared effect row is open (`<Async, ...>`),
     * the row var stands for "whatever effects the closure arguments have". This
     * walks the argument's record type and pulls effect names out of any
     * `FunctionType` field's effect row. The results are added directly to the
     * call's `propagatedEffects` — bypassing the substitution machinery (which
     * leaks badly when row var bindings interact with type var unification).
     *
     * Conservative by design: only walks one level of record fields and only
     * inspects FunctionType's own `.effects.effects` set. Effect propagation
     * through deeper structures or transitive HOFs is intentionally out of scope.
     */
    internal fun collectClosureArgEffects(argType: MezzeType): Set<String> {
        if (argType !is MezzeType.RecordType) return emptySet()
        val effects = mutableSetOf<String>()
        for ((_, fieldType) in argType.fields) {
            val fnType = fieldType as? MezzeType.FunctionType ?: continue
            val effRow = fnType.effects ?: continue
            effects.addAll(effRow.effects)
            for (instance in effRow.effectInstances) effects.add(instance.name)
        }
        return effects
    }

     /**
     * For other expressions, returns null.
     */
    internal fun extractArgumentName(expr: ParsedExpr): String? {
        return when (expr) {
            is ParsedRecordLit -> {
                val fieldNames = expr.entries.mapNotNull { entry ->
                    when (entry) {
                        is ParsedRecordEntry.Field -> entry.name
                        else -> null  // ParsedRecordEntry.Spread doesn't have a name
                    }
                }
                when {
                    fieldNames.isEmpty() -> null
                    fieldNames.size == 1 -> fieldNames.first()
                    fieldNames.size <= 3 -> "{${fieldNames.joinToString(", ")}}"
                    else -> "{${fieldNames.take(3).joinToString(", ")}, ...}"
                }
            }
            is ParsedIdentifier -> expr.localName
            else -> null
        }
    }

    private val exprConversionConfig = com.mezze.language.typechecker.util.TypeAnnotationConverter.ConversionConfig(
        resolveAbilityHash = { com.mezze.language.typechecker.WellKnownHashConstants.resolveAbilityHash(it) },
        validateEffects = { ContextBuilder.validateHermeticEffects(it.allEffectNames) },
        resolveCollectionTypes = true,
        honorOpenRowVars = true,
        inlineVariantNaming = true,
    )

    internal fun convertParsedTypeAnnotation(annotation: ParsedTypeAnnotation): MezzeType {
        return com.mezze.language.typechecker.util.TypeAnnotationConverter.convertWith(annotation, exprConversionConfig)
    }
}
