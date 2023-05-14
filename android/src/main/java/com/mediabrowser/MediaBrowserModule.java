package com.mediabrowser;

import static android.support.v4.media.MediaDescriptionCompat.EXTRA_DOWNLOAD_STATUS;

import static androidx.media.utils.MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_PERCENTAGE;
import static androidx.media.utils.MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS;
import static androidx.media.utils.MediaConstants.METADATA_KEY_IS_EXPLICIT;

import android.content.ContentResolver;
import android.media.MediaDescription;
import android.media.browse.MediaBrowser;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.media.utils.MediaConstants;

import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.module.annotations.ReactModule;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ReactModule(name = MediaBrowserModule.NAME)
public class MediaBrowserModule extends ReactContextBaseJavaModule {
  public static final String NAME = "MediaBrowser";

  private MediaBrowserAutoConnection autoConnection;

  public MediaBrowserModule(ReactApplicationContext reactContext) {
    super(reactContext);
    MediaItemsStore.getInstance().setReactApplicationContext(reactContext);

    autoConnection = new MediaBrowserAutoConnection(reactContext);
    autoConnection.setListener(new MediaBrowserAutoConnection.OnCarConnectionStateListener() {
      @Override
      public void onCarConnected() {
        sendCarConnectionToJS(true);
      }

      @Override
      public void onCarDisconnected() {
        sendCarConnectionToJS(false);
      }
    });
  }

  @Override
  @NonNull
  public String getName() {
    return NAME;
  }

  private static final String TAG = "MediaBrowserModule";

  @ReactMethod
  public void setMediaItems(String itemsJson) {
    Log.d(TAG, "setMediaItems called: " + itemsJson);

    try {
      JSONObject itemsObject = new JSONObject(itemsJson);
      Map<String, List<MediaBrowser.MediaItem>> hierarchy = buildMediaItemsHierarchy(itemsObject);
      Log.d(TAG, "setMediaItems hierarchy: " + hierarchy);
      MediaItemsStore.getInstance().setRootId(itemsObject.getString("id"));
      MediaItemsStore.getInstance().setMediaItemsHierarchy(hierarchy);
    } catch (JSONException e) {
      e.printStackTrace();
    }
  }

  @ReactMethod
  public void pushMediaItem(String parentId, String itemJson) {
    try {
      JSONObject itemObject = new JSONObject(itemJson);
      MediaBrowser.MediaItem newItem = createMediaItem(itemObject);
      MediaItemsStore.getInstance().pushMediaItem(parentId, newItem);
    } catch (JSONException e) {
      e.printStackTrace();
    }
  }

  @ReactMethod
  public void deleteMediaItem(String itemId) {
    MediaItemsStore.getInstance().deleteMediaItem(itemId);
  }

  @ReactMethod
  public void updateMediaItem(String updatedItemJson) {
    try {
      JSONObject updatedItemObject = new JSONObject(updatedItemJson);
      MediaBrowser.MediaItem updatedItem = createMediaItem(updatedItemObject);
      MediaItemsStore.getInstance().updateMediaItem(updatedItem);
    } catch (JSONException e) {
      e.printStackTrace();
    }
  }

  @ReactMethod
  public void findMediaSession() {
    MediaItemsStore.getInstance().findMediaSession();
  }

  @ReactMethod
  public void registerCarConnectionReceiver() {
    autoConnection.registerCarConnectionReceiver();
  }

  @ReactMethod
  public void unregisterCarConnectionReceiver() {
    autoConnection.unRegisterCarConnectionReceiver();
  }

  private void sendCarConnectionToJS(boolean isCarConnected) {
    getReactApplicationContext()
      .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
      .emit("onCarConnectionChanged", isCarConnected);
  }

  private Map<String, List<MediaBrowser.MediaItem>> buildMediaItemsHierarchy(JSONObject itemsObject) throws JSONException {
    Map<String, List<MediaBrowser.MediaItem>> hierarchy = new HashMap<>();

    String rootId = itemsObject.getString("id");
    JSONArray rootItems = itemsObject.getJSONArray("root");

    for (int i = 0; i < rootItems.length(); i++) {
      JSONObject item = rootItems.getJSONObject(i);
      addMediaItemToHierarchy(item, hierarchy, rootId);
    }

    return hierarchy;
  }

