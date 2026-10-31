/**
 * The dish — the olive.
 *
 * The site's identity is fixed to the olive. This pairs the icon with the
 * primary colour sampled from it, so the mascot and the interface agree.
 *
 * The hero used to cycle through six dishes; that was removed and only the
 * olive ships now. The other dishes' *colours* still live in styles/tokens.css
 * as the highlight / squiggle / syntax palette — only their icon art was
 * dropped. The type stays a list so the single call site (Mascot, the favicon)
 * needs no change.
 */
export interface Dish {
  key: string
  label: string
  icon: string
  /** Palette token. The icon was tinted to match, so the mascot and the
   *  interface always agree. */
  primary: string
  /** Text colour that sits on the primary. */
  onPrimary: string
}

/**
 * Cache-busting stamp on the icon URL.
 *
 * The icon lives in public/, so Astro never fingerprints it: a redesigned icon
 * keeps its old URL until a CDN entry expires. The stamp is a hash of the icon
 * bytes, refreshed by tools/stamp-icons.mjs, so the URL changes exactly when
 * the artwork does and never otherwise.
 */
const ICON_V = 'da748fd6'
const icon = (name: string) => `/icons/${name}.png?v=${ICON_V}`

export const dishes: Dish[] = [
  { key: 'olive', label: 'Olive', icon: icon('olive'), primary: 'var(--olive)', onPrimary: '#FFFEFA' },
]
