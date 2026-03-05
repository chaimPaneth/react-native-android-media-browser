package com.mediabrowser;

import android.content.Context;
import android.support.v4.media.session.MediaSessionCompat;

public class MediaSessionSingleton {
    private static final String TAG = "MediaSessionSingleton";
    private static MediaSessionCompat instance;

    private MediaSessionSingleton() { }

    public static MediaSessionCompat getInstance(Context context) {
        if (instance == null) {
            MBLog.d(TAG, "Creating MediaSessionCompat singleton instance");
            synchronized (MediaSessionSingleton.class) {
                if (instance == null) {
                    instance = new MediaSessionCompat(context, "MediaSession");
                }
            }
        }
        return instance;
    }

    public static void release() {
        MBLog.v(TAG, "→ release()");
        if (instance != null) {
            try {
                instance.setActive(false);
                instance.release();
            } catch (Exception ignored) {}
            instance = null;
        }
    }
}
