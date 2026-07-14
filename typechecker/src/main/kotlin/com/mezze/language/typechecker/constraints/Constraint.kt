package com.mezze.language.typechecker.constraints

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.ModulePath
import com.mezze.language.parsedast.Loc
import com.mezze.language.ast.TypeVar
import com.mezze.language.parsedast.ParsedPerform
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.dependencies.DefinitionRef
import com.mezze.language.typedast.AbilityImplRef
import com.mezze.language.typedast.Hash
import com.mezze.language.parsedast.*


sealed class ErrorContext {
    data class Simple(val message: String) : ErrorContext()

    data class FunctionArgument(
        val functionName: String?,
        val argumentName: String?,
        val parameterIndex: Int = 0     // 0-indexed position
    ) : ErrorContext()

    data class TypeAnnotation(
        val bindingName: String,
        val isReturnType: Boolean = false
    ) : ErrorContext()

    data class FieldAccess(
        val fieldName: String,
        val recordExpression: String? = null
    ) : ErrorContext()

    data class PatternMatch(
        val patternDescription: String,
        val constructorName: String? = null
    ) : ErrorContext()

    data class BinaryOperation(
        val operator: String,
        val isLeftOperand: Boolean
    ) : ErrorContext()

    data class SeqElement(val elementIndex: Int) : ErrorContext()

    data class ReturnType(val functionName: String?) : ErrorContext()

    data class StringInterpolation(val expressionIndex: Int) : ErrorContext()
}

sealed interface Constraint {
    val loc: Loc?
    val context: String?
    fun mapTypes(f: (MezzeType) -> MezzeType): Constraint

    data class Equality(
        val left: MezzeType,
        val right: MezzeType,
        override val context: String? = null,
        override val loc: Loc? = null,
        val isMatchCaseContext: Boolean = false,
        val errorContext: ErrorContext? = null
    ) : Constraint {
        override fun mapTypes(f: (MezzeType) -> MezzeType) = copy(left = f(left), right = f(right))
    }

    data class AbilityRequired(
        val typeVar: TypeVar,
        val abilityName: String,
        val abilityHash: Hash? = null,
        val typeArgs: List<MezzeType> = emptyList(),
        override val context: String? = null,
        override val loc: Loc? = null
    ) : Constraint {
        override fun mapTypes(f: (MezzeType) -> MezzeType) = copy(typeArgs = typeArgs.map(f))
    }

    data class AssociatedTypeResolution(
        val abilityName: String,
        val primaryTypeVar: TypeVar,
        val associatedTypeVars: Map<String, TypeVar>,
        override val context: String? = null,
        override val loc: Loc? = null
    ) : Constraint {
        override fun mapTypes(f: (MezzeType) -> MezzeType) = this
    }

    data class Subsumes(
        val general: MezzeType,
        val specific: MezzeType,
        override val context: String? = null,
        override val loc: Loc? = null
    ) : Constraint {
        override fun mapTypes(f: (MezzeType) -> MezzeType) = copy(general = f(general), specific = f(specific))
    }

    data class HasField(
        val recordType: MezzeType,
        val fieldName: String,
        val fieldType: MezzeType,
        override val context: String? = null,
        override val loc: Loc? = null,
        val strictFieldCheck: Boolean = false
    ) : Constraint {
        override fun mapTypes(f: (MezzeType) -> MezzeType) = copy(recordType = f(recordType), fieldType = f(fieldType))
    }

    data class HasConstructor(
        val variantType: MezzeType,
        val constructorName: String,
        val payloadType: MezzeType?,
        override val context: String? = null,
        override val loc: Loc? = null,
        val isPatternContext: Boolean = false
    ) : Constraint {
        override fun mapTypes(f: (MezzeType) -> MezzeType) = copy(variantType = f(variantType), payloadType = payloadType?.let(f))
    }

    data class MustBeOpen(
        val type: MezzeType,
        val bindingName: String? = null,
        override val context: String? = null,
        override val loc: Loc? = null
    ) : Constraint {
        override fun mapTypes(f: (MezzeType) -> MezzeType) = copy(type = f(type))
    }

    data class RecordPatternExhaustive(
        val recordType: MezzeType,
        val patternFields: Set<String>,
        override val context: String? = null,
        override val loc: Loc? = null
    ) : Constraint {
        override fun mapTypes(f: (MezzeType) -> MezzeType) = copy(recordType = f(recordType))
    }
}

