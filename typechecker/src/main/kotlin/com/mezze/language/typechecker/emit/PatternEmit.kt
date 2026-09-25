package com.mezze.language.typechecker.emit

import com.mezze.language.typechecker.solving.expandTypeApplicationToRecord
import com.mezze.language.typechecker.solving.expandTypeApplicationToVariant
import com.mezze.language.typechecker.solving.substituteTypeParamsByName
import com.mezze.language.typechecker.solving.substituteTypeVars
import com.mezze.language.ast.*
import com.mezze.language.ast.hashing.MezzeHashBuilder
import com.mezze.language.parser.MezzeParser as ParserBridge
import com.mezze.language.parsedast.*
import com.mezze.language.parsedast.Visibility as ParsedVisibility
import com.mezze.language.typedast.*
import com.mezze.language.typedast.TypeScheme as V2TypeScheme
import com.mezze.language.typechecker.constraints.ConstraintResult
import com.mezze.language.typechecker.constraints.ConstraintResult.MethodCallInfo
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.module.ModuleImportContext
import com.mezze.language.ast.solving.Substitution
import com.mezze.language.typechecker.WellKnownHashConstants
import com.mezze.language.typechecker.context.DeclarationRegistry
import com.mezze.language.typechecker.util.WellKnownTypeApp
import com.mezze.language.typechecker.util.receiverTypeName
import com.mezze.language.ast.hashing.HashTags
import com.mezze.language.ast.solving.closeGeneratedRowVars

/*
 * Match-case and pattern emission, moved out of TypedAstBuilder.
 *
 * One subject: how a pattern binds names and what types those names get. The
 * two rules `buildMatchCase` arbitrates between — FILL a binder the solver never
 * mentioned, and OVERRIDE a solver binding that resolved to nothing — are the
 * hardest-won comments in emit, and they belong somewhere findable rather than
 * halfway down a 3,100-line file.
 *
 * `declaredShape` and `patternBindingTypes` come along because they exist to
 * serve those rules.
 *
 * A pure move: no body was edited, only the indentation and the receiver.
 */

