package com.mediabrowser;

import android.content.ComponentName;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.os.Build;
import android.service.notification.NotificationListenerService;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;

import com.facebook.react.bridge.ReactApplicationContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class MediaItemsStore extends NotificationListenerService {
  private ReactApplicationContext reactContext;

  private static MediaItemsStore instance;

  private MediaSessionCompat.Token sessionToken;

  private Map<String, List<MediaBrowserCompat.MediaItem>> mediaItemsHierarchy;

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

  public void setRootId(String rootId) {
    this.rootId = rootId;
  }

  public String getRootId() {
    return rootId;
  }

  public void setMediaItemsHierarchy(Map<String, List<MediaBrowserCompat.MediaItem>> hierarchy) {
    this.mediaItemsHierarchy = hierarchy;
    if (listener != null) {
      String rootId = getRootId();
      // If the root ID is null, try to get it from the first item in the hierarchy
      if (rootId == null && !hierarchy.isEmpty()) {
        Map.Entry<String, List<MediaBrowserCompat.MediaItem>> firstEntry = hierarchy.entrySet().iterator().next();
        rootId = firstEntry.getKey();
      }
      listener.onMediaItemsUpdated(rootId);
    }
  }

  public List<MediaBrowserCompat.MediaItem> getMediaItemsByParentId(String parentId) {
    return mediaItemsHierarchy.get(parentId);
  }

  public MediaBrowserCompat.MediaItem getMediaItemById(String itemId) {
    for (Map.Entry<String, List<MediaBrowserCompat.MediaItem>> entry : mediaItemsHierarchy.entrySet()) {
      List<MediaBrowserCompat.MediaItem> children = entry.getValue();
      for (MediaBrowserCompat.MediaItem item : children) {
        if (item.getMediaId().equals(itemId)) {
          return item;
        }
      }
    }
    return null; // Return null if the item is not found
  }

  public void pushMediaItem(String parentId, MediaBrowserCompat.MediaItem newItem) {
    List<MediaBrowserCompat.MediaItem> children = mediaItemsHierarchy.get(parentId);
    if (children != null) {
      children.add(newItem);
    }
    if (listener != null) {
      listener.onMediaItemsUpdated(parentId);
    }
  }

  public void deleteMediaItem(String itemId) {
    String parentId = null;
    for (Map.Entry<String, List<MediaBrowserCompat.MediaItem>> entry : mediaItemsHierarchy.entrySet()) {
      List<MediaBrowserCompat.MediaItem> children = entry.getValue();
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        boolean removed = children.removeIf(item -> item.getMediaId().equals(itemId));
        if (removed) {
          parentId = entry.getKey();
          break;
        }
      } else {
        Iterator<MediaBrowserCompat.MediaItem> iterator = children.iterator();
        while (iterator.hasNext()) {
          MediaBrowserCompat.MediaItem item = iterator.next();
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

  public void updateMediaItem(MediaBrowserCompat.MediaItem updatedItem) {
    String itemId = updatedItem.getMediaId();
    String parentId = null;
    for (Map.Entry<String, List<MediaBrowserCompat.MediaItem>> entry : mediaItemsHierarchy.entrySet()) {
      List<MediaBrowserCompat.MediaItem> children = entry.getValue();
      for (int i = 0; i < children.size(); i++) {
        MediaBrowserCompat.MediaItem currentItem = children.get(i);
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

  public void updateMediaItems(String parentId, List<MediaBrowserCompat.MediaItem> updatedItems, boolean replace) {
    if (replace) {
      // Replace all existing items with the new list
      mediaItemsHierarchy.put(parentId, updatedItems);
    } else {
      // Update existing items and add new ones
      List<MediaBrowserCompat.MediaItem> children = mediaItemsHierarchy.get(parentId);
      if (children == null) {
        children = new ArrayList<>();
        mediaItemsHierarchy.put(parentId, children);
      }
      Map<String, MediaBrowserCompat.MediaItem> updatedItemsMap = new HashMap<>();
      for (MediaBrowserCompat.MediaItem updatedItem : updatedItems) {
        updatedItemsMap.put(updatedItem.getMediaId(), updatedItem);
      }
      for (int i = 0; i < children.size(); i++) {
        MediaBrowserCompat.MediaItem currentItem = children.get(i);
        MediaBrowserCompat.MediaItem updatedItem = updatedItemsMap.get(currentItem.getMediaId());
        if (updatedItem != null) {
          // Replace existing item
          children.set(i, updatedItem);
          updatedItemsMap.remove(currentItem.getMediaId());  // Item has been updated, so remove it from the map
        }
      }
      // Add any new items that were not in the original list
      children.addAll(updatedItemsMap.values());
    }
    if (listener != null) {
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
