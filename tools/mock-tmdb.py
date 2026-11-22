#!/usr/bin/env python3
"""
Tiny stdlib HTTP mock server for TMDB on the virtual TV.
Endpoints:
  /3/search/movie - returns 3 candidates (right one + 2 near misses)
  /3/movie/{id}   - returns full canned details
  /t/p/w185/...   - serves poster JPEGs from tools/gen-posters.py
  /t/p/w342/...   - serves poster JPEGs
Switches:
  ?fail=429 (retries once, succeeds on retry)
  ?fail=401 (unauthorized error)
"""

import http.server
import json
import os
import re
import socketserver
import sys
import urllib.parse

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8080
POSTERS_DIR = os.environ.get("POSTERS_DIR", "F:/Dev/tvdemo-sdk/web2/posters")
LOG_FILE = os.environ.get("MOCK_LOG_FILE", "F:/Dev/tvdemo-sdk/tmp/t60/mock.log")
os.makedirs(os.path.dirname(LOG_FILE), exist_ok=True)
fail_429_seen = set()

class TmdbHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        # Clean logging: method, path (with api_key stripped), status code
        safe_path = re.sub(r'([?&]api_key=)[^&]+', r'\1[REDACTED]', self.path)
        safe_args = [re.sub(r'([?&]api_key=)[^&]+', r'\1[REDACTED]', str(a)) for a in args]
        msg = f"[mock-tmdb] {self.command} {safe_path} - {format % tuple(safe_args)}\n"
        sys.stderr.write(msg)
        sys.stderr.flush()
        try:
            with open(LOG_FILE, "a", encoding="utf-8") as lf:
                lf.write(msg)
        except Exception:
            pass

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        query = urllib.parse.parse_qs(parsed.query)

        fail_param = query.get("fail", [""])[0]

        # 401 Unauthorized switch
        if fail_param == "401":
            self.send_response(401)
            self.send_header("Content-Type", "application/json;charset=utf-8")
            body = json.dumps({"status_code": 7, "status_message": "Invalid API key: You must be granted a valid key."}).encode("utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return

        # 429 Too Many Requests switch: fails first attempt, succeeds on retry
        if fail_param == "429":
            req_key = (self.client_address[0], path, str(sorted(query.items())))
            if req_key not in fail_429_seen:
                fail_429_seen.add(req_key)
                self.send_response(429)
                self.send_header("Retry-After", "1")
                self.send_header("Content-Type", "application/json;charset=utf-8")
                body = json.dumps({"status_code": 25, "status_message": "Your request count is over the allowed limit."}).encode("utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return

        # /3/search/movie
        if path == "/3/search/movie":
            q = query.get("query", [""])[0]
            year_str = query.get("year", ["0"])[0]
            try:
                y = int(year_str) if int(year_str) > 0 else 2020
            except Exception:
                y = 2020

            h = abs(hash(q)) % 1000
            id1 = 1000 + h
            id2 = 20000 + h
            id3 = 30000 + h

            c1 = {
                "id": id1,
                "title": q,
                "original_title": q,
                "release_date": f"{y}-05-15",
                "popularity": 85.0,
                "vote_count": 12500,
                "vote_average": 7.6,
                "poster_path": f"/poster_{id1 % 60}.jpg",
                "backdrop_path": f"/poster_{(id1 + 1) % 60}.jpg",
                "overview": f"The story of {q}."
            }
            c2 = {
                "id": id2,
                "title": q,
                "original_title": q,
                "release_date": f"{y - 12}-03-20",
                "popularity": 35.0,
                "vote_count": 1200,
                "vote_average": 6.2,
                "poster_path": f"/poster_{id2 % 60}.jpg",
                "backdrop_path": f"/poster_{(id2 + 1) % 60}.jpg",
                "overview": f"Classic remake of {q}."
            }
            c3 = {
                "id": id3,
                "title": f"{q} Returns",
                "original_title": f"{q} Returns",
                "release_date": f"{y + 3}-11-10",
                "popularity": 18.0,
                "vote_count": 350,
                "vote_average": 5.4,
                "poster_path": f"/poster_{id3 % 60}.jpg",
                "backdrop_path": f"/poster_{(id3 + 1) % 60}.jpg",
                "overview": f"Follow-up adventure to {q}."
            }

            resp = {
                "page": 1,
                "results": [c1, c2, c3],
                "total_pages": 1,
                "total_results": 3
            }
            body = json.dumps(resp).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json;charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return

        # /3/movie/{id}
        m_id = re.match(r"^/3/movie/(\d+)$", path)
        if m_id:
            mid = int(m_id.group(1))
            resp = {
                "id": mid,
                "title": f"Movie {mid}",
                "release_date": "2020-01-01",
                "runtime": 124,
                "overview": "A gripping cinematic experience.",
                "vote_average": 7.8,
                "vote_count": 5400,
                "poster_path": f"/poster_{mid % 60}.jpg",
                "backdrop_path": f"/poster_{(mid + 1) % 60}.jpg",
                "genres": [
                    {"id": 28, "name": "Action"},
                    {"id": 878, "name": "Science Fiction"}
                ],
                "credits": {
                    "cast": [
                        {"name": "Lead Star"},
                        {"name": "Co-Star One"},
                        {"name": "Co-Star Two"},
                        {"name": "Supporting Role"},
                        {"name": "Special Guest"}
                    ]
                },
                "videos": {
                    "results": [
                        {
                            "iso_639_1": "en",
                            "key": "dQw4w9WgXcQ",
                            "site": "YouTube",
                            "type": "Trailer"
                        }
                    ]
                }
            }
            body = json.dumps(resp).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json;charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return

        # Poster images: /t/p/w185/... or /t/p/w342/...
        m_img = re.match(r"^/t/p/(?:w185|w342)/([^/]+)$", path)
        if m_img:
            fname = m_img.group(1)
            fpath = os.path.join(POSTERS_DIR, fname)
            if not os.path.exists(fpath):
                # Fallback to poster_0.jpg if exact not found
                fpath = os.path.join(POSTERS_DIR, "poster_0.jpg")

            if os.path.exists(fpath):
                with open(fpath, "rb") as f:
                    data = f.read()
                self.send_response(200)
                self.send_header("Content-Type", "image/jpeg")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
                return
            else:
                self.send_response(404)
                self.end_headers()
                return

        self.send_response(404)
        self.end_headers()

def main():
    print(f"Starting mock TMDB server on 0.0.0.0:{PORT}...")
    server = socketserver.TCPServer(("0.0.0.0", PORT), TmdbHandler)
    server.serve_forever()

if __name__ == "__main__":
    main()
