package com.mezze.language.cli.commands

import com.mezze.language.parsedast.diagnostics.display
import com.mezze.language.parser.MezzeParser
import com.mezze.language.store.StoreSerialization
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.truffleir.PrintType
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.typechecker.compiler.PrintTypeBuilder
import com.mezze.language.typechecker.compiler.PrintTypeDefs
import com.mezze.language.typedast.TypedLetDecl
import java.io.File

/**
 * Compile one expression against a module, for an EVAL request.
 *
 * ## Why the client compiles
 *
 * The runtime cannot. `:interpreter` depends on `:parser` and `:typechecker` only
 * as test dependencies — at runtime it has neither, deliberately, because that
 * module is what ships as the slim jar and the native image. So the split is the
 * one LOAD already embodies: this side compiles, that side executes IR. A
 * consequence worth knowing: an expression's TYPE errors surface here, as ordinary
 * compiler diagnostics, and never travel as a protocol message.
 *
 * ## Why an expression cannot just run against already-compiled code
 *
 * Monomorphisation is ahead-of-time, seeded from call sites. There is no on-demand
 * specialisation, so an expression calling a generic at a type nothing in the
 * program used has no specialisation waiting for it. The expression must be
 * typechecked and monomorphised IN the module's context, i.e. compiled as a unit.
 *
 * ## How the module's scope is obtained
 *
 * By appending `let <binding> = {} -> <expr>` to the module's own source and
 * compiling the result. That is not a shortcut — it is what makes the expression's
 * scope exactly the module's scope, with every import and every top-level
 * declaration visible, without this file having to model scope at all.
 *
 * A THUNK rather than a bare binding, and that detail is load-bearing. The top
 * level is for definitions, so a top-level binding whose value comes from an
 * effectful call is rejected (V0502) even when that call handles its own effects —
 * it would run at module-load time. Wrapping in `{} -> …` makes the top level a
 * definition, and the runtime CALLS the entry with an empty record, exactly as it
 * runs `main`. Without the thunk, `mez eval 'main {}'` is impossible; with it, the
 * expression is evaluated in a function position where effects are allowed.
 *
 * It is also cheap on a warm runtime, and for a reason worth stating: FunctionIRs
 * are content-addressed, so appending a declaration leaves the code the USER wrote
 * at the hashes it already had, and the runtime's caches still hit for all of it.
 * Two functions are new per request — the expression's own binding, and the
 * synthetic `__entry__` wrapper, whose body is the module's top-level lets and so
 * changes whenever any of them does.
 *
 * Definitions do NOT accumulate across calls: each request compiles the module
 * plus one expression and discards it. That keeps every content-addressing
 * question about a REPL's `let` (does it get a hash? persist? shadow?) out of
 * scope until something actually needs it.
 */
object EvalClosure {

    /**
     * The name the expression is bound to.
     *
     * Deliberately not a name a user could write: it must not collide with a
     * declaration in the module it is appended to, and a collision would be a
     * confusing "already defined" error pointing at a line the user never wrote.
     */
    const val BINDING = "__mez_eval__"

    sealed interface Result {
        /** The (fns, meta) pair an [com.mezze.wire.Eval] request names. */
        data class Compiled(
            val fnsBytes: ByteArray,
            val metaBytes: ByteArray,
            val resultPrintType: PrintType,
            /** Rendered `MezzeType` of the expression, for a caller that shows it. */
            val resultType: String,
        ) : Result

        /** Compilation failed; [message] is already formatted for a terminal. */
        data class Failed(val message: String) : Result
    }

