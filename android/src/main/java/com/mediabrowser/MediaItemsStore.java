package com.mediabrowser;

import android.os.Build;
import android.support.v4.media.MediaBrowserCompat;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class MediaItemsStore {
  private static MediaItemsStore instance;

  private Map<String, List<MediaBrowserCompat.MediaItem>> mediaItemsHierarchy;

  private String rootId;

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
  }

  public List<MediaBrowserCompat.MediaItem> getMediaItemsByParentId(String parentId) {
    return mediaItemsHierarchy.get(parentId);
  }

  public void pushMediaItem(String parentId, MediaBrowserCompat.MediaItem newItem) {
    List<MediaBrowserCompat.MediaItem> children = mediaItemsHierarchy.get(parentId);
    if (children != null) {
      children.add(newItem);
    }
  }

  public void deleteMediaItem(String itemId) {
    for (Map.Entry<String, List<MediaBrowserCompat.MediaItem>> entry : mediaItemsHierarchy.entrySet()) {
      List<MediaBrowserCompat.MediaItem> children = entry.getValue();
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        children.removeIf(item -> item.getMediaId().equals(itemId));
      } else {
        Iterator<MediaBrowserCompat.MediaItem> iterator = children.iterator();
        while (iterator.hasNext()) {
          MediaBrowserCompat.MediaItem item = iterator.next();
          if (item.getMediaId().equals(itemId)) {
            iterator.remove();
          }
        }
      }
    }
  }

  public void updateMediaItem(MediaBrowserCompat.MediaItem updatedItem) {
    String itemId = updatedItem.getMediaId();
    for (Map.Entry<String, List<MediaBrowserCompat.MediaItem>> entry : mediaItemsHierarchy.entrySet()) {
      List<MediaBrowserCompat.MediaItem> children = entry.getValue();
      for (int i = 0; i < children.size(); i++) {
        MediaBrowserCompat.MediaItem currentItem = children.get(i);
        if (currentItem.getMediaId().equals(itemId)) {
          children.set(i, updatedItem);
          break;
        }
      }
    }
  }
}
