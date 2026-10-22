// Live weather for the "Effects" playground sample — 100% client-side, no server.
//
// The sample's code shows a wttr.in URL (`https://wttr.in/<City>?format=3`), but
// a browser can't reproduce that one-liner: wttr.in only returns the plain text
// to a curl-style User-Agent, which a browser `fetch` cannot set. So we read the
// City out of that URL and build the same line from open-meteo, whose JSON API is
// CORS-enabled and callable straight from the browser. No proxy, no adapter.
//
// Cached per URL (in memory + localStorage, short TTL) so a repeat Run reuses the
// value instead of re-fetching.

const TTL_MS = 10 * 60 * 1000 // 10 minutes
const KEY = (url: string) => 'mezze:weather:' + url

const mem = new Map<string, { t: number; v: string }>()

function cached(url: string): string | null {
  const hit = mem.get(url)
  if (hit && Date.now() - hit.t < TTL_MS) return hit.v
  try {
    const raw = localStorage.getItem(KEY(url))
    if (raw) {
      const { t, v } = JSON.parse(raw) as { t: number; v: string }
      if (typeof t === 'number' && typeof v === 'string' && Date.now() - t < TTL_MS) {
        mem.set(url, { t, v })
        return v
      }
    }
  } catch {
    /* localStorage unavailable (private mode, blocked) — fine, skip it */
  }
  return null
}

function store(url: string, v: string): void {
  const rec = { t: Date.now(), v }
  mem.set(url, rec)
  try {
    localStorage.setItem(KEY(url), JSON.stringify(rec))
  } catch {
    /* ignore */
  }
}

/** WMO weather code → an emoji, in the spirit of wttr.in's one-liner. */
function icon(code: number): string {
  if (code === 0) return '☀️'
  if (code === 1 || code === 2) return '⛅️'
  if (code === 3) return '☁️'
  if (code === 45 || code === 48) return '🌫️'
  if (code >= 51 && code <= 57) return '🌦️'
  if (code >= 61 && code <= 67) return '🌧️'
  if (code >= 71 && code <= 77) return '❄️'
  if (code >= 80 && code <= 82) return '🌦️'
  if (code === 85 || code === 86) return '❄️'
  if (code >= 95) return '⛈️'
  return '🌡️'
}

/** The city from a `…/wttr.in/<City>?…` URL, decoded; null if it isn't one. */
function cityFromUrl(url: string): string | null {
  const m = url.match(/wttr\.in\/([^/?#\s"']+)/)
  if (!m) return null
  try {
    return decodeURIComponent(m[1])
  } catch {
    return m[1]
  }
}

/**
 * The weather one-liner for [url]'s city (e.g. `Amsterdam: ⛅️ +9°C`), from cache
 * when fresh, else fetched once and cached. Returns null if it can't be built
 * (caller keeps its own fallback). All fetches are plain CORS browser calls.
 */
export async function liveWeather(url: string): Promise<string | null> {
  const hit = cached(url)
  if (hit != null) return hit

  const city = cityFromUrl(url)
  if (!city) return null

  try {
    const geo = await fetch(
      'https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&format=json&name=' +
        encodeURIComponent(city),
    )
    if (!geo.ok) return null
    const loc = (await geo.json())?.results?.[0]
    if (!loc) return null

    const fc = await fetch(
      `https://api.open-meteo.com/v1/forecast?latitude=${loc.latitude}&longitude=${loc.longitude}` +
        '&current=temperature_2m,weather_code',
    )
    if (!fc.ok) return null
    const cur = (await fc.json())?.current
    if (!cur || typeof cur.temperature_2m !== 'number') return null

    const t = Math.round(cur.temperature_2m)
    const line = `${city}: ${icon(cur.weather_code)} ${t >= 0 ? '+' : ''}${t}°C`
    store(url, line)
    return line
  } catch {
    return null
  }
}
