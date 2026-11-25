#!/usr/bin/env python3
"""Measures how many streams of an M3U playlist actually play, from the CI runner (not from Sri Lanka: geo-blocks differ).
Sample = N random entries (fixed seed) + every entry of the named groups. ffprobe each (Mozilla UA, 20 s cap, 8 at a time).
Usage: source-probe.py <m3u url> <out.md> [N] [group,group]. Writes a markdown report; prints the summary line."""
import concurrent.futures as cf, random, re, subprocess, sys, urllib.request, collections

url, out = sys.argv[1], sys.argv[2]
n = int(sys.argv[3]) if len(sys.argv) > 3 else 60
groups = [g for g in (sys.argv[4] if len(sys.argv) > 4 else "").split(",") if g]
text = urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"}), timeout=60).read().decode("utf-8", "replace")
lines = [l.strip("\r") for l in text.split("\n")]
entries = []
for i, l in enumerate(lines):
    if l.startswith("#EXTINF") and i + 1 < len(lines):
        u = next((x for x in lines[i + 1:i + 4] if x and not x.startswith("#")), "")
        g = re.search(r'group-title="([^"]*)"', l)
        entries.append({"name": l.rsplit(",", 1)[-1].strip(), "group": g.group(1) if g else "", "url": u})
by_group = collections.Counter(e["group"] for e in entries)
random.seed(42)
sample = random.sample(entries, min(n, len(entries)))
extra = [e for e in entries if e["group"] in groups and e not in sample]
todo = [("random", e) for e in sample] + [("group:" + e["group"], e) for e in extra]

def probe(e):
    if not e["url"].startswith("http"):
        return "not http"
    try:
        r = subprocess.run(["ffprobe", "-v", "error", "-rw_timeout", "15000000", "-user_agent", "Mozilla/5.0",
                            "-show_entries", "stream=codec_type", "-of", "csv", e["url"]], capture_output=True, text=True, timeout=20)
        if "video" in r.stdout or "audio" in r.stdout:
            return "ok"
        err = (r.stderr.strip().splitlines() or ["no streams"])[-1]
        m = re.search(r"\b([45]\d\d)\b", err)
        return ("HTTP " + m.group(1)) if m else err[:60]
    except subprocess.TimeoutExpired:
        return "timeout"

with cf.ThreadPoolExecutor(8) as ex:
    res = list(ex.map(lambda t: probe(t[1]), todo))
stats = collections.defaultdict(lambda: [0, 0])
for (kind, e), r in zip(todo, res):
    stats[kind][1] += 1
    stats[kind][0] += r == "ok"
fails = collections.Counter(r if r.startswith(("HTTP", "timeout", "not")) else "other error" for r in res if r != "ok")
with open(out, "w") as f:
    f.write(f"# Source probe\n\n{url}\n\n{len(entries)} channels; groups: " + ", ".join(f"{g or '(none)'} {c}" for g, c in by_group.most_common(12)) + "\n\n")
    f.write("| Sample | Playing | Of |\n|---|---|---|\n")
    for k, (ok, tot) in stats.items():
        f.write(f"| {k} | {ok} ({100 * ok // max(1, tot)}%) | {tot} |\n")
    f.write("\nFailures: " + ", ".join(f"{k} {v}" for k, v in fails.most_common()) + "\n\n| Result | Group | Channel |\n|---|---|---|\n")
    for (kind, e), r in zip(todo, res):
        f.write(f"| {r} | {e['group']} | {e['name']} |\n")
ok, tot = stats["random"]
print(f"SUMMARY {len(entries)} channels; random sample {ok}/{tot} playing; " + "; ".join(f"{k} {a}/{b}" for k, (a, b) in stats.items() if k != "random"))