internal fun TypedAstBuilder.buildMatchCase(
    case: ParsedMatchCase,
    file: String,
    contextType: MezzeType?,
    scrutineeType: MezzeType? = null,
): TypedCase {
    val patterns = case.patterns.map { buildPattern(it, file) }
    val bindingNames = case.patterns.flatMap { p -> p.extractBindings() }.toMutableList()

    val typedIfLetBindings = mutableListOf<TypedIfLetBinding>()
    for (ifLet in case.ifLetBindings) {
        val ifLetBindingNames = ifLet.pattern.extractBindings()
        bindingNames.addAll(ifLetBindingNames)
        val typedPattern = buildPattern(ifLet.pattern, file)
        val typedExpr = buildExpr(ifLet.expr, file)
        typedIfLetBindings.add(TypedIfLetBinding(typedPattern, typedExpr, ifLetBindingNames, ifLet.loc))
    }

    // #195 mono-fix #1: use the solver's TyVars from PatternConstraintResult
    // (threaded through ConstraintResult.matchCaseBindings) instead of
    // constructing fresh `TypeVar(name)` placeholders. Applying substitution
    // here resolves to concrete types when HasField fired. Without this,
    // the placeholder's `(name, id=0)` identity never matches the solver's
    // `(<fresh-name>, id=N)`, so substitution.apply returns it unchanged.
    val solverBindings = matchCaseBindings[case.loc] ?: emptyMap()
    // The solver's per-case binding types cover bodies it solved as module
    // bindings. An ability-impl op body is validated by ImplValidator instead
    // and leaves no entry here, so fall back to the STRUCTURAL read of the
    // pattern against the scrutinee's declared shape (`Wrap { p = l }` on a
    // `W` scrutinee binds `l : P`). Untyped bindings made every `==` on a
    // payload inside an impl body fall to the structural default.
    // A.2, MEASURED: ungating this (dropping `!typingImplOpBody`) recovers 101
    // of the 125 elaboration misses the census counts — and breaks exactly
    // four codec tests (JsonDecodeExhaustiveTest x3, UserDecodeAbilityTest),
    // the same four A.1 hit. Codec decode bodies depend on these bindings
    // being FRESH VARIABLES; preferring ground types, preferring the declared
    // shape, and a strictly-additive merge were all tried during A.1 and all
    // broke them. The dependency goes away when codec impls are generated
    // into the resolved module like derived impls, so this half of A.2 is
    // blocked on A.3 — not on a better heuristic here.
    // Two distinct uses of the scrutinee's declared shape, with two rules.
    //
    // FILL — supply a binder the solver never mentioned. Only inside an impl op
    // body. Widening it was measured and does not work: the codec decode
    // bodies depend on such binders staying FRESH VARIABLES (a dict slot is
    // keyed on the receiver BEING a variable — per-var slots, C.2 — so a binder
    // that stops being one loses its slot, the call degrades to a name-based
    // field read, and CallIndirect dies on [MezzeRecord, Object[]]).
    // JsonDecodeExhaustiveTest x3 and UserDecodeAbilityTest fail that way for
    // every widening tried: unconditional, "prefer ground types", "prefer the
    // declared shape", strictly-additive merge, and concrete-scrutinee-only.
    val structuralBindings: Map<String, MezzeType> =
        if (scrutineeType == null || !typingImplOpBody) emptyMap()
        else case.patterns.fold(emptyMap()) { acc, p -> acc + patternBindingTypes(p, scrutineeType) }

    // OVERRIDE — replace a solver binding that resolved to nothing. When the
    // scrutinee's type is fully CONCRETE, its pattern determines each binder's
    // type, so a solver entry that is still a bare variable is not a competing
    // answer, it is the absence of one. Preferring it lost real types:
    //
    //     let show = { a: Amt } -> match a is Grams { n } -> n.to_dbg {}
    //
    // typed `n` as a variable even though `Amt`'s payload says `Money`, so the
    // newtype printed as its erased payload (`4`, not `Money.(4)`) — while the
    // same match at top level was correct, because there the solver did resolve
    // it. This never overrides an answer, only a hole, and only where the
    // scrutinee is concrete enough to determine one.
    val determinedBindings: Map<String, MezzeType> =
        if (scrutineeType == null || scrutineeType.holdsTypeVar()) emptyMap()
        else case.patterns.fold(emptyMap()) { acc, p -> acc + patternBindingTypes(p, scrutineeType) }

    val resolvedTypes = bindingNames.associateWith { name ->
        val solverTy = solverBindings[name]?.let { substitution.apply(it) }
        if (solverTy != null) {
            if (solverTy.holdsTypeVar()) determinedBindings[name] ?: solverTy else solverTy
        } else structuralBindings[name] ?: run {
            // A.2 — the compiler does not know this binding's type, and says
            // so by minting a fresh variable. That is indistinguishable from
            // a legitimately polymorphic binding, so nothing downstream can
            // tell the difference: `x < y` on such a binding lowers to a
            // dynamic field read and, for an erased newtype, silently uses
            // the WRAPPED type's ordering (known-bugs.md BUG 11).
            //
            // Counted before it is made an error, so the blast radius is a
            // measurement rather than a guess. See ElaborationMissCensus.
            ElaborationMissCensus.recordMatchBindingMiss(name, case.loc)
            MezzeType.VarType(TypeVar.freshWithName(name))
        }
    }

    val bindings = bindingNames.map { name ->
        PatternBinding(name, resolvedTypes[name]!!, case.loc)
    }

    pushScope(bindingNames.toMutableList(), isLambdaBoundary = false)
    val savedContext = HashMap<String, MezzeType?>()
    for (name in bindingNames) {
        savedContext[name] = identifierContext[name]
        identifierContext[name] = resolvedTypes[name]!!
    }

    val caseGuard = case.guard
    val guard = if (caseGuard != null) {
        buildExpr(caseGuard, file, PrimitiveTypes.BOOL_TYPE)
    } else if (case.ifLetBindings.isNotEmpty()) {
        TypedBoolLit(true, PrimitiveTypes.BOOL_TYPE, case.loc)
    } else {
        null
    }
    val body = buildExpr(case.body, file, contextType)

    for ((name, prev) in savedContext) {
        if (prev != null) identifierContext[name] = prev else identifierContext.remove(name)
    }
    popScope()

    return TypedCase(patterns, bindings, guard, body, case.loc, typedIfLetBindings)
}

/** A declared nominal type in whichever representation it arrived, opened to
 *  its record / variant shape (registry-backed); anything else unchanged. */
