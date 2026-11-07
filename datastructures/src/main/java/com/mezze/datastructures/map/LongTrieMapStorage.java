package com.mezze.datastructures.map;

import com.mezze.datastructures.util.TrieHash;

/**
 * A persistent hash-array-mapped trie keyed on primitive {@code long}.
 *
 * <p>WHY THIS REPLACES THE OPEN-ADDRESSED TABLE. {@code LongHashMapStorage}
 * reached O(1) inserts by MUTATING its arrays whenever it still held an
 * ownership token, and handing the token to the derived storage. That makes a
 * derivation observable in the map it was derived from — BUG 38: a base map
 * reported 777 where the program had stored 300, and {@code contains_key} true
 * for a key only the derived map had, leaving the base self-inconsistent
 * (its {@code size} said 64 while a 65th key answered present).
 *
 * <p>The ownership token establishes "only one storage may WRITE these arrays".
 * What a persistent map needs is "no storage may OBSERVE a write it did not
 * make", and no amount of bookkeeping over shared mutable arrays provides it:
 * a superseded storage can only reconstruct its view from forward links (which
 * retain every later version — measured at 2.57 GB surviving each young
 * collection against HEAD's 19 MB) or from an undo log (unbounded, and retained
 * by the current version, which is the one appending to it). That whole family
 * was implemented, measured and discarded; see PERF-LOG and known-bugs BUG 38.
 *
 * <p>Structural sharing has the property directly. No version references
 * another, nothing is ever overwritten, and nothing needs undoing. An update
 * copies only the nodes on one root-to-leaf path — O(log32 n) — so the
 * shared-base case costs the same as any other update instead of cloning the
 * whole table.
 *
 * <p>NO COLLISION NODES, and that is not an omission. {@link #mix} is the
 * murmur3 64-bit finalizer, which is a BIJECTION on 64 bits: distinct keys
 * cannot share a full hash, so two keys can only travel together while their
 * hashes agree, and they must part before the 64 bits run out. Depth is bounded
 * at 13 levels and the "same hash, different key" case the general HAMT needs a
 * collision node for cannot arise. The impossible branch throws rather than
 * looping.
 *
 * <p>A null value means absent, the same convention the storage it replaces
 * used ({@code containsKey} was {@code getLong(key) != null}).
 */
public final class LongTrieMapStorage implements MapStorage {

    /** 5 bits of hash per level: 32 slots, one int bitmap each. */
    private static final int BITS = 5;
    private static final int MASK = 31;

    private static final long[] NO_KEYS = new long[0];
    private static final Object[] NO_VALUES = new Object[0];
    private static final Node[] NO_NODES = new Node[0];
    private static final Node EMPTY_ROOT = new Node(0, 0, NO_KEYS, NO_VALUES, NO_NODES);

    private final Node root;
    private final int size;

    public LongTrieMapStorage() {
        this(EMPTY_ROOT, 0);
    }

    private LongTrieMapStorage(Node root, int size) {
        this.root = root;
        this.size = size;
    }

    /**
     * One trie level. {@code dataMap} marks the slots holding an inline entry
     * and {@code nodeMap} the slots holding a child; a slot is in at most one.
     * Entries and children are stored compactly in bitmap order, so a node is
     * exactly as wide as it is occupied.
     *
     * <p>All fields final: this is the whole point. Nothing here is ever
     * written after construction, which is why no version can observe another.
     */
    static final class Node {
        final int dataMap;
        final int nodeMap;
        final long[] keys;
        final Object[] values;
        final Node[] nodes;

        Node(int dataMap, int nodeMap, long[] keys, Object[] values, Node[] nodes) {
            this.dataMap = dataMap;
            this.nodeMap = nodeMap;
            this.keys = keys;
            this.values = values;
            this.nodes = nodes;
        }
    }

    // ───────────────────────────── reads ─────────────────────────────

    @Override
    public int size() {
        return size;
    }

    public Object getLong(long key) {
        Node n = root;
        long hash = TrieHash.mix64(key);
        for (int shift = 0; ; shift += BITS) {
            int bit = 1 << (int) ((hash >>> shift) & MASK);
            if ((n.dataMap & bit) != 0) {
                int i = Integer.bitCount(n.dataMap & (bit - 1));
                return n.keys[i] == key ? n.values[i] : null;
            }
            if ((n.nodeMap & bit) == 0) return null;
            n = n.nodes[Integer.bitCount(n.nodeMap & (bit - 1))];
        }
    }

    @Override
    public Object get(Object key) {
        return key instanceof Long l ? getLong(l) : null;
    }

    public boolean containsKeyLong(long key) {
        return getLong(key) != null;
    }

    @Override
    public boolean containsKey(Object key) {
        return key instanceof Long l && getLong(l) != null;
    }

    @Override
    public byte getStrategyTag() {
        return TAG_HASH;
    }

    // ───────────────────────────── insert ────────────────────────────

