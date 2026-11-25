#!/usr/bin/env python3
"""
Mock Xtream Codes server for Manus TV app testing.
Stdlib-only HTTP server behaving like an Xtream Codes provider.
Invented content only.
Style: ThreadingHTTPServer, clean logging, no secrets in logs.
"""

import base64
import http.server
import json
import os
import re
import socket
import sys
import time
import urllib.parse

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8081
POSTERS_DIR = os.path.abspath(os.environ.get("POSTERS_DIR", "F:/Dev/tvdemo-sdk/web2/posters"))
LOG_FILE = os.path.abspath(os.environ.get("MOCK_LOG_FILE", "F:/Dev/tvdemo-sdk/tmp/t90/mock.log"))
os.makedirs(os.path.dirname(LOG_FILE), exist_ok=True)

# 12 real video files cycled across the 60 films:
# sintel.mkv (.mkv), cosmos.webm (.webm), ed.webm (.webm), spring.webm (.webm), A.mp4..H.mp4 (.mp4)
VIDEO_FILES = [
    "F:/Dev/tvdemo-sdk/tmp/A.mp4",
    "F:/Dev/tvdemo-sdk/cc/sintel.mkv",
    "F:/Dev/tvdemo-sdk/tmp/B.mp4",
    "F:/Dev/tvdemo-sdk/cc/cosmos.webm",
    "F:/Dev/tvdemo-sdk/tmp/C.mp4",
    "F:/Dev/tvdemo-sdk/cc/ed.webm",
    "F:/Dev/tvdemo-sdk/tmp/D.mp4",
    "F:/Dev/tvdemo-sdk/cc/spring.webm",
    "F:/Dev/tvdemo-sdk/tmp/E.mp4",
    "F:/Dev/tvdemo-sdk/tmp/F.mp4",
    "F:/Dev/tvdemo-sdk/tmp/G.mp4",
    "F:/Dev/tvdemo-sdk/tmp/H.mp4",
]

LIVE_FILE = "F:/Dev/tvdemo-sdk/web2/www/live_test/sintel/seg_Espanol_0.ts"
if not os.path.exists(LIVE_FILE):
    LIVE_FILE = "F:/Dev/tvdemo-sdk/tmp/A.mp4"
# CI (tools/ci/tv-check.sh): MOCK_MEDIA = one film file used for every film/episode, MOCK_LIVE = the live .ts (default: MOCK_MEDIA)
if os.environ.get("MOCK_MEDIA"):
    VIDEO_FILES = [os.environ["MOCK_MEDIA"]]
    LIVE_FILE = os.environ.get("MOCK_LIVE", os.environ["MOCK_MEDIA"])

MIME_TYPES = {
    "mp4": "video/mp4",
    "mkv": "video/x-matroska",
    "webm": "video/webm",
    "ts": "video/mp2t",
    "jpg": "image/jpeg",
    "jpeg": "image/jpeg",
    "json": "application/json; charset=utf-8",
}

TITLES = [
    "COSMIC DAWN", "NEON SHADOWS", "SILENT ORBIT", "CYBER RUNNER", "THE LAST VOYAGE",
    "DEEP ECHOES", "MIDNIGHT DRIVE", "QUANTUM REALM", "STELLAR DUST", "AURORA SKY",
    "GHOST SIGNAL", "VELOCITY ZERO", "EMERALD PEAK", "IRON HORIZON", "DARK MATTER",
    "SOLAR FLARE", "NEBULA DRIFT", "LOST PROTOCOL", "VAPOR TRAIL", "CHRONO SHIFT",
    "FROZEN STATIC", "ECLIPSE RISING", "TITAN ENGINE", "OBSIDIAN BAY", "PULSAR FLASH",
    "RADIO SILENCE", "ALPHA STATION", "CIRCUIT BREAKER", "VOID RUNNER", "DESERT MIRAGE",
    "HYPER LIGHT", "NIGHT CALLER", "STEEL OCEAN", "RED SECTOR", "FRACTURE LINE",
    "ORBITAL DECAY", "SHADOW FALL", "TWILIGHT ZONE", "INFINITY CORE", "ROGUE PLANET",
    "ZERO HOUR", "PRISM BREAK", "TERMINAL NODE", "VECTOR FIELDS", "STATIC DREAMS",
    "BEYOND HORIZONS", "PHANTOM GRID", "COBALT SKY", "SUB ZERO", "WARP MATRIX",
    "METEOR RAIN", "GRAVITY WELL", "CARBON DRIFT", "ASTRAL PLANE", "NEON HIGHWAY",
    "THUNDER RUN", "DIGITAL RAIN", "OUTER LIMITS", "OMEGA POINT", "FINAL FRONTIER"
]

