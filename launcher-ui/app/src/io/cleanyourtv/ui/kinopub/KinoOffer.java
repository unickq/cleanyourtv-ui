package io.cleanyourtv.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Handler;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.List;

/** Opens a detail page's KinoWatch offer after KinoPub matches the IMDb ID. */
final class KinoOffer {
    private final Activity activity;
    private final Handler main;
    private TextView chipSubtitle;
    private boolean lookingUp;

    KinoOffer(Activity activity, Handler main) {
        this.activity = activity;
        this.main = main;
    }

    void bind(View chip, TextView subtitle, String kind, int id, Tmdb.Title title) {
        chipSubtitle = subtitle;
        chip.setOnClickListener(v -> watch(kind, id, title));
    }

    String subtitle() {
        return lookingUp ? Strings.get(R.string.searching_kinopub)
                : Strings.get(R.string.subscription);
    }

    private void setLookingUp(boolean lookingUp) {
        this.lookingUp = lookingUp;
        if (chipSubtitle != null) chipSubtitle.setText(subtitle());
    }

    private void watch(String kind, int id, Tmdb.Title title) {
        if (lookingUp || title == null) return;
        if (!KinoPub.connected(activity)) {
            activity.startActivity(new Intent(activity, KinoLoginActivity.class));
            return;
        }
        setLookingUp(true);
        new Thread(() -> {
            try {
                List<JSONObject> matches = KinoPub.find(activity, kind, id, title.name);
                main.post(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    setLookingUp(false);
                    if (matches.isEmpty()) {
                        Toast.makeText(activity, Strings.get(R.string.no_exact_imdb_match_found_in_kinopub), Toast.LENGTH_LONG).show();
                    } else if (matches.size() == 1) {
                        open(matches.get(0), title);
                    } else {
                        String[] labels = new String[matches.size()];
                        for (int n = 0; n < labels.length; n++) {
                            JSONObject item = matches.get(n);
                            labels[n] = KinoPub.displayTitle(item.optString("title")) + " (" + item.optInt("year") + ") · "
                                    + item.optString("quality") + " · " + item.optString("voice");
                        }
                        new AlertDialog.Builder(activity).setTitle(Strings.get(R.string.choose_kinopub_version))
                                .setItems(labels, (dialog, which) -> open(matches.get(which), title))
                                .setNegativeButton(Strings.get(R.string.cancel_2), null).show();
                    }
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    setLookingUp(false);
                    if (!KinoPub.connected(activity)) activity.startActivity(new Intent(activity, KinoLoginActivity.class));
                    else Toast.makeText(activity, Strings.get(R.string.kinopub_lookup_failed),
                            Toast.LENGTH_LONG).show();
                });
            }
        }, "kinopub-lookup").start();
    }

    private void open(JSONObject item, Tmdb.Title title) {
        try {
            KinoPub.open(activity, item.optInt("id"));
            if (title != null) Tmdb.setSeen(activity, title, true);
        } catch (RuntimeException e) {
            Toast.makeText(activity, Strings.get(R.string.kinowatch_couldnt_open_this_title), Toast.LENGTH_LONG).show();
        }
    }
}
