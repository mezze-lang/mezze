# Dependency Management

## Mezze.toml Schema

```toml
[package]
name = "my_app"
version = "1.0.0"

[lib]
path = "src/lib.mz"

[[app]]
name = "my_app"
path = "src/app.mz"

# All Mezze packages (pure + wrappers)
[dependencies]
json = "https://github.com/mezze-lang/json/releases/download/v1.2.0/json.tar.gz#sha256:abc123..."
mezze-numpy = "https://github.com/mezze-lang/numpy/releases/download/v1.0.0/numpy.tar.gz#sha256:def456..."

# Polyglot configuration per language
[polyglot.python]
interop = "https://github.com/mezze-lang/python/releases/download/v1.0.0/python.tar.gz#sha256:..."
extras = ["requests>=2.28.0", "pandas==2.0.0"]

[polyglot.javascript]
interop = "https://github.com/mezze-lang/js/releases/download/v1.0.0/js.tar.gz#sha256:..."
extras = ["dayjs@1.11.0", "lodash@4.17.21"]

[polyglot.java]
interop = "https://github.com/mezze-lang/java/releases/download/v1.0.0/java.tar.gz#sha256:..."
extras = ["com.google.code.gson:gson:2.10"]
```

**Key points:**
- `[dependencies]` - All Mezze packages including wrappers like `mezze-numpy`
- `[polyglot.{lang}]` - One section per language
- `interop` - The interop library (exactly one version per language)
- `extras` - Raw foreign packages (pip, npm, maven format)
- Wrapper packages (e.g., `mezze-numpy`) must check that `[polyglot.python]` exists with compatible interop

## Directory Structure

```
project/
├── Mezze.toml              # Project manifest
├── Mezze.lock              # Locked versions (all deps)
├── src/
│   └── lib.mz
├── .deps/                 # ALL dependencies (gitignored)
│   ├── mezze/              # Mezze packages
│   │   ├── json-1.2.0/
│   │   ├── mezze-numpy-1.0.0/
│   │   └── mezze-python-1.0.0/
│   ├── python/            # Python packages (pip --target)
│   │   ├── requests/
│   │   └── pandas/
│   ├── node/              # Node packages (npm --prefix)
│   │   └── node_modules/
│   │       ├── dayjs/
│   │       └── lodash/
│   └── java/              # Java JARs
│       └── gson-2.10.jar
│
└── .mz/                 # Tooling only (not deps)
    ├── cache/             # Build cache
    └── lsp/               # LSP data

~/.mz/                   # Global user directory
├── cache/                 # Downloaded archives (shared across projects)
│   └── mezze/
│       ├── json-1.2.0.tar.gz
│       └── mezze-numpy-1.0.0.tar.gz
└── config.toml            # Global user config
```

## Interop Library Manifest

Each interop library declares how to install its language:

```toml
# mezze-python/Mezze.toml
[package]
name = "mezze-python"
version = "1.0.0"

[lib]
path = "src/lib.mz"

[polyglot.provider]
language_id = "python"
graalvm_component = "python"
install_command = "graalpy -m pip install --target {dest} {packages}"
runtime_path = "PYTHONPATH"
```

```toml
# mezze-js/Mezze.toml
[package]
name = "mezze-js"
version = "1.0.0"

[lib]
path = "src/lib.mz"

[polyglot.provider]
language_id = "js"
graalvm_component = "js"
install_command = "npm install --prefix {dest} {packages}"
runtime_path = "NODE_PATH"
```

## mezze sync Flow

```
$ mezze sync

Syncing dependencies...
✓ Downloaded json-1.2.0
✓ Downloaded mezze-numpy-1.0.0
✓ Downloaded mezze-python-1.0.0 (interop)

Polyglot: Python
  GraalPy not found, installing...
  Running: gu install python
  ✓ GraalPy installed

  Installing extras...
  Running: graalpy -m pip install --target .deps/python/ requests>=2.28.0 pandas==2.0.0
  ✓ Installed requests
  ✓ Installed pandas

Polyglot: JavaScript
  GraalJS not found, installing...
  Running: gu install js
  ✓ GraalJS installed

  Installing extras...
  Running: npm install --prefix .deps/node/ dayjs@1.11.0 lodash@4.17.21
  ✓ Installed dayjs
  ✓ Installed lodash

✓ Sync complete
```