MESSY_TITLES = [
    "EN - NEON SHADOWS 2021 1080p",
    "[4K] COSMIC DAWN",
    "COSMIC DAWN (MULTI-SUB)",
    "|UK| IRON HORIZON FHD",
    "cyber runner [hevc]",
    "THE LAST VOYAGE (CAM)",
    "DEEP ECHOES 2022 4K HDR",
    "|US| MIDNIGHT DRIVE 1080P",
    "quantum realm [multi-audio]",
    "STELLAR DUST (DUAL-AUDIO) 2020",
]

VOD_CATEGORIES = [
    {"category_id": "1", "category_name": "Action", "parent_id": 0},
    {"category_id": "2", "category_name": "Comedy", "parent_id": 0},
    {"category_id": "3", "category_name": "Drama", "parent_id": 0},
    {"category_id": "4", "category_name": "Sci-Fi", "parent_id": 0},
    {"category_id": "5", "category_name": "Family", "parent_id": 0},
    {"category_id": "6", "category_name": "Documentary", "parent_id": 0},
]

LIVE_CATEGORIES = [
    {"category_id": "1", "category_name": "News", "parent_id": 0},
    {"category_id": "2", "category_name": "Sports", "parent_id": 0},
    {"category_id": "3", "category_name": "Cinema", "parent_id": 0},
]

SERIES_CATEGORIES = [
    {"category_id": "1", "category_name": "Drama Series", "parent_id": 0},
    {"category_id": "2", "category_name": "Sci-Fi Series", "parent_id": 0},
    {"category_id": "3", "category_name": "Action Series", "parent_id": 0},
]

def redact(s: str) -> str:
    s = re.sub(r'([?&]password=)[^&]+', r'\1***', s)
    s = re.sub(r'(/(?:movie|live|series|timeshift)/[^/]+/)[^/]+(/)', r'\1***\2', s)
    return s

def get_film_template(i: int, host: str) -> dict:
    stream_id = 1001 + i
    vfile = VIDEO_FILES[i % len(VIDEO_FILES)]
    ext = vfile.rsplit(".", 1)[1].lower()
    cat_id = str((i % 6) + 1)
    rating = "7.4"
    rating_5 = 3.7
    added = "1672531200"
    icon = f"http://{host}/posters/poster_{i}.jpg"

    if i < 25:
        name = f"{TITLES[i]} (2021)"
    elif i < 35:
        name = MESSY_TITLES[i - 25]
    elif i == 35:
        name = "සඳගල තැන්න"
    elif i == 36:
        name = "EL LABERINTO DEL SUEÑO"
    elif i in (37, 38):
        name = "AURORA SKY"
        cat_id = "1" if i == 37 else "2"
    else:
        name = TITLES[i]

    film = {
        "num": i + 1,
        "name": name,
        "stream_type": "movie",
        "stream_id": stream_id,
        "stream_icon": icon,
        "rating": rating,
        "rating_5based": rating_5,
        "added": added,
        "category_id": cat_id,
        "container_extension": ext,
        "custom_sid": "",
        "direct_source": "",
    }

    # 10 with tmdb numeric field
    if i < 10:
        film["tmdb"] = 5001 + i

    # 8 with empty stream_icon
    if 40 <= i < 48:
        film["stream_icon"] = ""

    # 5 with empty rating
    if 48 <= i < 53:
        film["rating"] = ""
        film["rating_5based"] = 0

    # 3 with empty added
    if 53 <= i < 56:
        film["added"] = ""

    # 2 with category_id missing/null
    if i == 33:
        film["category_id"] = None
    elif i == 34:
        del film["category_id"]

    return film

def get_vod_info_dict(film: dict, i: int, host: str) -> dict:
    vfile = VIDEO_FILES[i % len(VIDEO_FILES)]
    ext = film.get("container_extension", vfile.rsplit(".", 1)[1].lower())
    tmdb_id = film.get("tmdb")

    if i % 10 == 0:
        plot = ""
        genre = ""
    else:
        plot = f"The compelling cinematic journey of {film['name']}."
        genre = "Action, Sci-Fi" if (i % 2 == 0) else "Drama, Thriller"

    info = {
        "movie_image": film.get("stream_icon", ""),
        "tmdb_id": tmdb_id,
        "name": film["name"],
        "o_name": film["name"],
        "releasedate": "2021-05-15",
        "plot": plot,
        "cast": "Lead Star, Supporting Star, Co-Star",
        "director": "Jane Director",
        "genre": genre,
        "duration_secs": 5400,
        "duration": "01:30:00",
        "rating": film.get("rating", ""),
        "backdrop_path": [f"http://{host}/posters/poster_{(i + 1) % 60}.jpg"],
    }

    movie_data = {
        "stream_id": film["stream_id"],
        "name": film["name"],
        "added": film.get("added", ""),
        "category_id": film.get("category_id"),
        "container_extension": ext,
    }

    return {"info": info, "movie_data": movie_data}

ARCHIVE_CHANNELS = {201, 202, 203, 204}

