package com.mezze.language.typechecker.types.effects

import com.mezze.language.ast.MezzeType

/**
 * Type definitions for OS platform thread concurrency primitives.
 *
 * Only ops that genuinely create platform threads keep their own
 * natives. Stateless ops (await/race/all/etc.) operate on
 * MezzePromise values and route through __native_loom_* via the
 * stdlib OSThreadAsync impl (see stdlib/std/effects/async.mz).
 *
 * The remaining live OS natives — __native_os_init_scope,
 * __native_os_finalize_scope, __native_os_spawn_tracked — are
 * resolved as untyped __native_* calls (TypedAstBuilder accepts
 * any "__native_"-prefixed name) and dispatched at runtime by
 * NativeDispatch.
 */
object OSThreadTypes {

    fun registerAll(context: MutableMap<String, MezzeType>) {
        // No OS-specific natives have explicit type signatures here.
    }
}
