package com.mezze.language.codebase

import com.mezze.language.comptime.HostValue
import com.mezze.language.parsedast.ParsedRecordDef
import com.mezze.language.parsedast.ParsedRecordType
import com.mezze.language.parsedast.ParsedTypeDecl
import com.mezze.language.parsedast.ParsedVariantDef
import com.mezze.language.parser.MezzeParser
import com.mezze.language.store.InMemoryMezzeStore
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The adapter and `std/codebase.mz` describe ONE set of shapes in two languages.
 *
 * The interpreter turns a [HostValue] into a Mezze value without checking it
 * against any type, so a field the adapter spells differently, or a constructor
 * the Mezze side does not declare, surfaces only when some program matches on it.
 * This reads the Mezze declarations and checks every record and constructor the
 * adapter produces over real code against them.
 */
class CodebaseShapeContractTest {

    private val source = """
        use std::effects::log::{ println, Log }

        ## Totals.
        ability Total for 'a where { total: { it: 'a } -> Int }
        effect Ping where { ping: { times: Int } -> Int <Ping> }

        type Point = { x: Int, y: Int }
        type Shape = | Circle { r: Float }, Dot |
        type Box 'a = { item: 'a }
        newtype Meters = Float
        type Name = Str
        impl Total for Point where { total = { it } -> it.x + it.y }

        #{route: "/p"}
        pub let shout: { msg: Str } -> {} <Log> = { msg } -> println { msg = msg }
        pub let pinged: {} -> Int <Ping> = {} -> Ping.ping { times = 1 }
        pub let sum: { x: 'a } with Total 'a -> Int = { x } -> x.total {}
        let twice: { msg: Str } -> {} <Log> = { msg } -> do
          shout { msg = msg }
          shout { msg = msg }
        let classify: { s: Shape, xs: [Int] } -> Str = { s, xs } -> do
          let n = 2
          match s is
            Circle { r } -> if r > 1.5 then "big" else "small $(shape_name { s = s })"
            Dot -> match xs is
              [a, ...rest] -> "list"
              _ -> "empty"
        let shape_name: { s: Shape } -> Str = { s } -> "shape"
        let build: {} -> { p: Point, q: [Int], f: Bool } = {} -> { p = { x = 1, y = 2 }, q = [1, 2], f = True }
    """.trimIndent()

    /** Declared constructors, by tag: the payload record's field names, or null for none. */
    private val constructors = mutableMapOf<String, Set<String>?>()
    private val records = mutableSetOf<Set<String>>()

    init {
        val file = File("../stdlib/std/codebase.mz")
        val module = MezzeParser().parseParsedModule(file.readText(), file.name)
        for (decl in module.decls.filterIsInstance<ParsedTypeDecl>()) {
            when (val def = decl.definition) {
                is ParsedRecordDef -> records += def.fields.map { it.name }.toSet()
                is ParsedVariantDef -> for (ctor in def.constructors) {
                    constructors[ctor.name] = (ctor.dataType as? ParsedRecordType)?.entries?.map { it.name }?.toSet()
                }
                else -> Unit
            }
        }
    }

    private val problems = mutableListOf<String>()

    private fun check(v: HostValue, where: String) {
        when (v) {
            is HostValue.Record -> {
                val names = v.fields.map { it.first }.toSet()
                if (names !in records) problems += "$where: a record {${names.sorted().joinToString()}} that std/codebase.mz does not declare"
                v.fields.forEach { (n, f) -> check(f, "$where.$n") }
            }
            is HostValue.Variant -> {
                if (v.tag !in constructors) problems += "$where: constructor `${v.tag}` is not declared in std/codebase.mz"
                else {
                    val want = constructors[v.tag]
                    val got = v.payload?.fields?.map { it.first }?.toSet()
                    if (want != got) problems += "$where: `${v.tag}` carries ${got ?: "nothing"}, declared ${want ?: "nothing"}"
                }
                v.payload?.fields?.forEach { (n, f) -> check(f, "$where.${v.tag}.$n") }
            }
            is HostValue.Seq -> v.items.forEachIndexed { i, x -> check(x, "$where[$i]") }
            is HostValue.Opt -> v.value?.let { check(it, where) }
            is HostValue.Str, is HostValue.IntV, is HostValue.Bool, is HostValue.FloatV -> Unit
        }
    }

    @Test
    fun `every shape the adapter produces is one std codebase declares`() {
        val store = InMemoryMezzeStore()
        indexSource(store, source)
        val q = CodebaseQueries(store)
        val host = CodebaseHostAdapter(q)
        val all = q.declsIn("app")
        assertTrue(all.size > 8, "the fixture code did not index: $all")

        check(host.decls("app"), "decls")
        check(host.modules(""), "modules")
        for (d in all) {
            val h = d.hash.hex
            check(host.resolve("app", d.name), "resolve(${d.name})")
            check(host.info(h), "info(${d.name})")
            check(host.signature(h), "signature(${d.name})")
            check(host.definition(h), "definition(${d.name})")
            check(host.implsOf(h), "implsOf(${d.name})")
            check(host.implsFor(h), "implsFor(${d.name})")
            check(host.abilitiesOf(h), "abilitiesOf(${d.name})")
            check(host.effectsOf(h), "effectsOf(${d.name})")
            check(host.performing(h), "performing(${d.name})")
            check(host.requiring(h), "requiring(${d.name})")
            check(host.performersOf(h), "performersOf(${d.name})")
            check(host.callers(h), "callers(${d.name})")
            check(host.callees(h), "callees(${d.name})")
            check(host.references(h), "references(${d.name})")
            check(host.usages(h), "usages(${d.name})")
            check(host.ref(h), "ref(${d.name})")
            check(host.body(h), "body(${d.name})")
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }
}
