#!/usr/bin/env python3
"""CI test playlist for the emulator check: invented channels in several countries, categories and qualities.
Every stream points at the mock Xtream server's live file. Usage: playlist.py <out.m3u> [host:port]"""
import sys

out = sys.argv[1]
host = sys.argv[2] if len(sys.argv) > 2 else "10.0.2.2:8081"
CH = [
    # name, country, group, quality tag
    ("Lanka News 24", "lk", "News", "1080p"), ("Ceylon Today", "lk", "News", ""), ("Hela TV HD", "lk", "Entertainment", ""),
    ("Kandy Music", "lk", "Music", "720p"), ("Ruhunu Sports", "lk", "Sports", "720p"), ("Island Movies 4K", "lk", "Movies", ""),
    ("Lotus Drama", "lk", "Series", "480p"), ("Ape Kids", "lk", "Kids", ""), ("Galle Classics", "lk", "Movies", "720p"),
    ("Delhi Live", "in", "News", "720p"), ("Bollywood Gold", "in", "Movies", "1080p"), ("Mumbai Beats", "in", "Music", ""),
    ("Cricket One HD", "in", "Sports", ""), ("Desi Series", "in", "Series", "720p"), ("Chennai Talkies", "in", "Movies", ""),
    ("London Daily", "gb", "News", "1080p"), ("Britcom", "gb", "Series", ""), ("Premier Goals", "gb", "Sports", "1080p"),
    ("Thames Docs", "gb", "Documentary", "720p"), ("Kids Kingdom UK", "gb", "Kids", ""),
    ("Apex Movies", "us", "Movies", "1080p"), ("Zenith 4K Cinema", "us", "Movies", ""), ("Hollywood Nights", "us", "Movies", "720p"),
    ("Metro News US", "us", "News", ""), ("Wild Planet", "us", "Documentary", "1080p"), ("Jazz Avenue", "us", "Music", ""),
    ("Outback Sport", "au", "Sports", "720p"), ("Sydney Shows", "au", "Entertainment", ""), ("Reef Docs FHD", "au", "Documentary", ""),
    ("Paris Info", "fr", "News", "720p"), ("Cinema Lumiere", "fr", "Movies", ""), ("Ecole Kids", "fr", "Kids", ""),
    ("1st Choice TV", "gb", "Entertainment", ""), ("24 Hour Comedy", "us", "Entertainment", "720p"), ("Ánimo Latino", "us", "Music", ""),
    ("Quiz Arena", "gb", "Entertainment", ""), ("Velvet Series", "us", "Series", "1080p"), ("Xtreme Motors", "au", "Sports", ""),
    ("Yoga Daily", "in", "Lifestyle", ""), ("Nature Calm HD", "lk", "Documentary", ""),
]
with open(out, "w", encoding="utf-8") as f:
    f.write("#EXTM3U\n")
    for i, (name, cc, group, q) in enumerate(CH):
        tid = "".join(c for c in name.lower() if c.isalnum())[:20] + "." + cc
        label = name + (f" ({q})" if q else "")
        f.write(f'#EXTINF:-1 tvg-id="{tid}" group-title="{group}",{label}\n')
        f.write(f"http://{host}/live/demo/demo123/{300 + i}.ts\n")
    # the live subtitle check: HLS + WebVTT served by the playlist server (tools/ci/make-media.sh <out> <www>)
    www = host.split(":")[0] + ":8000"
    f.write('#EXTINF:-1 tvg-id="subtitletest.lk" group-title="Entertainment",Subtitle Test HD\n')
    f.write(f"http://{www}/hls/master.m3u8\n")
print(f"{out}: {len(CH) + 1} channels")
