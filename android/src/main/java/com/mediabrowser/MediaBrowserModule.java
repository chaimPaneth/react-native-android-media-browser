package com.mediabrowser;

import static android.support.v4.media.MediaDescriptionCompat.EXTRA_DOWNLOAD_STATUS;

import static androidx.media.utils.MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_PERCENTAGE;
import static androidx.media.utils.MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS;
import static androidx.media.utils.MediaConstants.METADATA_KEY_IS_EXPLICIT;

import static com.mediabrowser.MediaBrowserUtils.convertReadableMapToJson;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.media.MediaDescription;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;

import androidx.annotation.NonNull;
import androidx.car.app.connection.CarConnection;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.Observer;
import androidx.media.utils.MediaConstants;

import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableType;
import com.facebook.react.bridge.UiThreadUtil;
import com.facebook.react.bridge.WritableNativeMap;
import com.facebook.react.module.annotations.ReactModule;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.facebook.react.bridge.LifecycleEventListener;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CenterCrop;

@ReactModule(name = MediaBrowserModule.NAME)
public class MediaBrowserModule extends ReactContextBaseJavaModule implements LifecycleEventListener {
  public static final String NAME = "MediaBrowser";
  
  // Single source of truth for the Android Auto foreground notification small icon name.
  public static final String ANDROID_AUTO_NOTIFICATION_ICON_NAME = "ic_stat_notify";

  private boolean isReactNativeReady = false;

  private CarConnection carConnection;

  private ReactApplicationContext reactContext;

  public MediaBrowserModule(ReactApplicationContext reactContext) {
    super(reactContext);
    this.reactContext = reactContext;
    MediaItemsStore.getInstance().setReactApplicationContext(reactContext);

    // Register lifecycle listener so we can retry CarConnection when an Activity appears.
    // This is critical for the headless→foreground transition (State 3→2): when
    // MediaBrowserService cold-starts React without an Activity, initializeCarConnection()
    // silently fails. onHostResume fires later when a real Activity appears.
    reactContext.addLifecycleEventListener(this);

    initializeCarConnection();
  }

  @Override
  @NonNull
  public String getName() {
    return NAME;
  }

  private static final String TAG = "MediaBrowserModule";

  // ── Off-bridge artwork loading ────────────────────────────────────────────────
  // The original code loaded each remote icon SYNCHRONOUSLY on the bridge thread,
  // blocking it for ~9s (207 images × cold network fetch) and freezing the app.
  // The fix: load the same images on a background thread pool instead. Same images,
  // same embedded bitmaps Android Auto already knows how to display — just not on
  // the bridge.
  // - 2 threads: enough parallelism without flooding Glide or starving the app's
  //   own image loading (expo-image also uses Glide).
  // - LOW priority: the app's own UI images always win.
  // - Warm Glide cache: on relaunch every image resolves from disk in <1ms.
  private static final int AA_ARTWORK_PX = 320;
  private static final ExecutorService ARTWORK_EXECUTOR = Executors.newFixedThreadPool(2);
  // Decoded artwork cache, keyed by mediaId. The async loader fills this; every
  // item build (createMediaItem) and update (updateMediaItem) re-attaches from it.
  // This decouples the bitmap from the item's lifecycle, so frequent now-playing
  // progress updates can never wipe the art — no matter the load/update ordering.
  // Bounded (LRU, ~32MB) so it can't grow unbounded as daily content changes;
  // evicted entries simply reload on next browse.
  private static final android.util.LruCache<String, Bitmap> ARTWORK_CACHE =
    new android.util.LruCache<String, Bitmap>(32 * 1024 * 1024) {
      @Override protected int sizeOf(String key, Bitmap value) {
        return value != null ? value.getByteCount() : 0;
      }
    };

  /**
   * Synchronously load all remote-artwork bitmaps into the hierarchy items.
   * Called on a background thread (not the bridge). After this returns, every
   * item that had a remote icon URL has its bitmap embedded — so when the
   * hierarchy is stored, AA sees items with art from the very first render.
   * No timers, no re-fetching, no scroll disruption.
   */
  private void loadBitmapsIntoHierarchy(Context context,
                                        Map<String, List<MediaBrowserCompat.MediaItem>> hierarchy) {
    if (context == null || hierarchy == null) return;
    Context app = context.getApplicationContext();
    for (Map.Entry<String, List<MediaBrowserCompat.MediaItem>> entry : hierarchy.entrySet()) {
      List<MediaBrowserCompat.MediaItem> items = entry.getValue();
      if (items == null) continue;
      for (int i = 0; i < items.size(); i++) {
        MediaBrowserCompat.MediaItem item = items.get(i);
        if (item == null || item.getDescription() == null) continue;
        if (item.getDescription().getIconBitmap() != null) continue;
        Uri iconUri = item.getDescription().getIconUri();
        String url = iconUri != null ? iconUri.getQueryParameter("url") : null;
        if ((url == null || url.isEmpty()) && iconUri != null) {
          String sch = iconUri.getScheme();
          if ("http".equalsIgnoreCase(sch) || "https".equalsIgnoreCase(sch)) {
            url = iconUri.toString();
          }
        }
        if (url == null || url.isEmpty()) continue;
        try {
          Bitmap bmp = Glide.with(app)
            .asBitmap()
            .load(url)
            .transform(new CenterCrop())
            .override(AA_ARTWORK_PX, AA_ARTWORK_PX)
            .priority(com.bumptech.glide.Priority.LOW)
            .submit()
            .get(10, TimeUnit.SECONDS);
          if (bmp != null) {
            MediaDescriptionCompat d = item.getDescription();
            MediaDescriptionCompat.Builder b = new MediaDescriptionCompat.Builder()
              .setMediaId(d.getMediaId())
              .setTitle(d.getTitle())
              .setSubtitle(d.getSubtitle())
              .setDescription(d.getDescription())
              .setIconUri(d.getIconUri())
              .setExtras(d.getExtras())
              .setIconBitmap(bmp);
            items.set(i, new MediaBrowserCompat.MediaItem(b.build(), item.getFlags()));
            ARTWORK_CACHE.put(item.getMediaId(), bmp);
          }
        } catch (Throwable ignored) { /* leave without art; not fatal */ }
      }
    }
  }

