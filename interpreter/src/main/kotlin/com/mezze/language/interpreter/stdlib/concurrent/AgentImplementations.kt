package com.mezze.language.interpreter.stdlib.concurrent

import com.mezze.language.interpreter.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Native implementations for Clojure-style agents.
 *
 * Agents provide asynchronous, queued state updates with these semantics:
 * - State changes happen asynchronously via a queue
 * - Each agent processes one action at a time (serialized)
 * - Actions are guaranteed to be applied in order
 * - If an action throws, the agent enters an error state
 *
 * Key differences from atoms:
 * - Atoms are synchronous (swap blocks until done)
 * - Agents are asynchronous (send returns immediately)
 * - Agents have error handling with restart capability
 *
 * Based on Clojure agent semantics.
 */
object AgentImplementations {

    /**
     * Register all agent-related native functions.
     */
    fun registerAll(context: MutableMap<String, MezzeValue>) {
        registerNew(context)
        registerDeref(context)
        registerSend(context)
        registerSendOff(context)
        registerAwait(context)
        registerError(context)
        registerRestart(context)
        registerSetErrorHandler(context)
    }

    /**
     * __native_agent_new: {value: 'a} -> Agent 'a
     * Creates a new agent with the given initial value.
     */
    private fun registerNew(context: MutableMap<String, MezzeValue>) {
        context["__native_agent_new"] = NativeMezzeFunction("__native_agent_new") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_agent_new expects MezzeRecord")

            val value = record.fields["value"]
                ?: throw MezzeException("__native_agent_new expects 'value' field")

            MezzeAgent(
                state = AtomicReference(value),
                actionQueue = ConcurrentLinkedQueue(),
                error = AtomicReference(null),
                processing = AtomicBoolean(false),
                errorHandler = AtomicReference(null)
            )
        }
    }

    /**
     * __native_agent_deref: {agent: Agent 'a} -> 'a
     * Returns the current value of the agent.
     * This is a snapshot - it may change immediately after reading.
     */
    private fun registerDeref(context: MutableMap<String, MezzeValue>) {
        context["__native_agent_deref"] = NativeMezzeFunction("__native_agent_deref") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_agent_deref expects MezzeRecord")

            val agent = record.fields["agent"] as? MezzeAgent
                ?: throw MezzeException("__native_agent_deref expects 'agent' field of type Agent")

            agent.state.get()
        }
    }

    /**
     * __native_agent_send: {agent: Agent 'a, f: {current: 'a} -> 'a} -> {}
     * Queues an action to be applied to the agent's state.
     * Returns immediately - action runs asynchronously.
     */
    private fun registerSend(context: MutableMap<String, MezzeValue>) {
        context["__native_agent_send"] = NativeMezzeFunction("__native_agent_send") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_agent_send expects MezzeRecord")

            val agent = record.fields["agent"] as? MezzeAgent
                ?: throw MezzeException("__native_agent_send expects 'agent' field of type Agent")

            val f = record.fields["f"]
                ?: throw MezzeException("__native_agent_send expects 'f' field")

            // Check if agent is in error state
            if (agent.error.get() != null) {
                throw MezzeException("Agent is in error state: ${agent.error.get()?.message}")
            }

            // Get the Truffle environment for creating threads
            val env = TruffleContextHolder.getEnv()
                ?: throw MezzeException("No Truffle environment available for agent")

            // Queue the action along with environment
            agent.actionQueue.add(AgentAction(f, env))

            // Try to start processing if not already processing
            processAgentQueue(agent)

            MezzeUnit
        }
    }

    /**
     * __native_agent_send_off: {agent: Agent 'a, f: {current: 'a} -> 'a} -> {}
     * Like send, but for potentially blocking operations.
     */
    private fun registerSendOff(context: MutableMap<String, MezzeValue>) {
        context["__native_agent_send_off"] = NativeMezzeFunction("__native_agent_send_off") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_agent_send_off expects MezzeRecord")

            val agent = record.fields["agent"] as? MezzeAgent
                ?: throw MezzeException("__native_agent_send_off expects 'agent' field of type Agent")

            val f = record.fields["f"]
                ?: throw MezzeException("__native_agent_send_off expects 'f' field")

            // Check if agent is in error state
            if (agent.error.get() != null) {
                throw MezzeException("Agent is in error state: ${agent.error.get()?.message}")
            }

            // Get the Truffle environment
            val env = TruffleContextHolder.getEnv()
                ?: throw MezzeException("No Truffle environment available for agent")

            // Queue the action (same as send for now)
            agent.actionQueue.add(AgentAction(f, env))
            processAgentQueue(agent)

            MezzeUnit
        }
    }

    /**
     * __native_agent_await: {agent: Agent 'a, timeout_ms: Int} -> Bool
     * Blocks until all actions sent to this agent (before this call) have completed.
     * Returns true if completed, false if timeout.
     */
    private fun registerAwait(context: MutableMap<String, MezzeValue>) {
        context["__native_agent_await"] = NativeMezzeFunction("__native_agent_await") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_agent_await expects MezzeRecord")

            val agent = record.fields["agent"] as? MezzeAgent
                ?: throw MezzeException("__native_agent_await expects 'agent' field of type Agent")

            val timeoutMs = (record.fields["timeout_ms"] as? MezzeInt)?.value ?: 0L

            // Wait until queue is empty and not processing
            val startTime = System.currentTimeMillis()
            while (agent.actionQueue.isNotEmpty() || agent.processing.get()) {
                if (timeoutMs > 0 && System.currentTimeMillis() - startTime > timeoutMs) {
                    return@NativeMezzeFunction MezzeBool(false)
                }
                Thread.sleep(1) // Yield
            }
            MezzeBool(true)
        }
    }

    /**
     * __native_agent_error: {agent: Agent 'a} -> Option String
     * Returns the error message if the agent is in error state, None otherwise.
     */
    private fun registerError(context: MutableMap<String, MezzeValue>) {
        context["__native_agent_error"] = NativeMezzeFunction("__native_agent_error") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_agent_error expects MezzeRecord")

            val agent = record.fields["agent"] as? MezzeAgent
                ?: throw MezzeException("__native_agent_error expects 'agent' field of type Agent")

            val error = agent.error.get()
            if (error != null) {
                mezzeOptionSome(MezzeString(error.message ?: "Unknown error"))
            } else {
                mezzeOptionNone()
            }
        }
    }

    /**
     * __native_agent_restart: {agent: Agent 'a, value: 'a} -> {}
     * Clears the error state and sets a new value.
     * The action queue is NOT cleared - pending actions will still run.
     */
    private fun registerRestart(context: MutableMap<String, MezzeValue>) {
        context["__native_agent_restart"] = NativeMezzeFunction("__native_agent_restart") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_agent_restart expects MezzeRecord")

            val agent = record.fields["agent"] as? MezzeAgent
                ?: throw MezzeException("__native_agent_restart expects 'agent' field of type Agent")

            val value = record.fields["value"]
                ?: throw MezzeException("__native_agent_restart expects 'value' field")

            agent.error.set(null)
            agent.state.set(value)

            // Resume processing if there are queued actions
            processAgentQueue(agent)

            MezzeUnit
        }
    }

    /**
     * __native_agent_set_error_handler: {agent: Agent 'a, handler: {agent: Agent 'a, error: String} -> {}} -> {}
     * Sets a custom error handler that's called when an action throws.
     */
    private fun registerSetErrorHandler(context: MutableMap<String, MezzeValue>) {
        context["__native_agent_set_error_handler"] = NativeMezzeFunction("__native_agent_set_error_handler") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_agent_set_error_handler expects MezzeRecord")

            val agent = record.fields["agent"] as? MezzeAgent
                ?: throw MezzeException("__native_agent_set_error_handler expects 'agent' field of type Agent")

            val handler = record.fields["handler"]
                ?: throw MezzeException("__native_agent_set_error_handler expects 'handler' field")

            agent.errorHandler.set(handler)
            MezzeUnit
        }
    }

    /**
     * Process the agent's action queue.
     * Uses CAS to ensure only one thread processes at a time.
     */
    private fun processAgentQueue(agent: MezzeAgent) {
        // Try to become the processor
        if (!agent.processing.compareAndSet(false, true)) {
            return // Another thread is already processing
        }

        // Get the first action to process (we need its env for the thread)
        val firstAction = agent.actionQueue.peek()
        if (firstAction == null) {
            agent.processing.set(false)
            return
        }

        // Create a Truffle thread using the environment from the action
        val thread = firstAction.env.newTruffleThreadBuilder {
            try {
                while (true) {
                    val action = agent.actionQueue.poll() ?: break

                    try {
                        val current = agent.state.get()
                        val newValue = callFunction(action.f, current)
                        agent.state.set(newValue)
                    } catch (e: Throwable) {
                        // Set error state
                        agent.error.set(e)

                        // Call error handler if set
                        agent.errorHandler.get()?.let { handler ->
                            try {
                                callErrorHandler(handler, agent, e.message ?: "Unknown error")
                            } catch (ignored: Throwable) {
                                // Ignore errors in error handler
                            }
                        }

                        // Stop processing on error
                        break
                    }
                }
            } catch (t: Throwable) {
                // Catch any outer exceptions too
                agent.error.set(t)
            } finally {
                agent.processing.set(false)

                // Check if more actions were added while we were finishing
                if (agent.actionQueue.isNotEmpty() && agent.error.get() == null) {
                    processAgentQueue(agent)
                }
            }
        }
            .virtual(true)  // Use virtual threads
            .build()

        // Start the processing thread
        thread.start()
    }

    /**
     * Helper to call a Mezze function with the current agent value.
     */
    private fun callFunction(f: MezzeValue, arg: MezzeValue): MezzeValue {
        val argsRecord = MezzeRecord(mapOf("current" to arg))
        return when (f) {
            is MezzeTruffleFunction -> {
                f.callTarget.call(
                    argsRecord,
                    f.closureVariables,
                    f.lexicalScope,
                    f.lexicalScopeSlotMap
                ) as MezzeValue
            }
            is NativeMezzeFunction -> {
                f.call(listOf(argsRecord))
            }
            else -> throw MezzeException("Expected function, got: ${f::class.simpleName}")
        }
    }

    /**
     * Helper to call error handler with agent and error message.
     */
    private fun callErrorHandler(handler: MezzeValue, agent: MezzeAgent, errorMsg: String) {
        val argsRecord = MezzeRecord(mapOf("agent" to agent, "error" to MezzeString(errorMsg)))
        when (handler) {
            is MezzeTruffleFunction -> {
                handler.callTarget.call(
                    argsRecord,
                    handler.closureVariables,
                    handler.lexicalScope,
                    handler.lexicalScopeSlotMap
                )
            }
            is NativeMezzeFunction -> {
                handler.call(listOf(argsRecord))
            }
            else -> throw MezzeException("Expected function, got: ${handler::class.simpleName}")
        }
    }
}

/**
 * Wraps an agent action with its Truffle environment.
 */
data class AgentAction(
    val f: MezzeValue,
    val env: com.oracle.truffle.api.TruffleLanguage.Env
)

/**
 * MezzeAgent - wraps agent state for Clojure-style asynchronous state updates.
 */
data class MezzeAgent(
    val state: AtomicReference<MezzeValue>,
    val actionQueue: ConcurrentLinkedQueue<AgentAction>,
    val error: AtomicReference<Throwable?>,
    val processing: AtomicBoolean,
    val errorHandler: AtomicReference<MezzeValue?>
) : MezzeValue() {
    override fun getDisplayString(): String {
        val errorMsg = error.get()?.let { " ERROR: ${it.message}" } ?: ""
        return "<agent:${state.get().getDisplayString()}$errorMsg>"
    }
}
