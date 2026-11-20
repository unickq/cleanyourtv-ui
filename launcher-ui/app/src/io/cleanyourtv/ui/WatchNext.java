package io.cleanyourtv.ui;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.ImageDecoder;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvContract.WatchNextPrograms;
import android.net.Uri;

import java.io.IOException;
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
        final Drawable artwork;

        Item(String title, String app, Intent resume, long position, long duration, Drawable artwork) {
            this.title = title;
            this.app = app;
            this.resume = resume;
            this.artwork = artwork;
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
                WatchNextPrograms.COLUMN_BROWSABLE, WatchNextPrograms.COLUMN_POSTER_ART_URI,
                WatchNextPrograms.COLUMN_THUMBNAIL_URI
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
                Drawable artwork = localArtwork(context, cursor.getString(7));
                if (artwork == null) artwork = localArtwork(context, cursor.getString(8));
                if (artwork == null) {
                    try {
                        artwork = context.getPackageManager().getApplicationIcon(pkg);
                    } catch (PackageManager.NameNotFoundException ignored) {
                    }
                }
                items.add(new Item(title, Providers.label(context, pkg), resume,
                        cursor.getLong(3), cursor.getLong(4), artwork));
            }
        } catch (RuntimeException e) {
            AppLog.w("Watch Next: " + e.getClass().getSimpleName());
            throw e;
        }
        return items;
    }

    private static Drawable localArtwork(Context context, String link) {
        if (link == null) return null;
        Uri uri = Uri.parse(link);
        if (!"content".equals(uri.getScheme()) && !"android.resource".equals(uri.getScheme())) return null;
        try {
            return new BitmapDrawable(context.getResources(), ImageDecoder.decodeBitmap(
                    ImageDecoder.createSource(context.getContentResolver(), uri),
                    (decoder, info, source) -> {
                        int width = info.getSize().getWidth(), height = info.getSize().getHeight();
                        float scale = Math.min(1f, 256f / Math.max(width, height));
                        decoder.setTargetSize(Math.max(1, Math.round(width * scale)),
                                Math.max(1, Math.round(height * scale)));
                    }));
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
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
