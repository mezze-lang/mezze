package com.mezze.language.typechecker.hover

import com.mezze.language.ast.*
import com.mezze.language.ast.prettyPrintTypeWithAliases
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.ImportKind
import com.mezze.language.typechecker.TypeCheckResult
import com.mezze.language.typechecker.lookup.TypedAstLookup
import com.mezze.language.typechecker.lookup.TypedAstScopeWalker

/**
 * Hover rendering, shared by every front end.
 *
 * This was private to the LSP's MezzeTextDocumentService, so the CLI and the
 * WASM playground could not reach it — the playground had grown a much weaker
 * version that printed a bare type with no alias resolution, no ability
 * specialisation and no doc comments. Same reasoning as TypedAstLookup and
 * JsonDiagnosticFormatter before it (2d0650955): one implementation, not
 * several of differing quality.
 *
 * Genuinely editor-bound behaviour stays in the LSP and arrives through the
 * optional hooks on [Context] — definition links need document URIs, and
 * cross-module docs need the project store. A caller that supplies neither
 * still gets types, ability specialisation and in-file docs.
 *
 * Output is Markdown with \`\`\`mezze fences, which is what LSP hover expects.
 */
object HoverInfo {

    /**
     * Everything hover reads about a document, with no editor types attached.
     *
     * Mirrors the fields of the LSP's DocumentState that hover actually uses;
     * version and diagnostics are absent because hover never touches them.
     */
    data class Context(
        val content: String,
        val typeCheckResult: TypeCheckResult?,
        val typeAliases: Map<String, TypeAliasInfo>,
        val parsedModule: ParsedModule?,
        /**
         * The last result that typechecked, so hover still answers while the
         * buffer is mid-edit and failing. The LSP keeps a per-URI cache; a
         * one-shot caller passes the current result or null.
         */
        val lastSuccessful: TypeCheckResult? = null,
        /** URI of this document, for linking to a definition in the same buffer. */
        val selfUri: String? = null,
        /**
         * Resolve a source path to a linkable URI, or null when unreachable.
         * The LSP turns this into "go to definition"; other callers return null
         * and hover names the symbol without a link.
         */
        val resolveFileUri: ((String) -> String?)? = null,
        /** Doc comment for a name defined in another module, or null. */
        val docFromStore: ((String) -> String?)? = null,
        /**
         * Ability decls from the project store, for ops whose owning ability is
         * not in this file. Empty when the caller has no store.
         */
        val storeAbilityDecls: (() -> List<com.mezze.language.typedast.TypedAbilityDecl>)? = null,
    )

    /**
     * Markdown for the symbol at a 0-based (line, character), or null.
     *
     * Coordinates are 0-based to match the LSP's Position; Loc and
     * TypedAstLookup are 1-based, and the conversion happens inside.
     */
    fun at(ctx: Context, line: Int, character: Int): String? =
        findHoverInfo(ctx, line, character)