  /**
   * Load artwork for a batch of items on a background thread, then refresh each
   * affected parent ONCE. Items that already carry a bitmap are skipped, so this is
   * cheap on re-publishes. No timers and no per-item notifications: every bitmap is
   * decoded and embedded first, then a single notifyChildrenChanged per parent tells
   * Android Auto to re-read the finished list.
   */
  private void loadArtworkForItems(final Context context, final String parentId,
                                   final List<MediaBrowserCompat.MediaItem> items) {
    if (context == null || items == null || items.isEmpty()) return;
    final Context app = context.getApplicationContext();
    ARTWORK_EXECUTOR.submit(new Runnable() {
      @Override public void run() {
        Set<String> affectedParents = new HashSet<>();
        for (final MediaBrowserCompat.MediaItem item : items) {
          if (item == null || item.getDescription() == null) continue;
          if (item.getDescription().getIconBitmap() != null) continue; // already has art
          Uri iconUri = item.getDescription().getIconUri();
          // Remote icons are stored as content://…?url=<remote>; recover the source URL.
          String u = iconUri != null ? iconUri.getQueryParameter("url") : null;
          if ((u == null || u.isEmpty()) && iconUri != null) {
            String sch = iconUri.getScheme();
            if ("http".equalsIgnoreCase(sch) || "https".equalsIgnoreCase(sch)) {
              u = iconUri.toString();
            }
          }
          if (u == null || u.isEmpty()) continue;
          final String mediaId = item.getMediaId();
          try {
            Bitmap bmp = Glide.with(app)
              .asBitmap()
              .load(u)
              .transform(new CenterCrop())
              .override(AA_ARTWORK_PX, AA_ARTWORK_PX)
              .priority(com.bumptech.glide.Priority.LOW)
              .submit()
              .get(30, TimeUnit.SECONDS);
            if (bmp != null) {
              ARTWORK_CACHE.put(mediaId, bmp);
              String resolved = MediaItemsStore.getInstance().updateItemBitmapById(mediaId, bmp);
              affectedParents.add(resolved != null ? resolved : parentId);
            }
          } catch (Throwable ignored) { /* leave item without art; not fatal */ }
        }
        for (String p : affectedParents) {
          if (p != null) MediaItemsStore.getInstance().notifyParentChanged(p);
        }
      }
    });
  }


  @Override
  public void initialize() {
    MBLog.v(TAG, "→ initialize()");
    super.initialize();
    
    boolean active = reactContext != null && reactContext.hasActiveReactInstance();
    
    // React Native is ready, we can register the receiver
    isReactNativeReady = true;

    // CRITICAL FIX: Notify MediaBrowserService that context is now active
    // This will flush any pending AA load requests that were waiting
    if (active) {
      MediaItemsStore.getInstance().onReactContextReady();
    }

    if (carConnection == null) {
      initializeCarConnection();
    }
  }

  // ── LifecycleEventListener ──────────────────────────────────────────
  // onHostResume fires when an Activity enters the foreground.
  // In the headless cold-start path (State 3) there is no Activity, so
  // CarConnection is never created. When the user later opens the app
  // (transition 3→2) this callback retries initializeCarConnection().
  @Override
  public void onHostResume() {
    if (carConnection == null && isReactNativeReady) {
      MBLog.d(TAG, "onHostResume: retrying CarConnection init (was null)");
      initializeCarConnection();
    }
  }

  @Override public void onHostPause() { /* no-op */ }
  @Override public void onHostDestroy() { /* no-op */ }

  private void initializeCarConnection() {
    MBLog.d(TAG, "Initializing CarConnection");
    UiThreadUtil.runOnUiThread(new Runnable() {
      @Override
      public void run() {
        if (isReactNativeReady && carConnection == null) {
          Activity currentActivity = reactContext.getCurrentActivity();
          if (currentActivity == null) {
            return;
          }

          carConnection = new CarConnection(reactContext);
          carConnection.getType().observe((LifecycleOwner) currentActivity, new Observer<Integer>() {
            @Override
            public void onChanged(Integer connectionType) {
              sendCarConnectionToJS(connectionType);

//        switch (connectionType) {
//          case CarConnection.CONNECTION_TYPE_NOT_CONNECTED:
//            break;
//          case CarConnection.CONNECTION_TYPE_NATIVE:
//            // Handle native connection state
//            break;
//          case CarConnection.CONNECTION_TYPE_PROJECTION:
//            // Handle projection connection state
//            break;
//        }
            }
          });
        }
      }
    });
  }

  @ReactMethod
  public void hasAndroidAutoNotificationIcon(Promise promise) {
      MBLog.v(TAG, "→ hasAndroidAutoNotificationIcon()");
      try {
          int resId = getReactApplicationContext()
              .getResources()
              .getIdentifier(
                  ANDROID_AUTO_NOTIFICATION_ICON_NAME,
                  "drawable",
                  getReactApplicationContext().getPackageName()
              );

          promise.resolve(resId != 0);
      } catch (Throwable t) {
          promise.reject(
              "ICON_CHECK_FAILED",
              "Failed to check for " + ANDROID_AUTO_NOTIFICATION_ICON_NAME,
              t
          );
      }
  }

  @ReactMethod
  public void getAndroidAutoNotificationIconName(Promise promise) {
      MBLog.v(TAG, "→ getAndroidAutoNotificationIconName()");
      promise.resolve(ANDROID_AUTO_NOTIFICATION_ICON_NAME);
  }

