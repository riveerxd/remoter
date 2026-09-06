#!/usr/bin/env bash
# Sets up the remoter hub on a VPS that also serves other things.
# Run from the laptop. Every change asks first and is checked against a
# before/after snapshot of what else the box is doing.
#
#   setup.sh check     <ssh-target>                  read only
#   setup.sh install   <ssh-target> <laptop-pubkey>
#   setup.sh add-phone <ssh-target> <phone-pubkey>
#   setup.sh status    <ssh-target>                  read only
#   setup.sh remove    <ssh-target>                  exact rollback
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
cmd="${1:-}"
target="${2:-}"
[[ -n "$cmd" && -n "$target" ]] || { sed -n '5,10p' "$0"; exit 2; }

remote() { ssh -o BatchMode=yes -o ConnectTimeout=10 "$target" "$@"; }

confirm() {
    local answer
    read -r -p "$1 [y/N] " answer
    [[ "$answer" == "y" ]] || { echo "stopped, nothing more changed"; exit 1; }
}

is_wg_key() { [[ "$1" =~ ^[A-Za-z0-9+/]{42}[AEIMQUYcgkosw480]=$ ]]; }

# What the rest of the box looks like. install and remove compare this before
# and after, and anything that moved besides our own five rules is a stop.
snapshot() {
    remote 'set -e
        echo "containers $(sudo -n docker ps -q | wc -l)"
        echo "https $(curl -sk -o /dev/null -w "%{http_code}" --max-time 5 https://127.0.0.1/ || true)"
        echo "http $(curl -s -o /dev/null -w "%{http_code}" --max-time 5 http://127.0.0.1/ || true)"
        sudo -n iptables -S | grep -v -- "rmt0" | sha256sum | cut -c1-16 | sed "s/^/iptables-other /"
        sudo -n iptables -t nat -S | sha256sum | cut -c1-16 | sed "s/^/nat /"
        echo "listeners $(sudo -n ss -tln | tail -n +2 | wc -l)"'
}

check() {
    remote 'set -e
        . /etc/os-release; echo "os: $PRETTY_NAME, kernel $(uname -r)"
        if ip link show rmt0 >/dev/null 2>&1; then echo "note: rmt0 already up, 51820 is ours"
        elif sudo -n ss -uln | grep -q ":51820 "; then echo "FAIL: udp 51820 already in use"; exit 1
        else echo "ok: udp 51820 free"; fi
        sudo -n iptables -S DOCKER-USER >/dev/null && echo "ok: DOCKER-USER chain exists"
        [ "$(sysctl -n net.ipv4.ip_forward)" = 1 ] && echo "ok: ip_forward already 1"
        if systemctl is-enabled nftables >/dev/null 2>&1; then echo "WARN: nftables.service enabled, its flush on restart would wipe Docker rules"; fi
        if [ -e /etc/wireguard/rmt0.conf ]; then echo "note: rmt0.conf already present"; fi
        command -v wg >/dev/null && echo "note: wireguard-tools installed" || echo "note: wireguard-tools not installed yet"'
}

# A plain restart runs the new file's teardown against the old tunnel, and if
# it fails the unit can't start again over the leftover interface. Stop, take
# down whatever is left with the current file, then start fresh.
cycle() {
    remote 'sudo -n systemctl stop wg-quick@rmt0 || true
        if ip link show rmt0 >/dev/null 2>&1; then sudo -n wg-quick down rmt0 || sudo -n ip link delete dev rmt0; fi
        sudo -n systemctl reset-failed wg-quick@rmt0 2>/dev/null || true
        sudo -n systemctl start wg-quick@rmt0'
}

render_and_place() {
    local laptop_pub="$1" phone_pub="$2"
    # The template travels over ssh stdin; keys are read and substituted on
    # the VPS, so the private key and PSKs never cross the wire.
    remote "sudo -n python3 - '$laptop_pub' '$phone_pub'" <<PY
import os, sys
laptop_pub, phone_pub = sys.argv[1], sys.argv[2]
tmpl = '''$(cat "$here/rmt0.conf.tmpl")'''
read = lambda p: open(p).read().strip()
phone = ""
if phone_pub:
    phone = "\n[Peer]\n# phone\nPublicKey    = %s\nPresharedKey = %s\nAllowedIPs   = 10.66.66.2/32\n" % (phone_pub, read("/etc/wireguard/psk-phone"))
out = (tmpl.replace("@VPS_PRIVATE_KEY@", read("/etc/wireguard/rmt0.key"))
           .replace("@LAPTOP_PUBLIC_KEY@", laptop_pub)
           .replace("@PSK_LAPTOP@", read("/etc/wireguard/psk-laptop"))
           .replace("@PHONE_PEER@", phone))
os.umask(0o077)
with open("/etc/wireguard/rmt0.conf.new", "w") as f:
    f.write(out)
PY
    echo "--- rendered config, secrets masked:"
    remote "sudo -n sed -E 's/^(PrivateKey|PresharedKey)( *= *).*/\1\2<hidden>/' /etc/wireguard/rmt0.conf.new"
}

