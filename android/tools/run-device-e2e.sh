#!/usr/bin/env bash
# Device e2e: the app's e2e build against the staging remoterd and remoter-agent on
# this laptop (port 9443, own config and device list), over the real tunnel.
#
# The laptop side is driven from here, between instrumentation runs, so the
# app never gets a control channel. Every laptop command below is overridable:
#   STAGING_CTL    remoterctl for the staging config
#   STAGING_STOP   make the staging laptop unreachable ("laptop down")
#   STAGING_START  bring it back
#   WG_DOWN/WG_UP  turn the phone's WireGuard tunnel off and on (needs
#                  "Allow remote control apps" in the WireGuard app)
#   RUNS           how many times to run the whole suite (default 3)
#
#   tools/run-device-e2e.sh pair 'remoter://pair?...'   pair once (you type the code)
#   tools/run-device-e2e.sh run                          everything else, RUNS times
#   tools/run-device-e2e.sh stub                         emulator: what runs with no laptop
set -euo pipefail
cd "$(dirname "$0")/.."

STAGING_CTL=${STAGING_CTL:-"sudo remoterctl --config /etc/remoter/staging/config.toml"}
STAGING_STOP=${STAGING_STOP:-"sudo systemctl stop remoterd-staging.service"}
STAGING_START=${STAGING_START:-"sudo systemctl start remoterd-staging.service"}
TUNNEL=${TUNNEL:-rmt}
WG_DOWN=${WG_DOWN:-"adb shell am broadcast -a com.wireguard.android.action.SET_TUNNEL_DOWN -n com.wireguard.android/.model.TunnelManager\\\$IntentReceiver -e tunnel $TUNNEL"}
WG_UP=${WG_UP:-"adb shell am broadcast -a com.wireguard.android.action.SET_TUNNEL_UP -n com.wireguard.android/.model.TunnelManager\\\$IntentReceiver -e tunnel $TUNNEL"}
RUNS=${RUNS:-3}
RUNNER=me.river.remoter.e2e.suite/androidx.test.runner.AndroidJUnitRunner
PKG=me.river.remoter.e2e

install() {
    ./gradlew -q :app:assembleE2e :e2e:assembleE2e
    adb install -r -t app/build/outputs/apk/e2e/app-e2e.apk >/dev/null
    adb install -r -t e2e/build/outputs/apk/e2e/e2e-e2e.apk >/dev/null
}

# Runs one test method with extra -e args; prints the result lines and any pair code.
instr() {
    local test=$1; shift
    local out
    out=$(adb shell am instrument -r -w "$@" -e class "$PKG.$test" "$RUNNER" 2>&1 | tr -d '\r')
    local code
    code=$(sed -n 's/^INSTRUMENTATION_STATUS: pair_code=//p' <<<"$out" | head -1)
    [[ -n $code ]] && echo ">>> The phone shows $code. Type it at the remoterctl prompt."
    # Status -2 is a failure; -3 (ignored) and -4 (assumption not met) are skips.
    local skipped; skipped=$(grep -cE '^INSTRUMENTATION_STATUS_CODE: -(3|4)' <<<"$out" || true)
    if grep -q '^INSTRUMENTATION_CODE: -1' <<<"$out" && ! grep -q '^INSTRUMENTATION_STATUS_CODE: -2' <<<"$out"; then
        if [[ $skipped -gt 0 ]]; then
            echo "skip  $test: $(sed -n 's/^INSTRUMENTATION_STATUS: stack=org.junit.AssumptionViolatedException: //p' <<<"$out" | head -1)"
        else
            echo "pass  $test"
        fi
    else
        echo "FAIL  $test"; grep -E '^(INSTRUMENTATION_STATUS: stack=|\s+at |AssertionError|java\.)' <<<"$out" | head -15
        return 1
    fi
}

case ${1:-} in
pair)
    link=${2:?"paste the link from: $STAGING_CTL pair --name 'S25 e2e' (run it in another terminal)"}
    install
    # Quoted once for adb's remote shell: the link carries & and ?.
    instr PairingE2eTest -e pair_link "'$link'"
    ;;
run)
    install
    fails=0
    for r in $(seq 1 "$RUNS"); do
        id=r$r$(date +%s)
        echo "== run $r of $RUNS"
        instr FlowE2eTest -e run_id "$id" || fails=$((fails + 1))
        $STAGING_CTL lock on >/dev/null
        instr ErrorsE2eTest#locked -e expect locked || fails=$((fails + 1))
        $STAGING_CTL lock off >/dev/null
        instr ErrorsE2eTest#rate_limited -e expect rate_limited -e run_id "$id" || fails=$((fails + 1))
        sleep 61   # let the mutation bucket refill before the next run
        $STAGING_STOP
        instr ErrorsE2eTest#laptop_down -e expect laptop_down || fails=$((fails + 1))
        $STAGING_START
        eval "$WG_DOWN"
        instr ErrorsE2eTest#vpn_off -e expect vpn_off || fails=$((fails + 1))
        eval "$WG_UP"
        sleep 5
    done
    echo "== $fails failure(s) over $RUNS run(s)"
    [[ $fails == 0 ]]
    ;;
stub)
    # The emulator has no VPN network and no laptop: only the tunnel-off path is real there.
    install
    instr ErrorsE2eTest#vpn_off -e expect vpn_off
    instr FlowE2eTest
    instr PairingE2eTest
    ;;
*)
    sed -n '2,19p' "$0"; exit 2 ;;
esac