  @ReactMethod
  public void setMediaItems(ReadableMap itemsMap, Promise promise) {
    MBLog.v(TAG, "→ setMediaItems()");
    try {
      final Map<String, List<MediaBrowserCompat.MediaItem>> hierarchy = buildMediaItemsHierarchy(itemsMap);
      final String rootId = itemsMap.getString("id");
      final Context context = getReactApplicationContext();

      // Resolve immediately so the bridge thread never blocks on image decoding.
      promise.resolve(null);

      // Load all artwork on a background thread, THEN store the hierarchy. This
      // guarantees Android Auto never sees items without bitmaps — no "cached as
      // empty" items, no timers, no re-fetch loops, no scroll disruption.
      ARTWORK_EXECUTOR.submit(new Runnable() {
        @Override public void run() {
          loadBitmapsIntoHierarchy(context, hierarchy);
          MediaItemsStore.getInstance().setRootId(rootId);
          MediaItemsStore.getInstance().setMediaItemsHierarchy(hierarchy);
        }
      });
    } catch (Exception e) {
      MBLog.e(TAG, "❌ setMediaItems FAILED", e);
      promise.reject("ERROR", e);
    }
  }

  @ReactMethod
  public void pushMediaItem(String parentId, ReadableMap itemMap, Promise promise) {
    MBLog.v(TAG, "→ pushMediaItem(parentId=" + parentId + ")");
    try {
      MediaBrowserCompat.MediaItem newItem = createMediaItem(itemMap);
      MediaItemsStore.getInstance().pushMediaItem(parentId, newItem);
      promise.resolve(null);
    } catch (Exception e) {
      promise.reject("ERROR", e);
    }
  }

  @ReactMethod
  public void deleteMediaItem(String itemId, Promise promise) {
    MBLog.v(TAG, "→ deleteMediaItem(itemId=" + itemId + ")");
    try {
      MediaItemsStore.getInstance().deleteMediaItem(itemId);
      promise.resolve(null);
    } catch (Exception e) {
      promise.reject("ERROR", e);
    }
  }

  @ReactMethod
  public void updateMediaItem(ReadableMap item, Promise promise) {
    MBLog.v(TAG, "→ updateMediaItem(id=" + (item.hasKey("id") ? item.getString("id") : "?") + ")");
    try {
      if (item.hasKey("id")) {
        String mediaId = item.getString("id");
        MediaBrowserCompat.MediaItem existingMediaItem = MediaItemsStore.getInstance().getMediaItemById(mediaId);
        if (existingMediaItem != null) {
          MediaDescriptionCompat oldDescription = existingMediaItem.getDescription();

          Bitmap oldIconBitmap = oldDescription.getIconBitmap();

          MediaDescriptionCompat.Builder descriptionBuilder = new MediaDescriptionCompat.Builder();
          descriptionBuilder.setMediaId(oldDescription.getMediaId())
            .setTitle(oldDescription.getTitle())
            .setSubtitle(oldDescription.getSubtitle())
            .setDescription(oldDescription.getDescription())
            .setIconUri(oldDescription.getIconUri())
            .setExtras(oldDescription.getExtras());

          // Preserve previous bitmap to avoid artwork loss when only extras change
          if (oldIconBitmap != null) {
            descriptionBuilder.setIconBitmap(oldIconBitmap);
          }

          if (item.hasKey("title")) {
            descriptionBuilder.setTitle(item.getString("title"));
          }
          if (item.hasKey("subTitle")) {
            descriptionBuilder.setSubtitle(item.getString("subTitle"));
          }
          if (item.hasKey("description")) {
            descriptionBuilder.setDescription(item.getString("description"));
          }

          // Handle icon with modern validation, fallback to legacy
          if (item.hasKey("icon")) {
            applyIconWithValidation(item, descriptionBuilder);
            // Re-attach the decoded artwork from the cache. This is what keeps the
            // image on items that get frequent updates (e.g. now-playing progress):
            // the bitmap lives in the cache, not in the item, so an update can't
            // wipe it regardless of load/update timing. Fall back to the previous
            // bitmap if the cache hasn't been populated yet.
            Bitmap cachedArt = ARTWORK_CACHE.get(mediaId);
            if (cachedArt != null) {
              descriptionBuilder.setIconBitmap(cachedArt);
            } else if (oldIconBitmap != null) {
              descriptionBuilder.setIconBitmap(oldIconBitmap);
            }
          } else if (item.hasKey("iconUri")) {
            String iconUri = item.getString("iconUri");
            if (iconUri != null && !iconUri.trim().isEmpty()) {
              try {
                descriptionBuilder.setIconUri(Uri.parse(iconUri));
              } catch (Exception e) {
                MBLog.w(TAG, "Invalid iconUri provided: " + iconUri, e);
              }
            }
          }

          ReadableMap itemExtras = item.hasKey("extras") ? item.getMap("extras") : null;
          if (itemExtras != null) {
            Bundle extras = oldDescription.getExtras();
            if (extras == null) {
              extras = new Bundle();
            }
            if (itemExtras.hasKey(DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS)) {
              extras.putInt(DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS, itemExtras.getInt(DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS));
            }
            if (itemExtras.hasKey(DESCRIPTION_EXTRAS_KEY_COMPLETION_PERCENTAGE)) {
              extras.putDouble(DESCRIPTION_EXTRAS_KEY_COMPLETION_PERCENTAGE, itemExtras.getDouble(DESCRIPTION_EXTRAS_KEY_COMPLETION_PERCENTAGE));
            }
            // Allow updating the "info" JSON directly (string or map) — MERGE, don't replace
            if (itemExtras.hasKey("info")) {
              try {
                // Parse existing info JSON (if any)
                String existingInfoStr = extras.getString("info");
                JSONObject baseInfo;
                try {
                  baseInfo = existingInfoStr != null ? new JSONObject(existingInfoStr) : new JSONObject();
                } catch (Exception ignore) {
                  baseInfo = new JSONObject();
                }

                // Parse incoming updates (string or map)
                JSONObject updateInfo = new JSONObject();
                if (itemExtras.getType("info") == ReadableType.Map) {
                  ReadableMap infoMap = itemExtras.getMap("info");
                  if (infoMap != null) {
                    updateInfo = convertReadableMapToJson(infoMap);
                  }
                } else if (itemExtras.getType("info") == ReadableType.String) {
                  String infoStr = itemExtras.getString("info");
                  if (infoStr != null) {
                    try { updateInfo = new JSONObject(infoStr); } catch (Exception ignored) {}
                  }
                }

                // Shallow-merge: new values override existing keys, others are kept
                JSONObject merged = mergeJsonObjects(baseInfo, updateInfo);
                extras.putString("info", merged.toString());
              } catch (Exception e) {
                MBLog.w(TAG, "Failed to merge extras.info from itemExtras", e);
              }
            }
            descriptionBuilder.setExtras(extras);
          }

          // Always re-attach decoded artwork from the cache before building —
          // regardless of whether this update carried an icon, iconUri, or only
          // extras. Now-playing progress updates carry ONLY extras, so they'd
          // otherwise rely on the old bitmap, which a load/update race can null out
          // permanently. The cache is the single source of truth for the bitmap.
          Bitmap cachedArtFinal = ARTWORK_CACHE.get(mediaId);
          if (cachedArtFinal != null) {
            descriptionBuilder.setIconBitmap(cachedArtFinal);
          } else if (existingMediaItem.getDescription().getIconBitmap() == null) {
            // No cached art yet and the item has no bitmap — kick off a background
            // load from its existing icon URI. Covers items whose art was never
            // pre-loaded (e.g. a now-playing item that only arrives via updateMediaItem).
            // Glide de-dupes, so repeated progress updates won't pile up downloads.
            loadArtworkForItems(getReactApplicationContext(), null,
              java.util.Collections.singletonList(existingMediaItem));
          }

          MediaDescriptionCompat newDescription = descriptionBuilder.build();
          MediaBrowserCompat.MediaItem updatedItem = new MediaBrowserCompat.MediaItem(newDescription, existingMediaItem.getFlags());
          MediaItemsStore.getInstance().updateMediaItem(updatedItem);
          promise.resolve("Media item with id " + mediaId + " updated successfully.");
        } else {
          MBLog.e(TAG, "Media item with id " + mediaId + " not found.");
          promise.reject("ERR_ITEM_NOT_FOUND", "Media item with id " + mediaId + " not found.");
        }
      } else {
        MBLog.e(TAG, "Required key id was not provided.");
        promise.reject("ERR_REQUIRED_KEY_NOT_PROVIDED", "Required key id was not provided.");
      }
    } catch (Exception e) {
      MBLog.e(TAG, "Error updating media item", e);
      promise.reject("ERR_UPDATE_MEDIA_ITEM", e.getMessage(), e);
    }
  }

