package com.mezze.language.comptime

import com.mezze.language.ast.BinaryOperator
import com.mezze.language.ast.OperatorMapping
import com.mezze.language.parsedast.*

/**
 * `std::syntax` data back into the parser's tree: the exact inverse of
 * [SyntaxEncoder], for reading what a macro produced.
 *
 * Nothing is guessed. Every constructor the encoder emits is decoded by its tag,
 * and a tag, a field or a field's type the encoder would not have written is
 * [Malformed], naming the constructor, the field and where in the value it was
 * found. `facts` is never read: it is what the compiler said about code it
 * compiled, and decoded code has not been compiled yet.
 *
 * Every [Loc] comes from [locate], given a node's `span`. A node the encoder
 * writes without a span of its own — a string's text, a sequence item, a `use`
 * path, a `#{…}` block (carried as JSON text) — is placed at the nearest
 * enclosing node that has one.
 *
 * A quote ([ParsedQuote]) carries code and the code that builds its value; the
 * value is the parser's to derive ([quoteValue]), not this decoder's. Without
 * it, a quote is [Malformed]: a macro may not produce a quote, because nothing
 * after parsing can build one's value.
 */
class SyntaxDecoder(
    /** Where a decoded node is placed: given the node's `span` value, its Loc. */
    private val locate: (HostValue) -> Loc,
    /** Builds a quote's value from its template, splices and Loc; null rejects quotes. */
    private val quoteValue: ((ParsedCode, List<ParsedExpr>, Loc) -> ParsedExpr)? = null,
) {
    /** A value that is not `std::syntax` data the encoder could have written. */
    class Malformed(message: String) : RuntimeException(message)

    fun expr(v: HostValue): ParsedExpr = expr(v, "Expr")
    fun decl(v: HostValue): ParsedDecl = decl(v, "SyntaxDecl")
    fun code(v: HostValue): ParsedCode = code(v, "Code")
    fun pattern(v: HostValue): ParsedPattern = pattern(v, "Pattern")
    fun type(v: HostValue): ParsedTypeAnnotation = type(v, "TypeExpr")

    // ── strict access ────────────────────────────────────────────────────

    /** A record's fields, read strictly; [at] names where the record was found. */
    private inner class R(val at: String, rec: HostValue.Record) {
        private val fields: Map<String, HostValue> = rec.fields.toMap().also {
            if (it.size != rec.fields.size) {
                val dup = rec.fields.groupBy { f -> f.first }.filterValues { fs -> fs.size > 1 }.keys
                throw Malformed("$at: field(s) ${dup.joinToString { d -> "`$d`" }} written more than once")
            }
        }

        fun at(name: String) = "$at.$name"

        operator fun get(name: String): HostValue =
            fields[name] ?: throw Malformed("$at: missing field `$name`")

        private fun wrong(name: String, expected: String, got: HostValue): Nothing =
            throw Malformed("${at(name)}: expected $expected, found ${describe(got)}")

        fun str(name: String): String = when (val v = get(name)) {
            is HostValue.Str -> v.value
            else -> wrong(name, "Str", v)
        }

        fun long(name: String): Long = when (val v = get(name)) {
            is HostValue.IntV -> v.value
            else -> wrong(name, "Int", v)
        }

        fun float(name: String): Double = when (val v = get(name)) {
            is HostValue.FloatV -> v.value
            else -> wrong(name, "Float", v)
        }

        fun bool(name: String): Boolean = when (val v = get(name)) {
            is HostValue.Bool -> v.value
            else -> wrong(name, "Bool", v)
        }

        /** An `Option`: the value inside, or null for `None`. */
        fun opt(name: String): HostValue? = when (val v = get(name)) {
            is HostValue.Opt -> v.value
            else -> wrong(name, "Option", v)
        }

        fun optStr(name: String): String? = when (val v = opt(name)) {
            null -> null
            is HostValue.Str -> v.value
            else -> wrong(name, "Option Str", v)
        }

        fun <T> optOf(name: String, f: (HostValue, String) -> T): T? = opt(name)?.let { f(it, at(name)) }

        fun seq(name: String): List<HostValue> = when (val v = get(name)) {
            is HostValue.Seq -> v.items
            else -> wrong(name, "a list", v)
        }

        fun <T> seqOf(name: String, f: (HostValue, String) -> T): List<T> =
            seq(name).mapIndexed { i, item -> f(item, "${at(name)}[$i]") }

        fun strs(name: String): List<String> = seq(name).mapIndexed { i, item ->
            (item as? HostValue.Str)?.value
                ?: throw Malformed("${at(name)}[$i]: expected Str, found ${describe(item)}")
        }

        fun loc(name: String = "span"): Loc = locate(get(name))
    }

    private fun record(v: HostValue, at: String): R = when (v) {
        is HostValue.Record -> R(at, v)
        else -> throw Malformed("$at: expected a record, found ${describe(v)}")
    }

    /** A variant of the `std::syntax` type [type]: its tag, and its payload's fields. */
    private inline fun <T> variant(v: HostValue, type: String, at: String, f: (String, R) -> T): T {
        if (v !is HostValue.Variant) throw Malformed("$at: expected a $type, found ${describe(v)}")
        val payload = v.payload ?: throw Malformed("$at: $type constructor `${v.tag}` carries no fields")
        return f(v.tag, R("$at(${v.tag})", payload))
    }

    private fun unknown(type: String, tag: String, at: String): Nothing =
        throw Malformed("$at: `$tag` is not a $type constructor")

    private fun describe(v: HostValue): String = when (v) {
        is HostValue.Str -> "Str"
        is HostValue.IntV -> "Int"
        is HostValue.FloatV -> "Float"
        is HostValue.Bool -> "Bool"
        is HostValue.Seq -> "a list"
        is HostValue.Record -> "a record"
        is HostValue.Variant -> "constructor `${v.tag}`"
        is HostValue.Opt -> "an Option"
    }

    private fun modulePath(text: String, at: String): ParsedModulePath {
        val segments = text.split(MODULE_PATH_SEPARATOR)
        if (segments.any { it.isEmpty() }) throw Malformed("$at: `$text` is not a module path")
        return ParsedModulePath(segments)
    }

    // ── declarations ─────────────────────────────────────────────────────

    private fun decl(v: HostValue, at: String): ParsedDecl {
        val r = record(v, at)
        val loc = r.loc()
        val visibility = if (r.bool("is_public")) Visibility.PUBLIC else Visibility.PRIVATE
        val leading = r.seqOf("comments", ::comment)
        val trailing = r.optOf("trailing", ::comment)
        val doc = r.optStr("doc")
        val metadata = metadata(r, loc)
        return variant(r["decl"], "Decl", r.at("decl")) { tag, d ->
            when (tag) {
                "LetSyntax" -> ParsedLetDecl(
                    pattern = pattern(d["pattern"], d.at("pattern")),
                    typeAnnotation = d.optOf("annotation", ::type),
                    value = expr(d["value"], d.at("value")),
                    visibility = visibility, leadingComments = leading, trailingComment = trailing,
                    docComment = doc, metadata = metadata, loc = loc,
                )
                "TypeSyntax" -> ParsedTypeDecl(
                    name = d.str("name"),
                    typeParams = d.strs("params"),
                    definition = typeDef(d["def"], d.at("def")),
                    visibility = visibility, leadingComments = leading, trailingComment = trailing,
                    docComment = doc, metadata = metadata, loc = loc,
                )
                "AbilitySyntax" -> ParsedAbilityDecl(
                    name = d.str("name"),
                    typeParams = d.strs("params"),
                    receiverTypeParam = d.optStr("receiver"),
                    associatedTypes = d.strs("associated"),
                    operations = d.seqOf("ops") { o, oat ->
                        val op = record(o, oat)
                        ParsedAbilityOp(
                            name = op.str("name"),
                            paramType = type(op["param"], op.at("param")),
                            returnType = type(op["result"], op.at("result")),
                            effects = op.optOf("effects", ::effectRow),
                            abilityConstraints = op.seqOf("requires", ::constraint),
                            doc = op.optStr("doc"),
                            loc = op.loc(),
                        )
                    },
                    visibility = visibility, leadingComments = leading, trailingComment = trailing,
                    docComment = doc, metadata = metadata, loc = loc,
                )
                "ImplSyntax" -> {
                    val abilityName = d.optStr("ability_name")
                    val abilityModule = d.optStr("ability_module")
                    val abilitySpan = d.opt("ability_span")
                    val target = when {
                        abilityName != null -> ParsedImplTarget.Named(ParsedQualifiedRef(
                            abilityName,
                            abilityModule?.let { modulePath(it, d.at("ability_module")) },
                            locate(abilitySpan ?: throw Malformed("${d.at("ability_span")}: a named impl needs its ability's span")),
                        ))
                        abilityModule != null || abilitySpan != null ->
                            throw Malformed("${d.at("ability_name")}: an inherent impl (no ability name) has no ability module or span")
                        else -> ParsedImplTarget.Inherent
                    }
                    ParsedImplAbilityDecl(
                        target = target,
                        targetTypes = d.seqOf("targets", ::type),
                        abilityTypeArgs = d.seqOf("ability_args", ::type),
                        forallVars = d.strs("forall"),
                        associatedTypeBindings = assoc(d, "associated"),
                        operations = d.seqOf("ops") { o, oat ->
                            val op = record(o, oat)
                            if (op.opt("effects") != null) {
                                throw Malformed("${op.at("effects")}: an impl's operation has no effect row of its own")
                            }
                            ParsedImplOp(
                                name = op.str("name"),
                                paramType = op.optOf("param", ::type),
                                returnType = op.optOf("result", ::type),
                                implementation = expr(op["body"], op.at("body")),
                                abilityConstraints = op.seqOf("requires", ::constraint),
                                doc = op.optStr("doc"),
                                loc = op.loc(),
                            )
                        },
                        visibility = visibility, leadingComments = leading, trailingComment = trailing,
                        docComment = doc, metadata = metadata, loc = loc,
                        derived = d.bool("derived"),
                    )
                }
                "EffectSyntax" -> ParsedEffectDecl(
                    name = d.str("name"),
                    associatedTypes = d.strs("associated"),
                    operations = d.seqOf("ops") { o, oat ->
                        val op = record(o, oat)
                        ParsedEffectOp(
                            name = op.str("name"),
                            paramType = type(op["param"], op.at("param")),
                            returnType = type(op["result"], op.at("result")),
                            effects = op.strs("effects"),
                            abilityConstraints = op.seqOf("requires", ::constraint),
                            doc = op.optStr("doc"),
                            loc = op.loc(),
                        )
                    },
                    visibility = visibility, leadingComments = leading, trailingComment = trailing,
                    docComment = doc, metadata = metadata, loc = loc,
                )
                "PerformerSyntax" -> ParsedPerformerDecl(
                    effect = ParsedQualifiedRef(
                        d.str("effect_name"),
                        d.optStr("effect_module")?.let { modulePath(it, d.at("effect_module")) },
                        d.loc("effect_span"),
                    ),
                    performerName = d.str("name"),
                    params = d.seqOf("params") { p, pat ->
                        val f = record(p, pat)
                        ParsedPerformerParam(f.str("name"), type(f["ty"], f.at("ty")), f.loc())
                    },
                    associatedTypeBindings = assoc(d, "associated"),
                    operations = d.seqOf("ops") { o, oat ->
                        val op = record(o, oat)
                        ParsedPerformerOp(
                            name = op.str("name"),
                            paramType = op.optOf("param", ::type),
                            returnType = op.optOf("result", ::type),
                            implementation = expr(op["body"], op.at("body")),
                            effects = op.optOf("effects", ::effectRow),
                            abilityConstraints = op.seqOf("requires", ::constraint),
                            doc = op.optStr("doc"),
                            loc = op.loc(),
                        )
                    },
                    visibility = visibility, leadingComments = leading, trailingComment = trailing,
                    docComment = doc, metadata = metadata, loc = loc,
                )
                "UseSyntax" -> {
                    val module = modulePath(d.str("module"), d.at("module"))
                    // The encoder writes no span for the path: it is the whole `use` line's.
                    val path = when (d.opt("items")) {
                        null -> ParsedUsePath.Module(module, loc)
                        else -> ParsedUsePath.Selective(module, d.seqOrOpt("items").mapIndexed { i, item ->
                            val ir = record(item, "${d.at("items")}[$i]")
                            ParsedImportItem(ir.str("name"), ir.optStr("alias"), ir.loc())
                        }, loc)
                    }
                    val alias = d.optStr("alias")
                    if (d.bool("reexport")) {
                        ParsedPubUseDecl(path, alias, visibility, leading, trailing, doc, metadata, loc)
                    } else {
                        ParsedUseDecl(path, alias, visibility, leading, trailing, doc, metadata, loc)
                    }
                }
                "TopLevelSyntax" -> ParsedTopLevelExpr(
                    expression = expr(d["expr"], d.at("expr")),
                    visibility = visibility, leadingComments = leading, trailingComment = trailing,
                    docComment = doc, metadata = metadata, loc = loc,
                )
                else -> unknown("Decl", tag, r.at("decl"))
            }
        }
    }

    /** `items: Option [ImportItem]`, already known to be `Some`. */
    private fun R.seqOrOpt(name: String): List<HostValue> = when (val v = opt(name)) {
        is HostValue.Seq -> v.items
        null -> emptyList()
        else -> throw Malformed("${at(name)}: expected Option [ImportItem], found Some of ${describe(v)}")
    }

    private fun typeDef(v: HostValue, at: String): ParsedTypeDef = variant(v, "TypeDefSyntax", at) { tag, d ->
        when (tag) {
            "RecordDefSyntax" -> ParsedRecordDef(
                fields = d.seqOf("fields") { f, fat ->
                    val r = record(f, fat)
                    val loc = r.loc()
                    ParsedRecordFieldDef(r.str("name"), type(r["ty"], r.at("ty")), r.optStr("doc"), metadata(r, loc), loc)
                },
                isOpen = d.bool("is_open"),
                loc = d.loc(),
            )
            "VariantDefSyntax" -> ParsedVariantDef(
                constructors = d.seqOf("constructors") { c, cat ->
                    val r = record(c, cat)
                    val loc = r.loc()
                    ParsedVariantCtorDef(r.str("name"), r.optOf("payload", ::type), r.optStr("doc"), metadata(r, loc), loc)
                },
                isOpen = d.bool("is_open"),
                loc = d.loc(),
            )
            "NewtypeDefSyntax" -> ParsedNewtypeDef(d.str("constructor"), type(d["wrapped"], d.at("wrapped")), d.loc())
            "AliasDefSyntax" -> ParsedTypeAliasDef(type(d["aliased"], d.at("aliased")), d.loc())
            else -> unknown("TypeDefSyntax", tag, at)
        }
    }

    // ── code ─────────────────────────────────────────────────────────────

    private fun code(v: HostValue, at: String): ParsedCode = variant(v, "Code", at) { tag, c ->
        when (tag) {
            "ExprCode" -> ParsedCode.Expr(expr(c["expr"], c.at("expr")))
            "DeclsCode" -> ParsedCode.Decls(c.seqOf("decls", ::decl))
            else -> unknown("Code", tag, at)
        }
    }

    // ── expressions ──────────────────────────────────────────────────────

    private fun expr(v: HostValue, at: String): ParsedExpr = variant(v, "Expr", at) { tag, e ->
        val loc = e.loc()
        fun sub(name: String) = expr(e[name], e.at(name))
        when (tag) {
            "IntExpr" -> ParsedIntLit(e.long("value"), loc)
            "NatExpr" -> ParsedNatLit(e.long("value"), loc)
            "FloatExpr" -> ParsedFloatLit(e.float("value"), loc)
            "FracExpr" -> ParsedFracLit(e.long("num"), e.long("den"), loc)
            "StrExpr" -> ParsedStringLit(e.seqOf("parts") { p, pat ->
                variant(p, "StrPart", pat) { ptag, part ->
                    when (ptag) {
                        "TextPart" -> ParsedStringPart.Literal(part.str("text"))
                        "InterpPart" -> ParsedStringPart.Interpolation(expr(part["expr"], part.at("expr")))
                        else -> unknown("StrPart", ptag, pat)
                    }
                }
            }, loc)
            "CharExpr" -> {
                val s = e.str("value")
                if (s.length != 1) throw Malformed("${e.at("value")}: a character is one Char, found \"$s\"")
                ParsedCharLit(s[0], loc)
            }
            "NameExpr" -> ParsedIdentifier(
                e.str("name"),
                e.optStr("module")?.let { modulePath(it, e.at("module")) },
                loc,
            )
            "LambdaExpr" -> ParsedLambda(
                params = lambdaParams(e["params"], e.at("params")),
                body = sub("body"),
                returnType = e.optOf("returns", ::type),
                abilityConstraints = e.seqOf("requires", ::constraint),
                loc = loc,
            )
            "CallExpr" -> ParsedFunctionCall(sub("callee"), sub("arg"), loc)
            "BinaryExpr" -> {
                val symbol = e.str("op")
                val op = OperatorMapping.getBinaryOp(symbol)
                    ?: throw Malformed("${e.at("op")}: `$symbol` is not a binary operator")
                ParsedBinaryOp(BinaryOperator(symbol, op), sub("left"), sub("right"), loc)
            }
            "PipeExpr" -> ParsedPipe(sub("left"), sub("right"), loc, e.seqOf("comments", ::comment))
            "PlaceholderExpr" -> ParsedPipePlaceholder(loc)
            "RecordExpr" -> ParsedRecordLit(e.seqOf("entries", ::entry), loc)
            "MemberExpr" -> ParsedMemberAccess(sub("receiver"), e.str("member"), loc, e.loc("member_span"))
            "VariantExpr" -> ParsedVariantConstruct(e.str("constructor"), e.optOf("payload", ::expr), loc)
            "NewtypeExpr" -> ParsedNewtypeConstruct(e.str("type_name"), sub("value"), loc)
            "IfExpr" -> ParsedIf(sub("cond"), sub("then_branch"), sub("else_branch"), loc)
            "MatchExpr" -> ParsedMatch(sub("scrutinee"), e.seqOf("arms", ::arm), loc)
            "DoExpr" -> ParsedDo(
                statements = e.seqOf("statements", ::statement),
                result = sub("result"),
                loc = loc,
                resultLeadingComments = e.seqOf("result_comments", ::comment),
            )
            "SeqExpr" -> ParsedSeqLit(e.seqOf("elements") { el, elat ->
                variant(el, "SeqElement", elat) { etag, s ->
                    when (etag) {
                        "ItemElement" -> ParsedSeqElement.Regular(expr(s["expr"], s.at("expr")))
                        "SpreadElement" -> ParsedSeqElement.Spread(expr(s["expr"], s.at("expr")), s.loc())
                        else -> unknown("SeqElement", etag, elat)
                    }
                }
            }, loc)
            "PerformExpr" -> ParsedPerform(
                handlers = e.seqOf("handlers") { h, hat ->
                    val r = record(h, hat)
                    ParsedPerformHandler(
                        ParsedQualifiedRef(
                            r.str("performer"),
                            r.optStr("module")?.let { modulePath(it, r.at("module")) },
                            r.loc(),
                        ),
                        r.optOf("params") { p, pat ->
                            expr(p, pat) as? ParsedRecordLit
                                ?: throw Malformed("$pat: a handler's arguments are a record (RecordExpr)")
                        },
                    )
                },
                body = sub("body"),
                loc = loc,
            )
            "EffectCallExpr" -> ParsedEffectCall(
                ParsedQualifiedRef(
                    e.str("op"),
                    e.optStr("effect_name")?.let { modulePath(it, e.at("effect_name")) },
                    e.loc("op_span"),
                ),
                sub("arg"),
                loc,
            )
            "ParensExpr" -> ParsedParens(sub("inner"), loc)
            "QuoteExpr" -> {
                val template = code(e["quoted"], e.at("quoted"))
                val splices = e.seqOf("splices", ::expr)
                val build = quoteValue
                    ?: throw Malformed("$at: a macro may not produce a quote; nothing after parsing builds a quote's value")
                ParsedQuote(template, splices, build(template, splices, loc), loc)
            }
            "MacroExpr" -> ParsedMacroCall(
                macro = ParsedIdentifier(
                    e.str("name"),
                    e.optStr("module")?.let { modulePath(it, e.at("module")) },
                    e.loc("name_span"),
                ),
                input = e.str("input"),
                inputLoc = e.loc("input_span"),
                expansion = e.optOf("expansion", ::code),
                loc = loc,
            )
            else -> unknown("Expr", tag, at)
        }
    }

    private fun entry(v: HostValue, at: String): ParsedRecordEntry = variant(v, "Entry", at) { tag, e ->
        when (tag) {
            "FieldEntry" -> ParsedRecordEntry.Field(
                name = e.str("name"),
                value = e.optOf("value", ::expr),
                loc = e.loc(),
                leadingComments = e.seqOf("comments", ::comment),
                trailingComment = e.optOf("trailing", ::comment),
            )
            "SpreadEntry" -> ParsedRecordEntry.Spread(
                expr = expr(e["expr"], e.at("expr")),
                loc = e.loc(),
                leadingComments = e.seqOf("comments", ::comment),
                trailingComment = e.optOf("trailing", ::comment),
            )
            else -> unknown("Entry", tag, at)
        }
    }

    private fun arm(v: HostValue, at: String): ParsedMatchCase {
        val r = record(v, at)
        val loc = r.loc()
        return ParsedMatchCase(
            patterns = r.seqOf("patterns", ::pattern),
            guard = r.optOf("guard", ::expr),
            ifLetBindings = r.seqOf("if_lets") { b, bat ->
                val il = record(b, bat)
                ParsedIfLetGuard(pattern(il["pattern"], il.at("pattern")), expr(il["value"], il.at("value")), il.loc())
            },
            body = expr(r["body"], r.at("body")),
            loc = loc,
            leadingComments = r.seqOf("comments", ::comment),
            trailingComment = r.optOf("trailing", ::comment),
            docComment = r.optStr("doc"),
            metadata = metadata(r, loc),
        )
    }

    private fun statement(v: HostValue, at: String): ParsedStatement = variant(v, "Statement", at) { tag, s ->
        val loc = s.loc()
        when (tag) {
            "LetStatement" -> ParsedStatement.Let(
                pattern = pattern(s["pattern"], s.at("pattern")),
                typeAnnotation = s.optOf("annotation", ::type),
                value = expr(s["value"], s.at("value")),
                loc = loc,
                leadingComments = s.seqOf("comments", ::comment),
                trailingComment = s.optOf("trailing", ::comment),
                docComment = s.optStr("doc"),
                metadata = metadata(s, loc),
            )
            "ExprStatement" -> ParsedStatement.Expr(
                expr = expr(s["expr"], s.at("expr")),
                loc = loc,
                leadingComments = s.seqOf("comments", ::comment),
                trailingComment = s.optOf("trailing", ::comment),
                docComment = s.optStr("doc"),
                metadata = metadata(s, loc),
            )
            else -> unknown("Statement", tag, at)
        }
    }

    private fun lambdaParams(v: HostValue, at: String): ParsedLambdaParams = variant(v, "LambdaParams", at) { tag, p ->
        when (tag) {
            "TypedParams" -> ParsedLambdaParams.Typed(p.seqOf("fields", ::fieldType))
            "NamedParams" -> ParsedLambdaParams.Untyped(
                names = p.strs("names"),
                isOpen = p.bool("is_open"),
                nameLocs = p.seq("spans").map(locate),
            )
            else -> unknown("LambdaParams", tag, at)
        }
    }

    // ── patterns ─────────────────────────────────────────────────────────

    private fun pattern(v: HostValue, at: String): ParsedPattern = variant(v, "Pattern", at) { tag, p ->
        val loc = p.loc()
        when (tag) {
            "WildcardPat" -> ParsedWildcardPattern(loc)
            "VarPat" -> ParsedVariablePattern(p.str("name"), loc)
            "ConstructorPat" -> ParsedConstructorPattern(p.str("constructor"), p.optOf("payload", ::pattern), loc)
            "NewtypePat" -> ParsedNewtypePattern(p.str("type_name"), pattern(p["inner"], p.at("inner")), loc)
            "RecordPat" -> ParsedRecordPattern(
                entries = p.seqOf("fields") { f, fat ->
                    val r = record(f, fat)
                    ParsedRecordPatternEntry(r.str("name"), r.optOf("pattern", ::pattern), r.loc())
                },
                isOpen = p.bool("is_open"),
                loc = loc,
            )
            "LiteralPat" -> ParsedLiteralPattern(
                variant(p["value"], "PatLiteral", p.at("value")) { ltag, l ->
                    when (ltag) {
                        "IntLiteral" -> ParsedLiteralValue.IntValue(l.long("value"))
                        "NatLiteral" -> ParsedLiteralValue.NatValue(l.long("value"))
                        "FloatLiteral" -> ParsedLiteralValue.FloatValue(l.float("value"))
                        "StrLiteral" -> ParsedLiteralValue.StringValue(l.str("value"))
                        "BoolLiteral" -> ParsedLiteralValue.BoolValue(l.bool("value"))
                        else -> unknown("PatLiteral", ltag, p.at("value"))
                    }
                },
                loc,
            )
            "SeqPat" -> ParsedSeqPattern(p.seqOf("elements", ::pattern), p.optStr("rest"), loc)
            "StrPat" -> ParsedStringInterpPattern(p.seqOf("parts") { part, pat ->
                variant(part, "StrPatPart", pat) { ptag, s ->
                    when (ptag) {
                        "TextPat" -> ParsedStringPatternPart.Literal(s.str("text"))
                        "CapturePat" -> ParsedStringPatternPart.Capture(s.str("name"))
                        else -> unknown("StrPatPart", ptag, pat)
                    }
                }
            }, loc)
            else -> unknown("Pattern", tag, at)
        }
    }

    // ── types as written ─────────────────────────────────────────────────

    private fun type(v: HostValue, at: String): ParsedTypeAnnotation = variant(v, "TypeExpr", at) { tag, t ->
        val loc = t.loc()
        when (tag) {
            "NamedType" -> ParsedNamedType(t.str("name"), loc)
            "QualifiedType" -> ParsedQualifiedType(
                modulePath(t.str("module"), t.at("module")).segments,
                t.str("name"),
                t.seqOf("args", ::type),
                loc,
            )
            "AppliedType" -> ParsedTypeApp(t.str("constructor"), t.seqOf("args", ::type), loc)
            "RecordTypeExpr" -> ParsedRecordType(t.seqOf("fields", ::fieldType), t.bool("is_open"), loc)
            "VariantTypeExpr" -> ParsedVariantType(
                t.seqOf("constructors") { c, cat ->
                    val r = record(c, cat)
                    ParsedVariantTypeCtor(r.str("name"), r.optOf("payload", ::type), r.loc())
                },
                t.bool("is_open"),
                loc,
            )
            "FnTypeExpr" -> ParsedFunctionType(
                paramType = type(t["param"], t.at("param")),
                returnType = type(t["result"], t.at("result")),
                effects = t.optOf("effects", ::effectRow),
                abilityConstraints = t.seqOf("requires", ::constraint),
                loc = loc,
            )
            "SeqTypeExpr" -> ParsedSeqType(type(t["element"], t.at("element")), loc)
            else -> unknown("TypeExpr", tag, at)
        }
    }

    private fun fieldType(v: HostValue, at: String): ParsedRecordTypeEntry {
        val r = record(v, at)
        return ParsedRecordTypeEntry(r.str("name"), type(r["ty"], r.at("ty")), r.loc())
    }

    /**
     * An effect row. The encoder writes a bare name (`<Log>`) as an effect with
     * no associated bindings, and an annotated one (`<Exception having 'e = Str>`)
     * with its bindings — the parser builds an annotation only for `having` — so
     * the bindings tell the two apart.
     */
    private fun effectRow(v: HostValue, at: String): ParsedEffectRow {
        val r = record(v, at)
        val names = mutableListOf<String>()
        val annotations = mutableListOf<ParsedEffectAnnotation>()
        r.seqOf("effects") { u, uat -> record(u, uat) }.forEach { u ->
            val associated = assoc(u, "associated")
            if (associated.isEmpty()) names += u.str("name")
            else annotations += ParsedEffectAnnotation(u.str("name"), associated, u.loc())
        }
        return ParsedEffectRow(
            effects = names,
            effectAnnotations = annotations,
            isPolymorphic = r.bool("is_open"),
            isPerformed = r.bool("performed"),
            loc = r.loc(),
        )
    }

    private fun constraint(v: HostValue, at: String): ParsedFnAbilityConstraint {
        val r = record(v, at)
        return ParsedFnAbilityConstraint(r.str("ability_name"), r.seqOf("args", ::type), assoc(r, "bindings"), r.loc())
    }

    /** `[AssocBinding]` as the map the parser keeps, in written order; a name bound twice is malformed. */
    private fun assoc(r: R, name: String): Map<String, ParsedTypeAnnotation> {
        val out = LinkedHashMap<String, ParsedTypeAnnotation>()
        r.seqOf(name) { b, bat -> record(b, bat) }.forEach { b ->
            val n = b.str("name")
            if (out.put(n, type(b["ty"], b.at("ty"))) != null) throw Malformed("${r.at(name)}: `$n` is bound twice")
        }
        return out
    }

    // ── leaves ───────────────────────────────────────────────────────────

    private fun comment(v: HostValue, at: String): ParsedComment {
        val r = record(v, at)
        return ParsedComment(r.str("text"), r.bool("is_doc"), r.loc())
    }

    /**
     * A `#{…}` block, kept as JSON text. The text has no position; the block is
     * placed at its owner, [owner].
     */
    private fun metadata(r: R, owner: Loc): ParsedMetadata? {
        val text = r.optStr("metadata") ?: return null
        return try {
            ParsedMetadata.fromJsonText(text, owner)
        } catch (e: IllegalArgumentException) {
            throw Malformed("${r.at("metadata")}: ${e.message}")
        }
    }
}
