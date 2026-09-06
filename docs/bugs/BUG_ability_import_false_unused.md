# An ability imported only for method dispatch is reported as unused

## What happens

Importing an ability so its methods can be called with dot syntax draws a
warning saying the import is unnecessary. Acting on that warning breaks the
build.

```
warning: Unused import: 'FromJson' is never referenced in this module.
         Remove it from the `use` clause.
```

Remove it as instructed:

```
error: Cannot access field 'from_json' on variant type: Json
```

## Reproduction

```mezze
use std::effects::log::{ Console, println }
use std::codec::json::{ Json, parse_str, FromJson }
use std::codec::core::{ DecodeError }

type Product = { product_name: Str }
type Reply = { product: Product }

let decode: { j: Json } -> Result Reply DecodeError = { j } -> j.from_json {}

let main = {} -> perform Console in
  match parse_str { s = "{\"product\":{\"product_name\":\"tahini\"}}" } is
    Err { error = _ } -> println { msg = "bad json" }
    Ok { val } -> match decode { j = val } is
      Err { error = _ } -> println { msg = "did not fit" }
      Ok { val = r } -> println { msg = r.product.product_name }
```

Runs and prints `tahini`, with a warning about `FromJson`. Delete `FromJson`
from the `use` clause and it no longer compiles.

## Why the check is wrong

`FromJson` never appears as a *name* in the body — the only thing referring to
it is `j.from_json {}`, a method call resolved through the ability. The unused
check evidently looks for the identifier and does not count method dispatch as
a reference.

That makes the warning actively harmful rather than merely noisy: it names a
specific fix, the fix is wrong, and the resulting error (`Cannot access field
'from_json' on variant type: Json`) points at the call site rather than at the
import that was just deleted.

Any ability imported purely so its methods resolve will hit this. `FromJson` is
just where it surfaced.

## Acceptance criteria

- The reproduction compiles, runs, prints `tahini`, and emits **no** warning.
- An ability that genuinely is unused still warns.
- An ability referenced only by name (e.g. in a `with` constraint) still counts
  as used.

## Provenance

Found writing the effects lesson for the Taste. The warning was followed, the
sample broke, and the samples gate caught it — which is the only reason it was
noticed rather than shipped.
