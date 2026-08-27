import * as pulumi from '@pulumi/pulumi'
import * as cloudflare from '@pulumi/cloudflare'

/**
 * Cloudflare Pages hosting for mezze-lang.org.
 *
 * Config (set with `pulumi config set`, `--secret` where noted):
 *   cloudflare:apiToken   secret — needs Pages:Edit and, for a custom domain,
 *                         Zone:Read + DNS:Edit on the zone
 *   mezze:accountId       Cloudflare account id
 *   mezze:domain          apex domain, e.g. mezze-lang.org  (optional)
 *   mezze:zoneId          zone id for that domain           (optional)
 *
 * Uploading the built site is deliberately not modelled here: `wrangler pages
 * deploy` does that, and having Pulumi own the project while CI owns the
 * uploads keeps a deploy from being able to destroy infrastructure.
 */
const cfg = new pulumi.Config('mezze')
const accountId = cfg.require('accountId')
const domain = cfg.get('domain')
const zoneId = cfg.get('zoneId')

const PROJECT = 'mezze-lang'
const PRODUCTION_BRANCH = 'main'

const site = new cloudflare.PagesProject('site', {
  accountId,
  name: PROJECT,
  productionBranch: PRODUCTION_BRANCH,
  deploymentConfigs: {
    production: {
      // The wasm runtime is ~55MB; nothing here should try to transform it.
      compatibilityDate: '2026-08-01',
    },
    preview: {
      compatibilityDate: '2026-08-01',
    },
  },
})

/**
 * Custom domain, apex and www.
 *
 * Only created when both `domain` and `zoneId` are configured, so the stack
 * stands up against the *.pages.dev URL before DNS is ready.
 */
if (domain && zoneId) {
  const apex = new cloudflare.PagesDomain('apex', {
    accountId,
    projectName: site.name,
    domain,
  })

  // Pages resolves the apex through a CNAME at the zone root; Cloudflare
  // flattens it, which is why this is a CNAME rather than an A record.
  new cloudflare.Record('apex-cname', {
    zoneId,
    name: '@',
    type: 'CNAME',
    content: pulumi.interpolate`${site.subdomain}`,
    proxied: true,
  }, { dependsOn: [apex] })

  new cloudflare.Record('www-cname', {
    zoneId,
    name: 'www',
    type: 'CNAME',
    content: domain,
    proxied: true,
  })
}

export const projectName = site.name
export const pagesUrl = pulumi.interpolate`https://${site.subdomain}`
export const siteUrl = domain ? `https://${domain}` : pagesUrl
