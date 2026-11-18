package com.mezze.interpreter.nodes.continuation;

/**
 * A minimal open-addressing {@code long → V} map for the machine's promise table: a
 * {@code HashMap<Long, V>} boxed every key above 127 on every lookup (BUG 77's profile showed
 * {@code Long <- HashMap.getNode} as the top allocation of spawn+await). Single-threaded
 * (the machine's), tombstone-free: removal re-inserts the run that follows the hole.
 */
final class LongObjectMap<V> {
    private long[] keys = new long[64];
    private Object[] vals = new Object[64];
    private boolean[] used = new boolean[64];
    private int size;

    int size() { return size; }

    @SuppressWarnings("unchecked")
    V get(long k) {
        int m = keys.length - 1, i = mix(k) & m;
        while (used[i]) { if (keys[i] == k) return (V) vals[i]; i = (i + 1) & m; }
        return null;
    }

    void put(long k, V v) {
        if ((size + 1) * 4 > keys.length * 3) grow();
        int m = keys.length - 1, i = mix(k) & m;
        while (used[i]) { if (keys[i] == k) { vals[i] = v; return; } i = (i + 1) & m; }
        used[i] = true; keys[i] = k; vals[i] = v; size++;
    }

    @SuppressWarnings("unchecked")
    V remove(long k) {
        int m = keys.length - 1, i = mix(k) & m;
        while (used[i]) {
            if (keys[i] == k) {
                V old = (V) vals[i];
                used[i] = false; vals[i] = null; size--;
                // re-insert the run after the hole so probing stays correct
                int j = (i + 1) & m;
                while (used[j]) {
                    long kk = keys[j]; Object vv = vals[j];
                    used[j] = false; vals[j] = null; size--;
                    put(kk, (V) vv);
                    j = (j + 1) & m;
                }
                return old;
            }
            i = (i + 1) & m;
        }
        return null;
    }

    private void grow() {
        long[] ok = keys; Object[] ov = vals; boolean[] ou = used;
        keys = new long[ok.length * 2]; vals = new Object[ok.length * 2]; used = new boolean[ok.length * 2]; size = 0;
        for (int i = 0; i < ok.length; i++) if (ou[i]) put(ok[i], (V) ov[i]);
    }

    private static int mix(long k) { k ^= k >>> 33; k *= 0xff51afd7ed558ccdL; k ^= k >>> 33; return (int) k; }
}