  // Shallow-merge: values from "updates" overwrite or extend "base"
  private static JSONObject mergeJsonObjects(JSONObject base, JSONObject updates) {
    if (base == null) base = new JSONObject();
    if (updates == null) return base;
    try {
      java.util.Iterator<String> it = updates.keys();
      while (it.hasNext()) {
        String k = it.next();
        Object v = updates.opt(k);
        // Shallow merge only; nested objects are replaced as a whole
        base.put(k, v);
      }
    } catch (Exception ignored) {}
    return base;
  }

  @ReactMethod
  public void updateMediaItems(String parentId, ReadableArray updatedItemsArray, boolean replace, Promise promise) {
    // MBLog.v(TAG, "→ updateMediaItems(parentId=" + parentId + ", replace=" + replace + ")");
    if (updatedItemsArray == null) {
      MBLog.e(TAG, "updateMediaItems called with null updatedItems");
      promise.reject("ERR_NULL_UPDATED_ITEMS", "updatedItemsArray was null");
      return;
    }

    try {
      handleItemsArray(parentId, updatedItemsArray, replace);
      promise.resolve("Success");
    } catch (Exception e) {
      MBLog.e(TAG, "Error updating media items", e);
      promise.reject("ERR_UPDATE_MEDIA_ITEMS", e.getMessage(), e);
    }
  }

  private void handleItemsMap(String parentId, ReadableMap itemMap, boolean replace) throws Exception {
    MediaBrowserCompat.MediaItem mediaItem = createMediaItem(itemMap);
    List<MediaBrowserCompat.MediaItem> updatedItems = new ArrayList<>();
    updatedItems.add(mediaItem);

    if (itemMap.hasKey("children")) {
      ReadableArray childrenArray = itemMap.getArray("children");
      handleItemsArray(mediaItem.getDescription().getMediaId(), childrenArray, replace);
    }

    MediaItemsStore.getInstance().updateMediaItems(parentId, updatedItems, replace);
  }

  private void handleItemsArray(String parentId, ReadableArray itemsArray, boolean replace) {
    // MBLog.d(TAG, "Handling items array for parentId=" + parentId + ", count=" + itemsArray.size() + ", replace=" + replace);
    List<MediaBrowserCompat.MediaItem> updatedItems = new ArrayList<>();
    for (int i = 0; i < itemsArray.size(); i++) {
      try {
        ReadableMap itemMap = itemsArray.getMap(i);
        MediaBrowserCompat.MediaItem mediaItem = createMediaItem(itemMap);

        if (itemMap.hasKey("children")) {
          ReadableArray childrenArray = itemMap.getArray("children");
          if (childrenArray != null) {
            handleItemsArray(mediaItem.getDescription().getMediaId(), childrenArray, replace);
          }
        }

        updatedItems.add(mediaItem);
      } catch (Exception e) {
        MBLog.e(TAG, "Error parsing JSON at index " + i + " in the items array", e);
      }
    }

    MediaItemsStore.getInstance().updateMediaItems(parentId, updatedItems, replace);
    // Load artwork on a background thread for this batch too.
    loadArtworkForItems(getReactApplicationContext(), parentId, updatedItems);
  }

