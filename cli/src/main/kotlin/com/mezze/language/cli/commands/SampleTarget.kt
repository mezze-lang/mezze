package com.mezze.language.cli.commands

import com.mezze.language.ast.MezzeType
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.typedast.TypedLetDecl
import java.io.File

/**
 * What `mez sample` needs to know about the function it was pointed at.
 *
 * Only two things, and the second is the surprising one: the function's name, and
 * its parameter's FIELD NAMES. Not the parameter's type — see [Ready.fieldNames].
 */
object SampleTarget {

    sealed interface Result {
        /**
         * The function's name and its parameter's FIELDS, with types.
         *
         * Types rather than only names, because the command generates FROM the type:
         * it already type-checked the module, so the shape of every argument is known
         * and there is no reason to emit a draw that depends on dispatch pinning it.
         * See [SampleGen].
         */
        data class Ready(
            val name: String,
            val fields: Map<String, com.mezze.language.ast.MezzeType>,
            /**
             * S-21: per field, the constructors this function's own `match` arms
             * discriminate on — the shapes worth generating, taken from the function
             * rather than guessed at. Empty for a function that matches on nothing.
             * See [SampleBranches].
             */
            val branches: List<SampleBranches.Row> = emptyList(),
        ) : Result {
            val fieldNames: List<String> get() = fields.keys.toList()
            /** Rows that can be pinned to an arm — the longest arm list. */
            val pinnableRows: Int get() = branches.size
        }

        /** Not sampleable, with the reason already formatted for a terminal. */
        data class Refused(val message: String) : Result
    }

    /**
     * Find [declName] in [moduleFile]'s scope and judge whether it can be sampled.
     *
     * This type-checks the module WITHOUT appending anything, which is the
     * difference from [EvalClosure.compile]: the command has to know the parameter's
     * shape before it can write the expression that generates it. Two passes over
     * one module, and the first one's only job is to answer this question.
     */
    fun resolve(projectRoot: File, moduleFile: File, declName: String): Result {
        val source = try {
            moduleFile.readText()
        } catch (e: Exception) {
            return Result.Refused("cannot read ${moduleFile.path}: ${e.message}")
        }

        val parsed = MezzeParser().parseWithRecovery(source, file = moduleFile.name)
        if (parsed.errors.isNotEmpty()) {
            return Result.Refused(
                com.mezze.language.langservice.printing.ParseErrorFormatter.formatAll(parsed.errors)
            )
        }

        DepStoreLoader.load(projectRoot)

        val raw = try {
            TypeChecker(strictTopLevel = true)
                .checkParsedModule(parsed.module, projectRoot.toPath(), enablePrelude = true)
        } catch (e: com.mezze.language.typechecker.TypeCheckError) {
            return Result.Refused(
                com.mezze.language.langservice.printing.ErrorFormatter.format(e, source)
            )
        }
        if (raw is TypeChecker.TypeCheckResult.Failure) {
            // The module itself does not compile, so there is nothing to sample and
            // the real problem is upstream of this command. Reporting the module's
            // own diagnostics beats a message about sampling.
            return Result.Refused(
                raw.errors.joinToString("\n\n") {
                    com.mezze.language.langservice.printing.ErrorFormatter.format(it, source)
                }
            )
        }

        val success = raw as TypeChecker.TypeCheckResult.Success
        val decls = success.typedModule?.decls?.filterIsInstance<TypedLetDecl>().orEmpty()
        val decl = decls.firstOrNull { it.name == declName }
            ?: return Result.Refused(notFound(declName, decls.map { it.name }))

        // The SAME judgement `mez eval` applies to an entry point, and the reason it
        // is shared: running an effectful function unhandled does not fail in Mezze,
        // it returns a suspended computation that renders as something plausible.
        // For a command whose whole job is showing what a function does, that is the
        // worst outcome available.
        when (val runnable = RunnabilityCheck.check(decl)) {
            is RunnabilityCheck.Result.Runnable -> Unit
            else -> return Result.Refused(
                RunnabilityCheck.refusal(runnable, declName)
                    ?: "`$declName` cannot be sampled"
            )
        }

        val fnType = decl.scheme.type as? MezzeType.FunctionType
            ?: return Result.Refused(
                "`$declName` is not a function (it has type ${com.mezze.language.ast.prettyPrintType(decl.scheme.type)}), " +
                    "so there are no arguments to generate."
            )

        // A Mezze function takes ONE record, so "generate arguments" is "generate
        // that record" — there is no arity to handle and no argument order to get
        // wrong. A parameter that is not a record is a function this command cannot
        // write a call to, which is a real limit and said plainly rather than
        // guessed around.
        val param = fnType.paramType
        val fields = (param as? MezzeType.RecordType)?.fields
            ?: return Result.Refused(
                "`$declName` takes ${com.mezze.language.ast.prettyPrintType(param)} rather than a " +
                    "record, so there are no named arguments to generate. Sample is for a function " +
                    "whose parameter is a record, which is how Mezze functions are normally written."
            )

        // S-21: the function's own `match` arms are the list of shapes worth
        // generating. Read here because this is where the typed declaration is in
        // hand; the command turns them into pinned rows.
        return Result.Ready(declName, fields, SampleBranches.inventory(decl, fields, decls))
    }

    /**
     * Sorted so the message is stable, and capped because a module with two hundred
     * declarations turns a miss into a wall of text that answers nothing.
     */
    private fun notFound(name: String, available: List<String>): String = buildString {
        append("No function named `$name` in this module.\n")
        val own = available.filterNot { it.startsWith("__") }.sorted()
        if (own.isNotEmpty()) {
            append("\n")
            com.mezze.language.parsedast.diagnostics.LevenshteinSuggest.closest(name, own)?.let {
                append("${Glyphs.TIP} Did you mean `$it`?\n\n")
            }
            append("Available: ${own.take(12).joinToString(", ")}")
            if (own.size > 12) append(", … (${own.size - 12} more)")
            append("\n")
        }
    }
}
