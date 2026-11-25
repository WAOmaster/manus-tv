#!/usr/bin/env bash
# Loads one real playlist (tools/ci/source-url.txt) as the app's only source on the emulator and records what a viewer sees:
# channel count, Countries, News, a UK search, then 10 channel flips with a screenshot each and whether each one played.
# Usage: source-check.sh <shots-dir> <playlist url>
set -u
SHOTS=${1:?shots dir}; URL=${2:?playlist url}; mkdir -p "$SHOTS"
PKG=lk.manus.iptv; ACT=$PKG/.PlayerActivity; n=0
shot() { n=$((n + 1)); adb exec-out screencap -p > "$(printf '%s/%02d-%s.png' "$SHOTS" "$n" "$1")"; }
k() { for x in "$@"; do adb shell input keyevent "$x"; sleep 0.7; done; }
open() { adb shell am start -n "$ACT" --es openrail "$1" >/dev/null; sleep "${2:-3}"; }
printf '%s' '<?xml version="1.0" encoding="utf-8" standalone="yes" ?><map><boolean name="setup_done" value="true" /></map>' > /tmp/iptv.xml
adb push /tmp/iptv.xml /data/local/tmp/iptv.xml >/dev/null
adb shell "run-as $PKG mkdir -p shared_prefs && run-as $PKG cp /data/local/tmp/iptv.xml shared_prefs/iptv.xml"
adb logcat -c
adb shell am start -n "$ACT" --es src "'$URL'" >/dev/null
sleep 45; shot first-channel
open foryou 4; shot guide-for-you
open countries 3; k 23; sleep 1; shot countries-menu
open cats 3; k 23; sleep 1; shot categories-menu
open search 3; k 20 20 22 23; sleep 1; shot search-country-picker
k 4; sleep 1 # close the picker
# tune into the United Kingdom list (Countries menu, 2nd row) and flip through it full screen; Up = next channel
open countries 3; k 23; sleep 1; k 20; sleep 1; k 22; sleep 1; k 23; sleep 12; shot uk-first-channel
for i in $(seq 1 10); do k 19; sleep 12; shot "flip-$i"; done
adb logcat -d -v time -s IPTV:* AndroidRuntime:* > "$SHOTS/logcat.txt"
grep -E 'index built|start #|playing after|failed|gave up|alternate|skip' "$SHOTS/logcat.txt" | sed 's/^.*IPTV *([ 0-9]*): //' > "$SHOTS/playback.txt"
echo "starts: $(grep -c 'start #' "$SHOTS/playback.txt")  playing: $(grep -c 'playing after' "$SHOTS/playback.txt")" | tee -a "$SHOTS/playback.txt"
grep -q 'FATAL EXCEPTION' "$SHOTS/logcat.txt" && echo "APP CRASHED" || echo "no crash"
exit 0
