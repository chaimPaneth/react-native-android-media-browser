package com.mediabrowser;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.support.v4.media.MediaDescriptionCompat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CenterCrop;
import com.facebook.common.references.CloseableReference;
import com.facebook.datasource.DataSource;
import com.facebook.datasource.DataSources;
import com.facebook.drawee.backends.pipeline.Fresco;
import com.facebook.imagepipeline.image.CloseableBitmap;
import com.facebook.imagepipeline.image.CloseableImage;
import com.facebook.imagepipeline.request.ImageRequest;
import com.facebook.imagepipeline.request.ImageRequestBuilder;

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

  public static void setIconBitmapFromFresco(
    Context context,
    MediaDescriptionCompat.Builder descriptionBuilder,
    Uri uri) {
    // 1. Build an ImageRequest
    ImageRequest imageRequest = ImageRequestBuilder
      .newBuilderWithSource(uri)
      .build();

    // 2. Fetch decoded image using Fresco
    DataSource<CloseableReference<CloseableImage>> dataSource =
      Fresco.getImagePipeline().fetchDecodedImage(imageRequest, context);

    try {
      // 3. Block until the final result is ready
      CloseableReference<CloseableImage> resultRef = DataSources.waitForFinalResult(dataSource);

      if (resultRef != null && resultRef.get() instanceof CloseableBitmap) {
        // 4. Extract Bitmap from Fresco's CloseableBitmap and scale
//        Bitmap bitmap = ((CloseableBitmap) resultRef.get()).getUnderlyingBitmap();
//        Bitmap cropped = centerCrop(bitmap, desiredSize);
//        Bitmap scaled = Bitmap.createScaledBitmap(cropped, desiredSize, desiredSize, true);

        Bitmap bmp = Glide.with(context)
          .asBitmap()
          .load(uri)
          .transform(new CenterCrop())  // or FitCenter, CircleCrop, etc.
          .submit()
          .get(DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // 5. Set the icon bitmap on your MediaDescriptionCompat
        descriptionBuilder.setIconBitmap(bmp);
      }
    } catch (Throwable e) {
      e.printStackTrace();
    } finally {
      // 7. Always close the data source
      dataSource.close();
    }
  }

  private static Bitmap centerCrop(Bitmap src, int targetSize) {
    // We'll make it square: targetSize x targetSize
    float srcWidth = src.getWidth();
    float srcHeight = src.getHeight();

    float srcAspect = srcWidth / srcHeight;
    float dstAspect = 1.0f; // square

    if (srcAspect > dstAspect) {
      // Source is wider than tall => crop left/right
      int newWidth = (int) (srcHeight * dstAspect);
      int offsetX = (int) ((srcWidth - newWidth) / 2);
      return Bitmap.createBitmap(src, offsetX, 0, newWidth, (int) srcHeight);
    } else if (srcAspect < dstAspect) {
      // Source is taller than wide => crop top/bottom
      int newHeight = (int) (srcWidth / dstAspect);
      int offsetY = (int) ((srcHeight - newHeight) / 2);
      return Bitmap.createBitmap(src, 0, offsetY, (int) srcWidth, newHeight);
    }
    // Already square
    return src;
  }
}

