package com.mezze.datastructures.map;

import com.mezze.datastructures.util.TrieHash;

import java.util.Objects;

/**
 * A persistent hash-array-mapped trie for arbitrary keys — the general-key
 * sister of {@link LongTrieMapStorage}, replacing the open-addressed
 * generic and String-keyed tables (both now deleted).
 *
 * <p>Same reason as the Long version: those tables mutated their arrays while
 * they still held an ownership token and handed the token to the derived
 * storage, so a derivation was observable in the map it came from (BUG 38). The
 * token says "only one storage may WRITE these arrays"; a persistent map needs
 * "no storage may OBSERVE a write it did not make". Structural sharing gives it
 * directly — every field here is final.
 *
 * <p>COLLISION NODES ARE NEEDED HERE, unlike in the Long version. That one
 * hashes a {@code long} through a bijection, so distinct keys cannot share a
 * full hash. Here the hash comes from {@code hashCode()}, which is 32 bits and
 * not injective: two unequal keys genuinely can agree on all of it, and once
 * the 32 bits are spent they have to share a node. {@link Collision} holds them
 * in parallel arrays and scans.
 *
 * <p>Key semantics match the storage replaced exactly — {@code hashCode()} put
 * through the murmur3 32-bit finalizer, {@code Objects.equals} for comparison,
 * null keys permitted. A key type with its own {@code Eq}/{@code Hash} goes to
 * {@code EvidenceMapStorage} instead and never reaches here.
 *
 * <p>A null value means absent, the convention the storages this replaces used.
 */
public final class TrieMapStorage implements MapStorage {

    private static final int BITS = 5;
    private static final int MASK = 31;
    /** Hash width: past this a {@link Collision} node is the only option. */
    private static final int HASH_BITS = 32;

    private static final Object[] NOTHING = new Object[0];
    private static final Node[] NO_NODES = new Node[0];
    private static final Bitmap EMPTY_ROOT = new Bitmap(0, 0, NOTHING, NOTHING, NO_NODES);

    private final Node root;
    private final int size;

    public TrieMapStorage() {
        this(EMPTY_ROOT, 0);
    }

    private TrieMapStorage(Node root, int size) {
        this.root = root;
        this.size = size;
    }

