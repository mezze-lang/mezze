package com.mezze.language.interpreter.nodes.variable

import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.NodeInfo
import com.mezze.language.interpreter.nodes.MezzeNode
import com.mezze.language.interpreter.MezzeValue
import com.mezze.language.interpreter.MezzeNull
import com.mezze.language.interpreter.MezzeLanguage
import com.mezze.language.interpreter.MezzeContext

/**
 * Truffle node for looking up global variables (like built-in functions)
 */
@NodeInfo(shortName = "global-lookup")
class GlobalLookupNode(
    private val name: String
) : MezzeNode() {
    
    override fun execute(frame: VirtualFrame): MezzeValue {
        // Look up the value in the global context
        val context = getContext()
        return context.lookupGlobal(name) ?: MezzeNull
    }
}