package lk.manus.iptv;

import android.util.Log;
import android.view.View;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lk.manus.iptv.player.Engine;
import lk.manus.iptv.player.SubPref;

/** Subtitle and audio tracks, the caption line and English translation. */
final class Subs {
    final PlayState ps;

    Subs(PlayState ps) { this.ps = ps; }

    private static final String TAG = "IPTV";

    private List<Engine.Track> subs = new ArrayList<>();
    private boolean audioRestored;
    private int subSel = -1;
    private boolean subRestored;
    private boolean shownAny;
    private boolean trWarned;
    private String trSrc = "";
    private String detCand = "";
    private int detAgree;

    final Runnable rescanR = new Runnable() { @Override public void run() { if (ps.playing && subs.isEmpty()) scanTracks(); } };

    void clearSubs() {
        ps.h.removeCallbacks(rescanR);
        subs = new ArrayList<>();
        ps.curCue = "";
        ps.trCue = "";
        ps.trOn = false;
        subSel = -1;
        subRestored = false;
        ps.audios = new ArrayList<>();
        audioRestored = false;
        shownAny = false;
        ps.subTv.setVisibility(View.GONE);
        ps.bCC.setVisibility(View.GONE);
        ps.guide.setCC(false);
    }

    /** Lists the stream's subtitle tracks; shows the CC badge; re-applies the choice remembered for this channel. */
    void scanTracks() {
        Engine e = ps.eng;
        if (e == null || !ps.playing) return;
        subs = e.subtitleTracks();
        StringBuilder log = new StringBuilder();
        for (Engine.Track x : subs) log.append(' ').append(x.mime).append('/').append(x.lang);
        if (!subs.isEmpty() || !ps.h.hasCallbacks(rescanR)) Log.i(TAG, "subtitle tracks:" + (subs.isEmpty() ? " none" : log));
        ps.audios = e.audioTracks();
        if (ps.audios.size() > 1 && !audioRestored && ps.previewCh == null && ps.playCh != null) {
            audioRestored = true;
            String key = ps.store.audioChoice(ps.playCh.url);
            for (int i = 0; i < ps.audios.size() && !key.isEmpty(); i++) if (audioKey(i).equals(key)) { if (!ps.audios.get(i).sel) e.selectAudio(ps.audios.get(i).id); break; }
        }
        boolean any = !subs.isEmpty();
        boolean real = shownAny; // an undeclared CC track (exposed on every HLS stream) only counts once a caption has shown
        for (Engine.Track x : subs) if (!x.mime.contains("cea-") || !x.lang.isEmpty() && !x.lang.equals("und")) real = true;
        ps.bCC.setVisibility(real ? View.VISIBLE : View.GONE);
        ps.guide.setCC(real);
        if (any && !subRestored && ps.previewCh == null && ps.playCh != null) {
            subRestored = true;
            String key = ps.store.subChoice(ps.playCh.url);
            if (ps.dbgSub >= 0 && ps.dbgSub < subs.size()) selectSub(ps.dbgSub, false);
            else if (!key.isEmpty()) { for (int i = 0; i < subs.size(); i++) if (subKey(i).equals(key)) { selectSub(i, false); break; } }
            else if (ps.store.subsDefault()) selectSub(SubPref.pick(subs), false);
        }
    }

    private String subKey(int i) {
        String l = subs.get(i).lang;
        return l.isEmpty() || l.equals("und") ? "#" + i : l;
    }

    private String subName(int i) { return trackName(subs.get(i), i); }

    /** Chooser label: English tracks as they are; others say what will happen to them. */
    private String subLabel(int i) {
        Engine.Track x = subs.get(i);
        String n = subName(i);
        if (!ps.store.subTranslate() || SubPref.isEnglish(x.lang)) return n;
        String l = SubPref.norm(x.lang);
        if (l.isEmpty()) return n + " → English if needed";
        return (n.endsWith(" (CC)") ? n.substring(0, n.length() - 5) : n) + " → English (translated)" + (n.endsWith(" (CC)") ? " (CC)" : "");
    }

