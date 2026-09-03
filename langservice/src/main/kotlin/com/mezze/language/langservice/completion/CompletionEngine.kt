package com.mezze.language.langservice.completion

import com.mezze.language.langservice.lookup.TypedAstLookup
import com.mezze.language.langservice.lookup.TypedAstScopeWalker
import com.mezze.language.langservice.hover.HoverInfo

import com.mezze.language.ast.*
import com.mezze.language.ast.prettyPrintTypeWithAliases
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.TypeCheckResult
import com.mezze.language.typechecker.ImportKind

/**
 * Transport-neutral completion engine. Translated verbatim from the LSP's old
 * `completion()` cluster, retargeted at [CompletionContext] / [CompletionCandidate]
 * so it never mentions lsp4j. The LSP adapter turns each [CompletionCandidate]
 * back into an lsp4j `CompletionItem`; the CLI/WASM front-ends adapt them their
 * own way.
 *
 * `complete(ctx)` reproduces the old dispatch EXACTLY (every short-circuit,
 * sortText string, kind, and snippet flag preserved).
 */
object CompletionEngine {

    fun complete(ctx: CompletionContext): List<CompletionCandidate> {
        val line = ctx.line
        val character = ctx.character

        ctx.log("[completion] request uri=${ctx.uri.substringAfterLast('/')} pos=$line:$character")

        // No code completion inside a comment — a `#` note or `##` doc line
        // is prose, so suggesting identifiers/keywords there is noise. Mirror
        // the hover guard (isInsideLineComment is string-aware, so `#` inside
        // a string literal still completes).
        run {
            val lines = ctx.content.lines()
            val curLine = lines.getOrNull(line)
            if (curLine != null && HoverInfo.isInsideLineComment(curLine, character)) {
                return emptyList()
            }
        }

        val items = mutableListOf<CompletionCandidate>()

        // Detect dot-context BEFORE asking getDotCompletions, so we can
        // short-circuit when the dot completions are empty (e.g. the
        // receiver type couldn't be inferred). Without this, the
        // fallback paths below (`getStdlibCompletions`, bindings,
        // keywords) get a shot at matching the partial method name
        // after the dot — and happily suggest things like
        // `use std::int::{to_string, abs}` for `name.to_`, which then
        // inserts the entire `use ...` blob over the user's cursor.
        val isDotContext = run {
            val lines = ctx.content.lines()
            if (line >= lines.size) false
            else {
                val before = lines[line].substring(0, minOf(character, lines[line].length))
                Regex("""\.\w*$""").containsMatchIn(before)
            }
        }

        // Import-path context: the cursor is inside a `use …` line, before
        // any `::{ … }` selective block. Complete module paths from the
        // store's registered modules. Checked before dot/keyword paths so
        // typing `use std::se` never falls through to binding/keyword
        // suggestions.
        val useCompletions = getImportPathCompletions(ctx, line, character)
        if (useCompletions != null) {
            return useCompletions
        }

        // Ability/effect NAME in a `with …` constraint or `<…>` effect row.
        val abilityEffectNames = getAbilityEffectNameCompletions(ctx, line, character)
        if (abilityEffectNames != null) {
            return abilityEffectNames
        }

        // Op names at a field-start position inside an impl body.
        val implOps = getImplBodyOpCompletions(ctx, line, character)
        if (implOps != null && implOps.isNotEmpty()) {
            return implOps
        }

        val dotCompletions = getDotCompletions(ctx, line, character)

        if (dotCompletions.isNotEmpty()) {
            items.addAll(dotCompletions)
            return items
        } else if (isDotContext) {
            // Dot context but no candidates — return empty rather than
            // falling through to keyword/binding/stdlib paths whose
            // matches would be applied to the post-dot range, polluting
            // the editor with garbage suggestions.
            return emptyList()
        }

        // Check if we're inside a match expression (after 'match X is')
        val matchCompletions = getMatchPatternCompletions(ctx, line, character)
        if (matchCompletions.isNotEmpty()) {
            items.addAll(matchCompletions)
            return items
        }

        // Check if we're inside a function call (after '{')
        val argCompletions = getFunctionArgumentCompletions(ctx, line, character)

        if (argCompletions.isNotEmpty()) {
            // If we're completing function arguments, prioritize those
            items.addAll(argCompletions)
        } else {
            // Standard completions
            // Add keywords — gated by whether we're at a fresh top-level
            // position. Top-level keywords only make sense as the start of a
            // new decl; surfacing them mid-expression spams the menu.
            val atTopLevel = isAtTopLevelStart(ctx, line, character)
            items.addAll(getKeywordCompletions(atTopLevel))

            // Add bindings from current document (top-level + imports)
            items.addAll(getBindingCompletions(ctx))

            // Add local variables in scope at this position
            items.addAll(getLocalVariableCompletions(ctx, line, character))

            // Add variant constructors from visible types
            items.addAll(getVariantConstructorCompletions(ctx))

            // Stdlib free-function completions with auto-import.
            items.addAll(getStdlibAutoImportCompletions(ctx, line, character))
        }

        // Context filter: in a TYPE-ANNOTATION position (`let f : |`,
        // `{x: |}`, `-> |`), values/functions/keywords are noise — the
        // user is naming a type. Keep only type-shaped items.
        val inTypeAnnotation = isInTypeAnnotationPosition(ctx, line, character)
        val filtered = if (inTypeAnnotation) {
            items.filter { it.kind.let { k ->
                k == CompletionKind.Struct || k == CompletionKind.Enum ||
                k == CompletionKind.Class || k == CompletionKind.TypeParameter
            } }
        } else items

        // Type-directed GENERATION: when the cursor sits at a position with a
        // known expected type, synthesize the construct that produces it
        // (Ok/Err, a full record literal, a lambda stub) pre-filled. Prepended
        // and floated to the very top ("0"). Suppressed in a type-annotation
        // slot (we want type NAMES there, not value constructors).
        val withGenerated = if (!inTypeAnnotation) {
            generateExpectedTypeItems(ctx, line, character) + filtered
        } else filtered

        val tiered = applyBaseRelevanceTiers(withGenerated)
        val ranked = rankItemsByExpectedTypeGeneric(tiered, ctx, line, character)

        return ranked
    }

    /**
     * Assign a base relevance tier (a `sortText` prefix) to each item that
     * doesn't already carry one, so the menu orders by how likely the item is
     * what the user wants at a fresh identifier position:
     *
     *   1_ local variables + lambda params (closest scope)
     *   2_ in-scope values / functions (top-level decls, imports)
     *   3_ types / variant constructors / effects / abilities
     *   4_ keywords
     */
    private fun applyBaseRelevanceTiers(items: List<CompletionCandidate>): List<CompletionCandidate> {
        return items.map { item ->
            if (item.sortText != null) return@map item  // keep an explicit rank
            val tier = when (item.kind) {
                CompletionKind.Field -> "1"        // record-field arg names
                CompletionKind.Variable,
                CompletionKind.Function, CompletionKind.Method -> "2"
                CompletionKind.Struct, CompletionKind.EnumMember,
                CompletionKind.Enum, CompletionKind.Interface,
                CompletionKind.Class, CompletionKind.Constructor -> "3"
                CompletionKind.Keyword -> "4"
                else -> "2"
            }
            item.apply { sortText = "${tier}_${label}" }
        }
    }

    /**
     * The typechecker's expected type at the cursor, if any — the smallest
     * `expectedTypeAt` entry whose loc contains (pos), with a little trailing
     * slack so a cursor just past the slot's end still counts.
     */
    private fun expectedTypeAtCursor(ctx: CompletionContext, line: Int, character: Int): MezzeType? {
        val result = ctx.typeCheckResult ?: return null
        val expectedTypeAt = result.expectedTypeAt
        if (expectedTypeAt.isEmpty()) return null
        val cursorLine = line + 1
        val cursorCol = character + 1
        val slack = 3
        return expectedTypeAt
            .filterKeys { loc ->
                val sb = loc.startLine < cursorLine ||
                    (loc.startLine == cursorLine && loc.startCol <= cursorCol)
                val ea = loc.endLine > cursorLine ||
                    (loc.endLine == cursorLine && loc.endCol + slack >= cursorCol)
                sb && ea
            }
            .entries
            .minByOrNull { (loc, _) -> (loc.endLine - loc.startLine) * 1_000_000 + (loc.endCol - loc.startCol) }
            ?.value
    }

    /**
     * Type-directed generation: given the expected type at the cursor, produce
     * completion items that CONSTRUCT a value of that type, pre-filled with
     * snippet tabstops. All items sort to the very top ("0").
     */
    private fun generateExpectedTypeItems(ctx: CompletionContext, line: Int, character: Int): List<CompletionCandidate> {
        val expected = expectedTypeAtCursor(ctx, line, character) ?: return emptyList()
        return generateItemsForType(ctx, expected)
    }

    /**
     * The type→construction-items core, callable directly with a concrete
     * [expected] type (used both by the cursor-based path and by field-value
     * completion, which knows the field's type from the call's record param).
     */
    private fun generateItemsForType(ctx: CompletionContext, expected: MezzeType): List<CompletionCandidate> {
        val out = mutableListOf<CompletionCandidate>()

        fun snippetItem(label: String, snippet: String, detail: String, order: Int): CompletionCandidate =
            candidate(label) {
                kind = CompletionKind.Snippet
                insertText = snippet
                this.snippet = true
                this.detail = detail
                sortText = "0_gen_${order}_$label"
            }

        // A tabstop that shows the element type where helpful, e.g. `${1:Int}`.
        fun tab(idx: Int, t: MezzeType): String {
            val hint = concreteTypeHint(t)
            return if (hint != null) "\${${idx}:$hint}" else "\$$idx"
        }

        when (expected) {
            is MezzeType.TypeApplication -> when (expected.constructor) {
                BuiltinTypes.RESULT -> {
                    val okT = expected.args.getOrNull(0)
                    val errT = expected.args.getOrNull(1)
                    out += snippetItem(BuiltinTypes.OK, "${BuiltinTypes.OK} { ${if (okT != null) tab(1, okT) else "\$1"} }", "construct Result", 0)
                    out += snippetItem(BuiltinTypes.ERR, "${BuiltinTypes.ERR} { ${BuiltinTypes.ERROR_FIELD} = ${if (errT != null) tab(1, errT) else "\$1"} }", "construct Result", 1)
                }
                BuiltinTypes.OPTION -> {
                    val someT = expected.args.getOrNull(0)
                    out += snippetItem(BuiltinTypes.SOME, "${BuiltinTypes.SOME} { ${if (someT != null) tab(1, someT) else "\$1"} }", "construct Option", 0)
                    out += snippetItem(BuiltinTypes.NONE, BuiltinTypes.NONE, "construct Option", 1)
                }
                else -> generateForNamedType(ctx, expected.constructor, out)
            }
            is MezzeType.VariantType -> {
                expected.identity.displayNameOrNull()?.let { generateForNamedType(ctx, it, out) }
            }
            is MezzeType.RecordType -> {
                if (expected.fields.isNotEmpty()) {
                    val body = expected.fields.entries.toList().mapIndexed { idx, (fn, ft) ->
                        "$fn = ${renderFieldPlaceholderPublic(fn, ft, idx + 1)}"
                    }.joinToString(", ")
                    out += snippetItem("{ … } record", "{ $body }", "fill record literal", 0)
                }
            }
            is MezzeType.FunctionType -> {
                val params = (expected.paramType as? MezzeType.RecordType)?.fields?.keys?.toList()
                    ?: listOf("x")
                out += snippetItem("{ … } -> …", "{${params.joinToString(", ")}} -> \$1", "fill lambda", 0)
            }
            is MezzeType.BoolType -> {
                out += snippetItem(BuiltinTypes.TRUE, BuiltinTypes.TRUE, "Bool", 0)
                out += snippetItem(BuiltinTypes.FALSE, BuiltinTypes.FALSE, "Bool", 1)
            }
            // Not usefully constructible from a bare expected type.
            is MezzeType.VarType, is MezzeType.IntType, is MezzeType.FloatType,
            is MezzeType.StringType, is MezzeType.CharType, is MezzeType.NatType,
            is MezzeType.FracType, is MezzeType.BytesType,
            is MezzeType.NewtypeType, is MezzeType.ListType,
            is MezzeType.SeqType, is MezzeType.MapType, is MezzeType.SetType,
            is MezzeType.SortedMapType, is MezzeType.SortedSetType,
            is MezzeType.EffectRow -> {}
        }
        return out
    }

