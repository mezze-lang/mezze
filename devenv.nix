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
  };

  enterShell = ''
    echo "🚀 Kotlin Language Development Environment"
    echo "Available commands:"
    echo "  build  - Build the project"
    echo "  run    - Run the project"
    echo "  test   - Run tests"
    echo "  clean  - Clean build artifacts"
    echo "  antlr  - Generate ANTLR parsers"
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