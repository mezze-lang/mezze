package com.mezze.language.typechecker

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * The two emit monoliths do not grow back.
 *
 * `TypedASTCompiler` was 5,580 lines and `TypedAstBuilder` 3,107 — not because
 * either does two jobs, but because everything they touch had nowhere else to
 * live. Four subjects were moved out (dictionary/evidence and pattern lowering
 * from the compiler; call emission and pattern emission from the builder), each
 * as a pure move: indentation and receiver changed, no body edited.
 *
 * A size bound is a crude instrument and it is deliberately crude. It cannot say
 * whether a file is well organised; it can only say that the thing which made
 * these two hard to work in — new code defaulting to the biggest file because
 * that is where the neighbours are — is being noticed. Adding 200 lines to
 * `TypedASTCompiler` should be a decision, not a default.
 *
 * Raising a bound is allowed and is not a defeat. Do it in the same commit as
 * the growth, with the reason, the way the placeholder and elaboration ratchets
 * are moved.
 */
class EmitFileSizeRatchetTest {

    private fun lines(relative: String): Int {
        // RepoRoot, not the mutable `mezze.project.root` system property — see its
        // doc: other tests in this JVM overwrite that, which made this ratchet
        // order-dependent and, for one sibling, silently skip.
        val f = RepoRoot.file(relative)
        assertTrue(f.exists(), "expected $relative at ${f.absolutePath}")
        return f.readLines().size
    }

    @Test
    fun `the emit monoliths stay split`() {
        for ((path, bound) in BOUNDS) {
            val actual = lines(path)
            assertTrue(
                actual <= bound,
                "$path is $actual lines, over its bound of $bound. Either move the new " +
                    "code to the file whose subject it shares — the siblings listed in " +
                    "this test are what came out already — or raise the bound here, in " +
                    "this commit, with the reason.",
            )
        }
    }

    @Test
    fun `the files that were split out still exist`() {
        // Guards the guard. Deleting a sibling and folding it back would satisfy
        // nothing above — the bounds would simply start failing later, once the
        // re-merged file grew — so the split itself is asserted directly.
        for (path in SPLIT_OUT) {
            assertTrue(lines(path) > 50, "$path should hold a real subject, not a stub")
        }
    }

