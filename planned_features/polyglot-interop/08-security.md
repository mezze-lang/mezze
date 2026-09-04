# Security Considerations

This document covers security aspects of the polyglot interop system.

References:
- [GraalVM Sandboxing Guide](https://www.graalvm.org/latest/security-guide/sandboxing/)
- [Polyglot Sandbox Policies](https://www.graalvm.org/jdk20/security-guide/polyglot-sandbox/)
- [Context.Builder API](https://www.graalvm.org/sdk/javadoc/org/graalvm/polyglot/Context.Builder.html)

## Threat Model

### What Foreign Code Can Do

By default with `allowAllAccess(true)`, foreign code can:
- Read/write files on the filesystem
- Make network connections
- Access environment variables
- Execute system processes
- Access all Java classes
- Consume unlimited CPU and memory

### Attack Vectors

1. **Malicious packages** - pip/npm packages with malicious code
2. **Code injection** - User input passed to `eval` unsanitized
3. **Resource exhaustion** - Infinite loops, memory bombs
4. **Data exfiltration** - Sending sensitive data over network
5. **Supply chain** - Compromised dependencies

## Sandbox Policies

GraalVM provides four sandbox policies (from least to most restrictive):

### TRUSTED (Default)

```kotlin
Context.newBuilder("python")
    .allowAllAccess(true)  // Full access to host
    .build()
```

Use for: Fully trusted code, development, internal tools.

### CONSTRAINED

```kotlin
Context.newBuilder("python")
    .sandbox(SandboxPolicy.CONSTRAINED)
    .out(outputStream)  // Must redirect output
    .err(errorStream)
    .build()
```

Restrictions:
- No native access
- No process creation
- No system exit
- No direct file/socket access
- No environment access
- No host class loading

Use for: Trusted but potentially buggy applications.

### ISOLATED

```kotlin
Context.newBuilder("python")
    .sandbox(SandboxPolicy.ISOLATED)
    .out(outputStream)
    .err(errorStream)
    .build()
```

Additional restrictions:
- Separate VM instance
- Stricter resource limits

Use for: Processing untrusted input with trusted code.

### UNTRUSTED

```kotlin
Context.newBuilder("python")
    .sandbox(SandboxPolicy.UNTRUSTED)
    .out(outputStream)
    .err(errorStream)
    .build()
```

Additional hardening:
- JIT spraying mitigations
- Speculative execution protections

Use for: Running untrusted code (e.g., user-submitted scripts).

**Note:** UNTRUSTED policy is only available in GraalVM Enterprise Edition.

## Mezze Security Levels

### Handler Security Parameter

Extend handler to accept security level:

```mezze
perform GraalPy { context = ctx, security = Trusted } in ...
perform GraalPy { context = ctx, security = Constrained } in ...
perform GraalPy { context = ctx, security = Isolated } in ...
```

### SecurityLevel Type

```mezze
pub type SecurityLevel =
    | Trusted        # Full access (default, for development)
    | Constrained    # Limited access (for production)
    | Isolated       # Separate VM (for untrusted input)
```

### Context Creation with Security

```mezze
# In python module
pub let create_context: { security: SecurityLevel } -> Context = { security } ->
    __native_python_create_context_with_security { security = security }
```

```kotlin
// Runtime implementation
fun createContextWithSecurity(language: String, security: String): MezzePolyglotContext {
    val builder = Context.newBuilder(language)

    when (security) {
        "Trusted" -> builder.allowAllAccess(true)
        "Constrained" -> {
            builder.sandbox(SandboxPolicy.CONSTRAINED)
                .out(ByteArrayOutputStream())
                .err(ByteArrayOutputStream())
        }
        "Isolated" -> {
            builder.sandbox(SandboxPolicy.ISOLATED)
                .out(ByteArrayOutputStream())
                .err(ByteArrayOutputStream())
        }
    }

    return MezzePolyglotContext(builder.build(), language)
}
```

## Resource Limits

### CPU Time Limit

Prevent infinite loops:

```kotlin
Context.newBuilder("python")
    .option("sandbox.MaxCPUTime", "10s")  // Max 10 seconds
    .option("sandbox.MaxCPUTimeCheckInterval", "100ms")  // Check every 100ms
    .build()
```

In Mezze:

```mezze
perform GraalPy { context = ctx, timeout = 10000 } in  # 10 seconds
    Python.eval { code = potentially_slow_code }
```

### Memory Limit

Prevent memory bombs:

```kotlin
Context.newBuilder("python")
    .option("sandbox.MaxHeapMemory", "256m")  // Max 256MB
    .build()
```

In Mezze:

```mezze
perform GraalPy { context = ctx, memory = 256 } in  # 256 MB
    Python.eval { code = potentially_memory_intensive }
```

### Statement Limit

Limit number of statements executed:

```kotlin
Context.newBuilder("python")
    .option("sandbox.MaxStatements", "1000000")  // Max 1M statements
    .build()
```

### Output Limit

Limit output size:

```kotlin
Context.newBuilder("python")
    .option("sandbox.MaxOutputStreamSize", "1m")  // Max 1MB output
    .build()
```

## Input Sanitization

### Never Pass Unsanitized User Input to eval

```mezze
# ❌ DANGEROUS - SQL injection equivalent
let user_input = get_user_input {}
Python.eval { code = "process('$(user_input)')" }

# ✅ SAFE - Use bindings instead
let user_input = get_user_input {}
Python.eval_with {
    code = "process(user_input)",
    bindings = { user_input = user_input }
}
```

### Validate Foreign Package Names

```mezze
# ❌ DANGEROUS - Arbitrary package installation
let pkg = get_user_input {}
Python.eval { code = "import $(pkg)" }

# ✅ SAFE - Allowlist of packages
let allowed = ["numpy", "pandas", "scipy"]
let pkg = get_user_input {}
if list::contains { list = allowed, elem = pkg } then
    Python.eval { code = "import $(pkg)" }
else
    Err { error = "Package not allowed" }
```

## File System Access

### Custom FileSystem

Restrict file access to specific directories:

```kotlin
Context.newBuilder("python")
    .allowIO(IOAccess.newBuilder()
        .fileSystem(FileSystem.newReadOnlyFileSystem(
            FileSystem.newDefaultFileSystem()
        ))
        .build())
    .build()
```

### Path Restrictions

```kotlin
Context.newBuilder("python")
    .currentWorkingDirectory(Path.of("/sandbox/work"))
    .allowIO(IOAccess.newBuilder()
        .allowHostFileAccess(false)
        .build())
    .build()
```

## Network Access

### Disable Network

```kotlin
Context.newBuilder("python")
    .allowIO(IOAccess.newBuilder()
        .allowHostSocketAccess(false)
        .build())
    .build()
```

### Allowlist Hosts

For constrained network access, implement a custom socket filter (requires custom code).

## Host Access Control

### Disable All Host Access

```kotlin
Context.newBuilder("python")
    .allowHostAccess(HostAccess.NONE)
    .build()
```

### Explicit Host Access

Only allow specific Java classes/methods:

```kotlin
val hostAccess = HostAccess.newBuilder()
    .allowAccess(SomeClass::class.java.getMethod("safeMethod"))
    .build()

Context.newBuilder("python")
    .allowHostAccess(hostAccess)
    .build()
```

## Mezze.toml Security Configuration

### Project-Level Defaults

```toml
[polyglot.python]
interop = "..."
extras = ["numpy"]

[polyglot.python.security]
level = "constrained"  # Default security level
timeout = 30000        # Default timeout (ms)
memory = 512           # Default memory limit (MB)
allow_network = false  # Disable network
allow_files = ["/data/*", "/tmp/*"]  # Allowed paths
```

### Per-Operation Override

Handler can override defaults:

```mezze
# Use project defaults
python::scoped { ctx ->
    perform GraalPy { context = ctx } in ...
}

# Override for specific operation
python::scoped { ctx ->
    perform GraalPy { context = ctx, security = Trusted, timeout = 60000 } in
        Python.eval { code = trusted_long_running_code }
}
```

## Security Checklist

### Development

- [ ] Use `Trusted` security level for convenience
- [ ] Set reasonable timeouts to catch infinite loops
- [ ] Use bindings instead of string interpolation in eval

### Production

- [ ] Use `Constrained` or `Isolated` security level
- [ ] Set strict timeout and memory limits
- [ ] Disable network access if not needed
- [ ] Restrict file system access
- [ ] Validate/sanitize all user input
- [ ] Audit all foreign dependencies
- [ ] Pin dependency versions in Mezze.lock
- [ ] Use checksum verification for packages

### High Security (Untrusted Code)

- [ ] Use GraalVM Enterprise with `UNTRUSTED` policy
- [ ] Run in isolated VM/container
- [ ] Strict resource limits
- [ ] No file/network access
- [ ] Log all operations for audit
- [ ] Consider additional sandboxing (Docker, gVisor, etc.)

## Known Limitations

1. **Community Edition** - `UNTRUSTED` policy only in Enterprise Edition
2. **Native extensions** - Some packages (numpy with native code) may bypass sandboxing
3. **Resource limits** - Check intervals mean limits aren't exact
4. **Shared memory** - `CONSTRAINED` still shares heap with host

## Best Practices

1. **Principle of least privilege** - Only grant access that's needed
2. **Defense in depth** - Layer multiple security controls
3. **Fail secure** - Default to restrictive settings
4. **Audit dependencies** - Review packages before adding
5. **Update regularly** - Keep GraalVM and packages updated
6. **Monitor runtime** - Log and alert on suspicious activity
