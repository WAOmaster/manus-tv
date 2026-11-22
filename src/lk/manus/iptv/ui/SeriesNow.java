package lk.manus.iptv.ui;

import android.content.Context;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lk.manus.iptv.data.Channel;
import lk.manus.iptv.data.CloudData;
import lk.manus.iptv.data.FilmOrder;
import lk.manus.iptv.data.Health;
import lk.manus.iptv.data.Index;
import lk.manus.iptv.data.SeriesClassifier;
import lk.manus.iptv.data.Store;
import lk.manus.iptv.meta.MetaStore;

/**
 * T110a, data only: series airing now on live channels, found from the guide, mirroring {@link Films}.
 * A guide entry is a series when its title carries a marker (S2 E5, Season 2 Episode 5, Ep 5...), or it is a short programme (under
 * {@link Films#MIN_FILM_MINUTES} min, so films stay in Movies on now) whose name matches a TMDB TV show with at least
 * {@link FilmOrder#MIN_VOTES} votes. It is never also in Movies on now (Films.candidates drops marked titles).
 * Flow: candidates() (no I/O) -> loadCached() off the UI thread -> ask() the rest -> shown() -> sort() once when the row opens.
 */
public final class SeriesNow {
    private SeriesNow() {}

    public static final int MIN_MINUTES = 10;

    public static final class Entry {
        public final Channel ch;
        public final CloudData.Slot slot;
        public final SeriesClassifier.Parsed parsed;
        public final boolean working, dead;
        public MetaStore.Film film;   // the TMDB TV match, or a no-match row; null = not looked up yet
        public boolean asked;
        public List<Entry> others = new ArrayList<>(); // T110e: the same show on other channels (best first); this entry is the group's representative
        public int groupSize() { return 1 + others.size(); }
        Entry(Channel c, CloudData.Slot s, SeriesClassifier.Parsed p, boolean w, boolean d) { ch = c; slot = s; parsed = p; working = w; dead = d; }

        public boolean matched() { return film != null && film.isMatched(); }
        /** Show to display: TMDB's name when matched, else the guide title without its marker. */
        public String title() { return matched() ? film.title : parsed.title; }
        public int season() { return parsed.season; }
        public int episode() { return parsed.episode; }
        public String posterPath() { return matched() ? film.posterPath : ""; }
        public int year() { return matched() ? film.releaseYear : 0; }
        public double rating() { return matched() ? film.rating : 0; }
        public int votes() { return matched() ? film.votes : 0; }
        public String genres() { return matched() ? film.genres : ""; }
        public String network() { return matched() ? film.cast : ""; } // MetaStore keeps a show's first network in the cast column
        public String channelId() { return ch.id; }
        public String channelName() { return ch.name; }
        public int minsLeft() { return Films.minsLeft(slot); }
        /** "S2 E5" when the guide gives both, "S2" or "E5" when only one, else "". */
        public String seLabel() { return (parsed.season > 0 ? "S" + parsed.season : "") + (parsed.season > 0 && parsed.episode > 0 ? " " : "") + (parsed.episode > 0 ? "E" + parsed.episode : ""); }
        /** Marked by the guide, or matched a TV show with enough votes. */
        public boolean isSeries() { return parsed.marker || (matched() && film.votes >= FilmOrder.MIN_VOTES); }
        /** Poster / year / rating still to fetch (or to decide, for unmarked ones). */
        public boolean needsLookup() { return film == null; }
    }

    /** Channels of source airing something that may be a series; no I/O. Marked entries are series already; unmarked ones wait for the TMDB cache or a lookup. */
    public static List<Entry> candidates(Context c, Index ix, Store st, List<Channel> source) {
        List<Entry> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        Health hl = Health.get(c);
        int marked = 0;
        for (Channel ch : source) {
            if (ch.id.isEmpty()) continue;
            CloudData.Slot n = CloudData.now(ch.id, now);
            if (n == null || (n.stop - n.start) / 60 < MIN_MINUTES || Films.filler(ch, n)) continue;
            SeriesClassifier.Parsed p = SeriesClassifier.parse(n.title);
            if (p.title.isEmpty()) continue;
            if (!p.marker && (n.stop - n.start) / 60 >= Films.MIN_FILM_MINUTES) continue; // film-length and unmarked: Movies on now owns it
            if (p.marker) marked++;
            out.add(new Entry(ch, n, p, hl.status(ch.url) > Health.UNKNOWN, hl.status(ch.url) == Health.DEAD));
        }
        if (lk.manus.iptv.Config.DEBUG) android.util.Log.i("IPTV", "series candidates: " + out.size() + " guide entries, " + marked + " marked by title");
        return out;
    }

    /** Fills film from the cache (no network). Off the UI thread: one small query per distinct title variant. A no-match older than a day is left open to ask again. */
    public static void loadCached(Context c, List<Entry> l) {
        MetaStore ms = MetaStore.get(c);
        Map<String, MetaStore.Film> seen = new HashMap<>();
        for (Entry e : l) {
            if (e.film != null) continue;
            String k = e.parsed.title;
            if (!seen.containsKey(k)) seen.put(k, cached(ms, k, System.currentTimeMillis()));
            e.film = seen.get(k);
        }
    }

