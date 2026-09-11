{
  description = "Mezze — the mez toolchain: a build shell + a prebuilt-tarball installer";

  # GraalVM CE 25 (JDK 25 + native-image) lives in nixpkgs-unstable; the
  # Truffle/polyglot deps pin to 25.0.x to match. Same choice as devenv.nix.
  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";

  outputs = { self, nixpkgs }:
    let
      systems = [ "aarch64-darwin" "x86_64-darwin" "aarch64-linux" "x86_64-linux" ];
      forAll = f: nixpkgs.lib.genAttrs systems (system: f (import nixpkgs { inherit system; }));
    in
    {
      # `nix develop` — everything needed to BUILD the toolchain from source, then
      # `./gradlew -Pversion=<v> :cli:installNative` (installs into ~/.mezze). This is
      # the nix/devenv-based install path: build in the shell, install to ~/.mezze.
      devShells = forAll (pkgs:
        let graalvm = pkgs.graalvmPackages.graalvm-ce;
        in {
          default = pkgs.mkShell {
            packages = [ graalvm pkgs.kotlin pkgs.gradle pkgs.just pkgs.zlib pkgs.nodejs_24 pkgs.tree-sitter ]
              ++ pkgs.lib.optionals pkgs.stdenv.isLinux [ pkgs.gcc ];
            JAVA_HOME = "${graalvm}";
            JAVA_OPTS = "--enable-native-access=ALL-UNNAMED";
            LIBRARY_PATH = "${pkgs.zlib}/lib";
            shellHook = ''
              echo "🍽  Mezze build shell (nix)."
              echo "   build + install a toolchain:  ./gradlew -Pversion=0.1.0 :cli:installNative"
              echo "   then put ~/.mezze/bin on PATH. Manage versions with:  mez toolchain"
            '';
          };
        });

      # `nix run github:mezze-lang/mezze#install -- <tarball|url> [--default]`
      # installs a PREBUILT toolchain into ~/.mezze (honoring MEZZE_HOME) and wires
      # the PATH proxy — no build, no GraalVM needed. Wraps scripts/install-mez.sh
      # with its runtime deps pinned so it works on a bare machine.
      apps = forAll (pkgs: {
        install = {
          type = "app";
          program = "${pkgs.writeShellApplication {
            name = "mez-install";
            runtimeInputs = [ pkgs.bash pkgs.curl pkgs.gnutar pkgs.gzip pkgs.coreutils ];
            text = ''exec bash ${self}/scripts/install-mez.sh "$@"'';
          }}/bin/mez-install";
        };
        default = self.apps.${pkgs.system}.install;
      });

      formatter = forAll (pkgs: pkgs.nixpkgs-fmt);
    };
}
