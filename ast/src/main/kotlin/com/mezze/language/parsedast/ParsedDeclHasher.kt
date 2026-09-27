package com.mezze.language.parsedast

import com.mezze.language.ast.hashing.HashTags
import com.mezze.language.ast.hashing.MezzeHashBuilder
import com.mezze.language.parsedast.*
import com.mezze.language.typedast.Hash

/**
 * Content hasher for ParsedAST — computes deterministic hashes directly from
 * parsed expressions WITHOUT converting to v1 AST. This is the AUTHORITATIVE
 * definition-identity hash (see TypedAstBuilder: TypedLetDecl.hash = hashLetDecl).
 *
 * DESIGN: hash names AS-WRITTEN — deliberately NOT alpha-normalized.
 * Mezze is call-by-name (arguments are named record fields: `f {x = .., y = ..}`),
 * so parameter/field/constructor names are part of a definition's INTERFACE, not
 * internal scaffolding. Renaming a param changes the API and MUST change the hash.
 * This is the opposite of Unison, where positional args make all bound names
 * non-semantic and safe to alpha-normalize.
 *
 * We do NOT normalize ANY names (not param/field/ctor names, not type-var names,
 * not interior `let` locals). The marginal cache reuse that alpha-normalization
 * would buy is not worth the footgun of a normalizer drawing the semantic-vs-
 * scaffolding line wrong and silently colliding two distinct definitions. Every
 * name difference is honestly a hash difference. (Decision: 2026-08-25.)
 *
 * TYPE VARIABLES are the case the API argument above does NOT cover, and they are
 * included anyway — on purpose. `type Box 'a = { v: 'a }` and
 * `type Box 'b = { v: 'b }` are the same type, and a type argument cannot be
 * passed by name, so renaming `'a` is not an interface change the way renaming a
 * record field is. They are hashed as-written for the SECOND reason only: the
 * line between a name that matters and a name that does not is exactly what a
 * normalizer has to get right every time, and getting it wrong collides two
 * definitions silently. The cost of including them is a cache miss on a rename;
 * the cost of excluding them wrongly is a wrong definition. We pay the cache miss.
 *
 * The visible consequence, so it is not a surprise: renaming a type parameter
 * changes the declaration's content hash, so its [com.mezze.language.ast.TypeIdentity.Declared]
 * changes with it, and anything keyed on the old hash (a precompiled artifact, a
 * cached impl index) must be rebuilt. Within one compilation everything rehashes
 * together and nothing observes the change.
 * → Do NOT "upgrade" this to the alpha-normalized TypedASTHasher for identity.
 *
 * Concretely hashed:
 * - Record field/param names ARE part of the hash (they're API)
 * - Variant constructor names ARE part of the hash
 * - Bound-variable / type-variable names ARE hashed as-written (no normalization)
 * - Source locations EXCLUDED (the one thing that is not content)
 * - Each node variant has a unique tag byte
 *
 * Replaces ParsedASTHasher, which round-tripped through two AST shapes to reach ASTHasher.
 */
object ParsedDeclHasher {

    /** Compatibility overload — file param is ignored (hashes are location-independent). */
    fun hashLetDecl(decl: ParsedLetDecl, @Suppress("UNUSED_PARAMETER") file: String): Hash? = hashLetDecl(decl)

    fun hashAllLetDecls(module: ParsedModule): Map<String, Hash> {
        val result = mutableMapOf<String, Hash>()
        for (decl in module.decls) {
            if (decl is ParsedLetDecl && decl.isSimpleBinding) {
                result[decl.variableName] = hashLetDecl(decl)
            }
        }
        return result
    }

    fun hashExpr(expr: ParsedExpr): Hash {
        val b = MezzeHashBuilder()
        appendExpr(b, expr)
        return Hash(b.build().toFullHexString())
    }

