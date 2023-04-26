package com.mediabrowser;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class MediaArtworkContentProvider extends ContentProvider {
  public static final String CONTENT_PROVIDER_AUTHORITY = "com.mediabrowser.provider";
  private static final int DOWNLOAD_TIMEOUT_SECONDS = 30;

  private static final Map<Uri, Uri> uriMap = new HashMap<>();

  public static Uri mapUri(Uri uri) {
    String path = uri.getEncodedPath();
    if (path != null) {
      path = path.substring(1).replace('/', ':');
    } else {
      return Uri.EMPTY;
    }
    Uri contentUri = new Uri.Builder()
      .scheme(ContentResolver.SCHEME_CONTENT)
      .authority("com.mediabrowser.provider")
      .path(path)
      .build();
    uriMap.put(contentUri, uri);
    return contentUri;
  }

  @Override
  public boolean onCreate() {
    return true;
  }

  @Override
  public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
    if (getContext() == null) return null;
    Uri remoteUri = uriMap.get(uri);
    if (remoteUri == null) throw new FileNotFoundException(uri.getPath());

    File file = new File(getContext().getCacheDir(), uri.getPath());

    if (!file.exists()) {
      // Use Glide to download the album art.
      File cacheFile = null;
      try {
        cacheFile = Glide.with(getContext())
          .asFile()
          .load(remoteUri)
          .submit()
          .get(DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      } catch (Exception e) {
        e.printStackTrace();
      }

      // Rename the file Glide created to match our own scheme.
      if (cacheFile != null) {
        cacheFile.renameTo(file);
        file = cacheFile;
      }
    }
    return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
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

