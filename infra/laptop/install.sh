#!/usr/bin/env bash
# Installs remoter on this laptop. Run it as yourself: it lists every command
# that needs sudo, asks once, then runs them.
#
#   install.sh --app-cert-sha256 <SHA-256 of the release signing cert>
#              [--desktop auto|hyprland|i3] [--terminal auto|kitty|alacritty]
#              [--with-staging --e2e-cert-sha256 <SHA-256 of the e2e cert>]
#
# --desktop and --terminal pick where session windows open. auto, the
# default, decides on every start: Hyprland if you're logged in to it, else i3;
# kitty if it's installed, else Alacritty.
#
# --with-staging also installs the staging instance the device e2e suite runs
# against (ports 9443/9444, /etc/remoter/staging), off unless asked for.
#
# Safe to run again: binaries and units are replaced, but the config, the
# device list and the server key are never overwritten once they exist.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
marker="remoter-e2e-test-build-marker"
bins=(remoterd remoter-agent remoter-exec remoterctl)

die() { echo "install.sh: $*" >&2; exit 1; }

[[ "$(id -u)" -ne 0 ]] || die "run it as your own user, it asks for sudo itself"
digest=""
e2e_digest=""
staging=0
desktop=auto
terminal=auto
while [[ $# -gt 0 ]]; do
    case "$1" in
        --app-cert-sha256) digest="${2:-}"; shift 2 ;;
        --e2e-cert-sha256) e2e_digest="${2:-}"; shift 2 ;;
        --with-staging) staging=1; shift ;;
        --desktop) desktop="${2:-}"; shift 2 ;;
        --terminal) terminal="${2:-}"; shift 2 ;;
        *) die "unknown argument $1" ;;
    esac
done
[[ "$desktop" =~ ^(auto|hyprland|i3)$ ]] || die "--desktop is auto, hyprland or i3"
[[ "$terminal" =~ ^(auto|kitty|alacritty)$ ]] || die "--terminal is auto, kitty or alacritty"
plain="${digest//:/}"
[[ "$plain" =~ ^[0-9A-Fa-f]{64}$ ]] || die "--app-cert-sha256 needs the 64 hex digits of the release signing cert (keytool -list -v)"
e2e_plain="${e2e_digest//:/}"
if [[ $staging -eq 1 ]]; then
    [[ "$e2e_plain" =~ ^[0-9A-Fa-f]{64}$ ]] || die "--with-staging needs --e2e-cert-sha256 with the 64 hex digits of the e2e signing cert"
    [[ "${e2e_plain,,}" != "${plain,,}" ]] || die "the e2e cert must differ from the release cert"
elif [[ -n "$e2e_digest" ]]; then
    die "--e2e-cert-sha256 only goes with --with-staging"
fi

user="$(id -un)"
uid="$(id -u)"
home="$HOME"
# Both go into config files and systemd units, so only characters that need
# no quoting anywhere are accepted, rather than escaping them for each format.
[[ "$user" =~ ^[a-z_][a-z0-9_-]{0,31}$ ]] || die "user name $user has characters remoter won't put in a config"
[[ "$home" =~ ^/[A-Za-z0-9._/-]+$ && "$home" != *..* ]] || die "home directory $home has characters remoter won't put in a config"

# Fills @KEY@ placeholders with bash's own replacement, quoted so no value is
# ever read as a pattern: no sed, no delimiter to escape.
render() {
    local text
    text="$(<"$1")"
    text="${text//@HOME@/"$home"}"
    text="${text//@USER@/"$user"}"
    text="${text//@UID@/"$uid"}"
    text="${text//@DESKTOP@/"$desktop"}"
    text="${text//@TERMINAL@/"$terminal"}"
    text="${text//@APP_CERT_SHA256@/"$plain"}"
    text="${text//@E2E_CERT_SHA256@/"$e2e_plain"}"
    printf '%s\n' "$text"
}

# No feature flags, ever: the e2e knobs must never reach an installed binary.
echo "building release binaries"
cargo build --release --locked --manifest-path "$repo/daemon/Cargo.toml" \
    -p remoterd -p remoter-agent -p remoter-exec -p remoterctl
target="$repo/daemon/target/release"
for b in "${bins[@]}"; do
    if grep -qa "$marker" "$target/$b"; then
        die "$b carries the e2e test marker, refusing to install it"
    fi
done

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
render "$here/config.toml.tmpl" > "$work/config.toml"
render "$here/tmpfiles-remoter.conf" > "$work/tmpfiles.conf"
printf '{"devices":[]}\n' > "$work/devices.json"
cp "$here"/sysusers-remoter.conf "$here"/nftables.conf "$here"/remoterd.service \
    "$here"/remoter-firewall.service "$here"/remoter-attest-refresh.service \
    "$here"/remoter-attest-refresh.timer "$work/"
for b in "${bins[@]}"; do cp "$target/$b" "$work/"; done
if [[ $staging -eq 1 ]]; then
    render "$here/config-staging.toml.tmpl" > "$work/config-staging.toml"
    cp "$here"/remoterd-staging.service "$here"/nftables-staging.conf \
        "$here"/remoter-firewall-staging.conf "$here"/remoter-attest-refresh-staging.conf "$work/"
fi
chmod -R a+rX "$work"

