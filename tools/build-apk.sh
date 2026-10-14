#!/usr/bin/env bash
# Build the Android APK.
#
#   tools/build-apk.sh              # debug APK (default)
#   tools/build-apk.sh --release    # signed release APK (needs tools/gen-keystore.sh first)
#
# Idempotent; safe to re-run. Gradle caches make incremental builds take seconds.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="$ROOT/packages/mobile-shell/android"
SIGNING_PROPS="${DSH_SIGNING_PROPS:-~/.secrets/dsh-mobile-signing.properties}"

MODE="debug"
for arg in "$@"; do
  case "$arg" in
    --release) MODE="release" ;;
    --debug) MODE="debug" ;;
    -h | --help)
      sed -n '2,9p' "${BASH_SOURCE[0]}"
      exit 0
      ;;
    *)
      echo "unknown argument: $arg (try --help)" >&2
      exit 2
      ;;
  esac
done

if [[ -f ~/.local/android/env.sh ]]; then
  # shellcheck disable=SC1091
  source ~/.local/android/env.sh
fi

: "${JAVA_HOME:?JAVA_HOME missing - run tools/install-toolchain.sh or source ~/.local/android/env.sh}"
: "${ANDROID_HOME:?ANDROID_HOME missing - run tools/install-toolchain.sh or source ~/.local/android/env.sh}"

cd "$ANDROID_DIR"

if [[ "$MODE" == "release" ]]; then
  if [[ ! -f "$SIGNING_PROPS" ]]; then
    echo "release signing config missing: $SIGNING_PROPS" >&2
    echo "run tools/gen-keystore.sh first (keystore + properties live outside the repo, in ~/.secrets)" >&2
    exit 1
  fi
  # shellcheck disable=SC1090
  source "$SIGNING_PROPS"
  : "${DSH_KEYSTORE:?DSH_KEYSTORE missing in $SIGNING_PROPS}"
  : "${DSH_KEY_ALIAS:?DSH_KEY_ALIAS missing in $SIGNING_PROPS}"
  : "${DSH_KEYSTORE_PASSWORD:?DSH_KEYSTORE_PASSWORD missing in $SIGNING_PROPS}"
  : "${DSH_KEY_PASSWORD:?DSH_KEY_PASSWORD missing in $SIGNING_PROPS}"

  ./gradlew assembleRelease --console=plain \
    "-Pandroid.injected.signing.store.file=$DSH_KEYSTORE" \
    "-Pandroid.injected.signing.store.password=$DSH_KEYSTORE_PASSWORD" \
    "-Pandroid.injected.signing.key.alias=$DSH_KEY_ALIAS" \
    "-Pandroid.injected.signing.key.password=$DSH_KEY_PASSWORD"

  APK="$ANDROID_DIR/app/build/outputs/apk/release/app-release.apk"
  LABEL="release (signed)"
else
  ./gradlew assembleDebug --console=plain
  APK="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
  LABEL="debug"
fi

echo
echo "APK [$LABEL]: $APK"
ls -l "$APK"
if command -v sha256sum >/dev/null 2>&1; then
  sha256sum "$APK"
fi
