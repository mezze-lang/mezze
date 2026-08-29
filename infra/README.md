# infra — Cloudflare Pages for mezze-lang.org

Provisions the Cloudflare Pages project, the apex + www custom domains, and the
DNS records that point at them. Pulumi with `@pulumi/cloudflare`.

The site itself (`website/dist/`) is uploaded separately with `wrangler pages
deploy` — see `just deploy` in the root `Justfile`. This stack owns the
plumbing, so a routine deploy holds no permission to destroy infrastructure.

All commands go through the root `Justfile`. Don't run `pulumi` directly — it
needs env vars populated by `sops exec-env secrets.yaml`.

## What you need from Cloudflare

**1. An API token** — dashboard → *My Profile* → *API Tokens* → *Create Token*
→ *Create Custom Token*:

| Category | Permission | Scope |
|---|---|---|
| Account | Cloudflare Pages | Edit | your account |
| Zone | DNS | Edit | zone: `mezze-lang.org` |
| Zone | Page Rules | Edit | zone: `mezze-lang.org` |
| Zone | Zone Settings | Edit | zone: `mezze-lang.org` |

Page Rules is for the www → apex redirect; Zone Settings for Always Use HTTPS.
Cloudflare shows the token once.

**2. Your Account ID** — dashboard sidebar (32-hex).

**3. The Zone ID for `mezze-lang.org`** — the domain's overview page, right
column (32-hex).

## Populate the secrets

```sh
just secrets-edit
```

```yaml
CLOUDFLARE_API_TOKEN:  xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
CLOUDFLARE_ACCOUNT_ID: xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
CLOUDFLARE_ZONE_ID:    xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

Save and close; sops encrypts in place. `secrets.yaml` stays committed — the
encryption travels with it. `.sops.yaml` uses the same age recipient as
shriyans.im, so the private key already at `~/.config/sops/age/keys.txt`
works here.

## Provision

```sh
just infra-install    # once
just infra-preview    # dry-run
just infra-up
```

**No Pulumi Cloud account needed.** devenv sets `PULUMI_BACKEND_URL=file://~`
so state lives in `~/.pulumi/`, and `PULUMI_CONFIG_PASSPHRASE=""` so nothing
prompts. Real secrets never enter Pulumi state — they arrive from sops on each
run. The stack is created on first use.

> ⚠️ Back up `~/.pulumi/`. It holds the resource-ID map; losing it means Pulumi
> can't reconcile what's provisioned against the code.

## Deploy the site

```sh
just build-all    # wasm (if stale) then the site
just deploy       # build + wrangler upload
```

wrangler reads `CLOUDFLARE_API_TOKEN` from the sops-decrypted env.

## Tear down

```sh
just infra-destroy
```

## Known blocker

**The wasm runtime is 55MB and Cloudflare Pages caps a single file at 25MB.**
Untested here — nothing has been deployed yet — but the upload is likely to be
rejected. Options are splitting the module, serving it from R2 and fetching
cross-origin, or asking Cloudflare to lift the cap. Worth settling before the
first deploy rather than during it.
