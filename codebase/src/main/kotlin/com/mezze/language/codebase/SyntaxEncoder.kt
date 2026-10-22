package com.mezze.language.codebase

import com.mezze.language.ast.MezzeType
import com.mezze.language.comptime.HostValue
import com.mezze.language.comptime.HostValue.Bool
import com.mezze.language.comptime.HostValue.FloatV
import com.mezze.language.comptime.HostValue.IntV
import com.mezze.language.comptime.HostValue.Opt
import com.mezze.language.comptime.HostValue.Seq
import com.mezze.language.comptime.HostValue.Str
import com.mezze.language.comptime.HostValue.Variant
import com.mezze.language.comptime.HostValue.Companion.record
import com.mezze.language.parsedast.*
import com.mezze.language.typedast.BindingRef
import com.mezze.language.typedast.Hash
import com.mezze.language.typedast.TypedBuildTime
import com.mezze.language.typedast.TypedCall
import com.mezze.language.typedast.TypedExpr
import com.mezze.language.typedast.TypedFieldAccess
import com.mezze.language.typedast.TypedPerformOp
import com.mezze.language.typedast.TypedVar
import com.mezze.language.typedast.forEachChild

/**
 * A declaration as written, in the shapes `std/syntax.mz` declares, with what the
 * compiler made of each node attached as `facts`.
 *
 * The tree is the parser's: an operator stays an operator, a pipe and its `@`
 * stay as written, parentheses and record shorthand are kept. The facts come
 * from the typed tree, joined by SPAN: the compiler keeps the span of what was
 * written on what it made of it, so `a > b`, which it turns into the call
 * `a.gt { r = b }`, still sits at the span of `a > b`. A node with no typed
 * counterpart at its span — parentheses, a newtype wrapper, anything in code
 * that did not type-check — has no facts, and says so.
 */