def get_live_channels(host: str) -> list:
    return [
        {"num": 1, "name": "Nova News HD", "stream_type": "live", "stream_id": 201, "stream_icon": f"http://{host}/posters/poster_0.jpg", "epg_channel_id": "nova.news", "added": "1672531200", "category_id": "1", "tv_archive": 1, "tv_archive_duration": 3},
        {"num": 2, "name": "Apex Global News", "stream_type": "live", "stream_id": 202, "stream_icon": f"http://{host}/posters/poster_1.jpg", "epg_channel_id": "apex.news", "added": "1672531200", "category_id": "1", "tv_archive": 1, "tv_archive_duration": 3},
        {"num": 3, "name": "Capital 24/7", "stream_type": "live", "stream_id": 203, "stream_icon": f"http://{host}/posters/poster_2.jpg", "epg_channel_id": "capital.news", "added": "1672531200", "category_id": "1", "tv_archive": 1, "tv_archive_duration": 3},
        {"num": 4, "name": "Velocity Sports 1", "stream_type": "live", "stream_id": 204, "stream_icon": f"http://{host}/posters/poster_3.jpg", "epg_channel_id": "velocity.sports1", "added": "1672531200", "category_id": "2", "tv_archive": 1, "tv_archive_duration": 3},
        {"num": 5, "name": "Velocity Sports 2", "stream_type": "live", "stream_id": 205, "stream_icon": f"http://{host}/posters/poster_4.jpg", "epg_channel_id": "velocity.sports2", "added": "1672531200", "category_id": "2", "tv_archive": 0, "tv_archive_duration": 0},
        {"num": 6, "name": "Arena Live HD", "stream_type": "live", "stream_id": 206, "stream_icon": f"http://{host}/posters/poster_5.jpg", "epg_channel_id": "arena.live", "added": "1672531200", "category_id": "2", "tv_archive": 0, "tv_archive_duration": 0},
        {"num": 7, "name": "Cinema Premiere", "stream_type": "live", "stream_id": 207, "stream_icon": f"http://{host}/posters/poster_6.jpg", "epg_channel_id": "cinema.premiere", "added": "1672531200", "category_id": "3", "tv_archive": 0, "tv_archive_duration": 0},
        {"num": 8, "name": "Action Central TV", "stream_type": "live", "stream_id": 208, "stream_icon": f"http://{host}/posters/poster_7.jpg", "epg_channel_id": "action.central", "added": "1672531200", "category_id": "3", "tv_archive": 0, "tv_archive_duration": 0},
        {"num": 9, "name": "Classic Showcase", "stream_type": "live", "stream_id": 209, "stream_icon": f"http://{host}/posters/poster_8.jpg", "epg_channel_id": "classic.showcase", "added": "1672531200", "category_id": "3", "tv_archive": 0, "tv_archive_duration": 0},
    ]

SERIES_TITLES = [
    "Chronicles of Orbit", "EN - SHADOW FLEET (2019) 1080p", "[4K] THE HIGH GATE S01", "|UK| SILENT SIGNALS (2020) FHD", "Quantum Echo",
    "Neon District", "Solar Horizon", "Cyber Nexus", "Deep Sky", "Iron Bastion",
    "Vapor Legacy", "Velocity Zero", "Emerald Divide", "Midnight Vector", "Titan Protocol",
    "Eclipse Rising", "Obsidian Core", "Pulsar Watch", "Alpha Station", "Circuit Realm",
    "Void Runners", "Hyper Light", "Night Hunters", "Steel Colony", "Red Sector",
    "Fracture Point", "Orbital Twilight", "Infinity Reach", "Rogue Cosmos", "Zero Latitude",
    "Prism Network", "Terminal Signal", "Cobalt Sky", "Warp Boundary", "Meteor Station",
    "සඳගල තැන්න", "Aurora Sky", "Aurora Sky", "Astraea", "Final Frontier"
]

EMPTY_SERIES_COVERS = {5, 12, 18, 24, 30, 34}  # 6 series
EMPTY_SERIES_PLOTS = {4, 11, 21, 29, 39}       # 5 series

def get_series_template(i: int, host: str) -> dict:
    name = SERIES_TITLES[i % len(SERIES_TITLES)]
    cat_id = "1" if (i % 40) == 36 else ("2" if (i % 40) == 37 else str(((i % 40) % 3) + 1))
    cover = "" if (i % 40) in EMPTY_SERIES_COVERS else f"http://{host}/posters/poster_{(i * 3) % 60}.jpg"
    plot = "" if (i % 40) in EMPTY_SERIES_PLOTS else f"Riveting narrative and dramatic encounters in {name}."
    rating = f"{7.0 + (i % 25) * 0.1:.1f}"
    genre = "Drama, Sci-Fi" if (i % 2 == 0) else "Action, Thriller"
    reldate = f"20{18 + (i % 6):02d}-0{1 + (i % 9):02d}-15"
    return {
        "series_id": i + 1,
        "name": name,
        "cover": cover,
        "plot": plot,
        "category_id": cat_id,
        "rating": rating,
        "last_modified": "1672531200",
        "genre": genre,
        "releaseDate": reldate,
    }