data class ConstraintResult(
    val type: MezzeType,
    val constraints: List<Constraint>,
    val effects: Set<String> = emptySet(),
    val effectInstances: Set<MezzeType.EffectInstance> = emptySet(),
    val performed: Boolean = false,
    val abilityConstraints: List<Constraint.AbilityRequired> = emptyList(),
    val associatedTypeConstraints: List<Constraint.AssociatedTypeResolution> = emptyList(),
    val undefinedVariables: List<UndefinedVariable> = emptyList(),
    val duplicateBindings: List<DuplicateBinding> = emptyList(),
    val tailCalls: Set<Loc> = emptySet(),
    val usedVariables: Set<String> = emptySet(),
    val unusedVariables: List<UnusedVariable> = emptyList(),
    val patternWarnings: List<String> = emptyList(),
    val referencedBindings: Set<DefinitionRef.ByName> = emptySet(),
    val methodCallResolutions: Map<Pair<Loc, String>, MethodCallInfo> = emptyMap(),
    val callReturnTypes: Map<Loc, MezzeType> = emptyMap(),
    val expectedTypeAt: Map<Loc, MezzeType> = emptyMap(),
    // #195 mono-fix #1: per-match-case pattern bindings, keyed by the case's
    // Loc. The values are the constraint generator's fresh TyVars from
    // PatternConstraintResult — the SAME TyVars that HasField etc. unified
    // with concrete types. TypedAstBuilder applies the final substitution
    // and uses these as PatternBinding.type + identifierContext entries for
    // guard/body emission. Without this, TypedAstBuilder constructed fresh
    // placeholder TyVars (e.g. `TypeVar(name="w", id=0)`) that had no
    // identity relationship to the solver's TyVars (id=N) — substitution
    // silently passed through, and the body kept `w: VarType` in the
    // specialized mono blob, defeating inline op dispatch on the renamed
    // bindings.
    val matchCaseBindings: Map<Loc, Map<String, MezzeType>> = emptyMap(),
) {
    data class MethodCallInfo(
        val abilityName: String?,        // null for inherent methods
        val methodName: String,
        val methodHash: Hash?,
        val receiverFieldName: String,
        val isInherentMethod: Boolean = false,
        val implContentHash: Hash? = null,
        val implStartLine: Int = 0,
        val implStartCol: Int = 0,
        val implSourceFile: String = "",
        val methodFunctionType: MezzeType? = null,
        // #195 mono-fix #2: per-call-site resolved instantiation. When
        // populated, TypedAstBuilder stamps `TypedCall.instantiation` so the
        // monomorphizer's transitive worklist (TypeSubstitution.collectByHashRefs)
        // can enqueue this call's specializations instead of silently
        // dropping every nested generic. typeArgs is in callee's typeParam
        // declaration order; abilityImpls is one entry per ability constraint
        // on the callee's function type, in declaration order. Both default to
        // empty — call sites that don't populate keep null instantiation
        // downstream (no allocation, hash-stable). Field population is wired
        // incrementally — the infrastructure can carry data even when the
        // populating site hasn't been built yet.
        val typeArgs: List<MezzeType> = emptyList(),
        val abilityImpls: List<AbilityImplRef> = emptyList(),
    )
    data class UndefinedVariable(
        val name: String,
        val loc: Loc?,
        val qualifier: ModulePath? = null,
        val isQualified: Boolean = false
    )

    data class DuplicateBinding(
        val name: String,
        val loc: Loc?
    )

    data class UnusedVariable(
        val name: String,
        val loc: Loc?
    )
    companion object {
        fun simple(type: MezzeType): ConstraintResult {
            return ConstraintResult(type, emptyList())
        }
    }
}

data class ConstraintContext(
    val typingContext: TypingContext,
    val expectedType: MezzeType? = null,
    val allowedEffects: Set<String> = emptySet(),
    val currentFunction: String? = null,
    val performerToEffect: Map<String, String> = emptyMap(),
    val performerAddedEffects: Map<String, Set<String>> = emptyMap(),
    val abilityNames: Set<String> = emptySet(),
    val effectNames: Set<String> = emptySet(),
    val effectDeclarations: Map<String, ParsedEffectDecl> = emptyMap(),
    val performContext: ParsedPerform? = null,
    val abilityDeclarations: Map<String, ParsedAbilityDecl> = emptyMap(),
    val recordLiteralBindings: Set<String> = emptySet(),
    val typeDeclarations: Map<String, ParsedTypeDecl> = emptyMap(),
    val currentBindingName: String? = null,

    val enclosingFunctionName: String? = null,
    val inTailPosition: Boolean = false,
    // Empty set means no user-driven preference (#304).
    val annotationAbilityNames: Set<String> = emptySet()
) {
    fun extend(name: String, type: MezzeType): ConstraintContext {
        return copy(typingContext = typingContext.extendMono(name, type))
    }

    fun withExpectedType(type: MezzeType): ConstraintContext {
        return copy(expectedType = type)
    }

    fun clearExpectedType(): ConstraintContext {
        return copy(expectedType = null)
    }

    fun withRecordLiteralBinding(name: String): ConstraintContext {
        return copy(recordLiteralBindings = recordLiteralBindings + name)
    }

    fun inTailPosition(): ConstraintContext {
        return copy(inTailPosition = true)
    }

    fun notInTailPosition(): ConstraintContext {
        return copy(inTailPosition = false)
    }
}