    fun hashLetDecl(decl: ParsedLetDecl): Hash {
        val b = MezzeHashBuilder()
        b.append(byteArrayOf(HashTags.LET_BINDING))
        b.appendBoolean(decl.visibility == Visibility.PUBLIC)
        decl.typeAnnotation?.let { b.appendBoolean(true); appendTypeAnnotation(b, it) } ?: b.appendBoolean(false)
        appendPattern(b, decl.pattern)
        appendExpr(b, decl.value)
        return Hash(b.build().toFullHexString())
    }

    fun hashTypeDecl(decl: ParsedTypeDecl): Hash {
        val b = MezzeHashBuilder()
        b.append(byteArrayOf(HashTags.TYPE_DECLARATION))
        b.append(decl.name)
        b.appendInt(decl.typeParams.size)
        for (tp in decl.typeParams) b.append(tp)
        appendTypeDef(b, decl.definition)
        return Hash(b.build().toFullHexString())
    }

    fun hashAbilityDecl(decl: ParsedAbilityDecl): Hash {
        val b = MezzeHashBuilder()
        b.append(byteArrayOf(HashTags.ABILITY_DECLARATION))
        b.append(decl.name)
        b.appendInt(decl.typeParams.size)
        for (tp in decl.typeParams) b.append(tp)
        b.appendInt(decl.operations.size)
        for (op in decl.operations.sortedBy { it.name }) {
            b.append(op.name)
            appendTypeAnnotation(b, op.paramType)
            appendTypeAnnotation(b, op.returnType)
        }
        return Hash(b.build().toFullHexString())
    }

    fun hashEffectDecl(decl: ParsedEffectDecl, @Suppress("UNUSED_PARAMETER") file: String): Hash? = hashEffectDecl(decl)
    fun hashPerformerDecl(decl: ParsedPerformerDecl, @Suppress("UNUSED_PARAMETER") file: String): Hash? = hashPerformerDecl(decl)
    fun hashImplAbilityDecl(decl: ParsedImplAbilityDecl, @Suppress("UNUSED_PARAMETER") file: String): Hash? = hashImplAbilityDecl(decl)

    fun hashEffectDecl(decl: ParsedEffectDecl): Hash {
        val b = MezzeHashBuilder()
        b.append(byteArrayOf(HashTags.EFFECT_DECLARATION))
        b.append(decl.name)
        b.appendInt(decl.operations.size)
        for (op in decl.operations.sortedBy { it.name }) {
            b.append(op.name)
            appendTypeAnnotation(b, op.paramType)
            appendTypeAnnotation(b, op.returnType)
        }
        return Hash(b.build().toFullHexString())
    }

    fun hashPerformerDecl(decl: ParsedPerformerDecl): Hash {
        val b = MezzeHashBuilder()
        b.append(byteArrayOf(HashTags.IMPL_DECLARATION))
        b.append(decl.effect.localName)
        decl.effect.qualifier?.let {
            b.appendBoolean(true); b.append(it.segments.joinToString("::"))
        } ?: b.appendBoolean(false)
        b.append(decl.performerName)
        b.appendInt(decl.operations.size)
        for (op in decl.operations.sortedBy { it.name }) {
            b.append(op.name)
            appendExpr(b, op.implementation)
        }
        return Hash(b.build().toFullHexString())
    }

    fun hashImplAbilityDecl(decl: ParsedImplAbilityDecl): Hash {
        val b = MezzeHashBuilder()
        b.append(byteArrayOf(HashTags.IMPL_ABILITY_DECLARATION))
        when (val target = decl.target) {
            is ParsedImplTarget.Named -> {
                b.appendBoolean(false)
                b.append(target.ability.localName)
                target.ability.qualifier?.let {
                    b.appendBoolean(true); b.append(it.segments.joinToString("::"))
                } ?: b.appendBoolean(false)
            }
            is ParsedImplTarget.Inherent -> b.appendBoolean(true)
        }
        b.appendInt(decl.abilityTypeArgs.size)
        for (t in decl.abilityTypeArgs) appendTypeAnnotation(b, t)
        b.appendInt(decl.targetTypes.size)
        for (t in decl.targetTypes) appendTypeAnnotation(b, t)
        b.appendInt(decl.forallVars.size)
        for (v in decl.forallVars) b.append(v)
        b.appendInt(decl.associatedTypeBindings.size)
        for ((k, v) in decl.associatedTypeBindings.toSortedMap()) {
            b.append(k)
            appendTypeAnnotation(b, v)
        }
        b.appendInt(decl.operations.size)
        for (op in decl.operations.sortedBy { it.name }) {
            b.append(op.name)
            appendExpr(b, op.implementation)
        }
        return b.buildHash()
    }

