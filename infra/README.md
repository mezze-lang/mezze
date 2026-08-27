# Infrastructure

Pulumi program for the Cloudflare Pages project behind mezze-lang.org.

It owns the **project and its DNS**, not the uploads. `wrangler pages deploy`
pushes the built site; keeping those separate means a routine deploy has no
permission to destroy infrastructure.

## First run

```sh
npm install
pulumi stack init prod
```

Then the config. The API token needs **Pages:Edit**, plus **Zone:Read** and
**DNS:Edit** on the zone if you want the custom domain:

```sh
pulumi config set --secret cloudflare:apiToken   <token>
pulumi config set mezze:accountId <account-id>
```

Both are on the Cloudflare dashboard — the account id is in the right-hand
sidebar of any zone's overview page.

Bring it up:

```sh
pulumi up
```

That gives you a `*.pages.dev` URL, which is enough to deploy against.

## Adding the domain

The domain is optional so the stack works before DNS is ready. When it is:

```sh
pulumi config set mezze:domain mezze-lang.org
pulumi config set mezze:zoneId <zone-id>
pulumi up
```

This adds the Pages custom domain and two proxied CNAMEs — apex and `www`.
The apex is a CNAME rather than an A record because Cloudflare flattens it at
the zone root.

## Deploying the site

From `website/`:

```sh
npm run build:all      # wasm, then the site
npx wrangler pages deploy dist --project-name mezze-lang
```

`build:all` skips the wasm step when `public/wasm` is newer than the compiler
sources, so it is only slow the first time or after a compiler change.

## Notes

- `Pulumi.<stack>.yaml` is committed. Secrets in it are encrypted by the
  Pulumi backend, and the stack cannot be reproduced without it.
- **The wasm bundle is 55MB.** Cloudflare Pages caps a single file at 25MB,
  so this will be rejected on upload. It has not been deployed yet, so that
  limit is untested here — expect to need one of: splitting the module,
  serving it from R2 and fetching cross-origin, or asking Cloudflare to lift
  the cap. Worth resolving before the first deploy rather than during it.
