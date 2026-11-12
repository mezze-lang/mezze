package com.mezze.datastructures.set;

import com.mezze.datastructures.util.TrieHash;

import java.util.Objects;

/**
 * A persistent hash-array-mapped trie for arbitrary elements — the general
 * sister of {@link LongTrieSetStorage}, replacing the open-addressed
 * generic, String-keyed and Double-keyed tables (all now deleted).
 *
 * <p>Same reason as everywhere else on this surface: those tables mutated their
 * table while holding an ownership token and handed the token on, so a
 * derivation was observable in the set it came from (BUG 38). Every field here
 * is final and an add copies one root-to-leaf path.
 *
 * <p>COLLISION NODES ARE NEEDED, unlike in the Long version: {@code hashCode()}
 * is 32 bits and not injective, so two unequal elements genuinely can agree on
 * all of it and must then share a node.
 *
 * <p>Element semantics match the storages replaced — {@code hashCode()} through
 * the murmur3 32-bit finalizer, {@code Objects.equals} to compare, nulls
 * permitted. An element type with its own {@code Eq}/{@code Hash} goes to
 * {@code EvidenceSetStorage} and never arrives here.
 */
public final class TrieSetStorage implements SetStorage {

    private static final int BITS = 5;
    private static final int MASK = 31;
    private static final int HASH_BITS = 32;

    private static final Object[] NOTHING = new Object[0];
    private static final Node[] NO_NODES = new Node[0];
    private static final Bitmap EMPTY_ROOT = new Bitmap(0, 0, NOTHING, NO_NODES);

    private final Node root;
    private final int size;

    public TrieSetStorage() {
        this(EMPTY_ROOT, 0);
    }

    private TrieSetStorage(Node root, int size) {
        this.root = root;
        this.size = size;
    }

    sealed interface Node permits Bitmap, Collision {}

    static final class Bitmap implements Node {
        final int dataMap;
        final int nodeMap;
        final Object[] elems;
        final Node[] nodes;

        Bitmap(int dataMap, int nodeMap, Object[] elems, Node[] nodes) {
            this.dataMap = dataMap;
            this.nodeMap = nodeMap;
            this.elems = elems;
            this.nodes = nodes;
        }
    }

    /** Elements agreeing on all 32 hash bits but not equal. */
    static final class Collision implements Node {
        final int hash;
        final Object[] elems;

        Collision(int hash, Object[] elems) {
            this.hash = hash;
            this.elems = elems;
        }
    }

    // ───────────────────────────── reads ─────────────────────────────

    @Override
    public int size() {
        return size;
    }

    @Override
    public boolean contains(Object elem) {
        int hash = TrieHash.mix32(elem);
        Node n = root;
        for (int shift = 0; ; shift += BITS) {
            if (n instanceof Collision c) {
                if (c.hash != hash) return false;
                for (Object e : c.elems) if (Objects.equals(e, elem)) return true;
                return false;
            }
            Bitmap b = (Bitmap) n;
            int bit = 1 << ((hash >>> shift) & MASK);
            if ((b.dataMap & bit) != 0) {
                return Objects.equals(b.elems[Integer.bitCount(b.dataMap & (bit - 1))], elem);
            }
            if ((b.nodeMap & bit) == 0) return false;
            n = b.nodes[Integer.bitCount(b.nodeMap & (bit - 1))];
        }
    }

    @Override
    public byte getStrategyTag() {
        return TAG_HASH;
    }

    // ───────────────────────────── add ───────────────────────────────

    @Override
    public SetStorage add(Object elem) {
        int[] grew = new int[1];
        Node newRoot = insert(root, elem, TrieHash.mix32(elem), 0, grew);
        if (newRoot == root) return this;
        return new TrieSetStorage(newRoot, size + grew[0]);
    }

