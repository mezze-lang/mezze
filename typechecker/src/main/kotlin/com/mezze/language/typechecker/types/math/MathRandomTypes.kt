package com.mezze.language.typechecker.types.math

import com.mezze.language.ast.MezzeType
import com.mezze.language.typechecker.types.*

object MathRandomTypes {
    fun registerAll(context: MutableMap<String, MezzeType>) = natives(context) {
        fn("__native_seeded_random_float") { param("seed" to int); returns(float) }
        fn("__native_seeded_random_int") { param("seed" to int, "min" to int, "max" to int); returns(int) }
        fn("__native_seeded_random_nat") { param("seed" to int, "max" to nat); returns(nat) }
        fn("__native_update_seed") { param("old_seed" to int, "new_seed" to int); returns(unit) }
    }
}