    /**
     * Emit a construction item per constructor of a locally-declared variant
     * type. No-op if the type isn't a variant decl in this module.
     */
    private fun generateForNamedType(ctx: CompletionContext, typeName: String, out: MutableList<CompletionCandidate>) {
        val decl = ctx.parsedModule?.decls?.filterIsInstance<ParsedTypeDecl>()?.find { it.name == typeName }
        val def = decl?.definition as? com.mezze.language.parsedast.ParsedVariantDef ?: return
        def.constructors.forEachIndexed { idx, ctor ->
            val recordEntries = (ctor.dataType as? com.mezze.language.parsedast.ParsedRecordType)?.entries
            val snippet = when {
                recordEntries != null && recordEntries.isNotEmpty() -> {
                    val body = recordEntries.mapIndexed { i, e -> "${e.name} = \${${i + 1}:${e.name}}" }
                        .joinToString(", ")
                    "${ctor.name} { $body }"
                }
                ctor.dataType != null -> "${ctor.name} { \$1 }"  // non-record payload
                else -> ctor.name                                 // no payload
            }
            out += candidate(ctor.name) {
                kind = CompletionKind.Snippet
                insertText = snippet
                this.snippet = true
                detail = "construct $typeName"
                sortText = "0_gen_${idx}_${ctor.name}"
            }
        }
    }

    /** Public shim so generation can reuse the typed field-placeholder renderer. */
    private fun renderFieldPlaceholderPublic(fieldName: String, fieldType: MezzeType, idx: Int): String =
        renderFieldPlaceholder(fieldName, fieldType, idx)

    private fun rankItemsByExpectedTypeGeneric(
        items: List<CompletionCandidate>,
        ctx: CompletionContext,
        line: Int,
        character: Int,
    ): List<CompletionCandidate> {
        val expected = expectedTypeAtCursor(ctx, line, character) ?: return items

        val expectedVariantName = (expected as? MezzeType.TypeApplication)?.constructor
            ?: (expected as? MezzeType.BoolType)?.let { "Bool" }

        // Core-variant constructor names come from ast/BuiltinTypes (single
        // source of truth) so this can't drift from the compiler's own set.
        val knownVariants = mutableMapOf(
            BuiltinTypes.TRUE to "Bool", BuiltinTypes.FALSE to "Bool",
            BuiltinTypes.SOME to "Option", BuiltinTypes.NONE to "Option",
            BuiltinTypes.OK to "Result", BuiltinTypes.ERR to "Result",
        )
        ctx.parsedModule?.decls?.filterIsInstance<ParsedTypeDecl>()?.forEach { td ->
            val def = td.definition
            if (def is com.mezze.language.parsedast.ParsedVariantDef) {
                for (c in def.constructors) knownVariants[c.name] = td.name
            }
        }

        return items.map { item ->
            val matches = expectedVariantName != null &&
                knownVariants[item.label] == expectedVariantName
            if (matches) {
                val secondary = item.sortText ?: item.label
                item.apply { sortText = "0_${secondary}" }
            } else item
        }
    }

    /**
     * Completions for module paths inside a `use …` line. Returns null when the
     * cursor is NOT in an import-path context (so the caller falls through to
     * ordinary completion); returns a (possibly empty) list when it is.
     */
    private fun getImportPathCompletions(ctx: CompletionContext, line: Int, character: Int): List<CompletionCandidate>? {
        val lineText = ctx.content.lines().getOrNull(line) ?: return null
        val before = lineText.substring(0, minOf(character, lineText.length))
        // Match `use ` / `pub use ` then capture the partial path typed so far.
        val m = Regex("""^\s*(?:pub\s+)?use\s+([\w:]*)$""").find(before) ?: return null
        val typed = m.groupValues[1]   // e.g. "std::se" or "" or "std::"

        val fromStore = try { ctx.store?.getAllModules()?.map { it.path } ?: emptyList() } catch (_: Exception) { emptyList() }
        val fromStdlib = try {
            com.mezze.language.typechecker.PrecompiledStdlib.stdlibTypedModules.values
                .mapNotNull { filePathToModulePath(it.path) }
        } catch (_: Exception) { emptyList() }
        val modules = (fromStore + fromStdlib).filter { it.isNotBlank() }.distinct()
        if (modules.isEmpty()) return emptyList()

        // The replacement covers just the already-typed path text, so accepting
        // an item overwrites `std::se`, not the whole line.
        val replaceStartCol = character - typed.length

        val items = modules.asSequence()
            .filter { it.isNotBlank() && it.startsWith(typed) }
            .distinct()
            .sorted()
            .take(50)
            .map { path ->
                candidate(path) {
                    kind = CompletionKind.Module
                    detail = "module"
                    textEdit = NeutralTextEdit(line, replaceStartCol, line, character, path)
                    // Sort by path so `std::int` precedes `std::seq`.
                    sortText = path
                }
            }
            .toList()
        return items
    }

    /**
     * Convert a module's *file* path to its dotted module path:
     * `../stdlib/std/int.mz` → `std::int`, `src/foo/bar.mz` → `foo::bar`.
     */
    private fun filePathToModulePath(filePath: String): String? {
        if (!filePath.endsWith(".mz")) return null
        val norm = filePath.replace('\\', '/')
        val segments = norm.removeSuffix(".mz").split('/').filter { it.isNotBlank() && it != ".." && it != "." }
        val anchor = segments.indexOfLast { it == "stdlib" || it == "src" }
        val relevant = if (anchor >= 0) segments.drop(anchor + 1) else segments
        return if (relevant.isEmpty()) null else relevant.joinToString("::")
    }

    private fun getStdlibAutoImportCompletions(
        ctx: CompletionContext,
        line: Int,
        character: Int,
    ): List<CompletionCandidate> {
        val store = ctx.store ?: return emptyList()
        val prefix = identifierPrefixAt(ctx, line, character) ?: return emptyList()
        if (prefix.length < 2) return emptyList()

        // Names already visible — don't suggest them again with an import.
        val visibleNames = mutableSetOf<String>()
        ctx.typeCheckResult?.resolvedImports?.forEach { visibleNames.add(it.localName) }
        ctx.parsedModule?.decls?.forEach { decl ->
            when (decl) {
                is com.mezze.language.parsedast.ParsedLetDecl ->
                    if (decl.isSimpleBinding) visibleNames.add(decl.variableName)
                is com.mezze.language.parsedast.ParsedTypeDecl -> visibleNames.add(decl.name)
                is com.mezze.language.parsedast.ParsedAbilityDecl -> visibleNames.add(decl.name)
                is com.mezze.language.parsedast.ParsedEffectDecl -> visibleNames.add(decl.name)
                else -> {}
            }
        }

        val items = mutableListOf<CompletionCandidate>()
        val seen = mutableSetOf<String>()

        fun addAutoImport(
            declRow: com.mezze.language.store.DeclRow,
            itemKind: CompletionKind,
            detailKind: String,
        ): Boolean {
            val name = declRow.declName
            if (name in visibleNames) return false
            if (!name.startsWith(prefix, ignoreCase = false) && !name.contains(prefix, ignoreCase = true)) return false
            if (name.startsWith("_")) return false  // mangled/private convention
            if (!seen.add("${declRow.modulePath}::$name")) return false
            items.add(candidate(name) {
                kind = itemKind
                detail = "$detailKind from ${declRow.modulePath}"
                declRow.doc?.takeIf { it.isNotBlank() }?.let {
                    documentation = HoverInfo.renderDocMarkdown(it)
                    documentationMarkdown = true
                }
                sortText = "zz_$name"  // penalize below locally-visible names
                insertText = name
                buildAutoImportEdit(ctx, declRow.modulePath, name)?.let {
                    additionalEdits = listOf(it)
                }
            })
            return true
        }

        try {
            for (declRow in store.getDeclsByKind(com.mezze.language.store.DeclKind.LET)) {
                addAutoImport(declRow, CompletionKind.Function, "fn")
                if (items.size >= 30) break
            }
            for ((kind, itemKind, label) in listOf(
                Triple(com.mezze.language.store.DeclKind.TYPE, CompletionKind.Struct, "type"),
                Triple(com.mezze.language.store.DeclKind.EFFECT, CompletionKind.Interface, "effect"),
                Triple(com.mezze.language.store.DeclKind.ABILITY, CompletionKind.Interface, "ability"),
            )) {
                if (items.size >= 40) break
                for (declRow in store.getDeclsByKind(kind)) {
                    addAutoImport(declRow, itemKind, label)
                    if (items.size >= 40) break
                }
            }
        } catch (e: Exception) {
            // Best-effort. Don't fail the whole completion if the store hiccups.
            ctx.log("Stdlib auto-import completion failed: ${e.message}")
        }
        return items
    }

    /**
     * Read the identifier-shaped prefix at the cursor. Returns null when
     * the cursor isn't on an identifier (e.g. inside a string literal).
     */
    private fun identifierPrefixAt(ctx: CompletionContext, line: Int, character: Int): String? {
        val lineText = ctx.content.split('\n').getOrNull(line) ?: return null
        val col = character.coerceIn(0, lineText.length)
        var s = col
        while (s > 0 && lineText[s - 1].let { it.isLetterOrDigit() || it == '_' }) s--
        return if (col > s) lineText.substring(s, col) else null
    }

