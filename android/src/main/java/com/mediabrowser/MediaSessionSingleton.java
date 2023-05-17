package com.mediabrowser;

import android.content.Context;
import android.support.v4.media.session.MediaSessionCompat;

public class MediaSessionSingleton {
    private static MediaSessionCompat instance;

    private MediaSessionSingleton() { }

    public static MediaSessionCompat getInstance(Context context) {
        if (instance == null) {
            synchronized (MediaSessionSingleton.class) {
                if (instance == null) {
                    instance = new MediaSessionCompat(context, "MediaSession");
                }
            }
        }
        return instance;
    }
}
