package com.mezze.language.store

import com.mezze.language.ast.hashing.MezzeHashBuilder
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.typedast.Hash

/**
 * What a function IS, as code — computed from what it lowered to, not from
 * what was written.
 *
 * A declaration's identity is computed before type checking, from its text and
 * what its names resolve to. It has to be: the type checker and every tool that
 * points at a declaration need it first. But that makes it the wrong name for a
 * piece of CODE in three ways —
 *
 *  - it includes the declaration's own name and where a nominal type lives, so
 *    the same function written in two modules, or under two names, is two
 *    identities;
 *  - a call it cannot resolve yet (`a + b`, `x.size {}`) is hashed as EVERY impl
 *    in scope that could answer it, so adding an unrelated impl to the standard
 *    library changes the identity of code that never calls it;
 *  - what a declaration lowers to can depend on its neighbours (a value typed by
 *    a later use, a call inlined or not by declaration order), which an identity
 *    taken before lowering cannot see.
 *
 * The code hash is taken AFTER: the hash of the lowered function's stored
 * content ([StoreSerialization.contentOf] — no file, no line, no invented name),
 * with its own name left out and every function it names replaced by THAT
 * function's code hash. So the same code is the same hash in any module, under
 * any name, on any machine; renaming a function changes no caller; and one code
 * hash is one piece of code by construction, because it is computed from it.
 *
 * It keys what is about the code rather than about the declaration: a compiled
 * function fetched by another node, a test's result that still holds because
 * nothing the test runs has changed.
 *
 * Mutually recursive functions cannot each contain the other's hash. A group is
 * hashed by refinement: every member starts as its content with calls inside
 * the group left blank, and is then re-hashed with each such call replaced by
 * the callee's current hash, once per member — enough for a difference anywhere
 * in the group to reach every member that can see it. Members that still agree
 * are the same code: there is no call sequence that tells them apart.
 */
object CodeHash {

    /**
     * The code hash of each of [functions], by identity.
     *
     * [known] answers for a function that is named but not among them — one
     * already stored, the standard library's. A name it cannot answer for stays
     * as the identity it is: still one function, only not yet shared under
     * another name.
     */
    fun of(functions: Collection<FunctionIR>, known: (Hash) -> Hash? = { null }): Map<Hash, Hash> {
        val byHash = LinkedHashMap<String, FunctionIR>()
        for (fn in functions) byHash.putIfAbsent(fn.hash.hex, fn)
        val order = byHash.keys.toList()
        val index = HashMap<String, Int>().apply { order.forEachIndexed { i, h -> put(h, i) } }

        val contents = order.map { content(byHash.getValue(it)) }
        val names = contents.map { namedHashes(it) }
        val uses = names.map { refs -> refs.mapNotNullTo(sortedSetOf()) { index[it.hex] } }

        val result = arrayOfNulls<String>(order.size)
        val externals = HashMap<String, String?>()
        fun external(hex: String): String? = externals.getOrPut(hex) { known(Hash(hex))?.hex }

        for (group in groups(order.size, uses)) {
            val members = group.toSet()
            // What a name inside this function's content is written as, given what
            // the group's members currently hash to.
            fun label(n: Int, current: Map<Int, String>): String {
                val bytes = contents[n].copyOf()
                for (ref in names[n]) {
                    val target = index[ref.hex]
                    val written = when {
                        target == null -> external(ref.hex) ?: continue
                        target in members -> current[target] ?: IN_GROUP
                        else -> result[target] ?: error("internal: function $target was named before it was hashed")
                    }
                    for (i in 0 until HEX) bytes[ref.at + i] = written[i].code.toByte()
                }
                return MezzeHashBuilder().append("code").append(bytes).buildHash().hex
            }
            var current = emptyMap<Int, String>()
            repeat(group.size + 1) { current = group.associateWith { label(it, current) } }
            for (n in group) result[n] = current.getValue(n)
        }
        return order.indices.associate { Hash(order[it]) to Hash(result[it]!!) }
    }

    /** One function's code hash, when it names nothing that is still to be hashed. */
    fun of(function: FunctionIR, known: (Hash) -> Hash? = { null }): Hash = of(listOf(function), known).getValue(function.hash)

    private const val HEX = 128
    private val IN_GROUP = "0".repeat(HEX)

    /** The function's stored content with its own name left out: a name is what it is CALLED. */
    private fun content(fn: FunctionIR): ByteArray =
        StoreSerialization.contentOf(StoreSerialization.serializeFunctionIR(fn.copy(name = "", qualifiedName = "")))

    private class Named(val hex: String, val at: Int)

    /**
     * Every hash written in [content], with where. A hash is stored as a CBOR text
     * of exactly [HEX] lowercase hex digits — header `78 80` — and nothing else in
     * a lowered function is; a string literal that happened to look like one would
     * only be offered to [known], which does not know it.
     */
    private fun namedHashes(content: ByteArray): List<Named> {
        val found = ArrayList<Named>()
        var i = 0
        while (i + 2 + HEX <= content.size) {
            if (content[i] == 0x78.toByte() && content[i + 1] == 0x80.toByte() && isHex(content, i + 2)) {
                found.add(Named(String(content, i + 2, HEX, Charsets.US_ASCII), i + 2))
                i += 2 + HEX
            } else i++
        }
        return found
    }

    private fun isHex(bytes: ByteArray, from: Int): Boolean {
        for (i in from until from + HEX) {
            val c = bytes[i].toInt()
            if (!(c in '0'.code..'9'.code || c in 'a'.code..'f'.code)) return false
        }
        return true
    }

    /** Strongly connected components of the naming graph, each after the ones it names. Iterative Tarjan. */
    private fun groups(n: Int, uses: List<Set<Int>>): List<List<Int>> {
        val number = IntArray(n) { -1 }
        val low = IntArray(n)
        val onStack = BooleanArray(n)
        val stack = ArrayDeque<Int>()
        val out = ArrayList<List<Int>>()
        var next = 0
        val succ = uses.map { it.toIntArray() }
        for (root in 0 until n) {
            if (number[root] != -1) continue
            val frames = ArrayDeque<IntArray>()
            fun enter(v: Int) {
                number[v] = next; low[v] = next; next++
                stack.addLast(v); onStack[v] = true
                frames.addLast(intArrayOf(v, 0))
            }
            enter(root)
            while (frames.isNotEmpty()) {
                val frame = frames.last()
                val v = frame[0]
                if (frame[1] < succ[v].size) {
                    val w = succ[v][frame[1]++]
                    if (number[w] == -1) enter(w) else if (onStack[w]) low[v] = minOf(low[v], number[w])
                    continue
                }
                frames.removeLast()
                frames.lastOrNull()?.let { parent -> low[parent[0]] = minOf(low[parent[0]], low[v]) }
                if (low[v] == number[v]) {
                    val group = ArrayList<Int>()
                    do { val w = stack.removeLast(); onStack[w] = false; group.add(w) } while (w != v)
                    out.add(group.sorted())
                }
            }
        }
        return out
    }
}
