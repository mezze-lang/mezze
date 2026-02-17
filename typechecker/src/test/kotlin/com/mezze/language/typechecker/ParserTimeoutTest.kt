package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import java.util.concurrent.*
import kotlin.concurrent.thread

class ParserTimeoutTest {

    @Test
    fun `test parsing with timeout and stack trace`() {
        println("========================================")
        println("TEST STARTED: Parsing with timeout mechanism")
        System.out.flush()

        val code = """
            use std::collections::array

            let map = {f: {x: Int} -> Int, xs: [Int]} ->
                match xs is
                  [] -> []
                  [head, ...tail] -> array::of {x = f {x = head}, xs = map {f = f, xs = tail}}
        """.trimIndent()

        var parserThread: Thread? = null
        var parseException: Exception? = null

        val parseTask = Callable<Unit> {
            parserThread = Thread.currentThread()
            println("Parser thread started: ${parserThread?.name}")
            System.out.flush()

            try {
                val parser = MezzeParser()
                println("Parser created")
                System.out.flush()

                println("About to parse...")
                System.out.flush()

                val program = parser.parse(code)

                println("Parsing completed successfully!")
                println("Program has ${program.items.size} items")
                System.out.flush()
            } catch (e: Exception) {
                parseException = e
                println("Parse exception: ${e.message}")
                e.printStackTrace()
                System.out.flush()
                throw e
            }
        }

        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit(parseTask)

        try {
            // Wait 5 seconds for parsing to complete
            println("Waiting up to 5 seconds for parsing...")
            System.out.flush()

            future.get(5, TimeUnit.SECONDS)

            println("SUCCESS: Parsing completed within timeout")
            System.out.flush()

        } catch (e: TimeoutException) {
            println("\n========================================")
            println("TIMEOUT: Parser hung after 5 seconds")
            println("========================================")
            System.out.flush()

            // Capture stack trace of the hanging thread
            parserThread?.let { thread ->
                println("\nStack trace of parser thread '${thread.name}':")
                println("State: ${thread.state}")
                println("\nStack trace:")
                thread.stackTrace.forEach { element ->
                    println("    at $element")
                }
                System.out.flush()
            }

            // Try to interrupt the thread
            println("\nAttempting to interrupt parser thread...")
            System.out.flush()
            future.cancel(true)
            parserThread?.interrupt()

            Thread.sleep(100) // Give it a moment

            println("Thread state after interrupt: ${parserThread?.state}")
            System.out.flush()

            throw AssertionError("Parser hung - see stack trace above")

        } catch (e: ExecutionException) {
            println("Execution exception: ${e.cause?.message}")
            e.cause?.printStackTrace()
            System.out.flush()
            throw e

        } finally {
            executor.shutdownNow()
            println("\n========================================")
            System.out.flush()
        }
    }
}
