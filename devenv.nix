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
    pkgs.just                     # command runner
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

  # Set environment variables for Gradle and other Java tools
  env = {
    JAVA_HOME = lib.mkForce "${graalvm}";
    JAVA_OPTS = "--enable-native-access=ALL-UNNAMED";
    LIBRARY_PATH = "${pkgs.zlib}/lib";
  };

  scripts = {
    # Build the mez CLI → ./cli/build/install/mez/bin/mez
    build.exec = ''
      echo "🔨 Building the mez CLI..."
      ./gradlew :cli:installDist
      echo "✅ mez → ./cli/build/install/mez/bin/mez"
    '';

    clean.exec = ''
      echo "🧹 gradle clean..."
      ./gradlew clean
    '';

    # The typechecker and interpreter suites are heavy and share a lot of RAM,
    # so run them as SEPARATE --no-daemon invocations, never one `gradle test`.
    test.exec = ''
      echo "🧪 parser + typechecker + interpreter (separate, --no-daemon)..."
      ./gradlew :parser:test      --no-daemon
      ./gradlew :typechecker:test --no-daemon
      ./gradlew :interpreter:test --no-daemon
    '';

    # The dev CLI (built jar launcher). Builds it first if missing.
    #   mez init hello   ·   mez exec app.mz   ·   mez check   ·   mez dev
    mez.exec = ''
      MEZ="./cli/build/install/mez/bin/mez"
      if [ ! -x "$MEZ" ]; then
        echo "mez not built yet — running: ./gradlew :cli:installDist" >&2
        ./gradlew :cli:installDist
      fi
      exec "$MEZ" "$@"
    '';

    build-native.exec = ''
      echo "🚀 Building the native mez binary..."
      export PATH="${graalvm}/bin:${pkgs.gcc}/bin:$PATH"
      export ZLIB_LIB_PATH="${pkgs.zlib}/lib"

      ./gradlew :cli:nativeCompile

      BIN="./cli/build/native/nativeCompile/mez"
      if [ -f "$BIN" ]; then
        echo "✅ Native binary: $BIN ($(du -h "$BIN" | cut -f1))"
      else
        echo "❌ Native compilation failed" >&2
        exit 1
      fi
    '';

    # Run the AOT native binary (build it with build-native first).
    mez-native.exec = ''
      BIN="./cli/build/native/nativeCompile/mez"
      if [ -x "$BIN" ]; then
        exec "$BIN" "$@"
      else
        echo "❌ Native binary not found. Run 'build-native' first." >&2
        exit 1
      fi
    '';

    ts-build.exec = ''
      echo "🌲 Generating + building tree-sitter-mezze..."
      cd tooling/tree-sitter-mezze
      tree-sitter generate
      tree-sitter build
    '';

    ts-test.exec = ''
      echo "🌲 Running tree-sitter corpus tests..."
      cd tooling/tree-sitter-mezze
      tree-sitter generate
      tree-sitter test
    '';

    vscode-install.exec = ''
      echo "📦 Packaging VSCode extension..."
      cd tooling/vscode-extension/extension
      npx vsce package --allow-missing-repository
      VSIX=$(ls -t *.vsix | head -1)
      if [ -f "$VSIX" ]; then
        echo "📥 Installing $VSIX..."
        code --install-extension "$VSIX" --force
        echo "✅ Extension installed! Reload VSCode to activate."
      else
        echo "❌ Failed to package extension"
        exit 1
      fi
    '';

    web-dev.exec = ''
      echo "🌐 Starting website dev server..."
      cd website
      [ -d node_modules ] || npm install
      npm run dev -- "$@"
    '';

    web-build.exec = ''
      echo "🌐 Building website..."
      cd website
      [ -d node_modules ] || npm install
      npm run build
    '';

    smoke-tests.exec = ''
      echo "🧪 Running CLI smoke tests..."
      ./docker-test-project/run-tests.sh "$@"
    '';
  };

  enterShell = ''
    echo "🚀 Mezze development environment"
    echo ""
    echo "  Build & test"
    echo "    build          build the mez CLI (./gradlew :cli:installDist)"
    echo "    test           parser + typechecker + interpreter (separate suites)"
    echo "    clean          gradle clean"
    echo ""
    echo "  Run the compiler"
    echo "    mez ...        dev CLI, e.g.  mez init hello  ·  mez exec app.mz  ·  mez check"
    echo "    build-native   build the AOT native binary"
    echo "    mez-native ... run the native binary"
    echo ""
    echo "  Tooling"
    echo "    ts-build / ts-test   tree-sitter grammar (tooling/tree-sitter-mezze)"
    echo "    vscode-install       package + install the VSCode extension"
    echo "    smoke-tests          CLI smoke tests (docker-test-project)"
    echo ""
    echo "  Website (or use: just <recipe>)"
    echo "    web-dev / web-build  dev server / static build (website/)"
    echo ""
    echo "  Try it:  mez init hello && cd hello && mez dev"
    echo ""
  '';

  enterTest = ''
    echo "Running environment tests"
    java -version
    kotlin -version
    gradle --version
  '';
}
