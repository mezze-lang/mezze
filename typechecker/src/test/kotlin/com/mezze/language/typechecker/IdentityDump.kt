package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.store.StoreSerialization
import java.nio.file.Paths
import java.security.MessageDigest

/**
 * Prints every identity and stored form this JVM computes, one per line, for
 * [HashesAreTheSameEverywhereTest] to run in a JVM of its own and compare.
 *
 *   id    <stdlib module>  <fingerprint of every identity it declares>
 *   typed <hash>           <sha256 of the declaration's stored content>
 *   ir    <hash>           <sha256 of the lowered function's stored content>
 */
object IdentityDump {

    val program = """
        use std::effects::log::{ Console, println }
        use std::collections::seq
        use std::abilities::default::{ Default }

        type Point = { x: Int, y: Int }
        ability Label for 'a where { label: { it: 'a } -> Str }
        impl Label for Point where { label = { it } -> "p" }
        effect Audit where { record: { line: Str } -> {} <Audit> }
        impl Audit for Quiet where { record = { line } -> {} }

        let twice = { x: Int } -> x * 2
        let double = { x } -> x * 2
        let pick : { a: 'a, b: 'a } -> 'a = { a, b } -> a
        let first = { o: { x: Int, ... } } -> o.x
        let apply : { x: Int, f: { v: Int } -> Int <...> } -> Int <...> = { x, f } -> f { v = x }
        let names = { xs: [Str] } -> seq::map { xs = xs, f = { x } -> "İ$(x)ı" }
        let sizes = { xs: [[Int]] } -> seq::map { xs = xs, f = { x } -> seq::map { xs = x, f = { x } -> x + 1 } }
        let fallback = { o } -> match o is
          Some { val } -> val
          None -> Default.default {}
        let bump = { xs: [Int] } -> do
          let g = { y } -> y + 1
          seq::map { xs = xs, f = { x } -> g { y = x } }
        let shout = { msg: Str } -> println { msg = msg }
        let main = {} -> perform Console in shout { msg = { x = 1, y = 2 }.label {} }
    """.trimIndent()

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }

    @JvmStatic
    fun main(args: Array<String>) {
        val out = StringBuilder()

        val manifest = IdentityDump::class.java.classLoader.getResourceAsStream("std/MANIFEST.txt")
            ?.bufferedReader()?.use { it.readLines() } ?: error("std/MANIFEST.txt is not on the classpath")
        // The build's own root, not the working directory: the stdlib's sources are
        // found from it, and the caller runs this from wherever it likes.
        val root = Paths.get(System.getProperty("mezze.project.root") ?: error("mezze.project.root is not set"))
        val resolver = ModuleResolver(root)
        for (file in manifest.sorted()) {
            if (file.isBlank() || file.substringAfterLast('/').startsWith("_")) continue
            val modulePath = file.removeSuffix(".mz").replace("/", "::")
            val module = resolver.resolveModule(modulePath)
            out.append("id ").append(modulePath).append(' ').append(module.identity?.hex ?: "none").append('\n')
        }

        // `warm`: check something else first. Type variables are numbered and named
        // from counters the process shares, so whatever was checked before decides
        // which numbers and names this program's variables get.
        if (args.getOrNull(1) == "warm") {
            val other = MezzeParser().parseParsedModule(
                "let swap = { p } -> { first = p.second, second = p.first }\nlet keep = { a, b, c } -> a\nlet twice_more = { x } -> x + x + x", "other.mz")
            check(TypeChecker().checkParsedModule(other, root, enablePrelude = true) is TypeChecker.TypeCheckResult.Success) {
                "the warm-up program did not type-check"
            }
        }

        val parsed = MezzeParser().parseParsedModule(program, args.getOrElse(0) { "app.mz" })
        val checked = TypeChecker().checkParsedModule(parsed, root, enablePrelude = true)
            as? TypeChecker.TypeCheckResult.Success ?: error("the dump program did not type-check")
        val typed = ArrayList<String>()
        for (decl in checked.typedModule!!.decls.filterIsInstance<com.mezze.language.typedast.TypedLetDecl>()) {
            typed += "typed ${decl.hash.hex} ${sha(StoreSerialization.contentOf(StoreSerialization.serializeTypedLetDecl(decl)))}"
        }
        val lowered = ArrayList<String>()
        for (fn in checked.loweredFunctions!!) {
            lowered += "ir ${fn.hash.hex} ${sha(StoreSerialization.contentOf(StoreSerialization.serializeFunctionIR(fn)))}"
        }
        typed.sorted().forEach { out.append(it).append('\n') }
        lowered.sorted().forEach { out.append(it).append('\n') }

        // Raw bytes: the child may be running under any default charset.
        System.out.write(out.toString().toByteArray(Charsets.UTF_8))
        System.out.flush()
    }
}