case "$cmd" in
check)
    check
    ;;
status)
    remote 'sudo -n wg show rmt0 latest-handshakes 2>/dev/null | sed "s/^[^\t]*\t/peer-handshake-unix /" || echo "rmt0 down"
            sudo -n iptables -S DOCKER-USER | grep rmt0 || true
            systemctl is-active wg-quick@rmt0 || true'
    ;;
install)
    laptop_pub="${3:-}"
    is_wg_key "$laptop_pub" || { echo "laptop public key missing or malformed"; exit 2; }
    check
    before="$(snapshot)"; echo "--- before:"; echo "$before"
    if ! remote 'command -v wg >/dev/null'; then
        confirm "Install wireguard-tools (apt, no service)?"
        remote 'sudo -n DEBIAN_FRONTEND=noninteractive apt-get install -y -q wireguard-tools >/dev/null'
    fi
    confirm "Create the VPS key and the two PSKs in /etc/wireguard (0600, skipped if present)?"
    remote 'sudo -n sh -c "umask 077; mkdir -p /etc/wireguard
        [ -e /etc/wireguard/rmt0.key ]   || wg genkey > /etc/wireguard/rmt0.key
        [ -e /etc/wireguard/psk-laptop ] || wg genpsk > /etc/wireguard/psk-laptop
        [ -e /etc/wireguard/psk-phone ]  || wg genpsk > /etc/wireguard/psk-phone"'
    echo "VPS public key: $(remote 'sudo -n sh -c "wg pubkey < /etc/wireguard/rmt0.key"')"
    render_and_place "$laptop_pub" ""
    confirm "Put this in place as /etc/wireguard/rmt0.conf and start wg-quick@rmt0?"
    remote 'sudo -n mv /etc/wireguard/rmt0.conf.new /etc/wireguard/rmt0.conf && sudo -n systemctl enable wg-quick@rmt0' || true
    cycle || echo "the tunnel didn't come back up cleanly, checking the rest of the box anyway"
    after="$(snapshot)"; echo "--- after:"; echo "$after"
    if [[ "$before" != "$after" ]]; then
        echo "SOMETHING ELSE CHANGED. Rolling back now."
        remote 'sudo -n systemctl disable --now wg-quick@rmt0'
        exit 1
    fi
    echo "ok: nothing else on the box moved"
    ;;
add-phone)
    phone_pub="${3:-}"
    is_wg_key "$phone_pub" || { echo "phone public key missing or malformed"; exit 2; }
    laptop_pub="$(remote "sudo -n awk '/# laptop/{getline; print \$3}' /etc/wireguard/rmt0.conf")"
    before="$(snapshot)"
    render_and_place "$laptop_pub" "$phone_pub"
    confirm "Replace rmt0.conf with this and restart wg-quick@rmt0?"
    remote 'sudo -n mv /etc/wireguard/rmt0.conf.new /etc/wireguard/rmt0.conf' || true
    cycle || echo "the tunnel didn't come back up cleanly, checking the rest of the box anyway"
    after="$(snapshot)"
    [[ "$before" == "$after" ]] || { echo "SOMETHING ELSE CHANGED"; echo "$before"; echo "$after"; exit 1; }
    echo "ok: phone peer added, nothing else moved"
    ;;
remove)
    confirm "Stop and disable wg-quick@rmt0 (removes the five DOCKER-USER rules)? Keys stay in /etc/wireguard."
    remote 'sudo -n systemctl disable --now wg-quick@rmt0'
    remote 'sudo -n iptables -S DOCKER-USER | grep rmt0 && echo "FAIL: rules left behind" || echo "ok: no rmt0 rules left"'
    ;;
*)
    sed -n '5,10p' "$0"; exit 2
    ;;
esac