internal fun TypedAstBuilder.declaredShape(type: MezzeType): MezzeType {
    val applied = substitution.apply(type)
    if (applied !is MezzeType.TypeApplication) return applied
    // Opening a declared type re-converts its parsed annotations against the
    // MODULE registry, which has no imported effects/abilities — a field typed
    // `{} -> Int <Log>` with `Log` imported throws. That read is only a fallback
    // for shapes the solver left untyped, so an unopenable shape just stays
    // nominal (the solver's own type, when it has one, is used first anyway).
    return try {
        expandTypeApplicationToRecord(applied, registry)
            ?: expandTypeApplicationToVariant(applied, registry)
            ?: applied
    } catch (_: com.mezze.language.typechecker.TypeCheckError) {
        applied
    }
}

/** Binding name → type, read structurally off [pattern] matched against a
 *  value of [type]. Only shapes the type actually exposes are typed; a
 *  binding whose type can't be read this way is simply absent. */
internal fun TypedAstBuilder.patternBindingTypes(pattern: ParsedPattern, type: MezzeType?): Map<String, MezzeType> {
    val shape = type?.let { declaredShape(it) }
    return when (pattern) {
        is ParsedVariablePattern -> if (shape != null) mapOf(pattern.name to shape) else emptyMap()
        is ParsedWildcardPattern -> emptyMap()
        is ParsedLiteralPattern -> emptyMap()
        is ParsedStringInterpPattern -> emptyMap()
        // `B.(x)` binds `x` to what B WRAPS, so recurse into `inner` against
        // the wrapped type. Returning nothing here left `x` untyped, and an
        // untyped receiver makes `x < y` compile to a dynamic field read —
        // `CallIndirect(ReadField(x, "lt"), …)` — against a value that is a
        // raw long at runtime, because a newtype is erased. Inside
        // `impl Ord for B` that silently produced the WRONG ANSWER rather
        // than an error. See known-bugs.md BUG 11.
        // `B.(x)` binds `x` to what B WRAPS, so recurse into `inner` against
        // the wrapped type. This arm used to return nothing, dropping the
        // inner pattern entirely and leaving `x` untyped — the same
        // one-sidedness the seq arm below was hardened against.
        //
        // HONESTLY: this changed no observed behaviour. BUG 11's repro does
        // not reach here (it arrives with `typingImplOpBody = false`, so the
        // structural read is skipped altogether). Kept anyway, unlike other
        // no-op experiments in this effort, because it has no downside — it
        // only ADDS a type where there was none, on a path gated to impl
        // bodies — and dropping a sub-pattern outright is a plain omission
        // rather than a judgement call.
        is ParsedNewtypePattern -> {
            // `B.(x)` binds `x` to what B WRAPS. `declaredShape` opens a
            // newtype into a VARIANT with one constructor — NOT a
            // NewtypeType — so the wrapped type is that constructor's
            // payload. The registry says which of the two payload shapes the
            // expansion produced: a newtype over a RECORD carries that record
            // directly, anything else is wrapped as `{ val: T }`. Reading the
            // declaration rather than guessing from the shape is what keeps
            // `newtype W = { val: Int }` distinguishable from
            // `newtype N = Int`.
            val payload = (shape as? MezzeType.VariantType)?.constructors?.get(pattern.typeName)
            val wrapsRecord = (
                registry.lookupType(pattern.typeName)
                    ?: com.mezze.language.typechecker.ModuleResolver
                        .lookupTypeDeclaration(pattern.typeName)
                )?.let { (it.definition as? ParsedNewtypeDef)?.wrappedType is ParsedRecordType }
            val wrapped = when {
                payload == null -> null
                wrapsRecord == true -> payload
                else -> (payload as? MezzeType.RecordType)?.fields?.get("val")
            }
            patternBindingTypes(pattern.inner, wrapped)
        }
        is ParsedConstructorPattern -> {
            val payloadType = (shape as? MezzeType.VariantType)?.constructors?.get(pattern.constructor)
            pattern.payload?.let { patternBindingTypes(it, payloadType) } ?: emptyMap()
        }
        is ParsedRecordPattern -> {
            val fields = (shape as? MezzeType.RecordType)?.fields
            pattern.entries.fold(emptyMap()) { acc, entry ->
                val fieldType = fields?.get(entry.name)
                val sub = entry.pattern
                acc + when {
                    sub != null -> patternBindingTypes(sub, fieldType)
                    fieldType != null -> mapOf(entry.name to fieldType)
                    else -> emptyMap()
                }
            }
        }
        is ParsedSeqPattern -> {
            // A container has two representations, and this read handled only
            // the dedicated nodes. The name-carried form is the DOMINANT one in
            // what actually flows (5,722 occurrences against 1,546 across the
            // typed stdlib — TypeRepresentationAuditTest), and `declaredShape`
            // above cannot normalize it away: it expands a TypeApplication to a
            // record or a variant, and a container is neither, so a
            // `TypeApplication("Seq", [T])` scrutinee arrives here intact and
            // every binding in the pattern came back with no type — which is
            // what makes `==` inside an impl body fall to the structural
            // default. Same shape as the bug `containerElementType` already
            // carries a comment about. B.1 removes the choice; until then, read
            // both.
            val elementType = when {
                shape is MezzeType.TypeApplication &&
                    shape.constructor in com.mezze.language.ast.BuiltinTypes.sequenceConstructors ->
                    shape.args.firstOrNull()
                else -> null
            }
            val elements = pattern.elements.fold(emptyMap<String, MezzeType>()) { acc, e -> acc + patternBindingTypes(e, elementType) }
            val rest = pattern.restPattern
            if (rest != null && shape != null && elementType != null) elements + (rest to shape) else elements
        }
    }
}

