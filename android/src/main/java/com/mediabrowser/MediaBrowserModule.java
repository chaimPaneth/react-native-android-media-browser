package com.mediabrowser;

import static android.support.v4.media.MediaDescriptionCompat.EXTRA_DOWNLOAD_STATUS;

import static androidx.media.utils.MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_PERCENTAGE;
import static androidx.media.utils.MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS;
import static androidx.media.utils.MediaConstants.METADATA_KEY_IS_EXPLICIT;

import static com.mediabrowser.MediaBrowserUtils.convertReadableMapToJson;

import android.app.Activity;
import android.content.ContentResolver;
import android.media.MediaDescription;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.util.Log;

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
import com.facebook.react.bridge.UiThreadUtil;
import com.facebook.react.bridge.WritableNativeMap;
import com.facebook.react.module.annotations.ReactModule;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;

@ReactModule(name = MediaBrowserModule.NAME)
public class MediaBrowserModule extends ReactContextBaseJavaModule {
  public static final String NAME = "MediaBrowser";

  private boolean isReactNativeReady = false;

  private CarConnection carConnection;

  private ReactApplicationContext reactContext;

  public MediaBrowserModule(ReactApplicationContext reactContext) {
    super(reactContext);
    this.reactContext = reactContext;
    MediaItemsStore.getInstance().setReactApplicationContext(reactContext);

    initializeCarConnection();
  }

  @Override
  @NonNull
  public String getName() {
    return NAME;
  }

  private static final String TAG = "MediaBrowserModule";

  @Override
  public void initialize() {
    super.initialize();

    // React Native is ready, we can register the receiver
    isReactNativeReady = true;

    if (carConnection == null) {
      initializeCarConnection();
    }
  }

