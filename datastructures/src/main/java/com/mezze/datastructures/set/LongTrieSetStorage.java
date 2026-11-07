package com.mezze.datastructures.set;

import com.mezze.datastructures.util.TrieHash;

/**
 * A persistent hash-array-mapped trie of primitive {@code long} elements — the
 * Set sister of {@link com.mezze.datastructures.map.LongTrieMapStorage}, and
 * for the same reason.
 *
 * <p>{@code LongHashSetStorage} reached O(1) adds by MUTATING its table while
 * it still held an ownership token, so a derivation was observable in the set
 * it came from: a base reported {@code contains} true for an element only the
 * derived set had, while its own {@code size} disagreed. That is BUG 38. The
 * token establishes "only one storage may WRITE this table"; what a persistent
 * set needs is "no storage may OBSERVE a write it did not make", and keeping
 * shared mutable state cannot give it — see the map's class comment and
 * known-bugs BUG 38 for the family of fixes that was measured and discarded.
 *
 * <p>Structural sharing gives it directly: nothing is written after
 * construction, an update copies only one root-to-leaf path, and no version
 * references another.
 *
 * <p>NO COLLISION NODES: {@link #mix} is a bijection on 64 bits, so distinct
 * elements cannot share a full hash and must part before the bits run out.
 *
 * <p>No sentinels either, so {@code Long.MIN_VALUE} and {@code MIN_VALUE+1} are
 * ordinary elements rather than values that forced the set into boxed storage.
 */
public final class LongTrieSetStorage implements SetStorage {

    private static final int BITS = 5;
    private static final int MASK = 31;

    private static final long[] NO_ELEMS = new long[0];
    private static final Node[] NO_NODES = new Node[0];
    private static final Node EMPTY_ROOT = new Node(0, 0, NO_ELEMS, NO_NODES);

    private final Node root;
    private final int size;

    public LongTrieSetStorage() {
        this(EMPTY_ROOT, 0);
    }

    private LongTrieSetStorage(Node root, int size) {
        this.root = root;
        this.size = size;
    }

    /** One trie level; every field final, which is the whole point. */
    static final class Node {
        final int dataMap;
        final int nodeMap;
        final long[] elems;
        final Node[] nodes;

        Node(int dataMap, int nodeMap, long[] elems, Node[] nodes) {
            this.dataMap = dataMap;
            this.nodeMap = nodeMap;
            this.elems = elems;
            this.nodes = nodes;
        }
    }

    // ───────────────────────────── reads ─────────────────────────────

    @Override
    public int size() {
        return size;
    }

    public boolean containsLong(long elem) {
        Node n = root;
        long hash = TrieHash.mix64(elem);
        for (int shift = 0; ; shift += BITS) {
            int bit = 1 << (int) ((hash >>> shift) & MASK);
            if ((n.dataMap & bit) != 0) {
                return n.elems[Integer.bitCount(n.dataMap & (bit - 1))] == elem;
            }
            if ((n.nodeMap & bit) == 0) return false;
            n = n.nodes[Integer.bitCount(n.nodeMap & (bit - 1))];
        }
    }

    @Override
    public boolean contains(Object elem) {
        return elem instanceof Long l && containsLong(l);
    }

    @Override
    public byte getStrategyTag() {
        return TAG_HASH;
    }

    /** Sum of the elements, without boxing. */
    public long sumLongs() {
        return sum(root);
    }

    private static long sum(Node n) {
        long total = 0;
        for (long e : n.elems) total += e;
        for (Node child : n.nodes) total += sum(child);
        return total;
    }

    // ───────────────────────────── add ───────────────────────────────

    @Override
    public SetStorage add(Object elem) {
        if (elem instanceof Long l) return addLong(l);
        return transitionToGeneric().add(elem);
    }

    public SetStorage addLong(long elem) {
        int[] grew = new int[1];
        Node newRoot = insert(root, elem, TrieHash.mix64(elem), 0, grew);
        if (newRoot == root) return this;
        return new LongTrieSetStorage(newRoot, size + grew[0]);
    }

    private static Node insert(Node n, long elem, long hash, int shift, int[] grew) {
        int bit = 1 << (int) ((hash >>> shift) & MASK);

        if ((n.dataMap & bit) != 0) {
            int i = Integer.bitCount(n.dataMap & (bit - 1));
            long had = n.elems[i];
            if (had == elem) return n;          // already present — share
            grew[0]++;
            Node child = merge(had, TrieHash.mix64(had), elem, hash, shift + BITS);
            return dataToNode(n, bit, i, child);
        }

        if ((n.nodeMap & bit) != 0) {
            int i = Integer.bitCount(n.nodeMap & (bit - 1));
            Node was = n.nodes[i];
            Node now = insert(was, elem, hash, shift + BITS, grew);
            if (now == was) return n;
            Node[] nodes = n.nodes.clone();
            nodes[i] = now;
            return new Node(n.dataMap, n.nodeMap, n.elems, nodes);
        }

        grew[0]++;
        return withData(n, bit, elem);
    }

