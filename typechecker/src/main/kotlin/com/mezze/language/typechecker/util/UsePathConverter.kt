package com.mezze.language.typechecker.util

import com.mezze.language.ast.ImportItem
import com.mezze.language.ast.ModulePath
import com.mezze.language.ast.SelectivePath
import com.mezze.language.ast.UsePath
import com.mezze.language.parsedast.ParsedUsePath

/**
 * Converts a parsed-AST [ParsedUsePath] into the core [UsePath] the module
 * resolver operates on. Previously duplicated verbatim as a private
 * `parsedUsePathToV1` in both ModuleResolver and ImportResolver.
 */
fun parsedUsePathToCore(path: ParsedUsePath): UsePath = when (path) {
    is ParsedUsePath.Module -> ModulePath(path.path.segments)
    is ParsedUsePath.Selective -> SelectivePath(
        module = path.modulePath.segments,
        items = path.items.map { ImportItem(name = it.name, alias = it.alias) }
    )
}
