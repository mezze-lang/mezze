{ pkgs, lib, config, inputs, ... }:

let
  pkgs-unstable = import inputs.nixpkgs-unstable { system = pkgs.stdenv.system; };

  # Prebuilt GraalVM for Linux
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

        # Expose 'home' attribute for devenv
        home = "$out";
      }
    else
      pkgs-unstable.graalvmPackages.graalvm-oracle;

in
{
  packages = [
    pkgs-unstable.kotlin
    graalvm-oracle-linux-prebuilt
    pkgs.zlib
    pkgs.gcc
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
      echo "🔨 Building Funk language..."
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

    funk-dev.exec = ''
      if [ ! -f "./cli/build/libs/funk.jar" ]; then
        echo "❌ JAR not found. Building it first..."
        gradle build
      fi
      java $JAVA_OPTS \
        -Dpolyglot.engine.WarnInterpreterOnly=false \
        -Dtruffle.TruffleRuntime=com.oracle.truffle.api.impl.DefaultTruffleRuntime \
        -jar ./cli/build/libs/funk.jar "$@"
    '';

    build-native.exec = ''
      echo "🚀 Building native Funk binary..."
      export PATH="${graalvm-oracle-linux-prebuilt}/bin:${pkgs.gcc}/bin:$PATH"
      export ZLIB_LIB_PATH="${pkgs.zlib}/lib"

      gradle build
      gradle :cli:nativeCompile

      if [ -f "./cli/build/native/nativeCompile/funk" ]; then
        echo "✅ Native binary built: ./cli/build/native/nativeCompile/funk"
        echo "📊 Size: $(du -h ./cli/build/native/nativeCompile/funk | cut -f1)"
      else
        echo "❌ Native compilation failed"
        exit 1
      fi
    '';

    funk.exec = ''
      NATIVE_BINARY="./cli/build/native/nativeCompile/funk"
      if [ -f "$NATIVE_BINARY" ]; then
        "$NATIVE_BINARY" "$@"
      else
        echo "❌ Native binary not found. Run 'build-native' first."
        exit 1
      fi
    '';
  };

  enterShell = ''
    echo "🚀 Funk Language Development Environment"
    echo ""
    echo "📦 Build Commands:"
    echo "  build        - Build the project"
    echo "  clean        - Clean build artifacts"
    echo "  test         - Run tests"
    echo ""
    echo "🎯 Funk CLI:"
    echo "  funk-dev     - Development CLI (JAR, faster builds)"
    echo "  funk         - Native binary CLI (production)"
    echo "  build-native - Build native binary"
    echo ""
    echo "📝 Examples:"
    echo "  funk-dev parse examples/basic/hello_world.funk"
    echo "  funk-dev check examples/functions/lambda_examples.funk"
    echo "  funk-dev exec examples/variants/color_examples.funk"
    echo ""
  '';

  enterTest = ''
    echo "Running environment tests"
    java -version
    kotlin -version
    gradle --version
  '';
}