    private String audioKey(int i) {
        String l = ps.audios.get(i).lang;
        return l.isEmpty() || l.equals("und") ? "#" + i : l;
    }

    String trackName(Engine.Track x, int i) {
        String n = "";
        if (!x.lang.isEmpty() && !x.lang.equals("und")) {
            Locale lo = new Locale(x.lang);
            n = lo.getDisplayLanguage(Locale.ENGLISH);
            if (n.equals(x.lang) && x.lang.length() == 3) // ISO 639-2 ("eng")
                for (String c : Locale.getISOLanguages()) { Locale t = new Locale(c); try { if (t.getISO3Language().equals(x.lang)) { n = t.getDisplayLanguage(Locale.ENGLISH); break; } } catch (Exception ignored) { } }
        }
        boolean cea = x.mime.contains("cea-");
        if (n.isEmpty()) n = x.label.isEmpty() ? (cea ? "Captions" : "Track " + (i + 1)) : x.label;
        return cea ? n + " (CC)" : n;
    }

    /** si = -1 turns subtitles off. persist = remember the choice for this channel. */
    private void selectSub(int si, boolean persist) {
        Engine e = ps.eng;
        if (e != null && !e.selectSubtitle(si < 0 ? -1 : subs.get(si).id)) si = -1;
        subSel = si;
        ps.curCue = "";
        ps.trCue = "";
        shownAny = false;
        startTranslation(si);
        ps.subTv.setVisibility(View.GONE);
        Log.i(TAG, "subtitles " + (si < 0 ? "off" : "on " + subs.get(si).mime + "/" + subs.get(si).lang));
        if (persist && ps.playCh != null) ps.store.setSubChoice(ps.playCh.url, si < 0 ? "-" : subKey(si)); // "-" = the user chose Off here
    }

    /** T40: decides whether track si needs translating and, if so, gets the model going. Never runs with subtitles Off or in the mini preview. */
    private void startTranslation(int si) {
        ps.trOn = false;
        trSrc = "";
        detCand = "";
        detAgree = 0;
        if (si < 0 && ps.tr != null) ps.tr.close(); // Off: free the models at once
        if (si < 0 || ps.previewCh != null || !ps.store.subTranslate()) return;
        String l = SubPref.norm(subs.get(si).lang);
        if (l.equals("en")) return;
        if (!translator().ok()) { warnOnce("Translation not available on this TV"); return; }
        ps.trOn = true;
        Log.i(TAG, "subtitle translation on, source " + (l.isEmpty() ? "unknown (detecting)" : l));
        if (!l.isEmpty()) useLang(l);
    }

    private lk.manus.iptv.subs.SubTranslator translator() {
        if (ps.tr == null) ps.tr = new lk.manus.iptv.subs.SubTranslator(ps.act, new lk.manus.iptv.subs.SubTranslator.Listener() {
            @Override public void downloading(String l) { ps.ui.showToast("Downloading " + lk.manus.iptv.subs.SubTranslator.langName(l) + "→English subtitles (once, ~30 MB)…", 0); }
            @Override public void ready(String l) {
                if (!l.equals(trSrc)) return;
                ps.ui.showToast(lk.manus.iptv.subs.SubTranslator.langName(l) + "→English subtitles ready", 2500);
                if (ps.trOn && !ps.curCue.isEmpty()) translateCue(ps.curCue);
            }
            @Override public void failed(String l) { if (l.equals(trSrc)) { ps.trOn = false; ps.ui.showToast("Couldn't get " + lk.manus.iptv.subs.SubTranslator.langName(l) + "→English subtitles (needs internet once)", 5000); showCue(); } }
        });
        return ps.tr;
    }

    private void warnOnce(String msg) { if (!trWarned) { trWarned = true; Log.w(TAG, msg); } ps.ui.showToast(msg, 4000); }

