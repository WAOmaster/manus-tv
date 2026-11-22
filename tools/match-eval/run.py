#!/usr/bin/env python3
"""Accuracy check for the film matcher, on the PC, with the app's own Java code and the real TMDB API.

  python tools/match-eval/run.py sample   -> titles.tsv: 100 film-length titles from the live guide feed (raw, minutes, channel)
  python tools/match-eval/run.py          -> result.tsv: raw | minutes | channel => MATCH film (year) [score], or NO MATCH + top 3 candidates

Needs TMDB_KEY in local.env (never printed). Candidates are cached in work/cands.json, so re-runs after a Matcher change make no requests.
"""
import gzip, io, json, os, random, re, shutil, subprocess, sys, time, urllib.parse, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
W = os.path.join(HERE, "work")
os.makedirs(W, exist_ok=True)


def get(u):
    return urllib.request.urlopen(urllib.request.Request(u, headers={"User-Agent": "match-eval"}), timeout=30).read()


def read(p):
    return io.open(p, encoding="utf-8").read().splitlines()


def sample(feed="https://waomaster.github.io/manus-iptv-data/", n=100, seed=15):
    epg = json.loads(gzip.decompress(get(feed + "epg.json.gz")))
    ch = {c["id"]: c for c in json.loads(get("https://iptv-org.github.io/api/channels.json"))}
    now, seen = time.time(), {}
    for k, progs in epg.items():
        base = k.split("@")[0]
        if "movies" not in ((ch.get(base) or {}).get("categories") or []):
            continue
        for st, en, t in progs:
            if en - st >= 70 * 60 and st < now + 86400 and t:
                seen.setdefault(t.lower(), (t, (en - st) // 60, base))
    items = list(seen.values())
    random.seed(seed)
    random.shuffle(items)
    io.open(os.path.join(HERE, "titles.tsv"), "w", encoding="utf-8").write("\n".join("%s\t%d\t%s" % i for i in items[:n]) + "\n")
    print(len(items), "film-length titles in the feed;", min(n, len(items)), "sampled")


def java(mode, a, b, extra=None):
    cls = os.path.join(W, "cls")
    if not os.path.isdir(cls):
        os.makedirs(cls)
        src = [os.path.join(ROOT, "src", "lk", "manus", "iptv", *p) for p in (("meta", "Matcher.java"), ("meta", "TitleCleaner.java"), ("data", "Search.java"), ("data", "Channel.java"))]
        subprocess.check_call(["javac", "-encoding", "UTF-8", "-nowarn", "-d", cls, os.path.join(HERE, "MatchEval.java")] + src)
    cmd = ["java", "-cp", cls, "MatchEval", mode, a, b]
    if extra:
        cmd.append(extra)
    subprocess.check_call(cmd)


def show(c, runtime=0):
    if runtime > 0:
        return "%s (%s) %dm v%s" % (c.get("title"), (c.get("release_date") or "")[:4], runtime, c.get("vote_count"))
    return "%s (%s) v%s" % (c.get("title"), (c.get("release_date") or "")[:4], c.get("vote_count"))


def fold_key_py(s):
    if not s:
        return ""
    f = re.sub(r'[\u2013\u2014\-:.,!?\'\u2018\u2019"\u201c\u201d\u00bf\u00a1&_]', ' ', s.lower())
    return ' '.join(f.split())


def is_exact_py(cand, clean_title):
    q = fold_key_py(clean_title)
    if not q:
        return False
    t1 = fold_key_py(cand.get("title") or "")
    if t1 and t1 == q:
        return True
    t2 = fold_key_py(cand.get("original_title") or "")
    return bool(t2 and t2 == q)


def main():
    key = [l.split("=", 1)[1].strip() for l in read(os.path.join(ROOT, "local.env")) if l.startswith("TMDB_KEY=")][0]
    shutil.rmtree(os.path.join(W, "cls"), ignore_errors=True)  # always recompile: the Matcher is what is being tuned
    titles = os.path.join(HERE, "titles.tsv")
    rows = [l.split("\t") for l in read(titles) if l]
    java("clean", titles, os.path.join(W, "clean.tsv"))
    clean = [l.split("\t") for l in read(os.path.join(W, "clean.tsv"))]
    cp = os.path.join(W, "cands.json")
    cache = json.load(io.open(cp, encoding="utf-8")) if os.path.exists(cp) else {}
    lines = []
    for idx, title, year in clean:
        if not title:
            continue
        ck = title + "|" + year
        if ck not in cache:
            q = {"query": title, "include_adult": "false", "api_key": key}
            if year != "0":
                q["year"] = year
            cache[ck] = json.loads(get("https://api.themoviedb.org/3/search/movie?" + urllib.parse.urlencode(q))).get("results", [])[:10]
            time.sleep(0.27)
        mins = rows[int(idx)][1]
        for c in cache[ck]:
            lines.append("\t".join([idx, title, year, str(mins), str(c["id"]), (c.get("title") or "").replace("\t", " "), (c.get("original_title") or "").replace("\t", " "),
                                    (c.get("release_date") or "")[:4] or "0", str(c.get("popularity") or 0), str(c.get("vote_count") or 0)]))
    json.dump(cache, io.open(cp, "w", encoding="utf-8"), ensure_ascii=False)
    io.open(os.path.join(W, "cands.tsv"), "w", encoding="utf-8").write("\n".join(lines) + "\n")
    java("pick", os.path.join(W, "cands.tsv"), os.path.join(W, "pick1.tsv"))
    pick1 = {l.split("\t")[0]: l.split("\t") for l in read(os.path.join(W, "pick1.tsv"))}

    alts_path = os.path.join(W, "alts.json")
    alts_cache = json.load(io.open(alts_path, encoding="utf-8")) if os.path.exists(alts_path) else {}
    alts_changed = False

    def ensure_details(cid):
        nonlocal alts_changed
        if cid not in alts_cache or not isinstance(alts_cache[cid], dict) or "runtime" not in alts_cache[cid]:
            u = f"https://api.themoviedb.org/3/movie/{cid}?api_key={key}&append_to_response=translations,alternative_titles"
            d = json.loads(get(u))
            trans = [t["data"]["title"] for t in d.get("translations", {}).get("translations", []) if t.get("data", {}).get("title")]
            alts = [t["title"] for t in d.get("alternative_titles", {}).get("titles", []) if t.get("title")]
            alts_cache[cid] = {
                "runtime": d.get("runtime") or 0,
                "titles": trans + alts
            }
            alts_changed = True
            time.sleep(0.27)

    for idx, title, year in clean:
        if not title:
            continue
        p = pick1.get(idx)
        cs = cache.get(title + "|" + year, [])
        if p and p[1] != "0":
            ensure_details(p[1])
            for c in cs:
                if str(c["id"]) != p[1] and is_exact_py(c, title):
                    ensure_details(str(c["id"]))
                    break
        else:
            for c in cs[:2]:
                ensure_details(str(c["id"]))

    if alts_changed or not os.path.exists(alts_path):
        json.dump(alts_cache, io.open(alts_path, "w", encoding="utf-8"), ensure_ascii=False)

    alts_lines = []
    for cid, data in alts_cache.items():
        rt = data.get("runtime", 0) if isinstance(data, dict) else 0
        titles_list = data.get("titles", []) if isinstance(data, dict) else data
        if not titles_list:
            alts_lines.append(f"{cid}\t{rt}\t")
        else:
            for t in titles_list:
                if t:
                    alts_lines.append(f"{cid}\t{rt}\t{t.replace(chr(9), ' ')}")
    alts_tsv = os.path.join(W, "alts.tsv")
    io.open(alts_tsv, "w", encoding="utf-8").write("\n".join(alts_lines) + "\n")

    java("pick", os.path.join(W, "cands.tsv"), os.path.join(W, "pick.tsv"), alts_tsv)
    pick = {l.split("\t")[0]: l.split("\t") for l in read(os.path.join(W, "pick.tsv"))}
    out, matched, ambiguous = [], 0, 0
    for idx, title, year in clean:
        raw, mins, chan = rows[int(idx)][:3]
        cs = cache.get(title + "|" + year, [])
        p = pick.get(idx)
        if not title:
            res = "NOT A FILM (cleaner)"
        elif p and p[1] != "0":
            matched += 1
            stage = p[3] if len(p) > 3 and p[3] in ("1", "2") else "1"
            runtime = int(p[4]) if len(p) > 4 else 0
            matched_cand = [c for c in cs if str(c["id"]) == p[1]][0]
            res = "MATCH%s %s [%.2f]" % (stage, show(matched_cand, runtime), float(p[2]))
        else:
            amb = int(p[5]) if p and len(p) > 5 else 0
            res = ("NO MATCH AMBIGUOUS (%d choices)  top: " % amb if amb else "NO MATCH  top: ") + " ; ".join(show(c) for c in cs[:3])
            ambiguous += 1 if amb else 0
        out.append("%s | %sm | %s => %s" % (raw, mins, chan, res))
    io.open(os.path.join(HERE, "result.tsv"), "w", encoding="utf-8").write("\n".join(out) + "\n")
    print(matched, "matched of", len(clean), "|", ambiguous, "AMBIGUOUS (offered as Which film?)")


if sys.argv[1:] == ["sample"]:
    sample()
else:
    main()