    /**
     * Find hover information at a position.
     * Works even when type checking fails - uses AST for basic info.
     * Returns markdown with syntax highlighting.
     */
    private fun findHoverInfo(state: Context, line0: Int, char0: Int): String? {
        // Get the word at the position
        val lines = state.content.lines()
        if (line0 >= lines.size) return null

        val line = lines[line0]

        // Don't hover inside a comment. `## # Heading` etc. is prose, not code —
        // extracting a "word" there and resolving it as an identifier produced
        // spurious type popups (e.g. `{} <Log>`) over comment text.
        if (isInsideLineComment(line, char0)) return null

        val word = extractWordAt(line, char0)
        if (word.isNullOrEmpty()) return null

        val result = state.typeCheckResult

        // Impl hover: if the cursor is on the HEADER of an impl decl (the `impl`
        // keyword, the ability/effect name, or the target type) — as opposed to
        // deep in an op body — show which ability/effect it implements, its ops,
        // and a link to the decl. Header = the line the impl's loc starts on.
        run {
            val pm = state.parsedModule ?: return@run
            val lineNo = line0 + 1
            // Header detection is robust to comment-shifted decl locs: the cursor
            // line must be inside the decl's range AND its trimmed text starts
            // with `impl` (the header line), not a `name = …` op-body line.
            val onImplHeaderLine = line.trimStart().startsWith("impl")
            for (decl in pm.decls) {
                if (lineNo < decl.loc.startLine || lineNo > decl.loc.endLine) continue
                when (decl) {
                    is com.mezze.language.parsedast.ParsedImplAbilityDecl -> {
                        if (onImplHeaderLine &&
                            (word == "impl" || word == decl.abilityName ||
                                decl.targetTypes.any { formatTypeAnnotation(it).substringBefore(' ') == word })
                        ) {
                            val abilityDecl = pm.decls.filterIsInstance<ParsedAbilityDecl>()
                                .find { it.name == decl.abilityName }
                                ?: result?.importedAbilities?.get(decl.abilityName)
                            val fromModule = result?.resolvedImports
                                ?.find { it.localName == decl.abilityName && it.kind == ImportKind.ABILITY }
                                ?.modulePath
                            return formatAbilityImplHover(decl, abilityDecl, fromModule)
                        }
                    }
                    is ParsedPerformerDecl -> {
                        if (onImplHeaderLine &&
                            (word == "impl" || word == decl.effect.localName || word == decl.performerName)
                        ) {
                            val effectDecl = pm.decls.filterIsInstance<ParsedEffectDecl>()
                                .find { it.name == decl.effect.localName }
                                ?: result?.importedEffects?.get(decl.effect.localName)
                            val fromModule = result?.resolvedImports
                                ?.find { it.localName == decl.effect.localName && it.kind == ImportKind.EFFECT }
                                ?.modulePath
                            return formatEffectImplHover(decl, effectDecl, fromModule)
                        }
                    }
                    else -> {}
                }
            }
        }

        // Perform-handler hover: the cursor is on the performer name in
        // `perform Console in do …`. Without this, the typed-AST lookup below
        // resolves `Console` as a nullary constructor and shows a bare `{}`
        // type. Instead, show which effect(s) this performer handles and a
        // link to the impl. Gated on the word being immediately preceded by
        // `perform ` on the line (a comma-separated handler list also counts,
        // e.g. `perform A, B in …`).
        run {
            val beforeWord = line.substring(0, char0.coerceIn(0, line.length))
            val atHandlerPos = Regex("\\bperform\\b[^)]*$").containsMatchIn(beforeWord) &&
                !Regex("\\bin\\b").containsMatchIn(beforeWord.substringAfterLast("perform"))
            if (!atHandlerPos) return@run
            val hover = performerHover(state, result, word) ?: return@run
            return hover
        }

        // Qualified effect-op call hover: the cursor is on `Log` or `log` in
        // `Log.log { … }`. The typed-AST lookup below resolves the whole call to
        // its `{}` return type, hiding what the reader wants — the effect and
        // the op's signature. Detect the `<Effect>.<op>` shape textually and:
        //   • on the effect qualifier → the effect decl (name + ops + link),
        //   • on the op name          → that op's signature `op: P -> R <E>`.
        run {
            // Find the word's actual span around the cursor (which may sit in
            // the middle of the word). Then `beforeWord`/`after` are the text
            // strictly before/after the whole word on this line.
            val cur = char0.coerceIn(0, line.length)
            var ws = cur; while (ws > 0 && (line[ws - 1].isLetterOrDigit() || line[ws - 1] == '_')) ws--
            var we = cur; while (we < line.length && (line[we].isLetterOrDigit() || line[we] == '_')) we++
            val beforeWord = line.substring(0, ws)
            val after = line.substring(we)

            // Case A — cursor on the op name: preceded by `<Effect>.` on the line.
            val qualMatch = Regex("([A-Z][A-Za-z0-9_]*)\\.$").find(beforeWord)
            if (qualMatch != null) {
                val effectName = qualMatch.groupValues[1]
                val effectDecl = effectDeclByName(state, result, effectName)
                val op = effectDecl?.operations?.find { it.name == word }
                if (effectDecl != null && op != null) {
                    val row = if (op.effects.isNotEmpty()) " <${op.effects.joinToString(", ")}>" else ""
                    val ty = "${formatTypeAnnotation(op.paramType)} -> ${formatTypeAnnotation(op.returnType)}$row"
                    val link = state.resolveFileUri?.invoke(effectDecl.loc.file)?.let { u ->
                        "\n\nop of effect [`$effectName`]($u#L${effectDecl.loc.startLine},${effectDecl.loc.startCol})"
                    } ?: "\n\nop of effect `$effectName`"
                    return appendDoc("```mezze\n$word: $ty\n```" + link, op.doc, null)
                }
            }

            // Case B — cursor on the qualifier: `word` is a known effect OR
            // ability, immediately followed by `.<op>` on the line → its decl.
            if (Regex("^\\.[a-z_]").containsMatchIn(after)) {
                val effectDecl = effectDeclByName(state, result, word)
                if (effectDecl != null) {
                    val fromModule = result?.resolvedImports
                        ?.find { it.localName == word && it.kind == ImportKind.EFFECT }?.modulePath
                    return formatEffectHover(effectDecl, fromModule)
                }
                val abilityDecl = abilityDeclByName(state, result, word)
                if (abilityDecl != null) {
                    val fromModule = result?.resolvedImports
                        ?.find { it.localName == word && it.kind == ImportKind.ABILITY }?.modulePath
                    return formatAbilityHover(abilityDecl, fromModule)
                }
            }
        }

        // Helper to format type with aliases
        fun formatType(type: MezzeType): String {
            return prettyPrintTypeWithAliases(type, state.typeAliases)
        }

        // Helper to wrap in syntax-highlighted code block
        fun codeBlock(code: String): String = "```mezze\n$code\n```"

        // PRIORITY: Use TypedAST position-based lookup for precise hover
        // This finds the exact expression at the cursor position, including
        // subexpressions in lambda bodies, record fields, etc.
        val typedModule = result?.typedModule ?: state.lastSuccessful?.typedModule
        if (typedModule != null) {
            val line = line0 + 1  // LSP is 0-indexed, Loc is 1-indexed
            val col = char0 + 1

            // Cursor ON a match / if-let pattern binder (`val` in `Ok { val }`):
            // findAtPosition returns the enclosing match, so this resolves the
            // binder's OWN type from the scope walker. Only used in the `else`
            // branch below (cursor landed on the container) — a USE of the same
            // name lands on its TypedVar and keeps its precise use-site type.
            fun patternBinderHover(): String? =
                TypedAstScopeWalker.patternBindingAt(typedModule, line, col)
                    ?.takeIf { it.name == word }
                    ?.let { b ->
                        val kindLabel =
                            if (b.kind == TypedAstScopeWalker.ScopeBinding.Kind.IF_LET_BINDING) "if let" else "match"
                        codeBlock("$kindLabel ${b.name} : ${formatType(b.type)}")
                    }

            when (val lookup = TypedAstLookup.findAtPosition(typedModule, line, col)) {
                is TypedAstLookup.LookupResult.Expr -> {
                    val expr = lookup.expr
                    val typeStr = formatType(expr.type)
                    return when (expr) {
                        is com.mezze.language.typedast.TypedVar -> {
                            val abilityHover = result?.let { renderAbilityMethodHover(expr.name, it, state, expr.type) }
                            val hover = abilityHover ?: codeBlock("${expr.name} : $typeStr")
                            val (doc, meta) = resolveDoc(state, result, expr.name)
                            appendDoc(hover, doc, meta)
                        }
                        is com.mezze.language.typedast.TypedFieldAccess -> {
                            // A method-in-a-chain accessed as `.eq` keeps a
                            // polymorphic solved type (dispatch not yet a call),
                            // so pass the RECEIVER type too — renderAbilityMethodHover
                            // specializes the generic op by the receiver when the
                            // solved method type is still a var.
                            val abilityHover = result?.let {
                                renderAbilityMethodHover(expr.field, it, state, expr.type, expr.record.type)
                            }
                            abilityHover ?: codeBlock(".${expr.field} : $typeStr")
                        }
                        is com.mezze.language.typedast.TypedCall -> codeBlock("$typeStr")
                        // A cursor on a match / if-let pattern binder lands here
                        // (findAtPosition returns the enclosing match). Prefer the
                        // binder's own type over the match's result type (`{}`).
                        else -> patternBinderHover() ?: codeBlock(typeStr)
                    }
                }
                is TypedAstLookup.LookupResult.Binding -> {
                    // Effect / ability / type decl names: findAtPosition returns
                    // a Binding with a SYNTHETIC fresh VarType (there's no real
                    // value-level type for a decl name), which would render the
                    // meaningless `Log : 'a`. Route to the proper structural
                    // hover instead (`effect Log where { … }`).
                    val effectDeclHit = state.parsedModule?.decls
                        ?.filterIsInstance<ParsedEffectDecl>()?.find { it.name == lookup.name }
                        ?: result?.importedEffects?.get(lookup.name)
                    if (effectDeclHit != null) {
                        val fromModule = result?.resolvedImports
                            ?.find { it.localName == lookup.name && it.kind == ImportKind.EFFECT }?.modulePath
                        return formatEffectHover(effectDeclHit, fromModule)
                    }
                    val abilityDeclHit = state.parsedModule?.decls
                        ?.filterIsInstance<ParsedAbilityDecl>()?.find { it.name == lookup.name }
                        ?: result?.importedAbilities?.get(lookup.name)
                    if (abilityDeclHit != null) {
                        val fromModule = result?.resolvedImports
                            ?.find { it.localName == lookup.name && it.kind == ImportKind.ABILITY }?.modulePath
                        return formatAbilityHover(abilityDeclHit, fromModule)
                    }
                    val typeStr = formatType(lookup.type)
                    val (doc, meta) = resolveDoc(state, result, lookup.name)
                    // A synthetic VarType named after a type decl (e.g. `Log`)
                    // renders as a bare type var — show `type Log` instead.
                    if (lookup.type is MezzeType.VarType) {
                        val matchingDecl = state.parsedModule?.decls?.filterIsInstance<ParsedTypeDecl>()
                            ?.firstOrNull { it.name == lookup.name }
                        if (matchingDecl != null) {
                            // Show the aliased/wrapped type when possible so
                            // `type MyInt = Int` and `newtype UserId = Str`
                            // render usefully instead of a bare `type MyInt`.
                            val header = typeDeclHeader(lookup.name, matchingDecl.definition)
                            return appendDoc(codeBlock(header), doc, meta)
                        }
                    }
                    return appendDoc(codeBlock("${lookup.name} : $typeStr"), doc, meta)
                }
                is TypedAstLookup.LookupResult.NotFound -> {} // Fall through to word-based lookup
            }

            // Local-binding hover (#124): when the cursor is on a NAME token —
            // either a let-binding's declared name (`let x = …` ← here), or
            // the param name in `{ x } -> …`, or a use of `x` further down —
            // search the scope at this position for a matching local. If we
            // get a hit, show its type; otherwise fall through to the
            // top-level / import paths below.
            val localScope = TypedAstScopeWalker.bindingsAtPosition(typedModule, line, col)
            // Iterate in reverse so innermost (shadowing) wins.
            val localHit = localScope.asReversed().firstOrNull { it.name == word }
            if (localHit != null) {
                val kindLabel = when (localHit.kind) {
                    TypedAstScopeWalker.ScopeBinding.Kind.LAMBDA_PARAM -> "param"
                    TypedAstScopeWalker.ScopeBinding.Kind.LET_LOCAL -> "let"
                    TypedAstScopeWalker.ScopeBinding.Kind.MATCH_BINDING -> "match"
                    TypedAstScopeWalker.ScopeBinding.Kind.IF_LET_BINDING -> "if let"
                }
                return codeBlock("$kindLabel ${localHit.name} : ${formatType(localHit.type)}")
            }
        }

        // If we have type check result, use it for full type info
        if (result != null) {
            // 1. Look up top-level bindings with inferred types via TypedModule
            val typedBinding = result.typedModule?.decls
                ?.filterIsInstance<com.mezze.language.typedast.TypedLetDecl>()
                ?.find { it.name == word }

            if (typedBinding != null) {
                // Prefer the user's explicit annotation when present: it
                // reflects intent and stays stable when the body has a type
                // error (in which case the inferred scheme is the wrong type).
                val annotated = state.parsedModule?.decls
                    ?.filterIsInstance<ParsedLetDecl>()
                    ?.firstOrNull { it.isSimpleBinding && it.variableName == word }
                    ?.typeAnnotation
                    ?.let {
                        try { com.mezze.language.typechecker.util.TypeAnnotationConverter.convert(it) }
                        catch (_: Exception) { null }
                    }
                val bindingType = annotated ?: typedBinding.scheme.type
                if (bindingType !is MezzeType.VarType) {
                    val doc = findDocForDecl(state.parsedModule, word)
                    val meta = findMetadataForDecl(state.parsedModule, word)
                    // Surface the scheme's ability constraints (`with Eq 'a`) —
                    // they're part of the signature but the bare type doesn't
                    // show them. Only when we didn't fall back to the annotation
                    // (which already carries any `with` clause verbatim).
                    val constraints = if (annotated == null)
                        renderSchemeConstraints(state, typedBinding.scheme, result.typedModule)
                    else ""
                    return appendDoc(codeBlock("$word : ${formatType(bindingType)}$constraints"), doc, meta)
                }
            }

            // 2. Look up in resolved imports
            val resolvedImport = result.resolvedImports.find { it.localName == word }
            if (resolvedImport != null) {
                val typeStr = formatType(resolvedImport.type)
                val kindPrefix = when (resolvedImport.kind) {
                    ImportKind.EFFECT -> "effect "
                    ImportKind.ABILITY -> "ability "
                    ImportKind.TYPE -> "type "
                    ImportKind.PERFORMER -> "performer "
                    ImportKind.FUNCTION -> ""
                }
                val fromModule = if (resolvedImport.modulePath.isNotEmpty()) {
                    "\n\n*from* `${resolvedImport.modulePath}`"
                } else ""
                val (doc, meta) = resolveDoc(state, result, word)
                return appendDoc(codeBlock("$kindPrefix$word : $typeStr") + fromModule, doc, meta)
            }
        }

        // Fallback: use parsedModule for basic info (even when type check failed)
        val parsedModule = state.parsedModule

        // 3. Look up type declarations from parsed module
        val typeDecl = parsedModule?.decls?.filterIsInstance<ParsedTypeDecl>()?.find { it.name == word }
        if (typeDecl != null) {
            val header = typeDeclHeader(word, typeDecl.definition)
            return appendDoc(codeBlock(header), typeDecl.docComment, typeDecl.metadata)
        }

        // 4-5. Look up ability declarations from parsed module or imports
        val abilityDecl = parsedModule?.decls?.filterIsInstance<ParsedAbilityDecl>()?.find { it.name == word }
            ?: result?.importedAbilities?.get(word)

        if (abilityDecl != null) {
            val fromModule = result?.resolvedImports
                ?.find { it.localName == word && it.kind == ImportKind.ABILITY }
                ?.modulePath
            return formatAbilityHover(abilityDecl, fromModule)
        }

        // 5b. Look up effect declarations from parsed module or imports
        val effectDecl = parsedModule?.decls?.filterIsInstance<ParsedEffectDecl>()?.find { it.name == word }
            ?: result?.importedEffects?.get(word)

        if (effectDecl != null) {
            val fromModule = result?.resolvedImports
                ?.find { it.localName == word && it.kind == ImportKind.EFFECT }
                ?.modulePath
            return formatEffectHover(effectDecl, fromModule)
        }

        // 7. Look up let bindings from parsed module
        val letDecl = parsedModule?.decls?.filterIsInstance<ParsedLetDecl>()
            ?.filter { it.isSimpleBinding }
            ?.find { it.variableName == word }

        if (letDecl != null) {
            return appendDoc(codeBlock("let $word = ..."), letDecl.docComment, letDecl.metadata)
        }

        return null
    }