    /**
     * Get function argument completions when inside a record-style function call.
     */
    private fun getFunctionArgumentCompletions(ctx: CompletionContext, line: Int, character: Int): List<CompletionCandidate> {
        val lines = ctx.content.lines()
        if (line >= lines.size) return emptyList()

        val lineText = lines[line]
        val beforeCursor = lineText.substring(0, minOf(character, lineText.length))

        val funcCallMatch = findFunctionCallContext(beforeCursor, lines, line)
            ?: return emptyList()

        val funcName = funcCallMatch.functionName
        val result = ctx.typeCheckResult ?: return emptyList()

        val funcType = findFunctionType(funcName, result) ?: return emptyList()

        val recordFields = extractRecordParameterFields(funcType)
        if (recordFields.isEmpty()) return emptyList()

        // Field-VALUE position: `foo { field = |` — offer type-directed
        // GENERATION for that field's type rather than the field-name list.
        val activeField = fieldValueContext(beforeCursor, recordFields.map { it.first })
        if (activeField != null) {
            val fieldType = recordFields.firstOrNull { it.first == activeField }?.second
            if (fieldType != null) {
                val gen = generateItemsForType(ctx, fieldType)
                if (gen.isNotEmpty()) return gen
            }
        }

        val items = mutableListOf<CompletionCandidate>()

        // First item: complete ALL fields at once (most useful).
        val allFieldsSnippet = recordFields.mapIndexed { index, (fieldName, fieldType) ->
            val placeholder = renderFieldPlaceholder(fieldName, fieldType, index + 1)
            "$fieldName = $placeholder"
        }.joinToString(", ")

        val allFieldsPreview = recordFields.joinToString(", ") { (name, _) -> "$name = ..." }

        items.add(candidate("Fill all fields") {
            kind = CompletionKind.Snippet
            detail = allFieldsPreview
            insertText = allFieldsSnippet
            snippet = true
            sortText = "0"  // Show first
        })

        // Also add individual field completions
        recordFields.forEachIndexed { index, (fieldName, fieldType) ->
            val typeStr = prettyPrintTypeWithAliases(fieldType, ctx.typeAliases)
            items.add(candidate(fieldName) {
                kind = CompletionKind.Field
                detail = typeStr
                insertText = "$fieldName = "
                sortText = "1_${index.toString().padStart(3, '0')}"
            })

            // If the field type is a variant, also suggest "fieldName = Constructor" shortcuts
            val variantCompletions = getVariantCompletionsForFieldType(fieldType, fieldName, ctx, index)
            items.addAll(variantCompletions)
        }

        return items
    }

    /**
     * If the cursor sits at a field-VALUE position inside a record-arg call
     * (`… { field = ⟨here⟩`), return the field's name; else null.
     */
    private fun fieldValueContext(beforeCursor: String, fieldNames: List<String>): String? {
        val segStart = maxOf(beforeCursor.lastIndexOf('{'), beforeCursor.lastIndexOf(','))
        if (segStart < 0) return null
        val seg = beforeCursor.substring(segStart + 1)
        val eq = seg.indexOf('=')
        if (eq < 0) return null
        val name = seg.substring(0, eq).trim()
        return if (name in fieldNames) name else null
    }

    /**
     * Get variant constructor completions for a specific field in a function call.
     */
    private fun getVariantCompletionsForFieldType(
        fieldType: MezzeType,
        fieldName: String,
        ctx: CompletionContext,
        fieldIndex: Int
    ): List<CompletionCandidate> {
        val variantType = when (fieldType) {
            is MezzeType.VariantType -> fieldType
            is MezzeType.TypeApplication -> findVariantTypeFromApplication(fieldType, ctx)
            else -> null
        } ?: return emptyList()

        return variantType.constructors.entries.mapIndexed { constructorIndex, (constructorName, payload) ->
            candidate(constructorName) {
                kind = CompletionKind.EnumMember
                sortText = "2_${fieldIndex.toString().padStart(3, '0')}_${constructorIndex.toString().padStart(3, '0')}"
                detail = "$fieldName: ${prettyPrintTypeWithAliases(fieldType, ctx.typeAliases)}"

                if (payload != null) {
                    val payloadStr = when (payload) {
                        is MezzeType.RecordType -> {
                            val fields = payload.fields.keys.mapIndexed { i, field ->
                                "$field = \${${i + 1}:}"
                            }.joinToString(", ")
                            " { $fields }"
                        }
                        else -> " { \${1:value} }"
                    }
                    insertText = "$constructorName$payloadStr"
                    snippet = true
                } else {
                    insertText = constructorName
                }
            }
        }
    }

    /**
     * Find the function being called in a record-style call context.
     */
    private fun findFunctionCallContext(beforeCursor: String, lines: List<String>, currentLine: Int): FunctionCallContext? {
        var braceDepth = 0

        for (i in currentLine downTo 0) {
            val line = if (i == currentLine) beforeCursor else lines[i]
            for (char in line.reversed()) {
                when (char) {
                    '}' -> braceDepth++
                    '{' -> {
                        braceDepth--
                        if (braceDepth < 0) {
                            val upToBrace = if (i == currentLine) {
                                line.substring(0, line.lastIndexOf('{'))
                            } else {
                                lines.subList(0, i + 1).joinToString("\n").substringBeforeLast('{')
                            }
                            val funcName = extractIdentifierBefore(upToBrace)
                            if (funcName != null) {
                                return FunctionCallContext(funcName)
                            }
                            return null
                        }
                    }
                }
            }
        }
        return null
    }

    private data class FunctionCallContext(val functionName: String)

    /**
     * Extract the identifier immediately before a position (skipping whitespace).
     */
    private fun extractIdentifierBefore(text: String): String? {
        val trimmed = text.trimEnd()
        if (trimmed.isEmpty()) return null

        val end = trimmed.length
        var start = end

        while (start > 0 && HoverInfo.isIdentifierChar(trimmed[start - 1])) {
            start--
        }

        return if (start < end) trimmed.substring(start, end) else null
    }

    /**
     * Find the type of a function by name.
     */
    private fun findFunctionType(funcName: String, result: TypeCheckResult): MezzeType? {
        val typedBinding = result.typedModule?.decls
            ?.filterIsInstance<com.mezze.language.typedast.TypedLetDecl>()
            ?.find { it.name == funcName }

        if (typedBinding != null) {
            val bindingType = typedBinding.scheme.type
            if (bindingType !is MezzeType.VarType) return bindingType
        }

        val import = result.resolvedImports.find { it.localName == funcName }
        if (import != null) {
            return import.type
        }

        return null
    }

    /**
     * Extract record field names and types from a function's parameter type.
     */
    private fun extractRecordParameterFields(funcType: MezzeType): List<Pair<String, MezzeType>> {
        val recordType = when (funcType) {
            is MezzeType.FunctionType -> funcType.paramType
            else -> return emptyList()
        }

        return when (recordType) {
            is MezzeType.RecordType -> {
                recordType.fields.map { (name, type) -> name to type }
            }
            else -> emptyList()
        }
    }

    /**
     * Snippet for a top-level function or imported function call:
     * `foo { field1 = ${1:field1}, field2 = ${2:field2} }`. Returns null
     * if the binding isn't a function.
     */
    private fun callInsertSnippet(name: String, type: MezzeType): String? {
        val fnType = type as? MezzeType.FunctionType ?: return null
        val paramRecord = fnType.paramType as? MezzeType.RecordType
        if (paramRecord == null) return "$name { $0 }"
        if (paramRecord.fields.isEmpty()) return "$name {}"
        val body = paramRecord.fields.entries.toList().mapIndexed { idx, (fieldName, fieldType) ->
            "$fieldName = ${renderFieldPlaceholder(fieldName, fieldType, idx + 1)}"
        }.joinToString(", ")
        return "$name { $body }"
    }

    internal fun buildMethodSnippet(
        methodName: String,
        paramNames: List<MethodParamHint>
    ): String {
        if (paramNames.isEmpty()) return "$methodName {}"
        val body = paramNames.mapIndexed { idx, hint ->
            val placeholder = when (hint) {
                is MethodParamHint.Plain -> "\${${idx + 1}:${hint.name}}"
                is MethodParamHint.Callback -> {
                    val params = hint.callbackParams.joinToString(", ")
                    "\${${idx + 1}:{$params} -> $0}"
                }
            }
            "${hint.name} = $placeholder"
        }.joinToString(", ")
        return "$methodName {$body}"
    }

    /**
     * Build the snippet placeholder for a single field given its name +
     * inferred MezzeType.
     */
    private fun renderFieldPlaceholder(fieldName: String, fieldType: MezzeType, idx: Int): String {
        val fnType = fieldType as? MezzeType.FunctionType
        if (fnType == null) {
            val hint = concreteTypeHint(fieldType)
            return if (hint != null) "\${${idx}:$fieldName: $hint}" else "\${${idx}:$fieldName}"
        }
        val innerParams = (fnType.paramType as? MezzeType.RecordType)
            ?.fields?.keys?.toList()
            ?: listOf("x")
        return "\${${idx}:{${innerParams.joinToString(", ")}} -> $0}"
    }

    /**
     * A short type name to show inside a snippet placeholder, or null when the
     * type isn't a helpfully-nameable concrete type.
     */
    private fun concreteTypeHint(type: MezzeType): String? = concreteTypeHint(type, depth = 0)

    private fun concreteTypeHint(type: MezzeType, depth: Int): String? {
        fun elem(t: MezzeType): String = if (depth >= 2) "_" else (concreteTypeHint(t, depth + 1) ?: "_")
        return when (type) {
            is MezzeType.IntType -> "Int"
            is MezzeType.FloatType -> "Float"
            is MezzeType.StringType -> "Str"
            is MezzeType.BoolType -> "Bool"
            is MezzeType.CharType -> "Char"
            is MezzeType.NatType -> "Nat"
            is MezzeType.FracType -> "Frac"
            is MezzeType.BytesType -> "Bytes"
            is MezzeType.TypeApplication ->
                if (type.args.isEmpty()) type.constructor
                else "${type.constructor} ${type.args.joinToString(" ") { elem(it) }}"
            is MezzeType.NewtypeType -> concreteTypeHint(type.wrappedType, depth)
            is MezzeType.ListType -> "List ${elem(type.elementType)}"
            is MezzeType.SeqType -> "Seq ${elem(type.elementType)}"
            is MezzeType.SetType -> "Set ${elem(type.elementType)}"
            is MezzeType.SortedSetType -> "SortedSet ${elem(type.elementType)}"
            is MezzeType.MapType -> "Map ${elem(type.keyType)} ${elem(type.valueType)}"
            is MezzeType.SortedMapType -> "SortedMap ${elem(type.keyType)} ${elem(type.valueType)}"
            is MezzeType.VariantType -> type.identity.displayNameOrNull()
            is MezzeType.VarType,
            is MezzeType.FunctionType,
            is MezzeType.RecordType,
            is MezzeType.EffectRow -> null
        }
    }

    /** Hint about a method parameter for snippet placeholder rendering. */
    internal sealed interface MethodParamHint {
        val name: String
        data class Plain(override val name: String) : MethodParamHint
        data class Callback(override val name: String, val callbackParams: List<String>) : MethodParamHint
    }

    /**
     * Build param hints from a parsed type annotation (for ability ops where
     * paramType is a record annotation).
     */
    internal fun paramHintsFromAnnotation(
        paramType: ParsedTypeAnnotation?,
        stripIt: Boolean = true
    ): List<MethodParamHint> {
        val record = paramType as? com.mezze.language.parsedast.ParsedRecordType ?: return emptyList()
        return record.entries
            .filter { !stripIt || it.name != "it" }
            .map { entry ->
                val fnType = entry.type as? com.mezze.language.parsedast.ParsedFunctionType
                if (fnType != null) {
                    val innerParams = (fnType.paramType as? com.mezze.language.parsedast.ParsedRecordType)
                        ?.entries?.map { it.name }
                        ?: listOf("x")
                    MethodParamHint.Callback(entry.name, innerParams)
                } else {
                    MethodParamHint.Plain(entry.name)
                }
            }
    }

