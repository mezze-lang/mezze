package com.mezze.language.cli.commands

/**
 * `mez --explain <CODE>` — print long-form documentation for an error code.
 *
 * Docs live inline in [ExplainDocs]. If no doc exists for the code we
 * print a friendly fallback pointing at the inline `error[CODE]:` snippet.
 */
class ExplainCommand {
    fun execute(args: List<String>) {
        if (args.isEmpty()) {
            System.err.println("Usage: mez --explain <CODE>")
            System.err.println("Example: mez --explain T0201")
            kotlin.system.exitProcess(1)
        }

        val code = args[0].trim()
        val body = ExplainDocs.lookup(code)
        if (body == null) {
            System.err.println("No long-form documentation for `${code.uppercase()}` yet.")
            System.err.println()
            System.err.println("The error message printed at the call site is the authoritative description.")
            System.err.println("Run `mez check` on your code to see it.")
            kotlin.system.exitProcess(1)
        }
        println(body)
    }
}
