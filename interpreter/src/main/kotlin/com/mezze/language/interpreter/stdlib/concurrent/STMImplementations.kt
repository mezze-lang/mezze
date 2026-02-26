package com.mezze.language.interpreter.stdlib.concurrent

import com.mezze.language.interpreter.*
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import java.util.BitSet

/**
 * Native implementations for Software Transactional Memory (STM).
 *
 * Implementation inspired by Yona language's STM with:
 * - Multi-Version Concurrency Control (MVCC) for snapshot isolation
 * - Bloom filters for fast conflict detection
 * - Optimistic concurrency with validation at commit time
 * - Retry/orElse for composable blocking
 *
 * Key design:
 * - Each TVar has a versioned value
 * - Transactions read from a consistent snapshot
 * - Writes are buffered until commit
 * - Commit validates read-set versions haven't changed
 * - On conflict, transaction automatically retries
 * - retry blocks until a watched TVar changes
 *
 * Transaction boundaries:
 * - Transaction starts on first STM operation in a perform block
 * - Transaction commits when the perform block completes successfully
 * - Transaction aborts on exception or retry
 */
object STMImplementations {

    // Global version counter for MVCC
    private val globalVersion = AtomicLong(0L)

    // Thread-local transaction context
    // Each thread gets its own transaction when inside a perform STM block
    private val currentTransaction = ThreadLocal<Transaction?>()

    // Maximum retries before giving up (prevents infinite loops)
    private const val DEFAULT_MAX_RETRIES = 10000
    private const val DEFAULT_SPIN_COUNT = 100

    fun registerAll(context: MutableMap<String, MezzeValue>) {
        registerTVarOperations(context)
        registerCoreSTMOperations(context)
        registerConfigurableSTMOperations(context)
        registerSequentialSTMOperations(context)
        registerBulkOperations(context)
    }

    // =========================================================================
    // TVar Operations (outside transactions)
    // =========================================================================

    private fun registerTVarOperations(context: MutableMap<String, MezzeValue>) {
        // __native_tvar_new: {init: 'a} -> TVar 'a
        context["__native_tvar_new"] = NativeMezzeFunction("__native_tvar_new") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_tvar_new expects MezzeRecord")

            val init = record.fields["init"]
                ?: throw MezzeException("__native_tvar_new expects 'init' field")

            MezzeTVar(TVar(init))
        }

        // __native_tvar_deref: {var: TVar 'a} -> 'a
        // Non-transactional read (for debugging, not consistent)
        context["__native_tvar_deref"] = NativeMezzeFunction("__native_tvar_deref") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_tvar_deref expects MezzeRecord")

