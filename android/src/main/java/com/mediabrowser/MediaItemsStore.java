package com.mediabrowser;

import android.media.browse.MediaBrowser;
import android.os.Build;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class MediaItemsStore {
  private static MediaItemsStore instance;

  private Map<String, List<MediaBrowser.MediaItem>> mediaItemsHierarchy;

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

  public void setMediaItemsHierarchy(Map<String, List<MediaBrowser.MediaItem>> hierarchy) {
    this.mediaItemsHierarchy = hierarchy;
    if (listener != null) {
      listener.onMediaItemsUpdated();
    }
  }

  public List<MediaBrowser.MediaItem> getMediaItemsByParentId(String parentId) {
    return mediaItemsHierarchy.get(parentId);
  }

  public void pushMediaItem(String parentId, MediaBrowser.MediaItem newItem) {
    List<MediaBrowser.MediaItem> children = mediaItemsHierarchy.get(parentId);
    if (children != null) {
      children.add(newItem);
    }
    if (listener != null) {
      listener.onMediaItemsUpdated();
    }
  }

  public void deleteMediaItem(String itemId) {
    for (Map.Entry<String, List<MediaBrowser.MediaItem>> entry : mediaItemsHierarchy.entrySet()) {
      List<MediaBrowser.MediaItem> children = entry.getValue();
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        children.removeIf(item -> item.getMediaId().equals(itemId));
      } else {
        Iterator<MediaBrowser.MediaItem> iterator = children.iterator();
        while (iterator.hasNext()) {
          MediaBrowser.MediaItem item = iterator.next();
          if (item.getMediaId().equals(itemId)) {
            iterator.remove();
          }
        }
      }
    }
    if (listener != null) {
      listener.onMediaItemsUpdated();
    }
  }

  public void updateMediaItem(MediaBrowser.MediaItem updatedItem) {
    String itemId = updatedItem.getMediaId();
    for (Map.Entry<String, List<MediaBrowser.MediaItem>> entry : mediaItemsHierarchy.entrySet()) {
      List<MediaBrowser.MediaItem> children = entry.getValue();
      for (int i = 0; i < children.size(); i++) {
        MediaBrowser.MediaItem currentItem = children.get(i);
        if (currentItem.getMediaId().equals(itemId)) {
          children.set(i, updatedItem);
          break;
        }
      }
    }
    if (listener != null) {
      listener.onMediaItemsUpdated();
    }
  }

  public interface MediaItemsUpdateListener {
    void onMediaItemsUpdated();
  }

  private MediaItemsUpdateListener listener;

  public void setListener(MediaItemsUpdateListener listener) {
    this.listener = listener;
  }
}