    /**
     * Compile [expression] in the scope of [moduleFile].
     *
     * [projectRoot] is where imports resolve from, as for an ordinary build.
     */
    fun compile(
        projectRoot: File,
        moduleFile: File,
        expression: String,
        /**
         * Imports the expression needs that the module does not already have.
         *
         * `mez sample` generates arguments, and a generated `List`/`Set`/`Map` is built
         * by a stdlib constructor the module may never import. The generator reports
         * what it used; this puts it in scope.
         *
         * PREPENDED, not appended: `use` belongs at the top, and the module's own
         * imports follow. A duplicate import of something the module already has is
         * harmless, so nothing has to diff them.
         */
        extraImports: Set<String> = emptySet(),
    ): Result {
        if (expression.isBlank()) return Result.Failed("nothing to evaluate")

        val moduleSource = try {
            moduleFile.readText()
        } catch (e: Exception) {
            return Result.Failed("cannot read ${moduleFile.path}: ${e.message}")
        }

        // A THUNK, not a bare binding, and this is load-bearing. The top level is
        // for definitions: a top-level binding whose value comes from an effectful
        // call is rejected (V0502) even when that call handles its own effects,
        // because it would run at module-load time. Binding `{} -> <expr>` instead
        // makes the top level a definition, and the runtime CALLS the entry with an
        // empty record — exactly how it runs `main` — so the expression is evaluated
        // in a function position where effects are allowed.
        //
        // A newline before it in case the file has no trailing one; otherwise the
        // binding joins the last line and an error points at source the user wrote.
        val prelude = if (extraImports.isEmpty()) "" else extraImports.joinToString("\n", postfix = "\n\n")
        val source = prelude + moduleSource.trimEnd() + "\n\nlet $BINDING = {} -> $expression\n"

        val parser = MezzeParser()
        val parsed = parser.parseWithRecovery(source, file = moduleFile.name)
        if (parsed.errors.isNotEmpty()) {
            return Result.Failed(
                com.mezze.language.langservice.printing.ParseErrorFormatter.formatAll(parsed.errors)
            )
        }

        DepStoreLoader.load(projectRoot)

        val raw = try {
            TypeChecker(strictTopLevel = true)
                .checkParsedModule(parsed.module, projectRoot.toPath(), enablePrelude = true)
        } catch (e: com.mezze.language.typechecker.TypeCheckError) {
            return Result.Failed(
                com.mezze.language.langservice.printing.ErrorFormatter.format(e, source)
            )
        }
        if (raw is TypeChecker.TypeCheckResult.Failure) {
            return Result.Failed(formatAgainstExpression(raw, expression, source))
        }
        val result = TypeCheckRunner.wrapSuccess(raw as TypeChecker.TypeCheckResult.Success)

        val decls = result.typedModule?.decls?.filterIsInstance<TypedLetDecl>().orEmpty()
        val entry = decls.firstOrNull { it.name == BINDING }
            ?: return Result.Failed(
                "the expression did not become a top-level binding — this is a compiler bug, " +
                    "not a problem with the expression"
            )

        // Refuse an expression whose effects nothing handles, BEFORE running it.
        // Mezze is lazy-until-discharged: calling an effectful function with no
        // enclosing handler does not fail, it returns a MezzeSuspend — so the value
        // would come back as an unevaluated thunk that renders as something
        // plausible. For a command whose whole job is showing what an expression
        // evaluates to, that is the worst failure: it looks like it worked.
        when (val runnable = RunnabilityCheck.check(entry)) {
            is RunnabilityCheck.Result.Runnable -> Unit

            is RunnabilityCheck.Result.NeedsHandlers -> return Result.Failed(
                buildString {
                    append("this expression performs ")
                    append(runnable.effects.joinToString(", "))
                    append(", and nothing here handles ")
                    append(if (runnable.effects.size == 1) "it" else "them")
                    append(".\n")
                    append("  Evaluating it would return an unevaluated computation rather than a ")
                    append("value.\n")
                    append("  Handle the effect inside the expression — ")
                    append("`perform <Handler> in <expr>` — or evaluate something that does not ")
                    append("perform.")
                }
            )

            // The expression was wrapped in a thunk above, so the binding is always
            // a function. Reaching here means the wrapping stopped working.
            is RunnabilityCheck.Result.NotAFunction -> return Result.Failed(
                "the eval binding is not a function (${runnable.actualType}) — this is a " +
                    "compiler bug, not a problem with the expression"
            )
        }

        // The shape describes the value the runtime gets back, which is the thunk's
        // RETURN type — the thunk itself is scaffolding and is never rendered.
        val resultType = (entry.scheme.type as? com.mezze.language.ast.MezzeType.FunctionType)
            ?.returnType
            ?: return Result.Failed(
                "the eval binding lost its function type — this is a compiler bug, " +
                    "not a problem with the expression"
            )

        // Local declarations first so one shadows an imported type of the same
        // name, matching lowering.
        val modules = listOfNotNull(result.typedModule) + result.loadedModules.values
        val printTypes = PrintTypeDefs.collect(modules)
        val resultPrintType = try {
            PrintTypeBuilder(printTypes.defs).build(resultType)
        } catch (e: PrintTypeBuilder.NotAConcreteValueType) {
            // A value whose type is still a variable has no printable shape. It is
            // refused here rather than rendered from the runtime value alone,
            // because that path silently loses exactly the distinctions the shape
            // carries — and it means the expression is ambiguous, which is worth
            // saying.
            return Result.Failed(
                "the expression's type is not concrete (${e.message}); " +
                    "an expression must evaluate to a definite type to be rendered"
            )
        }

        val rawClosure: List<FunctionIR> =
            NativeBuildPath.computeReachableClosure(result.loweredFunctions)
        // Same canonicalisation the run path applies, so a chain in the expression
        // is fused exactly as it would be if the expression lived in the file.
        val closureMap = rawClosure.associateBy { it.hash }
        val canonicalized = com.mezze.language.truffleir.ChainCanonicalizer.canonicalize(closureMap)
        val closure = rawClosure.map { canonicalized[it.hash] ?: it }

        val (fnBytes, metaBytes) = NativeBuildPath.packEmbeddedAppCbor(
            closure = closure,
            appName = "eval",
            entryHash = entry.hash.hex,
            entryName = BINDING,
            performerRecords = result.performerRecords,
            resultPrintType = resultPrintType,
        )
        return Result.Compiled(
            fnsBytes = fnBytes,
            metaBytes = metaBytes,
            resultPrintType = resultPrintType,
            // prettyPrintType, never `toString()`: MezzeType's toString is a debug
            // rendering that leaks Kotlin class names and identity hashes —
            // `TypeApplication(Seq …MezzeType$IntType@66c61024)` — which is not
            // something to show a user.
            resultType = com.mezze.language.ast.prettyPrintType(resultType),
        )
    }