    private fun renderAbilityMethodHover(
        methodName: String,
        result: TypeCheckResult,
        state: Context,
        solvedType: MezzeType? = null,
        receiverType: MezzeType? = null,
    ): String? {
        fun codeBlock(code: String): String = "```mezze\n$code\n```"

        // At a concrete call site the method's solved type is specialized to the
        // receiver (`to_str : { it: Int } -> Str`), which is far more useful than
        // the ability decl's generic `{ it: 'a } -> Str`. Prefer it whenever the
        // solved type is a fully-resolved FunctionType (no leftover tyvars) — a
        // still-polymorphic type means dispatch wasn't pinned yet, so fall back
        // to the generic decl signature below.
        val specialized = solvedType
            ?.takeIf { it is MezzeType.FunctionType && !typeHasVar(it) }
            ?.let { prettyPrintTypeWithAliases(it, state.typeAliases) }

        // When the method's OWN solved type is still polymorphic (e.g. `.eq` in a
        // chain, accessed before the call pins dispatch), we can still specialize
        // via the RECEIVER if it's concrete — substitute the ability's receiver
        // type param with the receiver in the generic op annotation.
        val concreteReceiver = receiverType
            ?.takeIf { it !is MezzeType.VarType && !typeHasVar(it) }

        // 1. User-defined / file-level abilities
        val localAbilities = state.parsedModule?.decls?.filterIsInstance<ParsedAbilityDecl>() ?: emptyList()
        val candidates = mutableListOf<Pair<ParsedAbilityDecl, ParsedAbilityOp>>()
        for (ability in result.importedAbilities.values + localAbilities) {
            ability.operations.firstOrNull { it.name == methodName }?.let {
                candidates.add(ability to it)
            }
        }
        // 2. Stdlib + transitively-imported abilities — the prelude strips
        // bindings/types/abilities from its returned context, so we have
        // to query the project store for ABILITY decls and look at their
        // operations directly. Same pattern as dot-completion step 4.
        if (candidates.isEmpty()) {
            run {
                try {
                    for (typedAbility in (state.storeAbilityDecls?.invoke() ?: emptyList())) {
                        val typedOp = typedAbility.operations.firstOrNull { it.name == methodName }
                            ?: continue
                        // Prefer the specialized call-site type; else specialize
                        // the generic op type by the concrete receiver; else the
                        // ability decl's generic op signature verbatim.
                        val rv = typedAbility.receiverTypeParam
                        val bySig = if (specialized != null) specialized
                            else if (concreteReceiver != null && rv != null) {
                                val p = replaceNamedVar(typedOp.paramType, rv, concreteReceiver)
                                val r = replaceNamedVar(typedOp.returnType, rv, concreteReceiver)
                                "${prettyPrintTypeWithAliases(p, state.typeAliases)} -> ${prettyPrintTypeWithAliases(r, state.typeAliases)}"
                            } else {
                                "${prettyPrintTypeWithAliases(typedOp.paramType, state.typeAliases)} -> " +
                                    prettyPrintTypeWithAliases(typedOp.returnType, state.typeAliases)
                            }
                        return codeBlock("${typedAbility.name}.${typedOp.name} : $bySig")
                    }
                } catch (_: Throwable) { /* store may not be open */ }
            }
        }
        if (candidates.isEmpty()) return null

        // Render the first candidate. If multiple abilities share a method
        // name (rare — e.g. NumAdd.add vs Seq.add), the receiver type at
        // dispatch picks one; we don't have that info here, so just show
        // the first match. Listing all could be a follow-up.
        val (ability, op) = candidates.first()
        val bySig = specialized
            ?: (concreteReceiver?.let { specializeAbilityOp(op, ability.receiverTypeParam, it, state.typeAliases) })
            ?: "${formatTypeAnnotation(op.paramType)} -> ${formatTypeAnnotation(op.returnType)}"
        val sig = "${ability.name}.${op.name} : $bySig"
        val doc = op.doc?.takeIf { it.isNotBlank() }?.let { "\n\n$it" } ?: ""
        return codeBlock(sig) + doc
    }