    /**
     * Bulk build from a java Map — the persistent counterpart of
     * {@code HashMapStorage.fromMap}, which the Map's transforms
     * ({@code map}, {@code filter}, …) used to call.
     *
     * <p>Rerouting only the SmallFlat promotion left those transforms building
     * an open-addressed table, so a MAPPED map still carried BUG 38 — its base
     * acquired a key that was only added to a map derived from it. The Set had
     * the identical hole through {@code fromCollection}; a test found both.
     *
     * <p>O(n log32 n) where the table was O(n), which runs once per transform.
     */
    public static MapStorage fromMap(java.util.Map<?, ?> entries) {
        MapStorage out = new TrieMapStorage();
        for (java.util.Map.Entry<?, ?> e : entries.entrySet()) {
            out = out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    sealed interface Node permits Bitmap, Collision {}

    /** One trie level. Entries and children sit compactly in bitmap order. */
    static final class Bitmap implements Node {
        final int dataMap;
        final int nodeMap;
        final Object[] keys;
        final Object[] values;
        final Node[] nodes;

        Bitmap(int dataMap, int nodeMap, Object[] keys, Object[] values, Node[] nodes) {
            this.dataMap = dataMap;
            this.nodeMap = nodeMap;
            this.keys = keys;
            this.values = values;
            this.nodes = nodes;
        }
    }

    /** Keys that agree on all {@value #HASH_BITS} hash bits but are not equal. */
    static final class Collision implements Node {
        final int hash;
        final Object[] keys;
        final Object[] values;

        Collision(int hash, Object[] keys, Object[] values) {
            this.hash = hash;
            this.keys = keys;
            this.values = values;
        }
    }

    // ───────────────────────────── reads ─────────────────────────────

    @Override
    public int size() {
        return size;
    }

    @Override
    public Object get(Object key) {
        int hash = TrieHash.mix32(key);
        Node n = root;
        for (int shift = 0; ; shift += BITS) {
            if (n instanceof Collision c) {
                if (c.hash != hash) return null;
                for (int i = 0; i < c.keys.length; i++) {
                    if (Objects.equals(c.keys[i], key)) return c.values[i];
                }
                return null;
            }
            Bitmap b = (Bitmap) n;
            int bit = 1 << ((hash >>> shift) & MASK);
            if ((b.dataMap & bit) != 0) {
                int i = Integer.bitCount(b.dataMap & (bit - 1));
                return Objects.equals(b.keys[i], key) ? b.values[i] : null;
            }
            if ((b.nodeMap & bit) == 0) return null;
            n = b.nodes[Integer.bitCount(b.nodeMap & (bit - 1))];
        }
    }

    @Override
    public boolean containsKey(Object key) {
        return get(key) != null;
    }

    @Override
    public byte getStrategyTag() {
        return TAG_HASH;
    }

    // ───────────────────────────── insert ────────────────────────────

    @Override
    public MapStorage put(Object key, Object value) {
        int[] grew = new int[1];
        Node newRoot = insert(root, key, value, TrieHash.mix32(key), 0, grew);
        if (newRoot == root) return this;
        return new TrieMapStorage(newRoot, size + grew[0]);
    }

    private static Node insert(Node n, Object key, Object value, int hash, int shift, int[] grew) {
        if (n instanceof Collision c) return insertIntoCollision(c, key, value, hash, shift, grew);

        Bitmap b = (Bitmap) n;
        int bit = 1 << ((hash >>> shift) & MASK);

        if ((b.dataMap & bit) != 0) {
            int i = Integer.bitCount(b.dataMap & (bit - 1));
            Object had = b.keys[i];
            if (Objects.equals(had, key)) {
                if (b.values[i] == value) return b;
                Object[] values = b.values.clone();
                values[i] = value;
                return new Bitmap(b.dataMap, b.nodeMap, b.keys, values, b.nodes);
            }
            grew[0]++;
            Node child = merge(had, b.values[i], TrieHash.mix32(had), key, value, hash, shift + BITS);
            return dataToNode(b, bit, i, child);
        }

        if ((b.nodeMap & bit) != 0) {
            int i = Integer.bitCount(b.nodeMap & (bit - 1));
            Node was = b.nodes[i];
            Node now = insert(was, key, value, hash, shift + BITS, grew);
            if (now == was) return b;
            Node[] nodes = b.nodes.clone();
            nodes[i] = now;
            return new Bitmap(b.dataMap, b.nodeMap, b.keys, b.values, nodes);
        }

        grew[0]++;
        return withData(b, bit, key, value);
    }

    private static Node insertIntoCollision(Collision c, Object key, Object value,
                                            int hash, int shift, int[] grew) {
        if (c.hash == hash) {
            for (int i = 0; i < c.keys.length; i++) {
                if (Objects.equals(c.keys[i], key)) {
                    if (c.values[i] == value) return c;
                    Object[] values = c.values.clone();
                    values[i] = value;
                    return new Collision(c.hash, c.keys, values);
                }
            }
            grew[0]++;
            int len = c.keys.length;
            Object[] keys = java.util.Arrays.copyOf(c.keys, len + 1);
            Object[] values = java.util.Arrays.copyOf(c.values, len + 1);
            keys[len] = key;
            values[len] = value;
            return new Collision(c.hash, keys, values);
        }
        // A key whose hash differs reached this collision node: the two must be
        // separated by a bitmap level above it.
        grew[0]++;
        return splitCollision(c, key, value, hash, shift);
    }

    /** A bitmap node holding {@code c} and the new entry at {@code shift}. */
    private static Node splitCollision(Collision c, Object key, Object value, int hash, int shift) {
        int ci = (c.hash >>> shift) & MASK;
        int ki = (hash >>> shift) & MASK;
        if (ci != ki) {
            return new Bitmap(1 << ki, 1 << ci,
                new Object[]{key}, new Object[]{value}, new Node[]{c});
        }
        if (shift >= HASH_BITS) {
            throw new IllegalStateException("hash bits exhausted with differing hashes");
        }
        Node child = splitCollision(c, key, value, hash, shift + BITS);
        return new Bitmap(0, 1 << ci, NOTHING, NOTHING, new Node[]{child});
    }

    private static Node merge(Object k1, Object v1, int h1,
                              Object k2, Object v2, int h2, int shift) {
        if (shift >= HASH_BITS) {
            // 32 bits spent and the keys are unequal: they share a node.
            return new Collision(h1, new Object[]{k1, k2}, new Object[]{v1, v2});
        }
        int i1 = (h1 >>> shift) & MASK;
        int i2 = (h2 >>> shift) & MASK;
        if (i1 != i2) {
            int dataMap = (1 << i1) | (1 << i2);
            return i1 < i2
                ? new Bitmap(dataMap, 0, new Object[]{k1, k2}, new Object[]{v1, v2}, NO_NODES)
                : new Bitmap(dataMap, 0, new Object[]{k2, k1}, new Object[]{v2, v1}, NO_NODES);
        }
        Node child = merge(k1, v1, h1, k2, v2, h2, shift + BITS);
        return new Bitmap(0, 1 << i1, NOTHING, NOTHING, new Node[]{child});
    }

    private static Bitmap withData(Bitmap b, int bit, Object key, Object value) {
        int i = Integer.bitCount(b.dataMap & (bit - 1));
        int len = b.keys.length;
        Object[] keys = new Object[len + 1];
        Object[] values = new Object[len + 1];
        System.arraycopy(b.keys, 0, keys, 0, i);
        System.arraycopy(b.values, 0, values, 0, i);
        keys[i] = key;
        values[i] = value;
        System.arraycopy(b.keys, i, keys, i + 1, len - i);
        System.arraycopy(b.values, i, values, i + 1, len - i);
        return new Bitmap(b.dataMap | bit, b.nodeMap, keys, values, b.nodes);
    }

    private static Bitmap dataToNode(Bitmap b, int bit, int dataIdx, Node child) {
        int len = b.keys.length;
        Object[] keys = new Object[len - 1];
        Object[] values = new Object[len - 1];
        System.arraycopy(b.keys, 0, keys, 0, dataIdx);
        System.arraycopy(b.values, 0, values, 0, dataIdx);
        System.arraycopy(b.keys, dataIdx + 1, keys, dataIdx, len - dataIdx - 1);
        System.arraycopy(b.values, dataIdx + 1, values, dataIdx, len - dataIdx - 1);

        int ni = Integer.bitCount(b.nodeMap & (bit - 1));
        Node[] nodes = new Node[b.nodes.length + 1];
        System.arraycopy(b.nodes, 0, nodes, 0, ni);
        nodes[ni] = child;
        System.arraycopy(b.nodes, ni, nodes, ni + 1, b.nodes.length - ni);

        return new Bitmap(b.dataMap ^ bit, b.nodeMap | bit, keys, values, nodes);
    }

    // ───────────────────────────── remove ────────────────────────────

    @Override
    public MapStorage remove(Object key) {
        boolean[] gone = new boolean[1];
        Node newRoot = delete(root, key, TrieHash.mix32(key), 0, gone);
        if (!gone[0]) return this;
        if (size - 1 == 0) return EmptyMapStorage.INSTANCE;
        return new TrieMapStorage(newRoot, size - 1);
    }

    private static Node delete(Node n, Object key, int hash, int shift, boolean[] gone) {
        if (n instanceof Collision c) {
            if (c.hash != hash) return c;
            for (int i = 0; i < c.keys.length; i++) {
                if (Objects.equals(c.keys[i], key)) {
                    gone[0] = true;
                    int len = c.keys.length;
                    Object[] keys = new Object[len - 1];
                    Object[] values = new Object[len - 1];
                    System.arraycopy(c.keys, 0, keys, 0, i);
                    System.arraycopy(c.values, 0, values, 0, i);
                    System.arraycopy(c.keys, i + 1, keys, i, len - i - 1);
                    System.arraycopy(c.values, i + 1, values, i, len - i - 1);
                    return new Collision(c.hash, keys, values);
                }
            }
            return c;
        }

        Bitmap b = (Bitmap) n;
        int bit = 1 << ((hash >>> shift) & MASK);

        if ((b.dataMap & bit) != 0) {
            int i = Integer.bitCount(b.dataMap & (bit - 1));
            if (!Objects.equals(b.keys[i], key)) return b;
            gone[0] = true;
            return withoutData(b, bit, i);
        }

        if ((b.nodeMap & bit) != 0) {
            int i = Integer.bitCount(b.nodeMap & (bit - 1));
            Node was = b.nodes[i];
            Node now = delete(was, key, hash, shift + BITS, gone);
            if (now == was) return b;
            // Canonicalise: a child holding exactly one entry moves back up, so
            // removal cannot leave chains of one-entry nodes behind.
            int only = soleEntryIndex(now);
            if (only >= 0) {
                Object k = now instanceof Bitmap nb ? nb.keys[only] : ((Collision) now).keys[only];
                Object v = now instanceof Bitmap nb2 ? nb2.values[only] : ((Collision) now).values[only];
                return nodeToData(b, bit, i, k, v);
            }
            Node[] nodes = b.nodes.clone();
            nodes[i] = now;
            return new Bitmap(b.dataMap, b.nodeMap, b.keys, b.values, nodes);
        }

        return b;
    }

    /** 0 if the node holds exactly one entry and no children, else -1. */
    private static int soleEntryIndex(Node n) {
        if (n instanceof Collision c) return c.keys.length == 1 ? 0 : -1;
        Bitmap b = (Bitmap) n;
        return b.nodeMap == 0 && Integer.bitCount(b.dataMap) == 1 ? 0 : -1;
    }

    private static Bitmap withoutData(Bitmap b, int bit, int i) {
        int len = b.keys.length;
        Object[] keys = new Object[len - 1];
        Object[] values = new Object[len - 1];
        System.arraycopy(b.keys, 0, keys, 0, i);
        System.arraycopy(b.values, 0, values, 0, i);
        System.arraycopy(b.keys, i + 1, keys, i, len - i - 1);
        System.arraycopy(b.values, i + 1, values, i, len - i - 1);
        return new Bitmap(b.dataMap ^ bit, b.nodeMap, keys, values, b.nodes);
    }

    private static Bitmap nodeToData(Bitmap b, int bit, int nodeIdx, Object key, Object value) {
        Node[] nodes = new Node[b.nodes.length - 1];
        System.arraycopy(b.nodes, 0, nodes, 0, nodeIdx);
        System.arraycopy(b.nodes, nodeIdx + 1, nodes, nodeIdx, b.nodes.length - nodeIdx - 1);

        int i = Integer.bitCount(b.dataMap & (bit - 1));
        int len = b.keys.length;
        Object[] keys = new Object[len + 1];
        Object[] values = new Object[len + 1];
        System.arraycopy(b.keys, 0, keys, 0, i);
        System.arraycopy(b.values, 0, values, 0, i);
        keys[i] = key;
        values[i] = value;
        System.arraycopy(b.keys, i, keys, i + 1, len - i);
        System.arraycopy(b.values, i, values, i + 1, len - i);

        return new Bitmap(b.dataMap | bit, b.nodeMap ^ bit, keys, values, nodes);
    }

    // ──────────────────────────── iteration ──────────────────────────

    @Override
    public Object[] keys() {
        Object[] out = new Object[size];
        collect(root, out, new int[1], true);
        return out;
    }

    @Override
    public Object[] values() {
        Object[] out = new Object[size];
        collect(root, out, new int[1], false);
        return out;
    }

    private static void collect(Node n, Object[] out, int[] at, boolean wantKeys) {
        if (n instanceof Collision c) {
            Object[] from = wantKeys ? c.keys : c.values;
            for (Object o : from) out[at[0]++] = o;
            return;
        }
        Bitmap b = (Bitmap) n;
        Object[] from = wantKeys ? b.keys : b.values;
        for (Object o : from) out[at[0]++] = o;
        for (Node child : b.nodes) collect(child, out, at, wantKeys);
    }

    @Override
    public void forEach(java.util.function.BiConsumer<Object, Object> consumer) {
        walk(root, consumer);
    }

    private static void walk(Node n, java.util.function.BiConsumer<Object, Object> consumer) {
        if (n instanceof Collision c) {
            for (int i = 0; i < c.keys.length; i++) consumer.accept(c.keys[i], c.values[i]);
            return;
        }
        Bitmap b = (Bitmap) n;
        for (int i = 0; i < b.keys.length; i++) consumer.accept(b.keys[i], b.values[i]);
        for (Node child : b.nodes) walk(child, consumer);
    }
}
