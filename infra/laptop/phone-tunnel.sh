#!/usr/bin/env bash
# Puts the phone on the rmt0 tunnel in one go: makes the phone's WireGuard key,
# adds it as a peer on the VPS hub, and shows the phone's config as a QR in this
# terminal for the WireGuard app to scan.
#
# The phone's private key and its PSK exist only in a 0700 tmpfs dir for the
# seconds this runs, go to the screen as a QR and nowhere else, and are shredded
# on exit. The WireGuard app has no way to take a PSK short of typing 44
# characters, so the whole config is made here and scanned.
#
#   phone-tunnel.sh <vps-ssh-target>
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
target="${1:?usage: phone-tunnel.sh <vps-ssh-target>}"
command -v qrencode >/dev/null || { echo "needs qrencode" >&2; exit 1; }

work="$(mktemp -d -p "${XDG_RUNTIME_DIR:?}")"
chmod 700 "$work"
trap 'find "$work" -type f -exec shred -u {} +; rmdir "$work"' EXIT
umask 077

wg genkey > "$work/phone.key"
phone_pub="$(wg pubkey < "$work/phone.key")"
echo "phone public key: $phone_pub"

"$here/../vps/setup.sh" add-phone "$target" "$phone_pub"

vps_pub="$(ssh -o BatchMode=yes "$target" 'sudo -n sh -c "wg pubkey < /etc/wireguard/rmt0.key"')"
ssh -o BatchMode=yes "$target" 'sudo -n cat /etc/wireguard/psk-phone' > "$work/psk"
endpoint="$(ssh -G "$target" | awk '$1 == "hostname" { print $2; exit }')"

{
    echo "[Interface]"
    echo "Address = 10.66.66.2/32"
    echo "PrivateKey = $(cat "$work/phone.key")"
    echo "MTU = 1280"
    echo
    echo "[Peer]"
    echo "PublicKey = $vps_pub"
    echo "PresharedKey = $(cat "$work/psk")"
    echo "Endpoint = $endpoint:51820"
    echo "AllowedIPs = 10.66.66.3/32"
    echo "PersistentKeepalive = 25"
} > "$work/phone.conf"

echo
echo "In the WireGuard app: + , Scan from QR code, name the tunnel rmt."
echo
qrencode -t ansiutf8 < "$work/phone.conf"
echo
read -r -p "Press Enter once the phone has scanned it (the QR is then wiped from memory) " _
clear
echo "done, turn the tunnel on in the app"