    /**
     * Format hover info for an ability declaration, showing all operations.
     */
    private fun formatAbilityHover(decl: ParsedAbilityDecl, fromModule: String?): String {
        fun codeBlock(code: String): String = "```mezze\n$code\n```"

        // Normalize a type-param token to exactly one leading apostrophe
        // (parser may store it with or without the `'`).
        fun tv(name: String) = "'" + name.trimStart('\'')
        // `typeParams` already includes the receiver (parser appends it), so
        // render `ability Name <extra generics> for 'receiver` without
        // repeating the receiver in both places.
        val receiver = decl.receiverTypeParam
        val generics = decl.typeParams.filter { it.trimStart('\'') != receiver?.trimStart('\'') }
        val genericsStr = if (generics.isNotEmpty()) " " + generics.joinToString(" ") { tv(it) } else ""
        val forClause = receiver?.let { " for ${tv(it)}" } ?: ""
        val header = "ability ${decl.name}$genericsStr$forClause"

        val ops = decl.operations.joinToString("\n") { op ->
            "  ${op.name}: ${formatTypeAnnotation(op.paramType)} -> ${formatTypeAnnotation(op.returnType)}"
        }

        val body = if (ops.isNotEmpty()) " where {\n$ops\n}" else ""
        val moduleInfo = if (!fromModule.isNullOrEmpty()) "\n\n*from* `$fromModule`" else ""

        return appendDoc(codeBlock("$header$body") + moduleInfo, decl.docComment, decl.metadata)
    }

