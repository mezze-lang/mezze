package com.mezze.language.typechecker.derive

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.OperatorMapping
import com.mezze.language.ast.PrimitiveTypes
import com.mezze.language.typechecker.codec.mezzeTypeToParsedAnnotation
import com.mezze.language.parsedast.Loc
import com.mezze.language.parsedast.ParsedBinaryOp
import com.mezze.language.parsedast.ParsedConstructorPattern
import com.mezze.language.parsedast.ParsedDo
import com.mezze.language.parsedast.ParsedExpr
import com.mezze.language.parsedast.ParsedIntLit
import com.mezze.language.parsedast.ParsedNatLit
import com.mezze.language.parsedast.ParsedStatement
import com.mezze.language.parsedast.ParsedFnAbilityConstraint
import com.mezze.language.parsedast.ParsedFunctionCall
import com.mezze.language.parsedast.ParsedRecordEntry
import com.mezze.language.parsedast.ParsedRecordLit
import com.mezze.language.parsedast.ParsedIdentifier
import com.mezze.language.parsedast.ParsedIf
import com.mezze.language.parsedast.ParsedImplAbilityDecl
import com.mezze.language.parsedast.ParsedImplOp
import com.mezze.language.parsedast.ParsedImplTarget
import com.mezze.language.parsedast.ParsedLambda
import com.mezze.language.parsedast.ParsedLambdaParams
import com.mezze.language.parsedast.ParsedMatch
import com.mezze.language.parsedast.ParsedMatchCase
import com.mezze.language.parsedast.ParsedMemberAccess
import com.mezze.language.parsedast.ParsedNamedType
import com.mezze.language.parsedast.ParsedNewtypeDef
import com.mezze.language.parsedast.ParsedPattern
import com.mezze.language.parsedast.ParsedQualifiedRef
import com.mezze.language.parsedast.ParsedRecordDef
import com.mezze.language.parsedast.ParsedRecordPattern
import com.mezze.language.parsedast.ParsedRecordPatternEntry
import com.mezze.language.parsedast.ParsedRecordType
import com.mezze.language.parsedast.ParsedRecordTypeEntry
import com.mezze.language.parsedast.ParsedTypeAliasDef
import com.mezze.language.parsedast.ParsedTypeAnnotation
import com.mezze.language.parsedast.ParsedTypeApp
import com.mezze.language.parsedast.ParsedTypeDecl
import com.mezze.language.parsedast.ParsedVariablePattern
import com.mezze.language.parsedast.ParsedVariantConstruct
import com.mezze.language.parsedast.ParsedVariantCtorDef
import com.mezze.language.parsedast.ParsedVariantDef
import com.mezze.language.parsedast.ParsedWildcardPattern

/**
 * The compiler's `#[derive]`: ability instances a record / variant gets for free,
 * written out as ordinary `impl` declarations so every downstream stage (dict
 * passing, monomorphization, container impls needing an element dict, importers)
 * sees one real instance instead of a structural fallback that ignores a field's
 * own impl.
 *
 * `Eq` for `type R 'a = { x: 'a, p: P }` becomes
 *
 *     impl Eq for R 'a where {
 *       eq  = { it, r } -> it.x == r.x && it.p == r.p,
 *       neq = { it, r } -> if it.x == r.x && it.p == r.p then False else True,
 *     }
 *
 * and for a variant a constructor match on `it` nested in one on `r`, payload
 * fields compared pairwise, a wildcard `False` arm when constructors differ. Each
 * component compare is a source-level `==`, so it dispatches the component's ONE
 * instance — derived or declared (a newtype's hand-written `Eq`) — and a type
 * parameter's `Eq 'a` obligation is harvested from the body like any impl's.
 *
 * Whether a type derives at all (every component has `Eq`) is the caller's call
 * (`AbilityChecker.canDeriveAbility`); this only writes the instance.
 */
object DerivedImplGenerator {
    /** Every generated node gets its OWN Loc: the solver's side tables (match-case
     *  binding types, scrutinee types, call-site rewrites) are keyed by Loc, so a
     *  shared sentinel would make every case / call overwrite the last. The line
     *  is a counter — meaningless as a position, unique as a key. */
    private val locCounter = java.util.concurrent.atomic.AtomicInteger()
    private fun loc(): Loc = locCounter.incrementAndGet().let { Loc("<derived>", it, 0, it, 0) }

    private const val EQ_ABILITY = "Eq"
    private const val LEFT = "it"
    private const val RIGHT = "r"