    /**
     * Build param hints from a typed MezzeType (for the unimported-ability
     * step that deserializes TypedAbilityDecl).
     */
    internal fun paramHintsFromMezzeType(
        paramType: MezzeType,
        stripIt: Boolean = true
    ): List<MethodParamHint> {
        val record = paramType as? MezzeType.RecordType ?: return emptyList()
        return record.fields
            .filter { (name, _) -> !stripIt || name != "it" }
            .map { (name, type) ->
                val fnType = type as? MezzeType.FunctionType
                if (fnType != null) {
                    val innerParams = (fnType.paramType as? MezzeType.RecordType)
                        ?.fields?.keys?.toList()
                        ?: listOf("x")
                    MethodParamHint.Callback(name, innerParams)
                } else {
                    MethodParamHint.Plain(name)
                }
            }
    }

    /**
     * Look up an inherent impl operation's post-solve param-record type
     * from PrecompiledStdlib's TypedAbilityImpls.
     */
    private fun lookupTypedImplOpParamType(
        precompiled: com.mezze.language.typechecker.PrecompiledStdlib,
        targetTypeName: String,
        opName: String
    ): MezzeType? {
        for (impl in precompiled.stdlibAbilityImpls) {
            val target = impl.targetTypes.firstOrNull() ?: continue
            if (getTypeName(target) != targetTypeName) continue

            val opBody = impl.operations[opName] ?: continue
            val lambda = opBody as? com.mezze.language.typedast.TypedLambda ?: continue
            val fields = lambda.params.associate { it.name to it.type }
            return MezzeType.RecordType(fields = fields, rowVar = null)
        }
        return null
    }

    /**
     * Return type of an inherent impl op, derived from the post-solve typed impl.
     */
    private fun lookupTypedImplOpReturnType(
        precompiled: com.mezze.language.typechecker.PrecompiledStdlib,
        targetTypeName: String,
        opName: String
    ): MezzeType? {
        for (impl in precompiled.stdlibAbilityImpls) {
            val target = impl.targetTypes.firstOrNull() ?: continue
            if (getTypeName(target) != targetTypeName) continue
            val opBody = impl.operations[opName] ?: continue
            val lambda = opBody as? com.mezze.language.typedast.TypedLambda ?: continue
            return lambda.body.type
        }
        return null
    }

    /**
     * Build hints from a ParsedImplOp by inspecting the implementation
     * lambda's params.
     */
    private fun paramHintsFromImplOpLambda(op: ParsedImplOp): List<MethodParamHint> {
        val lambda = op.implementation as? com.mezze.language.parsedast.ParsedLambda ?: return emptyList()
        val names = when (val p = lambda.params) {
            is com.mezze.language.parsedast.ParsedLambdaParams.Untyped -> p.names
            is com.mezze.language.parsedast.ParsedLambdaParams.Typed -> p.fields.map { it.name }
        }
        return names.filter { it != "it" }.map { MethodParamHint.Plain(it) }
    }

