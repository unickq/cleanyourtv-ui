package io.cleanyourtv.ui;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.media.tv.TvContract.WatchNextPrograms;
import android.net.Uri;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Reads the titles that apps publish to Android TV's Watch Next list. */
final class WatchNext {
    static final String PERMISSION = "android.permission.READ_TV_LISTINGS";
    private static final int MAX = 12;

    private WatchNext() {}

    static boolean allowed(Context context) {
        return context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    static final class Item {
        final String title, app;
        final Intent resume;
        final int progress;

        Item(String title, String app, Intent resume, long position, long duration) {
            this.title = title;
            this.app = app;
            this.resume = resume;
            progress = position > 0 && position < duration ? (int) (100.0 * position / duration) : -1;
        }
    }

    static List<Item> load(Context context) {
        List<Item> items = new ArrayList<>();
        if (!allowed(context)) return items;
        String[] columns = {
                WatchNextPrograms.COLUMN_PACKAGE_NAME, WatchNextPrograms.COLUMN_TITLE,
                WatchNextPrograms.COLUMN_INTENT_URI, WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
                WatchNextPrograms.COLUMN_DURATION_MILLIS, WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
                WatchNextPrograms.COLUMN_BROWSABLE
        };
        Set<String> seen = new HashSet<>();
        try (Cursor cursor = context.getContentResolver().query(WatchNextPrograms.CONTENT_URI,
                columns, null, null, WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS + " DESC")) {
            if (cursor == null) return items;
            while (items.size() < MAX && cursor.moveToNext()) {
                if ((!cursor.isNull(6) && cursor.getInt(6) == 0)
                        || cursor.getInt(5) == WatchNextPrograms.WATCH_NEXT_TYPE_WATCHLIST) continue;
                String pkg = cursor.getString(0), title = cursor.getString(1), uri = cursor.getString(2);
                if (pkg == null || pkg.isEmpty() || title == null || title.isEmpty() || uri == null
                        || context.getPackageName().equals(pkg)) continue;
                Intent resume = resumeIntent(pkg, uri);
                if (resume == null || !seen.add(pkg + "|" + uri)) continue;
                items.add(new Item(title, Providers.label(context, pkg), resume, cursor.getLong(3), cursor.getLong(4)));
            }
        } catch (RuntimeException e) {
            AppLog.w("Watch Next: " + e.getClass().getSimpleName());
            throw e;
        }
        return items;
    }

    private static Intent resumeIntent(String pkg, String uri) {
        try {
            Intent published = Intent.parseUri(uri, 0);
            if ((published.getPackage() != null && !pkg.equals(published.getPackage()))
                    || (published.getComponent() != null && !pkg.equals(published.getComponent().getPackageName()))
                    || published.getSelector() != null) return null;
            Uri data = published.getData();
            if (data != null && "file".equalsIgnoreCase(data.getScheme())) return null;
            if (data == null && published.getComponent() == null) return null;
            // Limit a provider-supplied link to its publisher, without flags or delegated URI grants.
            return new Intent(Intent.ACTION_VIEW, data).setPackage(pkg).setComponent(published.getComponent());
        } catch (URISyntaxException | IllegalArgumentException e) {
            return null;
        }
    }
}