  private void sendCarConnectionToJS(Integer carState) {
    MBLog.d(TAG, "Car connection state changed: " + carState);

    // When AA disconnects (0 = CONNECTION_TYPE_NOT_CONNECTED), immediately
    // clean the MediaSession so the NEXT connection starts with a blank state.
    // The session is a long-lived singleton; without this cleanup it retains
    // stale actions=311 which AA reads BEFORE onGetRoot() on the next connect,
    // causing it to navigate to the empty Now Playing screen.
    if (carState == 0) {
      MBLog.d(TAG, "  AA disconnected – clearing MediaSession for next reconnect");
      MediaBrowserService.clearSessionForReconnect();
    }

    if (isReactNativeReady) {
      getReactApplicationContext()
        .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
        .emit("onCarConnectionChanged", carState);
    }
    
    // Trigger headless service for background/killed app scenario
    Intent intent = new Intent(getReactApplicationContext(), MediaBrowserHeadlessService.class);
    intent.setAction(MediaBrowserHeadlessService.ACTION_CAR_CONNECTION_CHANGED);
    intent.putExtra("connectionType", carState);
    
    try {
      getReactApplicationContext().startService(intent);
    } catch (Exception e) {
      MBLog.e(TAG, "Failed to start headless service for car connection", e);
    }
  }

  private Map<String, List<MediaBrowserCompat.MediaItem>> buildMediaItemsHierarchy(ReadableMap itemsMap) throws Exception {
    // MBLog.d(TAG, "Building media items hierarchy from provided map");
    Map<String, List<MediaBrowserCompat.MediaItem>> hierarchy = new HashMap<>();

    String rootId = itemsMap.getString("id");
    ReadableArray rootItems = itemsMap.getArray("root");

    for (int i = 0; i < rootItems.size(); i++) {
      ReadableMap item = rootItems.getMap(i);
      addMediaItemToHierarchy(item, hierarchy, rootId);
    }

    return hierarchy;
  }

  private void addMediaItemToHierarchy(ReadableMap itemMap, Map<String, List<MediaBrowserCompat.MediaItem>> hierarchy, String parentId) throws Exception {
    // MBLog.d(TAG, "Adding media item to hierarchy: " + itemMap.getString("id") + " under parentId=" + parentId);
    MediaBrowserCompat.MediaItem mediaItem = createMediaItem(itemMap);

    if (!hierarchy.containsKey(parentId)) {
      hierarchy.put(parentId, new ArrayList<>());
    }
    hierarchy.get(parentId).add(mediaItem);

    if (itemMap.hasKey("children")) {
      ReadableArray children = itemMap.getArray("children");
      for (int i = 0; i < children.size(); i++) {
        ReadableMap childItem = children.getMap(i);
        addMediaItemToHierarchy(childItem, hierarchy, itemMap.getString("id"));
      }
    }
  }

  private MediaBrowserCompat.MediaItem createMediaItem(ReadableMap itemMap) throws Exception {
    // MBLog.d(TAG, "Creating media item: " + itemMap.getString("id"));
    String mediaId = itemMap.getString("id");
    MediaDescriptionCompat.Builder description = new MediaDescriptionCompat.Builder()
      .setMediaId(mediaId);

    if (itemMap.hasKey("title")) {
      description.setTitle(itemMap.getString("title"));
    }
    if (itemMap.hasKey("subTitle")) {
      description.setSubtitle(itemMap.getString("subTitle"));
    }

    // Validate and apply icon (production-clean: no debug bundle)
    applyIconWithValidation(itemMap, description);
    // Re-attach already-decoded artwork from the cache so a re-published tree keeps
    // its images immediately (and doesn't need to reload them).
    Bitmap cachedArt = ARTWORK_CACHE.get(mediaId);
    if (cachedArt != null) {
      description.setIconBitmap(cachedArt);
    }

    Bundle extras = new Bundle();
    if (itemMap.hasKey("browsableStyle")) {
      String browsableStyle = itemMap.getString("browsableStyle");
      extras.putInt(
        MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
        mapContentStyle(browsableStyle));
    }

    if (itemMap.hasKey("playableStyle")) {
      String playableStyle = itemMap.getString("playableStyle");
      extras.putInt(
        MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
        mapContentStyle(playableStyle));
    }

    if (itemMap.hasKey("groupTitle")) {
      String groupTitle = itemMap.getString("groupTitle");
      extras.putString(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, groupTitle);
    }

    ReadableMap itemExtras;

    if (itemMap.hasKey("extras")) {
      itemExtras = itemMap.getMap("extras");
      if (itemExtras != null && itemExtras.hasKey("info")) {
        ReadableMap infoMap = itemExtras.getMap("info");
        extras.putString("info", convertReadableMapToJson(infoMap).toString());
      }
    } else {
      itemExtras = new WritableNativeMap();
    }

    if (itemExtras.hasKey(EXTRA_DOWNLOAD_STATUS)) {
      extras.putInt(EXTRA_DOWNLOAD_STATUS, itemExtras.getInt(EXTRA_DOWNLOAD_STATUS));
    }
    if (itemExtras.hasKey(METADATA_KEY_IS_EXPLICIT)) {
      extras.putLong(METADATA_KEY_IS_EXPLICIT, itemExtras.getInt(METADATA_KEY_IS_EXPLICIT));
    }
    if (itemExtras.hasKey(DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS)) {
      extras.putInt(DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS, itemExtras.getInt(DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS));
    }
    if (itemExtras.hasKey(DESCRIPTION_EXTRAS_KEY_COMPLETION_PERCENTAGE)) {
      extras.putDouble(DESCRIPTION_EXTRAS_KEY_COMPLETION_PERCENTAGE, itemExtras.getDouble(DESCRIPTION_EXTRAS_KEY_COMPLETION_PERCENTAGE));
    }

    description.setExtras(extras);

    if (!itemMap.hasKey("playableOrBrowsable")) {
      throw new Error("Required field playableOrBrowsable not provided.");
    }

    int flags = Objects.equals(itemMap.getString("playableOrBrowsable"), "PLAYABLE")
      ? MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
      : MediaBrowserCompat.MediaItem.FLAG_BROWSABLE;

    return new MediaBrowserCompat.MediaItem(description.build(), flags);
  }

