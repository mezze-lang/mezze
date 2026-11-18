package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.truffleir.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * C1 of continuations-and-swappable-async: every call node carries whether its
 * callee MAY SUSPEND, decided from the callee's solved effect row. This is what
 * lets the lowering put the propagation check only where it can matter.
 *
 * The rule, asserted on the IR itself:
 *  - a pure callee: `false`
 *  - a callee whose row names an effect: `true`
 *  - a callee annotated `<*>` (handles its own effects): `false` — nothing can
 *    suspend outward through it
 *  - a callback parameter (open row after BUG 51): `true`
 */
class CallMaySuspendIRTest {

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
    }

    private fun lower(src: String): List<FunctionIR> {
        val r = TypeChecker().typeCheck(MezzeParser().parseParsedModule(src.trimIndent(), "p.mz"), Paths.get("."), true, null)
        return r.loweredFunctions
    }

    private fun collect(node: NodeIR, out: MutableList<NodeIR>) {
        out.add(node)
        node.forEachChildIR { collect(it, out) }
    }

    private val program = """
        effect Tick where { tick: {} -> Int <Tick>, }
        impl Tick for T1 where {
          initialize_scope = {} -> {},
          finalize_scope = {} -> {},
          tick = {} -> 1,
        }
        let pure_fn = { x } -> if x > 100 then x else pure_fn { x = x + 1 }   ## recursive: never inlined
        ## a `let` keeps this out of the trivial-wrapper inliner, which now inlines a bare
        ## effect-op forwarder (`{} -> Tick.tick {}`) at its call sites
        let eff_fn = {} -> do
          let t = Tick.tick {}
          t + 0
        let handled: {} -> Int <*> = {} -> perform T1 in Tick.tick {}
        let caller = {} -> do
          let a = pure_fn { x = 1 }
          let b = eff_fn {}
          let c = handled {}
          a + b + c
        let hof = { f } -> f {}
        let main = {} -> {}
    """

    @Test
    fun `direct calls carry the callee's row verdict`() {
        val fns = lower(program)
        val nameOf = fns.associate { it.hash to it.name }
        val caller = fns.first { it.name == "caller" && it.sourceFile == "p.mz" }
        val nodes = mutableListOf<NodeIR>(); collect(caller.body, nodes)
        val verdicts = nodes.filterIsInstance<CallDirectIR>()
            .mapNotNull { c -> nameOf[c.targetHash]?.let { it to c.maySuspend } }
            .toMap()
        assertEquals(false, verdicts["pure_fn"], "pure callee: $verdicts")
        assertEquals(true, verdicts["eff_fn"], "effectful callee: $verdicts")
        assertEquals(false, verdicts["handled"], "a <*> callee handles its own effects: $verdicts")
    }

    @Test
    fun `a callback parameter is a suspendable call`() {
        val fns = lower(program)
        val hof = fns.first { it.name == "hof" && it.sourceFile == "p.mz" }
        val nodes = mutableListOf<NodeIR>(); collect(hof.body, nodes)
        val indirect = nodes.filter { it is CallIndirectIR || it is CallIndirectScalarIR || it is CallLocalIR }
        assertTrue(indirect.isNotEmpty(), "hof must call its parameter: ${hof.body::class.simpleName}")
        val flags = indirect.map {
            when (it) {
                is CallIndirectIR -> it.maySuspend
                is CallIndirectScalarIR -> it.maySuspend
                is CallLocalIR -> it.maySuspend
                else -> false
            }
        }
        assertTrue(flags.all { it }, "an unannotated callback's row is open, so the call may suspend: $flags")
    }
}