    private static Node insert(Node n, Object elem, int hash, int shift, int[] grew) {
        if (n instanceof Collision c) {
            if (c.hash == hash) {
                for (Object e : c.elems) if (Objects.equals(e, elem)) return c;
                grew[0]++;
                Object[] elems = java.util.Arrays.copyOf(c.elems, c.elems.length + 1);
                elems[c.elems.length] = elem;
                return new Collision(c.hash, elems);
            }
            grew[0]++;
            return splitCollision(c, elem, hash, shift);
        }

        Bitmap b = (Bitmap) n;
        int bit = 1 << ((hash >>> shift) & MASK);

        if ((b.dataMap & bit) != 0) {
            int i = Integer.bitCount(b.dataMap & (bit - 1));
            Object had = b.elems[i];
            if (Objects.equals(had, elem)) return b;
            grew[0]++;
            Node child = merge(had, TrieHash.mix32(had), elem, hash, shift + BITS);
            return dataToNode(b, bit, i, child);
        }

        if ((b.nodeMap & bit) != 0) {
            int i = Integer.bitCount(b.nodeMap & (bit - 1));
            Node was = b.nodes[i];
            Node now = insert(was, elem, hash, shift + BITS, grew);
            if (now == was) return b;
            Node[] nodes = b.nodes.clone();
            nodes[i] = now;
            return new Bitmap(b.dataMap, b.nodeMap, b.elems, nodes);
        }

        grew[0]++;
        return withData(b, bit, elem);
    }

    private static Node splitCollision(Collision c, Object elem, int hash, int shift) {
        int ci = (c.hash >>> shift) & MASK;
        int ei = (hash >>> shift) & MASK;
        if (ci != ei) {
            return new Bitmap(1 << ei, 1 << ci, new Object[]{elem}, new Node[]{c});
        }
        if (shift >= HASH_BITS) {
            throw new IllegalStateException("hash bits exhausted with differing hashes");
        }
        return new Bitmap(0, 1 << ci, NOTHING,
            new Node[]{splitCollision(c, elem, hash, shift + BITS)});
    }

    private static Node merge(Object e1, int h1, Object e2, int h2, int shift) {
        if (shift >= HASH_BITS) {
            return new Collision(h1, new Object[]{e1, e2});
        }
        int i1 = (h1 >>> shift) & MASK;
        int i2 = (h2 >>> shift) & MASK;
        if (i1 != i2) {
            int dataMap = (1 << i1) | (1 << i2);
            return i1 < i2
                ? new Bitmap(dataMap, 0, new Object[]{e1, e2}, NO_NODES)
                : new Bitmap(dataMap, 0, new Object[]{e2, e1}, NO_NODES);
        }
        return new Bitmap(0, 1 << i1, NOTHING, new Node[]{merge(e1, h1, e2, h2, shift + BITS)});
    }

    private static Bitmap withData(Bitmap b, int bit, Object elem) {
        int i = Integer.bitCount(b.dataMap & (bit - 1));
        int len = b.elems.length;
        Object[] elems = new Object[len + 1];
        System.arraycopy(b.elems, 0, elems, 0, i);
        elems[i] = elem;
        System.arraycopy(b.elems, i, elems, i + 1, len - i);
        return new Bitmap(b.dataMap | bit, b.nodeMap, elems, b.nodes);
    }

    private static Bitmap dataToNode(Bitmap b, int bit, int dataIdx, Node child) {
        int len = b.elems.length;
        Object[] elems = new Object[len - 1];
        System.arraycopy(b.elems, 0, elems, 0, dataIdx);
        System.arraycopy(b.elems, dataIdx + 1, elems, dataIdx, len - dataIdx - 1);

        int ni = Integer.bitCount(b.nodeMap & (bit - 1));
        Node[] nodes = new Node[b.nodes.length + 1];
        System.arraycopy(b.nodes, 0, nodes, 0, ni);
        nodes[ni] = child;
        System.arraycopy(b.nodes, ni, nodes, ni + 1, b.nodes.length - ni);

        return new Bitmap(b.dataMap ^ bit, b.nodeMap | bit, elems, nodes);
    }

    // ───────────────────────────── remove ───────────────────────────