    private static Node merge(long e1, long h1, long e2, long h2, int shift) {
        int i1 = (int) ((h1 >>> shift) & MASK);
        int i2 = (int) ((h2 >>> shift) & MASK);
        if (i1 != i2) {
            int dataMap = (1 << i1) | (1 << i2);
            return i1 < i2
                ? new Node(dataMap, 0, new long[]{e1, e2}, NO_NODES)
                : new Node(dataMap, 0, new long[]{e2, e1}, NO_NODES);
        }
        if (shift >= 64) {
            throw new IllegalStateException(
                "two distinct long elements with an identical 64-bit mix — TrieHash.mix64() is not a bijection");
        }
        return new Node(0, 1 << i1, NO_ELEMS, new Node[]{merge(e1, h1, e2, h2, shift + BITS)});
    }

    private static Node withData(Node n, int bit, long elem) {
        int i = Integer.bitCount(n.dataMap & (bit - 1));
        int len = n.elems.length;
        long[] elems = new long[len + 1];
        System.arraycopy(n.elems, 0, elems, 0, i);
        elems[i] = elem;
        System.arraycopy(n.elems, i, elems, i + 1, len - i);
        return new Node(n.dataMap | bit, n.nodeMap, elems, n.nodes);
    }

    private static Node dataToNode(Node n, int bit, int dataIdx, Node child) {
        int len = n.elems.length;
        long[] elems = new long[len - 1];
        System.arraycopy(n.elems, 0, elems, 0, dataIdx);
        System.arraycopy(n.elems, dataIdx + 1, elems, dataIdx, len - dataIdx - 1);

        int ni = Integer.bitCount(n.nodeMap & (bit - 1));
        Node[] nodes = new Node[n.nodes.length + 1];
        System.arraycopy(n.nodes, 0, nodes, 0, ni);
        nodes[ni] = child;
        System.arraycopy(n.nodes, ni, nodes, ni + 1, n.nodes.length - ni);

        return new Node(n.dataMap ^ bit, n.nodeMap | bit, elems, nodes);
    }

    // ───────────────────────────── remove ───────────────────────────

    @Override
    public SetStorage remove(Object elem) {
        return elem instanceof Long l ? removeLong(l) : this;
    }

    public SetStorage removeLong(long elem) {
        boolean[] gone = new boolean[1];
        Node newRoot = delete(root, elem, TrieHash.mix64(elem), 0, gone);
        if (!gone[0]) return this;
        if (size - 1 == 0) return EmptySetStorage.INSTANCE;
        return new LongTrieSetStorage(newRoot, size - 1);
    }

    private static Node delete(Node n, long elem, long hash, int shift, boolean[] gone) {
        int bit = 1 << (int) ((hash >>> shift) & MASK);

        if ((n.dataMap & bit) != 0) {
            int i = Integer.bitCount(n.dataMap & (bit - 1));
            if (n.elems[i] != elem) return n;
            gone[0] = true;
            return withoutData(n, bit, i);
        }

        if ((n.nodeMap & bit) != 0) {
            int i = Integer.bitCount(n.nodeMap & (bit - 1));
            Node was = n.nodes[i];
            Node now = delete(was, elem, hash, shift + BITS, gone);
            if (now == was) return n;
            // Canonicalise: a child reduced to one element moves back up.
            if (now.nodeMap == 0 && Integer.bitCount(now.dataMap) == 1) {
                return nodeToData(n, bit, i, now.elems[0]);
            }
            Node[] nodes = n.nodes.clone();
            nodes[i] = now;
            return new Node(n.dataMap, n.nodeMap, n.elems, nodes);
        }

        return n;
    }

    private static Node withoutData(Node n, int bit, int i) {
        int len = n.elems.length;
        long[] elems = new long[len - 1];
        System.arraycopy(n.elems, 0, elems, 0, i);
        System.arraycopy(n.elems, i + 1, elems, i, len - i - 1);
        return new Node(n.dataMap ^ bit, n.nodeMap, elems, n.nodes);
    }

    private static Node nodeToData(Node n, int bit, int nodeIdx, long elem) {
        Node[] nodes = new Node[n.nodes.length - 1];
        System.arraycopy(n.nodes, 0, nodes, 0, nodeIdx);
        System.arraycopy(n.nodes, nodeIdx + 1, nodes, nodeIdx, n.nodes.length - nodeIdx - 1);

        int i = Integer.bitCount(n.dataMap & (bit - 1));
        int len = n.elems.length;
        long[] elems = new long[len + 1];
        System.arraycopy(n.elems, 0, elems, 0, i);
        elems[i] = elem;
        System.arraycopy(n.elems, i, elems, i + 1, len - i);

        return new Node(n.dataMap | bit, n.nodeMap ^ bit, elems, nodes);
    }

    // ──────────────────────────── iteration ─────────────────────────

    @Override
    public Object[] toArray() {
        Object[] out = new Object[size];
        collect(root, out, new int[1]);
        return out;
    }

    private static void collect(Node n, Object[] out, int[] at) {
        for (long e : n.elems) out[at[0]++] = Long.valueOf(e);
        for (Node child : n.nodes) collect(child, out, at);
    }

    /** Promote to {@link TrieSetStorage} when a non-Long element arrives. */
    private SetStorage transitionToGeneric() {
        SetStorage out = new TrieSetStorage();
        for (Object e : toArray()) {
            out = out.add(e);
        }
        return out;
    }
}
