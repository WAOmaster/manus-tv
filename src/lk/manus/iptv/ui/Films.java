package lk.manus.iptv.ui;

import android.content.Context;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import lk.manus.iptv.data.Channel;
import lk.manus.iptv.data.CloudData;
import lk.manus.iptv.data.Health;
import lk.manus.iptv.data.Index;
import lk.manus.iptv.data.Store;
import lk.manus.iptv.meta.MetaStore;
import lk.manus.iptv.meta.Tmdb;
import lk.manus.iptv.meta.TitleCleaner;

/** What the film screens share: which guide programmes are films, resolving them (never while a key is held), and the one-line facts. UI thread unless noted. */
public final class Films {
    private Films() {}

    /** DEBUG test hook (intent extra "filmsfuture"): Details treats every film as starting in 3 h. Never set in release. */
    public static boolean debugFuture;

    public static final int MIN_FILM_MINUTES = 70, MAX_FILM_MINUTES = 240;

    /** One channel whose current programme is film-length. film = what the cache already knew when the list was built (may be null). */
    public static final class Entry {
        public final Channel ch;
        public final CloudData.Slot slot;
        public final TitleCleaner.Cleaned clean;
        public final int pos;          // index in the source list
        public final boolean working, dead;
        public MetaStore.Film film;
        public boolean asked;
        public List<Entry> others = new ArrayList<>(); // T110e: the same film on other channels (best first); this entry is the group's representative
        public int groupSize() { return 1 + others.size(); }
        Entry(Channel c, CloudData.Slot s, TitleCleaner.Cleaned cl, int p, boolean w, boolean d) { ch = c; slot = s; clean = cl; pos = p; working = w; dead = d; }
        public int minsLeft() { return Films.minsLeft(slot); }
        public int slotMinutes() { return (int) ((slot.stop - slot.start) / 60); }
        public boolean matched() { return film != null && film.isMatched(); }
        /** Known to have no match (or removed): shows as a plain tile. */
        public boolean unmatched() { return film != null && !film.isMatched(); }
    }

    /** An entry for the programme airing on a channel the viewer is watching (banner -> Details). null when the title is not a film title. */
    public static Entry entry(Channel ch, CloudData.Slot s, MetaStore.Film f) {
        TitleCleaner.Cleaned cl = TitleCleaner.clean(s.title);
        if (cl == null) return null;
        Entry e = new Entry(ch, s, cl, -1, true, false);
        e.film = f;
        return e;
    }

    public static boolean enabled(Context c) { return Tmdb.get(c).hasKey(); }

    public static int minsLeft(CloudData.Slot s) {
        return (int) Math.max(1, (s.stop * 1000 - System.currentTimeMillis() + 59999) / 60000);
    }

    /** Channels of the merged Movies category airing a film-length programme that is not a series episode: working first, then most minutes left. */
    public static List<Entry> candidates(Context c, Index ix, Store st, List<Channel> source) {
        List<Entry> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        Health hl = Health.get(c);
        int dropped = 0;
        for (int i = 0; i < source.size(); i++) {
            Channel ch = source.get(i);
            if (ch.id.isEmpty()) continue;
            CloudData.Slot n = CloudData.now(ch.id, now);
            if (n == null || (n.stop - n.start) / 60 < MIN_FILM_MINUTES) continue;
            if (lk.manus.iptv.data.SeriesClassifier.isSeriesByGuide(n.title)) continue; // T110a: a series episode is Series on now's, never a film
            TitleCleaner.Cleaned cl = TitleCleaner.clean(n.title);
            if (cl == null) continue;
            if (filler(ch, n)) { dropped++; continue; }
            out.add(new Entry(ch, n, cl, i, hl.status(ch.url) > Health.UNKNOWN, hl.status(ch.url) == Health.DEAD));
        }
        sort(out, st);
        if (lk.manus.iptv.Config.DEBUG) android.util.Log.i("IPTV", "films filter: " + dropped + " filler programmes removed, " + out.size() + " kept");
        return out;
    }

    /** Guide filler, not a film: the title is the channel's own name (either contains the other once folded), or the block is over 4 h. */
    static boolean filler(Channel ch, CloudData.Slot n) {
        if ((n.stop - n.start) / 60 > MAX_FILM_MINUTES) return true;
        String t = TitleCleaner.fold(n.title), c = TitleCleaner.fold(ch.name);
        return !t.isEmpty() && !c.isEmpty() && (t.contains(c) || c.contains(t));
    }