    /** `impl Eq for <decl>`, or null when the shape has no structural `Eq`
     *  (newtype, alias, open record / variant). */
    fun eqImpl(decl: ParsedTypeDecl): ParsedImplAbilityDecl? {
        val eqBody: () -> ParsedExpr = when (val def = decl.definition) {
            is ParsedRecordDef -> if (def.isOpen) return null else ({ recordEq(def.fields.map { it.name }) })
            is ParsedVariantDef -> if (def.isOpen) return null else ({ variantEq(def) })
            is ParsedNewtypeDef -> return null
            is ParsedTypeAliasDef -> return null
        }
        // Type params are spelled `'a` in source decls and bare `a` in the
        // synthetic builtin variants (Option/Result); the impl target and its
        // implicit quantifiers use the source spelling, as the parser would.
        val params = decl.typeParams.map { if (it.startsWith("'")) it else "'$it" }
        val target: ParsedTypeAnnotation =
            if (params.isEmpty()) ParsedNamedType(decl.name, loc())
            else ParsedTypeApp(decl.name, params.map { ParsedNamedType(it, loc()) }, loc())
        return eqImplDecl(target, params, eqBody)
    }

    /** `impl Eq for { f1: T1, … }` for an ANONYMOUS (concrete, closed) record shape —
     *  the instance the comparing module synthesizes when the shape holds a newtype. */
    fun eqImplForShape(shape: MezzeType.RecordType): ParsedImplAbilityDecl =
        eqImplDecl(mezzeTypeToParsedAnnotation(shape, loc()), emptyList()) { recordEq(shape.fields.keys.sorted()) }

    /**
     * The native comparison behind each primitive's `Eq`.
     *
     * A primitive has no fields to compare, so its derived instance bottoms out in
     * the same native the hand-written stdlib impls called — these entries ARE
     * those bodies, moved from seven `.mz` files into the one place that decides
     * what `Eq` means. The argument names differ per native because the natives
     * were written independently; they are part of each native's signature, not a
     * choice this makes.
     *
     * `Nat` is deliberately absent: it is a newtype, so it derives nothing and
     * writes its own `Eq`, which is exactly the rule.
     */
    private data class PrimitiveEq(val native: String, val neqNative: String?, val lhs: String, val rhs: String)

    private val PRIMITIVE_EQ: Map<String, PrimitiveEq> = mapOf(
        PrimitiveTypes.INT to PrimitiveEq("__native_int_eq", "__native_int_neq", "l", "r"),
        PrimitiveTypes.FLOAT to PrimitiveEq("__native_float_eq", "__native_float_neq", "l", "r"),
        PrimitiveTypes.STR to PrimitiveEq("__native_string_eq", "__native_string_neq", "l", "r"),
        PrimitiveTypes.BOOL to PrimitiveEq("__native_bool_eq", "__native_bool_neq", "l", "r"),
        PrimitiveTypes.CHAR to PrimitiveEq("__native_char_eq", null, "a", "b"),
        PrimitiveTypes.FRAC to PrimitiveEq("__native_frac_equals", null, "l", "r"),
        PrimitiveTypes.BYTES to PrimitiveEq("__native_bytes_equals", null, "b1", "b2"),
    )

    /** Every primitive that derives `Eq`, for the caller that generates them. */
    val primitiveEqNames: Set<String> get() = PRIMITIVE_EQ.keys

    private const val HASH_ABILITY = "Hash"
    private const val STRUCTURAL_HASH_NATIVE = "__native_structural_hash"