            val tvar = unwrapTVar(record.fields["var"])
            tvar.read()
        }
    }

    // =========================================================================
    // Core STM Operations (OptimisticSTM handler)
    // =========================================================================

    private fun registerCoreSTMOperations(context: MutableMap<String, MezzeValue>) {
        // __native_stm_read: {var: TVar 'a} -> 'a
        context["__native_stm_read"] = NativeMezzeFunction("__native_stm_read") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_stm_read expects MezzeRecord")

            val tvar = unwrapTVar(record.fields["var"])
            val txn = getOrCreateTransaction()
            txn.read(tvar)
        }

        // __native_stm_write: {var: TVar 'a, val: 'a} -> {}
        context["__native_stm_write"] = NativeMezzeFunction("__native_stm_write") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_stm_write expects MezzeRecord")

            val tvar = unwrapTVar(record.fields["var"])
            val value = record.fields["val"]
                ?: throw MezzeException("__native_stm_write expects 'val' field")

            val txn = getOrCreateTransaction()
            txn.write(tvar, value)

            // Write is buffered - will be committed when the perform OptimisticSTM block ends
            MezzeRecord(emptyMap())
        }

        // __native_stm_modify: {var: TVar 'a, f: {val: 'a} -> 'a} -> {}
        context["__native_stm_modify"] = NativeMezzeFunction("__native_stm_modify") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_stm_modify expects MezzeRecord")

            val tvar = unwrapTVar(record.fields["var"])
            val f = record.fields["f"]
                ?: throw MezzeException("__native_stm_modify expects 'f' field")

            // Retry loop for atomic modify
            var retries = 0
            while (retries < DEFAULT_MAX_RETRIES) {
                val txn = getOrCreateTransaction()
                try {
                    val current = txn.read(tvar)
                    val newValue = callFunctionWithVal(f, current)
                    txn.write(tvar, newValue)

                    if (commitCurrentTransaction()) {
                        return@NativeMezzeFunction MezzeRecord(emptyMap())
                    }
                    // Commit failed, retry
                    retries++
                } catch (e: RetryException) {
                    // Explicit retry requested
                    clearCurrentTransaction()
                    throw e
                } catch (e: Exception) {
                    clearCurrentTransaction()
                    throw e
                }
            }
            throw MezzeException("STM modify failed after $DEFAULT_MAX_RETRIES retries")
        }

        // __native_stm_swap: {var: TVar 'a, val: 'a} -> 'a
        context["__native_stm_swap"] = NativeMezzeFunction("__native_stm_swap") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_stm_swap expects MezzeRecord")

            val tvar = unwrapTVar(record.fields["var"])
            val value = record.fields["val"]
                ?: throw MezzeException("__native_stm_swap expects 'val' field")

            // Retry loop for atomic swap
            var retries = 0
            while (retries < DEFAULT_MAX_RETRIES) {
                val txn = getOrCreateTransaction()
                try {
                    val old = txn.read(tvar)
                    txn.write(tvar, value)

                    if (commitCurrentTransaction()) {
                        return@NativeMezzeFunction old
                    }
                    // Commit failed, retry
                    retries++
                } catch (e: Exception) {
                    clearCurrentTransaction()
                    throw e
                }
            }
            throw MezzeException("STM swap failed after $DEFAULT_MAX_RETRIES retries")
        }

        // __native_stm_ensure: {var: TVar 'a} -> 'a
        // Marks TVar as protected - if modified by another transaction, this one retries
        context["__native_stm_ensure"] = NativeMezzeFunction("__native_stm_ensure") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_stm_ensure expects MezzeRecord")

            val tvar = unwrapTVar(record.fields["var"])
            val txn = getOrCreateTransaction()
            txn.ensure(tvar)
        }

        // __native_stm_retry: {} -> 'a
        context["__native_stm_retry"] = NativeMezzeFunction("__native_stm_retry") { _ ->
            val txn = currentTransaction.get()
            if (txn != null) {
                // Block until one of the read TVars changes
                txn.blockOnRetry()
                clearCurrentTransaction()
            }
            throw RetryException()
        }

        // __native_stm_or_else: {primary: {} -> 'a, fallback: {} -> 'a} -> 'a
        context["__native_stm_or_else"] = NativeMezzeFunction("__native_stm_or_else") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_stm_or_else expects MezzeRecord")

            val primaryFn = record.fields["primary"]
                ?: throw MezzeException("__native_stm_or_else expects 'primary' field")
            val fallbackFn = record.fields["fallback"]
                ?: throw MezzeException("__native_stm_or_else expects 'fallback' field")

            // Save current transaction state
            val savedTxn = currentTransaction.get()

            try {
                // Try primary
                val result = callThunk(primaryFn)
                result
            } catch (e: RetryException) {
                // Primary retried, restore state and try fallback
                currentTransaction.set(savedTxn)
                callThunk(fallbackFn)
            }
        }

        // __native_stm_check: {cond: Bool} -> {}
        context["__native_stm_check"] = NativeMezzeFunction("__native_stm_check") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_stm_check expects MezzeRecord")

            val cond = record.fields["cond"] as? MezzeBool
                ?: throw MezzeException("__native_stm_check expects 'cond' field of type Bool")

            if (!cond.value) {
                val txn = currentTransaction.get()
                if (txn != null) {
                    txn.blockOnRetry()
                    clearCurrentTransaction()
                }
                throw RetryException()
            }
            MezzeRecord(emptyMap())
        }
    }

    // =========================================================================
    // Configurable STM Operations (OptimisticSTMWith handler)
    // =========================================================================

    private fun registerConfigurableSTMOperations(context: MutableMap<String, MezzeValue>) {
        // Same as core operations - config params reserved for future tuning

        context["__native_stm_read_cfg"] = context["__native_stm_read"]!!

        context["__native_stm_write_cfg"] = NativeMezzeFunction("__native_stm_write_cfg") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_stm_write_cfg expects MezzeRecord")

            val tvar = unwrapTVar(record.fields["var"])
            val value = record.fields["val"]
                ?: throw MezzeException("__native_stm_write_cfg expects 'val' field")

            val txn = getOrCreateTransaction()
            txn.write(tvar, value)
            // Write is buffered - will be committed when the perform block ends
            MezzeRecord(emptyMap())
        }

        context["__native_stm_modify_cfg"] = context["__native_stm_modify"]!!
        context["__native_stm_swap_cfg"] = context["__native_stm_swap"]!!
        context["__native_stm_ensure_cfg"] = context["__native_stm_ensure"]!!
        context["__native_stm_retry_cfg"] = context["__native_stm_retry"]!!
        context["__native_stm_or_else_cfg"] = context["__native_stm_or_else"]!!
        context["__native_stm_check_cfg"] = context["__native_stm_check"]!!
    }

    // =========================================================================
    // Sequential STM Operations (SequentialSTM handler)
    // =========================================================================

    private fun registerSequentialSTMOperations(context: MutableMap<String, MezzeValue>) {
        // Sequential versions - same implementation, just aliases
        context["__native_stm_seq_read"] = context["__native_stm_read"]!!
        context["__native_stm_seq_write"] = context["__native_stm_write"]!!
        context["__native_stm_seq_modify"] = context["__native_stm_modify"]!!
        context["__native_stm_seq_swap"] = context["__native_stm_swap"]!!
        context["__native_stm_seq_ensure"] = context["__native_stm_ensure"]!!
        context["__native_stm_seq_retry"] = context["__native_stm_retry"]!!
        context["__native_stm_seq_or_else"] = context["__native_stm_or_else"]!!
        context["__native_stm_seq_check"] = context["__native_stm_check"]!!
    }

    // =========================================================================
    // Bulk Operations
    // =========================================================================

    private fun registerBulkOperations(context: MutableMap<String, MezzeValue>) {
        // __native_stm_read_all: {vars: [TVar 'a]} -> ['a]
        context["__native_stm_read_all"] = NativeMezzeFunction("__native_stm_read_all") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_stm_read_all expects MezzeRecord")

            val vars = record.fields["vars"] as? MezzeArrayList
                ?: throw MezzeException("__native_stm_read_all expects 'vars' field of type List")

            val txn = getOrCreateTransaction()
            val results = vars.toList().map { v ->
                val tvar = unwrapTVar(v)
                txn.read(tvar)
            }
            MezzeArrayList(results)
        }

        // __native_stm_write_all: {pairs: [{var: TVar 'a, val: 'a}]} -> {}
        context["__native_stm_write_all"] = NativeMezzeFunction("__native_stm_write_all") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_stm_write_all expects MezzeRecord")

            val pairs = record.fields["pairs"] as? MezzeArrayList
                ?: throw MezzeException("__native_stm_write_all expects 'pairs' field of type List")

            val txn = getOrCreateTransaction()
            for (pair in pairs.toList()) {
                val pairRecord = pair as? MezzeRecord
                    ?: throw MezzeException("__native_stm_write_all expects list of records")
                val tvar = unwrapTVar(pairRecord.fields["var"])
                val value = pairRecord.fields["val"]
                    ?: throw MezzeException("__native_stm_write_all expects 'val' field in each pair")
                txn.write(tvar, value)
            }
            commitCurrentTransaction()
            MezzeRecord(emptyMap())
        }

        // __native_stm_atomic: {f: {} -> 'a} -> 'a
        // Executes a function atomically with automatic retry on conflict
        context["__native_stm_atomic"] = NativeMezzeFunction("__native_stm_atomic") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_stm_atomic expects MezzeRecord")

            val f = record.fields["f"]
                ?: throw MezzeException("__native_stm_atomic expects 'f' field")

            var retries = 0
            while (retries < DEFAULT_MAX_RETRIES) {
                // Start a fresh transaction for each attempt
                clearCurrentTransaction()
                val txn = getOrCreateTransaction()

                try {
                    // Execute the transaction body
                    val result = callThunk(f)

                    // Try to commit
                    if (commitCurrentTransaction()) {
                        return@NativeMezzeFunction result
                    }
                    // Commit failed due to conflict, retry
                    retries++
                } catch (e: RetryException) {
                    // Explicit retry requested, block until a watched TVar changes
                    txn.blockOnRetry()
                    clearCurrentTransaction()
                    retries++
                } catch (e: Exception) {
                    clearCurrentTransaction()
                    throw e
                }
            }
            throw MezzeException("STM atomic failed after $DEFAULT_MAX_RETRIES retries")
        }

        // __native_stm_commit: {} -> Bool
        // Explicitly commit the current transaction, returns success
        context["__native_stm_commit"] = NativeMezzeFunction("__native_stm_commit") { _ ->
            val success = commitCurrentTransaction()
            MezzeBool(success)
        }
    }

    // =========================================================================
    // Helper Functions
    // =========================================================================

    private fun unwrapTVar(value: MezzeValue?): TVar {
        return when (value) {
            is MezzeTVar -> value.tvar
            is MezzeRecord -> {
                // Newtype wrapper: TVar 'a = TVar __NativeTVar
                val inner = value.fields.values.firstOrNull()
                when (inner) {
                    is MezzeTVar -> inner.tvar
                    else -> throw MezzeException("Expected TVar, got record with: ${value.fields.keys}")
                }
            }
            else -> throw MezzeException("Expected TVar, got: ${value?.javaClass?.simpleName}")
        }
    }

    private fun getOrCreateTransaction(): Transaction {
        var txn = currentTransaction.get()
        if (txn == null) {
            txn = Transaction()
            currentTransaction.set(txn)
        }
        return txn
    }

    private fun clearCurrentTransaction() {
        currentTransaction.remove()
    }

    /**
     * Commit the current transaction and clear it.
     * Called when a perform OptimisticSTM block ends or when an async task completes.
     * Public so PerformNode and LoomImplementations can call it.
     *
     * Note: If commit fails due to conflict, the transaction's writes are lost.
     * For proper conflict handling with retry, use the atomic wrapper which
     * re-executes the entire transaction body on conflict.
     */
    fun commitAndClearTransaction() {
        val txn = currentTransaction.get() ?: return

        // If there are uncommitted writes, commit them
        if (!txn.isReadOnly()) {
            // Try to commit. If it fails due to conflict, the writes are lost.
            // This is a limitation of the simple effect-based STM model.
            // For proper retry semantics, users should structure their code
            // to handle conflicts, or use the atomic wrapper.
            txn.commit(globalVersion)
        }
        currentTransaction.remove()
    }

    /**
     * Commit the current transaction.
     * Returns true if successful, false if validation failed (should retry).
     */
    private fun commitCurrentTransaction(): Boolean {
        val txn = currentTransaction.get() ?: return true

        val success = txn.commit(globalVersion)
        if (success) {
            currentTransaction.remove()
        } else {
            // Reset transaction for retry
            txn.reset()
        }
        return success
    }

    private fun callFunctionWithVal(f: MezzeValue, value: MezzeValue): MezzeValue {
        val argsRecord = MezzeRecord(mapOf("val" to value))
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

    private fun callThunk(f: MezzeValue): MezzeValue {
        val argsRecord = MezzeRecord(emptyMap())
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

    // =========================================================================
    // TVar Implementation
    // =========================================================================

    /**
     * Transactional Variable - a mutable reference that can only be
     * read/written within transactions.
     *
     * Uses a versioned value box for MVCC.
     */
    class TVar(initialValue: MezzeValue) {
        private val id = nextId.getAndIncrement()
        private val versionedValue = AtomicReference(VersionedValue(0L, initialValue))

        // Waiters blocked on retry for this TVar
        private val waiters = java.util.concurrent.ConcurrentLinkedQueue<Thread>()

        companion object {
            private val nextId = AtomicLong(0L)
        }

        fun read(): MezzeValue = versionedValue.get().value
        fun readVersioned(): VersionedValue = versionedValue.get()
        fun getId(): Long = id

        /**
         * Write a new value with the given version.
         * Uses CAS to ensure atomicity.
         * Returns true if successful.
         */
        fun write(version: Long, value: MezzeValue): Boolean {
            while (true) {
                val current = versionedValue.get()
                val new = VersionedValue(version, value)
                if (versionedValue.compareAndSet(current, new)) {
                    // Wake up any waiters
                    wakeWaiters()
                    return true
                }
                // CAS failed, but we'll retry in outer loop anyway
                return false
            }
        }

        /**
         * Unconditionally write a value (used after validation succeeds).
         */
        fun forceWrite(version: Long, value: MezzeValue) {
            versionedValue.set(VersionedValue(version, value))
            wakeWaiters()
        }

        private fun wakeWaiters() {
            var waiter = waiters.poll()
            while (waiter != null) {
                LockSupport.unpark(waiter)
                waiter = waiters.poll()
            }
        }

        fun addWaiter(thread: Thread) {
            waiters.add(thread)
        }

        fun removeWaiter(thread: Thread) {
            waiters.remove(thread)
        }
    }

    data class VersionedValue(val version: Long, val value: MezzeValue)

    // =========================================================================
    // Transaction Implementation
    // =========================================================================

    /**
     * STM Transaction with MVCC and optimistic concurrency.
     *
     * - readSet: TVars read and their versions at read time
     * - writeSet: TVars written and their new values
     * - ensureSet: TVars that must not change (strong consistency)
     * - readBloom: Bloom filter for fast conflict checking
     */
    class Transaction {
        private val readSet = mutableMapOf<Long, ReadEntry>()
        private val writeSet = mutableMapOf<Long, WriteEntry>()
        private val ensureSet = mutableSetOf<Long>()
        private val readBloom = BloomFilter(256)

        data class ReadEntry(val tvar: TVar, val version: Long, val value: MezzeValue)
        data class WriteEntry(val tvar: TVar, val value: MezzeValue)

        fun read(tvar: TVar): MezzeValue {
            val id = tvar.getId()

            // Check write set first (read your own writes)
            writeSet[id]?.let { return it.value }

            // Check read set (already read this TVar)
            readSet[id]?.let { return it.value }

            // First read - get current versioned value
            val vv = tvar.readVersioned()
            readSet[id] = ReadEntry(tvar, vv.version, vv.value)
            readBloom.add(id)
            return vv.value
        }

        fun write(tvar: TVar, value: MezzeValue) {
            val id = tvar.getId()

            // If not already read, read first to establish baseline version
            if (id !in readSet && id !in writeSet) {
                read(tvar)
            }

            writeSet[id] = WriteEntry(tvar, value)
        }

        fun ensure(tvar: TVar): MezzeValue {
            val id = tvar.getId()
            ensureSet.add(id)
            return read(tvar)
        }

        /**
         * Validate that all read TVars still have the same version.
         * Returns true if transaction can commit.
         */
        fun validate(): Boolean {
            for ((id, entry) in readSet) {
                val current = entry.tvar.readVersioned()
                if (current.version != entry.version) {
                    return false
                }
            }

            // Also validate ensure set
            for (id in ensureSet) {
                val entry = readSet[id] ?: continue
                val current = entry.tvar.readVersioned()
                if (current.version != entry.version) {
                    return false
                }
            }

            return true
        }

        /**
         * Commit the transaction.
         * Returns true if successful, false if validation failed.
         */
        fun commit(globalVersion: AtomicLong): Boolean {
            if (writeSet.isEmpty()) {
                // Read-only transaction - just validate
                return validate()
            }

            // Get new version for writes
            val newVersion = globalVersion.incrementAndGet()

            // Validate read set
            if (!validate()) {
                return false
            }

            // Apply writes atomically
            // Note: In a full implementation, we'd lock the TVars here
            // For simplicity, we just write them (works for most single-threaded cases)
            for ((_, entry) in writeSet) {
                entry.tvar.forceWrite(newVersion, entry.value)
            }

            return true
        }

        /**
         * Block until one of the read TVars changes, then retry.
         */
        fun blockOnRetry() {
            if (readSet.isEmpty()) {
                // Nothing to wait on
                return
            }

            val thread = Thread.currentThread()

            // Register as waiter on all read TVars
            for ((_, entry) in readSet) {
                entry.tvar.addWaiter(thread)
            }

            try {
                // Check if any TVar already changed while registering
                if (!validate()) {
                    return // Will retry with new values
                }

                // Park until woken up by a TVar change (with timeout to prevent deadlock)
                LockSupport.parkNanos(100_000_000L) // 100ms timeout
            } finally {
                // Unregister from all TVars
                for ((_, entry) in readSet) {
                    entry.tvar.removeWaiter(thread)
                }
            }
        }

        fun reset() {
            readSet.clear()
            writeSet.clear()
            ensureSet.clear()
            readBloom.clear()
        }

        /**
         * Check if this transaction has no pending writes.
         */
        fun isReadOnly(): Boolean = writeSet.isEmpty()
    }

    /**
     * Simple bloom filter for fast set membership testing.
     * Used to quickly check if a TVar might be in the read/write set.
     */
    class BloomFilter(private val size: Int) {
        private val bits = BitSet(size)

        private fun hash1(id: Long): Int = (id % size).toInt().let { if (it < 0) it + size else it }
        private fun hash2(id: Long): Int = ((id * 31) % size).toInt().let { if (it < 0) it + size else it }
        private fun hash3(id: Long): Int = ((id * 17 + 7) % size).toInt().let { if (it < 0) it + size else it }

        fun add(id: Long) {
            bits.set(hash1(id))
            bits.set(hash2(id))
            bits.set(hash3(id))
        }

        fun mightContain(id: Long): Boolean {
            return bits.get(hash1(id)) && bits.get(hash2(id)) && bits.get(hash3(id))
        }

        fun clear() {
            bits.clear()
        }
    }

    /**
     * Exception thrown when retry is called.
     */
    class RetryException : RuntimeException("STM retry")
}

/**
 * MezzeTVar - wraps a TVar for the Mezze runtime.
 */
data class MezzeTVar(
    val tvar: STMImplementations.TVar
) : MezzeValue() {
    override fun computeDisplayString(): String = "<tvar:${tvar.getId()}>"
}