    fun computeAllHashes(module: ParsedModule): ParsedModule {
        val hashedDecls = module.decls.map { decl ->
            when (decl) {
                is ParsedLetDecl -> decl
                is ParsedTypeDecl -> if (decl.contentHash != null) decl else decl.copy(contentHash = hashTypeDecl(decl))
                is ParsedAbilityDecl -> if (decl.contentHash != null) decl else decl.copy(contentHash = hashAbilityDecl(decl))
                is ParsedEffectDecl -> if (decl.contentHash != null) decl else decl.copy(contentHash = hashEffectDecl(decl))
                is ParsedPerformerDecl -> if (decl.contentHash != null) decl else decl.copy(contentHash = hashPerformerDecl(decl))
                is ParsedImplAbilityDecl -> if (decl.contentHash != null) decl else decl.copy(contentHash = hashImplAbilityDecl(decl))
                else -> decl
            }
        }
        return module.copy(decls = hashedDecls)
    }

    // ========================================================================
    // Expression hashing
    // ========================================================================

    private fun appendExpr(b: MezzeHashBuilder, expr: ParsedExpr) {
        when (expr) {
            is ParsedIntLit -> { b.append(byteArrayOf(HashTags.INTEGER_LITERAL)); b.appendLong(expr.value); Unit }
            is ParsedNatLit -> { b.append(byteArrayOf(0x01, HashTags.INTEGER_LITERAL)); b.appendLong(expr.value); Unit }
            is ParsedFloatLit -> { b.append(byteArrayOf(HashTags.FLOAT_LITERAL)); b.appendLong(java.lang.Double.doubleToLongBits(expr.value)); Unit }
            is ParsedFracLit -> { b.append(byteArrayOf(0x02, HashTags.FLOAT_LITERAL)); b.appendLong(expr.numerator); b.appendLong(expr.denominator); Unit }
            is ParsedCharLit -> { b.append(byteArrayOf(0x03, HashTags.STRING_LITERAL)); b.appendInt(expr.value.code); Unit }
            is ParsedStringLit -> {
                b.append(byteArrayOf(HashTags.STRING_LITERAL))
                b.appendInt(expr.parts.size)
                for (part in expr.parts) {
                    if (part is ParsedStringPart.Literal) { b.appendBoolean(false); b.append(part.value) }
                    else if (part is ParsedStringPart.Interpolation) { b.appendBoolean(true); appendExpr(b, part.expr) }
                }
            }
            is ParsedIdentifier -> {
                b.append(byteArrayOf(HashTags.EXTERNAL_REF))
                b.append(expr.localName)
                expr.qualifier?.let { b.appendBoolean(true); b.append(it.segments.joinToString("::")) } ?: b.appendBoolean(false)
                Unit
            }
            is ParsedLambda -> {
                b.append(byteArrayOf(HashTags.LAMBDA))
                when (val params = expr.params) {
                    is ParsedLambdaParams.Typed -> {
                        b.appendBoolean(true)
                        b.appendInt(params.fields.size)
                        for (f in params.fields) { b.append(f.name); appendTypeAnnotation(b, f.type) }
                    }
                    is ParsedLambdaParams.Untyped -> {
                        b.appendBoolean(false)
                        b.appendInt(params.names.size)
                        for (n in params.names) b.append(n)
                        b.appendBoolean(params.isOpen)
                    }
                }
                appendExpr(b, expr.body)
            }
            is ParsedFunctionCall -> { b.append(byteArrayOf(HashTags.FUNCTION_CALL)); appendExpr(b, expr.function); appendExpr(b, expr.arguments) }
            is ParsedPipe -> { b.append(byteArrayOf(HashTags.PIPE)); appendExpr(b, expr.left); appendExpr(b, expr.right) }
            is ParsedPipePlaceholder -> { b.append(byteArrayOf(HashTags.PIPE, 0x01)); Unit }
            is ParsedRecordLit -> {
                b.append(byteArrayOf(HashTags.RECORD))
                b.appendInt(expr.entries.size)
                for (entry in expr.entries) when (entry) {
                    is ParsedRecordEntry.Field -> {
                        b.appendBoolean(false); b.append(entry.name)
                        entry.value?.let { b.appendBoolean(true); appendExpr(b, it) } ?: b.appendBoolean(false)
                    }
                    is ParsedRecordEntry.Spread -> { b.appendBoolean(true); appendExpr(b, entry.expr) }
                }
            }
            is ParsedMemberAccess -> { b.append(byteArrayOf(HashTags.FIELD_ACCESS)); appendExpr(b, expr.receiver); b.append(expr.member); Unit }
            is ParsedVariantConstruct -> {
                b.append(byteArrayOf(HashTags.VARIANT_CONSTRUCTOR)); b.append(expr.constructor)
                expr.value?.let { b.appendBoolean(true); appendExpr(b, it) } ?: b.appendBoolean(false)
                Unit
            }
            is ParsedNewtypeConstruct -> {
                b.append(byteArrayOf(HashTags.VARIANT_CONSTRUCTOR, 0x01)); b.append(expr.typeName)
                appendExpr(b, expr.value)
            }
            is ParsedIf -> { b.append(byteArrayOf(HashTags.IF_ELSE)); appendExpr(b, expr.condition); appendExpr(b, expr.thenBranch); appendExpr(b, expr.elseBranch) }
            is ParsedMatch -> {
                b.append(byteArrayOf(HashTags.MATCH)); appendExpr(b, expr.scrutinee)
                b.appendInt(expr.cases.size)
                for (c in expr.cases) {
                    b.appendInt(c.patterns.size)
                    for (p in c.patterns) appendPattern(b, p)
                    c.guard?.let { b.appendBoolean(true); appendExpr(b, it) } ?: b.appendBoolean(false)
                    b.appendInt(c.ifLetBindings.size)
                    for (ilb in c.ifLetBindings) {
                        appendPattern(b, ilb.pattern)
                        appendExpr(b, ilb.expr)
                    }
                    appendExpr(b, c.body)
                }
            }
            is ParsedDo -> {
                b.append(byteArrayOf(HashTags.BLOCK))
                b.appendInt(expr.statements.size)
                for (stmt in expr.statements) when (stmt) {
                    is ParsedStatement.Expr -> { b.appendBoolean(false); appendExpr(b, stmt.expr) }
                    is ParsedStatement.Let -> {
                        b.appendBoolean(true); appendPattern(b, stmt.pattern)
                        stmt.typeAnnotation?.let { b.appendBoolean(true); appendTypeAnnotation(b, it) } ?: b.appendBoolean(false)
                        appendExpr(b, stmt.value)
                    }
                }
                appendExpr(b, expr.result)
            }
            is ParsedSeqLit -> {
                b.append(byteArrayOf(HashTags.ARRAY))
                b.appendInt(expr.elements.size)
                for (elem in expr.elements) when (elem) {
                    is ParsedSeqElement.Regular -> { b.appendBoolean(false); appendExpr(b, elem.expr) }
                    is ParsedSeqElement.Spread -> { b.appendBoolean(true); appendExpr(b, elem.expr) }
                }
            }
            is ParsedPerform -> {
                b.append(byteArrayOf(HashTags.PERFORM))
                b.appendInt(expr.handlers.size)
                for (h in expr.handlers) {
                    b.append(h.performer.localName)
                    h.performer.qualifier?.let { b.appendBoolean(true); b.append(it.segments.joinToString("::")) } ?: b.appendBoolean(false)
                    h.params?.let { b.appendBoolean(true); appendExpr(b, it) } ?: b.appendBoolean(false)
                }
                appendExpr(b, expr.body)
            }
            is ParsedEffectCall -> {
                b.append(byteArrayOf(HashTags.PERFORM, 0x01))
                b.append(expr.operation.localName)
                expr.operation.qualifier?.let { b.appendBoolean(true); b.append(it.segments.joinToString("::")) } ?: b.appendBoolean(false)
                appendExpr(b, expr.arguments)
            }
            is ParsedBinaryOp -> { b.append(byteArrayOf(HashTags.FUNCTION_CALL)); b.append(expr.op.symbol); appendExpr(b, expr.left); appendExpr(b, expr.right) }
            is ParsedParens -> appendExpr(b, expr.expr)
            is ParsedMatchCase -> { b.append(byteArrayOf(HashTags.MATCH, 0x01)); Unit }
            is ParsedIfLetGuard -> { b.append(byteArrayOf(HashTags.IF_ELSE, 0x01)); appendPattern(b, expr.pattern); appendExpr(b, expr.expr) }
            is ParsedTopLevelExpr -> appendExpr(b, expr.expression)
        }
    }

