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
        line(52, "KinoPub connected");
        line(28, "Open a TMDB title and choose Open in KinoWatch.");
        TextView disconnect = TvUi.button(this, k, "Disconnect KinoPub", v -> {
            worker = new Thread(() -> {
                try {
                    KinoPub.disconnect(this);
                    main.post(() -> { if (!isFinishing() && !cancelled) finish(); });
                } catch (Exception e) {
                    main.post(() -> { if (!isFinishing() && !cancelled) line(26, "Couldn't disconnect. Please try again."); });
                }
            });
            worker.start();
        });
        box.addView(disconnect);
        disconnect.requestFocus();
    }

    private void login() {
        line(52, "Connect KinoPub");
        status = line(28, "Getting an activation code…");
        worker = new Thread(() -> {
            try {
                JSONObject code = KinoPub.deviceCode();
                String device = code.getString("code"), user = code.getString("user_code");
                long interval = Math.max(5, code.optLong("interval", 5)) * 1000;
                long until = SystemClock.elapsedRealtime() + Math.min(3600, code.optLong("expires_in", 600)) * 1000;
                main.post(() -> {
                    if (cancelled || isFinishing()) return;
                    box.removeAllViews();
                    line(52, "Connect KinoPub");
                    line(28, "Open kino.watch/device, sign in and enter this code:");
                    line(68, user);
                    ImageView qr = new ImageView(this);
                    android.graphics.Bitmap bitmap = TmdbLoginActivity.render(Qr.encode("https://kino.watch/device"), Math.round(270 * k));
                    qr.setImageBitmap(bitmap);
                    qr.setBackgroundColor(Color.WHITE);
                    int pad = Math.round(16 * k);
                    qr.setPadding(pad, pad, pad, pad);
                    box.addView(qr, new LinearLayout.LayoutParams(bitmap.getWidth() + 2 * pad, bitmap.getHeight() + 2 * pad));
                    status = line(26, "Waiting for approval…  ·  Back cancels");
                });
                while (!cancelled && SystemClock.elapsedRealtime() < until) {
                    Thread.sleep(interval);
                    if (cancelled) return;
                    try {
                        JSONObject tokens = KinoPub.poll(device);
                        main.post(() -> {
                            if (cancelled || isFinishing()) return;
                            try { KinoPub.save(this, tokens); connected(); }
                            catch (Exception e) { status.setText("Couldn't save the KinoPub session. Go back and try again."); }
                        });
                        return;
                    } catch (KinoPub.ApiError e) {
                        if ("slow_down".equals(e.error)) interval += 5000;
                        else if (!"authorization_pending".equals(e.error)) throw e;
                    }
                }
                main.post(() -> { if (!cancelled) status.setText("The code expired. Go back and open KinoPub account again."); });
            } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            catch (Exception e) {
                main.post(() -> { if (!cancelled) status.setText("Couldn't connect to KinoPub. Go back and try again."); });
            }
        }, "kinopub-login");
        worker.start();
    }
}