    @Override
    public MapStorage put(Object key, Object value) {
        if (key instanceof Long l) return putLong(l, value);
        return transitionToGeneric().put(key, value);
    }

    public MapStorage putLong(long key, Object value) {
        int[] grew = new int[1];
        Node newRoot = insert(root, key, value, TrieHash.mix64(key), 0, grew);
        if (newRoot == root) return this;
        return new LongTrieMapStorage(newRoot, size + grew[0]);
    }

    private static Node insert(Node n, long key, Object value, long hash, int shift, int[] grew) {
        int bit = 1 << (int) ((hash >>> shift) & MASK);

        if ((n.dataMap & bit) != 0) {
            int i = Integer.bitCount(n.dataMap & (bit - 1));
            long had = n.keys[i];
            if (had == key) {
                if (n.values[i] == value) return n;
                Object[] values = n.values.clone();
                values[i] = value;
                return new Node(n.dataMap, n.nodeMap, n.keys, values, n.nodes);
            }
            // Two distinct keys want the same slot: push both one level down.
            grew[0]++;
            Node child = merge(had, n.values[i], TrieHash.mix64(had), key, value, hash, shift + BITS);
            return dataToNode(n, bit, i, child);
        }

        if ((n.nodeMap & bit) != 0) {
            int i = Integer.bitCount(n.nodeMap & (bit - 1));
            Node was = n.nodes[i];
            Node now = insert(was, key, value, hash, shift + BITS, grew);
            if (now == was) return n;
            Node[] nodes = n.nodes.clone();
            nodes[i] = now;
            return new Node(n.dataMap, n.nodeMap, n.keys, n.values, nodes);
        }

        grew[0]++;
        return withData(n, bit, key, value);
    }

    /**
     * A node holding two entries whose hashes agree down to {@code shift}.
     * Recurses only while they agree; because {@link #mix} is a bijection and
     * the keys differ, they must part within the 64 bits.
     */
    private static Node merge(long k1, Object v1, long h1, long k2, Object v2, long h2, int shift) {
        int i1 = (int) ((h1 >>> shift) & MASK);
        int i2 = (int) ((h2 >>> shift) & MASK);
        if (i1 != i2) {
            int dataMap = (1 << i1) | (1 << i2);
            return i1 < i2
                ? new Node(dataMap, 0, new long[]{k1, k2}, new Object[]{v1, v2}, NO_NODES)
                : new Node(dataMap, 0, new long[]{k2, k1}, new Object[]{v2, v1}, NO_NODES);
        }
        if (shift >= 64) {
            throw new IllegalStateException(
                "two distinct long keys with an identical 64-bit mix — TrieHash.mix64() is not a bijection");
        }
        Node child = merge(k1, v1, h1, k2, v2, h2, shift + BITS);
        return new Node(0, 1 << i1, NO_KEYS, NO_VALUES, new Node[]{child});
    }

    private static Node withData(Node n, int bit, long key, Object value) {
        int i = Integer.bitCount(n.dataMap & (bit - 1));
        int len = n.keys.length;
        long[] keys = new long[len + 1];
        Object[] values = new Object[len + 1];
        System.arraycopy(n.keys, 0, keys, 0, i);
        System.arraycopy(n.values, 0, values, 0, i);
        keys[i] = key;
        values[i] = value;
        System.arraycopy(n.keys, i, keys, i + 1, len - i);
        System.arraycopy(n.values, i, values, i + 1, len - i);
        return new Node(n.dataMap | bit, n.nodeMap, keys, values, n.nodes);
    }

    /** Replace the inline entry at {@code bit} with a child node. */
    private static Node dataToNode(Node n, int bit, int dataIdx, Node child) {
        int len = n.keys.length;
        long[] keys = new long[len - 1];
        Object[] values = new Object[len - 1];
        System.arraycopy(n.keys, 0, keys, 0, dataIdx);
        System.arraycopy(n.values, 0, values, 0, dataIdx);
        System.arraycopy(n.keys, dataIdx + 1, keys, dataIdx, len - dataIdx - 1);
        System.arraycopy(n.values, dataIdx + 1, values, dataIdx, len - dataIdx - 1);

        int ni = Integer.bitCount(n.nodeMap & (bit - 1));
        Node[] nodes = new Node[n.nodes.length + 1];
        System.arraycopy(n.nodes, 0, nodes, 0, ni);
        nodes[ni] = child;
        System.arraycopy(n.nodes, ni, nodes, ni + 1, n.nodes.length - ni);

        return new Node(n.dataMap ^ bit, n.nodeMap | bit, keys, values, nodes);
    }

    // ───────────────────────────── remove ────────────────────────────

    @Override
    public MapStorage remove(Object key) {
        return key instanceof Long l ? removeLong(l) : this;
    }

    public MapStorage removeLong(long key) {
        boolean[] gone = new boolean[1];
        Node newRoot = delete(root, key, TrieHash.mix64(key), 0, gone);
        if (!gone[0]) return this;
        if (size - 1 == 0) return EmptyMapStorage.INSTANCE;
        return new LongTrieMapStorage(newRoot, size - 1);
    }

