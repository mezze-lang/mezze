{ pkgs, lib, config, inputs, ... }:

let
  pkgs-unstable = import inputs.nixpkgs-unstable { system = pkgs.stdenv.system; };

  # Prebuilt GraalVM 24 — pinned to avoid Gradle/Kotlin DSL incompatibilities with JDK 25+
  graalvm-oracle-linux-prebuilt =
    if pkgs.stdenv.isLinux then
      pkgs.stdenv.mkDerivation rec {
        pname = "graalvm-oracle-24-linux";
        version = "24.0.2";

        src = pkgs.fetchurl {
          url = "https://download.oracle.com/graalvm/24/archive/graalvm-jdk-24.0.2_linux-x64_bin.tar.gz";
          sha256 = "1blv4lh4jgpy8mlmwxv3whamlah5p2mxybakjw3g9l61pd4il5mh";
        };

        nativeBuildInputs = [ pkgs.makeWrapper ];

        unpackPhase = ''
          mkdir -p $out
          tar -xzf $src --strip-components=1 -C $out
        '';

        installPhase = "true";

        home = "$out";
      }
    else if pkgs.stdenv.isDarwin && pkgs.stdenv.isAarch64 then
      pkgs.stdenv.mkDerivation rec {
        pname = "graalvm-oracle-24-darwin-aarch64";
        version = "24.0.2";

        src = pkgs.fetchurl {
          url = "https://download.oracle.com/graalvm/24/archive/graalvm-jdk-24.0.2_macos-aarch64_bin.tar.gz";
          sha256 = "1p4l6iz10dk95bqnfwspzgr70bpk7x276grm3ba2birrv5767ird";
        };

        nativeBuildInputs = [ pkgs.makeWrapper ];

        # Tarball contains: graalvm-jdk-24.0.2+11.1/Contents/Home/{bin,lib,...}
        # Strip the top-level directory, then symlink Contents/Home/* to $out/
        unpackPhase = ''
          mkdir -p $out/Contents
          tar -xzf $src --strip-components=1 -C $out
        '';

        installPhase = ''
          # JAVA_HOME needs bin/ at the top level
          ln -s $out/Contents/Home/bin $out/bin
          ln -s $out/Contents/Home/lib $out/lib
          ln -s $out/Contents/Home/include $out/include
          ln -s $out/Contents/Home/conf $out/conf
          ln -s $out/Contents/Home/jmods $out/jmods
          ln -s $out/Contents/Home/legal $out/legal
          ln -s $out/Contents/Home/release $out/release
        '';

        home = "$out";
      }
    else
      # Fallback for other Darwin (x86_64) or unknown platforms
      pkgs-unstable.graalvmPackages.graalvm-oracle;

in
{
  packages = [
    pkgs-unstable.kotlin
    graalvm-oracle-linux-prebuilt
    pkgs.zlib
    pkgs.gcc
    pkgs.nodejs
  ];

  languages = {
    java = {
      enable = true;
      jdk.package = graalvm-oracle-linux-prebuilt;
      gradle.enable = true;
    };
  };

  # Set environment variables for Gradle and other Java tools
  env = {
    JAVA_HOME = lib.mkForce "${graalvm-oracle-linux-prebuilt}";
    JAVA_OPTS = "--enable-native-access=ALL-UNNAMED";
    LIBRARY_PATH = "${pkgs.zlib}/lib:${pkgs.stdenv.cc.cc.lib}/lib";
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
      export PATH="${graalvm-oracle-linux-prebuilt}/bin:${pkgs.gcc}/bin:$PATH"
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
