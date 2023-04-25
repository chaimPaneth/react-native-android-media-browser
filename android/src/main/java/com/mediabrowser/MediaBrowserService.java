package com.mediabrowser;

import android.os.Bundle;
import android.support.v4.media.MediaBrowserCompat;

import androidx.media.MediaBrowserServiceCompat;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

public class MediaBrowserService extends MediaBrowserServiceCompat {
  private static final String MY_MEDIA_ROOT_ID = "ROOT";

  @Override
  public void onCreate() {
    super.onCreate();
  }

  @Override
  public BrowserRoot onGetRoot(@NonNull String clientPackageName,
                               int clientUid,
                               @Nullable Bundle rootHints) {
    String rootId = MediaItemsStore.getInstance().getRootId();
    return new BrowserRoot(rootId, null);
  }

  @Override
  public void onLoadChildren(@NonNull final String parentMediaId,
                             @NonNull final Result<List<MediaBrowserCompat.MediaItem>> result) {
    List<MediaBrowserCompat.MediaItem> mediaItems = MediaItemsStore.getInstance().getMediaItemsByParentId(parentMediaId);

    if (mediaItems == null) {
      mediaItems = new ArrayList<>();
    }

    result.sendResult(mediaItems);
  }
}
