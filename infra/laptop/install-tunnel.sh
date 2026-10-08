#!/usr/bin/env bash
# Brings up rmt0 between this laptop and the VPS hub. The laptop key is made
# here and never leaves, the VPS key never leaves the VPS, and the laptop PSK
# comes over SSH once and goes straight into NM.
#
# Or, with --direct, without a hub: the phone dials this laptop on one UDP
# port that the home router forwards here. Both keys and the PSK are made in a
# tmpfs dir, the laptop's go into NM through nmcli's editor on stdin (never in
# argv, where any user could read them), the phone's go to the screen as a QR.
# The profile is made switched off, so the hub keeps running until --use.
#
#   install-tunnel.sh <vps-ssh-target>
#   install-tunnel.sh --direct <home-name-or-address> [port]
#   install-tunnel.sh --use direct|hub
#   install-tunnel.sh --remove-direct
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
sysfs="${REMOTER_SYSFS:-/sys}"

has() { nmcli -t -f NAME connection show | grep -qx "$1"; }

# where traffic to the internet leaves; a VPN there would carry the phone's replies away
check_outer_route() {
    local dev
    dev="$(ip route get 1.1.1.1 | awk '{ for (i = 1; i < NF; i++) if ($i == "dev") { print $(i + 1); exit } }')"
    if [[ -z "$dev" || ! -e "$sysfs/class/net/$dev/device" ]]; then
        echo "traffic to the internet leaves through ${dev:-nothing}, not a network card: the phone's replies would go out there. Turn that VPN off first." >&2
        exit 1
    fi
}

use() {
    local on="$1" off
    case "$on" in
        direct) on=rmt0-direct off=rmt0 ;;
        hub) on=rmt0 off=rmt0-direct ;;
        *) echo "usage: install-tunnel.sh --use direct|hub" >&2; exit 1 ;;
    esac
    has "$on" || { echo "no NM connection called $on" >&2; exit 1; }
    [[ "$on" == rmt0-direct ]] && check_outer_route
    if has "$off"; then
        nmcli connection modify "$off" connection.autoconnect no
        nmcli connection down "$off" >/dev/null 2>&1 || true
    fi
    nmcli connection modify "$on" connection.autoconnect yes
    nmcli connection up "$on"
    echo "rmt0 is now $on. On the phone, switch the WireGuard app to the matching tunnel."
}

remove_direct() {
    has rmt0-direct || { echo "no NM connection called rmt0-direct" >&2; exit 1; }
    has rmt0 || { echo "there's no hub connection rmt0 to fall back to, not removing the only tunnel" >&2; exit 1; }
    if nmcli -t -f NAME connection show --active | grep -qx rmt0-direct; then
        echo "rmt0-direct is the one in use, run --use hub first" >&2; exit 1
    fi
    nmcli connection delete rmt0-direct
    echo "removed. Close the UDP forward on the router too."
}

direct() {
    local endpoint="${1:-}" port="${2:-}"
    [[ -n "$endpoint" ]] || { echo "usage: install-tunnel.sh --direct <home-name-or-address> [port]" >&2; exit 1; }
    # a plain name or address, nothing that needs quoting or reads as an option
    if [[ "$endpoint" == -* ]] || ! [[ "$endpoint" =~ ^[A-Za-z0-9.-]+$ || "$endpoint" =~ ^[0-9A-Fa-f:]+$ ]]; then
        echo "endpoint must be a plain host name, IPv4 or IPv6 address: $endpoint" >&2; exit 1
    fi
    has rmt0-direct && { echo "an NM connection called rmt0-direct already exists, not touching it" >&2; exit 1; }
    local used
    used="$(ss -Hlun | awk '{ n = split($4, a, ":"); print a[n] }')"
    if [[ -z "$port" ]]; then
        # high and random, so it's on nobody's list of WireGuard ports
        for _ in $(seq 50); do
            port=$(( 49152 + RANDOM % 16384 ))
            grep -qx "$port" <<< "$used" || break
        done
    fi
    if ! [[ "$port" =~ ^[0-9]+$ ]] || (( port < 1 || port > 65535 )); then
        echo "port must be 1 to 65535: $port" >&2; exit 1
    fi
    grep -qx "$port" <<< "$used" && { echo "UDP $port is already in use here" >&2; exit 1; }
    check_outer_route

    local host="$endpoint"
    [[ "$endpoint" == *:* ]] && host="[$endpoint]"
    case "$endpoint" in
        10.* | 192.168.* | 172.1[6-9].* | 172.2[0-9].* | 172.3[01].* | f[cd]* | fe80:*)
            echo "note: $endpoint is a private address, so this only works while the phone is on the same network" ;;
    esac

    # not local: the trap runs after this function has returned
    work="$(mktemp -d -p "${XDG_RUNTIME_DIR:?}")"
    chmod 700 "$work"
    trap 'find "$work" -type f -exec shred -u {} +; rmdir "$work"' EXIT
    umask 077
    wg genkey > "$work/laptop.key"
    wg genkey > "$work/phone.key"
    wg genpsk > "$work/psk"
    local laptop_pub phone_pub
    laptop_pub="$(wg pubkey < "$work/laptop.key")"
    phone_pub="$(wg pubkey < "$work/phone.key")"

    read -r -p "Create the NetworkManager connection rmt0-direct on UDP $port (switched off, the hub keeps running)? [y/N] " a
    [[ "$a" == y ]] || { echo "stopped before touching NetworkManager"; exit 1; }
    nmcli connection add type wireguard con-name rmt0-direct ifname rmt0 connection.autoconnect no \
        ipv4.method manual ipv4.addresses 10.66.66.3/32 ipv6.method disabled wireguard.listen-port "$port" >/dev/null
    printf 'set wireguard.private-key %s\nset wireguard.peers %s allowed-ips=10.66.66.2/32 preshared-key=%s\nsave\nquit\n' \
        "$(cat "$work/laptop.key")" "$phone_pub" "$(cat "$work/psk")" | nmcli connection edit rmt0-direct >/dev/null
    [[ "$(nmcli -g wireguard.listen-port connection show rmt0-direct)" == "$port" ]] || { echo "NM didn't keep the listen port" >&2; exit 1; }

    {
        echo "[Interface]"
        echo "Address = 10.66.66.2/32"
        echo "PrivateKey = $(cat "$work/phone.key")"
        echo "MTU = 1280"
        echo
        echo "[Peer]"
        echo "PublicKey = $laptop_pub"
        echo "PresharedKey = $(cat "$work/psk")"
        echo "Endpoint = $host:$port"
        echo "AllowedIPs = 10.66.66.3/32"
        echo "PersistentKeepalive = 25"
    } > "$work/phone.conf"
    echo
    echo "In the WireGuard app: + , Scan from QR code. Name it rmt once you switch; keep the hub one under another name to switch back later."
    echo
    qrencode -t ansiutf8 < "$work/phone.conf"
    echo
    read -r -p "Press Enter once the phone has scanned it (the QR is then wiped from memory) " _
    clear

    local lan
    lan="$(ip route get 1.1.1.1 | awk '{ for (i = 1; i < NF; i++) if ($i == "src") { print $(i + 1); exit } }')"
    echo "On the home router: forward UDP $port to $lan, and give this laptop a fixed lease for that address."
    echo "Then: install-tunnel.sh --use direct"
}

case "${1:-}" in
    --direct) shift; direct "$@"; exit ;;
    --use) use "${2:-}"; exit ;;
    --remove-direct) remove_direct; exit ;;
esac

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
