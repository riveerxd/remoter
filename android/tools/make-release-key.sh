#!/usr/bin/env bash
# Makes remoter's release signing key, as root, straight into a root-only
# directory. Nobody types a password: a random one is written next to the key,
# readable by root alone, and sign-release.sh reads it through sudo. Nothing
# running as my normal user can read either file, which is what keeps
# app_cert_sha256 meaningful.
#
#   sudo android/tools/make-release-key.sh
set -euo pipefail

[[ $(id -u) -eq 0 ]] || { echo "run it with sudo" >&2; exit 1; }
dir=/etc/remoter-signing
ks=$dir/release.p12
pw=$dir/password

digest() {
    KS_PASS="$(cat "$pw")" keytool -list -v -keystore "$ks" -storetype PKCS12 -alias remoter -storepass:env KS_PASS \
        | sed -n 's/^[[:space:]]*SHA256: //p' | tr -d ':' | tr 'A-F' 'a-f'
}

if [[ -e $ks ]]; then
    echo "a key already exists at $ks, leaving it alone"
    echo "app_cert_sha256 = \"$(digest)\""
    exit 0
fi

install -d -m 0700 -o root -g root "$dir"
umask 077
head -c 48 /dev/urandom | base64 | tr -d '\n/+=' > "$pw"
KS_PASS="$(cat "$pw")" keytool -genkeypair -keystore "$ks" -storetype PKCS12 -alias remoter \
    -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=remoter" -storepass:env KS_PASS -keypass:env KS_PASS
chmod 0600 "$ks" "$pw"
echo
echo "key: $ks (root 0600), password: $pw (root 0600)"
echo "Back both up offline. Losing them means every phone has to pair again."
echo "app_cert_sha256 = \"$(digest)\""
