package com.mezze.language.ast.solving

/**
 * An immutable hash map whose `plus` shares all but O(log n) of itself: a hash
 * array mapped trie.
 *
 * What makes composing substitutions linear. The solver builds one substitution
 * a step at a time — `step.compose(acc)` — and copying `acc` at every step made
 * checking a module quadratic in its size: a quarter of all time in a 25,000-line
 * project went to copying maps.
 *
 * It is a [Map], so every reader of a substitution's mappings is unchanged.
 */
class PersistentHashMap<K, V> private constructor(
    private val root: Node<K, V>,
    override val size: Int,
) : AbstractMap<K, V>() {

    override fun get(key: K): V? = root.get(key, spread(key), 0)

    override fun containsKey(key: K): Boolean = root.has(key, spread(key), 0)

    /** This map with [key] bound to [value]. */
    fun plus(key: K, value: V): PersistentHashMap<K, V> {
        val added = BooleanArray(1)
        val newRoot = root.put(key, value, spread(key), 0, added)
        return if (newRoot === root) this else PersistentHashMap(newRoot, size + if (added[0]) 1 else 0)
    }

    /** This map with every binding of [other] added; [other]'s win. */
    fun plusAll(other: Map<K, V>): PersistentHashMap<K, V> {
        var m = this
        for ((k, v) in other) m = m.plus(k, v)
        return m
    }

    override val entries: Set<Map.Entry<K, V>>
        get() = object : AbstractSet<Map.Entry<K, V>>() {
            override val size: Int get() = this@PersistentHashMap.size
            override fun iterator(): Iterator<Map.Entry<K, V>> {
                val out = ArrayList<Map.Entry<K, V>>(size)
                root.collect(out)
                return out.iterator()
            }
        }

    companion object {
        private val EMPTY = PersistentHashMap<Any?, Any?>(Node.Branch(0, emptyArray()), 0)

        @Suppress("UNCHECKED_CAST")
        fun <K, V> empty(): PersistentHashMap<K, V> = EMPTY as PersistentHashMap<K, V>

        /** [map] as a persistent map: itself when it already is one. */
        fun <K, V> of(map: Map<K, V>): PersistentHashMap<K, V> =
            map as? PersistentHashMap<K, V> ?: empty<K, V>().plusAll(map)

        private fun spread(key: Any?): Int {
            val h = key.hashCode()
            return h xor (h ushr 16)
        }

        private const val BITS = 5
        private const val MASK = (1 shl BITS) - 1
        private const val MAX_SHIFT = 30
    }

    private sealed class Node<K, V> {
        abstract fun get(key: K, hash: Int, shift: Int): V?
        abstract fun has(key: K, hash: Int, shift: Int): Boolean
        abstract fun put(key: K, value: V, hash: Int, shift: Int, added: BooleanArray): Node<K, V>
        abstract fun collect(out: MutableList<Map.Entry<K, V>>)

        class Leaf<K, V>(val hash: Int, override val key: K, override val value: V) : Node<K, V>(), Map.Entry<K, V> {
            override fun get(key: K, hash: Int, shift: Int): V? = if (hash == this.hash && key == this.key) value else null
            override fun has(key: K, hash: Int, shift: Int): Boolean = hash == this.hash && key == this.key
            override fun put(key: K, value: V, hash: Int, shift: Int, added: BooleanArray): Node<K, V> {
                if (hash == this.hash && key == this.key) return if (value === this.value) this else Leaf(hash, key, value)
                added[0] = true
                if (hash == this.hash || shift >= MAX_SHIFT) return Collision(hash, listOf(this, Leaf(hash, key, value)))
                return Branch.of(this, Leaf(hash, key, value), shift)
            }
            override fun collect(out: MutableList<Map.Entry<K, V>>) { out += this }
            override fun toString() = "$key=$value"
        }

        class Collision<K, V>(val hash: Int, val leaves: List<Leaf<K, V>>) : Node<K, V>() {
            override fun get(key: K, hash: Int, shift: Int): V? = leaves.firstOrNull { it.key == key }?.value
            override fun has(key: K, hash: Int, shift: Int): Boolean = leaves.any { it.key == key }
            override fun put(key: K, value: V, hash: Int, shift: Int, added: BooleanArray): Node<K, V> {
                val i = leaves.indexOfFirst { it.key == key }
                if (i >= 0) return Collision(hash, leaves.toMutableList().also { it[i] = Leaf(hash, key, value) })
                added[0] = true
                return Collision(hash, leaves + Leaf(hash, key, value))
            }
            override fun collect(out: MutableList<Map.Entry<K, V>>) { out += leaves }
        }

        class Branch<K, V>(val bitmap: Int, val children: Array<Node<K, V>>) : Node<K, V>() {
            private fun index(bit: Int) = Integer.bitCount(bitmap and (bit - 1))

            override fun get(key: K, hash: Int, shift: Int): V? {
                val bit = 1 shl ((hash ushr shift) and MASK)
                if (bitmap and bit == 0) return null
                return children[index(bit)].get(key, hash, shift + BITS)
            }

            override fun has(key: K, hash: Int, shift: Int): Boolean {
                val bit = 1 shl ((hash ushr shift) and MASK)
                if (bitmap and bit == 0) return false
                return children[index(bit)].has(key, hash, shift + BITS)
            }

            override fun put(key: K, value: V, hash: Int, shift: Int, added: BooleanArray): Node<K, V> {
                val bit = 1 shl ((hash ushr shift) and MASK)
                val i = index(bit)
                if (bitmap and bit == 0) {
                    added[0] = true
                    val next = arrayOfNulls<Node<K, V>>(children.size + 1)
                    System.arraycopy(children, 0, next, 0, i)
                    next[i] = Leaf(hash, key, value)
                    System.arraycopy(children, i, next, i + 1, children.size - i)
                    @Suppress("UNCHECKED_CAST")
                    return Branch(bitmap or bit, next as Array<Node<K, V>>)
                }
                val child = children[i]
                val updated = child.put(key, value, hash, shift + BITS, added)
                if (updated === child) return this
                val next = children.copyOf()
                next[i] = updated
                return Branch(bitmap, next)
            }

            override fun collect(out: MutableList<Map.Entry<K, V>>) { children.forEach { it.collect(out) } }

            companion object {
                fun <K, V> of(a: Leaf<K, V>, b: Leaf<K, V>, shift: Int): Node<K, V> {
                    if (shift >= MAX_SHIFT) return Collision(a.hash, listOf(a, b))
                    val ia = (a.hash ushr shift) and MASK
                    val ib = (b.hash ushr shift) and MASK
                    if (ia == ib) {
                        @Suppress("UNCHECKED_CAST")
                        return Branch(1 shl ia, arrayOf<Node<K, V>>(of(a, b, shift + BITS)))
                    }
                    @Suppress("UNCHECKED_CAST")
                    val pair = if (ia < ib) arrayOf<Node<K, V>>(a, b) else arrayOf<Node<K, V>>(b, a)
                    return Branch((1 shl ia) or (1 shl ib), pair)
                }
            }
        }
    }
}
