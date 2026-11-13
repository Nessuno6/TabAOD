package dev.deang.tabaod;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

/** Full-screen preview of the AOD with a sample pop-up. Tap to close. */
public class PreviewActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        AodStage view = new AodStage(this, false);
        view.setOnClickListener(v -> finish());
        setContentView(view);
        WindowInsetsController ic = getWindow().getInsetsController();
        if (ic != null) {
            ic.hide(WindowInsets.Type.systemBars());
            ic.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
        handler.postDelayed(() -> view.showPopup(new NotifStore.Item("preview", getPackageName(),
                "Preview notification", "This is how a new notification pops up on the always-on display.",
                null, null)), 800);
        handler.postDelayed(view::hidePopup, 6000);
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                view.tick();
                handler.postDelayed(this, 60_000);
            }
        }, 60_000);
        view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