    /** First matched variant; else a fresh no-match for every variant; else null (still to ask). */
    static MetaStore.Film cached(MetaStore ms, String title, long now) {
        MetaStore.Film none = null;
        boolean allKnown = true;
        for (String t : SeriesClassifier.showTitles(title)) {
            MetaStore.Film f = ms.getShow(t);
            if (f != null && f.isMatched()) return f;
            if (f == null || MetaStore.showNoMatchStale(f, now)) allKnown = false;
            else if (none == null) none = f;
        }
        return allKnown ? none : null;
    }

    /** Looks one entry up on TMDB TV the first time it is asked for (not when disabled or already known); changed runs on the UI thread. */
    public static void ask(Context c, final Entry e, final Runnable changed) {
        if (e.asked || e.film != null || !Films.enabled(c)) return;
        e.asked = true;
        resolve(MetaStore.get(c), SeriesClassifier.showTitles(e.parsed.title), 0, new java.util.function.Consumer<MetaStore.Film>() {
            @Override public void accept(MetaStore.Film f) { e.film = f; changed.run(); }
        });
    }

    /** Tries the full title, then the show part before ". " / " - " when the full one had no TMDB match (one request pair each, 4/s limit in Tmdb). done runs on the UI thread; never when TMDB could not answer. Also the DEBUG showtest hook. */
    public static void resolve(final MetaStore ms, final List<String> titles, final int i, final java.util.function.Consumer<MetaStore.Film> done) {
        ms.resolveShow(titles.get(i), new MetaStore.Callback() {
            @Override public void onResolved(lk.manus.iptv.meta.TitleCleaner.Cleaned clean, MetaStore.Film f) {
                if (f == null) return; // network trouble: no verdict, asked again next time
                if (!f.isMatched() && i + 1 < titles.size()) { resolve(ms, titles, i + 1, done); return; }
                done.accept(f);
            }
        });
    }

    /** The series only (unmarked entries still undecided or with no TV match are left out). */
    public static List<Entry> shown(List<Entry> l) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : l) if (e.isSeries()) out.add(e);
        return out;
    }

    /** T110e: one entry per show (same TMDB id, else same cleaned guide title), its other channels in {@code others}. Call before {@link #sort}; off the UI thread. */
    public static List<Entry> group(List<Entry> es, Store st) {
        Map<String, Long> rc = Films.recency(st);
        List<lk.manus.iptv.data.ShowGroups.Rec> in = new ArrayList<>();
        for (Entry e : es) in.add(new lk.manus.iptv.data.ShowGroups.Rec(e, e.matched() ? e.film.tmdbId : 0, e.parsed.title,
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

    /**
     * Final order, fixed when the row opens: pinned countries first, then (T108/T108b via FilmOrder) channels that work or are untested before
     * dead ones; inside each group poster first, newest first-air year, rating (needs >= 50 votes), letter tiles last in the order above.
     */
    public static void sort(List<Entry> l, Store st) {
        final Map<String, Integer> pin = new HashMap<>();
        int i = 0;
        for (String c : st.fyCountries()) pin.put(c, i++);
        final int none = i;
        java.util.Collections.sort(l, (a, b) -> {
            int pa = pin.containsKey(a.ch.country) ? pin.get(a.ch.country) : none, pb = pin.containsKey(b.ch.country) ? pin.get(b.ch.country) : none;
            if (pa != pb) return pa - pb;
            if (a.working != b.working) return a.working ? -1 : 1;
            return b.minsLeft() - a.minsLeft();
        });
        java.util.Collections.sort(l, (a, b) -> FilmOrder.compare(key(a), key(b))); // stable: ties keep the order above
    }

    static FilmOrder.Key key(Entry e) {
        return new FilmOrder.Key(!e.posterPath().isEmpty(), e.year(), e.rating(), e.votes(), e.dead);
    }

    /** "Friends · S2 E5 · ★ 8.4 · Comedy Central · 12 min left"; a part the guide or TMDB does not give is left out. */
    public static String facts(Entry e) {
        return Films.join(e.title(), e.seLabel(), e.matched() ? Films.rating(e.film) : "", e.channelName(), Films.alsoOn(e.groupSize()), e.minsLeft() > 0 ? e.minsLeft() + " min left" : "");
    }

    /** True when at least one programme on air is marked as a series by its title (no I/O): the menu entry and Home row appear. */
    public static boolean any(Context c, Index ix, Store st) {
        for (Entry e : candidates(c, ix, st, ix.all)) if (e.parsed.marker) return true;
        return false;
    }

    /** DEBUG: the first n entries in final order: show, S/E, year, rating/votes, poster, channel. */
    public static String describe(List<Entry> l, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(n, l.size()); i++) {
            Entry e = l.get(i);
            b.append(i + 1).append(". ").append(e.title()).append(" [").append(e.seLabel().isEmpty() ? "-" : e.seLabel()).append("] y").append(e.year())
             .append(" r").append(e.rating()).append("/v").append(e.votes()).append(e.posterPath().isEmpty() ? " nopic" : " pic").append(e.dead ? " DEAD" : e.working ? " W" : " -")
             .append(" ch=").append(e.channelName()).append(" ").append(e.minsLeft()).append("m | ");
        }
        return b.toString();
    }
}
