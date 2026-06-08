package com.mezze.language.typechecker.types.concurrent

import com.mezze.language.ast.MezzeType
import com.mezze.language.typechecker.types.*
import com.mezze.language.typechecker.util.WellKnownTypeApp

/**
 * Type definitions for Clojure-style agents (async atoms).
 *
 * Every native carries `<Async>` (post-#170).
 *
 * Agents provide asynchronous, queued state updates with these properties:
 * - State changes happen asynchronously (send returns immediately)
 * - Actions are processed sequentially in order
 * - If an action throws, the agent enters error state
 *
 * IMPORTANT: Each function gets its own fresh type variables!
 */
object AgentTypes {

    fun registerAll(context: MutableMap<String, MezzeType>) = natives(context) {
        // __native_agent_new: {val: 'a} -> Agent 'a <Async>
        fn("__native_agent_new") {
            val a = tvar("a")
            param("val" to a)
            returns(WellKnownTypeApp.of("Agent", listOf(a)))
            effects("Async")
        }

        // __native_agent_deref: {agent: Agent 'a} -> 'a <Async>
        fn("__native_agent_deref") {
            val a = tvar("a")
            param("agent" to WellKnownTypeApp.of("Agent", listOf(a)))
            returns(a)
            effects("Async")
        }

        // __native_agent_send: {agent: Agent 'a, f: {current: 'a} -> 'a} -> {} <Async>
        fn("__native_agent_send") {
            val a = tvar("a")
            val agentType = WellKnownTypeApp.of("Agent", listOf(a))
            val funcType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("current" to a)),
                a
            )
            param("agent" to agentType, "f" to funcType)
            returns(unit)
            effects("Async")
        }

        // __native_agent_send_off: {agent: Agent 'a, f: {current: 'a} -> 'a} -> {} <Async>
        fn("__native_agent_send_off") {
            val a = tvar("a")
            val agentType = WellKnownTypeApp.of("Agent", listOf(a))
            val funcType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("current" to a)),
                a
            )
            param("agent" to agentType, "f" to funcType)
            returns(unit)
            effects("Async")
        }

        // __native_agent_await: {agent: Agent 'a, timeout_ms: Int} -> Bool <Async>
        fn("__native_agent_await") {
            val a = tvar("a")
            param("agent" to WellKnownTypeApp.of("Agent", listOf(a)), "timeout_ms" to int)
            returns(bool)
            effects("Async")
        }

        // __native_agent_error: {agent: Agent 'a} -> Option String <Async>
        fn("__native_agent_error") {
            val a = tvar("a")
            param("agent" to WellKnownTypeApp.of("Agent", listOf(a)))
            returns(MezzeType.VariantType(
                mapOf(
                    "Some" to MezzeType.RecordType(mapOf("val" to str)),
                    "None" to null
                ),
                null
            ))
            effects("Async")
        }

        // __native_agent_restart: {agent: Agent 'a, val: 'a} -> {} <Async>
        fn("__native_agent_restart") {
            val a = tvar("a")
            param("agent" to WellKnownTypeApp.of("Agent", listOf(a)), "val" to a)
            returns(unit)
            effects("Async")
        }

        // __native_agent_set_error_handler: {agent: Agent 'a, handler: {agent: Agent 'a, error: String} -> {}} -> {} <Async>
        fn("__native_agent_set_error_handler") {
            val a = tvar("a")
            val agentType = WellKnownTypeApp.of("Agent", listOf(a))
            val handlerType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("agent" to agentType, "error" to str)),
                unit
            )
            param("agent" to agentType, "handler" to handlerType)
            returns(unit)
            effects("Async")
        }
    }
}
