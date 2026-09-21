package com.mezze.language.parser
import org.junit.jupiter.api.Test
class ParserPermissiveProbe {
    @Test fun probe() {
        val cases = listOf(
            "type  # note",
            "impl  # note",
            "impl E # note",
            "type Colo=  | Red, GreenB lue |",
            "newtype  serId = Int",
            "effect Log where { emit",
            "let\t s ",
            "'a @",
        )
        for (c in cases) {
            val r = try {
                val m = MezzeParser().parseParsedModule(c, "t.mz")
                "ACCEPTED decls=${m.decls.size} kinds=${m.decls.map { it::class.simpleName }}"
            } catch (e: Throwable) { "REJECTED(${e.javaClass.simpleName})" }
            println("PERMPROBE ${r}  <<${c.replace("\t","\\t")}>>")
        }
    }
}
