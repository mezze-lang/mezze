package com.mezze.language.typechecker.compiler

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.ModulePathConventions
import com.mezze.language.parsedast.Loc
import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.truffleir.BlockIR
import com.mezze.language.truffleir.CallNativeIR
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.truffleir.PerformOpIR
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.ast.prettyPrintType
import com.mezze.language.typedast.BindingRef
import com.mezze.language.typedast.Hash
import com.mezze.language.typedast.TypedCall
import com.mezze.language.typedast.TypedExpr
import com.mezze.language.typedast.TypedHandlerRef
import com.mezze.language.typedast.TypedModule
import com.mezze.language.typedast.TypedPerformOp
import com.mezze.language.typedast.forEachChild
import com.mezze.language.typedast.TypedRecord
import com.mezze.language.typedast.TypedRecordEntry
import com.mezze.language.typedast.TypedVar

/**
 * Continuations, until C4 (resumable natives) lands: a park inside a callback that a
 * NATIVE runs synchronously cannot be resumed — the native receives the callback's
 * continuation as if it were a value and the rest of the callback is lost. The
 * runtime reports that loudly ([TaskMachine.checkNoneSwallowed]); this is the
 * compile-time rule in front of it, and it is decided from TYPES, not names:
 *
 *  - the callee is a NATIVE WRAPPER — a function whose lowered body is a direct
 *    `__native_*` call (`seq::for_each`, `iter::map`, …);
 *  - the callee RUNS the callback during the call — its own row lists the
 *    callback's effects. `spawn_task { f }` does not (`-> Promise 'a`, a pure
 *    arrow): the callback runs later as a task, so it is never flagged;
 *  - the callback performs an effect that THIS module installs a cont-capable
 *    performer for (`EventLoop` for `Async`). Under `LoomAsync` the same program
 *    is fine, and says so by compiling.
 *
 * Conservative in one way: program-wide. A module that installs `EventLoop`
 * anywhere pays the rule everywhere; C4 deletes it.
 */
internal class ParkThroughNativeLedger {
    /** One op the callback may reach: `op == null` means "some op of this effect,
     *  which one is not visible from here" (a call into a function whose row names
     *  the effect but whose body is not a plain forwarder). */
    private class Reach(val effectHash: Hash, val effectName: String, val op: String?)
    private class Candidate(
        val loc: Loc, val callee: String, val field: String,
        val callbackType: MezzeType, val reaches: List<Reach>,
    )
    private class ContCapable(val performer: String, val loc: Loc, val contOps: Set<String>, val effectName: String)

    private val candidates = ArrayList<Candidate>()
    private val contCapable = HashMap<Hash, ContCapable>()
    /** Files of the modules being compiled that are NOT stdlib. A hazard inside the
     *  stdlib's own helpers is the stdlib's to fix (and the runtime detector's to
     *  catch), not a user's to be told about at a line they did not write. */
    var userFiles: Set<String> = emptySet()

    /** One compile call = one unit: a module's performers against its own call sites.
     *  The compiler instance is reused across modules, so the ledger resets per call;
     *  a hazard split across two user modules is the runtime detector's. */
    fun resetFor(module: TypedModule, isStdlibCompilation: Boolean) {
        candidates.clear(); contCapable.clear()
        // The primary module's call sites against its own performers. Its imports
        // arrive as `additionalModules` under FILE paths (not `std::`), so they are
        // not consulted here: their sites are theirs when they are primary.
        userFiles = if (isStdlibCompilation || ModulePathConventions.isStdlib(module.path)) emptySet()
            else module.decls.map { it.loc.file }.toSet()
    }

    fun noteContCapable(h: TypedHandlerRef) {
        contCapable.putIfAbsent(h.effectHash, ContCapable(h.performerName, h.loc, h.contOps, h.effectName))
    }

