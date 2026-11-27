package io.cleanyourtv.ui;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;

/** Device-code approval only; no account password is entered in the launcher. */
public class KinoLoginActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout box;
    private TextView status;
    private float k;
    private Thread worker;
    private volatile boolean cancelled;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        k = TvUi.k(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(TvUi.BG);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setContentView(root);
        if (KinoPub.connected(this)) connected(); else login();
    }

    @Override protected void onDestroy() {
        cancelled = true;
        if (worker != null) worker.interrupt();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private TextView line(int size, String text) {
        TextView view = TvUi.text(this, k, size, size > 40 ? Ui.displaySemibold : Ui.regular, TvUi.INK);
        view.setText(text);
        view.setGravity(Gravity.CENTER);
        view.setPadding(Math.round(30 * k), Math.round(10 * k), Math.round(30 * k), Math.round(10 * k));
        box.addView(view);
        return view;
    }

    private void connected() {
        box.removeAllViews();
        line(52, Strings.get(R.string.kinopub_connected));
        line(28, Strings.get(R.string.kinowatch_tile_hint));
        TextView disconnect = TvUi.button(this, k, Strings.get(R.string.disconnect_kinopub), v -> {
            worker = new Thread(() -> {
                try {
                    KinoPub.disconnect(this);
                    main.post(() -> { if (!isFinishing() && !cancelled) finish(); });
                } catch (Exception e) {
                    main.post(() -> { if (!isFinishing() && !cancelled) line(26, Strings.get(R.string.couldnt_disconnect_please_try_again)); });
                }
            });
            worker.start();
        });
        box.addView(disconnect);
        disconnect.requestFocus();
        worker = new Thread(() -> KinoPub.notifyDevice(this), "kinopub-device");
        worker.start();
    }

    private void login() {
        line(52, Strings.get(R.string.connect_kinopub));
        status = line(28, Strings.get(R.string.getting_an_activation_code));
        worker = new Thread(() -> {
            try {
                JSONObject code = KinoPub.deviceCode();
                String device = code.getString("code"), user = code.getString("user_code");
                long interval = Math.max(5, code.optLong("interval", 5)) * 1000;
                long until = SystemClock.elapsedRealtime() + Math.min(3600, code.optLong("expires_in", 600)) * 1000;
                main.post(() -> {
                    if (cancelled || isFinishing()) return;
                    box.removeAllViews();
                    line(52, Strings.get(R.string.connect_kinopub));
                    line(28, Strings.get(R.string.open_kino_watch_device_sign_in_and_enter_this_code));
                    line(68, user);
                    ImageView qr = new ImageView(this);
                    android.graphics.Bitmap bitmap = TmdbLoginActivity.render(Qr.encode("https://kino.watch/device"), Math.round(270 * k));
                    qr.setImageBitmap(bitmap);
                    qr.setBackgroundColor(Color.WHITE);
                    int pad = Math.round(16 * k);
                    qr.setPadding(pad, pad, pad, pad);
                    box.addView(qr, new LinearLayout.LayoutParams(bitmap.getWidth() + 2 * pad, bitmap.getHeight() + 2 * pad));
                    status = line(26, Strings.get(R.string.waiting_for_approval_back_cancels));
                });
                while (!cancelled && SystemClock.elapsedRealtime() < until) {
                    Thread.sleep(interval);
                    if (cancelled) return;
                    try {
                        JSONObject tokens = KinoPub.poll(device);
                        main.post(() -> {
                            if (cancelled || isFinishing()) return;
                            try { KinoPub.save(this, tokens); connected(); }
                            catch (Exception e) { status.setText(Strings.get(R.string.kinopub_session_save_failed)); }
                        });
                        return;
                    } catch (KinoPub.ApiError e) {
                        if ("slow_down".equals(e.error)) interval += 5000;
                        else if (!"authorization_pending".equals(e.error)) throw e;
                    }
                }
                main.post(() -> {
                    if (!cancelled) status.setText(Strings.get(R.string.kinopub_activation_expired));
                });
            } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            catch (Exception e) {
                main.post(() -> {
                    if (!cancelled) status.setText(Strings.get(R.string.couldnt_connect_to_kinopub_go_back_and_try_again));
                });
            }
        }, "kinopub-login");
        worker.start();
    }
}
