package com.mezze.language.typechecker.derive

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.OperatorMapping
import com.mezze.language.typechecker.codec.mezzeTypeToParsedAnnotation
import com.mezze.language.parsedast.Loc
import com.mezze.language.parsedast.ParsedBinaryOp
import com.mezze.language.parsedast.ParsedConstructorPattern
import com.mezze.language.parsedast.ParsedExpr
import com.mezze.language.parsedast.ParsedFnAbilityConstraint
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
