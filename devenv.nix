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
    build.exec = ''
      echo "🔨 Building Mezze language..."
      gradle build
    '';

    clean.exec = ''
      echo "🧹 Cleaning project..."
      gradle clean
    '';

    test.exec = ''
      echo "🧪 Running tests..."
      gradle test
    '';

    mezze-dev.exec = ''
      if [ ! -f "./cli/build/libs/mezze.jar" ]; then
        echo "❌ JAR not found. Building it first..."
        gradle build
      fi
      java $JAVA_OPTS \
        -Dpolyglot.engine.WarnInterpreterOnly=false \
        -Dtruffle.TruffleRuntime=com.oracle.truffle.api.impl.DefaultTruffleRuntime \
        -jar ./cli/build/libs/mezze.jar "$@"
    '';

    build-native.exec = ''
      echo "🚀 Building native Mezze binary..."
      export PATH="${graalvm}/bin:${pkgs.gcc}/bin:$PATH"
      export ZLIB_LIB_PATH="${pkgs.zlib}/lib"

      gradle build
      gradle :cli:nativeCompile

      if [ -f "./cli/build/native/nativeCompile/mezze" ]; then
        echo "✅ Native binary built: ./cli/build/native/nativeCompile/mezze"
        echo "📊 Size: $(du -h ./cli/build/native/nativeCompile/mezze | cut -f1)"
      else
        echo "❌ Native compilation failed"
        exit 1
      fi
    '';

    mezze.exec = ''
      NATIVE_BINARY="./cli/build/native/nativeCompile/mezze"
      if [ -f "$NATIVE_BINARY" ]; then
        "$NATIVE_BINARY" "$@"
      else
        echo "❌ Native binary not found. Run 'build-native' first."
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
    echo "🚀 Mezze Language Development Environment"
    echo ""
    echo "📦 Build Commands:"
    echo "  build        - Build the project"
    echo "  clean        - Clean build artifacts"
    echo "  test         - Run tests"
    echo ""
    echo "🎯 Mezze CLI:"
    echo "  mezze-dev     - Development CLI (JAR, faster builds)"
    echo "  mezze         - Native binary CLI (production)"
    echo "  build-native - Build native binary"
    echo ""
    echo "🛠️  Tooling:"
    echo "  vscode-install - Package and install VSCode extension"
    echo "  smoke-tests    - Run comprehensive CLI smoke tests"
    echo "  ts-build       - Generate + build tree-sitter-mezze grammar"
    echo "  ts-test        - Run tree-sitter corpus tests"
    echo ""
    echo "🌐 Website:"
    echo "  web-dev        - Website dev server (website/)"
    echo "  web-build      - Build website to website/dist"
    echo ""
    echo "📝 Examples:"
    echo "  mezze-dev parse examples/basic/hello_world.mezze"
    echo "  mezze-dev check examples/functions/lambda_examples.mezze"
    echo "  mezze-dev exec examples/variants/color_examples.mezze"
    echo ""
  '';

  enterTest = ''
    echo "Running environment tests"
    java -version
    kotlin -version
    gradle --version
  '';
}
