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

export const dishes: Dish[] = [
  { key: 'olive',     label: 'Olive',         icon: '/icons/olive.png',     primary: 'var(--olive)', onPrimary: '#FFFEFA' },
  { key: 'lemon',     label: 'Lemon',         icon: '/icons/lemon.png',     primary: 'var(--lemon)', onPrimary: '#FFFEFA' },
  { key: 'onion',     label: 'Pickled onion', icon: '/icons/onion.png',     primary: 'var(--onion)', onPrimary: '#FFFEFA' },
  { key: 'tomato',    label: 'Tomato',        icon: '/icons/tomato.png',    primary: 'var(--tomato)', onPrimary: '#FFFEFA' },
  { key: 'mint',      label: 'Mint',          icon: '/icons/mint.png',      primary: 'var(--mint)', onPrimary: '#FFFEFA' },
  { key: 'aubergine', label: 'Aubergine',     icon: '/icons/aubergine.png', primary: 'var(--aubergine)', onPrimary: '#FFFEFA' },
]