def get_series_list(host: str) -> list:
    return [get_series_template(i, host) for i in range(40)]

def make_episode(ep_id: int, ep_num: int, season: int, host: str,
                 plot: str = None, movie_image: str = None,
                 skip_duration_secs: bool = False, title: str = None) -> dict:
    file_idx = ep_id % len(VIDEO_FILES)
    vfile = VIDEO_FILES[file_idx]
    ext = vfile.rsplit(".", 1)[1].lower()

    ep_title = title if title is not None else f"Episode {ep_num}"
    ep_plot = plot if plot is not None else f"Plot summary for {ep_title} in season {season}."
    ep_img = movie_image if movie_image is not None else f"http://{host}/posters/poster_{(ep_id * 7) % 60}.jpg"

    info = {
        "duration": "00:42:10",
        "plot": ep_plot,
        "movie_image": ep_img,
    }
    if not skip_duration_secs:
        info["duration_secs"] = 2530

    return {
        "id": str(ep_id),
        "episode_num": ep_num,
        "title": ep_title,
        "container_extension": ext,
        "season": season,
        "info": info,
    }

def get_series_info_dict(series_id: int, host: str) -> dict:
    all_series = get_series_list(host)
    matched = [s for s in all_series if s["series_id"] == series_id]
    if not matched:
        return {"seasons": [], "info": {}, "episodes": []}

    s_meta = matched[0]
    info = {
        "name": s_meta["name"],
        "cover": s_meta["cover"],
        "plot": s_meta["plot"],
        "genre": s_meta["genre"],
        "rating": s_meta["rating"],
        "releaseDate": s_meta["releaseDate"],
        "backdrop_path": [
            f"http://{host}/posters/poster_{(series_id * 2) % 60}.jpg",
            f"http://{host}/posters/poster_{(series_id * 2 + 1) % 60}.jpg",
        ],
    }

    # Special series_id 11: returns {"episodes": []} (empty)
    if series_id == 11:
        return {
            "seasons": [],
            "info": info,
            "episodes": [],
        }

    # Special series_id 7: 320 episodes over 8 seasons
    if series_id == 7:
        seasons = []
        episodes = {}
        for s in range(1, 9):
            seasons.append({
                "season_number": s,
                "name": f"Season {s}",
                "episode_count": 40,
                "cover": f"http://{host}/posters/poster_{(s * 5) % 60}.jpg",
            })
            ep_list = []
            for ep in range(1, 41):
                ep_id = 200000 + (series_id * 1000) + ((s - 1) * 40) + ep
                ep_list.append(make_episode(ep_id, ep, s, host))
            episodes[str(s)] = ep_list
        return {"seasons": seasons, "info": info, "episodes": episodes}

    # Special series_id 8: 1 season of 1 episode
    if series_id == 8:
        seasons = [{
            "season_number": 1,
            "name": "Season 1",
            "episode_count": 1,
            "cover": f"http://{host}/posters/poster_8.jpg",
        }]
        ep_id = 200000 + (series_id * 1000) + 1
        episodes = {"1": [make_episode(ep_id, 1, 1, host)]}
        return {"seasons": seasons, "info": info, "episodes": episodes}

    # Special series_id 9: episodes with empty plot, empty movie_image, missing duration_secs, and one title like S01E02 - Pilot
    if series_id == 9:
        seasons = [{
            "season_number": 1,
            "name": "Season 1",
            "episode_count": 3,
            "cover": f"http://{host}/posters/poster_9.jpg",
        }]
        ep1_id = 200000 + (series_id * 1000) + 1
        ep2_id = 200000 + (series_id * 1000) + 2
        ep3_id = 200000 + (series_id * 1000) + 3
        episodes = {
            "1": [
                make_episode(ep1_id, 1, 1, host, plot="", movie_image="", skip_duration_secs=True),
                make_episode(ep2_id, 2, 1, host, title="S01E02 - Pilot"),
                make_episode(ep3_id, 3, 1, host),
            ]
        }
        return {"seasons": seasons, "info": info, "episodes": episodes}

    # Special series_id 10: episodes returned as an array of arrays instead of an object keyed by season
    if series_id == 10:
        seasons = [
            {"season_number": 1, "name": "Season 1", "episode_count": 8, "cover": f"http://{host}/posters/poster_10.jpg"},
            {"season_number": 2, "name": "Season 2", "episode_count": 8, "cover": f"http://{host}/posters/poster_11.jpg"},
        ]
        ep_arr_of_arr = []
        for s in range(1, 3):
            s_eps = []
            for ep in range(1, 9):
                ep_id = 200000 + (series_id * 1000) + ((s - 1) * 40) + ep
                s_eps.append(make_episode(ep_id, ep, s, host))
            ep_arr_of_arr.append(s_eps)
        return {"seasons": seasons, "info": info, "episodes": ep_arr_of_arr}

    # Standard series: 2-4 seasons x 6-12 episodes
    num_seasons = 2 + (series_id % 3)
    eps_per_season = 6 + (series_id % 7)
    seasons = []
    episodes = {}
    for s in range(1, num_seasons + 1):
        seasons.append({
            "season_number": s,
            "name": f"Season {s}",
            "episode_count": eps_per_season,
            "cover": f"http://{host}/posters/poster_{(series_id + s) % 60}.jpg",
        })
        ep_list = []
        for ep in range(1, eps_per_season + 1):
            ep_id = 200000 + (series_id * 1000) + ((s - 1) * 40) + ep
            ep_list.append(make_episode(ep_id, ep, s, host))
        episodes[str(s)] = ep_list

    return {"seasons": seasons, "info": info, "episodes": episodes}

