#!/usr/bin/env kotlin

/**
 * Refactoring script to replace manual MezzeVariant constructions with helper functions.
 *
 * Replaces:
 *   MezzeVariant("Some", MezzeRecord(mapOf("val" to X))) -> mezzeOptionSome(X)
 *   MezzeVariant("None", null) -> mezzeOptionNone()
 *   MezzeVariant("Ok", MezzeRecord(mapOf("val" to X))) -> mezzeResultOk(X)
 *   MezzeVariant("Err", MezzeRecord(mapOf("error" to X))) -> mezzeResultErr(X)
 *
 * Run from project root: kotlin scripts/refactor-option-helpers.kts
 */

import java.io.File

val interpreterDir = File("interpreter/src/main/kotlin")

// Files to skip (contains the helper definitions)
val skipFiles = setOf("MezzeValue.kt")

var totalReplacements = 0
var filesModified = 0

fun processFile(file: File) {
    if (skipFiles.contains(file.name)) {
        println("Skipping ${file.name} (contains helper definitions)")
        return
    }

    var content = file.readText()
    val originalContent = content
    var fileReplacements = 0

    // Pattern 1: MezzeVariant("Some", MezzeRecord(mapOf("val" to X)))
    // This is tricky because X can be complex. We need to match balanced parens.
    val somePattern = Regex("""MezzeVariant\("Some", MezzeRecord\(mapOf\("val" to ([^)]+)\)\)\)""")
    content = somePattern.replace(content) { match ->
        fileReplacements++
        "mezzeOptionSome(${match.groupValues[1]})"
    }

    // Pattern 2: MezzeVariant("None", null)
    val nonePattern = Regex("""MezzeVariant\("None", null\)""")
    content = nonePattern.replace(content) {
        fileReplacements++
        "mezzeOptionNone()"
    }

    // Pattern 3: MezzeVariant("None", MezzeRecord(emptyMap()))  (incorrect but exists)
    val noneEmptyPattern = Regex("""MezzeVariant\("None", MezzeRecord\(emptyMap\(\)\)\)""")
    content = noneEmptyPattern.replace(content) {
        fileReplacements++
        "mezzeOptionNone()"
    }

    // Pattern 4: MezzeVariant("Ok", MezzeRecord(mapOf("val" to X)))
    val okPattern = Regex("""MezzeVariant\("Ok", MezzeRecord\(mapOf\("val" to ([^)]+)\)\)\)""")
    content = okPattern.replace(content) { match ->
        fileReplacements++
        "mezzeResultOk(${match.groupValues[1]})"
    }

    // Pattern 5: MezzeVariant("Err", MezzeRecord(mapOf("error" to X)))
    val errPattern = Regex("""MezzeVariant\("Err", MezzeRecord\(mapOf\("error" to ([^)]+)\)\)\)""")
    content = errPattern.replace(content) { match ->
        fileReplacements++
        "mezzeResultErr(${match.groupValues[1]})"
    }

    if (content != originalContent) {
        file.writeText(content)
        println("${file.name}: $fileReplacements replacements")
        totalReplacements += fileReplacements
        filesModified++
    }
}

fun processDirectory(dir: File) {
    dir.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .forEach { processFile(it) }
}

println("Refactoring Option/Result helpers...")
println("=" .repeat(50))

processDirectory(interpreterDir)

println("=" .repeat(50))
println("Done! Modified $filesModified files with $totalReplacements total replacements.")
println()
println("Note: Complex nested expressions may not be fully replaced.")
println("Please run: ./gradlew :interpreter:compileKotlin")
println("And check for any remaining manual MezzeVariant patterns.")
