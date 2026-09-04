# std::codec

Serialization and deserialization for Mezze values. Four-tier ability system
built on top of format-neutral native primitives (`__native_json_parse` etc).

Users touch **Tier 4** (per-format sugar). Format authors write **Tiers 1 & 2**
for each new format. The compiler synthesizes **Tier 3** by walking the shape
of the value being encoded.

Full design: [`docs/codec/redesign-2026-09.md`](../../../docs/codec/redesign-2026-09.md).

---

## Layout

```
std/codec/
  core.mz    # Tier 1 (Format) + Tier 2 (per-shape) + Tier 3 (Encode/Decode)
  json.mz    # JsonC (variant tree) + JsonUtf8C (bytes) + ToJson/FromJson sugar
  url.mz     # UrlC (query params) + ToUrl/FromUrl sugar
```

`core.mz` also owns `SharedConfig`, `EncodeCtx`, `DecodeCtx`, `DecodeError`,
`FieldTransform`, path helpers, and the field-transform functions.

---

## User surface — Tier 4

For most callers this is all there is:

```mezze
use std::codec::json::{ json_default, json_pretty }

type User = { name: Str, age: Int, tags: [Str] }
let alice : User = { name = "Alice", age = 30, tags = ["admin", "user"] }

# Encode
let j        : Json  = alice.json {}                         # variant tree
let pretty   : Json  = alice.json_with { config = json_pretty }
let bytes    : Bytes = alice.json_utf8 {}                    # UTF-8 straight

# Decode — target-type-driven via inherent method syntax
let parsed : Result User DecodeError = User.from_json { it = j }
```

The rules are uniform across formats:

| Call                                | Meaning                                    |
|-------------------------------------|--------------------------------------------|
| `x.<fmt> {}`                        | encode `x` with the format's default config |
| `x.<fmt>_with { config = c }`       | encode `x` with an explicit config         |
| `Type.from_<fmt> { it = raw }`      | decode `raw` into `Type`, default config   |
| `Type.from_<fmt>_with { it, config }` | decode `raw` into `Type`, explicit config  |

Currently shipped Tier-4 pairs:

| Format       | Module               | Config    | Output | Abilities                |
|--------------|----------------------|-----------|--------|--------------------------|
| JSON (tree)  | `std::codec::json`   | `JsonC`     | `Json`   | `ToJson`, `FromJson`       |
| JSON (bytes) | `std::codec::json`   | `JsonUtf8C` | `Bytes`  | `ToJsonUtf8`, `FromJsonUtf8` |
| URL param    | `std::codec::url`    | `UrlC`      | `Str`    | `ToUrl`, `FromUrl`         |

---

## Adding a new format

Each format module owns:

1. Its config type (with a `shared: SharedConfig` field for cross-format knobs).
2. One or more preset config values.
3. `impl Format for <Config> having 'out = <Output>` — the identity link.
4. Tier-2 per-shape impls for every shape the format supports.
5. Tier-4 ability pair (`To<Fmt>`, `From<Fmt>`) with blanket impls.

Skeleton:

```mezze
pub type FooC = { shared: SharedConfig, /* format-specific knobs */ }
pub let foo_default: FooC = { shared = default_shared }

impl Format for FooC having 'out = FooOut where { }

impl EncodeScalar for FooC having 'out = FooOut where { /* per-scalar bodies */ }
impl EncodeRecord for FooC having 'out = FooOut where { /* … */ }
# … EncodeVariant, EncodeList, EncodeMap, EncodeOption as supported …

impl DecodeScalar for FooC having 'in = FooIn where { /* per-scalar bodies */ }
# … DecodeRecord, DecodeVariant, DecodeList, DecodeMap, DecodeOption …

ability ToFoo for 'a where {
  foo:      { it: 'a }               -> FooOut,
  foo_with: { it: 'a, config: FooC } -> FooOut,
}
impl ToFoo for 'a where {
  foo      = { it }         with Encode FooC 'a -> it.encode { config = foo_default },
  foo_with = { it, config } with Encode FooC 'a -> it.encode { config = config },
}

ability FromFoo 'a for 'in where {
  from_foo:      { it: 'in }               -> Result 'a DecodeError,
  from_foo_with: { it: 'in, config: FooC } -> Result 'a DecodeError,
}
impl FromFoo 'a for FooIn where {
  from_foo      = { it }         with Decode FooC 'a -> it.decode { config = foo_default },
  from_foo_with = { it, config } with Decode FooC 'a -> it.decode { config = config },
}
```

### Partial-capability formats

Omit the Tier-2 impls the format can't honestly implement (CSV can't encode
variants, URL params can't encode records). Types containing those shapes
fail auto-derivation at the offending field path — a compile error, never
a runtime error.

---

## Overriding auto-derivation for a specific type

Hand-write a Tier-3 impl to bypass the per-shape walker:

```mezze
newtype Money = Money { currency: Str, cents: Nat }

impl Encode JsonC for Money having 'out = Json where {
  encode = { it, config } -> match it is
    Money { currency, cents } ->
      JStr { val = "$(currency) $(cents / 100).$(cents % 100)" }
}
```

Types containing `Money` fields pick this up automatically — the auto-derived
record walker calls `Encode JsonC Money` for the field, which now dispatches
to the hand impl.

To make a type **unencodable**, define it as a newtype with no `Encode` impl
for the format — containing types fail at the field path.

---

## Polymorphic helpers

Library authors writing format-generic code use Tier 3 directly:

```mezze
# Generic over format
let generic : { val: 'a, config: 'config }
              with Encode 'config 'a
              -> 'out
            = { val, config } -> val.encode { config = config }

# Fixed format
let post_json : { val: 'a } with Encode JsonUtf8C 'a -> Bytes
              = { val } -> val.encode { config = json_utf8_default }

# Bounded output — any config that produces bytes
let post_bytes : { val: 'a, config: 'config }
                 with Encode 'config 'a, Format 'config having 'out = Bytes
                 -> Bytes
               = { val, config } -> val.encode { config = config }
```

Application code should prefer Tier 4 (`.json{}` etc.). Tier 3 is the escape
hatch, not the default.

---

## Migration note

The old `EncodeRepr` / `DecodeRepr` / `Encode` / `Decode` / `JsonFmt` /
`StreamFormat` / `StreamEncode` / `StreamDecode` abilities are **gone** — the
codec module was rewritten as part of task #86. If you're porting old code:

- `body.encode_repr { fmt = json_fmt, config = default_config }` →
  `body.json {}` (default) or `body.encode { config = json_default }`
  (polymorphic helper form).
- `body.decode_repr { fmt = json_fmt, ... }` →
  `Type.from_json { it = body }` (target-type-driven).
- `StreamFormat` / `StreamEncode` / `StreamDecode`: deleted without a
  replacement. Streaming is out-of-scope for phase 1; see the design doc's
  "What's deliberately out of scope" section.

See `docs/codec/redesign-2026-09.md` for the full old-vs-new mapping.
