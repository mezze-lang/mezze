# Mezze — one task runner for the whole repo. Run `just` to see everything.
#
# ENVIRONMENT
#   The toolchain (GraalVM/JDK 25, Kotlin, Node, tree-sitter, just) comes from the
#   devenv shell. direnv (.envrc) loads it automatically, so inside this repo
#   `just <recipe>` just works. From a shell WITHOUT direnv (CI, an agent, a bare
#   terminal), wrap it:
#
#       devenv shell -- just <recipe>          # e.g. devenv shell -- just test
#
# TWO AREAS
#   • the mez compiler — build / run / test        (groups: mez, test)
#   • the website       — mezze-lang.org + playground + Cloudflare deploy
#                         (groups: site, deploy)
#
# WEBSITE one-time setup:
#   1. your age private key is already at ~/.config/sops/age/keys.txt
#      (.sops.yaml uses the same recipient as shriyans.im)
#   2. `just secrets-edit`                    — populate the CLOUDFLARE_* values
#   3. `just infra-install && just infra-up`  — provision Pages + DNS
#   4. `just deploy`                          — build + upload
#   All secrets live in secrets.yaml, encrypted in place by sops (safe to commit).

set shell := ["bash", "-cu"]
set dotenv-load := false

mez     := "cli/build/install/mez/bin/mez"
site    := "website"
project := "mezze-lang"
stack   := "prod"

# bare `just` lists every recipe, grouped
default:
    @just --list

# ============================================================
# compiler — build & run mez
# ============================================================

# Build the mez CLI → cli/build/install/mez/bin/mez
# Also refresh ~/.mezze/lib/mezze-runtime.jar: the launcher searches
# there FIRST when spawning JVM children (mez dev / mez exec), so
# without this step a fresh dev build is shadowed by whatever
# runtime jar the previous `installNative` (or `mez` install) left
# behind — dev changes silently don't take effect.
[group('mez')]
build:
    ./gradlew :cli:installDist :cli:installRuntime
    @echo "→ {{mez}}"

# internal: build the CLI only if the launcher is missing
[private]
_mez:
    @[ -x {{mez}} ] || ./gradlew :cli:installDist :cli:installRuntime

# Run mez with any args:  just mez init hello   ·   just mez exec app.mz
[group('mez')]
mez *args: _mez
    @{{mez}} {{args}}

# Run a single .mz file:  just run app.mz
[group('mez')]
run file: _mez
    {{mez}} exec {{file}}

# Type-check a file or the current project:  just check [path]
[group('mez')]
check *args: _mez
    {{mez}} check {{args}}

# Format source:  just fmt [path]
[group('mez')]
fmt *args: _mez
    {{mez}} fmt {{args}}

# Explain a diagnostic code:  just explain T0205
[group('mez')]
explain code: _mez
    {{mez}} explain {{code}}

# AOT native binary → cli/build/native/nativeCompile/mez
[group('mez')]
native:
    ./gradlew :cli:nativeCompile
    @echo "→ cli/build/native/nativeCompile/mez"

