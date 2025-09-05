package com.mediabrowser;

import android.os.Bundle;
import android.support.v4.media.MediaBrowserCompat;
import android.util.Log;

public final class MediaItemsResumeProvider {
    private static final String TAG = "MediaItemsResumeProvider";
    private MediaItemsResumeProvider() {}

    /** Returns resume position in ms for the given app mediaId, or 0 if none. */
    public static long getResumePositionMs(String mediaId) {
        if (mediaId == null || mediaId.isEmpty()) return 0L;
        try {
            MediaBrowserCompat.MediaItem item =
                MediaItemsStore.getInstance().getMediaItemById(mediaId);
            if (item == null || item.getDescription() == null) return 0L;
            Bundle extras = item.getDescription().getExtras();
            return (extras != null) ? ResumeParsingUtils.extractResumePosition(extras) : 0L;
        } catch (Throwable t) {
            Log.w(TAG, "getResumePositionMs(" + mediaId + ") failed: " + t.getMessage());
            return 0L;
        }
    }
}