    /**
     * Channels of the viewer's pinned countries first, in the pinned order (the signal Rank uses), then everything else; inside each
     * block: posters first (known matches), then not yet resolved, then known no-match; each group working first, minutes left (most first), rating.
     */
    public static void sort(List<Entry> l, Store st) {
        final Map<String, Integer> pin = new HashMap<>();
        int i = 0;
        for (String c : st.fyCountries()) pin.put(c, i++);
        final int none = i;
        java.util.Collections.sort(l, (a, b) -> {
            int pa = pin.containsKey(a.ch.country) ? pin.get(a.ch.country) : none, pb = pin.containsKey(b.ch.country) ? pin.get(b.ch.country) : none;
            if (pa != pb) return pa - pb;
            int ga = a.matched() ? 0 : a.unmatched() ? 2 : 1, gb = b.matched() ? 0 : b.unmatched() ? 2 : 1;
            if (ga != gb) return ga - gb;
            if (a.working != b.working) return a.working ? -1 : 1;
            if (a.minsLeft() != b.minsLeft()) return b.minsLeft() - a.minsLeft();
            double ra = a.matched() ? a.film.rating : 0, rb = b.matched() ? b.film.rating : 0;
            return Double.compare(rb, ra);
        });
        // Final order (T108/T108b): films on dead channels last, then posters first, newest year, rated; letter tiles keep the order above. Stable, so ties keep it too.
        java.util.Collections.sort(l, (a, b) -> lk.manus.iptv.data.FilmOrder.compare(key(a), key(b)));
    }

    private static lk.manus.iptv.data.FilmOrder.Key key(Entry e) {
        boolean m = e.matched();
        boolean poster = m && e.film.posterPath != null && !e.film.posterPath.isEmpty();
        int year = m && e.film.releaseYear > 0 ? e.film.releaseYear : e.clean.year;
        return new lk.manus.iptv.data.FilmOrder.Key(poster, year, m ? e.film.rating : 0, m ? e.film.votes : 0, e.dead);
    }

    /** Recents rank per channel url: larger = watched more recently, absent = never (T110e). */
    static Map<String, Long> recency(Store st) {
        List<String> r = st.recents();
        Map<String, Long> m = new HashMap<>();
        for (int i = 0; i < r.size(); i++) m.put(r.get(i), (long) (r.size() - i));
        return m;
    }

    /** T110e: one entry per film (same TMDB id, else same cleaned title), its other channels in {@code others}. Call before {@link #sort}; off the UI thread. */
    public static List<Entry> group(List<Entry> es, Store st) {
        Map<String, Long> rc = recency(st);
        List<lk.manus.iptv.data.ShowGroups.Rec> in = new ArrayList<>();
        for (Entry e : es) in.add(new lk.manus.iptv.data.ShowGroups.Rec(e, e.matched() ? e.film.tmdbId : 0, e.clean.title + (e.clean.year > 0 ? " " + e.clean.year : ""),
            e.dead, e.working, lk.manus.iptv.data.ShowGroups.qualityOf(e.ch.quality), rc.containsKey(e.ch.url) ? rc.get(e.ch.url) : 0L));
        List<Entry> out = new ArrayList<>();
        for (lk.manus.iptv.data.ShowGroups.Group g : lk.manus.iptv.data.ShowGroups.group(in)) {
            Entry r = (Entry) g.rep.ref;
            r.others = new ArrayList<>();
            for (lk.manus.iptv.data.ShowGroups.Rec o : g.others) r.others.add((Entry) o.ref);
            out.add(r);
        }
        return out;
    }

    /** " · also on 3 more" for a tile that stands for several channels, else "". */
    static String alsoOn(int groupSize) { return groupSize > 1 ? "also on " + (groupSize - 1) + " more" : ""; }