    /** Column the expression starts at on the synthesized line, 1-indexed. */
    private val EXPR_COLUMN = "let $BINDING = {} -> ".length + 1

    /**
     * Report a typecheck failure against the EXPRESSION, not against the source
     * that was synthesized to compile it.
     *
     * The ordinary formatter renders a source frame, which here would cite a line
     * number past the end of the file and print `let __mez_eval__ = {} -> …` — a
     * generated identifier, on a line the user never wrote, in a file that does not
     * contain it. The message and the span are right; only the frame is wrong. So
     * the frame is rebuilt from the expression, which also makes the caret land on
     * the part of what the USER typed that is actually at fault.
     *
     * Errors in the module itself keep their real location: they belong to the
     * file, and re-pointing them at the expression would blame the wrong code.
     */
    private fun formatAgainstExpression(
        failure: TypeChecker.TypeCheckResult.Failure,
        expression: String,
        /** The source that was COMPILED, including any prepended imports. */
        source: String,
    ): String {
        // Counted from the source ACTUALLY compiled, not the module alone: prepended
        // imports shift every line, and a frame that pointed one line off would put
        // the caret under the wrong text.
        val syntheticLine = source.trimEnd().lines().size
        return buildString {
            for ((i, err) in failure.errors.withIndex()) {
                if (i > 0) append("\n\n")
                val code = err.code.display()
                append(if (code.isNotEmpty()) "error[$code]: " else "error: ").append(err.message)

                val loc = err.loc
                if (loc != null && loc.startLine == syntheticLine) {
                    append("\n  in: ").append(expression)
                    val from = loc.startCol - EXPR_COLUMN
                    val to = if (loc.endLine == syntheticLine) loc.endCol - EXPR_COLUMN else expression.length
                    if (from in 0..expression.length && to >= from) {
                        append("\n      ").append(" ".repeat(from))
                        append("^".repeat((to - from + 1).coerceAtLeast(1).coerceAtMost(expression.length - from)))
                    }
                } else if (loc != null) {
                    // Belongs to the module, not the expression: re-pointing it at
                    // the expression would blame the wrong code.
                    append("\n  --> ").append(loc.file).append(':')
                        .append(loc.startLine).append(':').append(loc.startCol)
                }
                for (hint in err.hints) append("\n  = hint: ").append(hint)
            }
            val explainable = failure.errors.map { it.code }.firstOrNull { ExplainDocs.hasDoc(it) }
            if (explainable != null) {
                append("\n\nFor more information, try `mez --explain ")
                append(explainable.display()).append("`.")
            }
        }
    }

    /** Write a compiled closure where an [com.mezze.wire.Eval] request can name it. */
    fun writeTo(dir: File, compiled: Result.Compiled): Pair<File, File> {
        dir.mkdirs()
        val fns = File(dir, "eval-fns.cbor").apply { writeBytes(compiled.fnsBytes) }
        val meta = File(dir, "eval-meta.cbor").apply { writeBytes(compiled.metaBytes) }
        return fns to meta
    }

    /** Read back what [writeTo] wrote — the same decode the runtime performs. */
    fun decodeMeta(metaBytes: ByteArray): StoreSerialization.DeserializedEmbeddedAppMeta =
        StoreSerialization.deserializeEmbeddedAppMeta(metaBytes)
}
