package com.funk.language.typechecker

/**
 * Simple error pretty printer for compatibility with CLI
 */
object ErrorPrettyPrinter {
    fun prettyPrint(error: TypeCheckError): String {
        return error.message ?: "Type check error"
    }
}