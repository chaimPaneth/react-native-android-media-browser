package com.mediabrowser;

import android.media.browse.MediaBrowser;
import android.media.session.MediaSession;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

public class MediaBrowserService extends android.service.media.MediaBrowserService implements MediaItemsStore.MediaItemsUpdateListener {
  private static final String MEDIA_ROOT_ID = "ROOT";

  private static final String TAG = "MediaBrowserService";

  MediaSession mSession;

  @Override
  public void onCreate() {
    super.onCreate();
    Log.d(TAG, "onCreate called");

    MediaItemsStore.getInstance().setListener(this);

    mSession = new MediaSession(this, "MediaBrowserService");
    setSessionToken(mSession.getSessionToken());
  }

  @Override
  public void onMediaItemsUpdated() {
    Log.d(TAG, "onMediaItemsUpdated called");
    notifyChildrenChanged(MediaItemsStore.getInstance().getRootId());
  }

  @Override
  public BrowserRoot onGetRoot(@NonNull String clientPackageName,
                               int clientUid,
                               @Nullable Bundle rootHints) {
    Log.d(TAG, "onGetRoot called");
    String rootId = MediaItemsStore.getInstance().getRootId();
    Log.d(TAG, "MyRootID is: " + rootId);

    // Print the media hierarchy
    List<MediaBrowser.MediaItem> mediaHierarchy = MediaItemsStore.getInstance().getMediaItemsByParentId(rootId);
    Log.d(TAG, "Media hierarchy: " + mediaHierarchy);
    return rootId != null ? new BrowserRoot(rootId, null) : null;
  }

  @Override
  public void onLoadChildren(@NonNull final String parentMediaId,
                             @NonNull final Result<List<MediaBrowser.MediaItem>> result) {
    Log.d(TAG, "onLoadChildren called");
    List<MediaBrowser.MediaItem> mediaItems = MediaItemsStore.getInstance().getMediaItemsByParentId(parentMediaId);

    if (mediaItems == null) {
      mediaItems = new ArrayList<>();
    }

    result.sendResult(mediaItems);
  }
}
