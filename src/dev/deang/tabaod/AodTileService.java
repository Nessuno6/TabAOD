package dev.deang.tabaod;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile that switches the AOD on or off. Takes effect at the next screen-off. */
public class AodTileService extends TileService {

    @Override public void onStartListening() {
        update();
    }

    @Override public void onClick() {
        boolean on = !Prefs.isEnabled(this);
        Prefs.setEnabled(this, on);
        android.util.Log.i(AodDozeService.TAG, "tile clicked, AOD now " + (on ? "on" : "off"));
        try (java.io.FileWriter w = new java.io.FileWriter(new java.io.File(getFilesDir(), "tile.log"), true)) {
            w.write(new java.util.Date() + " clicked -> " + (on ? "on" : "off") + "\n");
        } catch (Exception ignored) { }
        update();
    }

    private void update() {
        Tile t = getQsTile();
        if (t == null) return;
        boolean on = Prefs.isEnabled(this);
        t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel("AOD");
        t.setSubtitle(on ? "On" : "Off");
        t.updateTile();
    }
}
