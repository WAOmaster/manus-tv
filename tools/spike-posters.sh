#!/usr/bin/env bash
# T55 poster spike: poster row + grid, RecyclerView vs framework widgets
# Virtual TV emulator-5554 only. Never connects to 192.168.1.242.
set -euo pipefail
cd "$(dirname "$0")/.."

PYTHON=$(command -v python || command -v python3)
"$PYTHON" - << 'EOF'
import os, sys, time, re, subprocess, statistics, urllib.request

ADB = os.environ.get("ADB", r"F:\Dev\tvdemo-sdk\platform-tools\adb.exe")
DEVICE = "emulator-5554"
PKG = "lk.manus.iptv"
SPIKE_ACT = f"{PKG}/.spike.PosterSpikeActivity"
TMP_DIR = r"F:\Dev\tvdemo-sdk\tmp\t55"
WWW_POSTERS = r"F:\Dev\tvdemo-sdk\web2\posters"

os.makedirs(TMP_DIR, exist_ok=True)

def adb_cmd(args):
    cmd = [ADB, "-s", DEVICE] + args
    res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=False)
    return res.stdout

def adb_screencap(out_path):
    cmd = [ADB, "-s", DEVICE, "exec-out", "screencap", "-p"]
    with open(out_path, "wb") as f:
        subprocess.run(cmd, stdout=f, check=True)

def ensure_server():
    try:
        urllib.request.urlopen("http://127.0.0.1:8088/posters/poster_0.jpg", timeout=2)
        print("Demo web server running at port 8088.")
    except Exception:
        print("Starting demo web server...")
        serve_py = r"F:\Dev\tvdemo-sdk\web2\serve.py"
        subprocess.Popen([sys.executable, serve_py])
        time.sleep(2)

def parse_gfx(out):
    total_frames = 0
    janky_pct = 0.0
    p90 = 0
    p95 = 0

    m_tot = re.search(r"Total frames rendered:\s*(\d+)", out)
    if m_tot: total_frames = int(m_tot.group(1))

    m_jank = re.search(r"Janky frames:\s*\d+\s*\(([\d\.]+)%\)", out)
    if m_jank: janky_pct = float(m_jank.group(1))

    m_p90 = re.search(r"90th percentile:\s*(\d+)ms", out)
    if m_p90: p90 = int(m_p90.group(1))

    m_p95 = re.search(r"95th percentile:\s*(\d+)ms", out)
    if m_p95: p95 = int(m_p95.group(1))

    return total_frames, janky_pct, p90, p95

def parse_mem(out):
    pss_mb = 0.0
    gfx_mb = 0.0

    m_pss = re.search(r"TOTAL PSS:\s*(\d+)", out)
    if m_pss: pss_mb = int(m_pss.group(1)) / 1024.0

    # Look for Graphics: in App Summary
    m_gfx = re.search(r"Graphics:\s*(\d+)", out)
    if m_gfx: gfx_mb = int(m_gfx.group(1)) / 1024.0

    return pss_mb, gfx_mb

def run_trial(mode, shape, run_idx, take_cap=False):
    # 1. Stop app so live playback / background is killed
    adb_cmd(["shell", "am", "force-stop", PKG])
    time.sleep(1)

    # 2. Start spike activity
    adb_cmd(["shell", "am", "start", "-n", SPIKE_ACT, "--es", "mode", mode, "--es", "shape", shape])
    time.sleep(3)

    # 3. Reset gfxinfo
    adb_cmd(["shell", "dumpsys", "gfxinfo", PKG, "reset"])

    # 4. Send keys at 150 ms spacing
    if shape == "row":
        adb_cmd(["shell", "for i in $(seq 1 60); do input keyevent 22; usleep 150000; done"])
    else:
        adb_cmd(["shell", "for i in $(seq 1 10); do input keyevent 20; usleep 150000; done; for i in $(seq 1 10); do input keyevent 19; usleep 150000; done"])

    # 5. Record dumpsys
    gfx_out = adb_cmd(["shell", "dumpsys", "gfxinfo", PKG])
    mem_out = adb_cmd(["shell", "dumpsys", "meminfo", PKG])

    total_frames, janky_pct, p90, p95 = parse_gfx(gfx_out)
    pss_mb, gfx_mb = parse_mem(mem_out)

    if take_cap:
        cap_file = os.path.join(TMP_DIR, f"{mode}_{shape}.png")
        adb_screencap(cap_file)
        print(f"  Captured: {cap_file}")

    print(f"  Run {run_idx}: frames={total_frames}, janky={janky_pct:.2f}%, p90={p90}ms, p95={p95}ms, PSS={pss_mb:.1f}MB, GFX={gfx_mb:.1f}MB")
    return {
        "frames": total_frames,
        "janky_pct": janky_pct,
        "p90": p90,
        "p95": p95,
        "pss_mb": pss_mb,
        "gfx_mb": gfx_mb
    }

def main():
    ensure_server()

    # Verify device
    devs = adb_cmd(["devices"])
    if DEVICE not in devs:
        print(f"ERROR: {DEVICE} not connected!")
        sys.exit(1)

    combinations = [
        ("rv", "row"),
        ("rv", "grid"),
        ("fw", "row"),
        ("fw", "grid")
    ]

    results = {}

    for mode, shape in combinations:
        key = f"{mode}_{shape}"
        print(f"\n==========================================")
        print(f"Testing combination: mode={mode}, shape={shape}")
        print(f"==========================================")
        runs = []
        for r in range(1, 4):
            take_cap = (r == 1)
            data = run_trial(mode, shape, r, take_cap=take_cap)
            runs.append(data)

        # Compute medians
        med = {
            "janky_pct": statistics.median([x["janky_pct"] for x in runs]),
            "p90": statistics.median([x["p90"] for x in runs]),
            "p95": statistics.median([x["p95"] for x in runs]),
            "pss_mb": statistics.median([x["pss_mb"] for x in runs]),
            "gfx_mb": statistics.median([x["gfx_mb"] for x in runs]),
            "screencap": f"/f/Dev/tvdemo-sdk/tmp/t55/{mode}_{shape}.png"
        }
        results[key] = med
        print(f"-> Median: janky={med['janky_pct']:.2f}%, p90={med['p90']}ms, p95={med['p95']}ms, PSS={med['pss_mb']:.1f}MB, GFX={med['gfx_mb']:.1f}MB")

    # Stop app after runs
    adb_cmd(["shell", "am", "force-stop", PKG])

    print("\n\n### Summary Table (Medians across 3 runs)")
    print("| Mode | Shape | Janky % | p90 (ms) | p95 (ms) | PSS (MB) | Graphics (MB) | Screenshot |")
    print("|---|---|---|---|---|---|---|---|")
    for mode, shape in combinations:
        m = results[f"{mode}_{shape}"]
        print(f"| {mode} | {shape} | {m['janky_pct']:.2f}% | {m['p90']} | {m['p95']} | {m['pss_mb']:.1f} | {m['gfx_mb']:.1f} | `{m['screencap']}` |")

if __name__ == "__main__":
    main()
EOF
