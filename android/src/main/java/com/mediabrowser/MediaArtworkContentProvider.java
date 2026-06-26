package com.mediabrowser;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import com.bumptech.glide.Glide;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class MediaArtworkContentProvider extends ContentProvider {
  public static String CONTENT_PROVIDER_AUTHORITY = "com.mediabrowser.provider";
  private static final int DOWNLOAD_TIMEOUT_SECONDS = 30;

  private static final Map<Uri, Uri> uriMap = new HashMap<>();

  /**
   * Provider authority for the current app. MUST match the authority declared in
   * this library's AndroidManifest.xml:
   *   android:authorities="${applicationId}.mediabrowser.provider"
   * getPackageName() returns the app's applicationId at runtime, so the two always
   * agree and the authority is unique per app.
   */
  public static String getAuthority(Context context) {
    return context.getPackageName() + ".mediabrowser.provider";
  }

  public static android.net.Uri asAlbumArtContentURI(android.content.Context context, android.net.Uri webUri) {
    return new android.net.Uri.Builder()
      .scheme(android.content.ContentResolver.SCHEME_CONTENT)
      .authority(getAuthority(context))
      .appendQueryParameter("url", webUri.toString())
      .build();
  }

  public static Uri mapUri(Uri uri) {
    String path = uri.getEncodedPath();
    if (path != null) {
      path = path.substring(1).replace('/', ':');
    } else {
      return Uri.EMPTY;
    }
    Uri contentUri = new Uri.Builder()
      .scheme(ContentResolver.SCHEME_CONTENT)
      .authority(CONTENT_PROVIDER_AUTHORITY)
      .path(path)
      .build();
    uriMap.put(contentUri, uri);
    return contentUri;
  }

  @Override
  public boolean onCreate() {
    // Match the authority declared in the manifest (${applicationId}.mediabrowser.provider).
    Context context = getContext();
    if (context != null) {
      CONTENT_PROVIDER_AUTHORITY = context.getPackageName() + ".mediabrowser.provider";
    }
    return true;
  }

  @Override
  public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
    Context context = getContext();
    if (context == null) throw new FileNotFoundException("no context");

    // Resolve the remote source URL. Prefer the ?url= param produced by
    // asAlbumArtContentURI(); fall back to the legacy mapUri() table.
    String urlParam = uri.getQueryParameter("url");
    Uri remoteUri = urlParam != null ? Uri.parse(urlParam) : uriMap.get(uri);
    if (remoteUri == null) throw new FileNotFoundException(uri.toString());

    // Security: the provider is exported so Android Auto can read item art. Only
    // allow remote http/https sources — never file://, content://, android.resource,
    // etc. — so another app cannot use this provider to read the app's private files.
    String remoteScheme = remoteUri.getScheme();
    if (!("http".equalsIgnoreCase(remoteScheme) || "https".equalsIgnoreCase(remoteScheme))) {
      throw new FileNotFoundException("Unsupported art scheme: " + remoteScheme);
    }

    // Let Glide download, cache, and de-duplicate concurrent requests, then serve
    // its cached file directly (read-only). We do NOT rename/move Glide's file:
    // renaming broke Glide's own cache and raced between Android Auto's concurrent
    // requests (it opens art from several binder threads at once), which left some
    // items — especially the first ones rendered — without artwork.
    try {
      File cacheFile = Glide.with(context.getApplicationContext())
        .asFile()
        .load(remoteUri)
        .submit()
        .get(DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      if (cacheFile != null && cacheFile.exists() && cacheFile.length() > 0) {
        return ParcelFileDescriptor.open(cacheFile, ParcelFileDescriptor.MODE_READ_ONLY);
      }
    } catch (Throwable t) {
      throw new FileNotFoundException("art load failed: " + remoteUri);
    }
    throw new FileNotFoundException(uri.toString());
  }

  @Override
  public Uri insert(Uri uri, ContentValues values) {
    return null;
  }

  @Override
  public Cursor query(Uri uri, String[] projection, String selection,
                      String[] selectionArgs, String sortOrder) {
    return null;
  }

  @Override
  public int update(Uri uri, ContentValues values, String selection,
                    String[] selectionArgs) {
    return 0;
  }

  @Override
  public int delete(Uri uri, String selection, String[] selectionArgs) {
    return 0;
  }

  @Override
  public String getType(Uri uri) {
    return null;
  }
}