    /** DEBUG: the channel countries of the first n entries. */
    static String countries(List<Entry> l, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(n, l.size()); i++) b.append(i > 0 ? "," : "").append(l.get(i).ch.country.isEmpty() ? "?" : l.get(i).ch.country);
        return b.toString();
    }

    /** Film-length programmes that start within the next hours (soonest first), same filler rules as {@link #candidates}. */
    public static List<Entry> upcoming(Context c, List<Channel> source, int hours) {
        List<Entry> out = new ArrayList<>();
        long now = System.currentTimeMillis(), until = now + hours * 3600000L;
        Health hl = Health.get(c);
        for (int i = 0; i < source.size(); i++) {
            Channel ch = source.get(i);
            if (ch.id.isEmpty()) continue;
            CloudData.Slot n = CloudData.next(ch.id, now);
            if (n == null || n.start * 1000 <= now || n.start * 1000 > until) continue;
            long mins = (n.stop - n.start) / 60;
            if (mins < MIN_FILM_MINUTES) continue;
            TitleCleaner.Cleaned cl = TitleCleaner.clean(n.title);
            if (cl == null || lk.manus.iptv.data.SeriesClassifier.isSeriesByGuide(n.title) || filler(ch, n)) continue;
            out.add(new Entry(ch, n, cl, i, hl.status(ch.url) > Health.UNKNOWN, hl.status(ch.url) == Health.DEAD));
        }
        java.util.Collections.sort(out, (a, b) -> Long.compare(a.slot.start, b.slot.start));
        return out;
    }

    // ---- resolving ----

    private static final Map<String, MetaStore.Film> DONE = new HashMap<>(); // this run's answers, UI thread

    private static String key(CloudData.Slot s) { return s.title + "|" + (s.stop - s.start) / 60; }

    /** The answer this run already has for the programme (null = not asked yet). */
    public static MetaStore.Film known(CloudData.Slot s) { return DONE.get(key(s)); }

    /** Asks MetaStore (rate-limited there). cb runs on the UI thread with a film, a no-match film, or null. */
    public static void resolve(Context c, final CloudData.Slot s, final Consumer<MetaStore.Film> cb) {
        MetaStore.Film k = known(s);
        if (k != null) { cb.accept(k); return; }
        MetaStore.get(c).resolve(s.title, (int) ((s.stop - s.start) / 60), new MetaStore.Callback() {
            @Override public void onResolved(TitleCleaner.Cleaned clean, MetaStore.Film film) {
                if (film != null) DONE.put(key(s), film);
                cb.accept(film);
            }
        });
    }

    /** Resolves one entry the first time it is asked for (not when disabled, already known or already asked); changed runs on the UI thread once a film (or a no-match) is known. */
    public static void ask(Context c, final Entry e, final Runnable changed) {
        if (e.asked || e.film != null || !enabled(c)) return;
        e.asked = true;
        resolve(c, e.slot, new Consumer<MetaStore.Film>() {
            @Override public void accept(MetaStore.Film f) {
                if (f == null) return;
                e.film = f;
                changed.run();
            }
        });
    }

    /** The other films this programme's title could be (empty = nothing to choose from). */
    public static List<MetaStore.Choice> choices(Context c, Entry e) {
        return e.film == null ? new ArrayList<MetaStore.Choice>() : MetaStore.get(c).choices(e.film);
    }

    /** The viewer chose one: MetaStore fetches it once and keeps it for good; done(true) runs on the UI thread, with the entry's film replaced. */
    public static void choose(Context c, final Entry e, int tmdbId, final Consumer<Boolean> done) {
        MetaStore.get(c).choose(e.film, tmdbId, new Consumer<MetaStore.Film>() {
            @Override public void accept(MetaStore.Film f) {
                if (f != null) { DONE.put(key(e.slot), f); e.film = f; }
                done.accept(f != null);
            }
        });
    }

    /** "Not this film": MetaStore never matches it again, and this run forgets it too. */
    public static void forget(Context c, CloudData.Slot s, MetaStore.Film f) {
        MetaStore.get(c).forget(f);
        DONE.put(key(s), new MetaStore.Film(f.cleanTitle, f.year, 0, "", 0, 0, "", 0, "", "", "", "", 0.0, "none", -1L));
    }

    // ---- text ----

    public static String runtime(int min) {
        if (min <= 0) return "";
        return min < 60 ? min + " min" : String.format(Locale.US, "%dh%02d", min / 60, min % 60);
    }

    public static String rating(MetaStore.Film f) { return f.rating > 0 ? String.format(Locale.US, "★ %.1f", f.rating) : ""; }

    /** First n genres, comma separated. */
    public static String genres(MetaStore.Film f, int n) {
        String[] g = f.genres.split(",\\s*");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < g.length && i < n; i++) if (!g[i].isEmpty()) b.append(b.length() > 0 ? ", " : "").append(g[i]);
        return b.toString();
    }

    /** "2014 · ★ 6.7 · Sci-fi, Action" for the banner. */
    public static String bannerLine(MetaStore.Film f) {
        return join(f.releaseYear > 0 ? String.valueOf(f.releaseYear) : "", rating(f), genres(f, 2));
    }

    /** "Divergent · 2014 · ★ 6.7 · Lionsgate · 52 min left". An unmatched film shows its guide title. */
    public static String facts(Entry e) {
        boolean m = e.matched();
        return join(m ? e.film.title : e.clean.title, m && e.film.releaseYear > 0 ? String.valueOf(e.film.releaseYear) : "", m ? rating(e.film) : "", e.ch.name, alsoOn(e.groupSize()), e.minsLeft() + " min left");
    }

    /** "Divergent · 2014 · ★ 6.7 · Lionsgate · starts 21:00". */
    public static String factsSoon(Entry e) {
        boolean m = e.matched();
        return join(m ? e.film.title : e.clean.title, m && e.film.releaseYear > 0 ? String.valueOf(e.film.releaseYear) : "", m ? rating(e.film) : "", e.ch.name, "starts " + CloudData.hm(e.slot.start), lk.manus.iptv.data.Reminders.has(lk.manus.iptv.data.Keys.of(e.ch.url), e.slot.start) ? "reminder set" : "");
    }

    /** "Channel · Now: Programme · 18:12–20:29", or just the channel when the guide has nothing. */
    public static String factsChannel(Channel ch) {
        CloudData.Slot n = ch.id.isEmpty() ? null : CloudData.now(ch.id, System.currentTimeMillis());
        return n == null ? ch.name : join(ch.name, "Now: " + n.title, CloudData.hm(n.start) + "–" + CloudData.hm(n.stop));
    }

    public static String join(String... p) {
        StringBuilder b = new StringBuilder();
        for (String s : p) if (s != null && !s.isEmpty()) b.append(b.length() > 0 ? " · " : "").append(s);
        return b.toString();
    }
}
