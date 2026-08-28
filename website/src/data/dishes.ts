/**
 * The dish set.
 *
 * Each entry pairs an icon with a primary colour sampled from that icon, so the
 * mascot and the interface always agree. The order below is the cycle order:
 * a page starts at the first dish and clicking the hero mascot advances through
 * the list, wrapping at the end.
 *
 * Order: olive, lemon, onion, tomato, mint, aubergine.
 */
export interface Dish {
  key: string
  label: string
  icon: string
  /** Palette token. The icons were tinted to match these, so the
   *  mascot and the interface always agree. */
  primary: string
  /** Text colour that sits on the primary. */
  onPrimary: string
}

/**
 * Cache-busting stamp on the icon URLs.
 *
 * These live in public/, so Astro never fingerprints them: a redesigned icon
 * keeps its old URL, and a CDN goes on serving the cached copy until the entry
 * expires — a week, in our case, during which new artwork simply does not
 * appear. The stamp is a hash of the icon bytes, refreshed by tools/icons.sh,
 * so the URL changes exactly when the artwork does and never otherwise.
 */
const ICON_V = '04410a1b'
const icon = (name: string) => `/icons/${name}.png?v=${ICON_V}`

export const dishes: Dish[] = [
  { key: 'olive',     label: 'Olive',         icon: icon('olive'),     primary: 'var(--olive)', onPrimary: '#FFFEFA' },
  { key: 'lemon',     label: 'Lemon',         icon: icon('lemon'),     primary: 'var(--lemon)', onPrimary: '#FFFEFA' },
  { key: 'onion',     label: 'Pickled onion', icon: icon('onion'),     primary: 'var(--onion)', onPrimary: '#FFFEFA' },
  { key: 'tomato',    label: 'Tomato',        icon: icon('tomato'),    primary: 'var(--tomato)', onPrimary: '#FFFEFA' },
  { key: 'mint',      label: 'Mint',          icon: icon('mint'),      primary: 'var(--mint)', onPrimary: '#FFFEFA' },
  { key: 'aubergine', label: 'Aubergine',     icon: icon('aubergine'), primary: 'var(--aubergine)', onPrimary: '#FFFEFA' },
]
