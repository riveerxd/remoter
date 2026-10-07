# remoter

Start and stop `claude` Remote Control sessions on my laptop from my phone.

I kept wanting to kick off a session in some project folder while away from
the desk, then pick it up in the Claude app. remoter is the bit in between: an
Android app that browses folders on the laptop and starts a session there, and
a couple of small daemons on the laptop that do the actual work.

It's built for exactly one person, one laptop and one phone. Arch, Hyprland,
kitty, a Samsung with StrongBox. If your setup is different, expect to change
things.

## How it fits together

```
phone (app, 10.66.66.2) ──wg── VPS hub (10.66.66.1) ──wg── laptop (10.66.66.3)
                                                             remoterd  :8443 mTLS
                                                                │ unix socket
                                                             remoter-agent (your user)
                                                                │ systemd-run --user --scope
                                                             kitty ── remoter-exec ── claude
```

- WireGuard (`rmt0`): the laptop has no public address, so a cheap VPS is
  the hub. Only the phone and the laptop are peers, and the VPS only forwards
  between them.
- remoterd is the only thing listening. System service, own user, no home,
  no internet, heavy systemd sandbox. Talks mTLS 1.3 with pinned keys, bound
  to `rmt0` only.
- remoter-agent runs as you (user service). It checks every request
  signature again on its own, so a broken remoterd still can't start anything.
- remoter-exec runs inside the kitty window, starts `claude` and keeps the
  window around after it exits so you can see why.
- remoterctl is the admin tool: `init`, `pair`, `devices`, `revoke`,
  `lock on|off`, `log`, `status`, `doctor`.

Sessions open as kitty windows on Hyprland workspace 9, each in its own
`rc-<id>.scope`, so restarting the agent doesn't kill them.

## Security, roughly

- Every action that changes something (start, end, mkdir, resume) is signed
  by a P-256 key in the phone's StrongBox that needs a fingerprint per use.
  Reads (folder list, sessions, terminal tail) only need the TLS client key.
- Pairing checks Android key attestation against Google's roots: right app,
  right signing cert, locked bootloader, recent patch level. It's checked again
  once a day.
- Three bad signatures or one reused nonce locks the laptop side until
  `sudo remoterctl lock off`.
- The laptop firewall only restricts traffic coming in on `rmt0`. Everything
  else on the box is left alone.

What it doesn't protect against: someone with your unlocked phone can read
folder names and terminal output. They can't start or end anything without
your finger.

## Layout

```
daemon/    Rust workspace: remoterd, remoter-agent, remoter-exec, remoterctl,
           remoter-proto (wire types + fixtures), remoter-auth, remoter-attest
android/   the app: Kotlin, Compose, Hilt. core/* and feature/* modules
infra/     install scripts, systemd units, nftables, WireGuard templates
docs/      notes on claude CLI behaviour the code depends on
```

## Building

Daemons:

```sh
cd daemon
cargo build --release --locked
cargo test --workspace
```

Some tests create unix sockets under `daemon/target/tmp`, so with the repo
in a deeply nested path they fail with `path must be shorter than SUN_LEN`.
Clone it somewhere short.

App:

```sh
cd android
./gradlew assembleDebug testDebugUnitTest
```

Release builds need a signing key, see `android/tools/make-release-key.sh` and
`sign-release.sh`.

## Installing

1. VPS: `infra/vps/setup.sh check <host>`, then `install-tunnel.sh <host>` on
   the laptop. It adds to the VPS firewall instead of replacing it.
2. Phone tunnel: `infra/laptop/phone-tunnel.sh <host>` shows a QR for the
   WireGuard app.
3. Laptop: `infra/laptop/install.sh --app-cert-sha256 <release cert digest>`.
   It prints every sudo step before running any of them.
4. `sudo remoterctl pair --name phone`, open the link on the phone.
5. `remoterctl doctor` should be all green.

## Tests

- `cargo test --workspace` for the daemons. `e2e-host` runs the real daemons
  against a fake `claude` and a software phone.
- Some tests need root or a real desktop session and are `#[ignore]`d.
- `android/tools/run-device-e2e.sh` runs the app's e2e build against a staging
  instance on ports 9443/9444 (install with `--with-staging`).

## License

MIT, see `LICENSE`. The attestation test vectors in
`daemon/remoter-attest/testdata/keyattestation` come from Google's
keyattestation library and keep their own Apache 2.0 license.
