// Provision the Cloudflare Pages project, the apex + www custom domains, and
// the DNS that points at them, for mezze-lang.org.
//
// The built site (website/dist/) is uploaded separately by wrangler — see
// `just deploy`. This stack owns the plumbing, not the deploy artifacts, so a
// routine deploy holds no permission to destroy infrastructure.
//
// Config comes from environment variables, populated by `sops exec-env`:
//   CLOUDFLARE_API_TOKEN    — auto-detected by the cloudflare provider
//   CLOUDFLARE_ACCOUNT_ID   — the account owning the Pages project and zone
//   CLOUDFLARE_ZONE_ID      — the zone id for mezze-lang.org

import * as pulumi from '@pulumi/pulumi'
import * as cloudflare from '@pulumi/cloudflare'

function required(name: string): string {
  const v = process.env[name]
  if (!v) throw new Error(`${name} is not set (run 'just infra-up' — it loads secrets via sops)`)
  return v
}

const accountId = required('CLOUDFLARE_ACCOUNT_ID')
const zoneId = required('CLOUDFLARE_ZONE_ID')
const domain = 'mezze-lang.org'
const project = 'mezze-lang' // must match `wrangler pages deploy --project-name`
const managed = 'managed by pulumi · mezze-lang.org' // tag on every DNS record

// 1. Pages project, direct-upload mode (no git integration).
const pages = new cloudflare.PagesProject('site', {
  accountId,
  name: project,
  productionBranch: 'main',
  deploymentConfigs: {
    production: { compatibilityDate: '2026-08-01' },
    preview: { compatibilityDate: '2026-08-01' },
  },
})

// 2. Attach the apex and www custom domains.
const apex = new cloudflare.PagesDomain('apex', {
  accountId,
  projectName: pages.name,
  name: domain,
})
const www = new cloudflare.PagesDomain('www', {
  accountId,
  projectName: pages.name,
  name: `www.${domain}`,
})

// 3. DNS for each hostname, tagged so anyone in the dashboard can see these
// are managed here rather than by hand.
new cloudflare.DnsRecord('apex-cname', {
  zoneId,
  name: '@',
  type: 'CNAME',
  content: pages.subdomain,
  proxied: true,
  ttl: 1,
  comment: managed,
}, { dependsOn: [apex] })

new cloudflare.DnsRecord('www-cname', {
  zoneId,
  name: 'www',
  type: 'CNAME',
  content: pages.subdomain,
  proxied: true,
  ttl: 1,
  comment: managed,
}, { dependsOn: [www] })

// 4. Zone-level "Always Use HTTPS" — raw http:// requests 301 at the edge,
// covering anything that arrives before Pages sees it.
new cloudflare.ZoneSetting('always-https', {
  zoneId,
  settingId: 'always_use_https',
  value: 'on',
})

// 5. Canonical redirect www → apex, applied at the edge. A _redirects file in
// dist/ is unreliable when both hostnames are attached as Pages custom
// domains. Uses one of the three free Page Rules.
//
// Token needs: Zone → Page Rules → Edit.
new cloudflare.PageRule('www-to-apex', {
  zoneId,
  target: `www.${domain}/*`,
  priority: 1,
  status: 'active',
  actions: {
    forwardingUrl: {
      url: `https://${domain}/$1`,
      statusCode: 301,
    },
  },
})


// ---------------------------------------------------------------------------
// 6. R2 bucket for the WebAssembly runtime.
//
// Cloudflare Pages rejects any file over 25 MiB and the runtime is ~56 MiB, so
// it cannot ship inside dist/ — it lives here and the site fetches it at run
// time. R2 charges no egress, so serving a large binary costs storage only
// (~56 MB, comfortably inside the 10 GB free tier).
//
// No custom domain: the managed `pub-<hash>.r2.dev` URL is enough for a public
// asset, and one less DNS record to own.
const runtime = new cloudflare.R2Bucket('runtime', {
  accountId,
  name: `${project}-runtime`,
  location: 'WEUR',
});

// Public read access over the r2.dev domain. Without this the bucket is
// private and the browser gets a 401.
const runtimePublic = new cloudflare.R2ManagedDomain('runtime-public', {
  accountId,
  bucketName: runtime.name,
  enabled: true,
});

// CORS: the fetch is cross-origin now (site on mezze-lang.org, runtime on
// r2.dev), so the bucket must name who may read it. Restricted to this site's
// own origins — a bucket that allowed `*` would let any page on the web embed
// the runtime and bill the storage to this account.
new cloudflare.R2BucketCors('runtime-cors', {
  accountId,
  bucketName: runtime.name,
  rules: [{
    allowed: {
      origins: [`https://${domain}`, `https://www.${domain}`],
      methods: ['GET', 'HEAD'],
      // Range requests need this so a browser can resume a partial fetch.
      headers: ['range'],
    },
    exposeHeaders: ['content-length', 'content-range'],
    maxAgeSeconds: 86400,
  }],
});

export const pagesSubdomain = pages.subdomain // e.g. mezze-lang.pages.dev
export const primaryUrl = pulumi.interpolate`https://${domain}`
export const wwwUrl = pulumi.interpolate`https://www.${domain}`

// The base the site fetches the runtime from. `just deploy` reads this and
// writes it into the build, so the URL is never hardcoded in two places.
export const runtimeBucket = runtime.name
export const runtimeUrl = pulumi.interpolate`https://${runtimePublic.domain}`
