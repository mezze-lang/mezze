# Installing and managing `mez`

`mez` installs into `~/.mezze/` as one directory per version, with a single proxy
on your `PATH` that dispatches to the version a project asks for. Several versions
coexist; nothing is global except the proxy.

```
~/.mezze/
  toolchains/<version>/   one self-contained toolchain each (bin/mez, lib/, stdlib/)
  bin/mez                 the proxy — put this on PATH
  store/                  shared cache of downloaded dependencies
  settings.toml           your global default version
```

Put the proxy on your PATH once:

```sh
export PATH="$HOME/.mezze/bin:$PATH"
```

## Getting `mez`

### A. Prebuilt tarball (no build, no GraalVM)

```sh
# from a local tarball or an https URL:
scripts/install-mez.sh mez-0.1.0-darwin-arm64.tar.gz --default
# via nix, without cloning:
nix run github:mezze-lang/mezze#install -- https://…/mez-0.1.0-darwin-arm64.tar.gz --default
```

`--default` makes it your global default. Re-run for more versions; they coexist.

### B. Build from source (nix flake)

```sh
nix develop                                   # GraalVM 25 + gradle + kotlin
./gradlew -Pversion=0.1.0 :cli:installNative  # builds + installs into ~/.mezze
```

### C. Build from source (devenv)

```sh
devenv shell -- ./gradlew -Pversion=0.1.0 :cli:installNative
# or, inside the shell (direnv): just build   (dev), then installNative to publish
```

B and C both need **GraalVM** (for `native-image`) — the nix shell / devenv provide
it. A hand-built JDK works too, but must be GraalVM 25 CE. `mez` tells you if it's
missing.

## Managing versions

```sh
mez toolchain list                 # what's installed, which is default/active here
mez toolchain default 0.2.0        # set the global default
mez toolchain pin 0.1              # pin THIS project (writes [toolchain] to Mezze.toml)
mez toolchain install <tarball>    # add a version from a tarball / url#sha256
mez toolchain remove 0.1.0         # uninstall a version
mez toolchain which                # which version resolves here, and why
```

## Which version runs (first match wins)

| How | Scope |
|---|---|
| `mez +0.2.0 build` | one command |
| `MEZ_VERSION=0.2.0` | shell / CI |
| `Mezze.toml` → `[toolchain] channel = "0.1"` | this project (committed) |
| `mez toolchain default …` | you, everywhere |
| *(nothing)* | latest installed |

A channel is a track (`"0.1"`, `"nightly"`) or an exact pin (`"0.1.3"`).

## Cutting a release

```sh
scripts/release.sh 0.1.3           # → dist/mez-0.1.3-<os>-<arch>.tar.gz (+ .sha256)
```

Per-OS/arch (the binary isn't cross-built). Host the tarball anywhere; users install
it with A above. See `planned_features/package-management/releasing.md`.

## Resilience

- `<project>/.mez/` and `~/.mezze/store/` are caches — delete them freely; they
  rebuild (`.mez` from source on the next command, `store` via `mez sync` from
  `Mezze.lock`). A corrupt `.mez` cache self-heals with a notice.
- Deleting `~/.mezze/toolchains/<v>` uninstalls that version; the proxy then says
  it's not installed and how to fix it.
- GraalVM is a user prerequisite for `dev`/`build`, never bundled; `mez` detects
  its absence and prints install guidance. `check`/`fmt` need nothing extra.