def get_epg_listings(stream_id: str) -> dict:
    t_now = int(time.time())
    t_start1 = t_now - 1800
    t_end1 = t_now + 1800
    t_end2 = t_now + 5400
    return {
        "epg_listings": [
            {
                "id": "1",
                "epg_id": "1",
                "title": base64.b64encode(b"Morning Bulletin Live").decode("utf-8"),
                "lang": "en",
                "start": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(t_start1)),
                "end": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(t_end1)),
                "description": base64.b64encode(b"Comprehensive live news coverage.").decode("utf-8"),
                "channel_id": str(stream_id),
                "start_timestamp": str(t_start1),
                "stop_timestamp": str(t_end1),
                "now_playing": 1,
                "has_archive": 0,
            },
            {
                "id": "2",
                "epg_id": "2",
                "title": base64.b64encode(b"Prime Analysis").decode("utf-8"),
                "lang": "en",
                "start": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(t_end1)),
                "end": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(t_end2)),
                "description": base64.b64encode(b"Special guest discussions.").decode("utf-8"),
                "channel_id": str(stream_id),
                "start_timestamp": str(t_end1),
                "stop_timestamp": str(t_end2),
                "now_playing": 0,
                "has_archive": 0,
            },
        ]
    }

def get_simple_data_table(stream_id_str: str) -> dict:
    try:
        sid = int(stream_id_str)
    except Exception:
        sid = 0

    is_archive = sid in ARCHIVE_CHANNELS
    t_now = int(time.time())
    current_hour = (t_now // 3600) * 3600

    program_titles = [
        "Global News Hour", "Financial Markets Live", "Tech Innovations", "World Report",
        "Documentary Prime", "Sports Action Tonight", "Science Frontiers", "Cultural Spotlight",
        "Nightline Digest", "Morning Briefing Live", "Midday Edition", "Focus on Economy",
    ]

    listings = []
    if is_archive:
        for h in range(-72, 7):
            p_start = current_hour + h * 3600
            p_end = p_start + 3600
            p_idx = h + 72
            title_text = program_titles[p_idx % len(program_titles)]
            desc_text = f"Live coverage and in-depth reporting: {title_text}."
            has_arch = 1 if p_end <= t_now else 0

            listings.append({
                "id": str(p_idx + 1),
                "epg_id": str(p_idx + 1),
                "title": base64.b64encode(title_text.encode("utf-8")).decode("utf-8"),
                "lang": "en",
                "start": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(p_start)),
                "end": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(p_end)),
                "description": base64.b64encode(desc_text.encode("utf-8")).decode("utf-8"),
                "channel_id": str(stream_id_str),
                "start_timestamp": str(p_start),
                "stop_timestamp": str(p_end),
                "has_archive": has_arch,
            })
    else:
        for idx, h in enumerate([0, 1]):
            p_start = current_hour + h * 3600
            p_end = p_start + 3600
            title_text = program_titles[idx % len(program_titles)]
            desc_text = f"Live coverage and in-depth reporting: {title_text}."
            listings.append({
                "id": str(idx + 1),
                "epg_id": str(idx + 1),
                "title": base64.b64encode(title_text.encode("utf-8")).decode("utf-8"),
                "lang": "en",
                "start": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(p_start)),
                "end": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(p_end)),
                "description": base64.b64encode(desc_text.encode("utf-8")).decode("utf-8"),
                "channel_id": str(stream_id_str),
                "start_timestamp": str(p_start),
                "stop_timestamp": str(p_end),
                "has_archive": 0,
            })

    return {"epg_listings": listings}

