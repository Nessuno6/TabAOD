package dev.deang.tabaod;

import android.app.Activity;
import android.content.ComponentName;
import android.os.Bundle;
import android.service.quicksettings.TileService;
import android.widget.Toast;

/**
 * Invisible activity behind the "Turn AOD on/off" app shortcuts, which Samsung Modes and
 * Routines can run as app actions. Switches the setting, refreshes the Quick Settings tile,
 * applies it to a running AOD, and closes.
 */
public class AodSwitchActivity extends Activity {
    static final String ACTION_ON = "dev.deang.tabaod.action.AOD_ON";
    static final String ACTION_OFF = "dev.deang.tabaod.action.AOD_OFF";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        String action = getIntent() != null ? getIntent().getAction() : null;
        if (ACTION_ON.equals(action) || ACTION_OFF.equals(action)) {
            boolean on = ACTION_ON.equals(action);
            Prefs.setEnabled(this, on);
            AodDozeService.applyEnabled(on);
            try {
                TileService.requestListeningState(this, new ComponentName(this, AodTileService.class));
            } catch (Exception ignored) { }
            Toast.makeText(this, on ? "Always On Display on" : "Always On Display off", Toast.LENGTH_SHORT).show();
        }
        finish();
    }
}
