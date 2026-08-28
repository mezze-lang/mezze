# mezze-lang.org — everything you'll ever want to run, in one place.
#
# One-time setup:
#   1. your age private key is already at ~/.config/sops/age/keys.txt
#      (.sops.yaml uses the same recipient as shriyans.im)
#   2. `just secrets-edit` — populate the CLOUDFLARE_* values
#   3. `just infra-install && just infra-up` — provision Pages + DNS
#   4. `just deploy` — build + upload
#
# All secrets live in secrets.yaml, encrypted in place by sops (safe to commit).

set shell := ["bash", "-cu"]
set dotenv-load := false

site := "website"
project := "mezze-lang"
stack := "prod"

# bare `just` lists all recipes
default:
    @just --list --unsorted

# ============================================================
# dev / build
# ============================================================

# Dev server
dev:
    cd {{site}} && npm run dev -- --host 0.0.0.0

# Type + template checks
check:
    cd {{site}} && npx astro check

# Static production build → website/dist/
build:
    cd {{site}} && npm run build

# Skipped automatically when public/wasm is newer than the compiler sources.
# Compile the Mezze compiler to WebAssembly (Docker; slow)
wasm:
    cd {{site}} && npm run build:wasm

# Rebuild the WASM runtime even if it looks current
wasm-force:
    cd {{site}} && ./wasm-build/build.sh --force

# The samples are fixed, so their types are extracted once on the JVM rather
# than shipping the 58MB runtime to a page that never runs code (~28KB output).
# Precompute hover types for the landing-page samples
hovers:
    cd {{site}} && ./wasm-build/hovers.sh

#   wasm    compiler -> WebAssembly (Docker, slow; skipped when current)
#   hovers  type hints for the static samples (needs the jars wasm just built)
#   build   astro check + astro build, which embeds both
#
# Use `just build` alone only when the runtime and hover data are current.
# Everything: wasm -> hovers -> site. A full build from a clean checkout
release: wasm hovers build

# Alias kept for muscle memory — same thing as `release`.
build-all: release

# Serve the built site locally
preview: build
    cd {{site}} && npm run preview -- --host 0.0.0.0 --port 4322

# Remove build artifacts (leaves public/wasm — that is expensive to rebuild)
clean:
    cd {{site}} && rm -rf dist .astro

# ============================================================
# secrets — sops + age
# ============================================================

# Edit the encrypted secrets file (creates it on first run per .sops.yaml)
secrets-edit:
    sops secrets.yaml

# Print decrypted secrets to stdout (careful — don't paste this anywhere)
secrets-view:
    @sops -d secrets.yaml

# ============================================================
# infra (Pulumi + Cloudflare)
# ============================================================

# Install infra/ dependencies
infra-install:
    cd infra && npm install

# Pick an existing stack, or create it (idempotent)
_stack:
    @cd infra && (pulumi stack select {{stack}} 2>/dev/null || pulumi stack init {{stack}}) >/dev/null

# Dry-run: show what pulumi would change
infra-preview: _stack
    sops exec-env secrets.yaml 'cd infra && pulumi preview'

# Provision / update Cloudflare Pages project + custom domains + DNS
infra-up: _stack
    sops exec-env secrets.yaml 'cd infra && pulumi up'

# Tear down everything Pulumi created
infra-destroy: _stack
    sops exec-env secrets.yaml 'cd infra && pulumi destroy'

# ============================================================
# deploy
# ============================================================

# Upload the WebAssembly runtime to R2.
#
# Pages rejects files over 25 MiB and the runtime is ~56, so it is served from
# the bucket instead. Both files go together: mezze.js finds its .wasm through
# document.currentScript.src, so they must share an origin.
# Push the runtime to the R2 bucket
push-wasm:
    @cd infra && pulumi stack select {{stack}} >/dev/null 2>&1
    sops exec-env secrets.yaml 'set -euo pipefail; B=$(cd infra && pulumi stack output runtimeBucket); for f in mezze.js mezze.js.wasm; do echo "  uploading $f"; npx --yes wrangler@latest r2 object put "$B/wasm/$f" --file "{{site}}/public/wasm/$f" --remote --cache-control "public, max-age=31536000, immutable"; done'

# Depends on `release`, not `build`: deploying after a bare `build` would ship
# whatever wasm and hover data happened to be lying around, which may predate
# the compiler changes in this checkout. The wasm is stripped from dist/ — it
# is served from R2, and Pages would reject it for size anyway.
# Full pipeline, then upload website/dist/ to Cloudflare Pages
deploy: release push-wasm
    @cd infra && pulumi stack select {{stack}} >/dev/null 2>&1
    sops exec-env secrets.yaml 'set -euo pipefail; URL=$(cd infra && pulumi stack output runtimeUrl); echo "  runtime: $URL"; PUBLIC_WASM_BASE="$URL" npm --prefix {{site}} run build; rm -rf {{site}}/dist/wasm; npx --yes wrangler@latest pages deploy {{site}}/dist --project-name {{project}} --branch main'
# Deploy without a fresh build (uses whatever is in website/dist/)
deploy-only:
    sops exec-env secrets.yaml \
      'npx --yes wrangler@latest pages deploy {{site}}/dist --project-name {{project}} --branch main'
