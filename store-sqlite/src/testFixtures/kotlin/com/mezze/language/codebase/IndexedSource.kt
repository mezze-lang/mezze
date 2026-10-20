package com.mezze.language.codebase

import com.mezze.language.parser.MezzeParser
import com.mezze.language.store.MezzeStore
import com.mezze.language.typechecker.TypeCheckResult
import com.mezze.language.typechecker.TypeChecker
import java.nio.file.Paths

/**
 * Type-check [source] as module [module] and index it into [store] the way a
 * build does: the same `StorePopulator.populate` call, with the source passed so
 * declarations and signatures are written. [filePath] is recorded as the
 * module's file; point it at a real file to make source slices readable.
 *
 * For tests that must hold against what the compiler really writes, not against
 * rows a test assembled by hand.
 */
fun indexSource(
    store: MezzeStore,
    source: String,
    module: String = "app",
    filePath: String = "$module.mz",
    root: java.nio.file.Path = Paths.get("."),
) {
    val file = java.io.File(filePath).name
    val parsed = MezzeParser().parseParsedModule(source, file)
    val raw = TypeChecker().checkParsedModule(parsed, root, enablePrelude = true)
    val ok = raw as? TypeChecker.TypeCheckResult.Success
        ?: error("test source did not type-check: $raw")
    StorePopulator.populate(
        store,
        TypeCheckResult(typedModule = ok.typedModule, loweredFunctions = ok.loweredFunctions ?: emptyList()),
        module, filePath, "srchash",
        entrySource = source,
        parsedModule = parsed,
    )
}
