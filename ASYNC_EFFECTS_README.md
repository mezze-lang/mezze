# Funk Language: Async Effects & State Machine Execution

## Overview

The Funk language features a sophisticated effect system that combines direct-style programming with efficient async execution. Effects are compiled to state machines and executed using Project Loom's virtual threads, providing both ergonomic code and high-performance concurrency.

## Core Concepts

### Effects as First-Class Citizens

Effects in Funk are not just type annotations—they represent suspendable computations that can be intercepted, handled, and executed asynchronously.

```funk
# Declare an effect with its operations
effect AsyncIO {
  readFile : {path: String} -> String
  writeFile : {path: String, content: String} -> {}
}

# Provide an implementation
impl AsyncIO for FileSystem {
  readFile : {path: String} -> String = {path} -> 
    # This will suspend the current virtual thread
    native_read_file {path = path}
  
  writeFile : {path: String, content: String} -> {} = {path, content} ->
    # This will also suspend
    native_write_file {path = path, content = content}
}
```

### Direct-Style Programming

Write code that looks synchronous but executes asynchronously:

```funk
let processFiles : {files: List<String>} -> String <AsyncIO> = {files} ->
  do
    let contents = []
    for file in files do
      let content = AsyncIO.readFile {path = file}  # Suspends here
      contents = content :: contents
    String.join {separator = "\n", strings = contents}
```

### State Machine Compilation

The `do` block above is automatically compiled to a state machine:

```
State 0: Initialize contents = [], file_index = 0
State 1: Check if file_index < files.length
  - If yes: Call AsyncIO.readFile(files[file_index]) -> Suspend -> State 2
  - If no: Call String.join -> State 3
State 2: Store result in content, append to contents, increment file_index -> State 1  
State 3: Return final result -> Complete
```

## Execution Model

### Virtual Thread Integration

Each effectful computation runs on a Project Loom virtual thread:

```funk
# This creates a virtual thread per computation
let concurrent_results = par [
  AsyncIO.readFile {path = "file1.txt"},
  AsyncIO.readFile {path = "file2.txt"}, 
  AsyncIO.readFile {path = "file3.txt"}
]
```

### Suspension Points

Any effect call is a potential suspension point:

```funk
do
  let x = AsyncIO.readFile {path = "input.txt"}    # Suspension Point 1
  let processed = String.toUpper {str = x}         # Pure computation (no suspension)
  AsyncIO.writeFile {path = "output.txt", content = processed}  # Suspension Point 2
  "Done"
```

### Cooperative Scheduling

Effects cooperatively yield control, allowing thousands of concurrent operations:

```funk
let handle_requests : {requests: List<Request>} -> List<Response> <AsyncIO, AsyncHTTP> = {requests} ->
  do
    let responses = []
    # Each iteration can suspend, allowing other computations to run
    for request in requests do
      let response = AsyncHTTP.fetch {url = request.url}
      responses = response :: responses
    responses
```

## Effect Handlers

### Handler Selection

Effects are resolved through explicit handlers:

```funk
# Explicit handler binding
let result = handle processFiles {files = ["a.txt", "b.txt"]} with FileSystem

# Scoped handler binding  
use FileSystem in
  let result1 = processFiles {files = ["x.txt"]}
  let result2 = processFiles {files = ["y.txt"]}
  [result1, result2]
```

### Handler Composition

Multiple effects can be handled together:

```funk
let web_scraper : {urls: List<String>} -> List<String> <AsyncHTTP, AsyncIO> = {urls} ->
  do
    let results = []
    for url in urls do
      let content = AsyncHTTP.fetch {url = url}
      let filename = "cache/" + Hash.sha1 {data = url} + ".html"
      AsyncIO.writeFile {path = filename, content = content}
      results = content :: results
    results

# Handle both effects
let scraped = handle web_scraper {urls = urls} with HttpClient, FileSystem
```

### Custom Handlers

Create domain-specific effect implementations:

```funk
impl AsyncIO for S3Storage {
  readFile : {path: String} -> String = {path} ->
    s3_get_object {bucket = "my-bucket", key = path}
  
  writeFile : {path: String, content: String} -> {} = {path, content} ->
    s3_put_object {bucket = "my-bucket", key = path, body = content}
}

# Same code, different storage backend
let result = handle processFiles {files = files} with S3Storage
```

## Advanced Patterns

### Structured Concurrency

```funk
effect Concurrent {
  par : {computations: List<{} -> 'a <'effects>}} -> List<'a> <'effects>
  race : {computations: List<{} -> 'a <'effects>}} -> 'a <'effects>
}

let fastest_response : {urls: List<String>} -> String <AsyncHTTP, Concurrent> = {urls} ->
  Concurrent.race {
    computations = urls.map {url -> {} -> AsyncHTTP.fetch {url = url}}
  }
```

### Resource Management

```funk
effect Resource {
  bracket : {
    acquire: {} -> 'resource <'effects>,
    use: {'resource} -> 'result <'effects>, 
    release: {'resource} -> {} <'effects>
  } -> 'result <'effects>
}

let safe_file_processing : {path: String} -> String <AsyncIO, Resource> = {path} ->
  Resource.bracket {
    acquire = {} -> AsyncIO.openFile {path = path},
    use = {file} -> AsyncIO.readAll {file = file},
    release = {file} -> AsyncIO.closeFile {file = file}
  }
```