    /**
     * `impl Hash for Int`, and its six siblings — the partner of [primitiveEqImpl].
     *
     * `Eq` and `Hash` are one decision: a hash container buckets by `hash` and then
     * compares by `eq`, so a type whose equality is derived must have the hash that
     * AGREES with it. Deriving one and leaving the other hand-written is the exact
     * pairing the coherence rule rejects everywhere else, so the primitives cannot
     * be the place it is tolerated.
     *
     * The body is the one the seven `.mz` impls all had — the structural hash,
     * which is the derived hash by definition — so this moves the decision without
     * changing the answer. It calls the NATIVE rather than the stdlib's
     * `structural_hash` wrapper, because a generated impl must not depend on what
     * its host module happens to import: `std::int` imported that wrapper only for
     * the impl this replaces, so the very next tidy-up of an unused import would
     * have broken generation. The op's written signature is what pins the types the
     * wrapper used to.
     */
    fun primitiveHashImpl(name: String): ParsedImplAbilityDecl? {
        if (name !in PRIMITIVE_EQ) return null
        val receiver = ParsedNamedType(name, loc())
        return ParsedImplAbilityDecl(
            target = ParsedImplTarget.Named(ParsedQualifiedRef(HASH_ABILITY, null, loc())),
            targetTypes = listOf(receiver),
            forallVars = emptyList(),
            operations = listOf(
                ParsedImplOp(
                    name = "hash",
                    // Annotated for the same reason the stdlib annotated it: the body
                    // is a call whose result type the op's own signature has to pin,
                    // or the instance ships with an unbound return type.
                    paramType = ParsedRecordType(
                        listOf(ParsedRecordTypeEntry(LEFT, receiver, loc())),
                        loc = loc(),
                    ),
                    returnType = ParsedNamedType(PrimitiveTypes.INT, loc()),
                    implementation = ParsedLambda(
                        params = ParsedLambdaParams.Untyped(names = listOf(LEFT)),
                        body = ParsedFunctionCall(
                            function = id(STRUCTURAL_HASH_NATIVE),
                            arguments = ParsedRecordLit(
                                listOf(ParsedRecordEntry.Field(LEFT, id(LEFT), loc())),
                                loc(),
                            ),
                            loc = loc(),
                        ),
                        loc = loc(),
                    ),
                    loc = loc(),
                ),
            ),
            loc = loc(),
            derived = true,
        )
    }

    /**
     * `impl Eq for Int`, and its six siblings.
     *
     * Deriving for a primitive is what makes `Eq` uniform: a generic position
     * (`Pair 'a 'b`'s derived `eq` takes an `Eq 'b` dict) needs an INSTANCE to pass,
     * and "the compiler knows how to compare a `Str`" is not one. Without this the
     * derivability predicate and the evidence disagreed — `Str` answered "derives
     * Eq" while nothing generated the instance, so the dict slot for the component
     * was filled from elsewhere and `Pair P Str` compared its `Str` with `P`'s `eq`.
     */
    fun primitiveEqImpl(name: String): ParsedImplAbilityDecl? {
        val prim = PRIMITIVE_EQ[name] ?: return null
        val call = { native: String ->
            ParsedFunctionCall(
                function = id(native),
                arguments = ParsedRecordLit(
                    listOf(
                        ParsedRecordEntry.Field(prim.lhs, id(LEFT), loc()),
                        ParsedRecordEntry.Field(prim.rhs, id(RIGHT), loc()),
                    ),
                    loc(),
                ),
                loc = loc(),
            )
        }
        return ParsedImplAbilityDecl(
            target = ParsedImplTarget.Named(ParsedQualifiedRef(EQ_ABILITY, null, loc())),
            targetTypes = listOf(ParsedNamedType(name, loc())),
            forallVars = emptyList(),
            operations = listOf(
                ParsedImplOp(name = "eq", implementation = lambda(call(prim.native)), loc = loc()),
                ParsedImplOp(
                    name = "neq",
                    // Where a native `neq` exists it is the one the stdlib called;
                    // where it does not, negating `eq` is the same answer.
                    implementation = lambda(
                        prim.neqNative?.let { call(it) }
                            ?: ParsedIf(call(prim.native), bool(false), bool(true), loc())
                    ),
                    loc = loc(),
                ),
            ),
            loc = loc(),
            derived = true,
        )
    }

    private fun eqImplDecl(target: ParsedTypeAnnotation, forallVars: List<String>, eqBody: () -> ParsedExpr): ParsedImplAbilityDecl {
        // `with Eq 'a` for every type param, written EXPLICITLY on each op: a
        // precondition the validator merely infers from the body stays in the
        // declaring module's solver context, while an explicit one travels with
        // the impl to every importer — so `Some n == Some n` for an `n` with no
        // Eq is rejected at the use site, as `Option T < Option T` already is.
        val preconditions = forallVars.map { v ->
            ParsedFnAbilityConstraint(abilityName = EQ_ABILITY, typeArgs = listOf(ParsedNamedType(v, loc())), loc = loc())
        }
        return ParsedImplAbilityDecl(
            target = ParsedImplTarget.Named(ParsedQualifiedRef(EQ_ABILITY, null, loc())),
            targetTypes = listOf(target),
            forallVars = forallVars,
            operations = listOf(
                ParsedImplOp(name = "eq", implementation = lambda(eqBody()), abilityConstraints = preconditions, loc = loc()),
                ParsedImplOp(
                    name = "neq",
                    implementation = lambda(ParsedIf(eqBody(), bool(false), bool(true), loc())),
                    abilityConstraints = preconditions,
                    loc = loc(),
                ),
            ),
            loc = loc(),
            derived = true,
        )
    }

