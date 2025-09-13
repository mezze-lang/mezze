import re

# Read the file
with open('parser/src/test/kotlin/com/funk/language/parser/EffectParserTest.kt', 'r') as f:
    content = f.read()

# Fix the test cases
# 1. Fix testPerformExpressionWithSingleHandler
content = re.sub(
    r'perform Console do\s+Log\.print \{msg = "hello"\s+',
    'perform Console do\n                Log.print {msg = "hello"}\n            ',
    content
)

# 2. Fix testPerformExpressionWithMultipleHandlers  
content = re.sub(
    r'perform Console, MathRandom, FileSystem do\s+someFunction \{\}\s+',
    'perform Console, MathRandom, FileSystem do\n                someFunction {}\n            ',
    content
)

# 3. Fix testPerformExpressionParsing
content = re.sub(
    r'perform Console, JVM do\s+program\s+',
    'perform Console, JVM do\n                program\n            ',
    content
)

# 4. Fix testPerformExpressionWithParameterizedHandler
content = re.sub(
    r'perform FileLogger \{path = "/tmp/log\.txt"\} do\s+Log\.print \{msg = "hello"\}\s+',
    'perform FileLogger {path = "/tmp/log.txt"} do\n                Log.print {msg = "hello"}\n            ',
    content
)

# 5. Fix testComplexEffectProgram
content = re.sub(
    r'perform Console, MathRandom do\s+gameLogic \{\}\s+',
    'perform Console, MathRandom do\n                gameLogic {}\n            ',
    content
)

# Write the file back
with open('parser/src/test/kotlin/com/funk/language/parser/EffectParserTest.kt', 'w') as f:
    f.write(content)

print("Fixed parser tests")