### Error Handling

```funk
effect Error {
  throw : {'error} -> 'never
  catch : {
    computation: {} -> 'result <'effects>,
    handler: {'error} -> 'result <'effects>
  } -> 'result <'effects>
}

let robust_fetch : {url: String} -> String <AsyncHTTP, Error> = {url} ->
  Error.catch {
    computation = {} -> AsyncHTTP.fetch {url = url},
    handler = {error} -> "Failed to fetch: " + error.message
  }
```

## Performance Characteristics

### Memory Usage

- **Suspended Computations:** ~200 bytes per suspension point
- **Virtual Threads:** ~1KB overhead per thread  
- **State Machines:** Constant memory usage regardless of suspension duration
- **Effect Handlers:** Zero allocation for synchronous effects

### Throughput

- **Concurrent Operations:** Limited only by system resources, not thread pools
- **Context Switching:** ~10ns overhead for virtual thread switching
- **Effect Calls:** ~50ns overhead for async effects, ~5ns for sync effects
- **State Machine Transitions:** ~20ns per transition

### Latency

- **Suspension/Resumption:** Sub-microsecond for most operations
- **Handler Resolution:** Compile-time optimization eliminates runtime lookup
- **Error Propagation:** Zero-cost across async boundaries

## Debugging and Observability

### Async Stack Traces

```funk
# Automatic stack trace reconstruction across suspensions
do
  let x = AsyncIO.readFile {path = "missing.txt"}  # This will fail
  x + "suffix"

# Stack trace shows:
# at processFile (line 2) 
# at AsyncIO.readFile (FileSystem.kt:45)
# at native_read_file (native code)
```

### Execution Tracing

```funk
# Enable execution tracing
trace {
  let result = handle processFiles {files = files} with FileSystem
  result
}

# Output:
# [Thread-1] State 0: Initialize
# [Thread-1] State 1: Call AsyncIO.readFile("file1.txt") 
# [Thread-1] SUSPEND -> [Thread-2] 
# [Thread-2] State 2: Resume with "file1 content"
# [Thread-2] State 1: Call AsyncIO.readFile("file2.txt")
# [Thread-2] SUSPEND -> [Thread-3]
# ...
```

### Performance Monitoring

```funk
# Built-in performance metrics
monitor {
  let results = handle concurrent_processing {data = large_dataset} with Handlers
  results
}

# Metrics:
# - Total execution time: 1.2s
# - Suspension count: 1,247
# - Virtual threads used: 156  
# - Peak memory: 45MB
# - Effect calls: 3,891 (avg 0.3ms)
```

## Best Practices

### Effect Design

1. **Keep effects focused:** One effect per domain (IO, HTTP, DB, etc.)
2. **Use descriptive operation names:** `readFile` not `read`
3. **Include all necessary parameters:** Avoid global state
4. **Design for composition:** Effects should work well together

### Performance Optimization

1. **Batch operations when possible:** Reduce suspension points
2. **Use structured concurrency:** `par` and `race` for concurrent operations
3. **Implement custom handlers:** Optimize for your specific use case
4. **Profile suspension patterns:** Identify unnecessary async calls

### Error Handling

1. **Use typed errors:** Define specific error types for each effect
2. **Handle errors at appropriate levels:** Not every operation needs error handling
3. **Provide fallback handlers:** Graceful degradation for failed effects
4. **Test error scenarios:** Ensure proper cleanup on failures

## Migration Guide

### From Callback-Based Code

**Before:**
```java
readFile("input.txt", content -> {
    processContent(content, result -> {
        writeFile("output.txt", result, () -> {
            System.out.println("Done");
        });
    });
});
```

**After:**
```funk
do
  let content = AsyncIO.readFile {path = "input.txt"}
  let result = processContent {content = content}
  AsyncIO.writeFile {path = "output.txt", content = result}
  "Done"
```

### From Promise-Based Code

**Before:**
```javascript
fetch(url)
  .then(response => response.text())
  .then(content => processContent(content))
  .then(result => writeFile("output.txt", result))
  .catch(error => console.error(error));
```

**After:**
```funk
Error.catch {
  computation = {} -> do
    let response = AsyncHTTP.fetch {url = url}
    let content = AsyncHTTP.getText {response = response}
    let result = processContent {content = content}
    AsyncIO.writeFile {path = "output.txt", content = result}
  ,
  handler = {error} -> Console.error {message = error.toString()}
}
```

## Conclusion

The Funk language's async effect system provides the benefits of both worlds:

- **Ergonomic:** Write code that looks synchronous
- **Efficient:** Execute with the performance of async systems  
- **Composable:** Effects combine naturally
- **Safe:** Type-checked effect usage
- **Observable:** Built-in debugging and monitoring

The state machine compilation and virtual thread execution ensure that your code scales from simple scripts to high-performance concurrent applications without changing the programming model.
