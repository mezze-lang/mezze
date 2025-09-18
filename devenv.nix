{
  pkgs,
  lib,
  config,
  inputs,
  ...
}:
let
  pkgs-unstable = import inputs.nixpkgs-unstable { system = pkgs.stdenv.system; };
in
{
  packages = [
    pkgs-unstable.kotlin
    pkgs-unstable.graalvmPackages.graalvm-oracle
  ];

  languages = {
    java = {
      enable = true;
      jdk.package = pkgs-unstable.graalvmPackages.graalvm-oracle;
      gradle.enable = true;
    };
  };

  scripts = {
    # Basic build commands
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

    # Development CLI (JAR-based, faster builds)
    funk-dev.exec = ''
      if [ ! -f "./cli/build/libs/funk.jar" ]; then
        echo "❌ JAR not found. Building it first..."
        gradle build
      fi
      java \
        --enable-native-access=ALL-UNNAMED \
        -Dpolyglot.engine.WarnInterpreterOnly=false \
        -Dtruffle.TruffleRuntime=com.oracle.truffle.api.impl.DefaultTruffleRuntime \
        -jar ./cli/build/libs/funk.jar "$@"
    '';

    # Native binary build and execution
    build-native.exec = ''
      echo "🚀 Building native Funk binary..."
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

  # https://devenv.sh/tests/
  enterTest = ''
    echo "Running environment tests"
    java -version
    kotlin -version
    gradle --version
  '';
}
