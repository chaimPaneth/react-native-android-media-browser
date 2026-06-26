package com.mediabrowser;

import android.content.ComponentName;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;

import com.facebook.react.bridge.ReactApplicationContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class MediaItemsStore extends NotificationListenerService {
  private static final String TAG = "MediaItemsStore";
  private static final String TRANSIENT_PLAYBACK_PARENT_ID = "__TRANSIENT_PLAYBACK__";
  private static final int TRANSIENT_PLAYBACK_MAX_SIZE = 64;
  private ReactApplicationContext reactContext;

  private static MediaItemsStore instance;

  private MediaSessionCompat.Token sessionToken;

  private Map<String, List<MediaBrowserCompat.MediaItem>> mediaItemsHierarchy;

  private String rootId;
  
  // Indicates whether JS has populated the browse hierarchy at least once.
  // Used by the service to show an loading placeholder instead of Android Auto's "No items".
  private volatile boolean isHierarchyReady = false;

  /**
   * Last-resort fallback used by MediaBrowserService ONLY after a timeout.
   * Keep it deterministic and small, but do not use it for normal browsing.
   */
  public List<MediaBrowserCompat.MediaItem> getBootstrapChildren(String parentId) {
    return new ArrayList<>();
  }

  /**
   * Safe getter used by MediaBrowserService.
   * When hierarchy isn't ready, the Service should keep AA on the native spinner via result.detach().
   * When ready, return the real children (may be empty).
   */
  public List<MediaBrowserCompat.MediaItem> getSafeMediaItemsByParentId(String parentId) {
    List<MediaBrowserCompat.MediaItem> items = mediaItemsHierarchy.get(parentId);
    return items != null ? items : new ArrayList<>();
  }

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
    // MBLog.v(TAG, "→ setRootId(rootId=" + rootId + ")");
    this.rootId = rootId;
    // NOTE: Not notifying listener here - updateMediaItems will handle it with cache check
    // MBLog.d(TAG, "MediaItemsStore rootId set to: " + rootId);
  }

  public String getRootId() {
    return rootId;
  }

  public void setMediaItemsHierarchy(Map<String, List<MediaBrowserCompat.MediaItem>> hierarchy) {
    // MBLog.v(TAG, "→ setMediaItemsHierarchy(keys=" + hierarchy.keySet() + ")");
    this.mediaItemsHierarchy = hierarchy;

    // Only mark hierarchy as ready if at least one section has real children.
    // This prevents Android Auto from flushing pending onLoadChildren with empty
    // content when JS pushes a fallback/placeholder hierarchy before data loads.
    boolean hasContent = false;
    for (List<MediaBrowserCompat.MediaItem> items : hierarchy.values()) {
      if (items != null && !items.isEmpty()) {
        hasContent = true;
        break;
      }
    }
    if (hasContent) {
      this.isHierarchyReady = true;
    } else {
      MBLog.d(TAG, "Hierarchy has no real children \u2013 keeping isHierarchyReady=" + this.isHierarchyReady);
    }

    if (listener != null && this.isHierarchyReady) {
      String rootId = getRootId();
      // If the root ID is null, try to get it from the first item in the hierarchy
      if (rootId == null && !hierarchy.isEmpty()) {
        Map.Entry<String, List<MediaBrowserCompat.MediaItem>> firstEntry = hierarchy.entrySet().iterator().next();
        rootId = firstEntry.getKey();
      }
      if (rootId != null) {
        listener.onMediaItemsUpdated(rootId);
      }
    }
  }

  public List<MediaBrowserCompat.MediaItem> getMediaItemsByParentId(String parentId) {
    List<MediaBrowserCompat.MediaItem> items = mediaItemsHierarchy.get(parentId);
    return items != null ? items : new ArrayList<>();
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
    MBLog.v(TAG, "→ pushMediaItem(parentId=" + parentId + ", itemId=" + (newItem != null ? newItem.getMediaId() : "null") + ")");
    List<MediaBrowserCompat.MediaItem> children = mediaItemsHierarchy.get(parentId);
    if (children != null) {
      children.add(newItem);
    }
    notifyIfChanged(parentId);
  }

  public void deleteMediaItem(String itemId) {
    MBLog.v(TAG, "→ deleteMediaItem(itemId=" + itemId + ")");
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
    notifyIfChanged(parentId);
  }

  public void updateMediaItem(MediaBrowserCompat.MediaItem updatedItem) {
    MBLog.v(TAG, "→ updateMediaItem(itemId=" + (updatedItem != null ? updatedItem.getMediaId() : "null") + ")");
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
    notifyIfChanged(parentId);
  }

  public synchronized void upsertTransientMediaItem(MediaBrowserCompat.MediaItem updatedItem) {
    if (updatedItem == null || updatedItem.getDescription() == null || updatedItem.getMediaId() == null) {
      return;
    }

    String itemId = updatedItem.getMediaId();
    for (Map.Entry<String, List<MediaBrowserCompat.MediaItem>> entry : mediaItemsHierarchy.entrySet()) {
      if (TRANSIENT_PLAYBACK_PARENT_ID.equals(entry.getKey())) {
        continue;
      }
      List<MediaBrowserCompat.MediaItem> children = entry.getValue();
      if (children == null) {
        continue;
      }
      for (int index = 0; index < children.size(); index++) {
        MediaBrowserCompat.MediaItem currentItem = children.get(index);
        if (currentItem != null && itemId.equals(currentItem.getMediaId())) {
          return;
        }
      }
    }

    List<MediaBrowserCompat.MediaItem> transientItems = mediaItemsHierarchy.get(TRANSIENT_PLAYBACK_PARENT_ID);
    if (transientItems == null) {
      transientItems = new ArrayList<>();
      mediaItemsHierarchy.put(TRANSIENT_PLAYBACK_PARENT_ID, transientItems);
    }
    for (int index = 0; index < transientItems.size(); index++) {
      MediaBrowserCompat.MediaItem currentItem = transientItems.get(index);
      if (currentItem != null && itemId.equals(currentItem.getMediaId())) {
        transientItems.set(index, updatedItem);
        return;
      }
    }
    transientItems.add(updatedItem);
    // Prune oldest entries to keep the bucket bounded (FIFO eviction)
    while (transientItems.size() > TRANSIENT_PLAYBACK_MAX_SIZE) {
      transientItems.remove(0);
    }
  }

  public void updateMediaItems(String parentId, List<MediaBrowserCompat.MediaItem> updatedItems, boolean replace) {
    // MBLog.v(TAG, "→ updateMediaItems(parentId=" + parentId + ", count=" + updatedItems.size() + ", replace=" + replace + ")");
    if (replace) {
      // Carry over already-decoded artwork from the current items so a re-publish
      // with the same content doesn't momentarily drop images. Without this, a
      // freshly built (art-less) item would change the parent's signature and
      // trigger a spurious reload, then reload again once the art re-attaches.
      List<MediaBrowserCompat.MediaItem> existing = mediaItemsHierarchy.get(parentId);
      if (existing != null && !existing.isEmpty()) {
        Map<String, android.graphics.Bitmap> artById = new HashMap<>();
        for (MediaBrowserCompat.MediaItem ex : existing) {
          if (ex != null && ex.getDescription() != null && ex.getDescription().getIconBitmap() != null) {
            artById.put(ex.getMediaId(), ex.getDescription().getIconBitmap());
          }
        }
        if (!artById.isEmpty()) {
          for (int i = 0; i < updatedItems.size(); i++) {
            MediaBrowserCompat.MediaItem ni = updatedItems.get(i);
            if (ni == null || ni.getDescription() == null) continue;
            if (ni.getDescription().getIconBitmap() != null) continue;
            android.graphics.Bitmap art = artById.get(ni.getMediaId());
            if (art != null) {
              MediaDescriptionCompat d = ni.getDescription();
              MediaDescriptionCompat.Builder b = new MediaDescriptionCompat.Builder()
                .setMediaId(d.getMediaId())
                .setTitle(d.getTitle())
                .setSubtitle(d.getSubtitle())
                .setDescription(d.getDescription())
                .setIconUri(d.getIconUri())
                .setExtras(d.getExtras())
                .setIconBitmap(art);
              updatedItems.set(i, new MediaBrowserCompat.MediaItem(b.build(), ni.getFlags()));
            }
          }
        }
      }
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
    notifyIfChanged(parentId);
  }

  public interface MediaItemsUpdateListener {
    void onMediaItemsUpdated(String parentId);
  }

  private MediaItemsUpdateListener listener;

  // Tracks the last signature we notified per parent. Android Auto reloads a list
  // (and scrolls it back to the top) every time it receives notifyChildrenChanged
  // for the node being viewed. We were firing that on every re-publish and on every
  // now-playing progress tick, which made the list jump repeatedly. We now only
  // notify when a parent's structural content actually changed.
  private final Map<String, String> lastNotifiedSignature = new HashMap<>();

  /**
   * Stable signature of a parent's children, used for change detection.
   * Includes identity/structure (flags, mediaId, title, subtitle) and whether
   * artwork is attached — so artwork that loads in late triggers exactly ONE
   * refresh. It deliberately EXCLUDES playback progress (completion percentage /
   * status), so frequent now-playing progress updates never reload the browse list.
   */
  private String signatureOf(List<MediaBrowserCompat.MediaItem> items) {
    if (items == null) return "\u2205";
    StringBuilder sb = new StringBuilder(items.size() * 32);
    for (MediaBrowserCompat.MediaItem item : items) {
      if (item == null) { sb.append("\u00b7|"); continue; }
      MediaDescriptionCompat d = item.getDescription();
      sb.append(item.getFlags()).append(':')
        .append(item.getMediaId()).append(':')
        .append(d != null && d.getTitle() != null ? d.getTitle() : "").append(':')
        .append(d != null && d.getSubtitle() != null ? d.getSubtitle() : "").append(':')
        .append(d != null && d.getIconBitmap() != null ? "b" : "-")
        .append('|');
    }
    return sb.toString();
  }

  /**
   * Notify the listener for a parent ONLY if its children changed since the last
   * notification. Prevents Android Auto from reloading (and jumping to the top of)
   * a list when nothing visible changed — e.g. redundant re-publishes or progress
   * ticks. Returns true if a notification was sent.
   */
  private boolean notifyIfChanged(String parentId) {
    if (listener == null || parentId == null) return false;
    String sig = signatureOf(mediaItemsHierarchy.get(parentId));
    if (sig.equals(lastNotifiedSignature.get(parentId))) {
      return false; // unchanged — don't make AA reload
    }
    lastNotifiedSignature.put(parentId, sig);
    listener.onMediaItemsUpdated(parentId);
    return true;
  }

  /**
   * Replace an item's artwork bitmap in place. Used by the async artwork loader
   * so the bridge thread never blocks on image decoding. Returns the parentId of
   * the updated item (for a follow-up notifyChildrenChanged), or null if not found.
   * Does NOT notify — callers coalesce notifications per parent.
   */
  public synchronized String updateItemBitmapById(String itemId, android.graphics.Bitmap bmp) {
    if (itemId == null || bmp == null || mediaItemsHierarchy == null) return null;
    for (Map.Entry<String, List<MediaBrowserCompat.MediaItem>> entry : mediaItemsHierarchy.entrySet()) {
      List<MediaBrowserCompat.MediaItem> children = entry.getValue();
      if (children == null) continue;
      for (int i = 0; i < children.size(); i++) {
        MediaBrowserCompat.MediaItem current = children.get(i);
        if (current != null && itemId.equals(current.getMediaId())) {
          MediaDescriptionCompat d = current.getDescription();
          MediaDescriptionCompat.Builder b = new MediaDescriptionCompat.Builder()
            .setMediaId(d.getMediaId())
            .setTitle(d.getTitle())
            .setSubtitle(d.getSubtitle())
            .setDescription(d.getDescription())
            .setIconUri(d.getIconUri())
            .setExtras(d.getExtras())
            .setIconBitmap(bmp);
          MediaBrowserCompat.MediaItem updated =
            new MediaBrowserCompat.MediaItem(b.build(), current.getFlags());
          children.set(i, updated);
          return entry.getKey();
        }
      }
    }
    return null;
  }

  /** Trigger a children-changed notification for a parent (used by async artwork loading).
   *  Gated so it only fires when content actually changed (e.g. artwork attached),
   *  not on redundant calls. */
  public void notifyParentChanged(String parentId) {
    notifyIfChanged(parentId);
  }

  public void setListener(MediaItemsUpdateListener listener) {
    this.listener = listener;
  }

  /**
   * Called when React context becomes active after app restart
   * Triggers pending load requests to be processed
   */
  public void onReactContextReady() {
    MBLog.v(TAG, "→ onReactContextReady()");
    // Notify the service (if listener is set) that we should retry pending loads
    if (listener != null && rootId != null) {
      listener.onMediaItemsUpdated(rootId);
    }
  }

  /**
   * Clear all data from the store
   * Used when recovering from force-stop or cold start
   */
  public void clearAllData() {
    MBLog.v(TAG, "→ clearAllData()");
    mediaItemsHierarchy.clear();
    rootId = null;
    isHierarchyReady = false;
    reactContext = null;
  }

  public boolean isHierarchyReady() {
    return isHierarchyReady;
  }
}
