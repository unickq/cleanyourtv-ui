package io.cleanyourtv.ui;

import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

/** KinoPub's home tabs and the season count loaded for the large card. */
final class KinoHomePicks {
    private final Context context;
    private final Executor io;
    private final Handler main;
    private final Runnable onLoaded;
    private final List<List<Recs.Pick>> tabs = Arrays.asList(
            Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
    private String loadedLanguage = "";
    private final long[] nextAttempt = new long[3];
    private final boolean[] loading = new boolean[3], failedTabs = new boolean[3];

    KinoHomePicks(Context context, Executor io, Handler main, Runnable onLoaded) {
        this.context = context;
        this.io = io;
        this.main = main;
        this.onLoaded = onLoaded;
    }

    List<Recs.Pick> picks(int tab) { return tabs.get(tab); }

    int emptyMessage(int tab) {
        if (loading[tab]) return R.string.loading;
        return failedTabs[tab] ? R.string.kinopub_lookup_failed : R.string.nothing_found;
    }

    /** Only refresh the KinoPub tab the person selected; switching back uses its cached list. */
    void refresh(int tab) {
        String language = Ui.language;
        if (!language.equals(loadedLanguage)) {
            loadedLanguage = language;
            Collections.fill(tabs, Collections.emptyList());
            Arrays.fill(nextAttempt, 0);
            Arrays.fill(failedTabs, false);
        }
        long now = SystemClock.elapsedRealtime();
        if (loading[tab] || now < nextAttempt[tab]) return;
        loading[tab] = true;
        io.execute(() -> {
            List<Recs.Pick> out = new ArrayList<>();
            boolean failed = false;
            try {
                List<Tmdb.Title> titles = tab == 0 ? KinoPub.fresh(context, true) : KinoPub.hot(context, tab == 1);
                for (Tmdb.Title title : titles) {
                    Recs.Pick pick = new Recs.Pick();
                    pick.id = title.id;
                    pick.kind = KinoPub.KIND;
                    pick.title = title.name;
                    pick.poster = title.poster.isEmpty() ? null : title.poster;
                    pick.backdrop = title.backdrop.isEmpty() ? pick.poster : title.backdrop;
                    pick.meta = title.kinoCaption;
                    pick.kinoNeedsSeasons = title.kinoNeedsSeasons;
                    pick.pkg = KinoPub.PLAYER_PACKAGE;
                    pick.detailed = true;
                    out.add(pick);
                }
            } catch (Exception e) {
                failed = true;
                AppLog.w("KinoPub home picks: " + e.getClass().getSimpleName());
            }
            boolean retry = failed;
            main.post(() -> {
                loading[tab] = false;
                if (!language.equals(Ui.language)) {
                    onLoaded.run();
                    return;
                }
                if (!retry) tabs.set(tab, out);
                failedTabs[tab] = retry;
                nextAttempt[tab] = SystemClock.elapsedRealtime()
                        + (retry ? 60_000 : 15 * 60_000);
                onLoaded.run();
            });
        });
    }

    void addSeasonCount(Recs.Pick pick) {
        if (!pick.kinoNeedsSeasons) return;
        pick.kinoNeedsSeasons = false;
        io.execute(() -> {
            try {
                int seasons = KinoPub.seasonCount(context, pick.id);
                if (seasons <= 0) return;
                main.post(() -> {
                    pick.meta += " · " + Strings.quantity(R.plurals.seasons, seasons);
                    onLoaded.run();
                });
            } catch (Exception e) {
                AppLog.w("KinoPub season count: " + e.getClass().getSimpleName());
            }
        });
    }
}
