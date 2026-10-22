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

    /** The constructors `std/syntax.mz` declares: every one must be produced from real code. */
    private val syntaxConstructors = mutableSetOf<String>()

    init {
        for (name in listOf("codebase", "syntax")) {
        val file = File("../stdlib/std/$name.mz")
        val module = MezzeParser().parseParsedModule(file.readText(), file.name)
        for (decl in module.decls.filterIsInstance<ParsedTypeDecl>()) {
            if (name == "syntax") (decl.definition as? ParsedVariantDef)?.constructors?.mapTo(syntaxConstructors) { it.name }
            when (val def = decl.definition) {
                is ParsedRecordDef -> records += def.fields.map { it.name }.toSet()
                is ParsedVariantDef -> for (ctor in def.constructors) {
                    constructors[ctor.name] = (ctor.dataType as? ParsedRecordType)?.entries?.map { it.name }?.toSet()
                }
                else -> Unit
            }
        }
        }
    }

    private val produced = mutableSetOf<String>()

    private val problems = mutableListOf<String>()

    private fun check(v: HostValue, where: String) {
        when (v) {
            is HostValue.Record -> {
                val names = v.fields.map { it.first }.toSet()
                if (names !in records) problems += "$where: a record {${names.sorted().joinToString()}} that std/codebase.mz does not declare"
                v.fields.forEach { (n, f) -> check(f, "$where.$n") }
            }
            is HostValue.Variant -> {
                produced += v.tag
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
            check(host.syntax(h), "syntax(${d.name})")
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    /**
     * Every node kind `std/syntax.mz` declares comes out of real code — so a node
     * the encoder never produces, or one the Mezze side forgot, is caught here
     * rather than by the first program that matches on it.
     */
    @Test
    fun `the syntax of a module that uses every form is shaped as std syntax declares, and covers it`() {
        val store = InMemoryMezzeStore()
        indexSource(store, everyForm)
        val host = CodebaseHostAdapter(CodebaseQueries(store))
        check(host.moduleSyntax("app"), "module_syntax(app)")
        // Every expression kind through the occurrence index, facts included.
        val exprKinds = syntaxConstructors.filter { it.endsWith("Expr") }
        for (kind in exprKinds) check(host.occurrences(kind, null), "occurrences($kind)")
        check(host.occurrences("BinaryExpr", "=="), "occurrences(BinaryExpr, ==)")
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))

        // No source syntax reaches these. A `Char` literal is only built by
        // compiler-generated code (derived impls), which is never stored as
        // written. A boolean literal PATTERN has a parser branch, but the lexer
        // never emits the token it waits for: `True`/`False` are constructors.
        val unreachable = setOf("CharExpr", "BoolLiteral")
        val missing = syntaxConstructors - produced - unreachable
        assertTrue(missing.isEmpty(), "never produced from source: ${missing.sorted()}")
    }

    private val everyForm = """
        use std::effects::log::{ println, Console, Log }
        use std::effects::noop::{ noop }
        use std::collections::seq
        use std::collections::bytes
        use std::str
        pub use std::collections::seq::{ size }

        ## A shape.
        #{ route: "/shapes" }
        pub type Shape = | Circle { r: Float }, Dot |
        type Point = { x: Int, ## The y.
          y: Int }
        newtype Meters = Float
        type Pair = { left: Int, right: Int }
        type Alias = Point

        ability Area for 'a where { area: { it: 'a } -> Float }
        ability Measure for 'a having 'unit where { measure: { it: 'a } -> 'unit }
        impl Area for Shape where { area = { it } -> match it is
          Circle { r } -> r * r
          Dot -> 0.0 }
        impl Measure for Point having 'unit = Int where { measure = { it } -> it.x }

        effect Beep where { beep: { times: Int } -> Int <Beep> }
        impl Beep for Quiet where { beep = { times } -> resume { val = times } }
        impl Beep for Scaled { factor: Int } where { beep = { times } -> resume { val = times * factor } }

        let half: { n: Nat } -> Nat = { n } -> n
        let ratio = {} -> 3//4
        let raw = {} -> bytes::from_str { s = "x" }
        let letters = {} -> str::chars { s = "ab" }
        let double: { x: Int } -> Int = { x } -> x * 2
        let typed_params = { a: Int, b: Int } -> a + b
        let open_params: { a: Int, ... } -> Int = { a, ... } -> a
        let logger: { msg: Str } -> {} <Log> = { msg } -> println { msg = msg }
        let shout: { names: [Str], extra: Option Meters } -> Str <*> = { names, extra } -> do
          # a comment on a statement
          let count = seq::size { xs = names }
          let { left, right } = { left = count, right = 2 }
          let first = match names is
            [head, ...rest] -> head
            [] -> "none"
          let piped = 5 |> double { x = @ }
          let short = { left, right }
          noop {}
          let moved = { ...short, left = 9 }
          let direct = perform Quiet in perform Beep::beep { times = 2 }
          let m = Meters.(1.5)
          let mm = match m is
            Meters.(v) -> v
          let more = [...[2, 3], 1]
          let tag = match first is
            "x$(tail)" -> tail
            "a" -> "an a"
            _ -> "other"
          let flag = match count > 3 && left == right is
            True -> 1
            False -> 0
          let picked = match extra is
            Some { val } if let Meters.(f) = val -> f > 1.0
            Some { val = _ } if count > 1 -> True
            Some { val = _ } -> False
            None -> (count >= 0)
          let n = perform Scaled { factor = 2 } in Beep.beep { times = 3 }
          let lits = [1n == 1n, 2.5 > 1.0, 4 % 3 == 1]
          if picked then "$(first): $(tag) $(short.left.to_str {})" else "no"
        let ask = {} -> perform Quiet in Beep.beep { times = 1 }
        let literal_patterns = { count: Int } -> do
          let sized = match count is
            0 -> "none"
            _ -> "some"
          let natted = match 3n is
            1n -> "one"
            _ -> "more"
          let floated = match 2.5 is
            2.5 -> "exact"
            _ -> "near"
          "$(sized) $(natted) $(floated)"
        let shape_name: { s: Shape } -> Str = { s } -> match s is
          Circle { r = _ } -> "circle"
          Dot -> "dot"
        let local_types: { p: Point, s: std::syntax::Span, v: | On, Off | } -> Int = { p, s, v } -> p.x
        main {}
        let main = {} -> perform Console in println { msg = shout { names = ["a"], extra = None } }
    """.trimIndent()
}
