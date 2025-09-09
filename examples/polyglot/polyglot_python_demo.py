# Python side of polyglot demo
# This shows how to call Funk functions from Python

import polyglot

# Load and execute Funk code
funk_code = """
let add = { x: Int, y: Int } -> x + y
let multiply = { x: Int, y: Int } -> x * y
let createRecord = { name: String, value: Int } -> 
    { name = name, value = value }

// Return functions for Python to use
{
    add = add,
    multiply = multiply,
    createRecord = createRecord
}
"""

print("=== Funk ↔ Python Interop Demo ===")

# Evaluate Funk code and get the module
funk_module = polyglot.eval(language="funk", string=funk_code)

# Call Funk functions from Python
result1 = funk_module.add({"x": 10, "y": 20})
print(f"Funk add(10, 20) = {result1}")

result2 = funk_module.multiply({"x": 7, "y": 6})
print(f"Funk multiply(7, 6) = {result2}")

# Create a record using Funk
record = funk_module.createRecord({"name": "Python Record", "value": 42})
print(f"Created record: {record}")

# Access record fields from Python
print(f"Record name: {record.name}")
print(f"Record value: {record.value}")

# Python can pass its own data to Funk
python_data = {"x": 100, "y": 200}
result3 = funk_module.add(python_data)
print(f"Funk add with Python data: {result3}")

# Demonstrate type safety - this would fail at runtime
try:
    # This should fail because Funk expects Int but we're passing String
    bad_result = funk_module.add({"x": "hello", "y": "world"})
except Exception as e:
    print(f"Type error caught: {e}")