    // ========================================================================
    // Pattern hashing
    // ========================================================================

    private fun appendPattern(b: MezzeHashBuilder, pattern: ParsedPattern) {
        when (pattern) {
            is ParsedVariablePattern -> { b.append(byteArrayOf(HashTags.VARIABLE_PATTERN)); b.append(pattern.name); Unit }
            is ParsedWildcardPattern -> { b.append(byteArrayOf(HashTags.WILDCARD_PATTERN)); Unit }
            is ParsedLiteralPattern -> {
                b.append(byteArrayOf(HashTags.LITERAL_PATTERN))
                when (val v = pattern.value) {
                    is ParsedLiteralValue.IntValue -> { b.append(byteArrayOf(HashTags.INTEGER_LITERAL)); b.appendLong(v.value); Unit }
                    is ParsedLiteralValue.NatValue -> { b.append(byteArrayOf(0x01, HashTags.INTEGER_LITERAL)); b.appendLong(v.value); Unit }
                    is ParsedLiteralValue.FloatValue -> { b.append(byteArrayOf(HashTags.FLOAT_LITERAL)); b.appendLong(java.lang.Double.doubleToLongBits(v.value)); Unit }
                    is ParsedLiteralValue.StringValue -> { b.append(byteArrayOf(HashTags.STRING_LITERAL)); b.append(v.value); Unit }
                    is ParsedLiteralValue.BoolValue -> { b.append(byteArrayOf(HashTags.BOOLEAN_LITERAL)); b.appendBoolean(v.value); Unit }
                }
            }
            is ParsedConstructorPattern -> {
                b.append(byteArrayOf(HashTags.CONSTRUCTOR_PATTERN)); b.append(pattern.constructor)
                pattern.payload?.let { b.appendBoolean(true); appendPattern(b, it) } ?: b.appendBoolean(false)
                Unit
            }
            is ParsedNewtypePattern -> {
                b.append(byteArrayOf(HashTags.CONSTRUCTOR_PATTERN, 0x01)); b.append(pattern.typeName)
                appendPattern(b, pattern.inner)
            }
            is ParsedRecordPattern -> {
                b.append(byteArrayOf(HashTags.RECORD_PATTERN))
                b.appendInt(pattern.entries.size)
                for (e in pattern.entries) {
                    b.append(e.name)
                    e.pattern?.let { b.appendBoolean(true); appendPattern(b, it) } ?: b.appendBoolean(false)
                }
                b.appendBoolean(pattern.isOpen); Unit
            }
            is ParsedSeqPattern -> {
                b.append(byteArrayOf(HashTags.SEQ_PATTERN))
                b.appendInt(pattern.elements.size)
                for (elem in pattern.elements) appendPattern(b, elem)
                pattern.restPattern?.let { b.appendBoolean(true); b.append(it) } ?: b.appendBoolean(false)
                Unit
            }
            is ParsedStringInterpPattern -> {
                b.append(byteArrayOf(HashTags.STRING_INTERPOLATION_PATTERN))
                b.appendInt(pattern.parts.size)
                for (part in pattern.parts) when (part) {
                    is ParsedStringPatternPart.Literal -> { b.appendBoolean(false); b.append(part.value) }
                    is ParsedStringPatternPart.Capture -> { b.appendBoolean(true); b.append(part.variableName) }
                }
            }
        }
    }