    /**
     * Format hover info for an effect declaration, showing all operations.
     */
    private fun formatEffectHover(decl: ParsedEffectDecl, fromModule: String?): String {
        fun codeBlock(code: String): String = "```mezze\n$code\n```"

        val header = "effect ${decl.name}"

        val ops = decl.operations.joinToString("\n") { op ->
            "  ${op.name}: ${formatTypeAnnotation(op.paramType)} -> ${formatTypeAnnotation(op.returnType)}"
        }

        val body = if (ops.isNotEmpty()) " where {\n$ops\n}" else ""
        val moduleInfo = if (!fromModule.isNullOrEmpty()) "\n\n*from* `$fromModule`" else ""

        return appendDoc(codeBlock("$header$body") + moduleInfo, decl.docComment, decl.metadata)
    }

    /**
     * Hover for an ability impl (`impl Eq for Int where { … }`). Shows which
     * ability it implements + the target type, each op with its type (the impl's
     * own annotation when present, else the ability decl's op signature so the
     * method's shape is always visible), and a link to where the ability is
     * defined. `abilityDecl` is the resolved `ability` decl (may be null if the
     * ability is imported / unresolved — then op types fall back to the impl's).
     */
    private fun formatAbilityImplHover(
        decl: com.mezze.language.parsedast.ParsedImplAbilityDecl,
        abilityDecl: ParsedAbilityDecl?,
        fromModule: String?,
    ): String {
        fun codeBlock(code: String): String = "```mezze\n$code\n```"
        val abilityName = decl.abilityName ?: "<ability>"
        val targets = decl.targetTypes.joinToString(", ") { formatTypeAnnotation(it) }
        val header = "impl $abilityName" + if (targets.isNotEmpty()) " for $targets" else ""

        // Prefer the ability decl's op signatures (always typed); fall back to the
        // impl op's own annotation.
        val abilityOps = abilityDecl?.operations?.associateBy { it.name } ?: emptyMap()
        val ops = decl.operations.joinToString("\n") { op ->
            val sig = abilityOps[op.name]
            val param = op.paramType ?: sig?.paramType
            val ret = op.returnType ?: sig?.returnType
            val ty = if (param != null && ret != null)
                "${formatTypeAnnotation(param)} -> ${formatTypeAnnotation(ret)}" else "…"
            "  ${op.name}: $ty"
        }
        val body = if (ops.isNotEmpty()) " where {\n$ops\n}" else ""
        val link = fromModule?.takeIf { it.isNotEmpty() }
            ?.let { "\n\nability `$abilityName` defined in `$it`" }
            ?: "\n\nimplements ability `$abilityName`"
        return appendDoc(codeBlock("$header$body") + link, decl.docComment, decl.metadata)
    }

    /**
     * Hover for an effect impl / performer (`impl State for Counter where { … }`).
     * Same shape as the ability-impl hover but for effects.
     */
    private fun formatEffectImplHover(
        decl: ParsedPerformerDecl,
        effectDecl: ParsedEffectDecl?,
        fromModule: String?,
    ): String {
        fun codeBlock(code: String): String = "```mezze\n$code\n```"
        val effectName = decl.effect.localName
        val header = "impl $effectName for ${decl.performerName}"

        val effectOps = effectDecl?.operations?.associateBy { it.name } ?: emptyMap()
        val ops = decl.operations.joinToString("\n") { op ->
            val sig = effectOps[op.name]
            val param = op.paramType ?: sig?.paramType
            val ret = op.returnType ?: sig?.returnType
            val ty = if (param != null && ret != null)
                "${formatTypeAnnotation(param)} -> ${formatTypeAnnotation(ret)}" else "…"
            "  ${op.name}: $ty"
        }
        val body = if (ops.isNotEmpty()) " where {\n$ops\n}" else ""
        val link = fromModule?.takeIf { it.isNotEmpty() }
            ?.let { "\n\neffect `$effectName` defined in `$it`" }
            ?: "\n\nimplements effect `$effectName`"
        return appendDoc(codeBlock("$header$body") + link, decl.docComment, decl.metadata)
    }

