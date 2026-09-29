#!/usr/bin/env python3
"""Checks macrobenchmark results against the frame and startup budgets.

P99 frameOverrunMs must be below 0 for folder transitions, sheet drag and
home scroll, and cold start to the first home frame must be under 400 ms.
Only a real device counts: on an emulator this prints the numbers and exits
0, because emulator frame timing says nothing about the S25.
Usage: check_thresholds.py path/to/*-benchmarkData.json
"""
import json
import sys

FRAMES = {"folderTransitions", "sheetDrag", "homeScroll"}
COLD_MS = 400


def main(path):
    d = json.load(open(path))
    build = d.get("context", {}).get("build", {})
    fp = (build.get("fingerprint", "") + build.get("model", "") + build.get("device", "")).lower()
    emulator = any(k in fp for k in ("emulator", "generic", "sdk_gphone", "emu64", "ranchu", "goldfish"))
    fails = []
    for b in d["benchmarks"]:
        name = b["name"]
        if name in FRAMES:
            p99 = b["sampledMetrics"]["frameOverrunMs"]["P99"]
            print(f"{name}: P99 frameOverrunMs {p99:.1f}")
            if p99 >= 0:
                fails.append(f"{name} P99 frameOverrunMs {p99:.1f} is not below 0")
        if name == "coldStartToHome":
            ms = b["metrics"]["timeToInitialDisplayMs"]["median"]
            print(f"{name}: median {ms:.0f} ms")
            if ms >= COLD_MS:
                fails.append(f"cold start {ms:.0f} ms is not under {COLD_MS} ms")
    if emulator:
        print("emulator run: numbers are not meaningful, thresholds not judged")
        return 0
    for f in fails:
        print("FAIL", f)
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