  private void addMediaItemToHierarchy(JSONObject item, Map<String, List<MediaBrowser.MediaItem>> hierarchy, String parentId) throws JSONException {
    MediaBrowser.MediaItem mediaItem = createMediaItem(item);

    if (!hierarchy.containsKey(parentId)) {
      hierarchy.put(parentId, new ArrayList<>());
    }
    hierarchy.get(parentId).add(mediaItem);

    JSONArray children = item.optJSONArray("children");
    if (children != null) {
      for (int i = 0; i < children.length(); i++) {
        JSONObject childItem = children.getJSONObject(i);
        addMediaItemToHierarchy(childItem, hierarchy, item.getString("id"));
      }
    }
  }

  private MediaBrowser.MediaItem createMediaItem(JSONObject itemObject) throws JSONException {
    String mediaId = itemObject.getString("id");
    MediaDescription.Builder description = new MediaDescription.Builder()
      .setMediaId(mediaId);

    if (itemObject.has("title")) {
      description.setTitle(itemObject.getString("title"));
    }
    if (itemObject.has("subTitle")) {
      description.setSubtitle(itemObject.getString("subTitle"));
    }

    if (itemObject.has("icon")) {
      Uri iconUri = Uri.parse(itemObject.getString("icon"));
      if ("res".equals(iconUri.getScheme())) {
        int iconResId = getReactApplicationContext().getResources().getIdentifier(iconUri.getHost() + ":" + iconUri.getPath(), "drawable", getReactApplicationContext().getPackageName());
        description.setIconUri(Uri.parse("android.resource://" + getReactApplicationContext().getPackageName() + "/" + iconResId));
      } else {
//        Uri contentUri = asAlbumArtContentURI(iconUri);
        description.setIconUri(iconUri);
      }
    }

    Bundle extras = new Bundle();
    if (itemObject.has("browsableStyle")) {
      String browsableStyle = itemObject.getString("browsableStyle");
      extras.putInt(
        MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
        mapContentStyle(browsableStyle));
    }

    if (itemObject.has("playableStyle")) {
      String playableStyle = itemObject.getString("playableStyle");
      extras.putInt(
        MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
        mapContentStyle(playableStyle));
    }

    if (itemObject.has("groupTitle")) {
      String groupTitle = itemObject.getString("groupTitle");
      extras.putString(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, groupTitle);
    }

    JSONObject itemExtras;
    if (itemObject.has("extras")) {
      itemExtras = itemObject.getJSONObject("extras");
    } else {
      itemExtras = new JSONObject();
    }

    if (itemExtras.has(EXTRA_DOWNLOAD_STATUS)) {
      extras.putInt(EXTRA_DOWNLOAD_STATUS, itemExtras.getInt(EXTRA_DOWNLOAD_STATUS));
    }
    if (itemExtras.has(METADATA_KEY_IS_EXPLICIT)) {
      extras.putLong(METADATA_KEY_IS_EXPLICIT, itemExtras.getInt(METADATA_KEY_IS_EXPLICIT));
    }
    if (itemExtras.has(DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS)) {
      extras.putInt(DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS, itemExtras.getInt(DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS));
    }
    if (itemExtras.has(DESCRIPTION_EXTRAS_KEY_COMPLETION_PERCENTAGE)) {
      extras.putDouble(DESCRIPTION_EXTRAS_KEY_COMPLETION_PERCENTAGE, itemExtras.getDouble(DESCRIPTION_EXTRAS_KEY_COMPLETION_PERCENTAGE));
    }

    description.setExtras(extras);

    int flags = itemObject.getString("playableOrBrowsable").equals("PLAYABLE")
      ? MediaBrowser.MediaItem.FLAG_PLAYABLE
      : MediaBrowser.MediaItem.FLAG_BROWSABLE;

    return new MediaBrowser.MediaItem(description.build(), flags);
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
