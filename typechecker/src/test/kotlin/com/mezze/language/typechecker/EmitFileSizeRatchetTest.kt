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
            C + "TypedASTCompiler.kt" to 4733,   // +9 to run VariantMatchFolder a SECOND time after LetCopyPropagation and propagate its output: the first fold cannot see a variant BOUND TO A NAME, because propagation is what makes the construction adjacent to its match. The ordering decision belongs to this file, which owns the pass sequence, and the measurement (5.9x one field, 10.4x two, allocation gone) lives in LetCopyPropagation. +67 for the per-call-site arrows the dictionary path and mono both read, and for `receiverlessOpRef`: a receiver-less op has no dict slot inside a mono SPEC, where its output is concrete and it resolves outright. That belongs in DictEvidence.kt beside `abilityOpDictSlotOrNull`, and it stays here only because `elementFirst`, `refToDictedOp` and `specTypeArgs` are private to this class — widening three members to move one function is worse coupling than the bound. +69 for referencing a generic function's SPECIALIZATION instead of wrapping it in a dictionary-carrying closure — it belongs beside `tryWrapDictFunction`, whose decision it pre-empts, and reads as its last resort rather than a pass of its own; was 5,580; +151 for devirtualising a local-helper call, inlining a generic body into a concrete caller, and running LetCopyPropagation + ParamScalarizer + VariantMatchFolder; +4 to wire ChainLetInliner (ast/), -4 when eight kill switches went and their comments with them; +7 to wire FoldToSumRewriter (ast/), the pass and its reasoning living there
            // +42 for `contextRowWithBodyAssocTypes`: an INLINE lambda was stamped
            // with the row from the pushed CONTEXT, so a callback's associated
            // type was the CALLEE's variable rather than what the body threw, and
            // no call site could resolve a constraint on it. Filling it from the
            // lambda's own recorded row belongs where the lambda is stamped, so
            // the bound moves rather than the code.
            // +2 on the rebase onto main: main grew this file too, and our +42
            // landed on top of it. The bound tracks the file, not either change.
            E + "TypedAstBuilder.kt" to 2394,    // was 3,107
        )

        val SPLIT_OUT = listOf(
            C + "DictEvidence.kt",        // which calls need evidence, and which slot
            C + "PatternCompilation.kt",  // match/pattern lowering to IR
            E + "CallEmit.kt",            // the three call shapes
            E + "PatternEmit.kt",         // match-case binding types
        )
    }
}