    @Override
    public SetStorage remove(Object elem) {
        boolean[] gone = new boolean[1];
        Node newRoot = delete(root, elem, TrieHash.mix32(elem), 0, gone);
        if (!gone[0]) return this;
        if (size - 1 == 0) return EmptySetStorage.INSTANCE;
        return new TrieSetStorage(newRoot, size - 1);
    }

    private static Node delete(Node n, Object elem, int hash, int shift, boolean[] gone) {
        if (n instanceof Collision c) {
            if (c.hash != hash) return c;
            for (int i = 0; i < c.elems.length; i++) {
                if (Objects.equals(c.elems[i], elem)) {
                    gone[0] = true;
                    int len = c.elems.length;
                    Object[] elems = new Object[len - 1];
                    System.arraycopy(c.elems, 0, elems, 0, i);
                    System.arraycopy(c.elems, i + 1, elems, i, len - i - 1);
                    return new Collision(c.hash, elems);
                }
            }
            return c;
        }

        Bitmap b = (Bitmap) n;
        int bit = 1 << ((hash >>> shift) & MASK);

        if ((b.dataMap & bit) != 0) {
            int i = Integer.bitCount(b.dataMap & (bit - 1));
            if (!Objects.equals(b.elems[i], elem)) return b;
            gone[0] = true;
            return withoutData(b, bit, i);
        }

        if ((b.nodeMap & bit) != 0) {
            int i = Integer.bitCount(b.nodeMap & (bit - 1));
            Node was = b.nodes[i];
            Node now = delete(was, elem, hash, shift + BITS, gone);
            if (now == was) return b;
            if (holdsOneElement(now)) {
                Object only = now instanceof Bitmap nb ? nb.elems[0] : ((Collision) now).elems[0];
                return nodeToData(b, bit, i, only);
            }
            Node[] nodes = b.nodes.clone();
            nodes[i] = now;
            return new Bitmap(b.dataMap, b.nodeMap, b.elems, nodes);
        }

        return b;
    }

    private static boolean holdsOneElement(Node n) {
        if (n instanceof Collision c) return c.elems.length == 1;
        Bitmap b = (Bitmap) n;
        return b.nodeMap == 0 && Integer.bitCount(b.dataMap) == 1;
    }

    private static Bitmap withoutData(Bitmap b, int bit, int i) {
        int len = b.elems.length;
        Object[] elems = new Object[len - 1];
        System.arraycopy(b.elems, 0, elems, 0, i);
        System.arraycopy(b.elems, i + 1, elems, i, len - i - 1);
        return new Bitmap(b.dataMap ^ bit, b.nodeMap, elems, b.nodes);
    }

    private static Bitmap nodeToData(Bitmap b, int bit, int nodeIdx, Object elem) {
        Node[] nodes = new Node[b.nodes.length - 1];
        System.arraycopy(b.nodes, 0, nodes, 0, nodeIdx);
        System.arraycopy(b.nodes, nodeIdx + 1, nodes, nodeIdx, b.nodes.length - nodeIdx - 1);

        int i = Integer.bitCount(b.dataMap & (bit - 1));
        int len = b.elems.length;
        Object[] elems = new Object[len + 1];
        System.arraycopy(b.elems, 0, elems, 0, i);
        elems[i] = elem;
        System.arraycopy(b.elems, i, elems, i + 1, len - i);

        return new Bitmap(b.dataMap | bit, b.nodeMap ^ bit, elems, nodes);
    }

    // ──────────────────────────── iteration ─────────────────────────

    @Override
    public Object[] toArray() {
        Object[] out = new Object[size];
        collect(root, out, new int[1]);
        return out;
    }

    private static void collect(Node n, Object[] out, int[] at) {
        if (n instanceof Collision c) {
            for (Object e : c.elems) out[at[0]++] = e;
            return;
        }
        Bitmap b = (Bitmap) n;
        for (Object e : b.elems) out[at[0]++] = e;
        for (Node child : b.nodes) collect(child, out, at);
    }
    // ──────────────────────────── bulk build ─────────────────────────