    /** The track's language is known (tag or detected): English needs nothing, a language ML Kit lacks stays original, the rest gets its model. */
    private void useLang(String l) {
        trSrc = l;
        if (l.equals("en")) { ps.trOn = false; return; }
        if (!lk.manus.iptv.subs.SubTranslator.supported(l)) { ps.trOn = false; ps.ui.showToast("No offline translation for " + lk.manus.iptv.subs.SubTranslator.langName(l), 4000); return; }
        translator().ensure(l);
    }

    void translateCue(final String orig) {
        Log.i(TAG, "cue to translate: src=" + trSrc + " len=" + orig.length());
        if (trSrc.isEmpty()) { // no language tag: two matching detections lock the language for this track
            if (orig.length() < 8) return;
            translator().detect(orig, new lk.manus.iptv.subs.SubTranslator.Cb() {
                @Override public void done(String d) {
                    if (d == null || !ps.trOn || !trSrc.isEmpty()) return;
                    if (d.equals(detCand)) detAgree++; else { detCand = d; detAgree = 1; }
                    if (detAgree >= 2) { useLang(d); if (ps.trOn) translateCue(ps.curCue); showCue(); }
                }
            });
            return;
        }
        final lk.manus.iptv.subs.SubTranslator t = translator();
        if (!t.isReady(trSrc)) { t.ensure(trSrc); return; } // after an idle close the model reloads in a moment
        t.translate(trSrc, orig, new lk.manus.iptv.subs.SubTranslator.Cb() {
            @Override public void done(String out) { if (out != null && orig.equals(ps.curCue)) { ps.trCue = out; showCue(); } }
        });
    }

    /** The engine's current cue, shown bottom-centre unless something else owns the screen. */
    void showCue() {
        if (subSel < 0 || ps.curCue.isEmpty() || !ps.playing || ps.guide.isOpen() || ps.previewCh != null) { ps.subTv.setVisibility(View.GONE); return; }
        String shown = ps.trCue.isEmpty() ? ps.curCue : ps.trCue;
        if (!shown.contentEquals(ps.subTv.getText())) ps.subTv.setText(shown);
        ps.subTv.setAlpha(ps.trOn && ps.trCue.isEmpty() && !trSrc.equals("en") ? 0.55f : 1f); // the original shows faintly until its English is ready
        ps.subTv.setVisibility(View.VISIBLE);
        if (!shownAny) { shownAny = true; ps.bCC.setVisibility(View.VISIBLE); ps.guide.setCC(true); }
    }

    void openSubMenu() {
        if (ps.list == null || !ps.playing) return;
        if (subs.isEmpty()) { ps.ui.showToast("No subtitles on this channel", 2000); return; }
        List<String> l = new ArrayList<>();
        l.add("Off");
        for (int i = 0; i < subs.size(); i++) l.add(subLabel(i));
        ps.menus.openMenu("Subtitles", l, (subSel < 0 ? SubPref.pick(subs) : subSel) + 1, new Chooser.MenuPick() { @Override public void pick(int i) { selectSub(i - 1, true); } });
    }

    void openAudioMenu() {
        List<String> l = new ArrayList<>();
        int sel = 0;
        for (int i = 0; i < ps.audios.size(); i++) { l.add(trackName(ps.audios.get(i), i)); if (ps.audios.get(i).sel) sel = i; }
        ps.menus.openMenu("Audio", l, sel, new Chooser.MenuPick() { @Override public void pick(int i) {
            Engine e = ps.eng;
            if (e == null || i >= ps.audios.size() || !e.selectAudio(ps.audios.get(i).id)) return;
            if (ps.playCh != null) ps.store.setAudioChoice(ps.playCh.url, audioKey(i));
            Log.i(TAG, "audio " + ps.audios.get(i).lang);
            ps.h.postDelayed(new Runnable() { @Override public void run() { scanTracks(); } }, 500); // refresh the selected flags
        } });
    }
}
