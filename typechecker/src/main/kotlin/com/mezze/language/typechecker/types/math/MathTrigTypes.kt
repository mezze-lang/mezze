package com.mezze.language.typechecker.types.math

import com.mezze.language.ast.MezzeType
import com.mezze.language.typechecker.types.*

object MathTrigTypes {
    fun registerAll(context: MutableMap<String, MezzeType>) = natives(context) {
        // Native trig functions
        fn("__native_float_sin") { param("n" to float); returns(float) }
        fn("__native_float_cos") { param("n" to float); returns(float) }
        fn("__native_float_tan") { param("n" to float); returns(float) }
        fn("__native_float_asin") { param("n" to float); returns(float) }
        fn("__native_float_acos") { param("n" to float); returns(float) }
        fn("__native_float_atan") { param("n" to float); returns(float) }
        fn("__native_float_atan2") { param("y" to float, "x" to float); returns(float) }

        // Module-qualified: std::math::trig
        const("trig::pi", float)
        const("trig::e", float)
        fn("trig::sin") { param("n" to float); returns(float) }
        fn("trig::cos") { param("n" to float); returns(float) }
        fn("trig::tan") { param("n" to float); returns(float) }
        fn("trig::asin") { param("n" to float); returns(float) }
        fn("trig::acos") { param("n" to float); returns(float) }
        fn("trig::atan") { param("n" to float); returns(float) }
        fn("trig::atan2") { param("y" to float, "x" to float); returns(float) }
        fn("trig::degrees_to_radians") { param("degrees" to float); returns(float) }
        fn("trig::radians_to_degrees") { param("radians" to float); returns(float) }
        fn("trig::sinh") { param("n" to float); returns(float) }
        fn("trig::cosh") { param("n" to float); returns(float) }
        fn("trig::tanh") { param("n" to float); returns(float) }
    }
}
