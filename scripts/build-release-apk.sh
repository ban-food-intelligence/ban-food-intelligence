#!/usr/bin/env bash
# Builds the signed demo release APK into dist-apk/ and checks that no secret is inside.
#
#   ./scripts/build-release-apk.sh
#
# The demo keystore lives in mobile-kotlin/release-keystore/ (gitignored) and is created on
# first run with a random password stored in mobile-kotlin/local.properties (gitignored).
# It signs demo builds only; a production key would live in a secret store / Play App Signing.
set -euo pipefail
cd "$(dirname "$0")/.."
APP=mobile-kotlin
KS=$APP/release-keystore/demo-release.jks
PROPS=$APP/local.properties

if [ ! -f "$KS" ]; then
  mkdir -p "$(dirname "$KS")"
  PASS=$(openssl rand -hex 16)
  keytool -genkeypair -v -keystore "$KS" -alias demo -keyalg EC -groupname secp256r1 -sigalg SHA256withECDSA \
    -validity 3650 -storepass "$PASS" -keypass "$PASS" \
    -dname "CN=BAN Food Intelligence demo, O=AVATAR demo build, C=TR" >/dev/null 2>&1
  touch "$PROPS"
  grep -v '^DEMO_KEYSTORE_PASSWORD=' "$PROPS" > "$PROPS.tmp" || true
  echo "DEMO_KEYSTORE_PASSWORD=$PASS" >> "$PROPS.tmp" && mv "$PROPS.tmp" "$PROPS"
  echo ">> created demo keystore $KS (password in $PROPS)"
fi

(cd $APP && ./gradlew assembleRelease --console=plain -q)
APK=$APP/build/outputs/apk/release/app-release.apk
[ -f "$APK" ] || APK=$APP/build/outputs/apk/release/mobile-kotlin-release.apk
mkdir -p dist-apk
VER=$(sed -n 's/.*versionName = "\([0-9.]*\).*/\1/p' $APP/build.gradle.kts)
OUT=dist-apk/ban-shop-pos-v${VER}-demo.apk
cp "$(ls $APP/build/outputs/apk/release/*.apk | head -1)" "$OUT"

# Secret check: the NVIDIA key from local.properties must not appear anywhere in the APK.
KEY=$(sed -n 's/^NVIDIA_API_KEY=//p' "$PROPS" 2>/dev/null || true)
TMP=$(mktemp -d)
(cd "$TMP" && unzip -q -o "$OLDPWD/$OUT")
if [ -n "$KEY" ] && grep -r -a -F -q "$KEY" "$TMP"; then
  echo "!! NVIDIA key found inside the APK - do not publish"; rm -rf "$TMP"; exit 1
fi
if grep -r -a -q -E 'nvapi-[A-Za-z0-9_-]{20,}' "$TMP"; then
  echo "!! an nvapi- token pattern is inside the APK - do not publish"; rm -rf "$TMP"; exit 1
fi
rm -rf "$TMP"
echo ">> secret check passed (no NVIDIA key in the APK)"
sha256sum "$OUT"
