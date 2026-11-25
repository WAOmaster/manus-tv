#!/usr/bin/env bash
# Drives a DEBUG Manus TV build on an Android TV emulator with remote keys and saves a screenshot per check.
# Runs inside CI (.github/workflows/tv-check.yml) after the APK is installed and the mock servers are up:
#   playlist  http://10.0.2.2:8000/playlist.m3u  (tools/ci/playlist.py)
#   xtream    http://10.0.2.2:8081 demo/demo123   (tools/mock-xtream.py with MOCK_MEDIA)
# Usage: tv-check.sh <shots-dir>. Every step runs even if one fails; the screenshots are the evidence.
set -u
SHOTS=${1:?shots dir}; mkdir -p "$SHOTS"
PKG=lk.manus.iptv; ACT=$PKG/.PlayerActivity
n=0
shot() { n=$((n + 1)); local f; f=$(printf '%s/%02d-%s.png' "$SHOTS" "$n" "$1"); adb exec-out screencap -p > "$f"; echo "shot $f"; }
k() { for x in "$@"; do adb shell input keyevent "$x"; sleep "${KEY_PAUSE:-0.7}"; done; }
open() { adb shell am start -n "$ACT" --es openrail "$1" >/dev/null; sleep "${2:-3}"; }
UP=19 DOWN=20 LEFT=21 RIGHT=22 OK=23 BACK=4
D0=7 D1=8 D2=9 D3=10 D4=11 D5=12 D6=13 D7=14 D8=15 D9=16

echo "== first run: skip the setup wizard (debuggable build), then start with the CI playlist + mock film server"
# the device shell joins adb's arguments into one command line: anything with | or quotes is pushed as a file / single-quoted
printf '%s' '<?xml version="1.0" encoding="utf-8" standalone="yes" ?><map><boolean name="setup_done" value="true" /></map>' > /tmp/iptv.xml
adb push /tmp/iptv.xml /data/local/tmp/iptv.xml >/dev/null
adb shell "run-as $PKG mkdir -p shared_prefs && run-as $PKG cp /data/local/tmp/iptv.xml shared_prefs/iptv.xml && run-as $PKG ls shared_prefs" || echo "WARNING: could not mark setup done"
adb logcat -c
# starts on "Subtitle Test HD" (HLS + WebVTT) with track 1 on (track 0 is the empty CC track every HLS stream exposes): the M1 "never cut" check on the live path
adb shell am start -n "$ACT" --es src "http://10.0.2.2:8000/playlist.m3u" --ez subsdef true --ei sub 1 --es tune "'Subtitle Test'" --es xtream "'http://10.0.2.2:8081|demo|demo123'" >/dev/null
sleep 0.3; shot M1c-boot-a
sleep 0.5; shot M1c-boot-b
sleep 1; shot M1c-boot-c
sleep 8; shot M1a-live-subtitle-two-speakers
sleep 25; shot M1a-live-subtitle-still-two-speakers
sleep 32; shot M1a-live-subtitle-long-single-line

echo "== M2: the For You list, row-number jump, sort chip, keypad letters, filter"
open foryou 4; shot M2-all-number-sort
adb shell input keyevent $D1 $D2; sleep 0.6; shot M2-typed-12-jump # one input call: each call takes ~1 s on the emulator
sleep 2; k $D1; sleep 1.6; k $UP; shot M2-chips-focused
k $OK; sleep 0.5; shot M2-sorted-AZ
k $DOWN; k $D2; shot M2-key2-A
adb shell input keyevent $D2 $D2; sleep 0.6; shot M2-key22-B # repeats must land inside 1.5 s
k $D7; shot M2-key7-P
k $D0; k $UP $RIGHT $OK; sleep 1.5; adb shell input text "news"; sleep 1.5; shot M2-filter-typed
k $BACK $DOWN; sleep 1; shot M2-filtered-list # Back closes the on-screen keyboard (it owns the D-pad while up)
k $BACK; sleep 1; shot M2-filter-cleared-by-back
k $UP; k $OK $OK; sleep 0.5; shot M2-sort-back-to-number

echo "== M2: categories menu letter jump"
open cats 3; k $OK; sleep 1; shot M2-categories-menu
k $D6; shot M2-categories-key6

echo "== M3: search filters without typing"
open search 3; shot M3-search-page # focus starts in the search box
k $DOWN $DOWN; shot M3-chips-focused
k $RIGHT $OK; sleep 1; shot M3-country-picker
k $DOWN $OK; sleep 2; shot M3-country-chosen
k $RIGHT $RIGHT $OK; sleep 2; shot M3-quality-HD
k $LEFT $LEFT $LEFT $OK; sleep 2; shot M3-type-films

echo "== M1 + film subtitles: film from the library, opening message, subtitles on by default, the subtitle menu, Back twice"
open lib 5; shot M1b-library
k $OK; sleep 2; shot M1b-details
k $OK; sleep 0.4; shot M1c-film-opening
sleep 5; shot M1a-film-subtitle-long
sleep 4; shot M1a-film-subtitle-long-2
k $DOWN; sleep 0.8; shot FS-film-bar-subtitle-hint # film subtitles: the bar says "▼ Subtitles: English"
k $DOWN; sleep 1; shot FS-film-subtitle-menu # Down again opens Off / English
k $BACK; sleep 1
k $BACK; sleep 2; shot M1b-back1-details
k $BACK; sleep 2; shot M1b-back2-library-not-channels

echo "== M1c: tuning a live channel"
open foryou 4; k $DOWN $OK; sleep 0.3; shot M1c-tuning-banner

adb logcat -d -v time -s IPTV:* AndroidRuntime:* ActivityManager:W > "$SHOTS/logcat.txt" 2>/dev/null
adb shell dumpsys activity activities | grep -E 'mResumedActivity|mFocusedApp' > "$SHOTS/activity.txt" 2>/dev/null
grep -c 'FATAL EXCEPTION' "$SHOTS/logcat.txt" && echo "APP CRASHED - see logcat.txt" || echo "no crash in logcat"
exit 0
