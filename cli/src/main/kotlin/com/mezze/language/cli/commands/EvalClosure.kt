package com.mezze.language.cli.commands

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
 * By appending `let <binding> = <expr>` to the module's own source and compiling
 * the result. That is not a shortcut — it is what makes the expression's scope
 * exactly the module's scope, with every import and every top-level declaration
 * visible, without this file having to model scope at all.
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
    fun compile(projectRoot: File, moduleFile: File, expression: String): Result {
        if (expression.isBlank()) return Result.Failed("nothing to evaluate")

        val moduleSource = try {
            moduleFile.readText()
        } catch (e: Exception) {
            return Result.Failed("cannot read ${moduleFile.path}: ${e.message}")
        }

        // A newline before the binding in case the file has no trailing one —
        // otherwise the binding joins the last line and the parse error points at
        // source the user did write.
        val source = moduleSource.trimEnd() + "\n\nlet $BINDING = $expression\n"

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
            return Result.Failed(TypeCheckRunner.formatErrors(raw, source))
        }
        val result = TypeCheckRunner.wrapSuccess(raw as TypeChecker.TypeCheckResult.Success)

        val decls = result.typedModule?.decls?.filterIsInstance<TypedLetDecl>().orEmpty()
        val entry = decls.firstOrNull { it.name == BINDING }
            ?: return Result.Failed(
                "the expression did not become a top-level binding — this is a compiler bug, " +
                    "not a problem with the expression"
            )

        // The shape the runtime renders against. Local declarations first so one
        // shadows an imported type of the same name, matching lowering.
        val modules = listOfNotNull(result.typedModule) + result.loadedModules.values
        val printTypes = PrintTypeDefs.collect(modules)
        val resultPrintType = try {
            PrintTypeBuilder(printTypes.defs).build(entry.scheme.type)
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
            resultType = entry.scheme.type.toString(),
        )
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