    // ========================================================================
    // Type annotation hashing
    // ========================================================================

    private fun appendTypeAnnotation(b: MezzeHashBuilder, ann: ParsedTypeAnnotation) {
        when (ann) {
            is ParsedNamedType -> { b.append(byteArrayOf(HashTags.TYPE_VAR)); b.append(ann.name); Unit }
            is ParsedQualifiedType -> {
                b.append(byteArrayOf(HashTags.TYPE_CONSTRUCTOR))
                b.append(ann.modulePath.joinToString("::"))
                b.append(ann.typeName)
                // `args` distinguishes e.g. `codec::Entry Str Json` from
                // `codec::Entry Bool Xml`; without them these collide on the content
                // hash (mirrors the ParsedTypeApp branch, which already hashes args).
                b.appendInt(ann.args.size)
                for (arg in ann.args) appendTypeAnnotation(b, arg)
            }
            is ParsedTypeApp -> {
                b.append(byteArrayOf(HashTags.TYPE_CONSTRUCTOR, 0x01))
                b.append(ann.constructor)
                b.appendInt(ann.args.size)
                for (arg in ann.args) appendTypeAnnotation(b, arg)
            }
            is ParsedRecordType -> {
                b.append(byteArrayOf(HashTags.RECORD_TYPE))
                b.appendInt(ann.entries.size)
                for (f in ann.entries) { b.append(f.name); appendTypeAnnotation(b, f.type) }
                b.appendBoolean(ann.isOpen); Unit
            }
            is ParsedVariantType -> {
                b.append(byteArrayOf(HashTags.VARIANT_TYPE))
                b.appendInt(ann.constructors.size)
                for (c in ann.constructors.sortedBy { it.name }) {
                    b.append(c.name)
                    c.dataType?.let { b.appendBoolean(true); appendTypeAnnotation(b, it) } ?: b.appendBoolean(false)
                }
            }
            is ParsedFunctionType -> {
                b.append(byteArrayOf(HashTags.FUNCTION_TYPE))
                appendTypeAnnotation(b, ann.paramType)
                appendTypeAnnotation(b, ann.returnType)
                // `effects` and `abilityConstraints` are semantically significant —
                // `{p}->Str <FileSystem>` vs `{p}->Str` (pure), and `… with Eq 'a` vs
                // unconstrained, are DIFFERENT types. Dropping them let effectful and
                // pure / constrained and unconstrained signatures collide on the
                // content hash (which keys by-hash registries + import merge).
                // NB: ParsedEffectRow is NOT a ParsedTypeAnnotation, so we hash its
                // fields inline (the `is ParsedEffectRow` case below is unreachable).
                ann.effects?.let { row ->
                    b.appendBoolean(true)
                    b.appendInt(row.effects.size)
                    for (e in row.effects.sorted()) b.append(e)
                    b.appendInt(row.effectAnnotations.size)
                    for (ea in row.effectAnnotations.sortedBy { it.name }) {
                        b.append(ea.name)
                        b.appendInt(ea.associatedTypes.size)
                        for ((k, v) in ea.associatedTypes.toSortedMap()) { b.append(k); appendTypeAnnotation(b, v) }
                    }
                    b.appendBoolean(row.isPolymorphic)
                    b.appendBoolean(row.isPerformed)
                } ?: b.appendBoolean(false)
                b.appendInt(ann.abilityConstraints.size)
                for (c in ann.abilityConstraints) {
                    b.append(c.abilityName)
                    b.appendInt(c.typeArgs.size)
                    for (t in c.typeArgs) appendTypeAnnotation(b, t)
                    b.appendInt(c.associatedTypeBindings.size)
                    for ((k, v) in c.associatedTypeBindings.toSortedMap()) { b.append(k); appendTypeAnnotation(b, v) }
                }
            }
            is ParsedSeqType -> { b.append(byteArrayOf(HashTags.SEQ_TYPE)); appendTypeAnnotation(b, ann.elementType) }
            is ParsedEffectRow -> {
                b.append(byteArrayOf(HashTags.PERFORM, 0x02))
                b.appendInt(ann.effects.size)
                for (e in ann.effects.sorted()) b.append(e); Unit
            }
            is ParsedEffectAnnotation -> {
                b.append(byteArrayOf(HashTags.PERFORM, 0x03))
                b.append(ann.name)
                b.appendInt(ann.associatedTypes.size)
                for ((k, v) in ann.associatedTypes.toSortedMap()) {
                    b.append(k); appendTypeAnnotation(b, v)
                }
            }
        }
    }

