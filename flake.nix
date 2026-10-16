{
  # Install the mez toolchain with nix:
  #
  #   nix run   github:mezze-lang/mezze -- --version
  #   nix profile install github:mezze-lang/mezze
  #
  # This fetches the prebuilt release binary for your system (it does NOT build
  # the toolchain from source — native-image needs a full GraalVM). It installs
  # the versioned toolchain binary as $out/bin/mez, which self-locates its own
  # lib/ in the store; writable state (caches, synced deps) still lives under
  # ~/.mezze, so nothing is written into the read-only store.
  #
  # Per release: bump `version` and fill `hashes` with the real values —
  # `nix build` prints the correct hash on a mismatch (start from lib.fakeHash).
  description = "The Mezze toolchain (mez) — prebuilt binary install";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { self, nixpkgs }:
    let
      version = "0.1.0";
      repo = "mezze-lang/mezze";

      # nix system -> release asset platform + its tarball sha256.
      targets = {
        "x86_64-linux"  = { platform = "linux-x64";    hash = nixpkgs.lib.fakeHash; };
        "aarch64-linux" = { platform = "linux-arm64";  hash = nixpkgs.lib.fakeHash; };
        "x86_64-darwin" = { platform = "darwin-x64";   hash = nixpkgs.lib.fakeHash; };
        "aarch64-darwin"= { platform = "darwin-arm64"; hash = nixpkgs.lib.fakeHash; };
      };

      forAll = f: nixpkgs.lib.genAttrs (builtins.attrNames targets)
        (system: f system nixpkgs.legacyPackages.${system});

      mkMez = system: pkgs:
        let t = targets.${system};
        in pkgs.stdenv.mkDerivation {
          pname = "mez";
          inherit version;

          src = pkgs.fetchurl {
            url = "https://github.com/${repo}/releases/download/v${version}/mez-${version}-${t.platform}.tar.gz";
            sha256 = t.hash;
          };

          sourceRoot = ".";

          # Prebuilt GraalVM native-image binary: on Linux it needs its ELF
          # interpreter + rpath fixed up to the nix store's libs.
          nativeBuildInputs = pkgs.lib.optionals pkgs.stdenv.hostPlatform.isLinux [ pkgs.autoPatchelfHook ];
          buildInputs = pkgs.lib.optionals pkgs.stdenv.hostPlatform.isLinux [
            pkgs.stdenv.cc.cc.lib  # libstdc++ / libgcc_s
            pkgs.zlib
          ];

          installPhase = ''
            runHook preInstall
            # The tarball is the ~/.mezze layout. Install the VERSIONED toolchain
            # binary (not the PATH proxy): it self-locates its lib next to it, so
            # it needs no settings.toml / MEZZE_HOME, and caches go to ~/.mezze.
            tc=$(echo toolchains/*/)
            mkdir -p "$out/bin" "$out/lib"
            cp "$tc/bin/mez" "$out/bin/mez"
            cp -r "$tc/lib/." "$out/lib/"
            chmod +x "$out/bin/mez"
            runHook postInstall
          '';

          # The binary is already a self-contained native image; don't let nix
          # strip/mangle it beyond the autoPatchelf fixups above.
          dontStrip = true;

          meta = with pkgs.lib; {
            description = "The Mezze toolchain (mez): check/fmt/init standalone; dev/build use your GraalVM";
            homepage = "https://github.com/${repo}";
            platforms = builtins.attrNames targets;
            mainProgram = "mez";
          };
        };
    in {
      packages = forAll (system: pkgs: rec {
        mez = mkMez system pkgs;
        default = mez;
      });

      apps = forAll (system: pkgs: rec {
        mez = {
          type = "app";
          program = "${self.packages.${system}.mez}/bin/mez";
        };
        default = mez;
      });
    };
}
