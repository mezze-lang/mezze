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

# Compile the Mezze compiler to WebAssembly (Docker; 20-40 min).
# Skipped automatically when public/wasm is newer than the compiler sources.
wasm:
    cd {{site}} && npm run build:wasm

# Rebuild the WASM runtime even if it looks current
wasm-force:
    cd {{site}} && ./wasm-build/build.sh --force

# WASM (if stale) then the site — a full build from a clean checkout
build-all:
    cd {{site}} && npm run build:all

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

# Build the site and upload website/dist/ to Cloudflare Pages
deploy: build
    sops exec-env secrets.yaml \
      'npx --yes wrangler@latest pages deploy {{site}}/dist --project-name {{project}} --branch main'

# Deploy without a fresh build (uses whatever is in website/dist/)
deploy-only:
    sops exec-env secrets.yaml \
      'npx --yes wrangler@latest pages deploy {{site}}/dist --project-name {{project}} --branch main'