    private static Node delete(Node n, long key, long hash, int shift, boolean[] gone) {
        int bit = 1 << (int) ((hash >>> shift) & MASK);

        if ((n.dataMap & bit) != 0) {
            int i = Integer.bitCount(n.dataMap & (bit - 1));
            if (n.keys[i] != key) return n;
            gone[0] = true;
            return withoutData(n, bit, i);
        }

        if ((n.nodeMap & bit) != 0) {
            int i = Integer.bitCount(n.nodeMap & (bit - 1));
            Node was = n.nodes[i];
            Node now = delete(was, key, hash, shift + BITS, gone);
            if (now == was) return n;
            // CANONICALISE: a child reduced to a single inline entry moves back
            // up, so removal cannot leave a chain of one-entry nodes behind and
            // lookups stay as shallow as the contents justify.
            if (now.nodeMap == 0 && Integer.bitCount(now.dataMap) == 1) {
                return nodeToData(n, bit, i, now.keys[0], now.values[0]);
            }
            Node[] nodes = n.nodes.clone();
            nodes[i] = now;
            return new Node(n.dataMap, n.nodeMap, n.keys, n.values, nodes);
        }

        return n;
    }

    private static Node withoutData(Node n, int bit, int i) {
        int len = n.keys.length;
        long[] keys = new long[len - 1];
        Object[] values = new Object[len - 1];
        System.arraycopy(n.keys, 0, keys, 0, i);
        System.arraycopy(n.values, 0, values, 0, i);
        System.arraycopy(n.keys, i + 1, keys, i, len - i - 1);
        System.arraycopy(n.values, i + 1, values, i, len - i - 1);
        return new Node(n.dataMap ^ bit, n.nodeMap, keys, values, n.nodes);
    }

    /** Replace the child at {@code bit} with a single inline entry. */
    private static Node nodeToData(Node n, int bit, int nodeIdx, long key, Object value) {
        Node[] nodes = new Node[n.nodes.length - 1];
        System.arraycopy(n.nodes, 0, nodes, 0, nodeIdx);
        System.arraycopy(n.nodes, nodeIdx + 1, nodes, nodeIdx, n.nodes.length - nodeIdx - 1);

        int i = Integer.bitCount(n.dataMap & (bit - 1));
        int len = n.keys.length;
        long[] keys = new long[len + 1];
        Object[] values = new Object[len + 1];
        System.arraycopy(n.keys, 0, keys, 0, i);
        System.arraycopy(n.values, 0, values, 0, i);
        keys[i] = key;
        values[i] = value;
        System.arraycopy(n.keys, i, keys, i + 1, len - i);
        System.arraycopy(n.values, i, values, i + 1, len - i);

        return new Node(n.dataMap | bit, n.nodeMap ^ bit, keys, values, nodes);
    }

    // ──────────────────────────── iteration ──────────────────────────

    @Override
    public Object[] keys() {
        Object[] out = new Object[size];
        collectKeys(root, out, new int[1]);
        return out;
    }

    @Override
    public Object[] values() {
        Object[] out = new Object[size];
        collectValues(root, out, new int[1]);
        return out;
    }

    @Override
    public void forEach(java.util.function.BiConsumer<Object, Object> consumer) {
        walk(root, consumer);
    }

    /** Primitive-keyed iteration — no boxing for keys. */
    public void forEachLong(LongValueConsumer consumer) {
        walkLong(root, consumer);
    }

    @FunctionalInterface
    public interface LongValueConsumer {
        void accept(long key, Object value);
    }

    private static void collectKeys(Node n, Object[] out, int[] at) {
        for (int i = 0; i < n.keys.length; i++) out[at[0]++] = Long.valueOf(n.keys[i]);
        for (Node child : n.nodes) collectKeys(child, out, at);
    }

    private static void collectValues(Node n, Object[] out, int[] at) {
        for (int i = 0; i < n.values.length; i++) out[at[0]++] = n.values[i];
        for (Node child : n.nodes) collectValues(child, out, at);
    }

    private static void walk(Node n, java.util.function.BiConsumer<Object, Object> consumer) {
        for (int i = 0; i < n.keys.length; i++) {
            consumer.accept(Long.valueOf(n.keys[i]), n.values[i]);
        }
        for (Node child : n.nodes) walk(child, consumer);
    }

    private static void walkLong(Node n, LongValueConsumer consumer) {
        for (int i = 0; i < n.keys.length; i++) consumer.accept(n.keys[i], n.values[i]);
        for (Node child : n.nodes) walkLong(child, consumer);
    }

    /** Promote to {@link TrieMapStorage} when a non-Long key arrives. */
    private MapStorage transitionToGeneric() {
        MapStorage out = new TrieMapStorage();
        Object[] ks = keys();
        Object[] vs = values();
        for (int i = 0; i < ks.length; i++) {
            out = out.put(ks[i], vs[i]);
        }
        return out;
    }
}
