package com.mezze.language.parsedast

import kotlinx.serialization.Serializable

/**
 * Expressions — all have `loc: Loc`, nothing else.
 *
 * No inferredType. No contentHash. No resolvedAbilities.
 * No resolvedFunctionName. No typeSubstitution. No isTailCall.
 * Just pure syntax.
 */
@Serializable
sealed interface ParsedExpr {
    val loc: Loc
}


/** Integer literal: `42` */
@Serializable
data class ParsedIntLit(
    val value: Long,
    override val loc: Loc
) : ParsedExpr

/** Natural number literal: `42n` */
@Serializable
data class ParsedNatLit(
    val value: Long,
    override val loc: Loc
) : ParsedExpr

/** Float literal: `3.14` */
@Serializable
data class ParsedFloatLit(
    val value: Double,
    override val loc: Loc
) : ParsedExpr

/** Fraction literal: `1//2` */
@Serializable
data class ParsedFracLit(
    val numerator: Long,
    val denominator: Long,
    override val loc: Loc
) : ParsedExpr

/**
 * String literal, always represented as parts.
 *
 * Simple string `"hello"` → parts=[Literal("hello")]
 * Interpolated `"hi $(name)"` → parts=[Literal("hi "), Interpolation(name)]
 */
@Serializable
data class ParsedStringLit(
    val parts: List<ParsedStringPart>,
    override val loc: Loc
) : ParsedExpr

/** Character literal: `'a'` */
@Serializable
data class ParsedCharLit(
    val value: Char,
    override val loc: Loc
) : ParsedExpr


/**
 * Identifier — simple or qualified variable/function reference.
 *
 * - Simple: `x` → localName="x", qualifier=null
 * - Qualified: `int::add` → localName="add", qualifier=["int"]
 * - Native: `__native_int_add` → localName="__native_int_add", qualifier=null
 */
@Serializable
data class ParsedIdentifier(
    val localName: String,
    val qualifier: ParsedModulePath? = null,
    override val loc: Loc
) : ParsedExpr {
    val isQualified: Boolean get() = qualifier != null
}


/**
 * Lambda expression: `{ x, y } -> x + y` or `{ x: Int } -> x + 1`
 *
 * The `->` distinguishes lambdas from record literals.
 */
@Serializable
data class ParsedLambda(
    val params: ParsedLambdaParams,
    val body: ParsedExpr,
    val returnType: ParsedTypeAnnotation? = null,
    val abilityConstraints: List<ParsedFnAbilityConstraint> = emptyList(),
    override val loc: Loc
) : ParsedExpr

/**
 * Function call: `f { x = 1 }`, `f {}`, `add { l = 1, r = 2 }`
 *
 * In Mezze, function arguments are always records.
 */
@Serializable
data class ParsedFunctionCall(
    val function: ParsedExpr,
    val arguments: ParsedExpr,
    override val loc: Loc
) : ParsedExpr


/**
 * Binary operator expression: `x + y`, `a == b`, `p && q`
 *
 * Preserved from source — NOT desugared to method calls.
 * The typechecker desugars to `x.add {r = y}` internally but keeps
 * the original operator for error messages.
 */
@Serializable
data class ParsedBinaryOp(
    val op: String,
    val left: ParsedExpr,
    val right: ParsedExpr,
    override val loc: Loc
) : ParsedExpr

/**
 * Unary operator expression: `-x`, `!flag`
 *
 * Preserved from source — NOT desugared to method calls.
 */
@Serializable
data class ParsedUnaryOp(
    val op: String,
    val operand: ParsedExpr,
    override val loc: Loc
) : ParsedExpr


/** Pipe expression: `value |> f { param = @ }` */
@Serializable
data class ParsedPipe(
    val left: ParsedExpr,
    val right: ParsedExpr,
    override val loc: Loc
) : ParsedExpr

/** Pipe placeholder: `@` — the value being piped */
@Serializable
data class ParsedPipePlaceholder(
    override val loc: Loc
) : ParsedExpr


/**
 * Record literal: `{ x = 1, y }`, `{ ...r, x = 2 }`, `{}`
 */
@Serializable
data class ParsedRecordLit(
    val entries: List<ParsedRecordEntry>,
    override val loc: Loc
) : ParsedExpr

/**
 * Member access: `record.field`, `"hello".length`
 *
 * This covers both field access and method call receivers.
 * `x.add { r = y }` parses as FunctionCall(MemberAccess(x, "add"), {r=y}).
 */
@Serializable
data class ParsedMemberAccess(
    val receiver: ParsedExpr,
    val member: String,
    override val loc: Loc,
    /**
     * Loc of the member-name token ALONE (`.to_str` → the `to_str` span),
     * as opposed to [loc] which spans the whole `receiver.member` expression.
     * The typechecker assigns this narrow loc to the desugared method-name
     * node so hover/goto-def/references on a chained call (`x.a {}.b {}.c {}`)
     * resolve to the specific method under the cursor, not the outermost one.
     * Defaults to [loc] for synthetic member accesses (operators, do-bind
     * desugar) where there is no distinct source token.
     */
    val memberLoc: Loc = loc
) : ParsedExpr


/**
 * Variant construction: `Some { val = 42 }`, `None`, `Red`, `True`, `False`
 *
 * The parser treats any uppercase identifier as a variant constructor.
 * The typechecker resolves what it actually is (variant, ability ref, effect ref, etc.)
 */
@Serializable
data class ParsedVariantConstruct(
    val constructor: String,
    val value: ParsedExpr? = null,
    override val loc: Loc
) : ParsedExpr


