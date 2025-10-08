import com.funk.language.interpreter.FunkInt
import com.funk.language.interpreter.concurrency.*

fun main() {
    val concurrencyManager = LoomConcurrencyManager()
    val scheduler = SmartEffectScheduler(concurrencyManager)

    val op1 = scheduler.createEffectOperation(
        effectType = "IO",
        writeVars = setOf("a")
    ) { FunkInt(1L) }

    val op2 = scheduler.createEffectOperation(
        effectType = "Compute",
        readVars = setOf("a"),
        writeVars = setOf("b")
    ) { FunkInt(2L) }

    val op3 = scheduler.createEffectOperation(
        effectType = "Compute",
        readVars = setOf("b"),
        writeVars = setOf("c")
    ) { FunkInt(3L) }

    val block = EffectBlock(
        operations = listOf(op1, op2, op3),
        context = com.funk.language.interpreter.effects.EffectContext.empty()
    )

    val plan = scheduler.analyzeEffectBlock(block)
    val stats = plan.getStatistics()

    println("Total operations: ${stats.totalOperations}")
    println("Parallel groups: ${stats.parallelGroups}")
    println("Max parallelism: ${stats.maxParallelism}")

    println("\nParallel groups details:")
    plan.parallelGroups.forEachIndexed { index, group ->
        println("Group $index: ${group.effects.size} effects, maxConcurrency=${group.maxConcurrency}")
        group.effects.forEach { effect ->
            println("  - ${effect.id}: reads=${effect.readVariables}, writes=${effect.writeVariables}")
        }
    }

    concurrencyManager.shutdown()
}