  private void initializeCarConnection() {
    UiThreadUtil.runOnUiThread(new Runnable() {
      @Override
      public void run() {
        if (isReactNativeReady && carConnection == null) {
          carConnection = new CarConnection(reactContext);
          carConnection.getType().observe((LifecycleOwner) reactContext.getCurrentActivity(), new Observer<Integer>() {
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
  public void setMediaItems(ReadableMap itemsMap, Promise promise) {
    try {
      Map<String, List<MediaBrowserCompat.MediaItem>> hierarchy = buildMediaItemsHierarchy(itemsMap);
      MediaItemsStore.getInstance().setRootId(itemsMap.getString("id"));
      MediaItemsStore.getInstance().setMediaItemsHierarchy(hierarchy);
      promise.resolve(null);
    } catch (Exception e) {
      promise.reject("ERROR", e);
    }
  }

  @ReactMethod
  public void pushMediaItem(String parentId, ReadableMap itemMap, Promise promise) {
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
    try {
      MediaItemsStore.getInstance().deleteMediaItem(itemId);
      promise.resolve(null);
    } catch (Exception e) {
      promise.reject("ERROR", e);
    }
  }

  @ReactMethod
  public void updateMediaItem(ReadableMap item, Promise promise) {
    try {
      if (item.hasKey("id")) {
        String mediaId = item.getString("id");
        MediaBrowserCompat.MediaItem existingMediaItem = MediaItemsStore.getInstance().getMediaItemById(mediaId);
        if (existingMediaItem != null) {
          MediaDescriptionCompat oldDescription = existingMediaItem.getDescription();

          MediaDescriptionCompat.Builder descriptionBuilder = new MediaDescriptionCompat.Builder();
          descriptionBuilder.setMediaId(oldDescription.getMediaId())
            .setTitle(oldDescription.getTitle())
            .setSubtitle(oldDescription.getSubtitle())
            .setDescription(oldDescription.getDescription())
            .setIconUri(oldDescription.getIconUri())
            .setExtras(oldDescription.getExtras());

          if (item.hasKey("title")) {
            descriptionBuilder.setTitle(item.getString("title"));
          }
          if (item.hasKey("subTitle")) {
            descriptionBuilder.setSubtitle(item.getString("subTitle"));
          }
          if (item.hasKey("description")) {
            descriptionBuilder.setDescription(item.getString("description"));
          }
          if (item.hasKey("iconUri")) {
            descriptionBuilder.setIconUri(Uri.parse(item.getString("iconUri")));
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
            descriptionBuilder.setExtras(extras);
          }

          MediaDescriptionCompat newDescription = descriptionBuilder.build();
          MediaBrowserCompat.MediaItem updatedItem = new MediaBrowserCompat.MediaItem(newDescription, existingMediaItem.getFlags());
          MediaItemsStore.getInstance().updateMediaItem(updatedItem);
          promise.resolve("Media item with id " + mediaId + " updated successfully.");
        } else {
          Log.e(TAG, "Media item with id " + mediaId + " not found.");
          promise.reject("ERR_ITEM_NOT_FOUND", "Media item with id " + mediaId + " not found.");
        }
      } else {
        Log.e(TAG, "Required key id was not provided.");
        promise.reject("ERR_REQUIRED_KEY_NOT_PROVIDED", "Required key id was not provided.");
      }
    } catch (Exception e) {
      e.printStackTrace();
      promise.reject("ERR_UPDATE_MEDIA_ITEM", e.getMessage(), e);
    }
  }

  @ReactMethod
  public void updateMediaItems(String parentId, ReadableArray updatedItemsArray, boolean replace, Promise promise) {
    Log.d(TAG, "updateMediaItems called: " + updatedItemsArray);

    if (updatedItemsArray == null) {
      Log.e(TAG, "updateMediaItems called with null updatedItems");
      promise.reject("ERR_NULL_UPDATED_ITEMS", "updatedItemsArray was null");
      return;
    }

    try {
      handleItemsArray(parentId, updatedItemsArray, replace);
      promise.resolve("Success");
    } catch (Exception e) {
      e.printStackTrace();
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
        Log.e(TAG, "Error parsing JSON at index " + i + " in the items array", e);
      }
    }

    MediaItemsStore.getInstance().updateMediaItems(parentId, updatedItems, replace);
  }

  private void sendCarConnectionToJS(Integer carState) {
    if (isReactNativeReady) {
      getReactApplicationContext()
        .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
        .emit("onCarConnectionChanged", carState);
    }
  }

  private Map<String, List<MediaBrowserCompat.MediaItem>> buildMediaItemsHierarchy(ReadableMap itemsMap) throws Exception {
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
    String mediaId = itemMap.getString("id");
    MediaDescriptionCompat.Builder description = new MediaDescriptionCompat.Builder()
      .setMediaId(mediaId);

    if (itemMap.hasKey("title")) {
      description.setTitle(itemMap.getString("title"));
    }
    if (itemMap.hasKey("subTitle")) {
      description.setSubtitle(itemMap.getString("subTitle"));
    }

    if (itemMap.hasKey("icon")) {
      Uri iconUri = Uri.parse(itemMap.getString("icon"));
      if ("res".equals(iconUri.getScheme())) {
        int iconResId = getReactApplicationContext().getResources().getIdentifier(iconUri.getHost() + ":" + iconUri.getPath(), "drawable", getReactApplicationContext().getPackageName());
        description.setIconUri(Uri.parse("android.resource://" + getReactApplicationContext().getPackageName() + "/" + iconResId));
      } else {
//        Uri contentUri = asAlbumArtContentURI(iconUri);
        description.setIconUri(iconUri);
      }
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

  public static Uri asAlbumArtContentURI(Uri webUri) {
    return new Uri.Builder()
      .scheme(ContentResolver.SCHEME_CONTENT)
      .authority(MediaArtworkContentProvider.CONTENT_PROVIDER_AUTHORITY)
      .appendPath(webUri.getPath()) // Make sure you trust the URI!
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
}
