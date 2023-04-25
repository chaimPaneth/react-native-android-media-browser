package com.mediabrowser;

import android.net.Uri;
import android.os.Bundle;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;

import androidx.annotation.NonNull;
import androidx.media.utils.MediaConstants;

import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.module.annotations.ReactModule;
import com.mediabrowser.MediaItemsStore;

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

  public MediaBrowserModule(ReactApplicationContext reactContext) {
    super(reactContext);
  }

  @Override
  @NonNull
  public String getName() {
    return NAME;
  }

  @ReactMethod
  public void setMediaItems(String itemsJson) {
    try {
      JSONObject itemsObject = new JSONObject(itemsJson);
      Map<String, List<MediaBrowserCompat.MediaItem>> hierarchy = buildMediaItemsHierarchy(itemsObject);
      MediaItemsStore.getInstance().setMediaItemsHierarchy(hierarchy);
      MediaItemsStore.getInstance().setRootId(itemsObject.getString("id"));
    } catch (JSONException e) {
      e.printStackTrace();
    }
  }

  @ReactMethod
  public void pushMediaItem(String parentId, String itemJson) {
    try {
      JSONObject itemObject = new JSONObject(itemJson);
      MediaBrowserCompat.MediaItem newItem = createMediaItem(itemObject);
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
      MediaBrowserCompat.MediaItem updatedItem = createMediaItem(updatedItemObject);
      MediaItemsStore.getInstance().updateMediaItem(updatedItem);
    } catch (JSONException e) {
      e.printStackTrace();
    }
  }

  private Map<String, List<MediaBrowserCompat.MediaItem>> buildMediaItemsHierarchy(JSONObject itemsObject) throws JSONException {
    Map<String, List<MediaBrowserCompat.MediaItem>> hierarchy = new HashMap<>();

    String rootId = itemsObject.getString("id");
    JSONArray rootItems = itemsObject.getJSONArray("root");

    for (int i = 0; i < rootItems.length(); i++) {
      JSONObject item = rootItems.getJSONObject(i);
      addMediaItemToHierarchy(item, hierarchy);
    }

    return hierarchy;
  }

  private void addMediaItemToHierarchy(JSONObject item, Map<String, List<MediaBrowserCompat.MediaItem>> hierarchy) throws JSONException {
    String parentId = item.getString("id");

    JSONArray children = item.optJSONArray("children");
    if (children != null) {
      List<MediaBrowserCompat.MediaItem> mediaItems = new ArrayList<>();
      for (int i = 0; i < children.length(); i++) {
        JSONObject childItem = children.getJSONObject(i);
        MediaBrowserCompat.MediaItem mediaItem = createMediaItem(childItem);
        mediaItems.add(mediaItem);

        addMediaItemToHierarchy(childItem, hierarchy);
      }
      hierarchy.put(parentId, mediaItems);
    }
  }

  private MediaBrowserCompat.MediaItem createMediaItem(JSONObject itemObject) throws JSONException {
    String mediaId = itemObject.getString("id");
    MediaDescriptionCompat.Builder description = new MediaDescriptionCompat.Builder()
      .setMediaId(mediaId);

    if (itemObject.has("title")) {
      description.setTitle(itemObject.getString("title"));
    }
    if (itemObject.has("subTitle")) {
      description.setSubtitle(itemObject.getString("subTitle"));
    }
    if (itemObject.has("icon")) {
      description.setIconUri(Uri.parse(itemObject.getString("icon")));
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

    description.setExtras(extras);

    int flags = itemObject.getString("playableOrBrowsable").equals("PLAYABLE")
      ? MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
      : MediaBrowserCompat.MediaItem.FLAG_BROWSABLE;

    return new MediaBrowserCompat.MediaItem(description.build(), flags);
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