    private companion object {
        const val C = "typechecker/src/main/kotlin/com/mezze/language/typechecker/compiler/"
        const val E = "typechecker/src/main/kotlin/com/mezze/language/typechecker/emit/"

        /** Measured after the split, with a little slack so an ordinary fix does
         *  not have to touch this file. Lower is better; never raise silently. */
        val BOUNDS = mapOf(
            // S-3: +26 for the receiver-less branch of the instance-head binding — a
            // receiver-less ability op binds its impl's target pattern against the
            // type it PRODUCES, where a receiver-taking one binds against `it`.
            // Without it every parameterised container reached a dictionary slot
            // nobody filled. The surrounding block was extracted to
            // `structuralConstraintTypesFor` in the same commit, which shortens
            // `compileCall` but not the file — so the bound moves rather than the
            // code, which is what this test asks for when the code belongs here.
            // +23 for refusing the by-name declaration-index CALL route when
            // the frame CAPTURES that name. A captured function has no local
            // slot, so the search answered for a different declaration sharing
            // the name and a user's `let handler` was called in place of the
            // handler a route was given. The guard has to sit at that lookup —
            // putting it in `resolveLocalSlot` instead broke 37 recursive tests,
            // which is written down beside it — so the bound moves, not the code.
            // +26 for a non-recursive `let`'s RHS seeing the OUTER binding. Three
            // small edits — remember what the name meant, hide the new slot while
            // the RHS compiles, restore it after — and a comment that is most of
            // the diff, because the RECURSIVE case needs the opposite and the next
            // reader has to know which way round it goes. `let n = n` over a
            // top-level `n` used to die with `FrameSlotTypeException … got
            // Illegal`. The edit belongs where the slot is allocated, so the bound
            // moves rather than the code.
            // +38 for BUG 38: when several impls of a class-param ability share a
            // receiver, dispatch asks which one's OPERATION accepts the arguments
            // supplied, instead of hunting an argument field named literally `r` —
            // the stdlib's spelling, which is why `datetime` worked and a user
            // ability silently ran the wrong impl's body. Most of it is the
            // `VarType` reasoning and the class-arg-tuple fallback that
            // `datetime`'s unannotated params still need; the decision has to sit
            // beside `receiverMatches`, where the candidates are, so the bound
            // moves rather than the code.
            // +37 for BUG 39, the other end of the same arrow: a class param in
            // the RETURN (`Into 'b for 'a`) is reachable from no argument, so
            // dispatch also asks which candidate RETURNS what the call produces.
            // The stdlib's `Into` was broken for every target but the
            // first-registered one — `let f: Float = (5).into {}` returned the
            // STRING "5" — so this is not an edge the file can be spared. Same
            // placement argument as BUG 38 above.
            // +50 for BUG 40, the third of the class-param family:
            // `tryResolveAbilityOpByTuple`, which asks which impl a whole
            // class-arg TUPLE names — the dict path had only a list of candidate
            // receivers to try one at a time, which is the right question for a
            // single-parameter ability and the wrong one here. Most of the growth
            // is the comment on the ambiguous tail of `tryResolveAbilityOp`,
            // recording that making it DECLINE instead of guessing was tried and
            // reverted (it broke `x + 100` on an unsolved pattern binding). That
            // note is worth its lines: it is the obvious next fix and it does not
            // work.
            // +2 for asking [entryOpDictIsHeld] before recovering a var-typed
            // `from_json` receiver from the pinned decoder's input type. The
            // reasoning moved OUT, to `DictEvidence.kt` beside the dictionary
            // lookup it is about; what stayed is the two lines of condition that
            // have to sit in this expression, since it is the recovery being
            // guarded. The decoder's input is always `Json` and the entry impl's
            // receiver is `Str` when the text is parsed first, so the recovery was
            // running `impl FromJson 'a for Json` on a `Str` and the raw JSON text
            // reached `decode_record_field`.
            // +4 more for extending that same predicate to the CONCRETE-receiver
            // end (`viaEnclosingDecodeDict`), which had tested membership of the
            // derived-decoder table and so never fired for an entry op. The
            // reasoning is in `DictEvidence.kt` with the lookup; what stayed here
            // is the condition itself, which is the expression being changed.
            // +31 for `collectionKindOf`, which decides WHICH COLLECTION a native
            // call returns from the call's solved type and hands it to the IR.
            // `ChainCanonicalizer` used to ask the native's NAME instead —
            // `startsWith("__native_seq_")` taken to mean "produces a Seq" — and
            // a native's module is not its return type: the six `seq::to_*`
            // conversions hand back a different collection, so a fused loop read
            // a MezzeSet with SeqSize and `seq::size { xs = set::to_seq { s =
            // seq::to_set { xs } } }` CRASHED. It sits directly beside
            // `ordKindOf`, the function whose pattern it copies — the same
            // "decision carried from where the type was known" — and moving it
            // away from its one sibling would cost more than the bound.
            // +7 for running RecordScalarizer in the lowering pipeline: one call
            // plus the comment recording that it only pays with boxing
            // elimination on AND an inlined producer, because it was written,
            // measured flat and reverted twice before that order was known.
            // The pass itself lives in ast/truffleir; what is here is the
            // pipeline, which is this file's subject.
            // +4 for carrying `ownerKind` and `opKind` on each native call — two
            // lines per construction, and there are two constructions. Same
            // reason as `collectionKindOf` above and the same one line of
            // history: a `__native_…` name is a LABEL, so it is resolved once,
            // here, where the binding is in hand. `ChainCanonicalizer` and
            // `ChainLetInliner` asked the label 44 more times downstream —
            // `isAnyElementCollection(name, OP_MAP)` builds six native names and
            // compares all six, so one visit could construct and compare nearly
            // two hundred strings to learn what this resolution already knew.
            // They now read these two fields; the spelling predicates in
            // `NativeHofNames` are private, which is the compiler's proof that
            // nothing downstream consults the name any more.
            // +10 to run BytesMapArithRewriter: the loop, and the note that
            // `bytes::reverse` is the measured ceiling. A one-operation mapper
            // over bytes was costing a guest call PER BYTE — 5 136 ps/byte
            // against reverse's 298, which is the same shape without a
            // callback. The pass and all of its reasoning live in
            // ast/truffleir beside FoldToSumRewriter, whose idea it reuses;
            // what is here is the pass SEQUENCE, which is this file's subject.
            // +6 for routing `seq::unique` to its evidence form — one entry in
            // `evidenceNativeForm` and the comment saying why it belongs there.
            // `unique` buckets to find duplicates, so it needs the element's
            // own hashing for the same reason `set::from_seq` does: bucketing
            // by the compiler's hashing while the author's `Eq` decides
            // equality keeps BOTH of two values the author calls equal. The
            // table IS this file's subject — it is the one place that says
            // which natives take evidence — so the bound moves rather than the
            // code.
            // +68 for `ordNativeCall`: upgrading `seq::sort` to the
            // comparator-free native where the element was PROVED directly
            // comparable. 10.5x self-A/B, and the gate is the whole point — a
            // newtype over Int with a reversed `Ord` sorts as a long and
            // silently contradicts its author (known-bugs.md BUG 10), proven by
            // removing the gate and watching three tests fail. It reads as the
            // sibling of `evidenceNativeCall` directly above it: the same shape
            // of decision, a table keyed on the native's NAME, taken where the
            // solved type is in hand and nowhere downstream. Splitting the two
            // apart would scatter one family, so the bound moves.
            // +16 to bring `list` into the two tables `seq` already uses.
            // `list::sort` carried `seq::sort`'s defect VERBATIM — the same
            // `sort_by` route and the same dead `__native_list_sort` whose
            // comparator ended `return 0` — so it joins `ordNativeFastForm`
            // (2.35x), and `seqElementType` now admits List beside Seq. And
            // `list::unique`, which had NO constraint at all and ignored a
            // hand-written `Eq`, joins `evidenceNativeForm` so the two
            // `unique`s finally agree about what a duplicate is. Both tables
            // ARE this file's subject — they are the one place that says which
            // natives take evidence and which may drop their comparator — so
            // the bound moves rather than the code.
            // +9 for `sorted_set::from_seq`. known-bugs.md BUG 44: the
            // type-based fast paths in `MezzeSortedSet.compareElements`
            // answered before consulting the element's own `Ord`, so a
            // descending newtype was merged as ascending and `union`
            // CONCATENATED — size 6 while `contains` denied three elements.
            // Gating those paths is correct but cost 8.2x on union, because a
            // Mezze set always supplies a comparator. Dropping it where the
            // element is `OrdKind.DIRECT` restores the fast path, and that
            // decision belongs to `ordNativeFastForm` — this file's subject is
            // the one place that says which natives may shed an operand.
            // +22 for the `sorted_set` merges, known-bugs.md BUG 44's second
            // half. A SortedSet cannot be trusted to carry its own order —
            // `empty {}` threads no comparator while `add` places elements by
            // the author's `Ord` via a guest search, so the array is right and
            // the label is wrong. The merges therefore TAKE the order, and
            // drop it where the element is `OrdKind.DIRECT`, which is what
            // keeps the native fast paths and holds the cost to ~10% instead
            // of the 8.2x that gating alone measured. Four entries plus the
            // note recording why `map` and `filter_map` are deliberately
            // ABSENT (BUG 46: their target type is not readable here in
            // argument position, so upgrading would be guessing). The table is
            // this file's subject — the one place that says which natives may
            // shed an operand — so the bound moves rather than the code.
            // +3 for `from_set`, `from_list` and `set::to_sorted_set`, the last
            // three of the seven places a hand-written `Ord` was being
            // overridden today. Each now threads its comparator and drops it
            // where the element is directly comparable, which is this table's
            // whole job.
            // +6 for `sorted_map::map_keys` and `invert`, the last two of the
            // nine places a hand-written `Ord` was being overridden today.
            // Their natives set the order to null outright, with a comment
            // calling it "primitive-only (tracked with the sorted-container
            // gap)" — the gap was this. Threading the comparator is only
            // affordable because this table can drop it again where the key is
            // directly comparable, which is the table's whole purpose.
            // +4 for the four conversions INTO a sorted container —
            // `seq::to_sorted_set`/`to_sorted_map` and the deque pair — which
            // built their result with no comparator and so ignored a
            // hand-written `Ord`. Each now threads one and drops it where the
            // element is directly comparable, which is this table's purpose.
            // +8 for `nonemptylist::sort`, the THIRD collection to carry
            // `seq::sort`'s defect: one more row in the table and
            // `NonEmptyList` added to `seqElementType`, which had known only
            // SEQ/LIST/SORTED_SET and so would have DECLINED the rewrite
            // silently — the fix would have looked applied and measured
            // unchanged.
            // +4 for `set::map`'s evidence form. The table is where a native
            // that must consult the ELEMENT's instance is named, and
            // `set::map` belonged in it: it collapses by the TARGET's equality,
            // its result's elements being `'b`, and structurally it kept two
            // values a hand-written `Eq` calls one element. Same table, same
            // reason as `set::from_seq` four rows above.
            // +4 for `set::filter_map` and `map::map_keys`, the two siblings of
            // `set::map`: each produces a container whose elements or KEYS are
            // the target type and collapsed by representation without the
            // instance. Found by asking which other operations have that shape.
            // +14 for the SEVEN conversions into a hash container — the four
            // `to_set` and three `to_map` spellings. They create the container,
            // so they must bucket by the element's or key's own instance, and
            // declared nothing. Their SORTED twins were fixed the same day
            // under BUG 45 and these were missed; the table is where that
            // obligation is recorded.
            // +3 for `deque::unique`, `map::invert` and `sorted_map::to_map`
            // (BUG 50), the three remaining operations that CREATE a hash
            // container while declaring no evidence. Found by one grep over
            // every module's signatures rather than by a ratio, and each was
            // the sibling tell: `unique` was the only such op in `deque` with
            // no `with`, `to_map` sits a line under `from_map` which always
            // carried its `Ord`, and `invert`'s own comment promised behaviour
            // it never reached. `invert`'s pair is on the VALUE, because
            // inverting keys the result by the old values — the one constraint
            // in the family not on the obvious type parameter, which is why
            // the table entry is the record of it.
            // MERGE integration/all-three: the type-soundness branch bounded this
            // at 4511 and the perf branch at 4910, and the merged file is 5042 —
            // both sides grew it, in different places, and both sets of reasons are
            // kept above. The number is the measured merged size, not a slackening.
            C + "TypedASTCompiler.kt" to 5042,   // was 5,580 before the split
            // +42 for `contextRowWithBodyAssocTypes`: an INLINE lambda was stamped
            // with the row from the pushed CONTEXT, so a callback's associated
            // type was the CALLEE's variable rather than what the body threw, and
            // no call site could resolve a constraint on it. Filling it from the
            // lambda's own recorded row belongs where the lambda is stamped, so
            // the bound moves rather than the code.
            // +2 on the rebase onto main: main grew this file too, and our +42
            // landed on top of it. The bound tracks the file, not either change.
            // +1: `capturesIntoResult` now reads the perform body's row from the
            // side table recorded at constraint generation instead of re-deriving
            // it from a post-solve type that had already been stripped. The
            // decision belongs where TypedPerform is stamped — same reason as the
            // lambda row above — and none of the siblings covers perform emit.
            // 2395 -> 2400: BUG 43's receiverless-op diagnostic. It said "nothing
            // here determines that type" while the type sat in the call's argument
            // (`receive { target = Red, … }`), so the reader argues with it rather
            // than acting on it; the message now says what is true — with no `it`
            // an argument is not a receiver, so a parameter mentioning the type
            // chooses no impl. The file was EXACTLY at its bound, so the honest
            // choice was this or a worse message, and the subject belongs here.
            E + "TypedAstBuilder.kt" to 2400,    // was 3,107
        )

        val SPLIT_OUT = listOf(
            C + "DictEvidence.kt",        // which calls need evidence, and which slot
            C + "PatternCompilation.kt",  // match/pattern lowering to IR
            E + "CallEmit.kt",            // the three call shapes
            E + "PatternEmit.kt",         // match-case binding types
        )
    }
}
