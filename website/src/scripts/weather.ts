// Live weather for the "Effects" playground sample — 100% client-side, no server.
//
// The sample fetches `https://wttr.in/<City>?format=3`. A browser can't get that
// text one-liner: wttr.in only returns it to a curl User-Agent, which `fetch`
// can't send (the custom header fails wttr.in's CORS preflight). But wttr.in's
// JSON format (`format=j1`) IS served to a browser and is CORS-enabled — same
// service, same city. So we fetch j1 and render the same one-liner ourselves.
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

// wttr.in's WWO weather code → emoji (its WEATHER_SYMBOL table), so the rendered
// line matches what `format=3` would have shown.
const SYMBOL: Record<string, string> = {
  '113': '☀️', '116': '⛅️', '119': '☁️', '122': '☁️',
  '143': '🌫', '248': '🌫', '260': '🌫',
  '176': '🌦', '263': '🌦', '266': '🌦', '293': '🌦', '296': '🌦', '353': '🌦',
  '179': '🌧', '182': '🌧', '185': '🌧', '281': '🌧', '284': '🌧',
  '299': '🌧', '302': '🌧', '305': '🌧', '308': '🌧', '311': '🌧', '314': '🌧',
  '317': '🌧', '350': '🌧', '356': '🌧', '359': '🌧', '362': '🌧', '365': '🌧', '374': '🌧', '377': '🌧',
  '200': '⛈', '386': '⛈', '389': '⛈', '392': '⛈',
  '227': '🌨', '320': '🌨', '323': '🌨', '326': '🌨', '368': '🌨',
  '230': '❄️', '329': '❄️', '332': '❄️', '335': '❄️', '338': '❄️', '371': '❄️', '395': '❄️',
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
 * The weather one-liner for [url]'s city (e.g. `Amsterdam: 🌦 +10°C`), from cache
 * when fresh, else fetched once from wttr.in's JSON and cached. Returns null if
 * it can't be built — the caller keeps its own fallback.
 */
export async function liveWeather(url: string): Promise<string | null> {
  const hit = cached(url)
  if (hit != null) return hit

  const city = cityFromUrl(url)
  if (!city) return null

  try {
    // Same wttr.in URL, but the JSON format a browser is allowed to read.
    const j = new URL(url)
    j.searchParams.set('format', 'j1')
    const r = await fetch(j.toString())
    if (!r.ok) return null
    const cur = (await r.json())?.current_condition?.[0]
    if (!cur || cur.temp_C == null) return null

    const t = parseInt(cur.temp_C, 10)
    if (Number.isNaN(t)) return null
    const emoji = SYMBOL[String(cur.weatherCode)] ?? '🌡️'
    const line = `${city}: ${emoji} ${t >= 0 ? '+' : ''}${t}°C`
    store(url, line)
    return line
  } catch {
    return null
  }
}