    // ---------------------------------------------------------------------
    // Bodies
    // ---------------------------------------------------------------------

    /** `it.f1 == r.f1 && …`; the empty record is always equal. */
    private fun recordEq(fieldNames: List<String>): ParsedExpr =
        conjunction(fieldNames.map { field -> eq(member(id(LEFT), field), member(id(RIGHT), field)) })

    /**
     * ```
     * match it is
     *   C { f = __l_f, … } -> match r is
     *     C { f = __r_f, … } -> __l_f == __r_f && …
     *     _ -> False
     *   D -> match r is
     *     D -> True
     *     _ -> False
     * ```
     * The `_` arm is omitted for a single-constructor variant (nothing else to
     * match, and a redundant arm would be flagged).
     */
    private fun variantEq(def: ParsedVariantDef): ParsedExpr {
        val multi = def.constructors.size > 1
        val outer = def.constructors.map { ctor ->
            val (leftPat, leftNames) = ctorPattern(ctor, "__l")
            val (rightPat, rightNames) = ctorPattern(ctor, "__r")
            val sameCtor = conjunction(leftNames.zip(rightNames).map { (l, r) -> eq(id(l), id(r)) })
            val innerCases = buildList {
                add(ParsedMatchCase(patterns = listOf(rightPat), body = sameCtor, loc = loc()))
                if (multi) add(ParsedMatchCase(patterns = listOf(ParsedWildcardPattern(loc())), body = bool(false), loc = loc()))
            }
            ParsedMatchCase(patterns = listOf(leftPat), body = ParsedMatch(id(RIGHT), innerCases, loc()), loc = loc())
        }
        return ParsedMatch(id(LEFT), outer, loc())
    }

    /**
     * Constructor pattern binding every payload component to `<prefix>_<field>`,
     * plus the bound names in field order. A record payload is destructured field
     * by field (the compiler's variant-pattern lowering handles record-shaped
     * payloads); any other payload is bound whole as `<prefix>_payload`.
     */
    private fun ctorPattern(ctor: ParsedVariantCtorDef, prefix: String): Pair<ParsedPattern, List<String>> {
        val payload = ctor.dataType ?: return ParsedConstructorPattern(ctor.name, null, loc()) to emptyList()
        if (payload is ParsedRecordType) {
            val names = payload.entries.map { "${prefix}_${ctor.name}_${it.name}" }
            val entries = payload.entries.zip(names).map { (entry, bound) ->
                ParsedRecordPatternEntry(entry.name, ParsedVariablePattern(bound, loc()), loc())
            }
            return ParsedConstructorPattern(ctor.name, ParsedRecordPattern(entries, isOpen = false, loc = loc()), loc()) to names
        }
        val whole = "${prefix}_${ctor.name}_payload"
        return ParsedConstructorPattern(ctor.name, ParsedVariablePattern(whole, loc()), loc()) to listOf(whole)
    }

    // ---------------------------------------------------------------------
    // Arbitrary
    // ---------------------------------------------------------------------

    private const val ARBITRARY_ABILITY = "Arbitrary"
    private const val SAMPLE_EFFECT = "Sample"

