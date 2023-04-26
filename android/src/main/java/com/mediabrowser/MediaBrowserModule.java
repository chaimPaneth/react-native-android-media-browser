package com.mediabrowser;

import android.content.ContentResolver;
import android.media.MediaDescription;
import android.media.browse.MediaBrowser;
import android.net.Uri;
import android.os.Bundle;
import android.service.media.MediaBrowserService;
import android.util.Log;

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

  private static final String TAG = "MediaBrowserModule";

  @ReactMethod
  public void setMediaItems(String itemsJson) {
    Log.d(TAG, "setMediaItems called: " + itemsJson);

    try {
      JSONObject itemsObject = new JSONObject(itemsJson);
      Map<String, List<MediaBrowser.MediaItem>> hierarchy = buildMediaItemsHierarchy(itemsObject);
      Log.d(TAG, "setMediaItems hierarchy: " + hierarchy);
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
        Uri webUri = Uri.parse(itemObject.getString("icon"));
        Uri contentUri = asAlbumArtContentURI(webUri);

        description.setIconUri(contentUri);
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
