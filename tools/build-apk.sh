#!/usr/bin/env bash
# Build the debug APK. Idempotent; safe to re-run.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="$ROOT/packages/mobile-shell/android"

if [[ -f ~/.local/android/env.sh ]]; then
  # shellcheck disable=SC1091
  source ~/.local/android/env.sh
fi

: "${JAVA_HOME:?JAVA_HOME missing - run tools/install-toolchain.sh or source ~/.local/android/env.sh}"
: "${ANDROID_HOME:?ANDROID_HOME missing - run tools/install-toolchain.sh or source ~/.local/android/env.sh}"

cd "$ANDROID_DIR"
./gradlew assembleDebug --console=plain

APK="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
echo
echo "APK: $APK"
ls -l "$APK"
