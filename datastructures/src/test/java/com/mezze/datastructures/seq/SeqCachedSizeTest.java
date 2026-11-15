package com.mezze.datastructures.seq;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link MezzeSeq#size()} reads a FIELD, so every operation that produces a seq
 * has to produce the right one.
 *
 * <p>WHY IT IS A FIELD. `size` is the most-called operation any collection has,
 * and `storage.size()` is an interface call over the Long/Double/Boolean/Object
 * storages that is MEGAMORPHIC process-wide — it does not devirtualise however
 * monomorphic a given call site is. MEASURED on `bench/cases/allocsurface`,
 * n=2e7, min of 3, interleaved, both arms built by the dev launcher and the
 * extracted `MezzeSeq.class` confirmed to differ:
 *
 * <pre>
 *   row                     delegating   cached
 *     seq::size              2 169 ps      660   3.29x
 *     bytes::size (control)    634         652   0.97x
 *     map::size   (control)    662         597   1.11x
 *     seq::get    (control)  3 775       3 798   0.99x
 *     counted loop             216         228   0.95x
 * </pre>
 *
 * `seq::size` lands on `bytes::size` and `map::size`, which is where it should
 * have been: all three now have a dedicated operation, and the ~1 500 ps that
 * separated this one from them was the interface call and nothing else.
 *
 * <p>WHAT THIS TEST IS FOR. Caching is sound only because nothing ever changes
 * a seq's size in place: `MezzeSeq` is final with one private constructor,
 * `storage` is final, and every storage's own length is final — an append
 * returns a NEW seq over a new storage, which is what BUG 38 made structural
 * throughout. A storage that grew in place would make the cache WRONG rather
 * than slow, and wrong silently: the seq would simply report a stale count. So
 * every size-changing operation is checked here, on each storage kind, rather
 * than trusting that the constructor is the only place a size is set.
 */
public class SeqCachedSizeTest {

    @Test
    public void everyLongSeqOperationReportsItsOwnSize() {
        MezzeSeq<Long> xs = MezzeSeq.of(1L, 2L, 3L);
        assertEquals(3, xs.size());
        assertEquals(4, xs.append(4L).size());
        assertEquals(4, xs.prepend(0L).size());
        assertEquals(3, xs.update(1, 9L).size());
        assertEquals(2, xs.remove(0).size());
        assertEquals(4, xs.insert(1, 9L).size());
        assertEquals(6, xs.concat(MezzeSeq.of(7L, 8L, 9L)).size());
        assertEquals(2, xs.drop(1).size());
        assertEquals(1, xs.take(1).size());
        assertEquals(3, xs.reverse().size());
        // The original is untouched by all of it.
        assertEquals(3, xs.size());
    }

    @Test
    public void theEmptyAndSingletonCases() {
        assertEquals(0, MezzeSeq.empty().size());
        assertEquals(1, MezzeSeq.of(42L).size());
        assertEquals(1, MezzeSeq.<Long>empty().append(1L).size());
        assertEquals(0, MezzeSeq.of(1L).remove(0).size());
    }

    @Test
    public void theOtherStorageKinds() {
        MezzeSeq<String> objs = MezzeSeq.of("a", "b");
        assertEquals(2, objs.size());
        assertEquals(3, objs.append("c").size());

        MezzeSeq<Double> ds = MezzeSeq.of(1.5, 2.5);
        assertEquals(2, ds.size());
        assertEquals(3, ds.append(3.5).size());

        MezzeSeq<Boolean> bs = MezzeSeq.of(true, false, true);
        assertEquals(3, bs.size());
        assertEquals(2, bs.remove(0).size());
    }

    /** A lazily-represented range never materialises its array; its size is
     *  still its length and not its capacity. */
    @Test
    public void aLazyRangeKnowsItsSize() {
        MezzeSeq<Long> r = MezzeSeq.range(0, 1000);
        assertEquals(1000, r.size());
        assertEquals(999, r.drop(1).size());
        assertEquals(1001, r.append(1000L).size());
    }

    /** `size` and `isEmpty` must not disagree, since only one is cached. */
    @Test
    public void sizeAndIsEmptyAgree() {
        assertEquals(true, MezzeSeq.empty().isEmpty());
        assertEquals(false, MezzeSeq.of(1L).isEmpty());
        MezzeSeq<Long> one = MezzeSeq.of(1L);
        assertEquals(one.size() == 0, one.isEmpty());
        MezzeSeq<Long> none = one.remove(0);
        assertEquals(none.size() == 0, none.isEmpty());
    }
}
