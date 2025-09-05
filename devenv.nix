{ pkgs, lib, config, inputs, ... }:
let pkgs-unstable = import inputs.nixpkgs-unstable { system = pkgs.stdenv.system; };
in
{
  packages = [
    pkgs-unstable.kotlin
  ];
  
  languages = {
    java = {
      enable = true;
      jdk.package = pkgs.graalvm-ce;
      gradle.enable = true;
    };
  };

  # https://devenv.sh/scripts/
  scripts = {
    build.exec = ''
      echo "Building Kotlin language project..."
      ./gradlew build
    '';
    
    run.exec = ''
      echo "Running Kotlin language project..."
      ./gradlew run
    '';
    
    test.exec = ''
      echo "Running tests..."
      ./gradlew test
    '';
    
    clean.exec = ''
      echo "Cleaning project..."
      ./gradlew clean
    '';
    
    antlr.exec = ''
      echo "Generating ANTLR parsers..."
      ./gradlew generateGrammarSource
    '';
    
    funk.exec = ''
      # Production funk command using native binary
      if [ ! -f "./cli/build/native/nativeCompile/funk" ]; then
        echo "❌ Native binary not found. Building it first..."
        ./gradlew nativeCompile
      fi
      ./cli/build/native/nativeCompile/funk "$@"
    '';
    
    funk-dev.exec = ''
      # Development funk command using JAR (faster builds, easier debugging)
      if [ ! -f "./cli/build/libs/funk.jar" ]; then
        echo "❌ JAR not found. Building it first..."
        ./gradlew build
      fi
      java \
        --enable-native-access=ALL-UNNAMED \
        -Dpolyglot.engine.WarnInterpreterOnly=false \
        -Dtruffle.TruffleRuntime=com.oracle.truffle.api.impl.DefaultTruffleRuntime \
        -jar ./cli/build/libs/funk.jar "$@"
    '';
  };

  enterShell = ''
    echo "🚀 Funk Language Development Environment"
    echo "Available commands:"
    echo "  build     - Build the project"
    echo "  run       - Run the project"
    echo "  test      - Run tests"
    echo "  clean     - Clean build artifacts"
    echo "  antlr     - Generate ANTLR parsers"
    echo ""
    echo "🎯 Funk Language Commands:"
    echo "  funk      - Production command (native binary, fast execution)"
    echo "  funk-dev  - Development command (JAR, faster builds)"
    echo ""
    echo "Examples:"
    echo "  echo 'let x = 5 + 10' | funk exec"
    echo "  echo 'let add = {x, y} -> x + y' | funk-dev check"
    echo ""
    echo "Java version: $(java -version 2>&1 | head -n 1)"
    echo "Kotlin version: $(kotlin -version 2>&1)"
    echo "Gradle version: $(gradle --version | grep 'Gradle' | head -n 1)"
  '';

  # https://devenv.sh/tests/
  enterTest = ''
    echo "Running environment tests"
    java -version
    kotlin -version
    gradle --version
  '';
}