    private fun performerHover(state: Context, result: TypeCheckResult?, name: String): String? {
        fun codeBlock(code: String): String = "```mezze\n$code\n```"
        val pm = state.parsedModule

        fun effectDeclFor(effectName: String): ParsedEffectDecl? = effectDeclByName(state, result, effectName)

        // Clickable markdown link to the effect decl; falls back to the effect
        // reference in the impl header (`perf.effect.loc`, always in the impl's
        // own source) if the decl has no usable loc.
        fun effectLink(perf: ParsedPerformerDecl): String {
            val effectName = perf.effect.localName
            val localDecl = effectDeclFor(effectName)
            val loc = localDecl?.loc?.takeIf { !it.file.isNullOrEmpty() } ?: perf.effect.loc
            // A locally-declared effect (found in THIS document's parsed module)
            // links to the current document directly — its loc.file may not
            // resolve on disk (unsaved buffer), and resolveExistingFileUri would
            // then drop the link to plain text.
            val defUri = if (localDecl != null) state.selfUri else state.resolveFileUri?.invoke(loc.file)
            if (defUri != null) {
                return "[`$effectName`]($defUri#L${loc.startLine},${loc.startCol})"
            }
            return "`$effectName`"
        }

        // Render one impl as Mezze: `impl <Effect> for <name> where { ops }`.
        // Each op shows `op: Param -> Ret`, preferring the impl's own annotation
        // else the effect decl's op sig (imported impl ops carry null types).
        fun renderImpl(perf: ParsedPerformerDecl): String {
            val effectName = perf.effect.localName
            val sigByName = effectDeclFor(effectName)?.operations?.associateBy { it.name } ?: emptyMap()
            val opLines = perf.operations.joinToString("\n") { op ->
                val sig = sigByName[op.name]
                val param = op.paramType ?: sig?.paramType
                val ret = op.returnType ?: sig?.returnType
                if (param != null && ret != null)
                    "  ${op.name}: ${formatTypeAnnotation(param)} -> ${formatTypeAnnotation(ret)}"
                else "  ${op.name}"
            }
            return if (opLines.isBlank()) "impl $effectName for $name"
                else "impl $effectName for $name where {\n$opLines\n}"
        }

        // Local impls whose target is this name.
        val localImpls = pm?.decls?.filterIsInstance<ParsedPerformerDecl>()
            ?.filter { it.performerName == name } ?: emptyList()
        val perfs = if (localImpls.isNotEmpty()) localImpls
            // Imported performer. Performers live in TypeCheckResult.importedImpls
            // (keyed by name), NOT resolvedImports — the ResolvedImport builder
            // has no PERFORMER case, so a `use …::{ Console }` performer is
            // absent from resolvedImports entirely.
            else result?.importedImpls?.get(name)?.let { listOf(it) } ?: emptyList()
        if (perfs.isEmpty()) return null

        val body = perfs.joinToString("\n\n") { renderImpl(it) }
        val links = perfs
            .distinctBy { it.effect.localName }
            .joinToString("\n") { "handles effect ${effectLink(it)}" }
        return codeBlock(body) + "\n\n$links"
    }

    /**
     * Resolve an effect decl by name, from anywhere it might be reachable:
     * local decls, then by-name + transitively-loaded effects (allEffects),
     * then — for an effect whose ONLY its performer was imported (`Log` behind
     * `use …::{ Console }`, so it's in neither map) — a scan of every parsed
     * module. The last fallback recovers op signatures + decl loc.
     */
    fun effectDeclByName(state: Context, result: TypeCheckResult?, effectName: String): ParsedEffectDecl? =
        state.parsedModule?.decls?.filterIsInstance<ParsedEffectDecl>()?.find { it.name == effectName }
            ?: result?.allEffects?.get(effectName)
            ?: com.mezze.language.typechecker.ModuleResolver.getAllParsedModules().values
                .firstNotNullOfOrNull { m ->
                    m.decls.filterIsInstance<ParsedEffectDecl>().find { it.name == effectName }
                }

    /** Ability-decl analogue of [effectDeclByName] (local → imported → scan). */
    fun abilityDeclByName(state: Context, result: TypeCheckResult?, abilityName: String): ParsedAbilityDecl? =
        state.parsedModule?.decls?.filterIsInstance<ParsedAbilityDecl>()?.find { it.name == abilityName }
            ?: result?.importedAbilities?.get(abilityName)
            ?: com.mezze.language.typechecker.ModuleResolver.getAllParsedModules().values
                .firstNotNullOfOrNull { m ->
                    m.decls.filterIsInstance<ParsedAbilityDecl>().find { it.name == abilityName }
                }

    /**
     * Resolve doc + metadata for `name` at hover time, walking from
     * narrowest to widest scope:
     *
     *   1. The user's own parsed module (local decls).
     *   2. The parsed module the user imported `name` from — looked up in
     *      ModuleTypeChecker's in-memory cache. This honors the *specific*
     *      import source, so same-named symbols across modules (`min` in
     *      std::int vs std::collections::seq) resolve to the right doc,
     *      and modules StdlibPrecompiler never indexed (effects, codec,
     *      etc.) work without a build-time re-walk.
     *   3. The on-disk store, for cold-start lookups when nothing has
     *      been parsed yet.
     */
    private fun resolveDoc(
        state: Context,
        result: TypeCheckResult?,
        name: String
    ): Pair<String?, ParsedMetadata?> {
        // (1) user's file
        findDocForDecl(state.parsedModule, name)?.let { doc ->
            return doc to findMetadataForDecl(state.parsedModule, name)
        }
        // (2) imported module's parsed AST
        val importPath = result?.resolvedImports
            ?.find { it.localName == name }
            ?.modulePath
            ?.takeIf { it.isNotEmpty() }
        if (importPath != null) {
            val parsed = com.mezze.language.typechecker.module.ModuleTypeChecker
                .getAllCachedModules()[importPath]?.parsedModule
            if (parsed != null) {
                findDocForDecl(parsed, name)?.let { doc ->
                    return doc to findMetadataForDecl(parsed, name)
                }
            }
        }
        // (3) on-disk store
        return state.docFromStore?.invoke(name) to null
    }

