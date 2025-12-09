package com.mediabrowser;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.media.MediaMetadata;
import androidx.media.MediaBrowserServiceCompat;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

public class MediaBrowserService extends MediaBrowserServiceCompat implements MediaItemsStore.MediaItemsUpdateListener {
  private static final String MEDIA_ROOT_ID = "ROOT";
  private static final String TAG = "MediaBrowserService";
  private static final String CHANNEL_ID = "MediaPlaybackChannel";
  private static final int NOTIFICATION_ID = 2005;

  MediaSessionCompat mSession;

  @Override
  public void onCreate() {
    super.onCreate();

    // Check if this is a fresh start after force-stop
    boolean isColdStart = MediaItemsStore.getInstance().getReactApplicationContext() == null;

    if (isColdStart) {
        MediaItemsStore.getInstance().clearAllData();
    }

    MediaItemsStore.getInstance().setListener(this);

    mSession = MediaSessionSingleton.getInstance(this);
    
    // Set a callback immediately to handle early Android Auto connections
    // This callback will delegate to RNJWMediaSessionHelper when available
    mSession.setCallback(new MediaSessionCompat.Callback() {
        @Override
        public void onPlayFromMediaId(String mediaId, android.os.Bundle extras) {
            Log.d(TAG, "MediaBrowserService received onPlayFromMediaId: " + mediaId);
            
            // Try to delegate to RNJWMediaSessionHelper if available
            boolean delegated = delegateToRNJWMediaSessionHelper("onPlayFromMediaId", mediaId, extras);
            
            if (!delegated) {
                // Fallback: handle directly in MediaBrowserService
                Log.d(TAG, "RNJWMediaSessionHelper not available, handling directly");
                sendMediaItemToReactNative(mediaId);
                
                // Try to delegate to JWPlayerNativePlaybackHandler for actual playback
                try {
                    Class<?> handlerClass = Class.forName("com.jwplayer.rnjwplayer.JWPlayerNativePlaybackHandler");
                    java.lang.reflect.Method getInstanceMethod = handlerClass.getMethod("getInstance", Context.class);
                    Object handlerInstance = getInstanceMethod.invoke(null, MediaBrowserService.this);
                    
                    if (handlerInstance != null) {
                        // Get proper title/subtitle/icon from MediaItem instead of extras
                        MediaBrowserCompat.MediaItem mediaItem = MediaItemsStore.getInstance().getMediaItemById(mediaId);
                        String title = "Unknown Title";
                        String subtitle = "";
                        String icon = "";
                        
                        if (mediaItem != null) {
                            if (mediaItem.getDescription().getTitle() != null) {
                                title = mediaItem.getDescription().getTitle().toString();
                            }
                            if (mediaItem.getDescription().getSubtitle() != null) {
                                subtitle = mediaItem.getDescription().getSubtitle().toString();
                            }
                            if (mediaItem.getDescription().getIconUri() != null) {
                                icon = mediaItem.getDescription().getIconUri().toString();
                            }
                        }
                        
                        java.util.Map<String, Object> extrasMap = new java.util.HashMap<>();
                        if (extras != null) {
                            for (String key : extras.keySet()) {
                                Object value = extras.get(key);
                                if (value != null) {
                                    extrasMap.put(key, value);
                                }
                            }
                        }
                        
                        // Also add MediaItem extras if available
                        if (mediaItem != null && mediaItem.getDescription().getExtras() != null) {
                            Bundle itemExtras = mediaItem.getDescription().getExtras();
                            for (String key : itemExtras.keySet()) {
                                Object value = itemExtras.get(key);
                                if (value != null && !extrasMap.containsKey(key)) {
                                    extrasMap.put(key, value);
                                }
                            }
                        }
                        
                        java.lang.reflect.Method handleMethod = handlerClass.getMethod("handleHeadlessMediaSelection", 
                            String.class, String.class, String.class, String.class, java.util.Map.class);
                        handleMethod.invoke(handlerInstance, mediaId, title, subtitle, icon, extrasMap);
                        
                        Log.d(TAG, "MediaBrowserService fallback: Called JWPlayerNativePlaybackHandler for: " + title);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Could not call JWPlayerNativePlaybackHandler fallback: " + e.getMessage());
                }
            }
        }
        
        @Override
        public void onPlay() {
            Log.d(TAG, "MediaBrowserService received onPlay");
            delegateToRNJWMediaSessionHelper("onPlay", null, null);
        }
        
        @Override
        public void onPause() {
            Log.d(TAG, "MediaBrowserService received onPause");
            delegateToRNJWMediaSessionHelper("onPause", null, null);
        }
        
        @Override
        public void onStop() {
            Log.d(TAG, "MediaBrowserService received onStop");
            boolean delegated = delegateToRNJWMediaSessionHelper("onStop", null, null);
            
            // If delegation failed, handle stop directly
            if (!delegated) {
                // Stop background player if it exists
                try {
                    Class<?> handlerClass = Class.forName("com.jwplayer.rnjwplayer.JWPlayerNativePlaybackHandler");
                    java.lang.reflect.Method getInstanceMethod = handlerClass.getMethod("getInstance", Context.class);
                    Object handlerInstance = getInstanceMethod.invoke(null, MediaBrowserService.this);
                    
                    if (handlerInstance != null) {
                        java.lang.reflect.Method stopMethod = handlerClass.getMethod("stopAndCleanup");
                        stopMethod.invoke(handlerInstance);
                        Log.d(TAG, "Stopped background player via onStop");
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Could not stop background player via onStop: " + e.getMessage());
                }
                
                // Update MediaSession state
                if (mSession != null) {
                    mSession.setPlaybackState(new PlaybackStateCompat.Builder()
                        .setState(PlaybackStateCompat.STATE_STOPPED, 0, 1.0f)
                        .setActions(PlaybackStateCompat.ACTION_PLAY | 
                                   PlaybackStateCompat.ACTION_PAUSE | 
                                   PlaybackStateCompat.ACTION_STOP |
                                   PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                                   PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                                   PlaybackStateCompat.ACTION_SEEK_TO)
                        .build());
                }
                
                // Stop foreground service
                stopForeground(true);
            }
        }
        
        @Override
        public void onSkipToNext() {
            Log.d(TAG, "MediaBrowserService received onSkipToNext");
            delegateToRNJWMediaSessionHelper("onSkipToNext", null, null);
        }
        
        @Override
        public void onSkipToPrevious() {
            Log.d(TAG, "MediaBrowserService received onSkipToPrevious");
            delegateToRNJWMediaSessionHelper("onSkipToPrevious", null, null);
        }
        
        @Override
        public void onSeekTo(long pos) {
            Log.d(TAG, "MediaBrowserService received onSeekTo: " + pos);
            delegateToRNJWMediaSessionHelper("onSeekTo", String.valueOf(pos), null);
        }
    });
    
    // Set initial playback state
    mSession.setPlaybackState(new PlaybackStateCompat.Builder()
      .setState(PlaybackStateCompat.STATE_NONE, 0, 1.0f)
      .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE | PlaybackStateCompat.ACTION_STOP | 
                 PlaybackStateCompat.ACTION_SEEK_TO | PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
      .build());
    
    // Activate the session
    mSession.setActive(true);
    
    // Create notification channel for media playback
    createNotificationChannel();

    // Start as foreground service immediately to allow launching Activity on Android 12+
    // This is critical after force-stop when React context isn't available yet
    try {
      Intent activityIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
      PendingIntent pendingIntent = null;
      if (activityIntent != null) {
        activityIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        pendingIntent = PendingIntent.getActivity(this, 0, activityIntent,
          PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
      }

      String appName = getApplicationInfo().loadLabel(getPackageManager()).toString();
      
      NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(appName)
        .setContentText("Android Auto is ready")
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setStyle(new MediaStyle().setMediaSession(mSession.getSessionToken()))
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true);

      if (pendingIntent != null) {
        builder.setContentIntent(pendingIntent);
      }

      Notification notification = builder.build();

      // Android 14+ (API 34+) requires specifying the foreground service type
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
      } else {
        startForeground(NOTIFICATION_ID, notification);
      }

      // CRITICAL: After force-stop, React Native MUST be initialized WITHOUT launching MainActivity
      // Android 12+ blocks background activity launches, so we initialize React directly
      // This is how YouTube Music works - no Activity launch, just React context creation
      boolean hasReactContext = MediaItemsStore.getInstance().getReactApplicationContext() != null;

      if (!hasReactContext) {
        try {
          // Initialize React Native without launching Activity (YouTube Music approach)
          // Get the Application which implements ReactApplication
          android.app.Application app = getApplication();

          // Access ReactNativeHost via reflection to avoid compile-time dependency
          java.lang.reflect.Method getReactNativeHostMethod = app.getClass().getMethod("getReactNativeHost");
          final Object reactNativeHost = getReactNativeHostMethod.invoke(app);

          if (reactNativeHost != null) {
            // Get or create ReactInstanceManager from ReactNativeHost
            java.lang.reflect.Method getReactInstanceManagerMethod = reactNativeHost.getClass().getMethod("getReactInstanceManager");
            final Object reactInstanceManager = getReactInstanceManagerMethod.invoke(reactNativeHost);

            if (reactInstanceManager != null) {
              // Check if React context already exists or needs to be created
              java.lang.reflect.Method hasStartedMethod = reactInstanceManager.getClass().getMethod("hasStartedCreatingInitialContext");
              Boolean hasStarted = (Boolean) hasStartedMethod.invoke(reactInstanceManager);

              if (!hasStarted) {
                // Create ReactContext - this initializes React AND loads JS bundle
                java.lang.reflect.Method createReactContextMethod = reactInstanceManager.getClass().getMethod("createReactContextInBackground");
                createReactContextMethod.invoke(reactInstanceManager);
              }
            } else {
              Log.w(TAG, "[MediaBrowserService.onCreate] ReactInstanceManager was null");
            }
          } else {
            Log.w(TAG, "[MediaBrowserService.onCreate] ReactNativeHost was null");
          }
        } catch (Exception e) {
          Log.e(TAG, "[MediaBrowserService.onCreate] Failed to initialize React Native headlessly", e);
        }
      }
    } catch (Exception e) {
      Log.e(TAG, "Failed to start foreground service", e);
    }

    setSessionToken(mSession.getSessionToken());
  }
  
  /**
   * Try to delegate callback to RNJWMediaSessionHelper if it's available
   * @param action The action to delegate
   * @param param1 First parameter (mediaId for onPlayFromMediaId, position for onSeekTo)
   * @param extras Bundle extras for onPlayFromMediaId
   * @return true if delegation was successful, false if RNJWMediaSessionHelper not available
   */
  private boolean delegateToRNJWMediaSessionHelper(String action, String param1, android.os.Bundle extras) {
    try {
        Class<?> helperClass = Class.forName("com.jwplayer.rnjwplayer.session.RNJWMediaSessionHelper");
        
        switch (action) {
            case "onPlayFromMediaId":
                java.lang.reflect.Method playFromMediaIdMethod = helperClass.getMethod("handlePlayFromMediaId", String.class, android.os.Bundle.class);
                Boolean result = (Boolean) playFromMediaIdMethod.invoke(null, param1, extras);
                return result != null && result;
                
            case "onPlay":
                java.lang.reflect.Method playMethod = helperClass.getMethod("handlePlay");
                Boolean playResult = (Boolean) playMethod.invoke(null);
                return playResult != null && playResult;
                
            case "onPause":
                java.lang.reflect.Method pauseMethod = helperClass.getMethod("handlePause");
                Boolean pauseResult = (Boolean) pauseMethod.invoke(null);
                return pauseResult != null && pauseResult;
                
            case "onStop":
                java.lang.reflect.Method stopMethod = helperClass.getMethod("handleStop");
                Boolean stopResult = (Boolean) stopMethod.invoke(null);
                return stopResult != null && stopResult;
                
            case "onSkipToNext":
                java.lang.reflect.Method nextMethod = helperClass.getMethod("handleSkipToNext");
                Boolean nextResult = (Boolean) nextMethod.invoke(null);
                return nextResult != null && nextResult;
                
            case "onSkipToPrevious":
                java.lang.reflect.Method prevMethod = helperClass.getMethod("handleSkipToPrevious");
                Boolean prevResult = (Boolean) prevMethod.invoke(null);
                return prevResult != null && prevResult;
                
            case "onSeekTo":
                java.lang.reflect.Method seekMethod = helperClass.getMethod("handleSeekTo", long.class);
                Boolean seekResult = (Boolean) seekMethod.invoke(null, Long.parseLong(param1)); // param1 - position
                return seekResult != null && seekResult;
                
            default:
                return false;
        }
        
    } catch (Exception e) {
        Log.w(TAG, "Could not delegate to RNJWMediaSessionHelper: " + e.getMessage());
        return false;
    }
  }

  public static void updateSeekPosition(String mediaId, long positionMs) {
    if (mediaId == null) { return; }

    try {
      // Obtain a ReactContext the same way your service already does for other events.
      MediaItemsStore store = MediaItemsStore.getInstance();
      ReactContext reactContext = store.getReactApplicationContext();
      
      // Convert to seconds (what you want to store and emit)
      int positionSec = (int) (positionMs / 1000L);

      if (reactContext != null && reactContext.hasActiveCatalystInstance()) {
        WritableMap event = Arguments.createMap();
        event.putString("type", "media-seek");
        // event.putString("mediaId", mediaId);
        event.putString("mediaId", mediaId);
        // event.putString("mediaId", "123456"); mediaId should look numeric
        event.putInt("position", positionSec); // seconds
        reactContext
          .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
          .emit("MediaBrowserEvent", event);

        try { 
          android.content.Context ctx = (reactContext != null) ? reactContext.getApplicationContext() : null; 
          if (ctx != null) { 
            android.content.Intent intent = new android.content.Intent(ctx, MediaBrowserHeadlessService.class); 
            
            // Reuse existing action to ensure service starts 
            intent.setAction(MediaBrowserHeadlessService.ACTION_MEDIA_ITEM_SELECTED); 
            intent.putExtra("type", "media-seek"); 
            intent.putExtra("mediaId", mediaId); 
            intent.putExtra("position", positionSec);
            ctx.startService(intent); 
          } else { 
            Log.d("MediaBrowserService", "reportSeekFromNative: no Context to start headless service"); 
          } 
        } catch (Throwable t) { 
          Log.w("MediaBrowserService", "reportSeekFromNative: failed to start headless service for seek", t); 
        }
      } else {
        Log.d("MediaBrowserService", "reportSeekFromNative: ReactContext not available");
      }
    } catch (Throwable t) {
      Log.w("MediaBrowserService", "reportSeekFromNative failed", t);
    }
  }

  @Override
  public void onConfigurationChanged(Configuration newConfig) {
    super.onConfigurationChanged(newConfig);
  }
  
  @Override
  public void onDestroy() {
    super.onDestroy();
    if (mSession != null) {
      mSession.setActive(false);
    }
    stopForeground(true);
  }

  //  private MediaSession getMediaSession() {
//    // Get a reference to the MediaSessionManager.
//    MediaSessionManager mediaSessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
//
//    // Get a list of all active media sessions.
//    List<MediaSession> activeSessions = mediaSessionManager.getActiveSessions();
//
//    // Loop through the list of active sessions.
//    for (MediaSession mediaSession : activeSessions) {
//
//      // If the media session is active, then return it.
//      if (mediaSession.isActive()) {
//        return mediaSession;
//      }
//    }
//
//    // No active media session found.
//    return null;
//  }

  @Override
  public void onMediaItemsUpdated(String parentId) {
    if (parentId != null) {
      notifyChildrenChanged(parentId);
    }
  }

  /**
   * Poll for data with exponential backoff
   */
  private void pollForData(@NonNull final Result<List<MediaBrowserCompat.MediaItem>> result,
                          String parentMediaId, int attempt, long maxWaitMs, long startTime) {
    // Calculate delay: 500ms, 1s, 2s, 3s, 4s...
    long delay = attempt == 0 ? 500 : Math.min(attempt * 1000L, 4000);

    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
        try {
            ReactContext reactContext = MediaItemsStore.getInstance().getReactApplicationContext();
            List<MediaBrowserCompat.MediaItem> mediaItems = MediaItemsStore.getInstance()
                .getMediaItemsByParentId(parentMediaId);

            boolean hasData = mediaItems != null && !mediaItems.isEmpty();
            // IMPORTANT: Match onLoadChildren logic - only check if context exists
            // Don't check hasActiveCatalystInstance() - too strict during async JS bundle loading
            boolean hasContext = reactContext != null;
            long elapsed = System.currentTimeMillis() - startTime;

            if (hasData || elapsed >= maxWaitMs) {
                // Success or timeout - send whatever we have
                if (mediaItems == null) {
                    mediaItems = new ArrayList<>();
                }

                if (mediaItems.isEmpty() && !hasContext) {
                    Log.w(TAG, "[MediaBrowserService.pollForData] No data after " + elapsed + "ms - launching app as fallback");
                    try {
                        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
                        if (launchIntent != null) {
                            launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

                            // Use PendingIntent which is more reliable from a Service on Android 12+
                            PendingIntent pendingIntent = PendingIntent.getActivity(
                                this,
                                0,
                                launchIntent,
                                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                            );
                            pendingIntent.send();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "[MediaBrowserService.pollForData] Failed to launch Activity", e);
                    }
                }

                result.sendResult(mediaItems);
            } else {
                // Keep polling
                pollForData(result, parentMediaId, attempt + 1, maxWaitMs, startTime);
            }
        } catch (Exception e) {
            Log.e(TAG, "[MediaBrowserService.pollForData] Error polling for data", e);
            result.sendResult(new ArrayList<>());
        }
    }, delay);
  }

  @Override
  public BrowserRoot onGetRoot(@NonNull String clientPackageName,
                               int clientUid,
                               @Nullable Bundle rootHints) {
    String rootId = MediaItemsStore.getInstance().getRootId();
    // Always return a root, even if empty - use default ROOT if rootId is null
    // This prevents Android Auto from showing "doesn't seem to be working" error
    String finalRootId = rootId != null ? rootId : MEDIA_ROOT_ID;
    return new BrowserRoot(finalRootId, null);
  }

  @Override
  public void onLoadChildren(@NonNull final String parentMediaId,
                             @NonNull final Result<List<MediaBrowserCompat.MediaItem>> result) {
    // Check if React context is available
    ReactContext reactContext = MediaItemsStore.getInstance().getReactApplicationContext();
    // IMPORTANT: Only check if context exists, not if JS bundle is loaded
    // After force-stop, React initializes headlessly and JS bundle loads async
    // Checking hasActiveCatalystInstance() is too strict - context exists but JS still loading
    if (reactContext == null) {
        // Detach result to allow async processing
        result.detach();

        // Poll for data with progressive backoff
        long startTime = System.currentTimeMillis();
        pollForData(result, parentMediaId, 0, 15000, startTime); // Poll for up to 15 seconds

        return;
    }

    List<MediaBrowserCompat.MediaItem> mediaItems = MediaItemsStore.getInstance().getMediaItemsByParentId(parentMediaId);

    if (mediaItems == null) {
      mediaItems = new ArrayList<>();
    }

    sendBrowsableItemToJS(parentMediaId);
    result.sendResult(mediaItems);
  }

  /**
   * Public method to send media item to React Native
   * Called by RNJWMediaSessionHelper when onPlayFromMediaId is triggered
   */
  public static void sendMediaItemToReactNative(String mediaId) {
    try {
      // Get the MediaItemsStore instance
      MediaItemsStore store = MediaItemsStore.getInstance();
      MediaBrowserCompat.MediaItem mediaItem = store.getMediaItemById(mediaId);
      ReactContext reactContext = store.getReactApplicationContext();
      
      if (mediaItem != null && reactContext != null) {
        WritableMap mediaItemMap = Arguments.createMap();
        mediaItemMap.putString("id", mediaItem.getDescription().getMediaId());

        CharSequence title = mediaItem.getDescription().getTitle();
        if (title != null) {
          mediaItemMap.putString("title", title.toString());
        }

        CharSequence subtitle = mediaItem.getDescription().getSubtitle();
        if (subtitle != null) {
          mediaItemMap.putString("subTitle", subtitle.toString());
        }

        Uri iconUri = mediaItem.getDescription().getIconUri();
        if (iconUri != null) {
          mediaItemMap.putString("icon", iconUri.toString());
        }

        // Adding all extras
        Bundle extras = mediaItem.getDescription().getExtras();
        if (extras != null) {
          WritableMap extrasMap = Arguments.createMap();
          for (String key : extras.keySet()) {
            Object value = extras.get(key);
            if (value instanceof String) {
              extrasMap.putString(key, (String) value);
            } else if (value instanceof Integer) {
              extrasMap.putInt(key, (Integer) value);
            } else if (value instanceof Boolean) {
              extrasMap.putBoolean(key, (Boolean) value);
            }
          }
          mediaItemMap.putMap("extras", extrasMap);
        }

        // Add the playable flag
        mediaItemMap.putString("playableOrBrowsable", "PLAYABLE");

        // Send to React Native
        reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
          .emit("onMediaItemSelected", mediaItemMap);
          
        Log.d(TAG, "Static method: Sent onMediaItemSelected to React Native for: " + mediaId);
      }
    } catch (Exception e) {
      Log.e(TAG, "Error in static sendMediaItemToReactNative", e);
    }
  }

  private void sendMediaItemToJS(String mediaId) {
    ReactContext reactContext = MediaItemsStore.getInstance().getReactApplicationContext();
    MediaBrowserCompat.MediaItem mediaItem = MediaItemsStore.getInstance().getMediaItemById(mediaId);
    
    if (mediaItem != null) {
      WritableMap mediaItemMap = Arguments.createMap();
      mediaItemMap.putString("id", mediaItem.getDescription().getMediaId());

      CharSequence title = mediaItem.getDescription().getTitle();
      if (title != null) {
        mediaItemMap.putString("title", title.toString());
      }

      CharSequence subtitle = mediaItem.getDescription().getSubtitle();
      if (subtitle != null) {
        mediaItemMap.putString("subTitle", subtitle.toString());
      }

      Uri iconUri = mediaItem.getDescription().getIconUri();
      if (iconUri != null) {
        mediaItemMap.putString("icon", iconUri.toString());
      }

      // Adding all extras
      Bundle extras = mediaItem.getDescription().getExtras();
      if (extras != null) {
        WritableMap extrasMap = Arguments.createMap();
        for (String key : extras.keySet()) {
          Object value = extras.get(key);
          if (value instanceof String) {
            extrasMap.putString(key, (String) value);
          } else if (value instanceof Integer) {
            extrasMap.putInt(key, (Integer) value);
          } else if (value instanceof Boolean) {
            extrasMap.putBoolean(key, (Boolean) value);
          }
        }
        mediaItemMap.putMap("extras", extrasMap);
      }

      // Add the playable or browsable flag
      int flags = mediaItem.getFlags();
      if ((flags & MediaBrowserCompat.MediaItem.FLAG_PLAYABLE) != 0) {
        mediaItemMap.putString("playableOrBrowsable", "PLAYABLE");
      } else if ((flags & MediaBrowserCompat.MediaItem.FLAG_BROWSABLE) != 0) {
        mediaItemMap.putString("playableOrBrowsable", "BROWSABLE");
      }

      // Send to JS if React context is available
      if (reactContext != null) {
        reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
          .emit("onMediaItemSelected", mediaItemMap);
      }
      
      // Send to JWPlayer for headless mode handling
      try {
        java.util.Map<String, Object> extrasJavaMap = new java.util.HashMap<>();
        if (extras != null) {
          for (String key : extras.keySet()) {
            Object value = extras.get(key);
            extrasJavaMap.put(key, value);
          }
        }
        
        JWPlayerBridge.getInstance().handleMediaItemSelected(
          this, 
          mediaItem.getDescription().getMediaId(),
          title != null ? title.toString() : null,
          subtitle != null ? subtitle.toString() : null,
          iconUri != null ? iconUri.toString() : null,
          extrasJavaMap
        );
      } catch (Exception e) {
        Log.e(TAG, "Error sending to JWPlayer bridge", e);
      }
      
      // Trigger headless service for background/killed app scenario
      Intent intent = new Intent(this, MediaBrowserHeadlessService.class);
      intent.setAction(MediaBrowserHeadlessService.ACTION_MEDIA_ITEM_SELECTED);
      intent.putExtra("id", mediaItem.getDescription().getMediaId());
      if (title != null) {
        intent.putExtra("title", title.toString());
      }
      if (subtitle != null) {
        intent.putExtra("subTitle", subtitle.toString());
      }
      if (iconUri != null) {
        intent.putExtra("icon", iconUri.toString());
      }
      if ((flags & MediaBrowserCompat.MediaItem.FLAG_PLAYABLE) != 0) {
        intent.putExtra("playableOrBrowsable", "PLAYABLE");
      } else if ((flags & MediaBrowserCompat.MediaItem.FLAG_BROWSABLE) != 0) {
        intent.putExtra("playableOrBrowsable", "BROWSABLE");
      }
      
      try {
        startService(intent);
      } catch (Exception e) {
        Log.e(TAG, "Failed to start headless service", e);
      }
    }
  }

  private void sendBrowsableItemToJS(String parentMediaId) {
    ReactContext reactContext = MediaItemsStore.getInstance().getReactApplicationContext();
    WritableMap event = Arguments.createMap();
    event.putString("id", parentMediaId);
    event.putString("playableOrBrowsable", "BROWSABLE");

    // Send to JS if React context is available
    if (reactContext != null) {
        reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit("onBrowsableItemSelected", event);
    }
    
    // Send to JWPlayer for headless mode handling
    try {
      JWPlayerBridge.getInstance().handleBrowsableItemSelected(this, parentMediaId);
    } catch (Exception e) {
      Log.e(TAG, "Error sending browsable item to JWPlayer bridge", e);
    }
    
    // Trigger headless service for background/killed app scenario
    Intent intent = new Intent(this, MediaBrowserHeadlessService.class);
    intent.setAction(MediaBrowserHeadlessService.ACTION_BROWSABLE_ITEM_SELECTED);
    intent.putExtra("id", parentMediaId);
    intent.putExtra("playableOrBrowsable", "BROWSABLE");
    
    try {
      startService(intent);
    } catch (Exception e) {
      Log.e(TAG, "Failed to start headless service for browsable item", e);
    }
  }
  
  private void updateMediaSessionForPlayback(String mediaId) {
    MediaBrowserCompat.MediaItem mediaItem = MediaItemsStore.getInstance().getMediaItemById(mediaId);
    if (mediaItem != null && mSession != null) {
      try {
        // Update metadata
        MediaMetadataCompat.Builder metadataBuilder = new MediaMetadataCompat.Builder();
        
        CharSequence title = mediaItem.getDescription().getTitle();
        CharSequence subtitle = mediaItem.getDescription().getSubtitle();
        Uri iconUri = mediaItem.getDescription().getIconUri();
        
        if (title != null) {
          metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_TITLE, title.toString());
          metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title.toString());
        }
        
        if (subtitle != null) {
          metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, subtitle.toString());
          metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, subtitle.toString());
        }
        
        if (iconUri != null) {
          metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, iconUri.toString());
          metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, iconUri.toString());
        }
        
        metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, mediaId);
        
        // Try to get duration from extras if available
        Bundle extras = mediaItem.getDescription().getExtras();
        if (extras != null && extras.containsKey("info")) {
          try {
            String postJson = extras.getString("info");
            if (postJson != null) {
              // Parse JSON to get duration
              JSONObject postData = new JSONObject(postJson);
              if (postData.has("duration")) {
                double durationSeconds = postData.getDouble("duration");
                long durationMs = (long)(durationSeconds * 1000);
                metadataBuilder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs);
              }
            }
          } catch (Exception e) {
            // Ignore JSON parsing errors
          }
        }
        
        // Set metadata
        mSession.setMetadata(metadataBuilder.build());
        
        // Update playback state to playing
        mSession.setPlaybackState(new PlaybackStateCompat.Builder()
          .setState(PlaybackStateCompat.STATE_PLAYING, 0, 1.0f)
          .setActions(PlaybackStateCompat.ACTION_PLAY | 
                     PlaybackStateCompat.ACTION_PAUSE | 
                     PlaybackStateCompat.ACTION_STOP |
                     PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                     PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                     PlaybackStateCompat.ACTION_SEEK_TO)
          .build());
          
        // Show media notification
        showMediaNotification(title != null ? title.toString() : "Playing", 
                            subtitle != null ? subtitle.toString() : "");
          
        Log.d(TAG, "Updated MediaSession metadata and state for: " + title);
        
      } catch (Exception e) {
        Log.e(TAG, "Error updating MediaSession for playback", e);
      }
    }
  }
  
  private void createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      NotificationChannel channel = new NotificationChannel(
        CHANNEL_ID, 
        "Media Playback", 
        NotificationManager.IMPORTANCE_LOW
      );
      channel.setDescription("Controls for media playback");
      channel.setShowBadge(false);
      channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
      
      NotificationManager notificationManager = getSystemService(NotificationManager.class);
      if (notificationManager != null) {
        notificationManager.createNotificationChannel(channel);
      }
    }
  }
  
  private void showMediaNotification(String title, String artist) {
    try {
      // Create delete intent for when notification is dismissed
      Intent deleteIntent = new Intent(this, MediaBrowserService.class);
      deleteIntent.setAction("media_action_dismiss");
      PendingIntent deletePendingIntent = PendingIntent.getService(this, "dismiss".hashCode(), deleteIntent, 
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
      
      // Create notification with media controls
      NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(title)
        .setContentText(artist)
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setStyle(new MediaStyle()
          .setMediaSession(mSession.getSessionToken())
          .setShowActionsInCompactView(0, 1, 2))
        .addAction(android.R.drawable.ic_media_previous, "Previous", createMediaActionIntent("previous"))
        .addAction(android.R.drawable.ic_media_pause, "Pause", createMediaActionIntent("pause"))
        .addAction(android.R.drawable.ic_media_next, "Next", createMediaActionIntent("next"))
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .setDeleteIntent(deletePendingIntent); // Add delete intent
      
      Notification notification = builder.build();
      startForeground(NOTIFICATION_ID, notification);
      
    } catch (Exception e) {
      Log.e(TAG, "Error showing media notification", e);
    }
  }
  
  private PendingIntent createMediaActionIntent(String action) {
    Intent intent = new Intent(this, MediaBrowserService.class);
    intent.setAction("media_action_" + action);
    return PendingIntent.getService(this, action.hashCode(), intent, 
      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }
  
  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent != null && intent.getAction() != null) {
      String action = intent.getAction();
      
      if ("media_action_pause".equals(action)) {
        if (mSession != null && mSession.getController() != null) {
          mSession.getController().getTransportControls().pause();
        }
      } else if ("media_action_play".equals(action)) {
        if (mSession != null && mSession.getController() != null) {
          mSession.getController().getTransportControls().play();
        }
      } else if ("media_action_dismiss".equals(action)) {
        // Notification was dismissed - stop playback and cleanup
        Log.d(TAG, "Notification dismissed - stopping playback and cleanup");
        
        // Stop playback via MediaSession
        if (mSession != null && mSession.getController() != null) {
          mSession.getController().getTransportControls().stop();
        }
        
        // Stop background player if it exists
        try {
          Class<?> handlerClass = Class.forName("com.jwplayer.rnjwplayer.JWPlayerNativePlaybackHandler");
          java.lang.reflect.Method getInstanceMethod = handlerClass.getMethod("getInstance", Context.class);
          Object handlerInstance = getInstanceMethod.invoke(null, this);
          
          if (handlerInstance != null) {
            java.lang.reflect.Method stopMethod = handlerClass.getMethod("stopAndCleanup");
            stopMethod.invoke(handlerInstance);
            Log.d(TAG, "Stopped background player due to notification dismissal");
          }
        } catch (Exception e) {
          Log.w(TAG, "Could not stop background player: " + e.getMessage());
        }
        
        // Update MediaSession state to stopped
        if (mSession != null) {
          mSession.setPlaybackState(new PlaybackStateCompat.Builder()
            .setState(PlaybackStateCompat.STATE_STOPPED, 0, 1.0f)
            .setActions(PlaybackStateCompat.ACTION_PLAY | 
                       PlaybackStateCompat.ACTION_PAUSE | 
                       PlaybackStateCompat.ACTION_STOP |
                       PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                       PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                       PlaybackStateCompat.ACTION_SEEK_TO)
            .build());
        }
        
        // Stop foreground service
        stopForeground(true);
      }
    }
    
    return super.onStartCommand(intent, flags, startId);
  }
}
