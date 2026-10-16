import { defineConfig } from 'astro/config'

// https://astro.build/config
export default defineConfig({
  site: 'https://mezze-lang.org',
  vite: {
    server: {
      // Vite refuses any request whose Host header it does not recognise, so
      // reaching `astro dev` from another machine returns 403 "This host is not
      // allowed" even when the network path is fine. Both forms are needed:
      // `minipc` for the short MagicDNS name, `.ts.net` (leading dot = whole
      // suffix) for the fully-qualified tailnet name. Dev server only — the
      // static build has no host check.
      allowedHosts: ['minipc', '.ts.net'],
    },
  },
})