  /**
   * Validates the incoming icon and applies it to the description.
   * For http/https and 127.0.0.1 hosts, we embed a Bitmap via Fresco (AA-friendly).
   * For res: scheme, we map to android.resource://<pkg>/<id>.
   * For content:// and android.resource:// we pass through.
   * Otherwise, we fall back to a debug icon.
   * Returns a Bundle with debug fields about what happened.
   */
  private void applyIconWithValidation(ReadableMap itemMap, MediaDescriptionCompat.Builder description) {
    if (itemMap == null || !itemMap.hasKey("icon")) {
      return; // no icon provided
    }

    String iconStr = itemMap.getString("icon");
    // MBLog.d(TAG, "Applying icon with validation: " + iconStr);
    if (iconStr == null || iconStr.trim().isEmpty()) {
      return; // empty icon
    }

    try {
      ReactApplicationContext context = getReactApplicationContext();
      Uri iconUri = Uri.parse(iconStr);
      String scheme = iconUri.getScheme();
      String host = iconUri.getHost();

      // 0) No scheme provided (e.g., "ic_launcher_round"): treat as resource name
      if (scheme == null || scheme.trim().isEmpty()) {
        try {
          String resName = iconStr;
          if (resName != null) {
            // Normalize common prefixes like @drawable/, drawable/, @mipmap/, mipmap/
            if (resName.startsWith("@")) resName = resName.substring(1);
            if (resName.startsWith("drawable/")) resName = resName.substring("drawable/".length());
            if (resName.startsWith("mipmap/")) resName = resName.substring("mipmap/".length());

            int iconResId = context.getResources().getIdentifier(resName, "drawable", context.getPackageName());
            if (iconResId == 0) {
              iconResId = context.getResources().getIdentifier(resName, "mipmap", context.getPackageName());
            }
            if (iconResId != 0) {
              Uri resUri = Uri.parse("android.resource://" + context.getPackageName() + "/" + iconResId);
              // Embed bitmap for hosts that ignore URIs
              try {
                Bitmap bmp = Glide.with(context)
                  .asBitmap()
                  .load(iconResId)
                  .transform(new CenterCrop())
                  .override(384, 384)
                  .submit()
                  .get(10, TimeUnit.SECONDS);
                description.setIconBitmap(bmp);
              } catch (Throwable t) { }

              // Also provide content:// via our provider for hosts that prefer URIs
              try {
                Uri contentUri = MediaArtworkContentProvider.asAlbumArtContentURI(context, resUri);
                description.setIconUri(contentUri);
              } catch (Throwable t) {
                description.setIconUri(resUri);
              }
              // Handled
              return;
            } else { }
          }
        } catch (Throwable t) { }
        // If not resolvable, fall through to other handlers (will likely do nothing)
      }

      // 1) res: -> android.resource:// mapping
      if ("res".equalsIgnoreCase(scheme)) {
        String resName = iconUri.getLastPathSegment(); // e.g., "home"
        if (resName != null && !resName.isEmpty()) {
          int iconResId = context.getResources().getIdentifier(resName, "drawable", context.getPackageName());
          if (iconResId == 0) {
            iconResId = context.getResources().getIdentifier(resName, "mipmap", context.getPackageName());
          }
          if (iconResId != 0) {
            description.setIconUri(Uri.parse("android.resource://" + context.getPackageName() + "/" + iconResId));
          }
        }
        return;
      }

      // 2) content:// and android.resource:// passthrough
      if (ContentResolver.SCHEME_CONTENT.equalsIgnoreCase(scheme) || "android.resource".equalsIgnoreCase(scheme)) {
        description.setIconUri(iconUri);
        return;
      }

      // 3) Network / LAN (Metro and remote)
      boolean isNetwork = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
      boolean isLocalhostOrLan = host != null && (
        "127.0.0.1".equals(host) ||
        "localhost".equals(host) ||
        host.startsWith("10.") ||
        host.startsWith("192.168.") ||
        (host.startsWith("172.") && is172PrivateSecondOctet(host)) // 172.16–172.31
      );

      if (isNetwork || isLocalhostOrLan) {
        // 3a) Try to map Metro packager assets: unstable_path=.../<name>@3x.png -> R.drawable.<name>
        try {
          String query = iconUri.getQuery();
          if (query != null) {
            String marker = "unstable_path=";
            int idx = query.indexOf(marker);
            if (idx != -1) {
              String after = query.substring(idx + marker.length());
              try { 
                after = java.net.URLDecoder.decode(after, "UTF-8"); 
              } catch (Exception e) {}

              int slash = Math.max(after.lastIndexOf('/'), after.lastIndexOf('\\'));
              String fileName = slash >= 0 ? after.substring(slash + 1) : after; // e.g., home@3x.png

              if (!fileName.isEmpty()) {
                int dot = fileName.lastIndexOf('.');
                String base = dot >= 0 ? fileName.substring(0, dot) : fileName;  // home@3x
                String resName = base.replaceFirst("@\\d+x$", "");                    // home

                String pkg = context.getPackageName();
                int resId = context.getResources().getIdentifier(resName, "drawable", pkg);
                if (resId == 0) {
                  resId = context.getResources().getIdentifier(resName, "mipmap", pkg);
                }
                if (resId != 0) {
                  Uri resUri = Uri.parse("android.resource://" + pkg + "/" + resId);
                  description.setIconUri(resUri);
                  return;
                }
              }
            }
          }
        } catch (Exception e) {}

        // 3b) Store a content:// URI that carries the remote URL (?url=). We
        // deliberately DO NOT decode here — a synchronous fetch+decode per item
        // would block the bridge thread and freeze the app. The bitmap is loaded
        // off-bridge later (loadBitmapsIntoHierarchy for setMediaItems, or
        // loadArtworkForItems for incremental updates) and embedded into the item.
        try {
          Uri contentUri = asAlbumArtContentURI(iconUri);
          description.setIconUri(contentUri);
          return;
        } catch (Exception e) {}

        // 3c) Fallback: keep the raw remote URI so off-bridge loading can still find it.
        try {
          description.setIconUri(iconUri);
        } catch (Exception e) {}
      }

      // 4) Unsupported/unknown scheme: do nothing (no debug icon in production)
    } catch (Exception e) {}
  }

