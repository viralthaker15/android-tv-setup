#!/usr/bin/env python3
"""Idle CPU of the whole TV, of logd, and of TV Keys (app + its logcat child), over N seconds.

Same method as CleanYourTV's tools/bench.py: /proc/stat for the device, utime+stime from /proc/<pid>/stat
for processes. Process numbers are % of ONE core (like `top`); the device number is % of all cores.
Runs adb from the Mac and only reads files on the TV.

    ANDROID_SERIAL=<tv-ip>:5555 python3 -I tools/idle_cpu.py --seconds 120 --runs 2
"""
import argparse
import subprocess
import time

PKG = "dev.viral.tvkeys"
TICKS_PER_SECOND = 100   # USER_HZ on Android


def sh(cmd):
    return subprocess.run(["adb", "shell", cmd], capture_output=True, text=True, check=True).stdout


def pids():
    """name -> pid for logd, the TV Keys app and the logcat it started (found by parent pid)."""
    found = {}
    for line in sh("ps -A -o PID,PPID,NAME").splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 3:
            found.setdefault(parts[2], []).append((parts[0], parts[1]))
    out = {}
    if "logd" in found:
        out["logd"] = found["logd"][0][0]
    if PKG in found:
        app = found[PKG][0][0]
        out["tvkeys"] = app
        for pid, ppid in found.get("logcat", []):
            if ppid == app:
                out["tvkeys-logcat"] = pid
    return out


def sample(procs):
    files = " ".join(f"/proc/{p}/stat" for p in procs.values())
    text = sh(f"head -1 /proc/stat; cat {files} 2>/dev/null; cat /proc/uptime")
    lines = text.splitlines()
    cpu = [int(x) for x in lines[0].split()[1:]]
    ticks = {}
    for line in lines[1:]:
        if ") " in line:
            pid = line.split()[0]
            rest = line[line.rindex(")") + 2:].split()
            ticks[pid] = int(rest[11]) + int(rest[12])
    return sum(cpu), cpu[3] + cpu[4], ticks


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seconds", type=int, default=120)
    ap.add_argument("--runs", type=int, default=2)
    args = ap.parse_args()
    procs = pids()
    print("processes:", procs)
    for run in range(1, args.runs + 1):
        total0, idle0, t0 = sample(procs)
        start = time.time()
        time.sleep(args.seconds)
        total1, idle1, t1 = sample(procs)
        elapsed = time.time() - start
        device = 100 * (1 - (idle1 - idle0) / (total1 - total0))
        parts = [f"run {run}: {elapsed:.0f}s  whole TV {device:.1f}%"]
        for name, pid in procs.items():
            if pid in t0 and pid in t1:
                pct = 100 * (t1[pid] - t0[pid]) / (TICKS_PER_SECOND * elapsed)
                parts.append(f"{name} {pct:.2f}% ({t1[pid] - t0[pid]} ticks)")
        print("  ".join(parts), flush=True)


if __name__ == "__main__":
    main()