    // ========================================================================
    // Type definition hashing (for ParsedTypeDecl)
    // ========================================================================

    private fun appendTypeDef(b: MezzeHashBuilder, def: ParsedTypeDef) {
        when (def) {
            is ParsedRecordDef -> {
                b.append(byteArrayOf(HashTags.RECORD_TYPE, 0x01))
                b.appendInt(def.fields.size)
                for (f in def.fields) { b.append(f.name); appendTypeAnnotation(b, f.type) }
            }
            is ParsedVariantDef -> {
                b.append(byteArrayOf(HashTags.VARIANT_TYPE, 0x01))
                b.appendInt(def.constructors.size)
                for (c in def.constructors.sortedBy { it.name }) {
                    b.append(c.name)
                    c.dataType?.let { b.appendBoolean(true); appendTypeAnnotation(b, it) } ?: b.appendBoolean(false)
                }
            }
            is ParsedNewtypeDef -> {
                b.append(byteArrayOf(HashTags.TYPE_CONSTRUCTOR, 0x02))
                appendTypeAnnotation(b, def.wrappedType)
                // #400: a newtype is a NOMINAL, module-opaque type — two modules each
                // declaring `newtype Id = Str` must be DISTINCT types, not the same one.
                // Records/variants may collide structurally (they unify by shape), but a
                // newtype's whole purpose is a nominal wall, so its identity hash MUST
                // fold in the decl-site. We salt with the source file (the module the
                // newtype was declared in) — stable across edits elsewhere in the module,
                // yet different for a same-named newtype in another file. Without this the
                // content hash omits module context and cross-module newtypes collide,
                // laundering one module's Id where another's is required (a soundness hole).
                //
                // We salt with the FILE BASENAME, not the full path: the build-time
                // WellKnownHashGenerator parses stdlib with absolute paths while the
                // runtime parses with module-relative paths, so a full-path salt would make
                // a stdlib newtype's baked well-known hash disagree with its runtime hash
                // (breaking `MutSeq`/`Instant`/… identity resolution). The basename is
                // stable across both and across machines, and still distinguishes modules.
                b.append(def.loc.file.substringAfterLast('/').substringAfterLast('\\'))
            }
            is ParsedTypeAliasDef -> {
                b.append(byteArrayOf(HashTags.TYPE_ALIAS, 0x01))
                appendTypeAnnotation(b, def.aliasedType)
            }
        }
    }
}