    /**
     * The callee's type here is its GENERIC signature, which is the better witness:
     * a function RUNS a callback during the call iff the callback parameter's row
     * variable is the callee's own — `for_each: { xs, f: { x } -> 'b <ρ> } -> {} <ρ>`.
     * `spawn_task: { f: {} -> 'a <ρ> } -> Promise 'a` does not share it: the callback
     * runs later, as a task.
     *
     * The callback's effects are read from its BODY, not its type: a lambda
     * literal's row lives on the enclosing function, so its own `FunctionType`
     * carries none. A direct op is a `TypedPerformOp`; a forwarder such as
     * `yield_now: {} -> {} <Async>` carries its declared row on the callee.
     */
    fun noteCall(expr: TypedCall, irOf: (Hash) -> FunctionIR?) {
        if (expr.loc.file !in userFiles) return
        val fn = expr.function as? TypedVar ?: return
        val hash = (fn.binding as? BindingRef.ByHash)?.hash ?: return
        val calleeType = fn.type as? MezzeType.FunctionType ?: return
        val calleeRow = calleeType.effects?.rowVar ?: return
        val params = calleeType.paramType as? MezzeType.RecordType ?: return
        val arg = expr.arg as? TypedRecord ?: return
        val synchronous = params.fields.filter { (_, t) ->
            t is MezzeType.FunctionType && t.effects?.rowVar == calleeRow
        }.keys
        if (synchronous.isEmpty()) return
        if (!isNativeWrapper(irOf(hash) ?: return)) return
        for (entry in arg.entries) {
            val field = entry as? TypedRecordEntry.Field ?: continue
            if (field.name !in synchronous) continue
            val cb = field.value.type as? MezzeType.FunctionType ?: continue
            val reaches = ArrayList<Reach>()
            collectReaches(field.value, irOf, reaches)
            if (reaches.isEmpty()) continue
            candidates.add(Candidate(expr.loc, fn.name, field.name, cb, reaches))
        }
    }

    /** What the callback's body may perform, at OP granularity where visible: a
     *  `TypedPerformOp` names its op; a call to a plain forwarder (`await { p }` =
     *  `Async.await { p }`) is looked into through its lowered body; any other call
     *  whose row names an effect counts as "some op of it". */
    private fun collectReaches(expr: TypedExpr, irOf: (Hash) -> FunctionIR?, out: MutableList<Reach>) {
        when (expr) {
            is TypedPerformOp -> out.add(Reach(expr.effectHash, "", expr.operationName.substringAfterLast('.')))
            is TypedCall -> {
                val callee = expr.function
                val row = (callee.type as? MezzeType.FunctionType)?.effects
                if (row != null && row.effects.isNotEmpty()) {
                    val fwd = ((callee as? TypedVar)?.binding as? BindingRef.ByHash)?.hash?.let(irOf)?.let(::forwardedOp)
                    for ((name, h) in row.effectHashes) {
                        out.add(Reach(h, name, if (fwd != null && fwd.first == h) fwd.second else null))
                    }
                }
            }
            else -> {}
        }
        expr.forEachChild { collectReaches(it, irOf, out) }
    }

    /** `pub let await = { p } -> Async.await { p = p }` lowers to a body that is one
     *  `PerformOpIR` (possibly under an empty block): the op it forwards to. */
    private fun forwardedOp(ir: FunctionIR): Pair<Hash, String>? {
        var body = ir.body
        var depth = 0
        while (body is BlockIR && body.stmts.isEmpty() && depth++ < 3) body = body.result
        return (body as? PerformOpIR)?.let { it.effectHash to it.operationName.substringAfterLast('.') }
    }

    fun check() {
        if (candidates.isEmpty() || contCapable.isEmpty()) return
        for (c in candidates) {
            for (r in c.reaches) {
                val cc = contCapable[r.effectHash] ?: continue
                // Op-granular where the op is visible: `spawn` on a scheduler does not
                // park, `await` does. Unknown op: the performer parks on SOME op.
                if (r.op != null && r.op !in cc.contOps) continue
                val effect = cc.effectName.ifEmpty { r.effectName }
                val what = if (r.op != null) "`${r.op}` (an op of `$effect`), and `${cc.performer}`'s `${r.op}` parks"
                    else "`$effect`, and `${cc.performer}` parks on it"
                throw TypeCheckError(
                    "`${c.field}` calls $what (installed at ${cc.loc.startLine}:${cc.loc.startCol}) — but it is " +
                        "handed to `${c.callee}`, a native that cannot be suspended through: a park inside the " +
                        "callback would be lost. Run the loop in Mezze (a `match`, recursion, or `iter`), or keep " +
                        "`${c.field}` free of `$effect`. (`${c.field}: ${prettyPrintType(c.callbackType)}`)",
                    c.loc,
                    code = ErrorCode.V0513_PARK_THROUGH_NATIVE,
                )
            }
        }
    }

    /** The lowered body is a direct native call, possibly under an empty block. */
    private fun isNativeWrapper(ir: FunctionIR): Boolean {
        var body = ir.body
        var depth = 0
        while (body is BlockIR && body.stmts.isEmpty() && depth++ < 3) body = body.result
        return body is CallNativeIR
    }
}
