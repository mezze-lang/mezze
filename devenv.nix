{ pkgs, lib, config, inputs, ... }:

let
  pkgs-unstable = import inputs.nixpkgs-unstable { system = pkgs.stdenv.system; };

  # GraalVM 25 CE — provides JDK 25 + native-image. We use CE (not Oracle)
  # because nixpkgs' Oracle GraalVM 25 derivation has a broken
  # libonnxruntime auto-patchelf step (profile_inference feature). CE
  # works cleanly on Linux/macOS and is what mez ships against. The
  # Truffle/polyglot deps in build.gradle.kts pin to 25.0.x to match
  # this JDK's native-image polyglot version requirement.
  graalvm = pkgs-unstable.graalvmPackages.graalvm-ce;

in
{
  # This file is the ENVIRONMENT ONLY — toolchain, env vars, PATH. Every runnable
  # command (build the compiler, run the test suites, run mez, tooling, website,
  # deploy) lives in the Justfile. Run `just` (or `just --list`) to see them.
  packages = [
    pkgs-unstable.kotlin
    graalvm
    pkgs.zlib
    # Pinned major: the website (Astro) and the VSCode extension build against
    # this. Bare `pkgs.nodejs` is an alias that drifts with nixpkgs, which would
    # silently change the major version under the website's lockfile.
    pkgs.nodejs_24
    # tree-sitter CLI for the tooling/tree-sitter-mezze grammar
    # (generate parser.c, build native lib, run corpus tests).
    pkgs.tree-sitter
    # website deployment — see the Justfile
    pkgs.pulumi-bin
    pkgs.pulumiPackages.pulumi-nodejs
    pkgs.just                     # command runner — the entry point for everything
    pkgs.sops                     # secret file encryption
    pkgs.age                      # asymmetric backend for sops
  ] ++ lib.optionals pkgs.stdenv.isLinux [
    pkgs.gcc
  ];

  # --- website deployment (Pulumi + Cloudflare) -----------------------------
  env.PULUMI_SKIP_UPDATE_CHECK = "true";

  # pulumiPackages.pulumi-nodejs puts an OLD cloudflare provider (6.13) on PATH,
  # and an ambient plugin wins over ~/.pulumi/plugins. infra/package.json pins
  # 6.19, and PageRule/ZoneSetting differ between the two — so ignore ambient
  # plugins and use the version the project actually asked for.
  env.PULUMI_IGNORE_AMBIENT_PLUGINS = "true";
  # State lives in ~/.pulumi/ rather than Pulumi Cloud — back it up, it holds
  # the resource-ID map and losing it means Pulumi cannot reconcile what is
  # already provisioned against the code.
  env.PULUMI_BACKEND_URL = "file://~";
  # Nothing to prompt for: real secrets never enter Pulumi state, they arrive
  # as sops-decrypted env vars on each run.
  env.PULUMI_CONFIG_PASSPHRASE = "";

  languages = {
    java = {
      enable = true;
      jdk.package = graalvm;
      gradle.enable = true;
    };
  };

  # Environment for Gradle and the Truffle/native-image toolchain.
  env = {
    JAVA_HOME = lib.mkForce "${graalvm}";
    JAVA_OPTS = "--enable-native-access=ALL-UNNAMED";
    LIBRARY_PATH = "${pkgs.zlib}/lib";
    # native-image needs the C toolchain + zlib; harmless when unused.
    ZLIB_LIB_PATH = "${pkgs.zlib}/lib";
  };

  enterShell = ''
    echo "🍽  Mezze dev environment  —  everything runs through 'just'"
    echo ""
    echo "   just              list every command"
    echo "   just build        build the mez CLI"
    echo "   just test         parser + typechecker + interpreter suites"
    echo "   just mez ...       run mez, e.g.  just mez init hello  ·  just mez exec app.mz"
    echo "   just site-dev     website dev server"
    echo ""
    echo "   (direnv loads this env, so 'just' works directly. Outside it:"
    echo "    devenv shell -- just <recipe> )"
    echo ""
  '';

  enterTest = ''
    echo "Running environment tests"
    java -version
    kotlin -version
    gradle --version
  '';
}
