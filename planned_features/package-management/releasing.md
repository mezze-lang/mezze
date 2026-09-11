# Releasing a mez toolchain

A release is a **versioned toolchain tree** packaged into a tarball. No CI, no
registry — you build it locally and host the tarball wherever you like; the
installer takes a path or an `https` URL. Several versions coexist on a machine
(`~/.mezze/toolchains/<version>/`) and the PATH proxy picks which one a project
uses (see the toolchain proxy / `mez toolchain`).

## Cut a release

```
scripts/release.sh 0.1.3
```

This runs `./gradlew -Pversion=0.1.3 :cli:installNative` (a full native build) and
packages `~/.mezze/toolchains/0.1.3/` into:

```
dist/mez-0.1.3-<os>-<arch>.tar.gz          # bin/mez, lib/, stdlib/ under 0.1.3/
dist/mez-0.1.3-<os>-<arch>.tar.gz.sha256
```

The version is baked into `BuildInfo.VERSION`; treat it as immutable — never
re-cut a version with different bytes (the cache-invalidation + pin model assumes
`VERSION+GIT_SHA` uniquely identifies a compiler; see `versioning-governance.md`).
Tarballs are per-OS/arch — cut one on each target platform (the native binary is
not cross-built).

## Install one

```
scripts/install-mez.sh dist/mez-0.1.3-darwin-arm64.tar.gz --default
# or, hosted:
curl -fsSL https://…/install-mez.sh | bash -s -- https://…/mez-0.1.3-darwin-arm64.tar.gz --default
```

The installer unpacks into `$MEZZE_HOME/toolchains/<version>/` (honors
`MEZZE_HOME`, default `~/.mezze`), copies the toolchain binary to
`$MEZZE_HOME/bin/mez` (the proxy), and seeds `settings.toml`'s `default` if unset
(or on `--default`). It verifies a sibling `.sha256` when installing from a path.
Put `$MEZZE_HOME/bin` on `PATH` once.

`mez toolchain install <version>` does the same from inside the CLI once a release
source is configured (#53).

## Prerequisite

GraalVM stays a **user install** for `dev`/`build`/`build --native` (not bundled);
`mez` detects its absence and prints install guidance (`EnvironmentCheck`).
`check`/`fmt` need nothing extra.
