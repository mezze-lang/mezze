package com.mezze.language.typechecker.macro

import com.mezze.language.ast.MezzeType
import com.mezze.language.comptime.Comptime
import com.mezze.language.comptime.HostValue
import com.mezze.language.comptime.HostValue.Companion.record
import com.mezze.language.comptime.InvokeOutcome
import com.mezze.language.comptime.SyntaxDecoder
import com.mezze.language.comptime.SyntaxEncoder
import com.mezze.language.comptime.SyntaxHost
import com.mezze.language.parsedast.*
import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.parser.MezzeParser
import com.mezze.language.parser.ParseException
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.typechecker.IrClosure
import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.PrecompiledStdlib
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.typechecker.module.ModuleLoadResult
import com.mezze.language.typechecker.module.ModuleLoader
import com.mezze.language.typechecker.module.ModuleTypeCheckResult
import com.mezze.language.typechecker.module.ModuleTypeChecker
import com.mezze.language.typechecker.module.TypedModuleResult
import com.mezze.language.typedast.Hash
import com.mezze.language.typedast.TypedLetDecl
import com.mezze.language.store.HostValueCodec

/**
 * `name!( … )`: runs each macro call in a module and puts what it produced in its
 * place, before the module is identified or type checked.
 *
 * ## When
 *
 * On the module as parsed, before its declarations get their identities. A
 * declaration's identity covers what it compiles to, so it must see the
 * expansion; and expanding after identities were taken would leave every module
 * that imports this one identified against code that no longer exists.
 *
 * By then every module this one imports has been parsed and identified (they
 * are dependencies), so the macro's own module can be type checked and lowered
 * on demand. A macro cannot come from the module that calls it: that module is
 * what is being compiled.
 *
 * ## The cache
 *
 * A macro is pure (its type allows no effects), and its identity covers its code
 * and everything it calls. So (macro identity, input text) decides the output,
 * and an expansion computed once is reused for as long as the process lives —
 * across modules, rebuilds and editor keystrokes. The macro is run with its input
 * at a fixed origin, and the spans it returns are moved to where the input is;
 * moving a call, or editing around it, reuses the expansion.
 *
 * ## Errors
 *
 * Nothing here throws for a problem in the program. Each problem becomes a
 * [MacroDiagnostic] on the call, which the type checker reports where it points.
 */