internal class SyntaxEncoder(
    private val typeShape: (MezzeType) -> HostValue,
    private val declRef: (CodebaseQueries.DeclRef) -> HostValue,
    private val reached: (Hash) -> CodebaseQueries.DeclRef?,
    /** What a call through [ability] on [receiver] reaches (see [CodebaseQueries.dispatched]). */
    private val dispatched: (ability: Hash, receiver: MezzeType) -> CodebaseQueries.DeclRef?,
    typed: List<TypedExpr>,
) {
    private data class At(val startLine: Int, val startCol: Int, val endLine: Int, val endCol: Int)

    private fun at(loc: Loc) = At(loc.startLine, loc.startCol, loc.endLine, loc.endCol)

    /** Every typed node, by the span it was written at, outermost first. */
    private val bySpan: Map<At, List<TypedExpr>> = HashMap<At, MutableList<TypedExpr>>().also { index ->
        fun walk(e: TypedExpr) {
            index.getOrPut(at(e.loc)) { mutableListOf() } += e
            e.forEachChild { walk(it) }
        }
        typed.forEach { walk(it) }
    }

    /** The facts at a span, for an occurrence found through the index. */
    fun factsAt(startLine: Int, startCol: Int, endLine: Int, endCol: Int): HostValue =
        facts(At(startLine, startCol, endLine, endCol))

    private fun facts(loc: Loc): HostValue = facts(at(loc))

    private fun facts(span: At): HostValue {
        val nodes = bySpan[span] ?: return Opt(null)
        val node = nodes.firstOrNull { it !is TypedBuildTime } ?: return Opt(null)
        fun byHash(e: TypedExpr?): Hash? = ((e as? TypedVar)?.binding as? BindingRef.ByHash)?.hash
        val refersTo = nodes.firstNotNullOfOrNull { byHash(it) }?.let(reached)
        val calls = when (node) {
            // A call by hash; or a method or operator dispatched through an
            // ability, which reaches that ability's impl for the receiver's type.
            is TypedCall -> byHash(node.function)?.let(reached)
                ?: (node.function as? TypedFieldAccess)?.let { fa ->
                    fa.dispatchedAbility?.let { dispatched(it, fa.record.type) }
                }
            is TypedPerformOp -> reached(node.effectHash)
            else -> null
        }
        return Opt(record(
            "ty" to typeShape(node.type),
            "refers_to" to Opt(refersTo?.let(declRef)),
            "calls" to Opt(calls?.let(declRef)),
            "at_build" to Bool(nodes.any { it is TypedBuildTime }),
        ))
    }

    // ── declarations ─────────────────────────────────────────────────────

    fun decl(module: String, d: ParsedDecl, ref: CodebaseQueries.DeclRef?): HostValue = record(
        "decl" to declBody(d),
        "module" to Str(module),
        "span" to span(d.loc),
        "is_public" to Bool(d.visibility == Visibility.PUBLIC),
        "doc" to HostValue.str(d.docComment),
        "metadata" to metadata(d.metadata),
        "comments" to comments(d.leadingComments),
        "trailing" to Opt(d.trailingComment?.let(::comment)),
        "ref" to Opt(ref?.let(declRef)),
    )

    private fun declBody(d: ParsedDecl): HostValue = when (d) {
        is ParsedLetDecl -> Variant("LetSyntax", record(
            "pattern" to pattern(d.pattern),
            "annotation" to Opt(d.typeAnnotation?.let(::type)),
            "value" to expr(d.value),
        ))
        is ParsedTypeDecl -> Variant("TypeSyntax", record(
            "name" to Str(d.name),
            "params" to strs(d.typeParams),
            "def" to typeDef(d.definition),
        ))
        is ParsedAbilityDecl -> Variant("AbilitySyntax", record(
            "name" to Str(d.name),
            "params" to strs(d.typeParams),
            "receiver" to HostValue.str(d.receiverTypeParam),
            "associated" to strs(d.associatedTypes),
            "ops" to Seq(d.operations.map { op -> record(
                "name" to Str(op.name),
                "param" to type(op.paramType),
                "result" to type(op.returnType),
                "effects" to Opt(op.effects?.let(::effectRow)),
                "requires" to constraints(op.abilityConstraints),
                "doc" to HostValue.str(op.doc),
                "span" to span(op.loc),
            ) }),
        ))
        is ParsedImplAbilityDecl -> {
            val ability = (d.target as? ParsedImplTarget.Named)?.ability
            Variant("ImplSyntax", record(
                "ability_name" to HostValue.str(ability?.localName),
                "ability_module" to HostValue.str(ability?.qualifier?.toString()),
                "targets" to Seq(d.targetTypes.map(::type)),
                "ability_args" to Seq(d.abilityTypeArgs.map(::type)),
                "forall" to strs(d.forallVars),
                "associated" to assoc(d.associatedTypeBindings),
                "ops" to Seq(d.operations.map { op ->
                    op(op.name, op.paramType, op.returnType, op.implementation, null, op.abilityConstraints, op.doc, op.loc)
                }),
                "derived" to Bool(d.derived),
            ))
        }
        is ParsedEffectDecl -> Variant("EffectSyntax", record(
            "name" to Str(d.name),
            "associated" to strs(d.associatedTypes),
            "ops" to Seq(d.operations.map { op -> record(
                "name" to Str(op.name),
                "param" to type(op.paramType),
                "result" to type(op.returnType),
                "effects" to strs(op.effects),
                "requires" to constraints(op.abilityConstraints),
                "doc" to HostValue.str(op.doc),
                "span" to span(op.loc),
            ) }),
        ))
        is ParsedPerformerDecl -> Variant("PerformerSyntax", record(
            "name" to Str(d.performerName),
            "effect_name" to Str(d.effect.localName),
            "effect_module" to HostValue.str(d.effect.qualifier?.toString()),
            "params" to Seq(d.params.map { p -> record("name" to Str(p.name), "ty" to type(p.type), "span" to span(p.loc)) }),
            "associated" to assoc(d.associatedTypeBindings),
            "ops" to Seq(d.operations.map { op ->
                op(op.name, op.paramType, op.returnType, op.implementation, op.effects, op.abilityConstraints, op.doc, op.loc)
            }),
        ))
        is ParsedUseDecl -> use(d.path, d.alias, reexport = false)
        is ParsedPubUseDecl -> use(d.path, d.alias, reexport = true)
        is ParsedTopLevelExpr -> Variant("TopLevelSyntax", record("expr" to expr(d.expression)))
    }

    private fun op(
        name: String, param: ParsedTypeAnnotation?, result: ParsedTypeAnnotation?, body: ParsedExpr,
        effects: ParsedEffectRow?, requires: List<ParsedFnAbilityConstraint>, doc: String?, loc: Loc,
    ) = record(
        "name" to Str(name),
        "param" to Opt(param?.let(::type)),
        "result" to Opt(result?.let(::type)),
        "body" to expr(body),
        "effects" to Opt(effects?.let(::effectRow)),
        "requires" to constraints(requires),
        "doc" to HostValue.str(doc),
        "span" to span(loc),
    )

    private fun use(path: ParsedUsePath, alias: String?, reexport: Boolean): HostValue = Variant("UseSyntax", when (path) {
        is ParsedUsePath.Module -> record(
            "module" to Str(path.path.toString()),
            "items" to Opt(null),
            "alias" to HostValue.str(alias),
            "reexport" to Bool(reexport),
        )
        is ParsedUsePath.Selective -> record(
            "module" to Str(path.modulePath.toString()),
            "items" to Opt(Seq(path.items.map { i ->
                record("name" to Str(i.name), "alias" to HostValue.str(i.alias), "span" to span(i.loc))
            })),
            "alias" to HostValue.str(alias),
            "reexport" to Bool(reexport),
        )
    })

    private fun typeDef(def: ParsedTypeDef): HostValue = when (def) {
        is ParsedRecordDef -> Variant("RecordDefSyntax", record(
            "fields" to Seq(def.fields.map { f -> record(
                "name" to Str(f.name),
                "ty" to type(f.type),
                "doc" to HostValue.str(f.docComment),
                "metadata" to metadata(f.metadata),
                "span" to span(f.loc),
            ) }),
            "is_open" to Bool(def.isOpen),
            "span" to span(def.loc),
        ))
        is ParsedVariantDef -> Variant("VariantDefSyntax", record(
            "constructors" to Seq(def.constructors.map { c -> record(
                "name" to Str(c.name),
                "payload" to Opt(c.dataType?.let(::type)),
                "doc" to HostValue.str(c.docComment),
                "metadata" to metadata(c.metadata),
                "span" to span(c.loc),
            ) }),
            "is_open" to Bool(def.isOpen),
            "span" to span(def.loc),
        ))
        is ParsedNewtypeDef -> Variant("NewtypeDefSyntax", record(
            "constructor" to Str(def.constructor),
            "wrapped" to type(def.wrappedType),
            "span" to span(def.loc),
        ))
        is ParsedTypeAliasDef -> Variant("AliasDefSyntax", record(
            "aliased" to type(def.aliasedType),
            "span" to span(def.loc),
        ))
    }

    // ── expressions ──────────────────────────────────────────────────────

    /** [e] as its `std::syntax` node: its kind is [syntaxKind], shared with the occurrence index. */
    private fun node(e: ParsedExpr, vararg fields: Pair<String, HostValue>) =
        Variant(e.syntaxKind(), HostValue.Record(fields.toList() + listOf("span" to span(e.loc), "facts" to facts(e.loc))))

    fun expr(e: ParsedExpr): HostValue = when (e) {
        is ParsedIntLit -> node(e, "value" to IntV(e.value))
        is ParsedNatLit -> node(e, "value" to IntV(e.value))
        is ParsedFloatLit -> node(e, "value" to FloatV(e.value))
        is ParsedFracLit -> node(e, "num" to IntV(e.numerator), "den" to IntV(e.denominator))
        is ParsedStringLit -> node(e, "parts" to Seq(e.parts.map { p ->
            when (p) {
                is ParsedStringPart.Literal -> Variant("TextPart", record("text" to Str(p.value)))
                is ParsedStringPart.Interpolation -> Variant("InterpPart", record("expr" to expr(p.expr)))
            }
        }))
        is ParsedCharLit -> node(e, "value" to Str(e.value.toString()))
        is ParsedIdentifier -> node(e,
            "name" to Str(e.localName),
            "module" to HostValue.str(e.qualifier?.toString()),
        )
        is ParsedLambda -> node(e,
            "params" to lambdaParams(e.params),
            "body" to expr(e.body),
            "returns" to Opt(e.returnType?.let(::type)),
            "requires" to constraints(e.abilityConstraints),
        )
        is ParsedFunctionCall -> node(e, "callee" to expr(e.function), "arg" to expr(e.arguments))
        is ParsedBinaryOp -> node(e, "op" to Str(e.op.symbol), "left" to expr(e.left), "right" to expr(e.right))
        is ParsedPipe -> node(e,
            "left" to expr(e.left),
            "right" to expr(e.right),
            "comments" to comments(e.trailingComments),
        )
        is ParsedPipePlaceholder -> node(e)
        is ParsedRecordLit -> node(e, "entries" to Seq(e.entries.map { en ->
            when (en) {
                is ParsedRecordEntry.Field -> Variant("FieldEntry", record(
                    "name" to Str(en.name),
                    "value" to Opt(en.value?.let(::expr)),
                    "span" to span(en.loc),
                    "comments" to comments(en.leadingComments + listOfNotNull(en.trailingComment)),
                ))
                is ParsedRecordEntry.Spread -> Variant("SpreadEntry", record(
                    "expr" to expr(en.expr),
                    "span" to span(en.loc),
                    "comments" to comments(en.leadingComments + listOfNotNull(en.trailingComment)),
                ))
            }
        }))
        is ParsedMemberAccess -> node(e,
            "receiver" to expr(e.receiver),
            "member" to Str(e.member),
            "member_span" to span(e.memberLoc),
        )
        is ParsedVariantConstruct -> node(e,
            "constructor" to Str(e.constructor),
            "payload" to Opt(e.value?.let(::expr)),
        )
        is ParsedNewtypeConstruct -> node(e, "type_name" to Str(e.typeName), "value" to expr(e.value))
        is ParsedIf -> node(e,
            "cond" to expr(e.condition),
            "then_branch" to expr(e.thenBranch),
            "else_branch" to expr(e.elseBranch),
        )
        is ParsedMatch -> node(e, "scrutinee" to expr(e.scrutinee), "arms" to Seq(e.cases.map { c -> record(
            "patterns" to Seq(c.patterns.map(::pattern)),
            "guard" to Opt(c.guard?.let(::expr)),
            "if_lets" to Seq(c.ifLetBindings.map { b ->
                record("pattern" to pattern(b.pattern), "value" to expr(b.expr), "span" to span(b.loc))
            }),
            "body" to expr(c.body),
            "span" to span(c.loc),
            "doc" to HostValue.str(c.docComment),
            "metadata" to metadata(c.metadata),
            "comments" to comments(c.leadingComments + listOfNotNull(c.trailingComment)),
        ) }))
        is ParsedDo -> node(e,
            "statements" to Seq(e.statements.map(::statement)),
            "result" to expr(e.result),
        )
        is ParsedSeqLit -> node(e, "elements" to Seq(e.elements.map { el ->
            when (el) {
                is ParsedSeqElement.Regular -> Variant("ItemElement", record("expr" to expr(el.expr)))
                is ParsedSeqElement.Spread -> Variant("SpreadElement", record("expr" to expr(el.expr), "span" to span(el.loc)))
            }
        }))
        is ParsedPerform -> node(e,
            "handlers" to Seq(e.handlers.map { h -> record(
                "performer" to Str(h.performer.localName),
                "module" to HostValue.str(h.performer.qualifier?.toString()),
                "span" to span(h.performer.loc),
                "params" to Opt(h.params?.let(::expr)),
            ) }),
            "body" to expr(e.body),
        )
        is ParsedEffectCall -> node(e,
            "op" to Str(e.operation.localName),
            "effect_name" to HostValue.str(e.operation.qualifier?.toString()),
            "arg" to expr(e.arguments),
        )
        is ParsedParens -> node(e, "inner" to expr(e.expr))
    }

    private fun statement(st: ParsedStatement): HostValue = when (st) {
        is ParsedStatement.Let -> Variant("LetStatement", record(
            "pattern" to pattern(st.pattern),
            "annotation" to Opt(st.typeAnnotation?.let(::type)),
            "value" to expr(st.value),
            "span" to span(st.loc),
            "doc" to HostValue.str(st.docComment),
            "metadata" to metadata(st.metadata),
            "comments" to comments(st.leadingComments + listOfNotNull(st.trailingComment)),
        ))
        is ParsedStatement.Expr -> Variant("ExprStatement", record(
            "expr" to expr(st.expr),
            "span" to span(st.loc),
            "doc" to HostValue.str(st.docComment),
            "metadata" to metadata(st.metadata),
            "comments" to comments(st.leadingComments + listOfNotNull(st.trailingComment)),
        ))
    }

    private fun lambdaParams(p: ParsedLambdaParams): HostValue = when (p) {
        is ParsedLambdaParams.Typed -> Variant("TypedParams", record("fields" to Seq(p.fields.map(::fieldType))))
        is ParsedLambdaParams.Untyped -> Variant("NamedParams", record(
            "names" to strs(p.names),
            "is_open" to Bool(p.isOpen),
            "spans" to Seq(p.nameLocs.map(::span)),
        ))
    }

    // ── patterns ─────────────────────────────────────────────────────────

    private fun pattern(p: ParsedPattern): HostValue = when (p) {
        is ParsedWildcardPattern -> Variant("WildcardPat", record("span" to span(p.loc)))
        is ParsedVariablePattern -> Variant("VarPat", record("name" to Str(p.name), "span" to span(p.loc)))
        is ParsedConstructorPattern -> Variant("ConstructorPat", record(
            "constructor" to Str(p.constructor),
            "payload" to Opt(p.payload?.let(::pattern)),
            "span" to span(p.loc),
        ))
        is ParsedNewtypePattern -> Variant("NewtypePat", record(
            "type_name" to Str(p.typeName),
            "inner" to pattern(p.inner),
            "span" to span(p.loc),
        ))
        is ParsedRecordPattern -> Variant("RecordPat", record(
            "fields" to Seq(p.entries.map { f ->
                record("name" to Str(f.name), "pattern" to Opt(f.pattern?.let(::pattern)), "span" to span(f.loc))
            }),
            "is_open" to Bool(p.isOpen),
            "span" to span(p.loc),
        ))
        is ParsedLiteralPattern -> Variant("LiteralPat", record(
            "value" to when (val v = p.value) {
                is ParsedLiteralValue.IntValue -> Variant("IntLiteral", record("value" to IntV(v.value)))
                is ParsedLiteralValue.NatValue -> Variant("NatLiteral", record("value" to IntV(v.value)))
                is ParsedLiteralValue.FloatValue -> Variant("FloatLiteral", record("value" to FloatV(v.value)))
                is ParsedLiteralValue.StringValue -> Variant("StrLiteral", record("value" to Str(v.value)))
                is ParsedLiteralValue.BoolValue -> Variant("BoolLiteral", record("value" to Bool(v.value)))
            },
            "span" to span(p.loc),
        ))
        is ParsedSeqPattern -> Variant("SeqPat", record(
            "elements" to Seq(p.elements.map(::pattern)),
            "rest" to HostValue.str(p.restPattern),
            "span" to span(p.loc),
        ))
        is ParsedStringInterpPattern -> Variant("StrPat", record(
            "parts" to Seq(p.parts.map { part ->
                when (part) {
                    is ParsedStringPatternPart.Literal -> Variant("TextPat", record("text" to Str(part.value)))
                    is ParsedStringPatternPart.Capture -> Variant("CapturePat", record("name" to Str(part.variableName)))
                }
            }),
            "span" to span(p.loc),
        ))
    }

    // ── types as written ─────────────────────────────────────────────────

    private fun type(t: ParsedTypeAnnotation): HostValue = when (t) {
        is ParsedNamedType -> Variant("NamedType", record("name" to Str(t.name), "span" to span(t.loc)))
        is ParsedQualifiedType -> Variant("QualifiedType", record(
            "module" to Str(t.modulePath.joinToString(MODULE_PATH_SEPARATOR)),
            "name" to Str(t.typeName),
            "args" to Seq(t.args.map(::type)),
            "span" to span(t.loc),
        ))
        is ParsedTypeApp -> Variant("AppliedType", record(
            "constructor" to Str(t.constructor),
            "args" to Seq(t.args.map(::type)),
            "span" to span(t.loc),
        ))
        is ParsedRecordType -> Variant("RecordTypeExpr", record(
            "fields" to Seq(t.entries.map(::fieldType)),
            "is_open" to Bool(t.isOpen),
            "span" to span(t.loc),
        ))
        is ParsedVariantType -> Variant("VariantTypeExpr", record(
            "constructors" to Seq(t.constructors.map { c ->
                record("name" to Str(c.name), "payload" to Opt(c.dataType?.let(::type)), "span" to span(c.loc))
            }),
            "is_open" to Bool(t.isOpen),
            "span" to span(t.loc),
        ))
        is ParsedFunctionType -> Variant("FnTypeExpr", record(
            "param" to type(t.paramType),
            "result" to type(t.returnType),
            "effects" to Opt(t.effects?.let(::effectRow)),
            "requires" to constraints(t.abilityConstraints),
            "span" to span(t.loc),
        ))
        is ParsedSeqType -> Variant("SeqTypeExpr", record("element" to type(t.elementType), "span" to span(t.loc)))
    }

    private fun fieldType(f: ParsedRecordTypeEntry) = record("name" to Str(f.name), "ty" to type(f.type), "span" to span(f.loc))

    private fun effectRow(row: ParsedEffectRow) = record(
        "effects" to Seq(
            row.effects.map { name -> record("name" to Str(name), "associated" to Seq(emptyList()), "span" to span(row.loc)) } +
                row.effectAnnotations.map { a ->
                    record("name" to Str(a.name), "associated" to assoc(a.associatedTypes), "span" to span(a.loc))
                },
        ),
        "is_open" to Bool(row.isPolymorphic),
        "performed" to Bool(row.isPerformed),
        "span" to span(row.loc),
    )

    private fun constraints(cs: List<ParsedFnAbilityConstraint>) = Seq(cs.map { c -> record(
        "ability_name" to Str(c.abilityName),
        "args" to Seq(c.typeArgs.map(::type)),
        "bindings" to assoc(c.associatedTypeBindings),
        "span" to span(c.loc),
    ) })

    private fun assoc(bindings: Map<String, ParsedTypeAnnotation>) =
        Seq(bindings.map { (name, t) -> record("name" to Str(name), "ty" to type(t)) })

    // ── leaves ───────────────────────────────────────────────────────────

    private fun strs(xs: List<String>) = Seq(xs.map { Str(it) })

    private fun metadata(m: ParsedMetadata?): HostValue = HostValue.str(m?.takeIf { it.fields.isNotEmpty() }?.toJsonText())

    private fun comment(c: ParsedComment) = record("text" to Str(c.text), "is_doc" to Bool(c.isDoc), "span" to span(c.loc))

    private fun comments(cs: List<ParsedComment>) = Seq(cs.map(::comment))

    private fun span(loc: Loc) = record(
        "file" to HostValue.str(loc.file.ifEmpty { null }),
        "start_line" to IntV(loc.startLine.toLong()),
        "start_col" to IntV(loc.startCol.toLong()),
        "end_line" to IntV(loc.endLine.toLong()),
        "end_col" to IntV(loc.endCol.toLong()),
    )
}
