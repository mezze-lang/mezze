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

    /**
     * STRUCTURAL UNION — merges two tries, sharing every subtree only one side
     * has, and rehashing nothing.
     *
     * <p>`MezzeSet.addAll` collects both sides into arrays and calls
     * {@code fromElements}, which hashes every element again and throws both
     * input tries away. MEASURED on the real structure at a thousand elements a
     * side: the rebuild is 84% of that work for disjoint inputs and 72% for
     * identical ones, against a walk of both sides at 6 790 ps/element. This
     * replaces the rebuild.
     *
     * <p>No collision case, for the reason the class comment already gives:
     * {@link TrieHash#mix64} is a bijection, so distinct elements part before
     * the bits run out.
     *
     * @return the union, or {@code null} when the two tries are structurally
     *         identical and {@code a} is already the answer
     */
    static LongTrieSetStorage unionWith(LongTrieSetStorage a, LongTrieSetStorage b) {
        int[] grew = new int[1];
        Node root = unionNodes(a.root, b.root, 0, grew);
        if (root == a.root) return a;
        return new LongTrieSetStorage(root, a.size + grew[0]);
    }

    private static Node unionNodes(Node a, Node b, int shift, int[] grew) {
        // The same subtree on both sides: nothing to merge and nothing to copy.
        // This is what makes a union of overlapping sets cheap rather than
        // merely correct.
        if (a == b) return a;

        int dataOnly = 0, nodeOnly = 0;
        // Walk the bits present in either side. A position can hold data on one
        // side and a subtree on the other, so the four cases are resolved per
        // bit before any array is sized.
        int bits = a.dataMap | a.nodeMap | b.dataMap | b.nodeMap;

        // First pass: decide, for each bit, whether the result holds data or a
        // subtree there. Only a data/data pair with EQUAL elements stays data
        // when both sides had data; a differing pair becomes a subtree.
        int rest = bits;
        while (rest != 0) {
            int bit = rest & -rest;
            rest ^= bit;
            boolean aData = (a.dataMap & bit) != 0, aNode = (a.nodeMap & bit) != 0;
            boolean bData = (b.dataMap & bit) != 0, bNode = (b.nodeMap & bit) != 0;
            if (aData && bData) {
                if (a.elems[idx(a.dataMap, bit)] == b.elems[idx(b.dataMap, bit)]) {
                    dataOnly |= bit;
                } else {
                    nodeOnly |= bit;
                }
            } else if (aNode || bNode) {
                nodeOnly |= bit;
            } else {
                dataOnly |= bit;
            }
        }

        long[] elems = dataOnly == 0 ? NO_ELEMS : new long[Integer.bitCount(dataOnly)];
        Node[] nodes = nodeOnly == 0 ? NO_NODES : new Node[Integer.bitCount(nodeOnly)];
        int ei = 0, ni = 0;

        rest = bits;
        while (rest != 0) {
            int bit = rest & -rest;
            rest ^= bit;
            boolean aData = (a.dataMap & bit) != 0, aNode = (a.nodeMap & bit) != 0;
            boolean bData = (b.dataMap & bit) != 0, bNode = (b.nodeMap & bit) != 0;

            if (aData && bData) {
                long ea = a.elems[idx(a.dataMap, bit)];
                long eb = b.elems[idx(b.dataMap, bit)];
                if (ea == eb) {
                    elems[ei++] = ea;
                } else {
                    grew[0]++;
                    nodes[ni++] = merge(ea, TrieHash.mix64(ea), eb, TrieHash.mix64(eb),
                        shift + BITS);
                }
            } else if (aData && bNode) {
                // A's element goes into B's subtree. B's elements are all new
                // relative to A, so they are counted here: everything B's
                // subtree holds is added, minus nothing, and A's one element is
                // already in `a.size`.
                Node sub = b.nodes[idx(b.nodeMap, bit)];
                long ea = a.elems[idx(a.dataMap, bit)];
                int[] subGrew = new int[1];
                Node merged = insert(sub, ea, TrieHash.mix64(ea), shift + BITS, subGrew);
                // `insert` counts A's element as new if B lacked it; what this
                // needs is how many of B's elements were new to A, which is
                // every element of B's subtree except A's own when B had it.
                grew[0] += countElems(sub) - (subGrew[0] == 0 ? 1 : 0);
                nodes[ni++] = merged;
            } else if (aNode && bData) {
                Node sub = a.nodes[idx(a.nodeMap, bit)];
                long eb = b.elems[idx(b.dataMap, bit)];
                nodes[ni++] = insert(sub, eb, TrieHash.mix64(eb), shift + BITS, grew);
            } else if (aNode && bNode) {
                nodes[ni++] = unionNodes(a.nodes[idx(a.nodeMap, bit)],
                    b.nodes[idx(b.nodeMap, bit)], shift + BITS, grew);
            } else if (aData) {
                elems[ei++] = a.elems[idx(a.dataMap, bit)];
            } else if (aNode) {
                // A SHARED SUBTREE: B has nothing here, so A's node is the
                // answer by reference. No copy, no hashing, no allocation.
                nodes[ni++] = a.nodes[idx(a.nodeMap, bit)];
            } else if (bData) {
                grew[0]++;
                elems[ei++] = b.elems[idx(b.dataMap, bit)];
            } else {
                Node sub = b.nodes[idx(b.nodeMap, bit)];
                grew[0] += countElems(sub);
                nodes[ni++] = sub;
            }
        }

        return new Node(dataOnly, nodeOnly, elems, nodes);
    }

    /** The compacted index of [bit] within [map]. */
    private static int idx(int map, int bit) {
        return Integer.bitCount(map & (bit - 1));
    }

    /** Elements in a subtree. Only called for a subtree being transplanted. */
    private static int countElems(Node n) {
        int total = n.elems.length;
        for (Node child : n.nodes) total += countElems(child);
        return total;
    }

    /**
     * STRUCTURAL SYMMETRIC DIFFERENCE — elements in exactly one side, sharing
     * every subtree only one side has and rehashing nothing.
     *
     * <p>`SetNatives.symmetricDifference` walked both sides probing the other
     * and rebuilt the survivors through {@code fromElements}. At 26 464 686 ps
     * for two half-overlapping thousand-element sets that was the most
     * expensive set row left, and roughly 56% of it was the rebuild.
     *
     * <p>Unlike {@link #unionWith} this CANCELS, so a position can contribute
     * nothing and a subtree can come back empty. Each position's contribution
     * is therefore computed before the bitmaps are built, so no bit is ever
     * recorded for an empty child.
     *
     * @return the symmetric difference, empty when the two tries cancel out
     */
    static LongTrieSetStorage symmetricDifferenceWith(LongTrieSetStorage a,
                                                      LongTrieSetStorage b) {
        int[] count = new int[1];
        Node root = symDiffNodes(a.root, b.root, 0, count);
        if (root == null) return new LongTrieSetStorage();
        return new LongTrieSetStorage(root, count[0]);
    }

    /**
     * The symmetric difference of two subtrees, or null when it is empty.
     *
     * <p>Null rather than an empty Node so a caller never records a bit for a
     * child holding nothing — the hazard this operation has and the union does
     * not.
     */
    private static Node symDiffNodes(Node a, Node b, int shift, int[] count) {
        // Identical subtrees cancel entirely. The opposite of the union's
        // short-circuit, and the reason a self-symmetric-difference is empty.
        if (a == b) return null;

        int bits = a.dataMap | a.nodeMap | b.dataMap | b.nodeMap;
        int upper = Integer.bitCount(bits);

        // Contributions first, bitmaps after — see the class note above.
        long[] elems = new long[upper];
        Node[] kids = new Node[upper];
        int[] elemBit = new int[upper];
        int[] kidBit = new int[upper];
        int ne = 0, nk = 0;

        int rest = bits;
        while (rest != 0) {
            int bit = rest & -rest;
            rest ^= bit;
            boolean aData = (a.dataMap & bit) != 0, aNode = (a.nodeMap & bit) != 0;
            boolean bData = (b.dataMap & bit) != 0, bNode = (b.nodeMap & bit) != 0;

            if (aData && bData) {
                long ea = a.elems[idx(a.dataMap, bit)];
                long eb = b.elems[idx(b.dataMap, bit)];
                if (ea == eb) {
                    continue;                       // cancels
                }
                // Both survive, and they collided on this bit, so they go one
                // level down together — a SUBTREE here, not data.
                kidBit[nk] = bit;
                kids[nk++] = merge(ea, TrieHash.mix64(ea), eb, TrieHash.mix64(eb),
                    shift + BITS);
                count[0] += 2;
                continue;
            }

            if (aData && bNode) {
                Node sub = b.nodes[idx(b.nodeMap, bit)];
                long ea = a.elems[idx(a.dataMap, bit)];
                Node toggled = toggle(sub, ea, TrieHash.mix64(ea), shift + BITS, count);
                if (toggled != null) { kidBit[nk] = bit; kids[nk++] = toggled; }
                continue;
            }

            if (aNode && bData) {
                Node sub = a.nodes[idx(a.nodeMap, bit)];
                long eb = b.elems[idx(b.dataMap, bit)];
                Node toggled = toggle(sub, eb, TrieHash.mix64(eb), shift + BITS, count);
                if (toggled != null) { kidBit[nk] = bit; kids[nk++] = toggled; }
                continue;
            }

            if (aNode && bNode) {
                Node sub = symDiffNodes(a.nodes[idx(a.nodeMap, bit)],
                    b.nodes[idx(b.nodeMap, bit)], shift + BITS, count);
                if (sub != null) { kidBit[nk] = bit; kids[nk++] = sub; }
                continue;
            }

            // One side only: everything here is in exactly one side.
            if (aData) {
                elemBit[ne] = bit;
                elems[ne++] = a.elems[idx(a.dataMap, bit)];
                count[0]++;
            } else if (bData) {
                elemBit[ne] = bit;
                elems[ne++] = b.elems[idx(b.dataMap, bit)];
                count[0]++;
            } else if (aNode) {
                Node sub = a.nodes[idx(a.nodeMap, bit)];
                kidBit[nk] = bit;
                kids[nk++] = sub;                   // SHARED BY REFERENCE
                count[0] += countElems(sub);
            } else {
                Node sub = b.nodes[idx(b.nodeMap, bit)];
                kidBit[nk] = bit;
                kids[nk++] = sub;                   // SHARED BY REFERENCE
                count[0] += countElems(sub);
            }
        }

        if (ne == 0 && nk == 0) return null;

        int dataMap = 0, nodeMap = 0;
        for (int i = 0; i < ne; i++) dataMap |= elemBit[i];
        for (int i = 0; i < nk; i++) nodeMap |= kidBit[i];

        long[] outElems = ne == 0 ? NO_ELEMS : java.util.Arrays.copyOf(elems, ne);
        Node[] outKids = nk == 0 ? NO_NODES : java.util.Arrays.copyOf(kids, nk);
        return new Node(dataMap, nodeMap, outElems, outKids);
    }

    /**
     * [n] with [elem] removed if present and added if absent, or null when the
     * result is empty — the "in exactly one side" rule for a single element
     * against a whole subtree.
     */
    private static Node toggle(Node n, long elem, long hash, int shift, int[] count) {
        if (containsIn(n, elem, hash, shift)) {
            Node without = delete(n, elem, hash, shift);
            if (without != null) count[0] += countElems(without);
            return without;
        }
        int[] grew = new int[1];
        Node with = insert(n, elem, hash, shift, grew);
        count[0] += countElems(with);
        return with;
    }

    /** Whether [elem] is in this subtree. */
    private static boolean containsIn(Node n, long elem, long hash, int shift) {
        for (;;) {
            int bit = 1 << (int) ((hash >>> shift) & MASK);
            if ((n.dataMap & bit) != 0) return n.elems[idx(n.dataMap, bit)] == elem;
            if ((n.nodeMap & bit) == 0) return false;
            n = n.nodes[idx(n.nodeMap, bit)];
            shift += BITS;
        }
    }

    /**
     * [n] without [elem], or null when that empties the subtree. Rebuilt rather
     * than routed through the storage's own `remove`, which works from a root
     * and a size this does not have here.
     */
    private static Node delete(Node n, long elem, long hash, int shift) {
        int bit = 1 << (int) ((hash >>> shift) & MASK);

        if ((n.dataMap & bit) != 0) {
            int i = idx(n.dataMap, bit);
            if (n.elems[i] != elem) return n;             // not here
            if (n.elems.length == 1 && n.nodes.length == 0) return null;
            long[] elems = new long[n.elems.length - 1];
            System.arraycopy(n.elems, 0, elems, 0, i);
            System.arraycopy(n.elems, i + 1, elems, i, n.elems.length - i - 1);
            return new Node(n.dataMap ^ bit, n.nodeMap, elems, n.nodes);
        }

        if ((n.nodeMap & bit) == 0) return n;             // not here

        int i = idx(n.nodeMap, bit);
        Node now = delete(n.nodes[i], elem, hash, shift + BITS);
        if (now == n.nodes[i]) return n;
        if (now == null) {
            if (n.elems.length == 0 && n.nodes.length == 1) return null;
            Node[] nodes = new Node[n.nodes.length - 1];
            System.arraycopy(n.nodes, 0, nodes, 0, i);
            System.arraycopy(n.nodes, i + 1, nodes, i, n.nodes.length - i - 1);
            return new Node(n.dataMap, n.nodeMap ^ bit, n.elems, nodes);
        }
        Node[] nodes = n.nodes.clone();
        nodes[i] = now;
        return new Node(n.dataMap, n.nodeMap, n.elems, nodes);
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

    /**
     * ONE WALK, NO ARRAY. The boxing per element stays — a `Consumer<Object>`
     * takes an Object — but the n-element array and the second traversal that
     * `SetStorage.forEach`'s default pays for do not.
     */
    @Override
    public void forEach(java.util.function.Consumer<Object> consumer) {
        walkAll(root, consumer);
    }

    private static void walkAll(Node n, java.util.function.Consumer<Object> consumer) {
        for (long e : n.elems) consumer.accept(Long.valueOf(e));
        for (Node child : n.nodes) walkAll(child, consumer);
    }

    @Override
    public boolean forEachUntil(java.util.function.Predicate<Object> p) {
        return walkUntilAll(root, p);
    }

    /** As {@link #walkAll}, stopping the moment the predicate says so. */
    private static boolean walkUntilAll(Node n, java.util.function.Predicate<Object> p) {
        for (long e : n.elems) {
            if (!p.test(Long.valueOf(e))) return false;
        }
        for (Node child : n.nodes) {
            if (!walkUntilAll(child, p)) return false;
        }
        return true;
    }

    /** Promote to {@link TrieSetStorage} when a non-Long element arrives. */
    private SetStorage transitionToGeneric() {
        SetStorage out = new TrieSetStorage();
        for (Object e : toArray()) {
            out = out.add(e);
        }
        return out;
    }
    // ──────────────────────────── bulk build ─────────────────────────

    /**
     * BUILDS A TRIE IN PLACE, for the paths that rebuild a whole set.
     *
     * <p>The map's {@code LongTrieMapStorage.Builder} documents the reasoning
     * in full; it is the same here with no values to carry. {@link #addLong} is
     * persistent, so it allocates a node at every level of the path it touches
     * — right for an update whose base survives, wasted for a rebuild that
     * discards the base on the next element.
     *
     * <p>A builder node indexes its 32 slots DIRECTLY rather than keeping them
     * compact, so adding an element is one array store and a bitmap OR.
     * {@link #build()} compacts each node once into the immutable shape the
     * readers expect.
     *
     * <p>Single-threaded and single-use, and for a REBUILD rather than for
     * {@code add}: the slot arrays make a builder node wider than the node it
     * becomes, and that width dies with the builder.
     */
    public static final class Builder {

        /** A node under construction: slot-indexed, so no slot ever moves. */
        private static final class Mut {
            int dataMap;
            int nodeMap;
            final long[] elems = new long[1 << BITS];
            /** Allocated on the first child, because most nodes never get one. */
            Mut[] kids;
        }

        private final Mut root = new Mut();
        private int size;

        /** Adds an element. Already present is a no-op, as the adds were. */
        public void add(long elem) {
            long hash = TrieHash.mix64(elem);
            Mut n = root;
            for (int shift = 0; ; shift += BITS) {
                int slot = (int) ((hash >>> shift) & MASK);
                int bit = 1 << slot;

                if ((n.nodeMap & bit) != 0) {
                    n = n.kids[slot];
                    continue;
                }

                if ((n.dataMap & bit) == 0) {
                    n.dataMap |= bit;
                    n.elems[slot] = elem;
                    size++;
                    return;
                }

                long had = n.elems[slot];
                if (had == elem) {
                    return;
                }

                // Two distinct elements want one slot: push the sitting one a
                // level down and go round again. `mix64` is a bijection, so
                // they must part within the 64 bits.
                if (shift + BITS >= 64) {
                    throw new IllegalStateException(
                        "two distinct long elements with an identical 64-bit mix — "
                            + "TrieHash.mix64() is not a bijection");
                }
                Mut kid = new Mut();
                int hs = (int) ((TrieHash.mix64(had) >>> (shift + BITS)) & MASK);
                kid.dataMap = 1 << hs;
                kid.elems[hs] = had;

                if (n.kids == null) {
                    n.kids = new Mut[1 << BITS];
                }
                n.dataMap ^= bit;
                n.nodeMap |= bit;
                n.kids[slot] = kid;
                n.elems[slot] = 0L;
                n = kid;
            }
        }

        /** The finished storage. The builder is spent afterwards. */
        public SetStorage build() {
            if (size == 0) {
                return new LongTrieSetStorage();
            }
            return new LongTrieSetStorage(freeze(root), size);
        }

        /** Compacts a slot-indexed node into bitmap order, once. */
        private static Node freeze(Mut m) {
            int nd = Integer.bitCount(m.dataMap);
            int nn = Integer.bitCount(m.nodeMap);
            long[] elems = nd == 0 ? NO_ELEMS : new long[nd];
            Node[] nodes = nn == 0 ? NO_NODES : new Node[nn];

            int j = 0;
            for (int dm = m.dataMap; dm != 0; dm &= dm - 1) {
                elems[j++] = m.elems[Integer.numberOfTrailingZeros(dm)];
            }
            int k = 0;
            for (int nm = m.nodeMap; nm != 0; nm &= nm - 1) {
                nodes[k++] = freeze(m.kids[Integer.numberOfTrailingZeros(nm)]);
            }
            return new Node(m.dataMap, m.nodeMap, elems, nodes);
        }
    }
}
