package com.mezze.datastructures.util;

/**
 * The hash functions the persistent tries index by — one place, because four
 * tries need them and they must not drift apart.
 *
 * <p>Both are murmur3 finalizers. The 64-bit one matters more than it looks:
 * it is a BIJECTION, which is what lets the {@code long}-keyed tries do without
 * collision nodes entirely. Distinct keys cannot share a full hash, so two keys
 * travel together only while their hashes agree and must part before the 64
 * bits run out. The 32-bit one over {@code hashCode()} has no such property —
 * {@code hashCode} is not injective — so the general-key tries do carry
 * collision nodes.
 *
 * <p>Changing {@link #mix64} from a bijection would silently make those tries
 * wrong rather than slow, which is why it lives here with that written down
 * instead of being copied into each of them.
 */
public final class TrieHash {

    private TrieHash() {
    }

    /** murmur3's 64-bit finalizer — a bijection on 64 bits. */
    public static long mix64(long key) {
        long x = key;
        x ^= x >>> 33;
        x *= 0xff51afd7ed558ccdL;
        x ^= x >>> 33;
        x *= 0xc4ceb9fe1a85ec53L;
        x ^= x >>> 33;
        return x;
    }

    /**
     * murmur3's 32-bit finalizer over {@code hashCode()}, with null hashing to
     * 0 — the same spread the open-addressed tables these tries replaced
     * applied, so key distribution is unchanged.
     */
    public static int mix32(Object key) {
        int h = key == null ? 0 : key.hashCode();
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return h;
    }
}
