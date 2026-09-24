# contactcleaner

Android app that finds duplicate contacts, empty contacts, numbers missing a country code, repeated numbers and names without a romanization, and keeps a `PHONE` account registered so contacts saved from WhatsApp aren't purged.

## Build

```sh
nix develop  # optional: JDK, Android SDK, adb
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Usage

Open the app once to register the `PHONE` account, then tap **Scan** and apply the suggested fixes.
