#!/usr/bin/env bash
# CI test media: film.mp4 (test pattern + tone + mov_text subtitles with long two-speaker cues, for the M1 "never cut" check)
# live.ts (test pattern for live channels) and, with a second argument, <www>/hls/master.m3u8: a live-style HLS channel
# with WebVTT subtitles (the live subtitle path, where the M1 cut-off was seen). Usage: make-media.sh <out-dir> [www-dir]. Needs ffmpeg.
set -euo pipefail
OUT=${1:?out dir}; mkdir -p "$OUT"
cat > "$OUT/subs.srt" <<'SRT'
1
00:00:01,000 --> 00:00:30,000
- Did you remember to bring the tickets for the late show tonight, or did you leave them on the kitchen table again?
- I put them in my coat pocket this morning, right next to the keys and the shopping list your mother gave us.

2
00:00:30,500 --> 00:01:00,000
This is a short line.

3
00:01:00,500 --> 00:01:30,000
A very long single line that keeps going and going so that the subtitle box has to wrap it several times across the screen before it finally ends here.
SRT
ffmpeg -hide_banner -loglevel error -y -f lavfi -i testsrc2=size=1280x720:rate=25 -f lavfi -i sine=frequency=440:sample_rate=48000 \
  -i "$OUT/subs.srt" -t 90 -map 0:v -map 1:a -map 2:s -c:v libx264 -preset veryfast -pix_fmt yuv420p -c:a aac -b:a 96k \
  -c:s mov_text -metadata:s:s:0 language=eng -movflags +faststart "$OUT/film.mp4"
ffmpeg -hide_banner -loglevel error -y -f lavfi -i testsrc=size=1280x720:rate=25 -f lavfi -i sine=frequency=330:sample_rate=48000 \
  -t 120 -c:v libx264 -preset veryfast -pix_fmt yuv420p -c:a aac -b:a 96k -f mpegts "$OUT/live.ts"
if [ -n "${2:-}" ]; then
  mkdir -p "$2/hls"
  ffmpeg -hide_banner -loglevel error -y -f lavfi -i testsrc2=size=1280x720:rate=25 -f lavfi -i sine=frequency=500:sample_rate=48000 -i "$OUT/subs.srt" \
    -t 90 -map 0:v -map 1:a -map 2:s -c:v libx264 -preset veryfast -pix_fmt yuv420p -g 50 -c:a aac -b:a 96k -c:s webvtt \
    -f hls -hls_time 6 -hls_playlist_type vod -master_pl_name master.m3u8 -var_stream_map "v:0,a:0,s:0,sgroup:subs,language:eng" \
    -hls_segment_filename "$2/hls/seg_%v_%03d.ts" "$2/hls/stream_%v.m3u8"
fi
ls -la "$OUT"
