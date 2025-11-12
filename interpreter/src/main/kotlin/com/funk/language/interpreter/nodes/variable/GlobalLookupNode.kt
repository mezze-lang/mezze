package com.funk.language.interpreter.nodes.variable

import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.NodeInfo
import com.funk.language.interpreter.nodes.FunkNode
import com.funk.language.interpreter.FunkValue
import com.funk.language.interpreter.FunkNull
import com.funk.language.interpreter.FunkLanguage
import com.funk.language.interpreter.FunkContext

/**
 * Truffle node for looking up global variables (like built-in functions)
 */
@NodeInfo(shortName = "global-lookup")
class GlobalLookupNode(
    private val name: String
) : FunkNode() {
    
    override fun execute(frame: VirtualFrame): FunkValue {
        // Look up the value in the global context
        val context = getContext()
        return context.lookupGlobal(name) ?: FunkNull
    }
}