    /**
     * BUILDS A TRIE IN PLACE, for the paths that rebuild a whole set.
     *
     * <p>The long-keyed set's {@code Builder} documents why. This one carries
     * the two things it does not need: elements are compared with
     * {@code Objects.equals}, and {@code mix32} is NOT a bijection, so two
     * distinct elements can agree on all 32 bits and must end up in a
     * {@link Collision}. When that happens the slot stops being a builder node
     * and holds an immutable {@code Node}, and later elements reaching it go
     * through the ordinary persistent {@link #insert}.
     */
    public static final class Builder {

        /** A node under construction: slot-indexed, so no slot ever moves. */
        private static final class Mut {
            int dataMap;
            int nodeMap;
            final Object[] elems = new Object[1 << BITS];
            /**
             * A child is either another {@code Mut} or a frozen {@code Node}
             * standing for a collision subtree. Allocated on the first child.
             */
            Object[] kids;
        }

        private final Mut root = new Mut();
        private int size;

        /** Adds an element. Already present is a no-op, as the adds were. */
        public void add(Object elem) {
            int hash = TrieHash.mix32(elem);
            Mut n = root;
            for (int shift = 0; ; shift += BITS) {
                int slot = (hash >>> shift) & MASK;
                int bit = 1 << slot;

                if ((n.nodeMap & bit) != 0) {
                    Object kid = n.kids[slot];
                    if (kid instanceof Mut mut) {
                        n = mut;
                        continue;
                    }
                    // A frozen collision subtree: the persistent path owns it.
                    // The shift passed below is unobservable — a Collision is
                    // made only once the 32 bits are spent, so every element
                    // that can arrive here shares its full hash.
                    int[] grew = new int[1];
                    n.kids[slot] = insert((Node) kid, elem, hash, shift + BITS, grew);
                    size += grew[0];
                    return;
                }

                if ((n.dataMap & bit) == 0) {
                    n.dataMap |= bit;
                    n.elems[slot] = elem;
                    size++;
                    return;
                }

                Object had = n.elems[slot];
                if (Objects.equals(had, elem)) {
                    return;
                }

                int hadHash = TrieHash.mix32(had);
                int down = shift + BITS;

                if (n.kids == null) {
                    n.kids = new Object[1 << BITS];
                }
                n.dataMap ^= bit;
                n.nodeMap |= bit;
                n.elems[slot] = null;

                if (down >= HASH_BITS) {
                    // The hash is spent and the elements differ: one Collision
                    // node holds both, exactly as `merge` would build it.
                    n.kids[slot] = merge(had, hadHash, elem, hash, down);
                    size++;
                    return;
                }

                Mut kid = new Mut();
                int hs = (hadHash >>> down) & MASK;
                kid.dataMap = 1 << hs;
                kid.elems[hs] = had;
                n.kids[slot] = kid;
                n = kid;

                // Moving the sitting element down is not a size change: the new
                // one is counted by whichever branch finally places it.
            }
        }

        /** The finished storage. The builder is spent afterwards. */
        public SetStorage build() {
            if (size == 0) {
                return new TrieSetStorage();
            }
            return new TrieSetStorage(freeze(root), size);
        }

        /** Compacts a slot-indexed node into bitmap order, once. */
        private static Node freeze(Mut m) {
            int nd = Integer.bitCount(m.dataMap);
            int nn = Integer.bitCount(m.nodeMap);
            Object[] elems = nd == 0 ? NOTHING : new Object[nd];
            Node[] nodes = nn == 0 ? NO_NODES : new Node[nn];

            int j = 0;
            for (int dm = m.dataMap; dm != 0; dm &= dm - 1) {
                elems[j++] = m.elems[Integer.numberOfTrailingZeros(dm)];
            }
            int k = 0;
            for (int nm = m.nodeMap; nm != 0; nm &= nm - 1) {
                Object kid = m.kids[Integer.numberOfTrailingZeros(nm)];
                nodes[k++] = kid instanceof Mut mut ? freeze(mut) : (Node) kid;
            }
            return new Bitmap(m.dataMap, m.nodeMap, elems, nodes);
        }
    }
}
