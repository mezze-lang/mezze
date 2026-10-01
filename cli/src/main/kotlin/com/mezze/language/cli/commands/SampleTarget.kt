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
         * The function can be sampled.
         *
         * [fieldNames] is what the command generates from, and it is deliberately
         * names rather than types. The generated expression binds one
         * `Arbitrary.arbitrary {}` per field and lets the FUNCTION'S OWN parameter
         * pin each one:
         *
         *     let args = { n = Arbitrary.arbitrary {}, s = Arbitrary.arbitrary {} }
         *     { args = args, result = f { n = args.n, s = args.s } }
         *
         * The alternative — annotating the whole record with the parameter's
         * rendered type — was measured and does not work: an ANONYMOUS record
         * annotation does not pin a receiver-less ability operation, so
         * `let args: { n: Int } = Arbitrary.arbitrary {}` is rejected while the
         * named `let args: Args = …` is accepted. Generating per field sidesteps
         * that difference entirely and needs no type rendering, so there is no
         * round-trip through a printed type that might not re-parse.
         */
        data class Ready(val name: String, val fieldNames: List<String>) : Result

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

        return Result.Ready(declName, fields.keys.toList())
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