    /**
     * Append a doc comment and deprecation warning to hover markdown.
     */
    private fun appendDoc(hover: String, doc: String?, metadata: ParsedMetadata? = null): String {
        val parts = mutableListOf(hover)

        // Deprecation warning
        if (metadata?.isDeprecated == true) {
            val deprecation = buildString {
                append("**Deprecated**")
                metadata.since?.let { append(" since $it") }
                metadata.note?.let { append(" — $it") }
            }
            parts.add(deprecation)
        }

        // Doc comment — normalized so multi-block markdown (headings, lists,
        // fenced code, tables) renders correctly in the client's renderer.
        if (!doc.isNullOrBlank()) {
            parts.add(renderDocMarkdown(doc))
        }

        return parts.joinToString("\n\n---\n\n")
    }

    /**
     * Look up the doc comment for a named declaration from the current file's ParsedModule.
     * Returns the doc string or null.
     */
    fun findDocForDecl(parsedModule: ParsedModule?, name: String): String? {
        if (parsedModule == null) return null
        for (decl in parsedModule.decls) {
            val declName = when (decl) {
                is ParsedLetDecl -> if (decl.isSimpleBinding) decl.variableName else null
                is ParsedTypeDecl -> decl.name
                is ParsedAbilityDecl -> decl.name
                is ParsedEffectDecl -> decl.name
                is ParsedPerformerDecl -> decl.performerName
                else -> null
            }
            if (declName == name) return decl.docComment
        }
        return null
    }

    /**
     * Find the metadata for a named declaration from the current file's ParsedModule.
     */
    private fun findMetadataForDecl(parsedModule: ParsedModule?, name: String): ParsedMetadata? {
        if (parsedModule == null) return null
        for (decl in parsedModule.decls) {
            val declName = when (decl) {
                is ParsedLetDecl -> if (decl.isSimpleBinding) decl.variableName else null
                is ParsedTypeDecl -> decl.name
                is ParsedAbilityDecl -> decl.name
                is ParsedEffectDecl -> decl.name
                is ParsedPerformerDecl -> decl.performerName
                else -> null
            }
            if (declName == name) return decl.metadata
        }
        return null
    }

    /**
     * Normalize a `##`-sourced doc comment (stored as `\n`-joined lines) into
     * well-formed markdown. The parser joins doc lines with a single `\n`, but
     * markdown needs a blank line between BLOCK elements — otherwise a heading
     * runs into the following paragraph, a list glues to the text above it, and
     * a fenced code block never opens. We insert `\n\n` at block boundaries
     * while keeping consecutive lines of the SAME block (list items, code-fence
     * interior, table rows, paragraph soft-wraps) together.
     *
     * Client-agnostic: emits standard CommonMark/GFM text, so it renders the
     * same in VS Code (markdown-it), Zed, and any other LSP client.
     */
    fun renderDocMarkdown(doc: String): String {
        val lines = doc.split("\n")
        val out = StringBuilder()
        var inFence = false
        var prevKind: DocLineKind? = null

        for ((i, raw) in lines.withIndex()) {
            val line = raw
            val trimmed = line.trimStart()
            val kind = classifyDocLine(trimmed, inFence)

            // A fence toggles code-block mode; the toggle line itself is FENCE.
            if (trimmed.startsWith("```")) inFence = !inFence

            if (i > 0) {
                // Blank line between two different block kinds, or when either
                // side is a heading/fence/blockquote/table/hr boundary. Runs of
                // the same block kind (LIST↔LIST, TEXT↔TEXT inside a paragraph,
                // CODE↔CODE inside a fence) stay on adjacent lines.
                val needBlank = when {
                    inFence || prevKind == DocLineKind.CODE -> false        // inside a fence: keep verbatim
                    kind == DocLineKind.BLANK || prevKind == DocLineKind.BLANK -> false // author already spaced it
                    kind == prevKind && kind == DocLineKind.LIST -> false   // adjacent list items
                    kind == prevKind && kind == DocLineKind.TABLE -> false  // adjacent table rows
                    kind == prevKind && kind == DocLineKind.TEXT -> false   // paragraph soft-wrap
                    else -> true
                }
                out.append(if (needBlank) "\n\n" else "\n")
            }
            out.append(line)
            prevKind = kind
        }
        return out.toString()
    }

    /**
     * Render a type scheme's ability constraints as a ` with Eq 'a, Ord 'b`
     * suffix (empty string when there are none). Ability names come from the
     * constraint's hash — resolved first against the current typed module's
     * ability decls, then the store's ability index (covers stdlib). A
     * constraint whose ability we can't name is skipped rather than shown as a
     * raw hash.
     */
    private fun renderSchemeConstraints(
        state: Context,
        scheme: com.mezze.language.typedast.TypeScheme,
        typedModule: com.mezze.language.typedast.TypedModule?,
    ): String {
        if (scheme.abilityConstraints.isEmpty()) return ""
        val nameByHash = HashMap<Any, String>()
        typedModule?.abilityDecls?.forEach { nameByHash[it.hash] = it.name }
        try {
            state.storeAbilityDecls?.invoke()?.forEach {
                nameByHash.putIfAbsent(it.hash, it.name)
            }
        } catch (_: Exception) {}

        val rendered = scheme.abilityConstraints.mapNotNull { c ->
            val name = nameByHash[c.abilityHash] ?: return@mapNotNull null
            val args = if (c.typeArgs.isNotEmpty())
                " " + c.typeArgs.joinToString(" ") { prettyPrintType(it) }
            else ""
            "$name$args"
        }
        return if (rendered.isEmpty()) "" else " with ${rendered.joinToString(", ")}"
    }

    /**
     * Replace every VarType named [varName] in [type] with [replacement],
     * recursively. Used to specialize an ability op's generic annotation to a
     * concrete receiver — e.g. `Eq for 'a`'s `eq : { it: 'a, other: 'a } -> Bool`
     * becomes `{ it: Int, other: Int } -> Bool` when the receiver is `Int`.
     */
    private fun replaceNamedVar(type: MezzeType, varName: String, replacement: MezzeType): MezzeType {
        val bare = varName.trimStart('\'')
        if (type is MezzeType.VarType && type.variable.name.trimStart('\'') == bare) return replacement
        return type.transformChildren { replaceNamedVar(it, varName, replacement) }
    }

