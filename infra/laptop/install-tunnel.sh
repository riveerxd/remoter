#!/usr/bin/env bash
# Brings up rmt0 between this laptop and the VPS hub. The laptop key is made
# here and never leaves, the VPS key never leaves the VPS, and the laptop PSK
# comes over SSH once and goes straight into NM.
#
#   install-tunnel.sh <vps-ssh-target>
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
target="${1:?usage: install-tunnel.sh <vps-ssh-target>}"

if nmcli -t -f NAME connection show | grep -qx rmt0; then
    echo "an NM connection called rmt0 already exists, not touching it"; exit 1
fi

work="$(mktemp -d -p "${XDG_RUNTIME_DIR:?}")"
chmod 700 "$work"
trap 'find "$work" -type f -exec shred -u {} +; rmdir "$work"' EXIT

umask 077
wg genkey > "$work/laptop.key"
laptop_pub="$(wg pubkey < "$work/laptop.key")"
echo "laptop public key: $laptop_pub"

"$here/../vps/setup.sh" install "$target" "$laptop_pub"

vps_pub="$(ssh -o BatchMode=yes "$target" 'sudo -n sh -c "wg pubkey < /etc/wireguard/rmt0.key"')"
ssh -o BatchMode=yes "$target" 'sudo -n cat /etc/wireguard/psk-laptop' > "$work/psk"
endpoint="$(ssh -G "$target" | awk '$1 == "hostname" { print $2; exit }')"

python3 - "$here/rmt0.nmconnection.tmpl" "$work" "$vps_pub" "$endpoint" <<'PY'
import sys, os
tmpl, work, vps_pub, endpoint = sys.argv[1:]
text = open(tmpl).read()
text = "\n".join(l for l in text.splitlines() if not l.startswith("#")) + "\n"
text = (text.replace("@LAPTOP_PRIVATE_KEY@", open(f"{work}/laptop.key").read().strip())
            .replace("@VPS_PUBLIC_KEY@", vps_pub)
            .replace("@VPS_ENDPOINT@", endpoint)
            .replace("@PSK_LAPTOP@", open(f"{work}/psk").read().strip()))
with open(f"{work}/rmt0.conf", "w") as f:
    f.write(text)
PY

read -r -p "Create the NetworkManager connection rmt0 (autoconnect on, nothing else touched)? [y/N] " a
[[ "$a" == y ]] || { echo "stopped before touching NetworkManager"; exit 1; }
nmcli connection import type wireguard file "$work/rmt0.conf"
nmcli connection modify rmt0 connection.autoconnect yes
nmcli connection up rmt0

sleep 3
ip -br addr show rmt0
ping -c 3 -W 2 10.66.66.1 && echo "ok: VPS answers over rmt0"