    /**
     * `impl Arbitrary for <decl>`, or null when the shape has no structural one.
     *
     * Null for a NEWTYPE, and that is the rule rather than a limitation: a newtype
     * is opaque, and generation also asks a question the representation cannot
     * answer — `newtype Email = Str` does not want arbitrary strings, `newtype Age
     * = Int` does not want negative ones. The author opts in by writing the impl.
     * (Also null for an alias and an open record/variant, as `Eq` is.)
     *
     * Every component is a source-level `Arbitrary.arbitrary {}`, so it dispatches
     * the component's own instance — derived, or the hand-written one a primitive
     * and a newtype have. The call is receiver-less, so its type comes from the
     * position it sits in; that only works because an impl op's body is now checked
     * against the op's declared return type (S-1b), which is what gives a field its
     * expected type.
     */
    fun arbitraryImpl(decl: ParsedTypeDecl): ParsedImplAbilityDecl? {
        val body: ParsedExpr = when (val def = decl.definition) {
            is ParsedRecordDef -> if (def.isOpen) return null else recordArbitrary(def.fields.map { it.name })
            is ParsedVariantDef ->
                if (def.isOpen) return null else variantArbitrary(def, decl.name) ?: return null
            is ParsedNewtypeDef -> return null
            is ParsedTypeAliasDef -> return null
        }
        val params = decl.typeParams.map { if (it.startsWith("'")) it else "'$it" }
        val target: ParsedTypeAnnotation =
            if (params.isEmpty()) ParsedNamedType(decl.name, loc())
            else ParsedTypeApp(decl.name, params.map { ParsedNamedType(it, loc()) }, loc())

        // `with Arbitrary 'a` per type param, written explicitly for the reason the
        // Eq path documents: an inferred precondition stays in the declaring
        // module's solver, an explicit one travels to every importer.
        val preconditions = params.map { v ->
            ParsedFnAbilityConstraint(
                abilityName = ARBITRARY_ABILITY,
                typeArgs = listOf(ParsedNamedType(v, loc())),
                loc = loc(),
            )
        }
        return ParsedImplAbilityDecl(
            target = ParsedImplTarget.Named(ParsedQualifiedRef(ARBITRARY_ABILITY, null, loc())),
            targetTypes = listOf(target),
            forallVars = params,
            operations = listOf(
                ParsedImplOp(
                    name = "arbitrary",
                    implementation = ParsedLambda(
                        params = ParsedLambdaParams.Untyped(names = emptyList()),
                        body = body,
                        loc = loc(),
                    ),
                    abilityConstraints = preconditions,
                    loc = loc(),
                ),
            ),
            loc = loc(),
            derived = true,
        )
    }

    /** `{ f1 = Arbitrary.arbitrary {}, … }`; the empty record generates as itself. */
    private fun recordArbitrary(fieldNames: List<String>): ParsedExpr =
        ParsedRecordLit(
            fieldNames.map { name -> ParsedRecordEntry.Field(name, arbitraryCall(), loc()) },
            loc(),
        )

    /**
     * Pick a constructor, then generate its payload — or null when the shape has no
     * derivable choice.
     *
     * Null for a RECURSIVE variant. Bounding the recursion needs the `Sample` size
     * budget, and a generated body cannot name `Sample`: it is an effect, effects are
     * not built-in and the prelude deliberately does not propagate them, so the
     * generated impl would depend on whether its host module happened to import one.
     * The `Hash` generator hit the same wall and answered it by calling a native
     * instead. Rather than emit a body that only compiles in some modules, a
     * recursive variant does not derive and its author writes the impl that says how
     * to stop — the same opt-in a newtype gets, for a related reason.
     */
    private fun variantArbitrary(def: ParsedVariantDef, typeName: String): ParsedExpr? {
        if (def.constructors.any { isRecursiveCtor(it, typeName) }) return null
        return pickAmong(def.constructors)
    }

    /**
     * ```
     * do
     *   let __arb_pick = Arbitrary.arbitrary {}
     *   if __arb_pick < -33 then C0 { … }
     *   else if __arb_pick < 34 then C1 { … }
     *   else C2 { … }
     * ```
     *
     * ## Everything a generated body mentions must be nameable EVERYWHERE
     *
     * This impl is written into whatever module declares the type, so it may only
     * use what is in scope with no import at all. Two earlier attempts failed on
     * exactly that, both inside `std::collections::iter` — a module with no reason to
     * know sampling exists:
     *
     *  - `Sample.int {…}` — "'Sample' is not defined". Effects are not built-in, and
     *    the prelude deliberately does not propagate them.
     *  - a draw at `Nat`, chosen with `%` — "Cannot use '==' with Nat". `Nat` is a
     *    NEWTYPE, so its equality is a real impl that must be imported.
     *
     * What survives is the primitive path: `<` on `Int` lowers to an inline
     * comparison node, so it needs no impl in scope, and an `Int` literal is syntax.
     * `Arbitrary` itself is nameable because it is a built-in ability. So the draw is
     * an `Int`, pinned by the literals it is compared against, and the choice is made
     * by RANGE rather than by modulus — which also sidesteps `Int`'s generator
     * spreading across zero, where a negative modulus would leave the early arms
     * unreachable and dump everything into the final `else`.
     *
     * The thresholds split [[INT_SPREAD_LO], [INT_SPREAD_HI]] — the range
     * `impl Arbitrary for Int` draws from — into [ctors] roughly equal parts. That is
     * a coupling to the stdlib impl and is only about DISTRIBUTION: a wider range
     * keeps every arm reachable, and a narrower one would skew rather than break.
     *
     * The draw is bound ONCE. A fresh draw per arm would consume a new number at
     * every comparison and skew hard toward the later constructors.
     */
    private fun pickAmong(ctors: List<ParsedVariantCtorDef>): ParsedExpr {
        if (ctors.size == 1) return construct(ctors.first())
        val name = "__arb_pick"
        val span = INT_SPREAD_HI - INT_SPREAD_LO + 1
        fun threshold(i: Int): Long = INT_SPREAD_LO + span * (i + 1) / ctors.size

        var chain: ParsedExpr = construct(ctors.last())
        for (i in ctors.size - 2 downTo 0) {
            chain = ParsedIf(
                ParsedBinaryOp(
                    OperatorMapping.parse("<"),
                    id(name),
                    ParsedIntLit(threshold(i), loc()),
                    loc(),
                ),
                construct(ctors[i]),
                chain,
                loc(),
            )
        }
        return ParsedDo(
            statements = listOf(
                ParsedStatement.Let(
                    pattern = ParsedVariablePattern(name, loc()),
                    typeAnnotation = null,
                    value = arbitraryCall(),
                    loc = loc(),
                ),
            ),
            result = chain,
            loc = loc(),
        )
    }