# Build + run the native-smoke fixture as a native binary.
# Catches SVM blocklist violations before merge.
[group('test')]
native-test:
    #!/usr/bin/env bash
    set -euo pipefail
    MEZ=cli/build/native/nativeCompile/mez
    if [ ! -f "$MEZ" ]; then
        echo "Building mez native binary first…"
        ./gradlew :cli:nativeCompile
    fi
    WORKDIR=$(mktemp -d)
    trap 'rm -rf "$WORKDIR"' EXIT
    cp -r fixtures/native-smoke/* "$WORKDIR/"
    cd "$WORKDIR"
    echo "Building native-smoke with --native…"
    "$OLDPWD/$MEZ" build --native 2>&1 | tee native-build.log
    if ! grep -q 'native' native-build.log; then
        echo "FAIL: native binary not produced"
        exit 1
    fi
    echo "Running native binary…"
    ./.build/native_smoke-native
    echo "✓ native-test passed"

# gradle clean (compiler build outputs)
[group('mez')]
clean:
    ./gradlew clean

# ============================================================
# compiler — tests
# ============================================================
# The suites are heavy and share a lot of RAM: always run each as its OWN
# --no-daemon invocation, never one `gradle test`. Output is tee'd to logs/.

# One module's suite → logs/<module>-test.log :  just test-one typechecker
[group('test')]
test-one module:
    @mkdir -p logs
    ./gradlew :{{module}}:test --no-daemon 2>&1 | tee logs/{{module}}-test.log

# The usual set: parser + typechecker + interpreter (separate, logged)
[group('test')]
test: (test-one "parser") (test-one "typechecker") (test-one "interpreter")

# Full gate: the three above + cli
[group('test')]
gate: test (test-one "cli")

# End-to-end CLI smoke: check/exec/dev/build(jar)/build --native against a real app.
# Needs a built mez (`just build`). MEZ_E2E_NATIVE=0 skips the slow native step.
[group('test')]
test-cli:
    @mkdir -p logs
    bash scripts/cli-e2e.sh 2>&1 | tee logs/cli-e2e.log

# Toolchain-proxy dispatch: real ~/.mezze/bin/mez proxy → stub toolchains,
# every precedence rule. Needs the native CLI (`./gradlew :cli:nativeCompile`).
[group('test')]
test-proxy:
    @mkdir -p logs
    bash scripts/proxy-e2e.sh 2>&1 | tee logs/proxy-e2e.log

# Multi-version distribution: install two REAL toolchains side by side and check
# every selection mechanism (default / Mezze.toml pin / +ver / MEZ_VERSION / remove).
# Needs two installed versions, e.g. `-Pversion=0.1.0` and `-Pversion=0.2.0 :cli:installNative`.
[group('test')]
test-dist:
    @mkdir -p logs
    bash scripts/dist-e2e.sh 2>&1 | tee logs/dist-e2e.log

# Package management: a transitive local dep graph (app → greet → core) that must
# type-check AND run. Needs a built mez (`just build`).
[group('test')]
test-pkg:
    @mkdir -p logs
    bash scripts/pkg-e2e.sh 2>&1 | tee logs/pkg-e2e.log

# Package IR reuse (#46): a dep precompiled once gets the SAME function hashes a
# consumer's compile produces (the fromStore reuse invariant). Needs `just build`.
[group('test')]
test-pkg-reuse:
    @mkdir -p logs
    bash scripts/pkg-reuse-e2e.sh 2>&1 | tee logs/pkg-reuse-e2e.log

# Just one class:  just test-class typechecker '*AntiHang*'
[group('test')]
test-class module pattern:
    @mkdir -p logs
    ./gradlew :{{module}}:test --no-daemon --tests '{{pattern}}' 2>&1 | tee logs/{{module}}-{{pattern}}.log

# Install numpy 1.26.4 into the GraalPy venv (idempotent). The venv
# itself must already exist at interpreter/build/graalpy-venv/venv/
# — historically the org.graalvm.python Gradle plugin creates it,
# but the plugin's default parallel Meson build OOMs numpy's AVX2
# dispatch step. `-j1` caps compile jobs to one; the build takes
# ~25 min but succeeds. pip is a no-op when numpy is already
# installed, so re-running this recipe is safe.
#
# If the venv is missing, follow the manual bootstrap in
# examples/polyglot_numpy/README.md.
[group('mez')]
polyglot-setup-python:
    @mkdir -p logs
    @if [ ! -x interpreter/build/graalpy-venv/venv/bin/graalpy ]; then \
        echo "ERROR: GraalPy venv missing at interpreter/build/graalpy-venv/venv/"; \
        echo "       See examples/polyglot_numpy/README.md for the bootstrap steps."; \
        exit 1; \
    fi
    interpreter/build/graalpy-venv/venv/bin/graalpy -m pip install \
        --no-cache-dir \
        --config-settings=setup-args=-Dblas=none \
        --config-settings=setup-args=-Dlapack=none \
        --config-settings=compile-args=-j1 \
        'numpy==1.26.4' 2>&1 | tee logs/polyglot-setup.log | tail -n 20

# Run the polyglot numpy demo (Mezze x numpy). Requires numpy in a
# GraalPy venv on the classpath — bootstrap it with
# `just polyglot-setup-python`. Uses `mez exec` under the JVM.
#
# Wiring lives entirely in `examples/polyglot_numpy/Mezze.toml`
# ([polyglot.python] with python_path + options). No env vars — the
# runtime walks up from the script's dir, finds the manifest, and
# turns on GraalPy iff the section is present.
[group('mez')]
mez-run-numpy-demo: _mez
    {{mez}} exec examples/polyglot_numpy/src/app.mz

# Publish one polyglot-native jar into a local mezze-maven checkout.
# Standard maven layout — jar, pom, sha1s, and a per-artifact
# maven-metadata.xml at `<group>/<artifact>/`. The checkout at
# `<repo-dir>` is then pushed to
# git@github.com:mezze-lang/mezze-maven.git; consumers reach the
# published bytes via `raw.githubusercontent.com/mezze-lang/mezze-maven/main/`.
#
# Args:  group  artifact  version  src-jar  repo-dir
# Example:
#   just publish-maven \
#       dev.mezze polyglot-python-natives 0.2.0 \
#       polyglot-python-natives/build/libs/polyglot-python-natives-0.2.0.jar \
#       ../mezze-maven
[group('mez')]
publish-maven group artifact version srcjar repo_dir:
    #!/usr/bin/env bash
    set -euo pipefail
    # Just does NOT export recipe params as env vars (as of 1.46) — pull the
    # template substitution into a shell var first, then transform dots to
    # slashes for the maven directory layout.
    group="{{group}}"
    group_path="${group//./\/}"
    target_dir="{{repo_dir}}/${group_path}/{{artifact}}/{{version}}"
    mkdir -p "$target_dir"
    jar="$target_dir/{{artifact}}-{{version}}.jar"
    pom="$target_dir/{{artifact}}-{{version}}.pom"
    cp "{{srcjar}}" "$jar"
    cat > "$pom" <<POM
    <?xml version="1.0" encoding="UTF-8"?>
    <project xmlns="http://maven.apache.org/POM/4.0.0">
      <modelVersion>4.0.0</modelVersion>
      <groupId>{{group}}</groupId>
      <artifactId>{{artifact}}</artifactId>
      <version>{{version}}</version>
      <packaging>jar</packaging>
      <name>Mezze polyglot {{artifact}}</name>
    </project>
    POM
    for f in "$jar" "$pom"; do
        printf '%s' "$(sha1sum "$f" | awk '{print $1}')" > "$f.sha1"
        printf '%s' "$(md5sum  "$f" | awk '{print $1}')" > "$f.md5"
    done
    meta="{{repo_dir}}/${group_path}/{{artifact}}/maven-metadata.xml"
    now="$(date -u +%Y%m%d%H%M%S)"
    cat > "$meta" <<META
    <?xml version="1.0" encoding="UTF-8"?>
    <metadata>
      <groupId>{{group}}</groupId>
      <artifactId>{{artifact}}</artifactId>
      <versioning>
        <latest>{{version}}</latest>
        <release>{{version}}</release>
        <versions>
          <version>{{version}}</version>
        </versions>
        <lastUpdated>$now</lastUpdated>
      </versioning>
    </metadata>
    META
    printf '%s' "$(sha1sum "$meta" | awk '{print $1}')" > "$meta.sha1"
    printf '%s' "$(md5sum  "$meta" | awk '{print $1}')" > "$meta.md5"
    echo "→ published {{group}}:{{artifact}}:{{version}}"
    echo "  jar:  $jar"
    echo "  pom:  $pom"
    echo "  meta: $meta"
    echo "Next: cd {{repo_dir}} && git add -A && git commit -m 'publish {{artifact}} {{version}}' && git push"

# Run the pure-Python-stdlib demo (Mezze x math/statistics/functools).
# No venv required — GraalPy ships stdlib as classpath resources.
# Serves as the polyglot smoke test that works out-of-the-box.
[group('mez')]
mez-run-python-pure-demo: _mez
    {{mez}} exec examples/polyglot_python_pure/src/app.mz

# Run the polyglot JS demo (Mezze x GraalJS). Uses ECMAScript builtins
# (Math, JSON, Array reductions, Date) — no node_modules required.
# Serves as the polyglot smoke test that works out-of-the-box.
#
# Wiring lives entirely in `examples/polyglot_js/Mezze.toml`
# ([polyglot.js] presence-only). No env vars — the runtime walks up
# from the script's dir, finds the manifest, and turns on GraalJS iff
# the section is present.
[group('mez')]
mez-run-js-demo: _mez
    {{mez}} exec examples/polyglot_js/src/app.mz

# Run the polyglot Wasm demo (Mezze x GraalWasm). Assembles an 85-byte
# wasm module in memory, invokes its exported i32 functions, and
# marshals bytes through its linear memory.
#
# Wiring lives entirely in `examples/polyglot_wasm/Mezze.toml`
# ([polyglot.wasm] presence-only). No env vars — the runtime walks up
# from the script's dir, finds the manifest, and turns on GraalWasm iff
# the section is present.
[group('mez')]
mez-run-wasm-demo: _mez
    {{mez}} exec examples/polyglot_wasm/src/app.mz

# Build the numpy demo as a native binary + run it. Requires the
# GraalPy venv with numpy at interpreter/build/graalpy-venv/venv/.
# native-image consumes ~10 GB RAM and takes ~2 min. The venv (185 MB)
# is copied alongside the binary; both must ship together for the
# binary to run outside the project.
[group('mez')]
native-numpy-demo: _mez
    @mkdir -p logs
    cd examples/polyglot_numpy && \
      MEZZE_TIMING=1 {{absolute_path("./" + mez)}} build --native \
      2>&1 | tee -a $(pwd)/../../logs/native-numpy-demo.log
    examples/polyglot_numpy/.build/polyglot_numpy-native \
      2>&1 | tee -a logs/native-numpy-demo.log

# Build the pure-Python-stdlib demo as a native binary + run it.
# No venv needed; the binary is a single-file drop (with an empty
# marker sidecar dir for polyglot python discovery).
[group('mez')]
native-python-pure-demo: _mez
    @mkdir -p logs
    cd examples/polyglot_python_pure && \
      MEZZE_TIMING=1 {{absolute_path("./" + mez)}} build --native \
      2>&1 | tee -a $(pwd)/../../logs/native-python-pure-demo.log
    examples/polyglot_python_pure/.build/polyglot_python_pure-native \
      2>&1 | tee -a logs/native-python-pure-demo.log

# ============================================================
# tooling
# ============================================================

# Generate + build the tree-sitter-mezze grammar
[group('tooling')]
ts-build:
    cd tooling/tree-sitter-mezze && tree-sitter generate && tree-sitter build

# Run the tree-sitter corpus tests
[group('tooling')]
ts-test:
    cd tooling/tree-sitter-mezze && tree-sitter generate && tree-sitter test

# Package + install the VSCode extension into your local VSCode
[group('tooling')]
vscode-install:
    cd tooling/vscode-extension/extension && \
      npx vsce package --allow-missing-repository && \
      code --install-extension "$(ls -t *.vsix | head -1)" --force

# CLI smoke tests (docker-test-project)
[group('tooling')]
smoke-tests *args:
    ./docker-test-project/run-tests.sh {{args}}

# ============================================================
# website — dev / build
# ============================================================

# Website dev server
[group('site')]
site-dev:
    cd {{site}} && npm run dev -- --host 0.0.0.0

# astro type + template checks
[group('site')]
site-check:
    cd {{site}} && npx astro check

# Static production build → website/dist/
[group('site')]
site-build:
    cd {{site}} && npm run build

# Compile the Mezze compiler to WebAssembly (Docker; slow; skipped when current)
[group('site')]
wasm:
    cd {{site}} && npm run build:wasm

# Rebuild the WASM runtime even if it looks current
[group('site')]
wasm-force:
    cd {{site}} && ./wasm-build/build.sh --force

# Precompute hover types for the landing-page samples (needs the jars wasm built)
[group('site')]
hovers:
    cd {{site}} && ./wasm-build/hovers.sh

# Everything: wasm -> hovers -> site build (a full build from a clean checkout)
[group('site')]
release: wasm hovers site-build

# Serve the built site locally
[group('site')]
site-preview: site-build
    cd {{site}} && npm run preview -- --host 0.0.0.0 --port 4322

# Remove website build artifacts (leaves public/wasm — expensive to rebuild)
[group('site')]
site-clean:
    cd {{site}} && rm -rf dist .astro

# ============================================================
# deploy — secrets / infra / release  (Cloudflare via Pulumi)
# ============================================================

# Edit the encrypted secrets file (creates it on first run per .sops.yaml)
[group('deploy')]
secrets-edit:
    sops secrets.yaml

# Print decrypted secrets to stdout (careful — don't paste this anywhere)
[group('deploy')]
secrets-view:
    @sops -d secrets.yaml

# Install infra/ dependencies
[group('deploy')]
infra-install:
    cd infra && npm install

# internal: pick or create the pulumi stack (idempotent)
[private]
_stack:
    @cd infra && (pulumi stack select {{stack}} 2>/dev/null || pulumi stack init {{stack}}) >/dev/null

# Dry-run: show what pulumi would change
[group('deploy')]
infra-preview: _stack
    sops exec-env secrets.yaml 'cd infra && pulumi preview'

# Provision / update Cloudflare Pages project + custom domains + DNS
[group('deploy')]
infra-up: _stack
    sops exec-env secrets.yaml 'cd infra && pulumi up'

# Tear down everything Pulumi created
[group('deploy')]
infra-destroy: _stack
    sops exec-env secrets.yaml 'cd infra && pulumi destroy'

# Push the compressed WASM runtime to the R2 bucket (Pages rejects >25 MiB).
# Both mezze.js and mezze.js.wasm go together — they must share an origin.
#
# Uploaded under wasm/<stamp>/, where <stamp> is the content hash of the
# UNCOMPRESSED runtime (tools/wasm-stamp.mjs). The objects are served
# `immutable` for a year, so the URL MUST change when the bytes do — otherwise a
# returning visitor keeps the cached old runtime forever (which is exactly the
# cache-busting failure this fixes). `deploy` stamps the same value into
# PUBLIC_WASM_STAMP so the HTML requests this path; the stamp is deterministic,
# so the two agree even when the recipes run separately.
[group('deploy')]
push-wasm:
    @cd infra && pulumi stack select {{stack}} >/dev/null 2>&1
    node {{site}}/tools/compress-wasm.mjs
    sops exec-env secrets.yaml 'set -euo pipefail; B=$(cd infra && pulumi stack output runtimeBucket); S=$(node {{site}}/tools/wasm-stamp.mjs); echo "  stamp: $S"; for f in mezze.js mezze.js.wasm; do echo "  uploading wasm/$S/$f"; if [ "$f" = mezze.js.wasm ]; then CT=application/wasm; else CT=text/javascript; fi; npx --yes wrangler@latest r2 object put "$B/wasm/$S/$f" --file "{{site}}/public/wasm/.br/$f" --remote --content-encoding br --content-type "$CT" --cache-control "public, max-age=31536000, immutable"; done'

# Full pipeline: build the runtime, upload it, then build+deploy the site against it.
# Order matters: the runtime must be live at PUBLIC_WASM_BASE before the HTML ships.
# PUBLIC_WASM_STAMP is the content hash push-wasm just uploaded under wasm/<stamp>/;
# stamping it into the build makes the HTML request that exact (cache-busted) path.
[group('deploy')]
deploy: wasm hovers push-wasm
    @cd infra && pulumi stack select {{stack}} >/dev/null 2>&1
    sops exec-env secrets.yaml 'set -euo pipefail; URL=$(cd infra && pulumi stack output runtimeUrl); S=$(node {{site}}/tools/wasm-stamp.mjs); echo "  runtime: $URL  stamp: $S"; PUBLIC_WASM_BASE="$URL" PUBLIC_WASM_STAMP="$S" npm --prefix {{site}} run build; rm -rf {{site}}/dist/wasm; npx --yes wrangler@latest pages deploy {{site}}/dist --project-name {{project}} --branch main'

# Deploy without a fresh build (uses whatever is in website/dist/)
[group('deploy')]
deploy-only:
    sops exec-env secrets.yaml \
      'npx --yes wrangler@latest pages deploy {{site}}/dist --project-name {{project}} --branch main'