  // Helper: 172.16–172.31.*
  private static boolean is172PrivateSecondOctet(String host) {
    String[] parts = host.split("\\.");
    if (parts.length < 2) return false;
    try {
      int second = Integer.parseInt(parts[1]);
      return second >= 16 && second <= 31;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  public static Uri asAlbumArtContentURI(Uri webUri) {
    return new Uri.Builder()
      .scheme(ContentResolver.SCHEME_CONTENT)
      .authority(MediaArtworkContentProvider.CONTENT_PROVIDER_AUTHORITY)
      .appendQueryParameter("url", webUri.toString())
      .build();
  }

  private int mapContentStyle(String contentStyle) {
    switch (contentStyle) {
      case "CONTENT_STYLE_GRID_ITEM":
        return MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM;
      case "CONTENT_STYLE_LIST_ITEM":
        return MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM;
      case "CONTENT_STYLE_CATEGORY_LIST_ITEM":
        return MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_LIST_ITEM;
      case "CONTENT_STYLE_CATEGORY_GRID_ITEM":
        return MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_GRID_ITEM;
      default:
        return -1;
    }
  }
  
  @ReactMethod
  public void registerEventHandler() {
    MBLog.v(TAG, "→ registerEventHandler()");
    // This method is used to register the headless task event handler
    // The actual event handling is done in the HeadlessJsTaskService
    try {
      Intent intent = new Intent(getReactApplicationContext(), MediaBrowserService.class);
      intent.setAction(MediaBrowserService.ACTION_JS_READY);
      getReactApplicationContext().startService(intent);
    } catch (Throwable t) {
      MBLog.e(TAG, "Failed to send ACTION_JS_READY to MediaBrowserService", t);
    }
  }

  @ReactMethod
  public void playFromMediaId(String mediaId, ReadableMap postData, Promise promise) {
    MBLog.v(TAG, "→ playFromMediaId(mediaId=" + mediaId + ")");
    try {
      Bundle extras = null;
      ReadableMap postInfo = null;
      
      // First try to get extras from provided postData
      if (postData != null) {
        extras = new Bundle();
        
        // Check if postData has the standard MediaItem structure with nested extras.info
        // or if it's the raw post object itself
        if (postData.hasKey("extras")) {
          // Standard MediaItem structure: { id, title, extras: { info: {...} } }
          ReadableMap itemExtras = postData.getMap("extras");
          if (itemExtras != null && itemExtras.hasKey("info")) {
            if (itemExtras.getType("info") == ReadableType.Map) {
              postInfo = itemExtras.getMap("info");
            } else if (itemExtras.getType("info") == ReadableType.String) {
              // Already stringified, just use it
              try {
                String infoStr = itemExtras.getString("info");
                extras.putString("info", infoStr);
                extras.putString("infoJson", infoStr);
              } catch (Exception e) {
                MBLog.w(TAG, "playFromMediaId: Failed to parse info string: " + e.getMessage());
              }
            }
          }
        } else {
          // Raw post object passed directly
          postInfo = postData;
        }
        
        // If we have a post object (either from extras.info or raw), convert it
        if (postInfo != null) {
          try {
            String postJson = convertReadableMapToJson(postInfo).toString();
            extras.putString("info", postJson);
            extras.putString("infoJson", postJson);
            
            // Also extract key fields for MediaSession metadata
            if (postInfo.hasKey("title")) {
              extras.putString("title", postInfo.getString("title"));
            }
            
            // Extract author/subtitle
            if (postInfo.hasKey("authors") && postInfo.getType("authors") == ReadableType.Array) {
              ReadableArray authors = postInfo.getArray("authors");
              if (authors != null && authors.size() > 0 && authors.getType(0) == ReadableType.Map) {
                ReadableMap firstAuthor = authors.getMap(0);
                if (firstAuthor != null && firstAuthor.hasKey("name")) {
                  extras.putString("subtitle", firstAuthor.getString("name"));
                }
              }
            }
            
            // Extract image
            if (postInfo.hasKey("image")) {
              extras.putString("image", postInfo.getString("image"));
            } else if (postInfo.hasKey("seriesImage")) {
              extras.putString("image", postInfo.getString("seriesImage"));
            }
            
          } catch (Exception e) {
            MBLog.w(TAG, "playFromMediaId: Failed to create extras from postInfo: " + e.getMessage());
            extras = null;
          }
        }
      }

      if (extras != null && postData != null) {
        upsertTransientPlaybackItem(mediaId, postData, extras, postInfo);
      }
      
      // Fallback: try to get extras from MediaItemsStore if no postData provided
      if (extras == null) {
        MediaBrowserCompat.MediaItem mediaItem = MediaItemsStore.getInstance().getMediaItemById(mediaId);
        if (mediaItem != null && mediaItem.getDescription().getExtras() != null) {
          extras = mediaItem.getDescription().getExtras();
        } else {
          MBLog.w(TAG, "playFromMediaId: MediaItem not found in store for mediaId: " + mediaId);
        }
      }
      
      // CRITICAL: JWPlayer creates WebView which MUST be on UI thread
      // Run on UI thread to avoid "Calling View methods on another thread" crash
      final Bundle finalExtras = extras;
      UiThreadUtil.runOnUiThread(new Runnable() {
        @Override
        public void run() {
          try {
            // Trigger the MediaBrowserService callback onPlayFromMediaId
            // This will delegate to RNJWMediaSessionHelper.handlePlayFromMediaId with proper extras
            Class<?> helperClass = Class.forName("com.jwplayer.rnjwplayer.session.RNJWMediaSessionHelper");
            java.lang.reflect.Method playFromMediaIdMethod = helperClass.getMethod("handlePlayFromMediaId", String.class, Bundle.class);
            Boolean result = (Boolean) playFromMediaIdMethod.invoke(null, mediaId, finalExtras);
            
            if (result != null && result) {
              promise.resolve("Playback started for mediaId: " + mediaId);
            } else {
              MBLog.w(TAG, "playFromMediaId returned false for mediaId: " + mediaId);
              promise.reject("ERR_PLAYBACK_FAILED", "Failed to start playback for mediaId: " + mediaId);
            }
          } catch (Exception e) {
            MBLog.e(TAG, "Failed to call RNJWMediaSessionHelper.handlePlayFromMediaId: " + e.getMessage());
            promise.reject("ERR_PLAYBACK_EXCEPTION", "Exception during playback: " + e.getMessage(), e);
          }
        }
      });
    } catch (Exception e) {
      MBLog.e(TAG, "Error in playFromMediaId: " + e.getMessage());
      promise.reject("ERR_PLAY_FROM_MEDIA_ID", e.getMessage(), e);
    }
  }

  private void upsertTransientPlaybackItem(String mediaId, ReadableMap postData, Bundle extras, ReadableMap postInfo) {
    try {
      if (mediaId == null || mediaId.trim().isEmpty()) {
        return;
      }

      Bundle itemExtras = extras != null ? new Bundle(extras) : new Bundle();
      String title = firstNonEmptyString(
        getReadableString(postData, "title"),
        getReadableString(postInfo, "title"),
        itemExtras.getString("title")
      );
      String subtitle = firstNonEmptyString(
        getReadableString(postData, "subTitle"),
        getReadableString(postData, "subtitle"),
        getReadableString(postInfo, "subTitle"),
        getReadableString(postInfo, "subtitle"),
        itemExtras.getString("subtitle")
      );
      String icon = firstNonEmptyString(
        getReadableString(postData, "icon"),
        getReadableString(postData, "image"),
        getReadableString(postData, "seriesImage"),
        getReadableString(postInfo, "icon"),
        getReadableString(postInfo, "image"),
        getReadableString(postInfo, "seriesImage"),
        itemExtras.getString("image")
      );

      MediaDescriptionCompat.Builder description = new MediaDescriptionCompat.Builder()
        .setMediaId(mediaId)
        .setExtras(itemExtras);

      if (title != null) {
        description.setTitle(title);
      }
      if (subtitle != null) {
        description.setSubtitle(subtitle);
      }
      if (icon != null) {
        try {
          description.setIconUri(Uri.parse(icon));
        } catch (Exception ignored) {}
      }

      boolean existed = MediaItemsStore.getInstance().getMediaItemById(mediaId) != null;
      MediaBrowserCompat.MediaItem transientItem = new MediaBrowserCompat.MediaItem(
        description.build(),
        MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
      );
      MediaItemsStore.getInstance().upsertTransientMediaItem(transientItem);
      MBLog.d(TAG, "LOG_CAN_BE_REMOVED_ON_RELEASE playFromMediaId-transient-upsert mediaId="
        + mediaId
        + ", title=" + title
        + ", existed=" + existed
        + ", hasInfo=" + itemExtras.containsKey("info"));
    } catch (Exception e) {
      MBLog.w(TAG, "LOG_CAN_BE_REMOVED_ON_RELEASE playFromMediaId-transient-upsert-failed mediaId="
        + mediaId
        + ", error=" + e.getMessage());
    }
  }

  private String getReadableString(ReadableMap map, String key) {
    if (map == null || key == null || !map.hasKey(key) || map.getType(key) != ReadableType.String) {
      return null;
    }

    String value = map.getString(key);
    return value != null && !value.trim().isEmpty() ? value : null;
  }

  private String firstNonEmptyString(String... values) {
    if (values == null) {
      return null;
    }

    for (String value : values) {
      if (value != null && !value.trim().isEmpty()) {
        return value;
      }
    }

    return null;
  }

  /**
   * Syncs the playback speed in the Android Auto MediaSession so the custom action button
   * shows the correct icon and label when speed is changed from within the app.
   */
  @ReactMethod
  public void setPlaybackSpeed(double speed) {
    MBLog.v(TAG, "→ setPlaybackSpeed(speed=" + speed + ")");
    try {
      MediaBrowserService service = MediaBrowserService.getInstance();
      if (service != null) {
        service.setPlaybackSpeed((float) speed);
      }
    } catch (Exception e) {
      MBLog.w(NAME, "setPlaybackSpeed failed: " + e.getMessage());
    }
  }

  /**
   * Returns the current playback speed from the Android Auto MediaSession.
   * Used by the app to sync its speed UI after a headless session.
   */
  @ReactMethod
  public void getPlaybackSpeed(Promise promise) {
    MBLog.v(TAG, "→ getPlaybackSpeed()");
    try {
      MediaBrowserService service = MediaBrowserService.getInstance();
      if (service != null) {
        promise.resolve((double) service.getPlaybackSpeed());
      } else {
        promise.resolve(1.0);
      }
    } catch (Exception e) {
      MBLog.w(NAME, "getPlaybackSpeed failed: " + e.getMessage());
      promise.resolve(1.0);
    }
  }

  /**
   * Acknowledge that a skip event (identified by skipToken) was successfully handled by JS.
   * This prevents the 300 ms native fallback in RNJWMediaSessionHelper from double-firing.
   * Safe to call with an expired or unknown token — it is treated as a no-op.
   */
  @ReactMethod
  public void acknowledgeSkip(String skipToken, Promise promise) {
    MBLog.v(TAG, "→ acknowledgeSkip(skipToken=" + skipToken + ")");
    try {
      MediaBrowserService.acknowledgeSkip(skipToken);
      promise.resolve(null);
    } catch (Exception e) {
      MBLog.w(NAME, "acknowledgeSkip failed: " + e.getMessage());
      promise.resolve(null); // Non-fatal — never reject so JS callers don't need catch
    }
  }
}