    /** The range `impl Arbitrary for Int` draws from. See [pickAmong]. */
    private const val INT_SPREAD_LO = -100L
    private const val INT_SPREAD_HI = 100L

    /** `C`, `C { f = Arbitrary.arbitrary {} }`, or `C <arbitrary>` by payload shape. */
    private fun construct(ctor: ParsedVariantCtorDef): ParsedExpr {
        val payload = ctor.dataType ?: return ParsedVariantConstruct(ctor.name, null, loc())
        val value: ParsedExpr = if (payload is ParsedRecordType) {
            ParsedRecordLit(
                payload.entries.map { ParsedRecordEntry.Field(it.name, arbitraryCall(), loc()) },
                loc(),
            )
        } else {
            arbitraryCall()
        }
        return ParsedVariantConstruct(ctor.name, value, loc())
    }

    /**
     * Does this constructor mention [typeName], at any depth?
     *
     * Textual on the ANNOTATION tree rather than on a resolved type, because
     * generation runs before types are resolved. That makes it conservative in the
     * safe direction: a same-named type from elsewhere would be treated as
     * recursive, costing a budget guard that is merely unnecessary — where missing
     * a real recursion costs non-termination.
     */
    private fun isRecursiveCtor(ctor: ParsedVariantCtorDef, typeName: String): Boolean =
        ctor.dataType?.let { mentionsType(it, typeName) } ?: false

    private fun mentionsType(annotation: ParsedTypeAnnotation, typeName: String): Boolean = when (annotation) {
        is ParsedNamedType -> annotation.name == typeName
        is ParsedTypeApp -> annotation.constructor == typeName ||
            annotation.args.any { mentionsType(it, typeName) }
        is ParsedRecordType -> annotation.entries.any { mentionsType(it.type, typeName) }
        else -> false
    }

    private fun arbitraryCall(): ParsedExpr = ParsedFunctionCall(
        member(id(ARBITRARY_ABILITY), "arbitrary"),
        ParsedRecordLit(emptyList(), loc()),
        loc(),
    )

    // ---------------------------------------------------------------------
    // AST helpers
    // ---------------------------------------------------------------------

    private fun conjunction(terms: List<ParsedExpr>): ParsedExpr =
        terms.reduceOrNull { acc, t -> ParsedBinaryOp(OperatorMapping.parse("&&"), acc, t, loc()) } ?: bool(true)

    private fun eq(l: ParsedExpr, r: ParsedExpr): ParsedExpr =
        ParsedBinaryOp(OperatorMapping.parse("=="), l, r, loc())

    private fun lambda(body: ParsedExpr): ParsedLambda =
        ParsedLambda(params = ParsedLambdaParams.Untyped(names = listOf(LEFT, RIGHT)), body = body, loc = loc())

    private fun member(receiver: ParsedExpr, name: String): ParsedMemberAccess =
        ParsedMemberAccess(receiver = receiver, member = name, loc = loc())

    private fun id(name: String): ParsedIdentifier =
        ParsedIdentifier(localName = name, qualifier = null, loc = loc())

    private fun bool(value: Boolean): ParsedVariantConstruct =
        ParsedVariantConstruct(constructor = if (value) "True" else "False", value = null, loc = loc())
}
