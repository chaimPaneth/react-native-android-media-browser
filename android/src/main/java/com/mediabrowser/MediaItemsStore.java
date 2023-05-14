package com.mediabrowser;

import android.content.ComponentName;
import android.content.Context;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.os.Build;
import android.service.notification.NotificationListenerService;

import com.facebook.react.bridge.ReactApplicationContext;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class MediaItemsStore extends NotificationListenerService {
  private ReactApplicationContext reactContext;

  private static MediaItemsStore instance;

  private MediaSession.Token sessionToken;

  private Map<String, List<MediaBrowser.MediaItem>> mediaItemsHierarchy;

  private String rootId;

  public void setReactApplicationContext(ReactApplicationContext reactContext) {
    this.reactContext = reactContext;
  }

  public ReactApplicationContext getReactApplicationContext() {
    return reactContext;
  }

  private MediaItemsStore() {
    mediaItemsHierarchy = new HashMap<>();
  }

  public static synchronized MediaItemsStore getInstance() {
    if (instance == null) {
      instance = new MediaItemsStore();
    }
    return instance;
  }

  public void findMediaSession() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      MediaSessionManager mediaSessionManager = (MediaSessionManager) reactContext.getSystemService(Context.MEDIA_SESSION_SERVICE);

      List<MediaController> mediaControllerList = mediaSessionManager.getActiveSessions(
        new ComponentName(reactContext, MediaItemsStore.class));

      for (MediaController m : mediaControllerList) {
        if (m.getPackageName().equals("com.alldaf")) {
          sessionToken = m.getSessionToken();
        }
      }
    }
  }

  public void setRootId(String rootId) {
    this.rootId = rootId;
  }

  public String getRootId() {
    return rootId;
  }

  public void setMediaItemsHierarchy(Map<String, List<MediaBrowser.MediaItem>> hierarchy) {
    this.mediaItemsHierarchy = hierarchy;
    if (listener != null) {
      String rootId = getRootId();
      // If the root ID is null, try to get it from the first item in the hierarchy
      if (rootId == null && !hierarchy.isEmpty()) {
        Map.Entry<String, List<MediaBrowser.MediaItem>> firstEntry = hierarchy.entrySet().iterator().next();
        rootId = firstEntry.getKey();
      }
      listener.onMediaItemsUpdated(rootId);
    }
  }

  public List<MediaBrowser.MediaItem> getMediaItemsByParentId(String parentId) {
    return mediaItemsHierarchy.get(parentId);
  }

  public MediaBrowser.MediaItem getMediaItemById(String itemId) {
    for (Map.Entry<String, List<MediaBrowser.MediaItem>> entry : mediaItemsHierarchy.entrySet()) {
      List<MediaBrowser.MediaItem> children = entry.getValue();
      for (MediaBrowser.MediaItem item : children) {
        if (item.getMediaId().equals(itemId)) {
          return item;
        }
      }
    }
    return null; // Return null if the item is not found
  }

  public void pushMediaItem(String parentId, MediaBrowser.MediaItem newItem) {
    List<MediaBrowser.MediaItem> children = mediaItemsHierarchy.get(parentId);
    if (children != null) {
      children.add(newItem);
    }
    if (listener != null) {
      listener.onMediaItemsUpdated(parentId);
    }
  }

  public void deleteMediaItem(String itemId) {
    String parentId = null;
    for (Map.Entry<String, List<MediaBrowser.MediaItem>> entry : mediaItemsHierarchy.entrySet()) {
      List<MediaBrowser.MediaItem> children = entry.getValue();
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        boolean removed = children.removeIf(item -> item.getMediaId().equals(itemId));
        if (removed) {
          parentId = entry.getKey();
          break;
        }
      } else {
        Iterator<MediaBrowser.MediaItem> iterator = children.iterator();
        while (iterator.hasNext()) {
          MediaBrowser.MediaItem item = iterator.next();
          if (item.getMediaId().equals(itemId)) {
            iterator.remove();
            parentId = entry.getKey();
            break;
          }
        }
      }
    }
    if (listener != null && parentId != null) {
      listener.onMediaItemsUpdated(parentId);
    }
  }

  public void updateMediaItem(MediaBrowser.MediaItem updatedItem) {
    String itemId = updatedItem.getMediaId();
    String parentId = null;
    for (Map.Entry<String, List<MediaBrowser.MediaItem>> entry : mediaItemsHierarchy.entrySet()) {
      List<MediaBrowser.MediaItem> children = entry.getValue();
      for (int i = 0; i < children.size(); i++) {
        MediaBrowser.MediaItem currentItem = children.get(i);
        if (currentItem.getMediaId().equals(itemId)) {
          children.set(i, updatedItem);
          parentId = entry.getKey();
          break;
        }
      }
      if (parentId != null) {
        break;
      }
    }
    if (listener != null && parentId != null) {
      listener.onMediaItemsUpdated(parentId);
    }
  }

  public interface MediaItemsUpdateListener {
    void onMediaItemsUpdated(String parentId);
  }

  private MediaItemsUpdateListener listener;

  public void setListener(MediaItemsUpdateListener listener) {
    this.listener = listener;
  }
}