class XtreamHandler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, format, *args):
        safe_path = redact(self.path)
        safe_args = [redact(str(a)) for a in args]
        msg = f"[mock-xtream] {self.command} {safe_path} - {format % tuple(safe_args)}\n"
        sys.stderr.write(msg)
        sys.stderr.flush()
        try:
            with open(LOG_FILE, "a", encoding="utf-8") as lf:
                lf.write(msg)
        except Exception:
            pass

    def check_auth(self, user: str, pwd: str) -> str:
        if user == "demo" and pwd == "demo123":
            return "active"
        elif user == "expired" and pwd == "expired1":
            return "expired"
        else:
            return "invalid"

    def send_json(self, data, status=200):
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "keep-alive")
        self.end_headers()
        self.wfile.write(body)

    def stream_big_vod(self, count: int, host: str, cat_filter: str = None):
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Transfer-Encoding", "chunked")
        self.send_header("Connection", "keep-alive")
        self.end_headers()

        def write_chunk(chunk_bytes: bytes):
            if chunk_bytes:
                self.wfile.write(f"{len(chunk_bytes):X}\r\n".encode("ascii"))
                self.wfile.write(chunk_bytes)
                self.wfile.write(b"\r\n")

        base_films = [get_film_template(i, host) for i in range(60)]
        buf = [b"["]
        written_count = 0
        for i in range(count):
            base = base_films[i % 60]
            item = dict(base)
            item["stream_id"] = 1001 + i
            item["num"] = i + 1
            if cat_filter and item.get("category_id") != cat_filter:
                continue
            if written_count > 0:
                buf.append(b",")
            buf.append(json.dumps(item, ensure_ascii=False).encode("utf-8"))
            written_count += 1
            if len(buf) >= 1000:
                write_chunk(b"".join(buf))
                buf = []

        buf.append(b"]")
        write_chunk(b"".join(buf))
        self.wfile.write(b"0\r\n\r\n")

    def stream_big_series(self, count: int, host: str, cat_filter: str = None):
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Transfer-Encoding", "chunked")
        self.send_header("Connection", "keep-alive")
        self.end_headers()

        def write_chunk(chunk_bytes: bytes):
            if chunk_bytes:
                self.wfile.write(f"{len(chunk_bytes):X}\r\n".encode("ascii"))
                self.wfile.write(chunk_bytes)
                self.wfile.write(b"\r\n")

        base_series = get_series_list(host)
        buf = [b"["]
        written_count = 0
        for i in range(count):
            base = base_series[i % 40]
            item = dict(base)
            item["series_id"] = 1 + i
            if cat_filter and item.get("category_id") != cat_filter:
                continue
            if written_count > 0:
                buf.append(b",")
            buf.append(json.dumps(item, ensure_ascii=False).encode("utf-8"))
            written_count += 1
            if len(buf) >= 1000:
                write_chunk(b"".join(buf))
                buf = []

        buf.append(b"]")
        write_chunk(b"".join(buf))
        self.wfile.write(b"0\r\n\r\n")

    def serve_file_with_range(self, filepath: str, content_type: str, head_only: bool = False):
        if not os.path.exists(filepath):
            self.send_response(404)
            self.end_headers()
            return

        total_size = os.path.getsize(filepath)
        range_header = self.headers.get("Range")

        if not range_header:
            self.send_response(200)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(total_size))
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Connection", "keep-alive")
            self.end_headers()
            if not head_only:
                with open(filepath, "rb") as f:
                    while True:
                        chunk = f.read(65536)
                        if not chunk:
                            break
                        self.wfile.write(chunk)
            return

        m = re.match(r"^bytes=(\d+)-(\d+)?$", range_header.strip())
        if not m:
            self.send_response(416)
            self.send_header("Content-Range", f"bytes */{total_size}")
            self.end_headers()
            return

        start = int(m.group(1))
        end_str = m.group(2)
        end = int(end_str) if end_str else total_size - 1

        if start > end or start >= total_size:
            self.send_response(416)
            self.send_header("Content-Range", f"bytes */{total_size}")
            self.end_headers()
            return

        end = min(end, total_size - 1)
        length = end - start + 1

        self.send_response(206)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Range", f"bytes {start}-{end}/{total_size}")
        self.send_header("Content-Length", str(length))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Connection", "keep-alive")
        self.end_headers()

        if not head_only:
            with open(filepath, "rb") as f:
                f.seek(start)
                remaining = length
                while remaining > 0:
                    chunk = f.read(min(remaining, 65536))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    remaining -= len(chunk)

    def do_HEAD(self):
        self.handle_request(head_only=True)

    def do_GET(self):
        self.handle_request(head_only=False)

    def do_POST(self):
        content_len = int(self.headers.get("Content-Length", 0))
        post_body = self.rfile.read(content_len).decode("utf-8", errors="replace") if content_len > 0 else ""
        post_query = urllib.parse.parse_qs(post_body)
        self.handle_request(head_only=False, extra_query=post_query)

    def handle_request(self, head_only: bool = False, extra_query: dict = None):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        query = urllib.parse.parse_qs(parsed.query)
        if extra_query:
            for k, v in extra_query.items():
                query.setdefault(k, []).extend(v)

        host_header = self.headers.get("Host", f"127.0.0.1:{PORT}")
        host_name = host_header.split(":")[0]
        port_str = host_header.split(":")[1] if ":" in host_header else str(PORT)

        # Failure switches
        fail_param = query.get("fail", [""])[0]
        if fail_param == "500":
            self.send_response(500)
            self.send_header("Content-Type", "text/plain")
            self.send_header("Content-Length", "21")
            self.end_headers()
            if not head_only:
                self.wfile.write(b"Internal Server Error")
            return

        if fail_param == "slow":
            time.sleep(3)

        # 1. Posters: GET /posters/<file>.jpg
        m_poster = re.match(r"^/posters/([^/]+)$", path)
        if m_poster:
            fname = m_poster.group(1)
            fpath = os.path.join(POSTERS_DIR, fname)
            if not os.path.exists(fpath):
                # Fallback to poster_0.jpg if missing
                fpath = os.path.join(POSTERS_DIR, "poster_0.jpg")
            self.serve_file_with_range(fpath, "image/jpeg", head_only=head_only)
            return

        # 2. Movie and Series streams: GET /(movie|series)/<user>/<pass>/<id>.<ext>
        m_stream = re.match(r"^/(movie|series)/([^/]+)/([^/]+)/(\d+)\.([a-zA-Z0-9]+)$", path)
        if m_stream:
            stream_kind = m_stream.group(1)
            user = m_stream.group(2)
            pwd = m_stream.group(3)
            stream_id = int(m_stream.group(4))
            ext = m_stream.group(5).lower()

            auth_status = self.check_auth(user, pwd)
            if auth_status == "invalid":
                self.send_response(401)
                self.send_header("Content-Type", "text/plain")
                self.send_header("Content-Length", "12")
                self.end_headers()
                if not head_only:
                    self.wfile.write(b"Unauthorized")
                return
            elif auth_status == "expired":
                self.send_response(403)
                self.send_header("Content-Type", "text/plain")
                self.send_header("Content-Length", "9")
                self.end_headers()
                if not head_only:
                    self.wfile.write(b"Forbidden")
                return

            if stream_kind == "movie":
                film_idx = (stream_id - 1001) % 60
                vfile = VIDEO_FILES[film_idx % len(VIDEO_FILES)]
            else:
                file_idx = stream_id % len(VIDEO_FILES)
                vfile = VIDEO_FILES[file_idx]

            content_type = MIME_TYPES.get(ext, "video/mp4")
            self.serve_file_with_range(vfile, content_type, head_only=head_only)
            return

        # 3. Live streams: GET /live/<user>/<pass>/<id>.ts
        m_live = re.match(r"^/live/([^/]+)/([^/]+)/(\d+)(?:\.([a-zA-Z0-9]+))?$", path)
        if m_live:
            user = m_live.group(1)
            pwd = m_live.group(2)
            auth_status = self.check_auth(user, pwd)
            if auth_status == "invalid":
                self.send_response(401)
                self.send_header("Content-Type", "text/plain")
                self.send_header("Content-Length", "12")
                self.end_headers()
                if not head_only:
                    self.wfile.write(b"Unauthorized")
                return
            elif auth_status == "expired":
                self.send_response(403)
                self.send_header("Content-Type", "text/plain")
                self.send_header("Content-Length", "9")
                self.end_headers()
                if not head_only:
                    self.wfile.write(b"Forbidden")
                return

            self.serve_file_with_range(LIVE_FILE, "video/mp2t", head_only=head_only)
            return

        # 4. Timeshift: GET /timeshift/<user>/<pass>/<duration_minutes>/<YYYY-MM-DD:HH-MM>/<stream_id>.ts
        m_timeshift = re.match(r"^/timeshift/([^/]+)/([^/]+)/([^/]+)/([^/]+)/(\d+)\.ts$", path)
        if m_timeshift:
            user = m_timeshift.group(1)
            pwd = m_timeshift.group(2)
            duration_minutes = m_timeshift.group(3)
            start_time = m_timeshift.group(4)
            stream_id = int(m_timeshift.group(5))

            auth_status = self.check_auth(user, pwd)
            if auth_status == "invalid":
                self.send_response(401)
                self.send_header("Content-Type", "text/plain")
                self.send_header("Content-Length", "12")
                self.end_headers()
                if not head_only:
                    self.wfile.write(b"Unauthorized")
                return
            elif auth_status == "expired":
                self.send_response(403)
                self.send_header("Content-Type", "text/plain")
                self.send_header("Content-Length", "9")
                self.end_headers()
                if not head_only:
                    self.wfile.write(b"Forbidden")
                return

            if stream_id not in ARCHIVE_CHANNELS:
                self.send_response(404)
                self.send_header("Content-Type", "text/plain")
                self.send_header("Content-Length", "9")
                self.end_headers()
                if not head_only:
                    self.wfile.write(b"Not Found")
                return

            self.serve_file_with_range(LIVE_FILE, "video/mp2t", head_only=head_only)
            return

        # 4. Xtream API: /player_api.php
        if path == "/player_api.php":
            user = query.get("username", [""])[0]
            pwd = query.get("password", [""])[0]
            action = query.get("action", [""])[0]

            auth_status = self.check_auth(user, pwd)

            # Wrong login -> HTTP 200 {"user_info":{"auth":0}}
            if auth_status == "invalid":
                self.send_json({"user_info": {"auth": 0}})
                return

            # No action -> Account authentication / Server info
            if not action:
                if auth_status == "expired":
                    resp = {
                        "user_info": {
                            "username": "expired",
                            "auth": 1,
                            "status": "Expired",
                            "exp_date": "1609459200",
                            "max_connections": "1",
                            "active_cons": "0",
                            "allowed_output_formats": ["m3u8", "ts"],
                        },
                        "server_info": {
                            "url": host_name,
                            "port": port_str,
                            "server_protocol": "http",
                        },
                    }
                else:
                    resp = {
                        "user_info": {
                            "username": "demo",
                            "auth": 1,
                            "status": "Active",
                            "exp_date": "1893456000",
                            "max_connections": "1",
                            "active_cons": "0",
                            "allowed_output_formats": ["m3u8", "ts"],
                        },
                        "server_info": {
                            "url": host_name,
                            "port": port_str,
                            "server_protocol": "http",
                        },
                    }
                self.send_json(resp)
                return

            # Actions requested with expired account
            if auth_status == "expired":
                self.send_json({
                    "user_info": {
                        "username": "expired",
                        "auth": 1,
                        "status": "Expired",
                        "exp_date": "1609459200",
                    }
                })
                return

            # Action dispatch for active account
            if action == "get_live_categories":
                self.send_json(LIVE_CATEGORIES)
                return

            if action == "get_live_streams":
                cat_id = query.get("category_id", [""])[0]
                channels = get_live_channels(host_header)
                if cat_id:
                    channels = [c for c in channels if c.get("category_id") == cat_id]
                self.send_json(channels)
                return

            if action == "get_vod_categories":
                self.send_json(VOD_CATEGORIES)
                return

            if action == "get_vod_streams":
                big_param = query.get("big", [os.environ.get("MOCK_BIG", "0")])[0]
                cat_id = query.get("category_id", [""])[0]
                try:
                    big_count = int(big_param)
                except Exception:
                    big_count = 0

                if big_count > 0:
                    self.stream_big_vod(big_count, host_header, cat_filter=cat_id if cat_id else None)
                    return

                films = [get_film_template(i, host_header) for i in range(60)]
                if cat_id:
                    films = [f for f in films if f.get("category_id") == cat_id]
                self.send_json(films)
                return

            if action == "get_vod_info":
                vod_id_str = query.get("vod_id", [""])[0] or query.get("stream_id", [""])[0]
                try:
                    vod_id = int(vod_id_str)
                except Exception:
                    vod_id = 1001

                idx = (vod_id - 1001) % 60
                film = get_film_template(idx, host_header)
                film["stream_id"] = vod_id
                info_resp = get_vod_info_dict(film, idx, host_header)
                self.send_json(info_resp)
                return

            if action == "get_series_categories":
                self.send_json(SERIES_CATEGORIES)
                return

            if action == "get_series":
                big_param = query.get("big_series", [os.environ.get("MOCK_BIG_SERIES", "0")])[0]
                cat_id = query.get("category_id", [""])[0]
                try:
                    big_count = int(big_param)
                except Exception:
                    big_count = 0

                if big_count > 0:
                    self.stream_big_series(big_count, host_header, cat_filter=cat_id if cat_id else None)
                    return

                series = get_series_list(host_header)
                if cat_id:
                    series = [s for s in series if s.get("category_id") == cat_id]
                self.send_json(series)
                return

            if action == "get_series_info":
                series_id_str = query.get("series_id", [""])[0]
                try:
                    series_id = int(series_id_str)
                except Exception:
                    series_id = 0
                self.send_json(get_series_info_dict(series_id, host_header))
                return

            if action == "get_short_epg":
                stream_id = query.get("stream_id", ["0"])[0]
                self.send_json(get_epg_listings(stream_id))
                return

            if action == "get_simple_data_table":
                stream_id = query.get("stream_id", ["0"])[0]
                self.send_json(get_simple_data_table(stream_id))
                return

            # Unknown action
            self.send_json([])
            return

        self.send_response(404)
        self.end_headers()

def main():
    print(f"Starting mock Xtream server on 0.0.0.0:{PORT}...")
    server = http.server.ThreadingHTTPServer(("0.0.0.0", PORT), XtreamHandler)
    server.serve_forever()

if __name__ == "__main__":
    main()
