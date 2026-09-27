#!/usr/bin/env bash
# Signs the release APK with the root-only release key, then prints the cert
# digest the laptop pins as app_cert_sha256.
#
# Gradle never sees the key: it builds an unsigned APK, and apksigner runs as
# root against /etc/remoter-signing/release.p12 with the random password that
# make-release-key.sh left next to it. Both files are root 0600, so the
# password never passes through a user shell, history, `ps` or the repo.
set -euo pipefail
cd "$(dirname "$0")/.."

SUDO=${SUDO-sudo}
KS=${KS:-/etc/remoter-signing/release.p12}
PW=${PW:-/etc/remoter-signing/password}
ALIAS=${ALIAS:-remoter}
CONFIG=${CONFIG:-/etc/remoter/config.toml}
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
IN=app/build/outputs/apk/release/app-release-unsigned.apk
OUT=app/build/outputs/apk/release/app-release.apk

$SUDO test -f "$KS" || { echo "no keystore at $KS" >&2; exit 1; }

./gradlew -q :app:assembleRelease
[[ -f $IN ]] || { echo "Gradle made no unsigned release APK at $IN" >&2; exit 1; }
# A release APK that is already signed would mean a key reached Gradle.
if "$BT/apksigner" verify "$IN" >/dev/null 2>&1; then
    echo "$IN is already signed: Gradle must not sign release builds" >&2; exit 1
fi
pkg=$("$BT/aapt2" dump packagename "$IN")
[[ $pkg == me.river.remoter ]] || { echo "unexpected package $pkg" >&2; exit 1; }
"$BT/zipalign" -c -P 16 4 "$IN" || { echo "$IN is not aligned" >&2; exit 1; }

rm -f "$OUT"
# Root reads the key and its password file; `file:` keeps it off the argv. No --key-pass:
# given the same file twice, apksigner reads the key password from the file's second line,
# and a PKCS12 key shares the keystore password anyway, which apksigner then uses.
$SUDO "$BT/apksigner" sign --ks "$KS" --ks-type PKCS12 --ks-key-alias "$ALIAS" \
    --ks-pass "file:$PW" --out "$OUT" "$IN"
$SUDO chown "$(id -u):$(id -g)" "$OUT"
rm -f "$OUT.idsig"

certs=$("$BT/apksigner" verify --verbose --print-certs "$OUT")
grep -E '^Verified using .*: true|certificate (DN|SHA-256)' <<<"$certs"
# minSdk 34 gets a v3 signature only, so the line reads "V3.0 Signer: ..." rather than "Signer #1".
digests=$(sed -nE 's/^(Signer #1|V3\.[0-9] Signer): certificate SHA-256 digest: //p' <<<"$certs" | sort -u)
[[ $(wc -l <<<"$digests") == 1 && $digests =~ ^[0-9a-f]{64}$ ]] || { echo "expected exactly one signing certificate, got: $digests" >&2; exit 1; }
digest=$digests

echo
echo "app_cert_sha256 = \"$digest\""
pinned=$(sed -n 's/^app_cert_sha256 *= *"\([^"]*\)".*/\1/p' "$CONFIG" 2>/dev/null | head -1 || true)
if [[ -z $pinned ]]; then
    echo "$CONFIG has no app_cert_sha256 yet; put the line above in it."
elif [[ ${pinned,,} == "$digest" ]]; then
    echo "matches $CONFIG"
else
    echo "does NOT match $CONFIG ($pinned): this APK can't pair" >&2; exit 1
fi
echo "signed: $OUT"