/** If expression: `if cond then a else b` */
@Serializable
data class ParsedIf(
    val condition: ParsedExpr,
    val thenBranch: ParsedExpr,
    val elseBranch: ParsedExpr,
    override val loc: Loc
) : ParsedExpr

/**
 * Match expression: `match expr is  pattern -> body, ...`
 */
@Serializable
data class ParsedMatch(
    val scrutinee: ParsedExpr,
    val cases: List<ParsedMatchCase>,
    override val loc: Loc
) : ParsedExpr

/**
 * A single match case.
 *
 * Supports or-patterns (`Some x | None -> ...`) and guards (`x if x > 0 -> ...`).
 */
@Serializable
data class ParsedMatchCase(
    val patterns: List<ParsedPattern>,
    val guard: ParsedExpr? = null,
    val ifLetBindings: List<ParsedIfLetGuard> = emptyList(),
    val body: ParsedExpr,
    val loc: Loc,
    // Comments preserved for the formatter — same semantics as ParsedStatement.
    // Hash-excluded (match cases hash by pattern/guard/body only).
    val leadingComments: List<ParsedComment> = emptyList(),
    val trailingComment: ParsedComment? = null,
    val docComment: String? = null,
    val metadata: ParsedMetadata? = null,
)

/**
 * If-let guard in a match case: `if let Some {val = x} = expr`
 */
@Serializable
data class ParsedIfLetGuard(
    val pattern: ParsedPattern,
    val expr: ParsedExpr,
    val loc: Loc
)


/**
 * Do block: `do  let x = 1  x + 1`
 *
 * Statements are evaluated in order. The result expression is returned.
 */
@Serializable
data class ParsedDo(
    val statements: List<ParsedStatement>,
    val result: ParsedExpr,
    override val loc: Loc
) : ParsedExpr

@Serializable
sealed interface ParsedStatement {
    val loc: Loc

    /**
     * Comments preserved for the formatter (round-trip), mirroring the fields
     * on [ParsedDecl]. `leadingComments` = regular `#` lines above the
     * statement; `trailingComment` = a `#` on the same line after it;
     * `docComment` = merged `##` lines; `metadata` = a `#{…}` block. None of
     * these affect the content hash (see ParsedDeclHasher — statements are
     * hashed by structure only). In-block `##` doc comments are preserved as
     * text but do NOT become an LSP doc (there's no decl to attach to).
     */
    val leadingComments: List<ParsedComment>
    val trailingComment: ParsedComment?
    val docComment: String?
    val metadata: ParsedMetadata?

    @Serializable
    data class Let(
        val pattern: ParsedPattern,
        val typeAnnotation: ParsedTypeAnnotation? = null,
        val value: ParsedExpr,
        override val loc: Loc,
        override val leadingComments: List<ParsedComment> = emptyList(),
        override val trailingComment: ParsedComment? = null,
        override val docComment: String? = null,
        override val metadata: ParsedMetadata? = null,
    ) : ParsedStatement

    @Serializable
    data class Expr(
        val expr: ParsedExpr,
        override val loc: Loc,
        override val leadingComments: List<ParsedComment> = emptyList(),
        override val trailingComment: ParsedComment? = null,
        override val docComment: String? = null,
        override val metadata: ParsedMetadata? = null,
    ) : ParsedStatement

    /**
     * Do-block bind statement: `pat <- expr`.
     *
     * Sugar for the Tryable mechanism. The typechecker's TypedAstBuilder
     * rewrites a do-block containing any Bind statement into:
     *   - each Bind becomes `let pat = expr.try {}`
     *   - the do-block's result expression is wrapped in `Ok {val = ...}`
     *   - the whole block is wrapped in a synthetic `perform ExceptionToResult`
     *
     * Pattern must be irrefutable (same constraint as Let). The pattern
     * binds to the unwrapped success value (the `'a` inside `Result/Option/...`).
     *
     * See `docs/design/do-bind-proposal.md`.
     */
    @Serializable
    data class Bind(
        val pattern: ParsedPattern,
        val expr: ParsedExpr,
        override val loc: Loc,
        override val leadingComments: List<ParsedComment> = emptyList(),
        override val trailingComment: ParsedComment? = null,
        override val docComment: String? = null,
        override val metadata: ParsedMetadata? = null,
    ) : ParsedStatement
}


/** Seq literal: `[1, 2, 3]`, `[x, ...rest]`, `[]` */
@Serializable
data class ParsedSeqLit(
    val elements: List<ParsedSeqElement>,
    override val loc: Loc
) : ParsedExpr


/**
 * Perform expression: `perform Handler in body`
 *
 * Examples:
 * - `perform Console in expr`
 * - `perform SeededRandom {seed = 42} in expr`
 * - `perform Console, LocalDisk in expr`
 */
@Serializable
data class ParsedPerform(
    val handlers: List<ParsedPerformHandler>,
    val body: ParsedExpr,
    override val loc: Loc
) : ParsedExpr

/**
 * Effect operation call.
 *
 * Examples:
 * - `read_file { path = "x" }` (looks like regular call but operation is qualified)
 * - `println { msg = "hi" }`
 */
@Serializable
data class ParsedEffectCall(
    val operation: ParsedQualifiedRef,
    val arguments: ParsedExpr,
    override val loc: Loc
) : ParsedExpr


/**
 * Parenthesized expression: `(expr)`
 *
 * Preserved for the formatter to maintain user's grouping style.
 */
@Serializable
data class ParsedParens(
    val expr: ParsedExpr,
    override val loc: Loc
) : ParsedExpr