internal fun TypedAstBuilder.buildPattern(pattern: ParsedPattern, file: String): TypedPattern {
    val unknownType = MezzeType.VarType(TypeVar.freshInternal("pat"))
    val loc = pattern.loc
    return when (pattern) {
        is ParsedVariablePattern -> TypedVarPattern(pattern.name, unknownType, loc)
        is ParsedWildcardPattern -> TypedWildcardPattern(unknownType, loc)
        is ParsedConstructorPattern -> {
            if (pattern.constructor == BuiltinTypes.TRUE && pattern.payload == null) {
                TypedLiteralPattern(TypedBoolLit(true, PrimitiveTypes.BOOL_TYPE, loc), PrimitiveTypes.BOOL_TYPE, loc)
            } else if (pattern.constructor == BuiltinTypes.FALSE && pattern.payload == null) {
                TypedLiteralPattern(TypedBoolLit(false, PrimitiveTypes.BOOL_TYPE, loc), PrimitiveTypes.BOOL_TYPE, loc)
            } else {
                val payload = pattern.payload?.let { buildPattern(it, file) }
                TypedVariantPattern(pattern.constructor, payload, unknownType, loc)
            }
        }
        is ParsedRecordPattern -> TypedRecordPattern(
            fields = pattern.entries.map { e ->
                TypedFieldPattern(e.name, e.pattern?.let { buildPattern(it, file) }, e.loc)
            },
            isOpen = pattern.isOpen,
            type = unknownType,
            loc = loc
        )
        is ParsedLiteralPattern -> {
            val value = when (val v = pattern.value) {
                is ParsedLiteralValue.IntValue -> TypedIntLit(v.value, MezzeType.IntType, loc)
                is ParsedLiteralValue.NatValue -> TypedNatLit(v.value, natLiteralType, loc)
                is ParsedLiteralValue.FloatValue -> TypedFloatLit(v.value, MezzeType.FloatType, loc)
                is ParsedLiteralValue.StringValue ->
                    TypedStringLit(listOf(TypedStringPart.Literal(v.value)), MezzeType.StringType, loc)
                is ParsedLiteralValue.BoolValue -> TypedBoolLit(v.value, PrimitiveTypes.BOOL_TYPE, loc)
            }
            TypedLiteralPattern(value, value.type, loc)
        }
        is ParsedSeqPattern -> TypedSeqPattern(
            elements = pattern.elements.map { buildPattern(it, file) },
            rest = pattern.restPattern,
            type = unknownType,
            loc = loc
        )
        is ParsedStringInterpPattern ->
            TypedStringInterpPattern(
                parts = pattern.parts.map { part ->
                    when (part) {
                        is ParsedStringPatternPart.Literal -> TypedStringPatternPart.Literal(part.value)
                        is ParsedStringPatternPart.Capture -> TypedStringPatternPart.Capture(part.variableName)
                    }
                },
                type = MezzeType.StringType,
                loc = loc
            )
        is ParsedNewtypePattern -> {
            // Runtime erasure: newtype destructuring is identity.
            buildPattern(pattern.inner, file)
        }
    }
}
