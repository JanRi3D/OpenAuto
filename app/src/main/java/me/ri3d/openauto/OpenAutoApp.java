package me.ri3d.openauto;

import android.app.Application;
import android.util.Log;

/**
 * Records memory pressure in the connection log so low-RAM behaviour on a radio can be read back in
 * Diagnostics. Nothing is cached that could be freed: fonts are the only process-wide cache and are
 * tiny; media buffers belong to the active session and die with it.
 */
public class OpenAutoApp extends Application {
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        Log.w("Connection", "memory trim level " + level);
        if (level >= TRIM_MEMORY_RUNNING_LOW) ConnectionManager.get(this).noteMemoryPressure(level);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        ConnectionManager.get(this).noteMemoryPressure(TRIM_MEMORY_COMPLETE);
    }
}
