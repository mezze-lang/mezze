package com.mezze.language.codebase

import com.mezze.language.ast.MezzeType
import com.mezze.language.comptime.HostValue
import com.mezze.language.comptime.HostValue.Bool
import com.mezze.language.comptime.HostValue.Companion.record
import com.mezze.language.comptime.HostValue.FloatV
import com.mezze.language.comptime.HostValue.IntV
import com.mezze.language.comptime.HostValue.Opt
import com.mezze.language.comptime.HostValue.Seq
import com.mezze.language.comptime.HostValue.Str
import com.mezze.language.comptime.HostValue.Variant
import com.mezze.language.parsedast.Loc
import com.mezze.language.typedast.*

/**
 * A typed body as `std::codebase`'s `CodeExpr`: every node with its type and
 * span, every name with the declaration it resolves to (by hash, so a local that
 * shadows a top-level name is never mistaken for it).
 *
 * One `when` per typed sealed family, with no `else`: a node added to the typed
 * tree must be given a shape here before anything compiles.
 */
internal class TypedBodyEncoder(
    private val typeShape: (MezzeType) -> HostValue,
    private val declOf: (Hash) -> HostValue?,
) {

    private fun span(loc: Loc) = record(
        "file" to HostValue.str(loc.file),
        "start_line" to IntV(loc.startLine.toLong()),
        "start_col" to IntV(loc.startCol.toLong()),
        "end_line" to IntV(loc.endLine.toLong()),
        "end_col" to IntV(loc.endCol.toLong()),
    )

    private fun node(tag: String, e: TypedExpr, vararg fields: Pair<String, HostValue>) =
        Variant(tag, HostValue.Record(fields.toList() + listOf("ty" to typeShape(e.type), "span" to span(e.loc))))

    private fun lit(tag: String, value: HostValue) = Variant(tag, record("val" to value))

    private fun item(e: TypedRecordEntry): HostValue = when (e) {
        is TypedRecordEntry.Field -> Variant("NamedItem", record("name" to Str(e.name), "value" to expr(e.value)))
        is TypedRecordEntry.Spread -> Variant("SpreadItem", record("value" to expr(e.expr)))
    }

    fun expr(e: TypedExpr): HostValue = when (e) {
        is TypedIntLit -> node("LitExpr", e, "lit" to lit("LInt", IntV(e.value)))
        is TypedNatLit -> node("LitExpr", e, "lit" to lit("LNat", IntV(e.value)))
        is TypedFloatLit -> node("LitExpr", e, "lit" to lit("LFloat", FloatV(e.value)))
        is TypedFracLit -> node("LitExpr", e, "lit" to Variant("LFrac", record("num" to IntV(e.numerator), "den" to IntV(e.denominator))))
        is TypedBoolLit -> node("LitExpr", e, "lit" to lit("LBool", Bool(e.value)))
        is TypedCharLit -> node("LitExpr", e, "lit" to lit("LChar", Str(e.value.toString())))
        is TypedStringLit -> node("StrExpr", e, "parts" to Seq(e.parts.map { p ->
            when (p) {
                is TypedStringPart.Literal -> Variant("TextPart", record("text" to Str(p.value)))
                is TypedStringPart.Interpolation -> Variant("InterpPart", record("expr" to expr(p.expr)))
            }
        }))
        is TypedVar -> node("VarExpr", e,
            "name" to Str(e.name),
            "decl" to Opt((e.binding as? BindingRef.ByHash)?.let { declOf(it.hash) }),
        )
        is TypedCall -> node("CallExpr", e, "callee" to expr(e.function), "arg" to expr(e.arg))
        is TypedLambda -> node("LambdaExpr", e, "params" to Seq(e.params.map { Str(it.name) }), "body" to expr(e.body))
        is TypedRecord -> node("RecordExpr", e, "items" to Seq(e.entries.map { item(it) }))
        is TypedFieldAccess -> node("FieldAccessExpr", e, "record" to expr(e.record), "field" to Str(e.field))
        is TypedRecordUpdate -> node("UpdateExpr", e, "record" to expr(e.record), "items" to Seq(e.updates.map { item(it) }))
        is TypedVariantConstruct -> node("VariantExpr", e, "constructor" to Str(e.tag), "payload" to Opt(e.payload?.let { expr(it) }))
        is TypedMatch -> node("MatchExpr", e, "scrutinee" to expr(e.scrutinee), "arms" to Seq(e.cases.map { c ->
            record(
                "patterns" to Seq(c.patterns.map { pattern(it) }),
                "guard" to Opt(c.guard?.let { expr(it) }),
                "if_lets" to Seq(c.ifLetBindings.map { b -> record("pattern" to pattern(b.pattern), "value" to expr(b.expr)) }),
                "body" to expr(c.body),
                "span" to span(c.loc),
            )
        }))
        is TypedIf -> node("IfExpr", e, "cond" to expr(e.condition), "then_branch" to expr(e.thenBranch), "else_branch" to expr(e.elseBranch))
        is TypedBlock -> node("BlockExpr", e, "statements" to Seq(e.statements.map { st ->
            when (st) {
                is TypedStatement.Let -> Variant("LetStmt", record("name" to Str(st.name), "value" to expr(st.value), "span" to span(st.loc)))
                is TypedStatement.Expr -> Variant("ExprStmt", record("expr" to expr(st.expr), "span" to span(st.loc)))
            }
        }), "result" to expr(e.result))
        is TypedSeq -> node("SeqExpr", e, "items" to Seq(e.elements.map { el ->
            when (el) {
                is TypedSeqElement.Regular -> Variant("PlainItem", record("value" to expr(el.expr)))
                is TypedSeqElement.Spread -> Variant("SpreadItem", record("value" to expr(el.expr)))
            }
        }))
        is TypedPerform -> node("PerformExpr", e, "handlers" to Seq(e.handlers.map { h ->
            record("performer" to Str(h.performerName), "performer_hash" to Str(h.performerHash.hex), "effect_hash" to Str(h.effectHash.hex))
        }), "body" to expr(e.body))
        is TypedPerformOp -> node("OpExpr", e, "effect_hash" to Str(e.effectHash.hex), "op" to Str(e.operationName), "arg" to expr(e.arg))
        is TypedPipe -> node("PipeExpr", e, "left" to expr(e.left), "right" to expr(e.right))
    }

    private fun pnode(tag: String, p: TypedPattern, vararg fields: Pair<String, HostValue>) =
        Variant(tag, HostValue.Record(fields.toList() + listOf("ty" to typeShape(p.type))))

    fun pattern(p: TypedPattern): HostValue = when (p) {
        is TypedVarPattern -> pnode("VarPat", p, "name" to Str(p.name))
        is TypedWildcardPattern -> pnode("WildcardPat", p)
        is TypedLiteralPattern -> pnode("LitPat", p, "value" to expr(p.value))
        is TypedVariantPattern -> pnode("VariantPat", p, "constructor" to Str(p.tag), "payload" to Opt(p.payload?.let { pattern(it) }))
        is TypedRecordPattern -> pnode("RecordPat", p, "fields" to Seq(p.fields.map { f ->
            record("name" to Str(f.name), "pattern" to Opt(f.pattern?.let { pattern(it) }))
        }), "is_open" to Bool(p.isOpen))
        is TypedSeqPattern -> pnode("SeqPat", p, "elements" to Seq(p.elements.map { pattern(it) }), "rest" to HostValue.str(p.rest))
        is TypedStringInterpPattern -> pnode("StrPat", p, "parts" to Seq(p.parts.map { part ->
            when (part) {
                is TypedStringPatternPart.Literal -> Variant("TextPat", record("text" to Str(part.value)))
                is TypedStringPatternPart.Capture -> Variant("CapturePat", record("name" to Str(part.variableName)))
            }
        }))
    }
}