# Every root step is one argv. `plan` prints them, `apply` runs them; both
# walk the same list, so what you approve is exactly what runs.
mode=plan
step() {
    if [[ $mode == plan ]]; then
        printf '  sudo'; printf ' %q' "$@"; printf '\n'
    else
        printf '+ sudo'; printf ' %q' "$@"; printf '\n'
        sudo -- "$@"
    fi
}
# The same, but only when `path` doesn't exist yet: config, device list and
# server key are never replaced.
once() {
    local path="$1"; shift
    if [[ $mode == plan ]]; then
        printf '  only if %q is missing: sudo' "$path"; printf ' %q' "$@"; printf '\n'
    elif sudo -- test -e "$path"; then
        printf '  kept %s\n' "$path"
    else
        step "$@"
    fi
}
# The same, but a failure is reported and the install goes on.
try() {
    if [[ $mode == plan ]]; then
        printf '  sudo'; printf ' %q' "$@"; printf '   (may fail, the install goes on)\n'
    else
        step "$@" || echo "  that failed; pairing needs it: sudo $*"
    fi
}

root_steps() {
    step install -d -o root -g root -m 0755 /etc/remoter
    # Neither drop-in dir is guaranteed: a stock Arch install has no /etc/sysusers.d.
    step install -d -o root -g root -m 0755 /etc/sysusers.d /etc/tmpfiles.d
    step install -o root -g root -m 0755 "$work/remoterd" "$work/remoter-agent" "$work/remoter-exec" "$work/remoterctl" /usr/local/bin/
    step install -o root -g root -m 0644 "$work/sysusers-remoter.conf" /etc/sysusers.d/remoter.conf
    step systemd-sysusers /etc/sysusers.d/remoter.conf
    step install -o root -g root -m 0644 "$work/tmpfiles.conf" /etc/tmpfiles.d/remoter.conf
    step systemd-tmpfiles --create /etc/tmpfiles.d/remoter.conf
    once /etc/remoter/config.toml install -o root -g root -m 0644 "$work/config.toml" /etc/remoter/config.toml
    once /etc/remoter/devices.json install -o root -g root -m 0644 "$work/devices.json" /etc/remoter/devices.json
    step install -o root -g root -m 0644 "$work/nftables.conf" /etc/remoter/nftables.conf
    step install -o root -g root -m 0644 "$work/remoterd.service" "$work/remoter-firewall.service" \
        "$work/remoter-attest-refresh.service" "$work/remoter-attest-refresh.timer" /etc/systemd/system/
    if [[ $staging -eq 1 ]]; then
        step install -d -o root -g root -m 0755 /etc/remoter/staging /etc/systemd/system/remoter-firewall.service.d \
            /etc/systemd/system/remoter-attest-refresh.service.d
        once /etc/remoter/staging/config.toml install -o root -g root -m 0644 "$work/config-staging.toml" /etc/remoter/staging/config.toml
        once /etc/remoter/staging/devices.json install -o root -g root -m 0644 "$work/devices.json" /etc/remoter/staging/devices.json
        step install -o root -g root -m 0644 "$work/nftables-staging.conf" /etc/remoter/nftables-staging.conf
        step install -o root -g root -m 0644 "$work/remoter-firewall-staging.conf" /etc/systemd/system/remoter-firewall.service.d/staging.conf
        step install -o root -g root -m 0644 "$work/remoter-attest-refresh-staging.conf" /etc/systemd/system/remoter-attest-refresh.service.d/staging.conf
        step install -o root -g root -m 0644 "$work/remoterd-staging.service" /etc/systemd/system/
    fi
    step systemctl daemon-reload
    once /etc/remoter/server.key /usr/local/bin/remoterctl init
    step systemctl enable --now remoter-firewall.service
    step systemctl enable remoterd.service remoter-attest-refresh.timer
    step systemctl restart remoterd.service
    step systemctl start remoter-attest-refresh.timer
    if [[ $staging -eq 1 ]]; then
        once /etc/remoter/staging/server.key /usr/local/bin/remoterctl --config /etc/remoter/staging/config.toml init
        step systemctl restart remoter-firewall.service
        step systemctl enable remoterd-staging.service
        step systemctl restart remoterd-staging.service
    fi
    try /usr/local/bin/remoterctl refresh
    if [[ $staging -eq 1 ]]; then
        try /usr/local/bin/remoterctl --config /etc/remoter/staging/config.toml refresh
    fi
}

user_units=(remoter-agent.service)
[[ $staging -eq 1 ]] && user_units+=(remoter-agent-staging.service)

echo
echo "These run as root, in this order:"
root_steps
echo
echo "Then, as you (no sudo):"
for u in "${user_units[@]}"; do
    printf '  install -D -m 0644 %q %q\n' "$here/$u" "$home/.config/systemd/user/$u"
done
echo "  systemctl --user daemon-reload, then enable and restart ${user_units[*]}"
echo
if [[ -e /etc/remoter/config.toml ]] && ! diff -q "$work/config.toml" /etc/remoter/config.toml >/dev/null 2>&1; then
    echo "/etc/remoter/config.toml exists and differs from the template; it is kept as is:"
    diff -u /etc/remoter/config.toml "$work/config.toml" || true
    echo
fi
read -r -p "Run them? [y/N] " answer
[[ "$answer" == y ]] || { echo "stopped, nothing was changed"; exit 1; }

mode=apply
root_steps

for u in "${user_units[@]}"; do
    install -D -m 0644 "$here/$u" "$home/.config/systemd/user/$u"
done
systemctl --user daemon-reload
for u in "${user_units[@]}"; do
    systemctl --user enable "$u"
    systemctl --user restart "$u"
done

echo
echo "installed. Check it with:"
echo "  remoterctl doctor"
echo "  cargo test --manifest-path $repo/daemon/Cargo.toml -p remoterctl --test installed -- --ignored"
