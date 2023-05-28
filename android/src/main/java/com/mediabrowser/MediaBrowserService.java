package com.mediabrowser;

import android.content.Context;
import android.content.res.Configuration;
import android.media.MediaMetadata;
import androidx.media.MediaBrowserServiceCompat;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.util.ArrayList;
import java.util.List;

public class MediaBrowserService extends MediaBrowserServiceCompat implements MediaItemsStore.MediaItemsUpdateListener {
  private static final String MEDIA_ROOT_ID = "ROOT";

  private static final String TAG = "MediaBrowserService";

  MediaSessionCompat mSession;

  @Override
  public void onCreate() {
    super.onCreate();
    Log.d(TAG, "onCreate called");

    MediaItemsStore.getInstance().setListener(this);

    mSession = MediaSessionSingleton.getInstance(this);

//    mSession = new MediaSessionCompat(this, "MediaBrowserService");
    setSessionToken(mSession.getSessionToken());

//    PlaybackStateCompat.Builder stateBuilder = new PlaybackStateCompat.Builder()
//      .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE | PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS);
//    mSession.setPlaybackState(stateBuilder.build());

    mSession.setCallback(new MediaSessionCompat.Callback() {
      @Override
      public void onPrepare() {
        super.onPrepare();
      }

      @Override
      public void onPrepareFromMediaId(String mediaId, Bundle extras) {
        super.onPrepareFromMediaId(mediaId, extras);
      }

      @Override
      public void onPrepareFromSearch(String query, Bundle extras) {
        super.onPrepareFromSearch(query, extras);
      }

      @Override
      public void onPrepareFromUri(Uri uri, Bundle extras) {
        super.onPrepareFromUri(uri, extras);
      }

      @Override
      public void onPlayFromMediaId(String mediaId, Bundle extras) {
        super.onPlayFromMediaId(mediaId, extras);
        sendMediaItemToJS(mediaId);

        // Fetch the MediaItem from the MediaItemsStore.
        MediaBrowserCompat.MediaItem mediaItem = MediaItemsStore.getInstance().getMediaItemById(mediaId);
        if (mediaItem != null) {
          // Update the MediaSession's metadata.
          mSession.setMetadata(new MediaMetadataCompat.Builder()
            .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, mediaItem.getDescription().getMediaId())
            .putString(MediaMetadata.METADATA_KEY_TITLE, mediaItem.getDescription().getTitle().toString())
            .putString(MediaMetadata.METADATA_KEY_ARTIST, mediaItem.getDescription().getSubtitle().toString())
            // Add more metadata fields as needed.
            .build());
        }
      }

      @Override
      public void onPlayFromSearch(String query, Bundle extras) {
        super.onPlayFromSearch(query, extras);
      }

      @Override
      public void onPlayFromUri(Uri uri, Bundle extras) {
        super.onPlayFromUri(uri, extras);
      }
    });

//    mSession.setActive(true);
  }

  @Override
  public void onConfigurationChanged(Configuration newConfig) {
    super.onConfigurationChanged(newConfig);
  }

  //  private MediaSession getMediaSession() {
//    // Get a reference to the MediaSessionManager.
//    MediaSessionManager mediaSessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
//
//    // Get a list of all active media sessions.
//    List<MediaSession> activeSessions = mediaSessionManager.getActiveSessions();
//
//    // Loop through the list of active sessions.
//    for (MediaSession mediaSession : activeSessions) {
//
//      // If the media session is active, then return it.
//      if (mediaSession.isActive()) {
//        return mediaSession;
//      }
//    }
//
//    // No active media session found.
//    return null;
//  }

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
    List<MediaBrowserCompat.MediaItem> mediaHierarchy = MediaItemsStore.getInstance().getMediaItemsByParentId(rootId);
    Log.d(TAG, "Media hierarchy: " + mediaHierarchy);
    return rootId != null ? new BrowserRoot(rootId, null) : null;
  }

  @Override
  public void onLoadChildren(@NonNull final String parentMediaId,
                             @NonNull final Result<List<MediaBrowserCompat.MediaItem>> result) {
    Log.d(TAG, "onLoadChildren called");
    List<MediaBrowserCompat.MediaItem> mediaItems = MediaItemsStore.getInstance().getMediaItemsByParentId(parentMediaId);

    if (mediaItems == null) {
      mediaItems = new ArrayList<>();
    }

    result.sendResult(mediaItems);
  }

  private void sendMediaItemToJS(String mediaId) {
    ReactContext reactContext = MediaItemsStore.getInstance().getReactApplicationContext();
    if (reactContext != null) {
      MediaBrowserCompat.MediaItem mediaItem = MediaItemsStore.getInstance().getMediaItemById(mediaId);
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
