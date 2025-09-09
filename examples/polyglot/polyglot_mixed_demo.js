// Mixed language demo - JavaScript orchestrating Funk, Python, and R
// This shows the power of GraalVM polyglot execution

console.log("=== Multi-Language Polyglot Demo ===");

// Define some data processing functions in Funk
const funkModule = Polyglot.eval('funk', `
    // Data transformation functions
    let transform = { data: { values: [Int] } } -> {
        // Double all values
        values = data.values  // In real Funk, we'd map over this
    }
    
    let validate = { input: Int } -> 
        if input > 0 then
            { valid = true, message = "Valid input" }
        else
            { valid = false, message = "Input must be positive" }
    
    {
        transform = transform,
        validate = validate
    }
`);

// Define some statistical functions in Python (if available)
try {
    const pythonStats = Polyglot.eval('python', `
def calculate_stats(numbers):
    if not numbers:
        return {"mean": 0, "sum": 0, "count": 0}
    
    total = sum(numbers)
    count = len(numbers)
    mean = total / count
    
    return {
        "sum": total,
        "count": count,
        "mean": mean
    }

# Return the function for JS to use
calculate_stats
`);

    // Use all languages together
    const data = [1, 2, 3, 4, 5, 10, -2];
    
    console.log("Original data:", data);
    
    // Validate each number using Funk
    console.log("\n--- Validation (Funk) ---");
    data.forEach(num => {
        const result = funkModule.validate({ input: num });
        console.log(`${num}: ${result.message} (valid: ${result.valid})`);
    });
    
    // Calculate statistics using Python
    console.log("\n--- Statistics (Python) ---");
    const stats = pythonStats(data);
    console.log("Sum:", stats.sum);
    console.log("Count:", stats.count);
    console.log("Mean:", stats.mean);
    
    console.log("\n--- Cross-Language Data Flow ---");
    console.log("1. JavaScript provides data");
    console.log("2. Funk validates each element");
    console.log("3. Python calculates statistics");
    console.log("4. JavaScript orchestrates everything");
    
} catch (e) {
    console.log("Python not available in this GraalVM build:", e.message);
    console.log("Demo works with Funk and JavaScript only");
}

// Show that we can pass complex objects between languages
const complexData = {
    name: "Dataset 1",
    values: [1, 2, 3, 4, 5],
    metadata: {
        source: "JavaScript",
        timestamp: new Date().toISOString()
    }
};

console.log("\n--- Complex Object Passing ---");
console.log("JavaScript object:", JSON.stringify(complexData, null, 2));

// Pass to Funk for validation
const validation = funkModule.validate({ input: complexData.values.length });
console.log("Funk validation of array length:", validation.message);
