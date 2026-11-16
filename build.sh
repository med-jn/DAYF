#!/usr/bin/env bash
# Tik Guest: local build (macOS/Linux). Bumps versionCode, builds APK + AAB into dist/.
set -euo pipefail
cd "$(dirname "$0")"
if [ ! -f local.properties ]; then
  SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
  [ -d "$SDK" ] || { echo "Android SDK not found. Set ANDROID_HOME."; exit 1; }
  echo "sdk.dir=$SDK" > local.properties
fi
CODE=$(grep '^versionCode=' version.properties | cut -d= -f2)
if [ "${1:-}" != "--no-bump" ]; then CODE=$((CODE+1)); echo "versionCode=$CODE" > version.properties; fi
echo "== Tik Guest 1.0.$CODE =="
chmod +x gradlew
./gradlew assembleDebug bundleRelease -PskipBump=1 --console=plain
mkdir -p dist
cp app/build/outputs/apk/debug/app-debug.apk "dist/TikGuest-1.0.$CODE.apk"
cp app/build/outputs/bundle/release/app-release.aab "dist/TikGuest-1.0.$CODE.aab"
ls -la dist
