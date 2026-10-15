#!/usr/bin/env bash
# Create the release signing keystore for DSH Mobile, outside the repository.
#
#   tools/gen-keystore.sh            # refuses to overwrite an existing keystore
#   tools/gen-keystore.sh --force    # replace it (changes the app signing identity!)
#
# Output:
#   ${HOME}/.secrets/dsh-mobile-release.jks         the keystore
#   ${HOME}/.secrets/dsh-mobile-signing.properties  shell-sourceable vars used by tools/build-apk.sh --release
#
# Back up both files: losing them means you can never update an installed release APK in place.
set -euo pipefail

SECRETS_DIR="${DSH_SECRETS_DIR:-${HOME}/.secrets}"
KEYSTORE="$SECRETS_DIR/dsh-mobile-release.jks"
PROPS="$SECRETS_DIR/dsh-mobile-signing.properties"
ALIAS="${DSH_KEY_ALIAS:-dsh-mobile}"
DNAME="${DSH_KEY_DNAME:-CN=DSH Mobile, OU=dsh-mobile, O=dsh-mobile, L=Shanghai, ST=Shanghai, C=CN}"

FORCE=0
for arg in "$@"; do
  case "$arg" in
    --force) FORCE=1 ;;
    -h | --help)
      sed -n '2,14p' "${BASH_SOURCE[0]}"
      exit 0
      ;;
    *)
      echo "unknown argument: $arg (try --help)" >&2
      exit 2
      ;;
  esac
done

if [[ -f ${HOME}/.local/android/env.sh ]]; then
  # shellcheck disable=SC1091
  source ${HOME}/.local/android/env.sh
fi
: "${JAVA_HOME:?JAVA_HOME missing - run tools/install-toolchain.sh or source ${HOME}/.local/android/env.sh}"

if [[ -e "$KEYSTORE" && "$FORCE" -ne 1 ]]; then
  echo "keystore already exists: $KEYSTORE" >&2
  echo "re-run with --force only if you intend to change the app signing identity" >&2
  exit 1
fi

mkdir -p "$SECRETS_DIR"
chmod 700 "$SECRETS_DIR"

STORE_PASSWORD="$(openssl rand -base64 24 | tr -d '/+=' | cut -c1-24)"
KEY_PASSWORD="$STORE_PASSWORD"

"$JAVA_HOME/bin/keytool" -genkeypair -v \
  -keystore "$KEYSTORE" \
  -alias "$ALIAS" \
  -keyalg RSA -keysize 2048 -validity 10950 \
  -storepass "$STORE_PASSWORD" -keypass "$KEY_PASSWORD" \
  -dname "$DNAME" >/dev/null

cat > "$PROPS" <<EOF
# DSH Mobile release signing - sourced by tools/build-apk.sh --release.
# Keep this file and the keystore together, outside the repository.
DSH_KEYSTORE="$KEYSTORE"
DSH_KEY_ALIAS="$ALIAS"
DSH_KEYSTORE_PASSWORD="$STORE_PASSWORD"
DSH_KEY_PASSWORD="$KEY_PASSWORD"
EOF
chmod 600 "$KEYSTORE" "$PROPS"

echo "keystore:   $KEYSTORE"
echo "properties: $PROPS"
echo
echo "Back both up now. Losing them means future releases cannot update an installed APK in place."
echo "Next: tools/build-apk.sh --release"
