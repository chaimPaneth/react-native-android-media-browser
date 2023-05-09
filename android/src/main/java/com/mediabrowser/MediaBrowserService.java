package com.mediabrowser;

import android.media.browse.MediaBrowser;
import android.media.session.MediaSession;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

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

    mSession.setCallback(new MediaSession.Callback() {
      @Override
      public void onPlayFromMediaId(String mediaId, Bundle extras) {
        super.onPlayFromMediaId(mediaId, extras);
        sendMediaItemToJS(mediaId);
      }
    });

    mSession.setActive(true);
  }

  @Override
  public void onMediaItemsUpdated(String parentId) {
    Log.d(TAG, "onMediaItemsUpdated called");
    if (parentId != null) {
      notifyChildrenChanged(parentId);
    }
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

  private void sendMediaItemToJS(String mediaId) {
    ReactContext reactContext = MediaItemsStore.getInstance().getReactApplicationContext();
    if (reactContext != null) {
      MediaBrowser.MediaItem mediaItem = MediaItemsStore.getInstance().getMediaItemById(mediaId);
      if (mediaItem != null) {
        WritableMap mediaItemMap = Arguments.createMap();
        mediaItemMap.putString("id", mediaItem.getDescription().getMediaId());
        mediaItemMap.putString("title", mediaItem.getDescription().getTitle().toString());
        mediaItemMap.putString("subTitle", mediaItem.getDescription().getSubtitle().toString());
        mediaItemMap.putString("icon", mediaItem.getDescription().getIconUri().toString());
        mediaItemMap.putString("mediaUrl", mediaItem.getDescription().getExtras().getString("media_url"));

        reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
          .emit("onMediaItemSelected", mediaItemMap);
      }
    }
  }
}
