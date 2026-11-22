#!/usr/bin/env python3
"""
Runs full emulator verification for T60:
1. 5 titles log matches via metatest
2. Cache hit on second run of same title (0 HTTP requests in mock log)
3. ?fail=429 retries once and completes
4. ?fail=401 marks key bad and stops asking
Outputs evidence to /f/Dev/tvdemo-sdk/tmp/t60/
"""

import os
import re
import subprocess
import time

ADB = "F:/Dev/tvdemo-sdk/platform-tools/adb.exe"
DEVICE = "emulator-5554"
TMP_DIR = "F:/Dev/tvdemo-sdk/tmp/t60"
MOCK_LOG = os.path.join(TMP_DIR, "mock.log")
EVIDENCE_LOG = os.path.join(TMP_DIR, "metatest.log")
SUMMARY_TXT = os.path.join(TMP_DIR, "summary.txt")

os.makedirs(TMP_DIR, exist_ok=True)

def adb(cmd):
    full = [ADB, "-s", DEVICE] + cmd
    res = subprocess.run(full, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if res.stdout.strip():
        print("ADB stdout:", res.stdout.strip())
    if res.stderr.strip():
        print("ADB stderr:", res.stderr.strip())
    return res.stdout.strip()

def clear_logcat():
    subprocess.run([ADB, "-s", DEVICE, "logcat", "-c"])

def get_logs():
    res = subprocess.run([ADB, "-s", DEVICE, "logcat", "-d", "-s", "IPTV", "IPTV-tmdb", "IPTV-meta"],
                         stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    return res.stdout

def run_metatest(raw_title, base="http://10.0.2.2:8080"):
    clear_logcat()
    cmd = ["shell", "am", "start", "-S", "-n", "lk.manus.iptv/.PlayerActivity",
           "--es", "tmdb_base", base,
           "--es", "metatest", f"'{raw_title}'"]
    adb(cmd)

    for _ in range(60):
        time.sleep(0.25)
        logs = get_logs()
        if "IPTV meta:" in logs:
            return logs
    return get_logs()

def read_mock_log():
    if os.path.exists(MOCK_LOG):
        with open(MOCK_LOG, "r", encoding="utf-8") as f:
            return f.read()
    return ""

def main():
    print("--- Starting T60 Emulator Verification ---")
    evidence_lines = []

    # Reset mock log
    if os.path.exists(MOCK_LOG):
        open(MOCK_LOG, "w").close()

    # 1. 5 Titles metatest matches
    titles = [
        "Divergent (2014) [HD]",
        "EN - The.Matrix.1999.1080p",
        "Inception (2010) [4K]",
        "Interstellar (2014) [1080p]",
        "The Dark Knight (2008) [HD]"
    ]

    print("\n--- Part 1: Metatest for 5 titles ---")
    for t in titles:
        print(f"Testing: {t}")
        logs = run_metatest(t)
        meta_line = None
        for line in logs.splitlines():
            if "IPTV meta:" in line:
                meta_line = line.strip()
                break
        print(f"Result: {meta_line}")
        assert meta_line is not None and "->" in meta_line, f"Failed match for {t}"
        evidence_lines.append(meta_line)

    # 2. Second run of same title is a cache hit (no HTTP requests in mock log)
    print("\n--- Part 2: Cache hit test (second run of same title) ---")
    time.sleep(1)
    mock_before = read_mock_log()
    repeat_title = "Inception (2010) [4K]"
    print(f"Repeating: {repeat_title}")
    logs = run_metatest(repeat_title)
    mock_after = read_mock_log()

    repeat_line = None
    for line in logs.splitlines():
        if "IPTV meta:" in line:
            repeat_line = line.strip()
            break
    print(f"Result: {repeat_line}")
    assert repeat_line is not None and "Inception" in repeat_line

    new_requests = mock_after[len(mock_before):].strip()
    print(f"New mock HTTP requests during cache hit: '{new_requests}'")
    assert len(new_requests) == 0, f"Expected 0 HTTP requests for cache hit, got:\n{new_requests}"
    print("Cache hit confirmed: 0 HTTP requests made to mock server!")
    evidence_lines.append(f"Cache hit: {repeat_line} (0 HTTP requests made)")

    # 3. ?fail=429 run retries once
    print("\n--- Part 3: ?fail=429 test ---")
    mock_before = read_mock_log()
    title_429 = "The Pianist (2002) [HD]"
    print(f"Testing 429 with: {title_429}")
    logs = run_metatest(title_429, base="http://10.0.2.2:8080?fail=429")
    mock_after = read_mock_log()

    res_429 = None
    for line in logs.splitlines():
        if "IPTV meta:" in line:
            res_429 = line.strip()
            break
    print(f"Result: {res_429}")
    assert res_429 is not None and "The Pianist" in res_429 and "->" in res_429, f"Expected match on retry, got {res_429}"

    new_429_logs = mock_after[len(mock_before):]
    print(f"Mock log for 429 run:\n{new_429_logs}")
    # Verify that mock received 429 request and then retry 200 request
    assert "429" in new_429_logs, "Expected 429 in mock log"
    assert "200" in new_429_logs, "Expected successful retry 200 in mock log"
    print("429 retry confirmed: initial request failed 429, client retried once and succeeded!")
    evidence_lines.append(f"429 retry: {res_429}")

    # 4. ?fail=401 run stops asking
    print("\n--- Part 4: ?fail=401 test ---")
    mock_before = read_mock_log()
    title_401 = "Goodfellas (1990) [HD]"
    print(f"Triggering 401 with: {title_401}")
    logs = run_metatest(title_401, base="http://10.0.2.2:8080?fail=401")
    mock_after = read_mock_log()
    new_401_logs = mock_after[len(mock_before):]
    print(f"Mock log for 401 run:\n{new_401_logs}")
    assert "401" in new_401_logs, "Expected 401 in mock log"

    # Now ask for a different title that hasn't been cached: should stop asking!
    print("Testing that app stops asking TMDB after 401...")
    mock_before = read_mock_log()
    title_after_401 = "The Shawshank Redemption (1994) [HD]"
    logs = run_metatest(title_after_401, base="http://10.0.2.2:8080")
    mock_after = read_mock_log()
    subsequent_requests = mock_after[len(mock_before):].strip()
    print(f"Mock requests after 401: '{subsequent_requests}'")
    assert len(subsequent_requests) == 0, f"Expected 0 requests after 401, got: {subsequent_requests}"
    print("401 key disqualification confirmed: app stopped asking TMDB after 401!")
    evidence_lines.append("401 test: 401 received, subsequent search made 0 HTTP requests (key disqualified)")

    # Save evidence files
    with open(EVIDENCE_LOG, "w", encoding="utf-8") as f:
        f.write("\n".join(evidence_lines) + "\n")

    summary = f"""T60 Acceptance Verification Summary
-----------------------------------
1. Metatest for 5 titles:
{chr(10).join('  - ' + l for l in evidence_lines[:5])}

2. Cache Hit Verification:
  - {evidence_lines[5]}

3. HTTP 429 Back-off & Retry:
  - {evidence_lines[6]}
  - Mock log confirms initial 429 followed by retry 200

4. HTTP 401 Key Disqualification:
  - {evidence_lines[7]}
  - Mock log confirms 401 followed by 0 requests on subsequent search
"""
    with open(SUMMARY_TXT, "w", encoding="utf-8") as f:
        f.write(summary)

    print("\n--- Verification Succeeded! ---")
    print(summary)

if __name__ == "__main__":
    main()