internal class MacroExpander(
    private val resolver: ModuleResolver,
    /** The modules being identified together with this one: none of them is compiled yet. */
    private val group: Set<String>,
) {

    fun expand(parsed: ParsedModule, modulePath: String?): ParsedModule {
        val module = QuoteTypes.inScope(parsed)
        if (module.decls.none { it.hasMacroCall() }) return module
        val imports = Imports(module)
        val expanded = module.decls.flatMap { d ->
            // `name!( … )` standing as a declaration: what it produces are
            // declarations, put in its place. A derive is one of these.
            val call = (d as? ParsedTopLevelExpr)?.expression as? ParsedMacroCall
            if (call != null) declarations(d, call, imports, modulePath)
            else listOf(d.mapExprs { e -> rewrite(e, imports, modulePath, depth = 0) })
        }
        val at = module.decls.first().loc.let { Loc(it.file, 1, 1, 1, 1) }
        return module.copy(decls = Hygiene.imports(handedOver, at) + expanded)
    }

    /** Names expansions in this module use from their macros' modules, under hidden names (see [Hygiene]). */
    private val handedOver = LinkedHashMap<Pair<String, String>, String>()

    private fun declarations(
        written: ParsedTopLevelExpr,
        call: ParsedMacroCall,
        imports: Imports,
        modulePath: String?,
    ): List<ParsedDecl> {
        val expanded = expandCall(call.copy(expansion = null, diagnostics = emptyList()), imports, modulePath, asDeclarations = true)
        val decls = (expanded.expansion as? ParsedCode.Decls)?.decls
        // A call that could not be expanded stays, carrying why, for the type
        // checker to report where it is written.
        if (decls == null || expanded.diagnostics.isNotEmpty()) return listOf(written.copy(expression = expanded.copy(expansion = null)))
        return decls.map { d -> d.mapExprs { e -> rewrite(e, imports, modulePath, depth = 1) } }
    }

    private fun rewrite(e: ParsedExpr, imports: Imports, modulePath: String?, depth: Int): ParsedExpr {
        val inner = e.mapChildren { rewrite(it, imports, modulePath, depth) }
        if (inner !is ParsedMacroCall) return inner
        // Always expanded afresh, even when an expansion came with the parse (a
        // stored module): the macro may have changed since. The cache makes an
        // unchanged one free.
        val expanded = expandCall(inner.copy(expansion = null, diagnostics = emptyList()), imports, modulePath)
        // What a macro produced may call macros itself.
        val code = expanded.expansion as? ParsedCode.Expr ?: return expanded
        if (!code.expr.hasMacroCall()) return expanded
        if (depth >= MAX_DEPTH) {
            return expanded.copy(expansion = null, diagnostics = expanded.diagnostics + MacroDiagnostic(
                ErrorCode.V1003_MACRO_FAILED,
                "`${inner.macro.localName}!` expands to macro calls $MAX_DEPTH levels deep; its expansion does not end",
                inner.loc,
            ))
        }
        return expanded.copy(expansion = ParsedCode.Expr(rewrite(code.expr, imports, modulePath, depth + 1)))
    }

    private fun expandCall(
        call: ParsedMacroCall,
        imports: Imports,
        modulePath: String?,
        asDeclarations: Boolean = false,
    ): ParsedMacroCall {
        fun fail(code: ErrorCode, message: String, hints: List<String> = emptyList()) =
            call.copy(diagnostics = listOf(MacroDiagnostic(code, message, call.loc, hints)))

        val (macroModule, name) = imports.resolve(call.macro)
            ?: return fail(
                ErrorCode.V1001_MACRO_NOT_FOUND,
                "no macro `${call.macro.localName}` is imported here",
                listOf("import it from the module that declares it: `use <module>::{ ${call.macro.localName} }`"),
            )
        if (macroModule == modulePath || macroModule in group) {
            return fail(
                ErrorCode.V1001_MACRO_NOT_FOUND,
                if (macroModule == modulePath) "`$name` is declared in this module, which is not compiled yet when its macros run"
                else "`$name` is declared in `$macroModule`, which imports this module back, so neither is compiled before the other",
                listOf("move `$name` to a module of its own, that this one imports and that does not import this one"),
            )
        }
        // The macro's identity comes with its module's parse: enough to find an
        // expansion made before, in this process or a past one, without type
        // checking the macro's module or starting an interpreter. Those happen
        // only for an input this macro has not seen.
        val source = when (val s = macroSource(macroModule, name)) {
            is Source.Found -> s
            is Source.Problem -> return fail(s.code, s.message, s.hints)
        }
        val key = CacheKey(source.identity, call.input)
        val output = CACHE.get(key) ?: stored(key)?.also { CACHE.put(key, it) } ?: run {
            val macro = when (val m = macroFunction(macroModule, name)) {
                is Found.Macro -> m
                is Found.Problem -> return fail(m.code, m.message, m.hints)
            }
            val evaluator = Comptime.obtain()
                ?: return fail(ErrorCode.V1003_MACRO_FAILED, "this `mez` has no evaluator, so it cannot run macros")
            val argument = record("input" to record(
                "text" to HostValue.Str(call.input),
                "span" to inputOrigin(call.input),
            ))
            when (val outcome = evaluator.invoke(
                macro.functions, macro.hash, argument, macro.performers,
                origin = call.loc.toString(), syntax = Parsing,
            )) {
                is InvokeOutcome.Returned -> outcome.value.also { CACHE.put(key, it); store(key, it) }
                is InvokeOutcome.Failed -> return fail(
                    ErrorCode.V1003_MACRO_FAILED,
                    "`${call.macro.localName}!` failed: ${outcome.message}",
                    outcome.guestFrames.take(5).map { "at $it" },
                )
            }
        }
        return hygienic(decode(call, output, asDeclarations), source)
    }

    /** Where a macro is declared, and its identity: what its module's parse alone says. */
    private sealed interface Source {
        class Found(val identity: Hash, val module: String, val parsed: ParsedModule) : Source
        class Problem(val code: ErrorCode, val message: String, val hints: List<String> = emptyList()) : Source
    }

    private fun macroSource(module: String, name: String): Source {
        val loaded = when (val l = ModuleLoader(resolver).loadModule(module)) {
            is ModuleLoadResult.Success -> l.module
            is ModuleLoadResult.Failure -> return Source.Problem(
                ErrorCode.V1001_MACRO_NOT_FOUND, "the module `$module` could not be loaded",
                l.errors.take(3).map { it.toString() },
            )
        }
        val parsed = loaded.parsedModule
            ?: return Source.Problem(ErrorCode.V1003_MACRO_FAILED, "the module `$module` has no parsed form")
        val decl = parsed.decls.filterIsInstance<ParsedLetDecl>().firstOrNull { name in it.pattern.extractBindings() }
            ?: return Source.Problem(ErrorCode.V1001_MACRO_NOT_FOUND, "`$module` declares no function `$name`")
        val identity = decl.contentHash
            ?: return Source.Problem(ErrorCode.V1003_MACRO_FAILED, "`$name` in `$module` has not been identified")
        return Source.Found(identity, module, parsed)
    }

    /** An expansion a past compile stored, if any. */
    private fun stored(key: CacheKey): HostValue? =
        ModuleResolver.expansionStore()?.getExpansion(key.macro, sha256(key.input))?.let(HostValueCodec::decode)

    private fun store(key: CacheKey, output: HostValue) {
        ModuleResolver.expansionStore()?.putExpansion(key.macro, sha256(key.input), HostValueCodec.encode(output))
    }

    private fun sha256(s: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** [call]'s expansion with the names its macro wrote meaning what they meant there (see [Hygiene]). */
    private fun hygienic(call: ParsedMacroCall, macro: Source.Found): ParsedMacroCall {
        val code = call.expansion ?: return call
        val hygiene = Hygiene(macro.module, macro.parsed) { loc -> macroWritten.containsKey(loc) }
        val rewritten = when (code) {
            is ParsedCode.Expr -> ParsedCode.Expr(hygiene.expr(code.expr, emptySet()))
            is ParsedCode.Decls -> ParsedCode.Decls(code.decls.map(hygiene::decl))
        }
        if (hygiene.unreachable.isNotEmpty()) {
            val names = hygiene.unreachable.joinToString(", ") { "`$it`" }
            return call.copy(expansion = null, diagnostics = call.diagnostics + MacroDiagnostic(
                ErrorCode.V1003_MACRO_FAILED,
                "`${call.macro.localName}!` expands to code using $names, private to `${macro.module}`",
                call.loc,
                listOf("make ${names} `pub`: an expansion is compiled in its caller, which can reach only what `${macro.module}` exports"),
            ))
        }
        handedOver.putAll(hygiene.handedOver)
        return call.copy(expansion = rewritten)
    }

    /** The macro's [output] — an `Expansion` — read into the call. */
    private fun decode(call: ParsedMacroCall, output: HostValue, asDeclarations: Boolean): ParsedMacroCall {
        val decoder = SyntaxDecoder(locate = { span -> place(span, call) })
        return try {
            val fields = (output as? HostValue.Record)?.fields?.toMap()
                ?: throw SyntaxDecoder.Malformed("the macro returned something other than an `Expansion` record")
            val code = decoder.code(fields["code"] ?: throw SyntaxDecoder.Malformed("the `Expansion` has no `code`"))
            val reported = ((fields["errors"] as? HostValue.Seq)
                ?: throw SyntaxDecoder.Malformed("the `Expansion` has no `errors` list")).items.map { error(it, call) }
            val wanted = if (asDeclarations) code is ParsedCode.Decls else code is ParsedCode.Expr
            if (!wanted) {
                return call.copy(diagnostics = reported + MacroDiagnostic(
                    ErrorCode.V1003_MACRO_FAILED,
                    if (asDeclarations) "`${call.macro.localName}!` returned an expression, where its call stands as a declaration"
                    else "`${call.macro.localName}!` returned declarations, where its call stands as an expression",
                    call.loc,
                    listOf(if (asDeclarations) "return `declare { decls = … }`" else "return `expand_to { e = … }`"),
                ))
            }
            call.copy(expansion = code, diagnostics = reported)
        } catch (e: SyntaxDecoder.Malformed) {
            call.copy(diagnostics = listOf(MacroDiagnostic(
                ErrorCode.V1003_MACRO_FAILED,
                "`${call.macro.localName}!` returned code the compiler cannot read: ${e.message}",
                call.loc,
            )))
        }
    }

    private fun error(v: HostValue, call: ParsedMacroCall): MacroDiagnostic {
        val f = (v as? HostValue.Record)?.fields?.toMap()
            ?: throw SyntaxDecoder.Malformed("a `MacroError` is not a record")
        val message = (f["message"] as? HostValue.Str)?.value
            ?: throw SyntaxDecoder.Malformed("a `MacroError` has no `message`")
        val hints = (f["hints"] as? HostValue.Seq)?.items?.map { (it as? HostValue.Str)?.value ?: "" }.orEmpty()
        return MacroDiagnostic(ErrorCode.V1004_MACRO_INPUT_ERROR, message, place(f["span"], call), hints)
    }

    // ── where things are ─────────────────────────────────────────────────

    /**
     * Where a span the macro returned belongs. A span in its input is moved to
     * where that input is written; any other — code the macro wrote itself, in
     * its own file — is the call: the caller sees the call that produced it.
     */
    private fun place(span: HostValue?, call: ParsedMacroCall): Loc {
        val f = (span as? HostValue.Record)?.fields?.toMap() ?: return written(call)
        val file = ((f["file"] as? HostValue.Opt)?.value as? HostValue.Str)?.value
        if (file != INPUT_FILE) return written(call)
        fun int(k: String) = (f[k] as? HostValue.IntV)?.value?.toInt() ?: 1
        val at = call.inputLoc
        fun line(l: Int) = at.startLine + l - 1
        fun col(l: Int, c: Int) = if (l == 1) at.startCol + c - 1 else c
        return Loc(at.file, line(int("start_line")), col(int("start_line"), int("start_col")),
            line(int("end_line")), col(int("end_line"), int("end_col")))
    }

    /**
     * The call's location, as the location of code its macro wrote — a fresh
     * object each time, remembered, so [Hygiene] can tell a name the macro wrote
     * from one that came from the input, though both sit in the caller's file.
     */
    private fun written(call: ParsedMacroCall): Loc = call.loc.copy().also { macroWritten[it] = true }

    private val macroWritten = java.util.IdentityHashMap<Loc, Boolean>()

    /** The span a macro is told its input has: a fixed origin, so the output does not depend on where the call is. */
    private fun inputOrigin(text: String): HostValue {
        val lines = text.split('\n')
        return record(
            "file" to HostValue.str(INPUT_FILE),
            "start_line" to HostValue.IntV(1), "start_col" to HostValue.IntV(1),
            "end_line" to HostValue.IntV(lines.size.toLong()),
            "end_col" to HostValue.IntV(lines.last().length.toLong()),
        )
    }

    // ── finding the macro ────────────────────────────────────────────────

    private sealed interface Found {
        class Macro(
            val hash: Hash,
            val functions: List<FunctionIR>,
            val performers: List<Array<String>>,
            /** Where it is declared: what [Hygiene] resolves its names against. */
            val module: String,
            val file: String,
            val parsed: ParsedModule,
        ) : Found
        class Problem(val code: ErrorCode, val message: String, val hints: List<String> = emptyList()) : Found
    }

    /** [name] in [module], type checked and lowered, if it is a macro. */
    private fun macroFunction(module: String, name: String): Found {
        val loaded = ModuleLoader(resolver).loadModule(module)
        val typed: TypedModuleResult = when (loaded) {
            is ModuleLoadResult.Success -> {
                val checker = ModuleTypeChecker.withContextAndResolver(
                    TypeChecker.createInitialContext(), resolver, resolver.world,
                )
                when (val r = checker.typeCheckModule(loaded.module)) {
                    is ModuleTypeCheckResult.Success -> r.module
                    is ModuleTypeCheckResult.Failure -> return Found.Problem(
                        ErrorCode.V1003_MACRO_FAILED,
                        "the module `$module`, which declares `$name`, has errors",
                        r.errors.take(3).map { it.message },
                    )
                }
            }
            is ModuleLoadResult.Failure -> return Found.Problem(
                ErrorCode.V1001_MACRO_NOT_FOUND, "the module `$module` could not be loaded",
                loaded.errors.take(3).map { it.toString() },
            )
        }
        val decl = typed.typedModule?.decls?.filterIsInstance<TypedLetDecl>()?.firstOrNull { it.name == name }
            ?: return Found.Problem(ErrorCode.V1001_MACRO_NOT_FOUND, "`$module` declares no function `$name`")
        shapeProblem(decl.scheme.type)?.let { return Found.Problem(ErrorCode.V1002_NOT_A_MACRO, "`$name` is not a macro: $it",
            listOf("a macro has the type `{ input: MacroInput } -> Expansion` (`std::macro`), with no effects")) }

        val local = typed.loweredFunctions.associateBy { it.hash }
        val own = local[decl.hash] ?: PrecompiledStdlib.preloweredFunctions[decl.hash]
            ?: return Found.Problem(ErrorCode.V1003_MACRO_FAILED, "`$name` has no compiled form")
        val performers = typed.loweredPerformers + PrecompiledStdlib.preloweredPerformerRecords
        val source = (loaded as ModuleLoadResult.Success).module
        val parsed = source.parsedModule
            ?: return Found.Problem(ErrorCode.V1003_MACRO_FAILED, "the module `$module` has no parsed form to resolve `$name`'s names in")
        return Found.Macro(
            decl.hash, IrClosure.reachable(listOf(own), local::get), performers,
            module, source.filePath, parsed,
        )
    }

    /** Why [type] is not a macro's, or null when it is. */
    private fun shapeProblem(type: MezzeType): String? {
        val fn = type as? MezzeType.FunctionType ?: return "it is not a function"
        if (fn.effects?.let { it.allEffectNames.isNotEmpty() || it.rowVar != null } == true) {
            return "it performs effects, and a macro runs while its caller is compiled"
        }
        val param = fn.paramType as? MezzeType.RecordType ?: return "its parameter is not a record"
        if (param.fields.keys != setOf("input")) return "it does not take exactly `{ input: MacroInput }`"
        val result = fn.returnType as? MezzeType.RecordType ?: return "it does not return an `Expansion`"
        if (result.fields.keys != setOf("code", "errors")) return "it does not return an `Expansion`"
        return null
    }

    /** What `use` lines bring into scope, for resolving a macro's name. */
    private class Imports(module: ParsedModule) {
        private val items = HashMap<String, Pair<String, String>>()
        private val aliases = HashMap<String, String>()

        init {
            for (d in module.decls) {
                val (path, alias) = when (d) {
                    is ParsedUseDecl -> d.path to d.alias
                    is ParsedPubUseDecl -> d.path to d.alias
                    else -> continue
                }
                when (path) {
                    is ParsedUsePath.Module -> {
                        val full = path.path.toString()
                        aliases[alias ?: full.substringAfterLast(MODULE_PATH_SEPARATOR)] = full
                    }
                    is ParsedUsePath.Selective -> for (item in path.items) {
                        items[item.alias ?: item.name] = path.modulePath.toString() to item.name
                    }
                }
            }
        }

        /** (module, name) the macro [ref] names, or null when nothing in scope does. */
        fun resolve(ref: ParsedIdentifier): Pair<String, String>? {
            val q = ref.qualifier ?: return items[ref.localName]
            val segments = q.segments
            val first = aliases[segments.first()]
            val module = if (first != null) (listOf(first) + segments.drop(1)).joinToString(MODULE_PATH_SEPARATOR)
                else segments.joinToString(MODULE_PATH_SEPARATOR)
            return module to ref.localName
        }
    }

    /** The parser, for a macro reading its input as Mezze. */
    private object Parsing : SyntaxHost {
        override fun tokens(text: String, file: String, line: Int, column: Int): HostValue =
            HostValue.Seq(MezzeParser().lex(text, line, column).map { t ->
                record(
                    "kind" to HostValue.Str(t.kind),
                    "text" to HostValue.Str(t.text),
                    "span" to record(
                        "file" to HostValue.str(file),
                        "start_line" to HostValue.IntV(t.line.toLong()), "start_col" to HostValue.IntV(t.column.toLong()),
                        "end_line" to HostValue.IntV(t.endLine.toLong()), "end_col" to HostValue.IntV(t.endColumn.toLong()),
                    ),
                )
            })

        override fun parse(text: String, file: String, line: Int, column: Int, declarations: Boolean): HostValue = try {
            val code = MezzeParser().parseCode(text, file, line, column, declarations)
            record("code" to HostValue.Opt(SyntaxEncoder.WITHOUT_FACTS.code(code)), "error" to HostValue.Opt(null))
        } catch (e: ParseException) {
            record("code" to HostValue.Opt(null), "error" to HostValue.Opt(record(
                "message" to HostValue.Str(e.errorMessage),
                "span" to record(
                    "file" to HostValue.str(file),
                    "start_line" to HostValue.IntV(e.line.toLong()), "start_col" to HostValue.IntV(e.column.toLong()),
                    "end_line" to HostValue.IntV(e.line.toLong()), "end_col" to HostValue.IntV(e.column.toLong()),
                ),
            )))
        }
    }

    private data class CacheKey(val macro: Hash, val input: String)

    companion object {
        /** The file a macro is told its input is in, so its spans can be told from the macro's own. */
        const val INPUT_FILE = "<macro input>"

        /** How deep macros may expand into macro calls before the expansion is taken not to end. */
        private const val MAX_DEPTH = 64

        /** Expansions by (macro identity, input), for the life of the process; least recently used go first. */
        private val CACHE = object {
            private val map = object : LinkedHashMap<CacheKey, HostValue>(256, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, HostValue>) = size > 4096
            }
            @Synchronized fun get(k: CacheKey) = map[k]
            @Synchronized fun put(k: CacheKey, v: HostValue) { map[k] = v }
        }

        /** Whether [this] holds a macro call anywhere. */
        private fun ParsedDecl.hasMacroCall(): Boolean {
            var found = false
            mapExprs { e -> if (!found && e.hasMacroCall()) found = true; e }
            return found
        }

        private fun ParsedExpr.hasMacroCall(): Boolean =
            this is ParsedMacroCall || children().any { it.hasMacroCall() }
    }
}