### Error: gu not found

```
$ mezze sync

Syncing dependencies...
✓ Downloaded json-1.2.0
✓ Downloaded mezze-numpy-1.0.0

Polyglot: Python
✗ GraalVM Updater (gu) not found

To use polyglot features, install GraalVM and ensure 'gu' is in your PATH:
  https://www.graalvm.org/downloads/

Then run 'mezze sync' again.
```

### Detailed Flow

```
┌─────────────────────────────────────────────────────────────┐
│  1. Parse Mezze.toml                                         │
│     - Read [dependencies]                                   │
│     - Read [polyglot.*] sections                            │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  2. Resolve Mezze dependency graph                           │
│     - All [dependencies]                                    │
│     - All [polyglot.*.interop] packages                     │
│     - Transitive deps from all packages                     │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  3. Download Mezze packages                                  │
│     - Check ~/.mz/cache/ first                            │
│     - Download missing to cache                             │
│     - Verify SHA256 checksums                               │
│     - Extract to .deps/mezze/{name}-{version}/               │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  4. For each [polyglot.{lang}] section:                     │
│                                                             │
│     a. Check if 'gu' is available                           │
│        - If not: print error, skip this language            │
│                                                             │
│     b. Read interop library's [polyglot.provider]           │
│        - Get graalvm_component name                         │
│        - Get install_command template                       │
│                                                             │
│     c. Check if GraalVM component installed                 │
│        - Run: gu list | grep {component}                    │
│        - If missing: gu install {component}                 │
│                                                             │
│     d. Install extras                                       │
│        - Run install_command with:                          │
│          {dest} = .deps/{lang}/                             │
│          {packages} = extras joined                         │
│                                                             │
│     e. Collect extras from wrapper packages                 │
│        - Read [foreign.{lang}] from mezze-numpy etc          │
│        - Install those too                                  │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  5. Write Mezze.lock                                         │
│     - All Mezze package versions + checksums                 │
│     - All extras versions (as installed)                    │
└─────────────────────────────────────────────────────────────┘
```

## Wrapper Package Dependencies

Wrapper packages like `mezze-numpy` can declare foreign dependencies they need:

```toml
# mezze-numpy/Mezze.toml
[package]
name = "mezze-numpy"
version = "1.0.0"

[lib]
path = "src/lib.mz"

# Requires polyglot.python with compatible interop
[polyglot.requires]
python = ">=1.0.0"  # Minimum interop version

# Foreign packages this wrapper needs
[foreign.python]
packages = ["numpy>=1.24.0"]
```

When `mezze sync` processes `mezze-numpy`:
1. Checks `[polyglot.python]` exists in user's Mezze.toml
2. Checks interop version satisfies `>=1.0.0`
3. Adds `numpy>=1.24.0` to Python extras to install

## Mezze.lock Schema

```toml
# Auto-generated by mezze sync - do not edit

[mezze]
json = { version = "1.2.0", checksum = "sha256:abc123..." }
mezze-numpy = { version = "1.0.0", checksum = "sha256:def456..." }
mezze-python = { version = "1.0.0", checksum = "sha256:..." }

[python]
numpy = "1.24.0"
requests = "2.28.2"
pandas = "2.0.0"

[javascript]
dayjs = "1.11.0"
lodash = "4.17.21"
```

## Runtime Configuration

When Mezze interpreter creates a polyglot context, it sets paths from interop library config:

| Language | Path Setting |
|----------|-------------|
| Python | `PYTHONPATH=.deps/python/` or context option |
| JavaScript | `NODE_PATH=.deps/node/node_modules/` or `js.commonjs-require-cwd` |
| R | `R_LIBS_USER=.deps/r/` |
| Ruby | `GEM_HOME=.deps/ruby/` |
| Java | Add `.deps/java/*.jar` to classpath |

## CLI Commands

```bash
# Install all dependencies
mezze sync

# Clean reinstall
mezze sync --clean

# Verbose output
mezze sync --verbose
```

## .gitignore

```gitignore
# Dependencies
.deps/

# Tooling cache
.mz/
```
