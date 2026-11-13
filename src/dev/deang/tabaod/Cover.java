package dev.deang.tabaod;

import android.content.Context;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The tablet's cover (Book Cover / keyboard cover). Samsung only lets system apps listen to
 * it, so this reads the cover service's status (the same text as "dumpsys cover") using
 * the DUMP permission the module grants. Fails soft: if it can't tell, the cover counts as open.
 */
final class Cover {
    private static final Pattern STATE = Pattern.compile(
            "CoverState\\(switchState=(true|false)[^)]*attached=(true|false)");

    /** True when a cover is attached and closed over the screen. Call off the main thread if you can. */
    static boolean isClosed(Context c) {
        File f = new File(c.getCacheDir(), "cover.txt");
        try {
            IBinder b = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "cover");
            if (b == null) return false;
            try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_WRITE
                    | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE)) {
                b.dump(pfd.getFileDescriptor(), new String[0]);
            }
            Matcher m = STATE.matcher(new String(Files.readAllBytes(f.toPath())));
            // switchState=false means the cover is closed.
            return m.find() && m.group(1).equals("false") && m.group(2).equals("true");
        } catch (Throwable t) {
            return false;
        } finally {
            f.delete();
        }
    }
}
