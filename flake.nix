{
  description = "contactcleaner development environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = {
    nixpkgs,
    flake-utils,
    ...
  }:
    flake-utils.lib.eachSystem ["x86_64-linux" "aarch64-darwin"] (
      system: let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        };
        buildToolsVersion = "36.0.0";
        android = pkgs.androidenv.composeAndroidPackages {
          platformVersions = ["36"];
          buildToolsVersions = [buildToolsVersion];
          includeEmulator = false;
          includeSystemImages = false;
          includeNDK = false;
        };
        sdk = "${android.androidsdk}/libexec/android-sdk";
      in {
        devShells.default = pkgs.mkShell {
          packages = [
            android.androidsdk
            pkgs.android-tools
            pkgs.jdk21
          ];

          ANDROID_HOME = sdk;
          ANDROID_SDK_ROOT = sdk;
          JAVA_HOME = pkgs.jdk21.home;
          GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdk}/build-tools/${buildToolsVersion}/aapt2";
        };
      }
    );
}
