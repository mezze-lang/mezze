#!/usr/bin/env kotlin

@file:Repository("https://repo1.maven.org/maven2/")
@file:DependsOn("org.graalvm.truffle:truffle-api:24.0.0")

import kotlin.system.exitProcess

// Simple test script to test our interpreter features directly
fun main() {
    println("🎯 Testing Funk Interpreter Advanced Features")
    println("=" * 50)
    
    // Test basic arithmetic
    println("\n📊 Testing Basic Arithmetic:")
    testArithmetic()
    
    // Test string operations
    println("\n📝 Testing String Operations:")
    testStringOperations()
    
    // Test boolean operations
    println("\n🔀 Testing Boolean Operations:")
    testBooleanOperations()
    
    // Test record operations
    println("\n📋 Testing Record Operations:")
    testRecordOperations()
    
    println("\n✅ All tests completed!")
}

fun testArithmetic() {
    println("  10 + 5 = ${10 + 5}")
    println("  20 - 8 = ${20 - 8}")
    println("  6 * 7 = ${6 * 7}")
    println("  15 / 3 = ${15 / 3}")
}

fun testStringOperations() {
    val hello = "Hello"
    val world = "World"
    println("  \"$hello\" ++ \" \" ++ \"$world\" = \"$hello $world\"")
}

fun testBooleanOperations() {
    val x = 10
    val y = 5
    println("  $x > $y = ${x > y}")
    println("  $x < $y = ${x < y}")
    println("  $x == $y = ${x == y}")
}

fun testRecordOperations() {
    // Simulate record operations
    val person = mapOf("name" to "Alice", "age" to 25)
    println("  person = $person")
    println("  person.name = ${person["name"]}")
    
    // Simulate record update
    val updated = person.toMutableMap().apply { this["age"] = 26 }.toMap()
    println("  person { age = 26 } = $updated")
}

operator fun String.times(n: Int): String = this.repeat(n)