    /**
     * Specialize an ability op's `param -> return` signature to a concrete
     * receiver [receiver] by substituting the ability's receiver type param.
     * Returns null if the annotation can't be converted (falls back to generic).
     */
    fun specializeAbilityOp(
        op: ParsedAbilityOp,
        receiverTypeParam: String?,
        receiver: MezzeType,
        aliases: Map<String, TypeAliasInfo>,
    ): String? {
        if (receiverTypeParam == null) return null
        return try {
            val conv = com.mezze.language.typechecker.util.TypeAnnotationConverter
            val param = replaceNamedVar(conv.convert(op.paramType), receiverTypeParam, receiver)
            val ret = replaceNamedVar(conv.convert(op.returnType), receiverTypeParam, receiver)
            "${prettyPrintTypeWithAliases(param, aliases)} -> ${prettyPrintTypeWithAliases(ret, aliases)}"
        } catch (_: Exception) { null }
    }

    /** True if [type] still contains any unresolved type variable. */
    private fun typeHasVar(type: MezzeType): Boolean =
        com.mezze.language.ast.collectTypeVarsInOrder(type).isNotEmpty()


    /**
     * Extract the word at a character position in a line.
     */
    fun extractWordAt(line: String, character: Int): String? {
        if (character > line.length) return null

        var start = character
        var end = character

        // Find start of word
        while (start > 0 && isIdentifierChar(line[start - 1])) {
            start--
        }

        // Find end of word
        while (end < line.length && isIdentifierChar(line[end])) {
            end++
        }

        return if (start < end) line.substring(start, end) else null
    }

    /**
     * True if `character` sits at or after the start of a line comment on
     * `line`. Mezze line comments begin with `#` (covering `#`, `##` doc, and
     * `#{ }` metadata). A `#` inside a double-quoted string doesn't start a
     * comment, so we track string state while scanning. Used to suppress hover
     * (and could gate other position features) over comment prose.
     */
    fun isInsideLineComment(line: String, character: Int): Boolean {
        var inString = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '\\' && inString -> { i++ }  // skip escaped char in a string
                c == '"' -> inString = !inString
                c == '#' && !inString -> return character >= i
            }
            i++
        }
        return false
    }

    fun isIdentifierChar(c: Char): Boolean {
        return c.isLetterOrDigit() || c == '_' || c == '\''
    }

    /**
     * Format a ParsedTypeAnnotation to a readable string.
     */
    fun formatTypeAnnotation(type: ParsedTypeAnnotation): String {
        return when (type) {
            is ParsedNamedType -> type.name
            is ParsedRecordType -> {
                if (type.entries.isEmpty()) "{}"
                else "{ ${type.entries.joinToString(", ") { "${it.name}: ${formatTypeAnnotation(it.type)}" }} }"
            }
            is ParsedFunctionType -> {
                val constraints = if (type.abilityConstraints.isNotEmpty()) {
                    " with ${type.abilityConstraints.joinToString(", ") { c ->
                        c.abilityName + if (c.typeArgs.isNotEmpty()) " ${c.typeArgs.joinToString(" ") { formatTypeAnnotation(it) }}" else ""
                    }}"
                } else ""
                "${formatTypeAnnotation(type.paramType)}$constraints -> ${formatTypeAnnotation(type.returnType)}"
            }
            is ParsedTypeApp -> {
                if (type.args.isEmpty()) type.constructor
                else "${type.constructor} ${type.args.joinToString(" ") { formatTypeAnnotation(it) }}"
            }
            is ParsedSeqType -> "[${formatTypeAnnotation(type.elementType)}]"
            is ParsedVariantType -> "| ${type.constructors.joinToString(", ") { it.name }} |"
            is ParsedQualifiedType -> "${type.modulePath}::${type.typeName}"
        }
    }

    private fun classifyDocLine(trimmed: String, inFence: Boolean): DocLineKind = when {
        inFence -> DocLineKind.CODE
        trimmed.isEmpty() -> DocLineKind.BLANK
        trimmed.startsWith("```") -> DocLineKind.FENCE
        trimmed.startsWith("#") -> DocLineKind.HEADING
        trimmed.matches(Regex("""^([-*+]\s|\d+[.)]\s).*""")) -> DocLineKind.LIST
        trimmed.startsWith(">") -> DocLineKind.QUOTE
        trimmed.startsWith("|") -> DocLineKind.TABLE
        trimmed.matches(Regex("""^(-{3,}|\*{3,}|_{3,})$""")) -> DocLineKind.HR
        else -> DocLineKind.TEXT
    }

    private enum class DocLineKind { HEADING, LIST, FENCE, CODE, QUOTE, TABLE, HR, BLANK, TEXT }

    /**
     * A type declaration's header, as it would be written in source.
     *
     * Records and variants used to fall through to a bare `type Money`, which
     * only restates the name the reader is already pointing at. Exhaustive over
     * [ParsedTypeDef] — a new definition kind must decide what to show here.
     */
    fun typeDeclHeader(name: String, def: ParsedTypeDef): String = when (def) {
        is ParsedRecordDef -> {
            val fields = def.fields.joinToString(", ") {
                "${it.name}: ${formatTypeAnnotation(it.type)}"
            }
            val body = if (def.isOpen) {
                if (def.fields.isEmpty()) "{ ... }" else "{ $fields, ... }"
            } else "{ $fields }"
            "type $name = $body"
        }
        is ParsedVariantDef -> {
            // Pipes delimit the whole list; the cases inside are comma-separated.
            val ctors = def.constructors.joinToString(", ") { c ->
                val payload = c.dataType?.let { " ${formatTypeAnnotation(it)}" } ?: ""
                "${c.name}$payload"
            }
            val body = if (def.isOpen) "| $ctors, ... |" else "| $ctors |"
            "type $name = $body"
        }
        is ParsedNewtypeDef -> "newtype $name = ${formatTypeAnnotation(def.wrappedType)}"
        is ParsedTypeAliasDef -> "type $name = ${formatTypeAnnotation(def.aliasedType)}"
    }
}