    /**
     * Get dot-completions: when user types `x.`, suggest methods available on x's type.
     */
    private fun getDotCompletions(ctx: CompletionContext, line: Int, character: Int): List<CompletionCandidate> {
        val lines = ctx.content.lines()
        if (line >= lines.size) return emptyList()

        val lineText = lines[line]
        val beforeCursor = lineText.substring(0, minOf(character, lineText.length))
        ctx.log("[dot-completion] pos=$line:$character beforeCursor=${beforeCursor.takeLast(60).let { "...$it" }}")

        val identifierMatch = Regex("""(\w+)\.(\w*)$""").find(beforeCursor)
        val partialMethod: String
        val receiverName: String?
        if (identifierMatch != null) {
            receiverName = identifierMatch.groupValues[1]
            partialMethod = identifierMatch.groupValues[2]
            ctx.log("[dot-completion] path=identifier receiver='$receiverName' partial='$partialMethod'")
        } else {
            val exprDotMatch = Regex("""\.(\w*)$""").find(beforeCursor) ?: run {
                ctx.log("[dot-completion] no dot match — returning empty")
                return emptyList()
            }
            receiverName = null
            partialMethod = exprDotMatch.groupValues[1]
            ctx.log("[dot-completion] path=expression partial='$partialMethod'")
        }

        // Use current type check result, or fall back to last successful one
        val result = ctx.typeCheckResult
            ?: ctx.lastSuccessful
            ?: run {
                ctx.log("[dot-completion] no typeCheckResult — returning empty")
                return emptyList()
            }
        ctx.log("[dot-completion] typeCheckResult=${if (ctx.typeCheckResult != null) "current" else "lastSuccessful"}")

        fun formatType(type: MezzeType): String {
            return prettyPrintTypeWithAliases(type, ctx.typeAliases)
        }

        val receiverType = if (receiverName != null) {
            findReceiverType(receiverName, result, line, character, ctx)
        } else {
            findReceiverTypeForExpressionAt(ctx, line, character, result)
        } ?: run {
            ctx.log("[dot-completion] receiver type lookup returned null — returning empty")
            return emptyList()
        }
        val baseType = receiverType
        ctx.log("[dot-completion] receiverType=$baseType (typeName=${getTypeName(baseType) ?: "<none>"})")

        val items = mutableListOf<CompletionCandidate>()
        val addedMethods = mutableSetOf<String>()  // Deduplicate
        val candidateReturnTypes = mutableMapOf<String, MezzeType>()

        val localAbilities = ctx.parsedModule?.decls?.filterIsInstance<ParsedAbilityDecl>() ?: emptyList()
        val allAbilities = result.importedAbilities.values + localAbilities

        if (baseType is MezzeType.RecordType) {
            ctx.log("[dot-completion] record receiver: fields=${baseType.fields.keys}")
            for ((fieldName, fieldType) in baseType.fields) {
                items.add(candidate(fieldName) {
                    kind = CompletionKind.Field
                    detail = formatType(fieldType)
                })
            }
            val final = if (partialMethod.isNotEmpty()) {
                items.filter { it.label.startsWith(partialMethod, ignoreCase = true) }
            } else items
            ctx.log("[dot-completion] returning ${final.size} field completions: ${final.map { it.label }}")
            return final
        }

        val targetTypeName = getTypeName(baseType)

        if (targetTypeName == null) {
            ctx.log("[dot-completion] receiver type unresolved ($baseType) — returning no methods (never dump all)")
            return emptyList()
        }

        // 1. Ability methods whose ability is in scope AND whose impl exists.
        for (abilityDecl in allAbilities) {
            if (!isBuiltinAbilityForType(abilityDecl.name, baseType)) continue
            for (op in abilityDecl.operations) {
                if (op.name in addedMethods) continue
                val paramStr = HoverInfo.formatTypeAnnotation(op.paramType)
                if (!paramStr.contains("it")) continue
                val hints = paramHintsFromAnnotation(op.paramType, stripIt = true)
                val sigDoc = HoverInfo.specializeAbilityOp(op, abilityDecl.receiverTypeParam, baseType, ctx.typeAliases)
                    ?: "${HoverInfo.formatTypeAnnotation(op.paramType)} -> ${HoverInfo.formatTypeAnnotation(op.returnType)}"
                items.add(candidate(op.name) {
                    kind = CompletionKind.Method
                    detail = "${abilityDecl.name}.${op.name}"
                    documentation = sigDoc
                    insertText = buildMethodSnippet(op.name, hints)
                    snippet = true
                })
                addedMethods.add(op.name)
                try {
                    candidateReturnTypes[op.name] =
                        com.mezze.language.typechecker.util.TypeAnnotationConverter.convert(op.returnType)
                } catch (_: Exception) { /* unconvertible — skip for ranking */ }
            }
        }

        // 2. Inherent methods from the local file's `impl Type where { ... }`.
        val parsedImpls = ctx.parsedModule?.decls?.filterIsInstance<ParsedImplAbilityDecl>() ?: emptyList()
        for (impl in parsedImpls) {
            if (impl.target !is ParsedImplTarget.Inherent) continue
            val implTarget = impl.targetTypes.firstOrNull()?.let { ann ->
                when (ann) {
                    is com.mezze.language.parsedast.ParsedNamedType -> ann.name
                    is com.mezze.language.parsedast.ParsedTypeApp -> ann.constructor
                    else -> null
                }
            } ?: continue
            if (targetTypeName != null && implTarget != targetTypeName) continue
            for (op in impl.operations) {
                if (op.name in addedMethods) continue
                val hints = paramHintsFromAnnotation(op.paramType, stripIt = true)
                    .ifEmpty { paramHintsFromImplOpLambda(op) }
                items.add(candidate(op.name) {
                    kind = CompletionKind.Method
                    detail = "$implTarget.${op.name}"
                    insertText = buildMethodSnippet(op.name, hints)
                    snippet = true
                })
                addedMethods.add(op.name)
                try {
                    op.returnType?.let { rt ->
                        candidateReturnTypes[op.name] =
                            com.mezze.language.typechecker.util.TypeAnnotationConverter.convert(rt)
                    }
                } catch (_: Exception) {}
            }
        }

        // 3. Inherent methods from the prelude.
        try {
            val precompiledForInherent = com.mezze.language.typechecker.PrecompiledStdlib
            for ((_, impl) in precompiledForInherent.preludeImportContext.importedAbilityImpls) {
                if (!impl.isInherent) continue
                val implTargetName = impl.targetTypes.firstOrNull()?.let { ann ->
                    when (ann) {
                        is com.mezze.language.parsedast.ParsedNamedType -> ann.name
                        is com.mezze.language.parsedast.ParsedTypeApp -> ann.constructor
                        else -> null
                    }
                } ?: continue
                if (targetTypeName != null && implTargetName != targetTypeName) continue
                for (op in impl.operations) {
                    if (op.name in addedMethods) continue
                    val typedParamType = lookupTypedImplOpParamType(
                        precompiledForInherent, implTargetName, op.name
                    )
                    val hints = when {
                        typedParamType != null -> paramHintsFromMezzeType(typedParamType, stripIt = true)
                        else -> paramHintsFromAnnotation(op.paramType, stripIt = true)
                            .ifEmpty { paramHintsFromImplOpLambda(op) }
                    }
                    val typedReturnType = lookupTypedImplOpReturnType(
                        precompiledForInherent, implTargetName, op.name
                    )
                    val sigDoc = if (typedParamType != null && typedReturnType != null) {
                        "${prettyPrintTypeWithAliases(typedParamType, ctx.typeAliases)} -> " +
                            prettyPrintTypeWithAliases(typedReturnType, ctx.typeAliases)
                    } else null
                    items.add(candidate(op.name) {
                        kind = CompletionKind.Method
                        detail = "$implTargetName.${op.name}"
                        if (sigDoc != null) documentation = sigDoc
                        insertText = buildMethodSnippet(op.name, hints)
                        snippet = true
                    })
                    addedMethods.add(op.name)
                    if (typedReturnType != null) {
                        candidateReturnTypes[op.name] = typedReturnType
                    } else {
                        try {
                            op.returnType?.let { rt ->
                                candidateReturnTypes[op.name] =
                                    com.mezze.language.typechecker.util.TypeAnnotationConverter.convert(rt)
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        } catch (_: Exception) { /* prelude may not be loaded in tests */ }

        // 4. Unimported ability methods — walk ability impls across the whole project.
        try {
            val store = ctx.store
            if (store != null && targetTypeName != null) {
                val importedAbilityNames = result.importedAbilities.keys + localAbilities.map { it.name }.toSet()

                val abilityDecls = store.getDeclsByKind(com.mezze.language.store.DeclKind.ABILITY)
                val abilityModuleByHash = abilityDecls.associate { it.hash to it.modulePath }
                val abilityNameByHash = abilityDecls.associate { it.hash to it.declName }

                val implDecls = store.getDeclsByKind(com.mezze.language.store.DeclKind.IMPL)
                var step4Added = 0
                for (implRow in implDecls) {
                    val implBlob = store.getBlob(implRow.hash) ?: continue
                    if (implBlob.kind != com.mezze.language.store.BlobKind.ABILITY_IMPL) continue
                    val implTyped = try {
                        val bytes = implBlob.typed ?: continue
                        com.mezze.language.store.StoreSerialization.deserializeTypedAbilityImpl(bytes)
                    } catch (_: Exception) { continue }

                    val abilityName = abilityNameByHash[implTyped.abilityHash] ?: continue
                    if (abilityName in importedAbilityNames) continue   // already covered by step 1
                    val abilityModule = abilityModuleByHash[implTyped.abilityHash] ?: continue

                    val target = implTyped.targetTypes.firstOrNull() ?: continue
                    val implTargetName = getTypeName(target) ?: continue
                    if (implTargetName != targetTypeName) continue

                    val abilityHashRow = abilityDecls.firstOrNull { it.hash == implTyped.abilityHash } ?: continue
                    val abilityBlob = store.getBlob(abilityHashRow.hash) ?: continue
                    val abilityDecl = try {
                        val bytes = abilityBlob.typed ?: continue
                        com.mezze.language.store.StoreSerialization.deserializeTypedAbilityDecl(bytes)
                    } catch (_: Exception) { continue }

                    for (op in abilityDecl.operations) {
                        if (op.name in addedMethods) continue
                        val hints = paramHintsFromMezzeType(op.paramType, stripIt = true)
                        val item = candidate(op.name) {
                            kind = CompletionKind.Method
                            detail = "$abilityName.${op.name}  (auto-import $abilityModule)"
                            insertText = buildMethodSnippet(op.name, hints)
                            snippet = true
                            sortText = "z_${op.name}"  // sort below already-imported methods
                        }
                        val edit = buildAutoImportEdit(ctx, abilityModule, abilityName)
                        if (edit != null) item.additionalEdits = listOf(edit)
                        items.add(item)
                        addedMethods.add(op.name)
                        step4Added++
                    }
                }
                ctx.log("[dot-completion] step4 added=$step4Added unimported-ability methods")
            }
        } catch (e: Exception) {
            ctx.log("[dot-completion] unimported-ability scan failed: ${e.message}")
        }

        // Postfix completions: `x.some` → `Some { x }`, etc.
        if (receiverName != null && identifierMatch != null) {
            val startCol = character - identifierMatch.value.length
            items.addAll(postfixCompletions(receiverName, partialMethod, line, startCol.coerceAtLeast(0), character))
        }

        // Filter by partial method name if the user already started typing.
        val filtered = if (partialMethod.isNotEmpty()) {
            items.filter { it.label.startsWith(partialMethod, ignoreCase = true) }
        } else {
            items
        }

        // A `.` inside string interpolation demands a Str.
        val expectedOverride = if (isInsideStringInterpolation(beforeCursor)) MezzeType.StringType else null

        val rankedItems = rankByExpectedType(
            items = filtered,
            cursorLine = line + 1,
            cursorCol = character + 1,
            result = result,
            candidateReturnTypes = candidateReturnTypes,
            expectedOverride = expectedOverride,
            ctx = ctx,
        )

        ctx.log("[dot-completion] returning ${rankedItems.size} method completions for type '${getTypeName(baseType) ?: baseType::class.simpleName}': ${rankedItems.take(20).map { it.label }}${if (rankedItems.size > 20) "..." else ""}")
        return rankedItems
    }

    /**
     * Postfix-style completions on a named receiver: `x.some` → `Some { x }`.
     */
    private fun postfixCompletions(
        receiver: String,
        partial: String,
        replaceLine: Int,
        replaceStartCol: Int,
        replaceEndCol: Int,
    ): List<CompletionCandidate> {
        data class Pf(val trigger: String, val expansion: String, val detail: String)
        val templates = listOf(
            Pf("some", "Some { $receiver }", "wrap in Some"),
            Pf("ok", "Ok { $receiver }", "wrap in Ok"),
            Pf("err", "Err { error = $receiver }", "wrap in Err"),
            Pf("match", "match $receiver is\n| \${1:pattern} -> \${2:result}", "match on this value"),
            Pf("let", "let \${1:name} = $receiver", "bind to a let"),
        )
        return templates
            .filter { partial.isEmpty() || it.trigger.startsWith(partial, ignoreCase = true) }
            .map { pf ->
                candidate(pf.trigger) {
                    kind = CompletionKind.Snippet
                    detail = "postfix: ${pf.detail}"
                    // Rank postfix below real methods sharing the prefix.
                    sortText = "zzz_${pf.trigger}"
                    snippet = true
                    textEdit = NeutralTextEdit(replaceLine, replaceStartCol, replaceLine, replaceEndCol, pf.expansion)
                }
            }
    }

    /**
     * Type-aware completion ranking.
     */
    private fun rankByExpectedType(
        items: List<CompletionCandidate>,
        cursorLine: Int,
        cursorCol: Int,
        result: TypeCheckResult,
        candidateReturnTypes: Map<String, MezzeType>,
        expectedOverride: MezzeType? = null,
        ctx: CompletionContext,
    ): List<CompletionCandidate> {
        val expectedTypeAt = result.expectedTypeAt
        ctx.log("[rank] cursor=$cursorLine:$cursorCol expectedTypeAt size=${expectedTypeAt.size} candidateReturnTypes size=${candidateReturnTypes.size} override=$expectedOverride")
        if (expectedOverride != null) {
            return rankAgainst(items, expectedOverride, candidateReturnTypes)
        }
        if (expectedTypeAt.isEmpty()) return items

        val slack = 3
        val expectedEntry = expectedTypeAt
            .filterKeys { loc ->
                val startsBefore = loc.startLine < cursorLine ||
                    (loc.startLine == cursorLine && loc.startCol <= cursorCol)
                val endsAfter = loc.endLine > cursorLine ||
                    (loc.endLine == cursorLine && loc.endCol + slack >= cursorCol)
                startsBefore && endsAfter
            }
            .entries
            .minByOrNull { (loc, _) ->
                (loc.endLine - loc.startLine) * 1_000_000 + (loc.endCol - loc.startCol)
            }
        ctx.log("[rank] enclosing entry=${expectedEntry?.key} expected=${expectedEntry?.value ?: "<none>"}")
        val expected = expectedEntry?.value ?: return items
        return rankAgainst(items, expected, candidateReturnTypes)
    }

    /**
     * Score each candidate against `expected`.
     */
    private fun rankAgainst(
        items: List<CompletionCandidate>,
        expected: MezzeType,
        candidateReturnTypes: Map<String, MezzeType>,
    ): List<CompletionCandidate> {
        val expectsStr = expected is MezzeType.StringType
        val strRenderers = setOf("to_str", "to_dbg")
        return items.map { item ->
            val candType = candidateReturnTypes[item.label]
            val matches = when {
                expectsStr && item.label in strRenderers -> true
                candType == null -> false
                candType is MezzeType.VarType -> false
                else -> com.mezze.language.typechecker.solving.unify(candType, expected) is
                    com.mezze.language.typechecker.solving.UnificationResult.Success
            }
            item.apply { sortText = "${if (matches) "0" else "5"}_${label}" }
        }
    }

    /**
     * True when the character position at the end of `beforeCursor` sits inside
     * an unclosed string-interpolation hole `"…$( … <cursor>`.
     */
    private fun isInsideStringInterpolation(beforeCursor: String): Boolean {
        var inString = false
        var depth = 0          // open `$(` interpolation parens
        var i = 0
        while (i < beforeCursor.length) {
            val c = beforeCursor[i]
            when {
                c == '\\' && inString -> i++   // skip escaped char
                c == '"' -> inString = !inString
                inString && c == '$' && i + 1 < beforeCursor.length && beforeCursor[i + 1] == '(' -> {
                    depth++; i++
                }
                depth > 0 && c == ')' -> depth--
            }
            i++
        }
        return depth > 0
    }

    /** Get the simple type name for ability matching. */
    private fun getTypeName(type: MezzeType): String? {
        return when (type) {
            is MezzeType.IntType -> "Int"
            is MezzeType.FloatType -> "Float"
            is MezzeType.StringType -> "Str"
            is MezzeType.BoolType -> "Bool"
            is MezzeType.NatType -> "Nat"
            is MezzeType.FracType -> "Frac"
            is MezzeType.CharType -> "Char"
            is MezzeType.BytesType -> "Bytes"
            is MezzeType.ListType -> "List"
            is MezzeType.SeqType -> "Seq"
            is MezzeType.SetType -> "Set"
            is MezzeType.SortedSetType -> "SortedSet"
            is MezzeType.MapType -> "Map"
            is MezzeType.SortedMapType -> "SortedMap"
            is MezzeType.TypeApplication -> type.constructor
            is MezzeType.VariantType -> type.constructors.keys.firstOrNull()
            else -> null
        }
    }

    /** Check if a type has automatic builtin implementation for an ability. */
    private fun isBuiltinAbilityForType(abilityName: String, type: MezzeType): Boolean {
        return com.mezze.language.ast.BuiltinAbilities.typeHasAutomaticImplementation(type, abilityName)
    }

    /** Find the type of a receiver identifier at a given position. */
    private fun findReceiverType(name: String, result: TypeCheckResult, line: Int, character: Int, ctx: CompletionContext): MezzeType? {
        // Check top-level bindings via TypedModule
        val typedBinding = result.typedModule?.decls
            ?.filterIsInstance<com.mezze.language.typedast.TypedLetDecl>()
            ?.find { it.name == name }
        if (typedBinding != null) {
            val bindingType = typedBinding.scheme.type
            if (bindingType !is MezzeType.VarType) return bindingType
        }

        // Check resolved imports
        val resolvedImport = result.resolvedImports.find { it.localName == name }
        if (resolvedImport != null) return resolvedImport.type

        // Check local bindings (do-block lets, match/lambda bindings).
        val local = findLocalBindingType(name, result.typedModule, line + 1)
        if (local != null && local !is MezzeType.VarType) return local

        // #159: fall back to the last successful typed module.
        val lastGood = ctx.lastSuccessful?.typedModule
        if (lastGood != null && lastGood !== result.typedModule) {
            findLocalBindingType(name, lastGood, line + 1)
                ?.takeIf { it !is MezzeType.VarType }
                ?.let { return it }
        }

        // Last resort — strip-and-reparse recovery.
        stripDotAndResolveReceiver(ctx, name, line, character)
            ?.takeIf { it !is MezzeType.VarType }
            ?.let { return it }

        return local
    }

    /**
     * Recover a `name.`-receiver's type by COMPLETING the dangling dot into a
     * side-effect-free `.to_dbg {}` call, reparsing + rechecking the buffer to a
     * solved module, and reading the type at the receiver's use site. `to_dbg`
     * works on any type (and returns Str, so it also satisfies interpolation),
     * so the synthesized edit typechecks regardless of the receiver. Recovers a
     * type even when the live buffer is a hard parse error (dangling `.`) or a
     * forward-referenced polymorphic binding not yet baked to concrete.
     */
    private fun stripDotAndResolveReceiver(ctx: CompletionContext, name: String, line: Int, character: Int): MezzeType? {
        return try {
            val lines = ctx.content.lines().toMutableList()
            val li = line
            if (li !in lines.indices) return null
            val srcLine = lines[li]
            val dotIdx = character - 1
            if (dotIdx < 0 || dotIdx >= srcLine.length || srcLine[dotIdx] != '.') return null
            lines[li] = srcLine.substring(0, dotIdx) + ".to_dbg {}" + srcLine.substring(dotIdx + 1)
            val stripped = lines.joinToString("\n")
            val fileName = ctx.uriToPath(ctx.uri)?.fileName?.toString()
            val parsed = ctx.parser.parseWithRecovery(stripped, file = fileName).module
            val projectRoot = if (ctx.workspaceRoot != null) java.nio.file.Paths.get(ctx.workspaceRoot)
                else ctx.uriToPath(ctx.uri)?.parent ?: java.nio.file.Paths.get(".")
            val typed = when (val r = ctx.typeChecker.checkParsedModule(parsed, projectRoot, null, enablePrelude = true)) {
                is com.mezze.language.typechecker.TypeChecker.TypeCheckResult.Success -> r.typedModule
                is com.mezze.language.typechecker.TypeChecker.TypeCheckResult.Failure -> r.typedModule
            } ?: return null
            val useLine = li + 1
            val useCol = dotIdx
            (TypedAstLookup.findAtPosition(typed, useLine, useCol) as? TypedAstLookup.LookupResult.Expr)
                ?.expr?.type
                ?.takeIf { it !is MezzeType.VarType }
                ?.let { return it }
            typed.decls.filterIsInstance<com.mezze.language.typedast.TypedLetDecl>()
                .firstOrNull { it.name == name }
                ?.scheme?.type
                ?.takeIf { it !is MezzeType.VarType }
                ?.let { return it }
            findLocalBindingType(name, typed, line + 1)?.takeIf { it !is MezzeType.VarType }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Expression-chain analogue: for a dangling dot after a call/index/group
     * (`nums.first {}.`, `(a + b).`, `xs[0].`) the receiver has no single name,
     * so complete the dot to `.to_dbg {}`, recheck to a solved module, and read
     * the type of the expression that ENDS at the original closing delimiter.
     */
    private fun stripDotAndResolveExpressionReceiver(ctx: CompletionContext, line: Int, character: Int): MezzeType? {
        return try {
            val lines = ctx.content.lines().toMutableList()
            val li = line
            if (li !in lines.indices) return null
            val srcLine = lines[li]
            val dotIdx = character - 1
            if (dotIdx < 0 || dotIdx >= srcLine.length || srcLine[dotIdx] != '.') return null
            val closerIdx = dotIdx - 1
            if (closerIdx < 0) return null
            val closer = srcLine[closerIdx]
            if (closer != '}' && closer != ')' && closer != ']') return null
            lines[li] = srcLine.substring(0, dotIdx) + ".to_dbg {}" + srcLine.substring(dotIdx + 1)
            val stripped = lines.joinToString("\n")
            val fileName = ctx.uriToPath(ctx.uri)?.fileName?.toString()
            val parsed = ctx.parser.parseWithRecovery(stripped, file = fileName).module
            val projectRoot = if (ctx.workspaceRoot != null) java.nio.file.Paths.get(ctx.workspaceRoot)
                else ctx.uriToPath(ctx.uri)?.parent ?: java.nio.file.Paths.get(".")
            val typed = when (val r = ctx.typeChecker.checkParsedModule(parsed, projectRoot, null, enablePrelude = true)) {
                is com.mezze.language.typechecker.TypeChecker.TypeCheckResult.Success -> r.typedModule
                is com.mezze.language.typechecker.TypeChecker.TypeCheckResult.Failure -> r.typedModule
            } ?: return null
            val endLine = li + 1
            val endCol = closerIdx + 1
            TypedAstLookup.findExprEndingAt(typed, endLine, endCol)
                ?.type
                ?.takeIf { it !is MezzeType.VarType }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Find the receiver type for an arbitrary expression-receiver dot completion.
     */
    private fun findReceiverTypeForExpressionAt(
        ctx: CompletionContext,
        line: Int,
        character: Int,
        result: TypeCheckResult
    ): MezzeType? {
        val lines = ctx.content.lines()
        if (line >= lines.size) return null
        val lineText = lines[line]
        val beforeCursor = lineText.substring(0, minOf(character, lineText.length))
        val dotIdx = beforeCursor.lastIndexOf('.')
        if (dotIdx < 0) return null

        val charBeforeDot = dotIdx - 1
        val endsInCloser = charBeforeDot >= 0 &&
            lineText[charBeforeDot].let { it == '}' || it == ')' || it == ']' }

        // 1. Typed-AST lookup.
        val typedModule = result.typedModule ?: ctx.lastSuccessful?.typedModule
        if (typedModule != null && charBeforeDot >= 0) {
            val lookupLine = line + 1
            val lookupCol = charBeforeDot + 1
            if (endsInCloser) {
                val enclosing = TypedAstLookup.findExprEndingAt(typedModule, lookupLine, lookupCol)
                val t = enclosing?.type
                if (t != null && t !is MezzeType.VarType) return t
                stripDotAndResolveExpressionReceiver(ctx, line, character)?.let { return it }
            } else {
                val lookup = TypedAstLookup.findAtPosition(typedModule, lookupLine, lookupCol)
                if (lookup is TypedAstLookup.LookupResult.Expr) {
                    val t = lookup.expr.type
                    if (t !is MezzeType.VarType) return t
                }
            }
        }

        // 2. Syntactic fallback for literal receivers.
        val receiverText = scanReceiverExpression(beforeCursor, dotIdx) ?: return null
        return classifyLiteralType(receiverText)
    }

    /**
     * Walk backwards from `dotIdx` matching bracket pairs to find where the
     * receiver expression begins.
     */
    private fun scanReceiverExpression(line: String, dotIdx: Int): String? {
        var i = dotIdx - 1
        while (i >= 0 && line[i].isWhitespace()) i--
        if (i < 0) return null

        val ch = line[i]
        if (ch == ']' || ch == ')' || ch == '}') {
            val open = when (ch) { ']' -> '['; ')' -> '('; '}' -> '{'; else -> return null }
            var depth = 1
            i--
            while (i >= 0 && depth > 0) {
                when (line[i]) {
                    ch -> depth++
                    open -> depth--
                }
                if (depth == 0) break
                i--
            }
            if (depth != 0 || i < 0) return null
            return line.substring(i, dotIdx).trim()
        }
        if (ch == '"') {
            var j = i - 1
            while (j >= 0 && line[j] != '"') j--
            if (j < 0) return null
            return line.substring(j, dotIdx).trim()
        }
        var j = i
        while (j >= 0 && (line[j].isLetterOrDigit() || line[j] == '_')) j--
        return line.substring(j + 1, dotIdx).trim()
    }

    /**
     * Classify a receiver text by syntactic shape.
     */
    private fun classifyLiteralType(receiverText: String): MezzeType? {
        if (receiverText.isEmpty()) return null
        val first = receiverText.first()
        return when {
            first == '[' && receiverText.endsWith("]") -> {
                MezzeType.SeqType(MezzeType.VarType(com.mezze.language.ast.TypeVar.fresh()))
            }
            first == '"' && receiverText.endsWith("\"") -> MezzeType.StringType
            first == '\'' && receiverText.endsWith("'") -> MezzeType.CharType
            first.isDigit() || (first == '-' && receiverText.length > 1 && receiverText[1].isDigit()) -> {
                if (receiverText.contains('.')) MezzeType.FloatType else MezzeType.IntType
            }
            receiverText == BuiltinTypes.TRUE || receiverText == BuiltinTypes.FALSE ||
                receiverText == BuiltinTypes.TRUE.lowercase() || receiverText == BuiltinTypes.FALSE.lowercase() -> MezzeType.BoolType
            else -> null
        }
    }

    /**
     * Conservative check for "cursor at the start of a fresh top-level statement".
     */
    private fun isAtTopLevelStart(ctx: CompletionContext, line: Int, character: Int): Boolean {
        val lines = ctx.content.split('\n')
        val lineText = lines.getOrNull(line) ?: return false
        val cursor = character.coerceIn(0, lineText.length)
        val before = lineText.substring(0, cursor)
        if (before.isNotEmpty() && before.first().isWhitespace()) return false
        if (before.any { !it.isLetterOrDigit() && it != '_' }) return false
        return true
    }

    /**
     * Best-effort: is the cursor in a TYPE-ANNOTATION position?
     */
    private fun isInTypeAnnotationPosition(ctx: CompletionContext, line: Int, character: Int): Boolean {
        val lineText = ctx.content.split('\n').getOrNull(line) ?: return false
        val cursor = character.coerceIn(0, lineText.length)
        var before = lineText.substring(0, cursor)
        if (before.count { it == '"' } % 2 == 1) return false
        before = before.trimEnd { it.isLetterOrDigit() || it == '_' || it == '\'' || it == '.' || it == ' ' }
        if (before.endsWith("->")) {
            val colonIdx = before.indexOf(':')
            val hasTypeColon = colonIdx >= 0 && (colonIdx == 0 || before[colonIdx - 1] != ':')
            val hasEquals = before.contains('=')  // `=` means a value body, not a sig
            return hasTypeColon && !hasEquals
        }
        if (before.endsWith(":") && !before.endsWith("::")) {
            val lastBrace = before.lastIndexOf('{')
            val eqAfterBrace = before.lastIndexOf('=') > lastBrace
            return !eqAfterBrace || lastBrace < 0
        }
        return false
    }

    /**
     * Op-name completions inside an impl body. Returns null when the cursor
     * isn't in an impl body.
     */
    private fun getImplBodyOpCompletions(ctx: CompletionContext, line: Int, character: Int): List<CompletionCandidate>? {
        val pm = ctx.parsedModule ?: return null
        val lines = ctx.content.lines()
        val lineText = lines.getOrNull(line) ?: return null
        val before = lineText.substring(0, character.coerceIn(0, lineText.length))
        if (!Regex("^\\s*[a-z_][a-zA-Z0-9_]*$|^\\s*$").matches(before)) return null
        val prefix = before.trim()
        val lineNo = line + 1

        for (decl in pm.decls) {
            val (declLoc, opDecls, implementedNames) = when (decl) {
                is com.mezze.language.parsedast.ParsedImplAbilityDecl -> {
                    val ability = pm.decls.filterIsInstance<ParsedAbilityDecl>()
                        .find { it.name == decl.abilityName } ?: continue
                    Triple(decl.loc, ability.operations.map { it.name }, decl.operations.map { it.name }.toSet())
                }
                is ParsedPerformerDecl -> {
                    val effect = pm.decls.filterIsInstance<ParsedEffectDecl>()
                        .find { it.name == decl.effect.localName } ?: continue
                    Triple(decl.loc, effect.operations.map { it.name }, decl.operations.map { it.name }.toSet())
                }
                else -> continue
            }
            if (lineNo < declLoc.startLine || lineNo > declLoc.endLine) continue

            val items = mutableListOf<CompletionCandidate>()
            for (opName in opDecls) {
                if (opName in implementedNames) continue
                if (prefix.isNotEmpty() && !opName.startsWith(prefix)) continue
                items.add(candidate(opName) {
                    kind = CompletionKind.Method
                    detail = "impl op"
                    insertText = "$opName = {\${1:it}} -> \${0:body}"
                    snippet = true
                })
            }
            return items
        }
        return null
    }

    /**
     * Ability/effect NAME completions in a type signature. Returns null when
     * the cursor isn't in either position.
     */
    private fun getAbilityEffectNameCompletions(ctx: CompletionContext, line: Int, character: Int): List<CompletionCandidate>? {
        val lines = ctx.content.lines()
        val lineText = lines.getOrNull(line) ?: return null
        val before = lineText.substring(0, character.coerceIn(0, lineText.length))
        val prefix = Regex("[A-Za-z_][A-Za-z0-9_]*$").find(before)?.value ?: ""
        val head = before.dropLast(prefix.length)
        val inConstraint = Regex("\\bwith\\b[^<>]*$").containsMatchIn(head)
        val inEffectRow = head.lastIndexOf('<') > head.lastIndexOf('>')
        if (!inConstraint && !inEffectRow) return null

        val pm = ctx.parsedModule
        val names = LinkedHashSet<String>()
        pm?.decls?.forEach {
            when (it) {
                is ParsedAbilityDecl -> names.add(it.name)
                is ParsedEffectDecl -> names.add(it.name)
                else -> {}
            }
        }
        ctx.typeCheckResult?.resolvedImports?.forEach {
            if (it.kind == ImportKind.ABILITY || it.kind == ImportKind.EFFECT) names.add(it.localName)
        }
        ctx.typeCheckResult?.importedAbilities?.keys?.forEach { names.add(it) }

        return names
            .filter { prefix.isEmpty() || it.startsWith(prefix) }
            .map { name ->
                candidate(name) {
                    kind = CompletionKind.Interface
                    detail = if (inConstraint) "ability" else "effect"
                }
            }
    }

    private fun getKeywordCompletions(atTopLevel: Boolean): List<CompletionCandidate> {
        val items = mutableListOf<CompletionCandidate>()

        fun snippet(label: String, body: String, detail: String) {
            items.add(candidate(label) {
                kind = CompletionKind.Keyword
                this.detail = detail
                insertText = body
                this.snippet = true
            })
        }
        fun plain(label: String) {
            items.add(candidate(label) { kind = CompletionKind.Keyword })
        }

        if (atTopLevel) {
            snippet("let",
                "let \${1:name} = \${2:value}",
                "let binding")
            snippet("pub let",
                "pub let \${1:name} = \${2:value}",
                "exported let")
            snippet("use",
                "use \${1:module::path}",
                "import")
            snippet("type",
                "type \${1:Name} = { \${2:field}: \${3:Type} }",
                "type declaration")
            snippet("effect",
                "effect \${1:Name} {\n    \${2:op}: \${3:ParamType} -> \${4:ReturnType}\n}",
                "effect declaration")
            snippet("ability",
                "ability \${1:Name} for \${2:'a} where {\n    \${3:method}: \${2:'a} -> \${4:ReturnType}\n}",
                "ability declaration")
            snippet("impl",
                "impl \${1:Ability} for \${2:Type} where {\n    \${3:method} = {\${4:it}} -> \${5:body}\n}",
                "ability impl")
            snippet("impl effect",
                "impl \${1:Effect} for \${2:Performer} where {\n    \${3:op} = {\${4:args}} -> \${5:body}\n}",
                "effect performer impl")
        }

        // Expression-form keyword snippets.
        snippet("if",
            "if \${1:cond} then \${2:then_expr} else \${3:else_expr}",
            "conditional")
        snippet("match",
            "match \${1:expr} is\n| \${2:pattern} -> \${3:result}",
            "pattern match")
        snippet("do",
            "do\n    \$0\nend",
            "do block")
        snippet("perform",
            "perform \${1:handler} in do\n    \$0\nend",
            "perform with handler")
        // Bare modifiers — no snippet bodies make sense for these standalone.
        listOf("as", "where", "with", "for", "in", "then", "else", "is").forEach(::plain)

        return items
    }

    private fun getBindingCompletions(ctx: CompletionContext): List<CompletionCandidate> {
        val typedModule = ctx.typeCheckResult?.typedModule
        val parsedModule = ctx.parsedModule
        val items = mutableListOf<CompletionCandidate>()

        // Add let bindings (functions and values) from TypedModule
        typedModule?.decls
            ?.filterIsInstance<com.mezze.language.typedast.TypedLetDecl>()
            ?.forEach { decl ->
                val ty = decl.scheme.type
                items.add(candidate(decl.name) {
                    kind = if (ty is MezzeType.FunctionType) CompletionKind.Function else CompletionKind.Variable
                    detail = prettyPrintTypeWithAliases(ty, ctx.typeAliases)
                    resolveData = completionResolveKey(ctx.uri, decl.name)
                    val snippet = callInsertSnippet(decl.name, ty)
                    if (snippet != null) {
                        insertText = snippet
                        this.snippet = true
                    }
                })
            }

        // Add type declarations from parsed module
        parsedModule?.decls?.filterIsInstance<ParsedTypeDecl>()?.forEach { typeDecl ->
            items.add(candidate(typeDecl.name) {
                kind = CompletionKind.Struct
                detail = "type"
            })
        }

        // Add effect declarations from parsed module
        parsedModule?.decls?.filterIsInstance<ParsedEffectDecl>()?.forEach { effectDecl ->
            items.add(candidate(effectDecl.name) {
                kind = CompletionKind.Interface
                detail = "effect"
            })
        }

        // Add ability declarations from parsed module
        parsedModule?.decls?.filterIsInstance<ParsedAbilityDecl>()?.forEach { abilityDecl ->
            items.add(candidate(abilityDecl.name) {
                kind = CompletionKind.Interface
                detail = "ability"
            })
        }

        // Add imports (if type check succeeded)
        ctx.typeCheckResult?.resolvedImports?.forEach { import ->
            items.add(candidate(import.localName) {
                kind = when (import.kind) {
                    ImportKind.FUNCTION -> CompletionKind.Function
                    ImportKind.TYPE -> CompletionKind.Struct
                    ImportKind.EFFECT -> CompletionKind.Interface
                    ImportKind.ABILITY -> CompletionKind.Interface
                    ImportKind.PERFORMER -> CompletionKind.Class
                }
                detail = prettyPrintTypeWithAliases(import.type, ctx.typeAliases)
                if (import.kind == ImportKind.FUNCTION) {
                    val snippet = callInsertSnippet(import.localName, import.type)
                    if (snippet != null) {
                        insertText = snippet
                        this.snippet = true
                    }
                }
            })
        }

        return items
    }

    /**
     * Get local variable completions — every binding in scope at the cursor.
     */
    private fun getLocalVariableCompletions(ctx: CompletionContext, line: Int, character: Int): List<CompletionCandidate> {
        val typedModule = ctx.typeCheckResult?.typedModule
            ?: ctx.lastSuccessful?.typedModule
            ?: return emptyList()
        val lineNo = line + 1
        val col = character + 1
        val scope = TypedAstScopeWalker.bindingsAtPosition(typedModule, lineNo, col)
        if (scope.isEmpty()) return emptyList()

        // Inner-most shadow wins.
        val byName = LinkedHashMap<String, TypedAstScopeWalker.ScopeBinding>()
        for (b in scope) byName[b.name] = b

        return byName.values.map { b ->
            candidate(b.name) {
                kind = CompletionKind.Variable
                detail = prettyPrintTypeWithAliases(b.type, ctx.typeAliases)
                sortText = "1_${b.name}"
            }
        }
    }

    /**
     * Get variant constructor completions for match patterns.
     */
    private fun getMatchPatternCompletions(ctx: CompletionContext, line: Int, character: Int): List<CompletionCandidate> {
        val lines = ctx.content.lines()
        if (line >= lines.size) return emptyList()

        val matchContext = findMatchContext(lines, line, character) ?: return emptyList()

        val result = ctx.typeCheckResult ?: return emptyList()
        val scrutineeType = findExpressionType(matchContext.scrutinee, result)
            ?: return emptyList()

        val patterns = getVariantConstructorCompletionsForType(scrutineeType, ctx)
        return patterns.map { item ->
            if (item.label == "_") return@map item
            val original = item.insertText ?: item.label
            item.apply {
                insertText = "$original -> \${0}"
                snippet = true
            }
        }
    }

    /**
     * Context information for a match expression being edited.
     */
    private data class MatchContext(
        val scrutinee: String,
        val existingPatterns: Set<String>
    )

    /**
     * Find the match expression context at the current position.
     */
    private fun findMatchContext(lines: List<String>, line: Int, character: Int): MatchContext? {
        val currentLineText = lines[line].substring(0, minOf(character, lines[line].length))

        var searchText = ""
        for (i in line downTo maxOf(0, line - 20)) {
            val lineStr = if (i == line) currentLineText else lines[i]

            if (i < line && lineStr.trimStart().let {
                it.startsWith("let ") || it.startsWith("type ") || it.startsWith("effect ") ||
                it.startsWith("ability ") || it.startsWith("impl ") || it.startsWith("pub ") ||
                it.startsWith("use ")
            }) {
                break
            }

            searchText = lineStr + "\n" + searchText
        }

        val matchRegex = Regex("""match\s+(\S+(?:\s*\{[^}]*\})?)\s+is""", RegexOption.DOT_MATCHES_ALL)
        val matchResult = matchRegex.findAll(searchText).lastOrNull() ?: return null

        val scrutinee = matchResult.groupValues[1].trim()

        val matchEndIndex = matchResult.range.last
        val textAfterMatch = searchText.substring(matchEndIndex + 1)

        val currentLineInSearch = searchText.lines().lastOrNull() ?: ""
        val isInPatternPosition = textAfterMatch.contains("\n") ||
                                   textAfterMatch.trim().isEmpty() ||
                                   currentLineInSearch.trim().let {
                                       it.isEmpty() ||
                                       it.matches(Regex("""^[A-Z][a-zA-Z0-9_]*.*""")) ||
                                       it.endsWith("->")
                                   }

        if (!isInPatternPosition) return null

        val existingPatterns = mutableSetOf<String>()
        val afterMatch = searchText.substringAfter("is", "")
        val patternRegex = Regex("""^\s*([A-Z][a-zA-Z0-9_]*)(?:\s*\{[^}]*\})?\s*->""", RegexOption.MULTILINE)
        patternRegex.findAll(afterMatch).forEach { result ->
            existingPatterns.add(result.groupValues[1])
        }

        return MatchContext(scrutinee, existingPatterns)
    }

    /**
     * Find the type of an expression by name (variable lookup).
     */
    private fun findExpressionType(exprName: String, result: TypeCheckResult): MezzeType? {
        val cleanName = exprName.trim().split(Regex("""\s*\{""")).firstOrNull()?.trim() ?: exprName

        val typedBinding = result.typedModule?.decls
            ?.filterIsInstance<com.mezze.language.typedast.TypedLetDecl>()
            ?.find { it.name == cleanName }

        if (typedBinding != null) {
            val bindingType = typedBinding.scheme.type
            if (bindingType !is MezzeType.VarType) return bindingType
        }

        val import = result.resolvedImports.find { it.localName == cleanName }
        if (import != null) {
            return import.type
        }

        result.typedModule?.decls
            ?.filterIsInstance<com.mezze.language.typedast.TypedLetDecl>()
            ?.forEach { decl ->
                val paramType = findParameterTypeInTypedBinding(cleanName, decl)
                if (paramType != null) {
                    return paramType
                }
            }

        return null
    }

    /**
     * Find a parameter type in a typed let binding's function type.
     */
    private fun findParameterTypeInTypedBinding(paramName: String, decl: com.mezze.language.typedast.TypedLetDecl): MezzeType? {
        val funcType = decl.scheme.type
        if (funcType is MezzeType.FunctionType) {
            val paramType = funcType.paramType
            if (paramType is MezzeType.RecordType) {
                return paramType.fields[paramName]
            }
        }
        return null
    }

    /**
     * Get variant constructor completions for a specific variant type.
     */
    private fun getVariantConstructorCompletionsForType(type: MezzeType, ctx: CompletionContext): List<CompletionCandidate> {
        val variantType = when (type) {
            is MezzeType.VariantType -> type
            is MezzeType.TypeApplication -> {
                findVariantTypeFromApplication(type, ctx)
            }
            else -> null
        } ?: return emptyList()

        val items = mutableListOf<CompletionCandidate>()

        variantType.constructors.entries.forEachIndexed { index, (name, payload) ->
            val item = candidate(name) {
                kind = CompletionKind.EnumMember
                sortText = index.toString().padStart(3, '0')

                if (payload != null) {
                    val payloadStr = when (payload) {
                        is MezzeType.RecordType -> {
                            val fields = payload.fields.keys.mapIndexed { i, field ->
                                "$field = \${${i + 1}:}"
                            }.joinToString(", ")
                            " { $fields }"
                        }
                        else -> {
                            val typeStr = prettyPrintTypeWithAliases(payload, ctx.typeAliases)
                            " { \${1:value}: $typeStr }"
                        }
                    }
                    detail = "with payload"
                    insertText = name + payloadStr
                    snippet = true
                } else {
                    detail = "unit constructor"
                    insertText = name
                }
            }
            items.add(item)
        }

        // Add wildcard pattern at the end
        items.add(candidate("_") {
            kind = CompletionKind.Keyword
            detail = "wildcard (matches anything)"
            insertText = "_ -> \${1:}"
            snippet = true
            sortText = "zzz"  // Show last
        })

        return items
    }

    /**
     * Find the variant type from a type application.
     */
    private fun findVariantTypeFromApplication(typeApp: MezzeType.TypeApplication, ctx: CompletionContext): MezzeType.VariantType? {
        val typeName = typeApp.constructor
        val parsedModule = ctx.parsedModule ?: return null

        val typeDecl = parsedModule.decls.filterIsInstance<ParsedTypeDecl>().find { it.name == typeName }
        return when (val def = typeDecl?.definition) {
            is ParsedVariantDef -> {
                val constructors = def.constructors.associate { it.name to null as MezzeType? }
                MezzeType.VariantType(constructors, null)
            }
            else -> null
        }
    }

    /**
     * Get all visible variant constructors as completions.
     */
    private fun getVariantConstructorCompletions(ctx: CompletionContext): List<CompletionCandidate> {
        val parsedModule = ctx.parsedModule
        val items = mutableListOf<CompletionCandidate>()

        // Add constructors from local type declarations
        if (parsedModule != null) {
            for (decl in parsedModule.decls.filterIsInstance<ParsedTypeDecl>()) {
                when (val def = decl.definition) {
                    is ParsedVariantDef -> {
                        for (constructor in def.constructors) {
                            items.add(candidate(constructor.name) {
                                kind = CompletionKind.EnumMember
                                detail = decl.name

                                if (constructor.dataType != null) {
                                    insertText = "${constructor.name} { \${1:} }"
                                    snippet = true
                                } else {
                                    insertText = constructor.name
                                }
                            })
                        }
                    }
                    else -> {}
                }
            }
        }

        // Add Bool constructors (built-in)
        items.add(candidate(BuiltinTypes.TRUE) {
            kind = CompletionKind.EnumMember
            detail = BuiltinTypes.BOOL
        })
        items.add(candidate(BuiltinTypes.FALSE) {
            kind = CompletionKind.EnumMember
            detail = BuiltinTypes.BOOL
        })

        // Add Option constructors (commonly used)
        items.add(candidate(BuiltinTypes.SOME) {
            kind = CompletionKind.EnumMember
            detail = BuiltinTypes.OPTION
            insertText = "${BuiltinTypes.SOME} { ${BuiltinTypes.VAL_FIELD} = \${1:} }"
            snippet = true
        })
        items.add(candidate(BuiltinTypes.NONE) {
            kind = CompletionKind.EnumMember
            detail = BuiltinTypes.OPTION
        })

        // Add Result constructors (commonly used)
        items.add(candidate(BuiltinTypes.OK) {
            kind = CompletionKind.EnumMember
            detail = BuiltinTypes.RESULT
            insertText = "${BuiltinTypes.OK} { ${BuiltinTypes.VAL_FIELD} = \${1:} }"
            snippet = true
        })
        items.add(candidate(BuiltinTypes.ERR) {
            kind = CompletionKind.EnumMember
            detail = BuiltinTypes.RESULT
            insertText = "${BuiltinTypes.ERR} { ${BuiltinTypes.ERROR_FIELD} = \${1:} }"
            snippet = true
        })

        return items
    }

    /** Build the data payload the client hands back to completionItem/resolve. */
    private fun completionResolveKey(uri: String, name: String): Map<String, String> =
        mapOf("uri" to uri, "name" to name)

    /**
     * Find a local binding's type by searching through the typed module.
     */
    private fun findLocalBindingType(name: String, typedModule: com.mezze.language.typedast.TypedModule?, line: Int): MezzeType? {
        if (typedModule == null) return null
        for (decl in typedModule.decls) {
            if (decl is com.mezze.language.typedast.TypedLetDecl &&
                line >= decl.loc.startLine && line <= decl.loc.endLine
            ) {
                val found = searchLocalBindingInExpr(decl.value, name, line)
                if (found != null) return found
            }
        }
        return null
    }

    /**
     * Walk a typed expression looking for a binding of [name] introduced before [cursorLine].
     */
    private fun searchLocalBindingInExpr(
        expr: com.mezze.language.typedast.TypedExpr,
        name: String,
        cursorLine: Int
    ): MezzeType? {
        var best: Pair<Int, MezzeType>? = null
        fun consider(loc: com.mezze.language.parsedast.Loc, type: MezzeType) {
            if (loc.startLine > cursorLine) return
            val cur = best
            if (cur == null || loc.startLine > cur.first) best = loc.startLine to type
        }
        fun visit(e: com.mezze.language.typedast.TypedExpr) {
            if (cursorLine < e.loc.startLine || cursorLine > e.loc.endLine + 1) return
            when (e) {
                is com.mezze.language.typedast.TypedBlock -> {
                    for (stmt in e.statements) {
                        when (stmt) {
                            is com.mezze.language.typedast.TypedStatement.Let -> {
                                if (stmt.name == name) consider(stmt.loc, stmt.type)
                                visit(stmt.value)
                            }
                            is com.mezze.language.typedast.TypedStatement.Expr -> visit(stmt.expr)
                        }
                    }
                    visit(e.result)
                }
                is com.mezze.language.typedast.TypedLambda -> {
                    for (p in e.params) {
                        if (p.name == name) consider(p.loc, p.type)
                    }
                    visit(e.body)
                }
                is com.mezze.language.typedast.TypedIf -> { visit(e.condition); visit(e.thenBranch); visit(e.elseBranch) }
                is com.mezze.language.typedast.TypedMatch -> {
                    visit(e.scrutinee)
                    for (case in e.cases) {
                        for (b in case.bindings) {
                            if (b.name == name) consider(b.loc, b.type)
                        }
                        for (ilb in case.ifLetBindings) visit(ilb.expr)
                        case.guard?.let { visit(it) }
                        visit(case.body)
                    }
                }
                is com.mezze.language.typedast.TypedCall -> { visit(e.function); visit(e.arg) }
                is com.mezze.language.typedast.TypedRecord -> {
                    for (entry in e.entries) when (entry) {
                        is com.mezze.language.typedast.TypedRecordEntry.Field -> visit(entry.value)
                        is com.mezze.language.typedast.TypedRecordEntry.Spread -> visit(entry.expr)
                    }
                }
                is com.mezze.language.typedast.TypedFieldAccess -> visit(e.record)
                is com.mezze.language.typedast.TypedRecordUpdate -> {
                    visit(e.record)
                    for (entry in e.updates) when (entry) {
                        is com.mezze.language.typedast.TypedRecordEntry.Field -> visit(entry.value)
                        is com.mezze.language.typedast.TypedRecordEntry.Spread -> visit(entry.expr)
                    }
                }
                is com.mezze.language.typedast.TypedVariantConstruct -> e.payload?.let { visit(it) }
                is com.mezze.language.typedast.TypedSeq -> {
                    for (el in e.elements) when (el) {
                        is com.mezze.language.typedast.TypedSeqElement.Regular -> visit(el.expr)
                        is com.mezze.language.typedast.TypedSeqElement.Spread -> visit(el.expr)
                    }
                }
                is com.mezze.language.typedast.TypedPerform -> {
                    for (h in e.handlers) h.params?.let { visit(it) }
                    visit(e.body)
                }
                is com.mezze.language.typedast.TypedPerformOp -> visit(e.arg)
                is com.mezze.language.typedast.TypedPipe -> { visit(e.left); visit(e.right) }
                else -> { /* leaves: literals, TypedVar, TypedStringLit, etc. */ }
            }
        }
        visit(expr)
        return best?.second
    }

    /**
     * Build a NeutralTextEdit that inserts `use <module>::<name>` in the right place.
     */
    private fun buildAutoImportEdit(ctx: CompletionContext, modulePath: String, name: String): NeutralTextEdit? {
        val parsed = ctx.parsedModule ?: return null
        val useDecls = parsed.decls.filterIsInstance<com.mezze.language.parsedast.ParsedUseDecl>()

        val formatted = ctx.formatUseSnippet("use $modulePath::$name\n")
        val newLine = if (formatted.endsWith("\n")) formatted else formatted + "\n"

        if (useDecls.isNotEmpty()) {
            val last = useDecls.maxBy { it.loc.endLine }
            val insertLine = last.loc.endLine  // 1-based — that's the next line in 0-based LSP
            return NeutralTextEdit(insertLine, 0, insertLine, 0, newLine)
        }

        val firstDecl = parsed.decls.firstOrNull()
        if (firstDecl != null) {
            val insertLine = firstDecl.loc.startLine - 1  // 1-based -> 0-based
            return NeutralTextEdit(insertLine, 0, insertLine, 0, newLine + "\n")
        }

        return NeutralTextEdit(0, 0, 0, 0, newLine + "\n")
    }
}
