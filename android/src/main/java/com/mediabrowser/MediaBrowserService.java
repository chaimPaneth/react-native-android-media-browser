package com.mediabrowser;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.media.MediaMetadata;
import androidx.media.MediaBrowserServiceCompat;
import androidx.media.utils.MediaConstants;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.facebook.react.common.LifecycleState;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class MediaBrowserService extends MediaBrowserServiceCompat implements MediaItemsStore.MediaItemsUpdateListener {
  private static final String MEDIA_ROOT_ID = "ROOT";
  private static final String EMPTY_RECENT_ROOT_ID = "__EMPTY_RECENT__";
  /** Browse node holding recently played items, most recent first. */
  private static final String HISTORY_PARENT_ID = "History";
  /**
   * Browse node the JS layer publishes in place of real content when no user is
   * signed in. Its presence in ROOT is the signal that the tree is unauthenticated
   * rather than merely empty. If the JS layer ever renames it, the resume path
   * degrades to the generic "nothing to resume" message rather than misbehaving.
   */
  private static final String LOGIN_NODE_ID = "Login";
  /** How long a bare resume request may wait for the browse tree before being answered. */
  private static final int RESUME_WAIT_TOTAL_MS = 12000;
  private static final int RESUME_WAIT_STEP_MS = 500;
  private static final String TAG = "MediaBrowserService";
  private static final String CHANNEL_ID = "MediaPlaybackChannel";
  /**
   * Own foreground-service notification id. Deliberately distinct from
   * RNJWNotificationHelper.DEFAULT_NOTIFICATION_ID (2005), which is owned exclusively by
   * JWPlayer's MediaStyle notification. Sharing 2005 meant this service's plain cold-start
   * notification replaced the MediaStyle one, leaving SystemUI with a notification key it
   * could not build media controls from ("No result from loadMediaData").
   */
  public static final int NOTIFICATION_ID = 2006;
  /** The id RNJWNotificationHelper posts its MediaStyle notification under. Never posted to here. */
  public static final int JWPLAYER_MEDIA_NOTIFICATION_ID = 2005;
  public static final String ACTION_JS_READY = "com.mediabrowser.ACTION_JS_READY";

  // Static instance for cross-component access (e.g., from MediaBrowserModule)
  private static MediaBrowserService sInstance;

  // Pending skip acknowledgements: skipToken → dispatch timestamp (ms).
  // RNJWMediaSessionHelper stores the token after dispatching a skip event to RN;
  // HeadlessTaskHandler calls acknowledgeSkip() after successfully handling it.
  // A 300 ms fallback in RNJWMediaSessionHelper fires native skip if the token is not cleared.
  private static final ConcurrentHashMap<String, Long> pendingSkipAcks = new ConcurrentHashMap<>();
  private static final long SKIP_ACK_TIMEOUT_MS = 300;

  // Monotonic id assigned to each playlist completion and carried on the one selected route.
  private static final AtomicLong sCompletionSeq = new AtomicLong(0);

  // Playback speed control state
  private float currentPlaybackSpeed = 1.0f;
  private static final String ACTION_CHANGE_SPEED = "com.mediabrowser.ACTION_CHANGE_SPEED";

  MediaSessionCompat mSession;
  private Set<String> browsedItems = new HashSet<>(); // Track items that have been browsed
  private static final long ROOT_KICK_INTERVAL_MS = 1000;
  private long lastRootKickMs = 0;

  // Pending async loads (event-driven). Avoid polling loops.
  private final Map<String, Result<List<MediaBrowserCompat.MediaItem>>> pendingLoadResults = new HashMap<>();
  private final Map<String, Runnable> pendingTimeouts = new HashMap<>();

  /** True while this service holds its own foreground notification (NOTIFICATION_ID). */
  private boolean holdsOwnNotification = false;

  /**
   * Whether the session currently carries a "cannot resume" error that was published in
   * answer to a resume request. The head unit keeps its error screen up for as long as
   * that state stands, so it has to be withdrawn once the reason for it goes away —
   * otherwise signing in on the phone leaves the message on screen indefinitely.
   */
  private boolean cannotResumeErrorPublished = false;

  @Override
  public void onCreate() {
    super.onCreate();

    sInstance = this;
    MBLog.v(TAG, "→ onCreate()");

    // Check if this is a fresh start after force-stop
    boolean isColdStart = MediaItemsStore.getInstance().getReactApplicationContext() == null;
    MBLog.d(TAG, "  " + (isColdStart ? "❄️ COLD START detected (no React context)" : "♻️ WARM START (React context exists)"));

    if (isColdStart) {
        MBLog.d(TAG, "  🧹 Clearing all data for cold start");
        MediaItemsStore.getInstance().clearAllData();
    }

    MediaItemsStore.getInstance().setListener(this);

    mSession = MediaSessionSingleton.getInstance(this);
    
    // Set a callback immediately to handle early Android Auto connections
    // This callback will delegate to RNJWMediaSessionHelper when available
    mSession.setCallback(new MediaSessionCompat.Callback() {
        @Override
        public void onPlayFromMediaId(String mediaId, android.os.Bundle extras) {
            MBLog.v(TAG, "→ onPlayFromMediaId(mediaId=" + mediaId + ")");
            // Try to delegate to RNJWMediaSessionHelper if available
            boolean delegated = delegateToRNJWMediaSessionHelper("onPlayFromMediaId", mediaId, extras);
            
            if (!delegated) {
                // Fallback: handle directly in MediaBrowserService
                
                // This ensures ACTION_MEDIA_ITEM_SELECTED is sent to headless service,
                // which triggers setupMediaBrowser() to register the openPlayer callback
                // RN will then call handleHeadlessMediaSelection through the proper flow
                sendMediaItemToJS(mediaId);
                
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
                    }
                } catch (Exception e) {
                    MBLog.w(TAG, "Could not call JWPlayerNativePlaybackHandler fallback: " + e.getMessage());
                }
            }
        }
        
        @Override
        public void onPlay() {
            MBLog.v(TAG, "→ onPlay()");

            boolean hasMetadata = mSession != null && mSession.getController() != null
                    && mSession.getController().getMetadata() != null;

            if (hasMetadata) {
                delegateToRNJWMediaSessionHelper("onPlay", null, null);
                return;
            }

            // No metadata: this is a bare resume request, which Android Auto issues
            // on car connect when the previous session ended while playing. It then
            // holds the player screen until playback actually starts, so returning
            // without doing anything leaves the user on an empty player until the
            // request times out. Resume the most recent item instead.
            String resumeId = findMostRecentHistoryMediaId();

            if (resumeId != null) {
                MBLog.d(TAG, "  ▶️ Bare onPlay with no metadata — resuming most recent item: " + resumeId);
                onPlayFromMediaId(resumeId, null);
                return;
            }

            // Nothing resolvable YET. On a cold start the request routinely arrives
            // before the browse tree exists, and an empty tree is indistinguishable
            // from a signed-out one, so answering now would tell a signed-in user to
            // sign in. Wait for the tree instead, then answer from real data.
            if (!MediaItemsStore.getInstance().isHierarchyReady()) {
                MBLog.d(TAG, "  ⏳ Bare onPlay before the browse tree exists — waiting before answering");
                awaitHierarchyThenAnswerResume(0);
                return;
            }

            // Tree is loaded and still has nothing to resume, so this is a real answer.
            MBLog.d(TAG, "  ⛔ Bare onPlay with nothing to resume — answering so the caller stops waiting");
            reportCannotResume();
        }

        /**
         * Re-check for something to resume until the browse tree is populated.
         *
         * A resume request is only answerable once the tree exists: before that, a
         * missing History entry means "not loaded", not "nothing played". Polling here
         * keeps the caller's player screen up for the short time the first load takes,
         * which is the same wait it already tolerates, and then either starts real
         * playback or gives a truthful refusal. Bounded so the request is always
         * answered rather than left hanging.
         */
        private void awaitHierarchyThenAnswerResume(final int elapsedMs) {
            if (MediaItemsStore.getInstance().isHierarchyReady()) {
                String readyId = findMostRecentHistoryMediaId();
                if (readyId != null) {
                    MBLog.d(TAG, "  ▶️ Browse tree arrived after " + elapsedMs
                            + "ms — resuming most recent item: " + readyId);
                    onPlayFromMediaId(readyId, null);
                } else {
                    MBLog.d(TAG, "  ⛔ Browse tree arrived after " + elapsedMs
                            + "ms with nothing to resume — answering now");
                    reportCannotResume();
                }
                return;
            }

            if (elapsedMs >= RESUME_WAIT_TOTAL_MS) {
                MBLog.d(TAG, "  ⛔ Browse tree still absent after " + elapsedMs
                        + "ms — answering so the caller stops waiting");
                reportCannotResume();
                return;
            }

            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    awaitHierarchyThenAnswerResume(elapsedMs + RESUME_WAIT_STEP_MS);
                }
            }, RESUME_WAIT_STEP_MS);
        }
        
        @Override
        public void onPause() {
            MBLog.v(TAG, "→ onPause()");
            delegateToRNJWMediaSessionHelper("onPause", null, null);
        }
        
        @Override
        public void onStop() {
            MBLog.v(TAG, "→ onStop()");
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
                    }
                } catch (Exception e) {
                    MBLog.w(TAG, "Could not stop background player via onStop: " + e.getMessage());
                }
                
                // Update MediaSession state
                if (mSession != null) {
                    mSession.setPlaybackState(new PlaybackStateCompat.Builder()
                        .setState(PlaybackStateCompat.STATE_STOPPED, 0, currentPlaybackSpeed)
                        .setActions(PlaybackStateCompat.ACTION_PLAY |
                                   PlaybackStateCompat.ACTION_PAUSE |
                                   PlaybackStateCompat.ACTION_STOP |
                                   PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                                   PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                                   PlaybackStateCompat.ACTION_SEEK_TO)
                        .addCustomAction(buildSpeedCustomAction())
                        .build());
                }
                
                stopForegroundAndSelf();
            }
        }
        
        @Override
        public void onSkipToNext() {
            MBLog.v(TAG, "→ onSkipToNext()");
            delegateToRNJWMediaSessionHelper("onSkipToNext", null, null);
        }
        
        @Override
        public void onSkipToPrevious() {
            MBLog.v(TAG, "→ onSkipToPrevious()");
            delegateToRNJWMediaSessionHelper("onSkipToPrevious", null, null);
        }
        
        @Override
        public void onSeekTo(long pos) {
            MBLog.v(TAG, "→ onSeekTo(pos=" + pos + ")");
            delegateToRNJWMediaSessionHelper("onSeekTo", String.valueOf(pos), null);
        }

        @Override
        public void onCustomAction(String action, android.os.Bundle extras) {
            MBLog.v(TAG, "→ onCustomAction(action=" + action + ")");
            if (ACTION_CHANGE_SPEED.equals(action)) {
                // Emit event to JS; the vehicle lib computes the next speed and
                // calls setPlaybackSpeed() back with the result.
                emitSpeedButtonPressedEvent();
            }
        }
    });
    
    // Set initial playback state with ZERO actions.
    // Transport controls and custom actions are added later in
    // updateMediaSessionForPlayback() when actual media is loaded.
    // Setting actions here (e.g. ACTION_PLAY) causes Android Auto to
    // think the session is playback-capable and show Now Playing immediately.
    mSession.setPlaybackState(new PlaybackStateCompat.Builder()
      .setState(PlaybackStateCompat.STATE_NONE, 0, 1.0f)
      .setActions(0L)
      .build());
    
    // Do NOT activate the session here – an active session with STATE_NONE
    // causes Android Auto to navigate directly to Now Playing (empty player).
    // The session will be activated in updateMediaSessionForPlayback() when
    // actual media metadata is available.
    // mSession.setActive(true);
    
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
      
      // NOTE: Do NOT use MediaStyle here. A MediaStyle notification signals
      // Android Auto that an active media session exists, causing it to
      // navigate directly to the (empty) Now Playing screen on launch.
      // The proper MediaStyle notification is created later in
      // showMediaNotification() once real playback starts.
      NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(appName)
        .setContentText("Android Auto is ready")
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true);

      if (pendingIntent != null) {
        builder.setContentIntent(pendingIntent);
      }

      Notification notification = builder.build();

      // Only promote when JWPlayer is NOT already running a media FGS. During playback the
      // JWPlayer MediaStyle notification is the user-visible media control surface; posting
      // a second plain ongoing notification here is noise at best and, when SystemUI's media
      // resumption probe re-creates this service every ~1.5s, a permanent replacement of the
      // controls at worst. The promotion is still required on cold start (force-stop), where it
      // is what permits a background activity launch on Android 12+.
      boolean jwPlayerActive = checkHasActivePlayer();
      MBLog.e(TAG, "onCreate: startForeground decision jwPlayerActive=" + jwPlayerActive
        + " isColdStart=" + isColdStart);

      if (!jwPlayerActive) {
        // Android 14+ (API 34+) requires specifying the foreground service type
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
          startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
          startForeground(NOTIFICATION_ID, notification);
        }
        holdsOwnNotification = true;
      } else {
        MBLog.d(TAG, "onCreate: JWPlayer active — skipping cold-start notification, "
          + "MediaStyle notification " + JWPLAYER_MEDIA_NOTIFICATION_ID + " stays owner");
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
          boolean contextCreated = false;

          // ── Attempt 1: New Architecture (RN 0.73+ Bridgeless / ReactHost) ──
          // With newArchEnabled=true the runtime is owned by ReactHost, not
          // ReactInstanceManager. ReactHost.start() is the correct API to
          // create the JS runtime headlessly.
          try {
            java.lang.reflect.Method getReactHostMethod = app.getClass().getMethod("getReactHost");
            final Object reactHost = getReactHostMethod.invoke(app);

            if (reactHost != null) {
              // ReactHost.start() creates the JS runtime if not already running.
              // It is idempotent: calling it when the host is already started is a no-op.
              java.lang.reflect.Method startMethod = reactHost.getClass().getMethod("start");
              startMethod.invoke(reactHost);
              contextCreated = true;
              MBLog.d(TAG, "  ✅ React context created via ReactHost.start() (New Architecture)");
            }
          } catch (NoSuchMethodException nsme) {
            MBLog.d(TAG, "  ℹ️ getReactHost() not available, falling back to Old Architecture path");
          } catch (Exception newArchEx) {
            MBLog.w(TAG, "  ⚠️ ReactHost.start() failed, falling back to Old Architecture path: " + newArchEx.getMessage());
          }

          // ── Attempt 2: Old Architecture (ReactInstanceManager) ──
          if (!contextCreated) {
            try {
              java.lang.reflect.Method getReactNativeHostMethod = app.getClass().getMethod("getReactNativeHost");
              final Object reactNativeHost = getReactNativeHostMethod.invoke(app);

              if (reactNativeHost != null) {
                java.lang.reflect.Method getReactInstanceManagerMethod = reactNativeHost.getClass().getMethod("getReactInstanceManager");
                final Object reactInstanceManager = getReactInstanceManagerMethod.invoke(reactNativeHost);

                if (reactInstanceManager != null) {
                  java.lang.reflect.Method hasStartedMethod = reactInstanceManager.getClass().getMethod("hasStartedCreatingInitialContext");
                  Boolean hasStarted = (Boolean) hasStartedMethod.invoke(reactInstanceManager);

                  if (!hasStarted) {
                    java.lang.reflect.Method createReactContextMethod = reactInstanceManager.getClass().getMethod("createReactContextInBackground");
                    createReactContextMethod.invoke(reactInstanceManager);
                    MBLog.d(TAG, "  ✅ React context created via ReactInstanceManager (Old Architecture)");
                  } else {
                    MBLog.d(TAG, "  ℹ️ ReactInstanceManager already started creating context");
                  }
                }
              }
            } catch (Exception oldArchEx) {
              MBLog.e(TAG, "  ❌ Old Architecture React init also failed", oldArchEx);
            }
          }
        } catch (Exception e) {
          MBLog.e(TAG, "Failed to initialize React Native headlessly", e);
        }
      }
    } catch (Exception e) {
      MBLog.e(TAG, "Failed to start foreground service", e);
    }

    // ── Warm-start sync: if JWPlayer is already playing (e.g. phone app was
    // active when AA reconnects), pull its current state into the session so
    // AA sees the currently-playing track immediately.
    syncSessionFromActivePlayer();

    setSessionToken(mSession.getSessionToken());
  }

  /**
   * Media id of the most recently played item, or null when nothing is available.
   *
   * Reads the History browse node, which is ordered most-recent-first, and returns
   * its first playable entry. Returns null when the browse hierarchy has not been
   * populated yet — in that case there is genuinely nothing to resume, because the
   * store has no record of what was last played.
   */
  private String findMostRecentHistoryMediaId() {
    try {
      List<MediaBrowserCompat.MediaItem> history =
              MediaItemsStore.getInstance().getSafeMediaItemsByParentId(HISTORY_PARENT_ID);
      if (history == null || history.isEmpty()) {
        return null;
      }
      for (MediaBrowserCompat.MediaItem item : history) {
        if (item != null && item.isPlayable() && item.getMediaId() != null) {
          return item.getMediaId();
        }
      }
    } catch (Throwable t) {
      MBLog.w(TAG, "findMostRecentHistoryMediaId failed: " + t.getMessage());
    }
    return null;
  }

  /**
   * Whether ROOT currently represents an unauthenticated tree.
   *
   * When nobody is signed in, the JS layer replaces the usual sections with a single
   * login node, so there is no content to resume and none to browse either. That case
   * needs a different answer from "signed in but nothing played yet".
   *
   * The login node is the ONLY accepted signal. An absent or empty ROOT is deliberately
   * not treated as signed out: before the first load it is simply unpopulated, and a
   * signed-in user on a cold start must never be told to sign in.
   */
  private boolean isSignedOutTree() {
    try {
      if (!MediaItemsStore.getInstance().isHierarchyReady()) {
        return false;
      }
      List<MediaBrowserCompat.MediaItem> rootChildren =
              MediaItemsStore.getInstance().getSafeMediaItemsByParentId(MEDIA_ROOT_ID);
      if (rootChildren == null || rootChildren.isEmpty()) {
        return false;
      }
      for (MediaBrowserCompat.MediaItem item : rootChildren) {
        if (item != null && LOGIN_NODE_ID.equalsIgnoreCase(item.getMediaId())) {
          return true;
        }
      }
    } catch (Throwable t) {
      MBLog.w(TAG, "isSignedOutTree failed: " + t.getMessage());
    }
    return false;
  }

  /**
   * Answer a resume request that cannot be satisfied, immediately and in the user's terms.
   *
   * A caller that asked the session to resume keeps its player screen up until the session
   * reports progress, so this must respond rather than stay silent — staying silent is what
   * left the user watching an empty player until the request timed out.
   *
   * The error message is rendered verbatim on the car screen, so it is written for the user,
   * not for a log. When the tree is unauthenticated the message is paired with a resolution
   * action, which the head unit renders as a button that opens this app on the phone so the
   * user can sign in there.
   */
  private void reportCannotResume() {
    if (mSession == null) {
      return;
    }
    try {
      boolean signedOut = isSignedOutTree();

      CharSequence appLabel = getApplicationInfo().loadLabel(getPackageManager());
      String message = signedOut
              ? "Sign in to " + appLabel + " on your phone to listen here"
              : "Nothing played yet — choose something to start";
      int errorCode = signedOut
              ? PlaybackStateCompat.ERROR_CODE_AUTHENTICATION_EXPIRED
              : PlaybackStateCompat.ERROR_CODE_ACTION_ABORTED;

      PlaybackStateCompat.Builder builder = new PlaybackStateCompat.Builder()
              .setState(PlaybackStateCompat.STATE_ERROR, 0, 0f)
              .setActions(0L)
              .setErrorMessage(errorCode, message);

      if (signedOut) {
        Bundle resolution = buildSignInResolutionExtras();
        if (resolution != null) {
          builder.setExtras(resolution);
        }
      }

      mSession.setPlaybackState(builder.build());
      cannotResumeErrorPublished = true;
      MBLog.d(TAG, "  ⛔ Published STATE_ERROR (signedOut=" + signedOut + "): " + message);
    } catch (Throwable t) {
      MBLog.w(TAG, "reportCannotResume failed: " + t.getMessage());
    }
  }

  /**
   * Withdraw a "cannot resume" error once the condition behind it has gone.
   *
   * The head unit shows its error screen for as long as the session reports an error, and
   * nothing else in this class ever moves the session out of that state when no playback
   * follows. So a user who signs in on the phone while the sign-in message is up would keep
   * staring at it until backing out by hand. Returning to STATE_NONE releases that screen
   * and lets the refreshed browse tree be shown instead.
   *
   * Only ever touches a session that is actually in the error state, so a real playback
   * state pushed by the player is never overwritten.
   */
  private void clearCannotResumeErrorIfResolved() {
    if (!cannotResumeErrorPublished || mSession == null) {
      return;
    }
    // Still unauthenticated: the message is still the right thing to show.
    if (isSignedOutTree()) {
      return;
    }
    try {
      PlaybackStateCompat current = mSession.getController() != null
              ? mSession.getController().getPlaybackState()
              : null;
      if (current != null && current.getState() != PlaybackStateCompat.STATE_ERROR) {
        // Something real took over already; nothing to withdraw.
        cannotResumeErrorPublished = false;
        return;
      }

      mSession.setPlaybackState(new PlaybackStateCompat.Builder()
              .setState(PlaybackStateCompat.STATE_NONE, 0, 1.0f)
              .setActions(0L)
              .build());
      cannotResumeErrorPublished = false;
      MBLog.d(TAG, "  ✅ Withdrew resume error — content is available again");

      // A client parked on the login node needs to be told to re-query it, so the
      // now-invalid id can send it back out to the refreshed tree.
      try {
        notifyChildrenChanged(LOGIN_NODE_ID);
      } catch (Throwable ignored) {}
    } catch (Throwable t) {
      MBLog.w(TAG, "clearCannotResumeErrorIfResolved failed: " + t.getMessage());
    }
  }

  /**
   * Extras that turn a sign-in error into a tappable action on the car screen.
   *
   * The head unit renders the label as a button and fires the intent on the phone, which
   * is the only place credentials can be entered. Returns null when no launch intent
   * exists, in which case the error is still shown, just without the button.
   */
  private Bundle buildSignInResolutionExtras() {
    try {
      Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
      if (launchIntent == null) {
        return null;
      }
      launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
      PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, launchIntent,
              PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

      Bundle extras = new Bundle();
      extras.putString(
              MediaConstants.PLAYBACK_STATE_EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL,
              "Sign in");
      extras.putParcelable(
              MediaConstants.PLAYBACK_STATE_EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT,
              pendingIntent);
      return extras;
    } catch (Throwable t) {
      MBLog.w(TAG, "buildSignInResolutionExtras failed: " + t.getMessage());
      return null;
    }
  }

  /**
   * Called (via reflection) by RNJWMediaService once it has promoted to foreground with the
   * real MediaStyle notification. Drops this service's plain placeholder so the user never sees
   * two notifications, while leaving the process's media FGS state owned by the playback service.
   */
  public static void onJwPlayerNotificationPosted() {
    MediaBrowserService self = sInstance;
    if (self == null || !self.holdsOwnNotification) {
      return;
    }
    MBLog.e(TAG, "onJwPlayerNotificationPosted: dropping placeholder " + NOTIFICATION_ID);
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        self.stopForeground(Service.STOP_FOREGROUND_REMOVE);
      } else {
        self.stopForeground(true);
      }
    } catch (Throwable t) {
      MBLog.w(TAG, "onJwPlayerNotificationPosted failed: " + t.getMessage());
    }
    self.holdsOwnNotification = false;
  }

  /**
   * Ask RNJWMediaSessionHelper to re-point at the (possibly new) MediaSession
   * singleton and re-push its current metadata + playback state.  This covers
   * both UI player (RNJWPlayerView) and background player scenarios.
   * Uses reflection so this module has no compile-time dependency on JWPlayer.
   */
  private void syncSessionFromActivePlayer() {
    try {
      Class<?> helperClass = Class.forName("com.jwplayer.rnjwplayer.session.RNJWMediaSessionHelper");
      java.lang.reflect.Method refresh = helperClass.getMethod("refreshSessionReference");
      refresh.invoke(null);  // static method
      MBLog.d(TAG, "syncSessionFromActivePlayer: refreshSessionReference() called successfully");
    } catch (ClassNotFoundException e) {
      MBLog.d(TAG, "syncSessionFromActivePlayer: JWPlayer classes not available — skipping");
    } catch (Exception e) {
      MBLog.w(TAG, "syncSessionFromActivePlayer failed: " + e.getMessage());
    }
  }
  
  /**
   * Poll until JS has populated the browse hierarchy, then send real children.
   * This keeps Android Auto on the native loading spinner (via result.detach())
   * and avoids returning empty lists that some head units cache.
   *
   * IMPORTANT: result must be detached before calling this.
   */
  @Override
  public void onLoadChildren(@NonNull final String parentMediaId,
                            @NonNull final Result<List<MediaBrowserCompat.MediaItem>> result) {
    MBLog.v(TAG, "→ onLoadChildren(parentMediaId=" + parentMediaId + ")");

    // Return empty list for the synthetic "no recent content" root.
    // This tells Android Auto there is nothing to resume.
    if (EMPTY_RECENT_ROOT_ID.equals(parentMediaId)) {
      MBLog.d(TAG, "  ↩ Returning empty children for EMPTY_RECENT_ROOT");
      result.sendResult(new ArrayList<>());
      return;
    }

    // The login node exists only while unauthenticated, and it never has children.
    // A client that had navigated into it stays there after a sign-in, re-querying a
    // node that is no longer part of the tree and being handed an empty list forever.
    // A null result marks the id invalid, which is the one answer that makes the client
    // leave the screen on its own instead of waiting for content that will never come.
    if (LOGIN_NODE_ID.equals(parentMediaId) && !isSignedOutTree()) {
      MBLog.d(TAG, "  ↩ Login node requested after sign-in — answering null so the client leaves it");
      result.sendResult(null);
      return;
    }

    final MediaItemsStore store = MediaItemsStore.getInstance();

    // CRITICAL: Check if React context is active before proceeding
    ReactContext reactContext = store.getReactApplicationContext();
    boolean hasContext = reactContext != null;
    boolean isActive = hasContext && reactContext.hasActiveReactInstance();
    
    if (!hasContext || !isActive) {
      result.detach();
      registerPendingLoad(parentMediaId, result, 60000); // Give 60s for context to become active
      return;
    }

    // Send browsable event only on FIRST browse (user navigation), not on subsequent queries.
    // IMPORTANT: ROOT is a boot signal. On cold start (force-stop), AA usually browses ROOT first
    // while React/JS is still loading. If we "consume" ROOT once and never retry, Android 12 can
    // get stuck forever with an empty hierarchy.
    try {
      final boolean isRoot = MEDIA_ROOT_ID.equals(parentMediaId);
      final boolean isPlaceholder = parentMediaId.endsWith("-loading")
        || parentMediaId.endsWith(" loading...")
        || parentMediaId.contains("-empty");

      // Use the same store instance already created above
      final boolean hierarchyReadyNow = store.isHierarchyReady();

      if (!isPlaceholder) {
        if (isRoot && !hierarchyReadyNow) {
          // Cold-start retry: keep kicking ROOT (throttled) until JS populates the hierarchy.
          final long now = System.currentTimeMillis();
          if (now - lastRootKickMs >= ROOT_KICK_INTERVAL_MS) {
            lastRootKickMs = now;
            MBLog.d(TAG, "  🔁 ROOT cold-start kick (hierarchy not ready) -> sending event to JS");
            sendBrowsableItemToJS(parentMediaId);
          }
          // IMPORTANT: do NOT add ROOT to browsedItems while hierarchy isn't ready.
        } else {
          final boolean isFirstBrowse = !browsedItems.contains(parentMediaId);
          if (isFirstBrowse) {
            // MBLog.d(TAG, "  🆕 First browse of: " + parentMediaId + " - sending event to JS");
            browsedItems.add(parentMediaId);
            sendBrowsableItemToJS(parentMediaId);
          }
        }
      }
    } catch (Throwable t) {
      MBLog.w(TAG, "Failed to send browsable item to JS for " + parentMediaId, t);
    }

    // If React/JS hasn't populated the hierarchy at least once, keep AA on its native spinner.
    boolean hierarchyReady = store.isHierarchyReady();
    MBLog.d(TAG, "  🏗️ isHierarchyReady: " + hierarchyReady);

    if (!hierarchyReady) {
      MBLog.d(TAG, "  ⏳ hierarchy not ready -> detaching and waiting for store update (no polling)");
      result.detach();
      registerPendingLoad(parentMediaId, result, 15000);
      return;
    }

    // Hierarchy is ready: return children if present.
    List<MediaBrowserCompat.MediaItem> mediaItems = store.getMediaItemsByParentId(parentMediaId);
    if (mediaItems == null) {
      mediaItems = new ArrayList<>();
    }

    // For lazy-loaded nodes, Android Auto can cache an empty response.
    // If we have no children yet, detach and wait for update (no polling).
    boolean isLazyNode = "Downloads".equals(parentMediaId) || parentMediaId.startsWith("series:") || parentMediaId.startsWith("authors:");
    boolean isHome = "Home".equals(parentMediaId);
    
    if (mediaItems.isEmpty() && (isLazyNode || isHome)) {
      result.detach();
      // Give Home 30s for slow network loads, others 8s
      int timeout = isHome ? 30000 : 8000;
      registerPendingLoad(parentMediaId, result, timeout);
      return;
    }

    MBLog.d(TAG, "  ✅ Sending " + mediaItems.size() + " items to Android Auto for parentId: " + parentMediaId);
    result.sendResult(mediaItems);
  }

  /**
   * Poll until JS has populated the browse hierarchy, then send real children.
   * This keeps Android Auto on the native loading spinner (via result.detach()).
   * IMPORTANT: result must be detached before calling this.
   */
  private void pollForHierarchyReadyAndSend(
    @NonNull final Result<List<MediaBrowserCompat.MediaItem>> result,
    @NonNull final String parentId,
    final int attempt,
    final int timeoutMs,
    final long startTimeMs
  ) {
    try {
      final long now = System.currentTimeMillis();
      final long elapsed = now - startTimeMs;

      if (MediaItemsStore.getInstance().isHierarchyReady()) {
        List<MediaBrowserCompat.MediaItem> items =
          MediaItemsStore.getInstance().getSafeMediaItemsByParentId(parentId);

        if (items == null) items = new ArrayList<>();

        // Log.d(TAG, "✅ pollForHierarchyReadyAndSend: hierarchyReady=true parentId=" + parentId + " items=" + items.size());
        // MBLog.d(TAG, "✅ pollForHierarchyReadyAndSend: hierarchyReady=true parentId=" + parentId + " items=" + items.size());
        result.sendResult(items);
        return;
      }

      if (elapsed >= timeoutMs) {
        // Give up: send an empty list (AA shows its own empty-state UI).
        MBLog.w(TAG, "⏱️ pollForHierarchyReadyAndSend: timeout parentId=" + parentId + " sending empty");
        result.sendResult(new ArrayList<MediaBrowserCompat.MediaItem>());
        return;
      }

      int delay;
      if (attempt <= 0) delay = 120;
      else if (attempt == 1) delay = 200;
      else if (attempt == 2) delay = 350;
      else if (attempt == 3) delay = 600;
      else if (attempt == 4) delay = 1000;
      else delay = 1500;

      // MBLog.d(TAG, "⏳ pollForHierarchyReadyAndSend: waiting parentId=" + parentId + " attempt=" + attempt + " delayMs=" + delay);

      new Handler(Looper.getMainLooper()).postDelayed(
        new Runnable() {
          @Override
          public void run() {
            pollForHierarchyReadyAndSend(result, parentId, attempt + 1, timeoutMs, startTimeMs);
          }
        },
        delay
      );

    } catch (Throwable t) {
      try {
        MBLog.w(TAG, "Failed pollForHierarchyReadyAndSend, sending empty for parentId=" + parentId, t);
        result.sendResult(new ArrayList<MediaBrowserCompat.MediaItem>());
      } catch (Throwable ignored) {
        MBLog.e(TAG, "Failed to recover from pollForHierarchyReadyAndSend error for parentId=" + parentId, t);
      }
    }
  }

  /**
   * Try to delegate callback to RNJWMediaSessionHelper if it's available
   * @param action The action to delegate
   * @param param1 First parameter (mediaId for onPlayFromMediaId, position for onSeekTo)
   * @param extras Bundle extras for onPlayFromMediaId
   * @return true if delegation was successful, false if RNJWMediaSessionHelper not available
   */
  private boolean delegateToRNJWMediaSessionHelper(String action, String param1, android.os.Bundle extras) {
    MBLog.v(TAG, "→ delegateToRNJWMediaSessionHelper(action=" + action + (param1 != null ? ", param=" + param1 : "") + ")");
    try {
        Class<?> helperClass = Class.forName("com.jwplayer.rnjwplayer.session.RNJWMediaSessionHelper");
        MBLog.d(TAG, "  ✅ Found RNJWMediaSessionHelper class");
        
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

            case "onSetSpeed":
                try {
                    java.lang.reflect.Method setSpeedMethod = helperClass.getMethod("handleSetSpeed", float.class);
                    Boolean setSpeedResult = (Boolean) setSpeedMethod.invoke(null, Float.parseFloat(param1));
                    return setSpeedResult != null && setSpeedResult;
                } catch (NoSuchMethodException speedEx) {
                    MBLog.w(TAG, "handleSetSpeed not available: " + speedEx.getMessage());
                    return false;
                }
                
            default:
                return false;
        }
        
    } catch (Exception e) {
        MBLog.w(TAG, "  ⚠️ Delegation failed for '" + action + "': " + e.getMessage());
        return false;
    }
  }

  // ==================== Speed Control Helpers ====================

  /**
   * Returns the R.drawable resource id for the given speed.
   *
   * Icons are the same PNGs used by CarPlay, copied from
   * @orthodox-union/all-mobile-vehicle  →  src/images/tachometer-{tier}@3x.png
   *
   * Tier thresholds must stay in sync with getSpeedIconTier() in
   * @orthodox-union/all-mobile-vehicle  →  src/utils/speed.ts
   *
   * Tier mapping:
   *   slow     → speed <= 1.0
   *   average  → speed <= 1.25
   *   normal   → speed <= 1.5
   *   fast     → speed <= 2.0
   *   fastest  → speed > 2.0
   */
  private int getSpeedIconResource(float speed) {
    MBLog.v(TAG, "→ getSpeedIconResource(speed=" + speed + ")");
    if (speed <= 1.0f)  return R.drawable.ic_speed_slow;
    if (speed <= 1.25f) return R.drawable.ic_speed_average;
    if (speed <= 1.5f)  return R.drawable.ic_speed_normal;
    if (speed <= 2.0f)  return R.drawable.ic_speed_fast;
    return R.drawable.ic_speed_fastest;
  }

  /** Builds a PlaybackStateCompat.CustomAction for the speed button. */
  private PlaybackStateCompat.CustomAction buildSpeedCustomAction() {
    MBLog.v(TAG, "→ buildSpeedCustomAction()");
    // Format: 1.0 → "1x", 1.25 → "1.25x", 1.5 → "1.5x"
    String raw = String.format("%.2f", currentPlaybackSpeed);
    raw = raw.replaceAll("0+$", "").replaceAll("\\.$", "");
    String label = raw + "x";
    return new PlaybackStateCompat.CustomAction.Builder(
        ACTION_CHANGE_SPEED,
        label,
        getSpeedIconResource(currentPlaybackSpeed))
        .build();
  }

  /**
   * Public static method that returns the current speed custom action.
   * Called by RNJWMediaSessionHelper as a fallback when no custom actions
   * exist on the session (e.g. after a state wipe during media switch).
   * Returns null if no MediaBrowserService instance is running.
   */
  public static android.support.v4.media.session.PlaybackStateCompat.CustomAction getSpeedCustomAction() {
    MBLog.v(TAG, "→ getSpeedCustomAction()");
    if (sInstance == null) return null;
    try {
      return sInstance.buildSpeedCustomAction();
    } catch (Exception e) {
      return null;
    }
  }

  /** Updates the current PlaybackState to reflect the new speed (including new custom action label/icon). */
  private void updatePlaybackStateSpeed() {
    MBLog.v(TAG, "→ updatePlaybackStateSpeed()");
    if (mSession == null) return;
    try {
      PlaybackStateCompat current = mSession.getController().getPlaybackState();
      int state = (current != null) ? current.getState() : PlaybackStateCompat.STATE_NONE;
      long position = (current != null) ? current.getPosition() : 0;
      long actions = (current != null) ? current.getActions() :
          (PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE |
           PlaybackStateCompat.ACTION_STOP | PlaybackStateCompat.ACTION_SEEK_TO |
           PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS);
      mSession.setPlaybackState(new PlaybackStateCompat.Builder()
          .setState(state, position, currentPlaybackSpeed)
          .setActions(actions)
          .addCustomAction(buildSpeedCustomAction())
          .build());
    } catch (Exception e) {
      MBLog.w(TAG, "updatePlaybackStateSpeed failed: " + e.getMessage());
    }
  }

  /**
   * Public entry point for speed-button press, callable via reflection from
   * RNJWMediaSessionHelper.onCustomAction when the callback has been replaced.
   * Emits an event to JS so the vehicle lib can compute the next speed.
   */
  public void handleSpeedAction() {
    MBLog.v(TAG, "→ handleSpeedAction()");
    emitSpeedButtonPressedEvent();
  }

  /**
   * Emits an 'onSpeedButtonPressed' event to JS so the vehicle lib can cycle
   * the speed and call setPlaybackSpeed() back with the computed value.
   */
  private void emitSpeedButtonPressedEvent() {
    MBLog.v(TAG, "→ emitSpeedButtonPressedEvent()");
    try {
      ReactContext reactContext = MediaItemsStore.getInstance().getReactApplicationContext();
      if (reactContext != null && reactContext.hasActiveCatalystInstance()) {
        reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
            .emit("onSpeedButtonPressed", null);
      }
    } catch (Exception e) {
      MBLog.w(TAG, "emitSpeedButtonPressedEvent failed: " + e.getMessage());
    }
  }

  /**
   * Public API: sets the playback speed, updates the Android Auto custom action
   * button (icon + label), and delegates to JWPlayer so the actual rate matches.
   *
   * Called from JS (via MediaBrowserModule) in two scenarios:
   *  1. Speed-button-pressed handler computed the next speed.
   *  2. App-side player speed changed and AA UI needs to stay in sync.
   */
  public void setPlaybackSpeed(float speed) {
    MBLog.v(TAG, "→ setPlaybackSpeed(speed=" + speed + ")");
    currentPlaybackSpeed = speed;
    delegateToRNJWMediaSessionHelper("onSetSpeed", String.valueOf(speed), null);
    updatePlaybackStateSpeed();
  }

  /**
   * Static sync-only entry point: updates the stored speed and refreshes the
   * AA custom-action icon WITHOUT delegating back to JWPlayer (since the player
   * already has the correct rate).
   *
   * Called via reflection from RNJWMediaSessionHelper.updatePlaybackState() when
   * it detects the actual JWPlayer rate drifted from the stored speed — i.e. the
   * in-app UI changed speed directly, bypassing the vehicle-aware wrapper.
   */
  public static void setPlaybackSpeedFromSync(float speed) {
    MBLog.v(TAG, "→ setPlaybackSpeedFromSync(speed=" + speed + ")");
    if (sInstance != null) {
      sInstance.currentPlaybackSpeed = speed;
      sInstance.updatePlaybackStateSpeed();
    }
  }

  /**
   * Returns the current playback speed tracked by the MediaSession.
   * Called from JS (via MediaBrowserModule) so the app can sync its UI after
   * a headless Android Auto session that changed the speed.
   */
  public float getPlaybackSpeed() {
    MBLog.v(TAG, "→ getPlaybackSpeed()");
    // Try to read the actual rate from the active JWPlayer instance first.
    // This covers the case where speed was changed from the in-app UI without
    // going through MediaBrowserService.setPlaybackSpeed().
    try {
      Class<?> helperClass = Class.forName("com.jwplayer.rnjwplayer.session.RNJWMediaSessionHelper");
      java.lang.reflect.Method getRate = helperClass.getMethod("getActualPlaybackRate");
      float actualRate = (float) getRate.invoke(null);
      if (actualRate > 0) {
        currentPlaybackSpeed = actualRate; // keep stored value in sync
        return actualRate;
      }
    } catch (Exception ignored) {}
    return currentPlaybackSpeed;
  }

  /** Static accessor so MediaBrowserModule can reach this instance. */
  public static MediaBrowserService getInstance() {
    return sInstance;
  }

  /**
   * Reset the MediaSession to a completely clean state.
   * Called when Android Auto disconnects so the NEXT connection sees
   * actions=0 / active=false / metadata=null and does NOT navigate to
   * the empty Now Playing screen.
   *
   * Root cause: MediaSessionSingleton is a long-lived singleton that retains
   * whatever playback state was last set (e.g. actions=311 after playing a track).
   * Android Auto reads that stale state BEFORE onGetRoot() is even called on the
   * next connection, decides "this session can resume", and sends onPlay().
   */
  /**
   * Check if JWPlayer has an active player via reflection.
   * PlaybackManager.hasActivePlayer() is an instance method, so we must get
   * the singleton via getInstance() first.
   */
  /** Package-private so MediaBrowserHeadlessService can apply the same shared-notification rule. */
  static boolean checkHasActivePlayer() {
    try {
      Class<?> pmClass = Class.forName("com.jwplayer.rnjwplayer.PlaybackManager");
      java.lang.reflect.Method getInst = pmClass.getMethod("getInstance");
      Object pm = getInst.invoke(null);  // getInstance() is static
      java.lang.reflect.Method hasActive = pmClass.getMethod("hasActivePlayer");
      return (boolean) hasActive.invoke(pm);  // hasActivePlayer() is instance method
    } catch (Exception e) {
      MBLog.w(TAG, "checkHasActivePlayer failed: " + e.getMessage());
      return false;
    }
  }

  public static void clearSessionForReconnect() {
    if (sInstance == null || sInstance.mSession == null) return;

    // If JWPlayer still has an active player, don't wipe the session —
    // the user is still listening and we want to preserve Now Playing state.
    if (checkHasActivePlayer()) {
      MBLog.d(TAG, "clearSessionForReconnect: JWPlayer active — skipping session wipe");
      return;
    }

    try {
      sInstance.mSession.setMetadata(null);
      sInstance.mSession.setActive(false);
      sInstance.mSession.setPlaybackState(new PlaybackStateCompat.Builder()
          .setState(PlaybackStateCompat.STATE_NONE, 0, 1.0f)
          .setActions(0L)
          .build());
      sInstance.cannotResumeErrorPublished = false;
      MBLog.d(TAG, "🧹 clearSessionForReconnect: session cleaned (active=false, actions=0, metadata=null)");
    } catch (Exception e) {
      MBLog.w(TAG, "clearSessionForReconnect failed: " + e.getMessage());
    }
  }

  public static void updateSeekPosition(String mediaId, long positionMs) {
    MBLog.v(TAG, "→ updateSeekPosition(mediaId=" + mediaId + ", positionMs=" + positionMs + ")");
    if (mediaId == null) {
      return;
    }

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
          }
        } catch (Throwable t) {
          MBLog.w(TAG, "Failed to start headless service for seek", t);
        }
      }
    } catch (Throwable t) {
      MBLog.w(TAG, "Report Seek From Native failed", t);
    }
  }

  @Override
  public void onConfigurationChanged(Configuration newConfig) {
    MBLog.v(TAG, "→ onConfigurationChanged()");
    super.onConfigurationChanged(newConfig);
  }
  
  @Override
  public void onDestroy() {
    MBLog.v(TAG, "→ onDestroy()");
    super.onDestroy();

    // Check if JWPlayer still has an active player via reflection.
    // If it does, do NOT touch the session — JWPlayer owns it.
    boolean hasActivePlayer = checkHasActivePlayer();

    if (hasActivePlayer) {
      MBLog.d(TAG, "  ℹ️ onDestroy: JWPlayer still active — leaving session untouched");
    } else if (mSession != null) {
      // No active player — clean the session so the NEXT Android Auto
      // connection sees actions=0 / metadata=null instead of stale state.
      mSession.setMetadata(null);
      mSession.setPlaybackState(new PlaybackStateCompat.Builder()
          .setState(PlaybackStateCompat.STATE_NONE, 0, 1.0f)
          .setActions(0L)
          .build());
      mSession.setActive(false);
      // Release fully when no active player — removes from dumpsys media_session
      mSession.release();
      MBLog.d(TAG, "  🧹 onDestroy: session fully released (no active player)");
    }
    
    // Only release the shared singleton when nothing is playing. Releasing it while a JWPlayer
    // owner is attached destroys the MediaSession that the media-playback FGS notification (2005)
    // is linked to; the platform reacts with "setFgsInactiveLocked ... notification=2005" and the
    // playback service silently loses its foreground state. The hasActivePlayer branch above
    // already avoided touching mSession, but this unconditional release undid that.
    if (!hasActivePlayer) {
      MediaSessionSingleton.release();
    } else {
      MBLog.d(TAG, "onDestroy: keeping shared MediaSession (JWPlayer active)");
    }

    sInstance = null;
    stopForegroundAndSelf();
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
    // Only log top-level nodes to avoid flooding (leaf series:/authors: nodes are too many)
    if (parentId == null || (!parentId.startsWith("series:") && !parentId.startsWith("authors:"))) {
      MBLog.v(TAG, "→ onMediaItemsUpdated(parentId=" + parentId + ")");
    }
    // Normalize parentId
    if (parentId == null || parentId.trim().isEmpty()) {
      parentId = MEDIA_ROOT_ID;
    }

    // A tree update may mean a sign-in completed or content finally arrived, which
    // retires any resume error still on screen.
    clearCannotResumeErrorIfResolved();

    // If root changed, allow re-sending browse events for deep nodes
    java.util.Set<String> browsedSnapshot = null;
    if (MEDIA_ROOT_ID.equals(parentId)) {
      // MBLog.d(TAG, "  🧹 ROOT updated: refreshing browsed nodes and clearing cache");
      browsedSnapshot = new java.util.HashSet<>(browsedItems);
      browsedItems.clear();
    }

    // Always notify the specific parent
    notifyChildrenChanged(parentId);

    // If AA is waiting on a detached Result for this parent, flush it now.
    flushPendingIfReady(parentId);

    // If ROOT became ready, also flush ROOT waiters and any other pending nodes.
    if (MEDIA_ROOT_ID.equals(parentId) && MediaItemsStore.getInstance().isHierarchyReady()) {
      flushPendingIfReady(MEDIA_ROOT_ID);
      try {
        // Flush all pending nodes opportunistically
        for (String key : new java.util.HashSet<>(pendingLoadResults.keySet())) {
          flushPendingIfReady(key);
        }
      } catch (Throwable ignored) {}
    }

    // When ROOT changes, also refresh nodes AA may already be inside.
    // This is a safe way to recover from previously empty/placeholder children.
    if (browsedSnapshot != null) {
      try {
        for (String browsed : browsedSnapshot) {
          if (browsed != null && !MEDIA_ROOT_ID.equals(browsed)) {
            notifyChildrenChanged(browsed);
          }
        }
      } catch (Throwable t) {
        MBLog.w(TAG, "Failed to notify browsed nodes", t);
      }
    }
  }

  private void registerPendingLoad(@NonNull final String parentId,
                                   @NonNull final Result<List<MediaBrowserCompat.MediaItem>> result,
                                   final int timeoutMs) {
    MBLog.v(TAG, "→ registerPendingLoad(parentId=" + parentId + ", timeoutMs=" + timeoutMs + ")");
    // Replace any previous pending result for this parentId (AA may re-request quickly)
    pendingLoadResults.put(parentId, result);

    // Clear any existing timeout runnable
    Runnable oldTimeout = pendingTimeouts.remove(parentId);
    if (oldTimeout != null) {
      try { new Handler(Looper.getMainLooper()).removeCallbacks(oldTimeout); } catch (Throwable ignored) {}
    }

    Runnable timeoutRunnable = new Runnable() {
      @Override
      public void run() {
        try {
          Result<List<MediaBrowserCompat.MediaItem>> pending = pendingLoadResults.remove(parentId);
          pendingTimeouts.remove(parentId);
          if (pending != null) {
            MBLog.w(TAG, "⏱️ Pending load timeout: parentId=" + parentId + " -> sending empty");
            pending.sendResult(new ArrayList<MediaBrowserCompat.MediaItem>());
          }
        } catch (Throwable t) {
          MBLog.w(TAG, "Failed sending timeout empty result for parentId=" + parentId, t);
        }
      }
    };

    pendingTimeouts.put(parentId, timeoutRunnable);
    new Handler(Looper.getMainLooper()).postDelayed(timeoutRunnable, timeoutMs);
  }

  private void flushPendingIfReady(@NonNull final String parentId) {
    // MBLog.v(TAG, "→ flushPendingIfReady(parentId=" + parentId + ")");
    try {
      Result<List<MediaBrowserCompat.MediaItem>> pending = pendingLoadResults.remove(parentId);
      Runnable timeout = pendingTimeouts.remove(parentId);
      if (timeout != null) {
        try { new Handler(Looper.getMainLooper()).removeCallbacks(timeout); } catch (Throwable ignored) {}
      }
      if (pending != null) {
        List<MediaBrowserCompat.MediaItem> items = MediaItemsStore.getInstance().getSafeMediaItemsByParentId(parentId);
        if (items == null) items = new ArrayList<>();
        MBLog.d(TAG, "✅ Flushing pending load: parentId=" + parentId + " items=" + items.size());
        pending.sendResult(items);
      }
    } catch (Throwable t) {
      MBLog.w(TAG, "Failed flushing pending result for parentId=" + parentId, t);
    }
  }

  @Override
  public BrowserRoot onGetRoot(@NonNull String clientPackageName,
                               int clientUid,
                               @Nullable Bundle rootHints) {
    MBLog.v(TAG, "→ onGetRoot(clientPackage=" + clientPackageName + ", uid=" + clientUid + ")");

    // Connect-time posture: this is what the client reads before it decides which
    // screen to open, so record it verbatim.
    try {
      String state = "n/a";
      boolean hasMeta = false;
      boolean active = mSession != null && mSession.isActive();
      if (mSession != null && mSession.getController() != null) {
        hasMeta = mSession.getController().getMetadata() != null;
        PlaybackStateCompat p = mSession.getController().getPlaybackState();
        state = (p == null) ? "null" : String.valueOf(p.getState());
      }
      MBLog.d(TAG, "  🔎 connect posture: sessionActive=" + active
              + " hasMetadata=" + hasMeta
              + " playbackState=" + state
              + " hierarchyReady=" + MediaItemsStore.getInstance().isHierarchyReady()
              + " recentHint=" + (rootHints != null
                  && rootHints.getBoolean("android.service.media.extra.RECENT", false)));
    } catch (Throwable t) {
      MBLog.w(TAG, "connect posture probe failed: " + t.getMessage());
    }

    // Handle Android Auto EXTRA_RECENT hint.
    // AA asks "do you have something to resume?" — only say "yes" when there is
    // actual media metadata (a track was loaded).  When no metadata exists we
    // return EMPTY_RECENT_ROOT_ID which resolves to an empty children list,
    // telling AA "nothing to resume" so it opens the browse tree instead of the
    // empty Now Playing screen.
    if (rootHints != null) {
      boolean wantsRecent = rootHints.getBoolean(
          "android.service.media.extra.RECENT", false);
      if (wantsRecent) {
        // Check both metadata AND playback state — metadata alone can be stale
        // from a previous session.  Only report "resumable" when actually
        // PLAYING, PAUSED, or BUFFERING.
        boolean canResume = false;
        if (mSession != null && mSession.getController() != null) {
          boolean hasMetadata = mSession.getController().getMetadata() != null;
          PlaybackStateCompat psc = mSession.getController().getPlaybackState();
          boolean hasActiveState = psc != null && (
              psc.getState() == PlaybackStateCompat.STATE_PLAYING ||
              psc.getState() == PlaybackStateCompat.STATE_PAUSED ||
              psc.getState() == PlaybackStateCompat.STATE_BUFFERING);
          canResume = hasMetadata && hasActiveState;
        }
        if (!canResume) {
          MBLog.d(TAG, "  ↩ EXTRA_RECENT requested but no resumable media – returning empty recent root");
          return new BrowserRoot(EMPTY_RECENT_ROOT_ID, null);
        }
      }
    }

    String rootId = MediaItemsStore.getInstance().getRootId();
    // Always return a root, even if empty - use default ROOT if rootId is null
    // This prevents Android Auto from showing "doesn't seem to be working" error
    String finalRootId = rootId != null ? rootId : MEDIA_ROOT_ID;
    MBLog.d(TAG, "  ✅ Returning rootId: " + finalRootId);
    return new BrowserRoot(finalRootId, null);
  }

  /**
   * Public method to send skip to next event to React Native
   * Called by RNJWMediaSessionHelper when Next button pressed in Android Auto
   * @param mediaId The ID of the current media item
   * @return The skip token that was included in the event payload (may be null on failure)
   */
  public static String sendSkipToNextEventToReactNative(String mediaId) {
    MBLog.v(TAG, "→ sendSkipToNextEventToReactNative(mediaId=" + mediaId + ")");
    try {
      MediaItemsStore store = MediaItemsStore.getInstance();
      ReactContext reactContext = store.getReactApplicationContext();

      if (reactContext != null) {
        String skipToken = generateSkipToken();
        pendingSkipAcks.put(skipToken, System.currentTimeMillis());

        WritableMap event = Arguments.createMap();
        event.putString("type", "skip-to-next");
        event.putString("mediaId", mediaId);
        event.putString("skipToken", skipToken);
        event.putLong("timestamp", System.currentTimeMillis());

        try {
          reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
            .emit("onSkipToNext", event);
        } catch (Exception emitError) {
          pendingSkipAcks.remove(skipToken);
          MBLog.e(TAG, "Error emitting skip-to-next event: " + emitError.getMessage(), emitError);
          return null;
        }
        return skipToken;
      } else {
        MBLog.w(TAG, "Cannot emit skip-to-next - no React context");
        return null;
      }
    } catch (Exception e) {
      MBLog.e(TAG, "Error in sendSkipToNextEventToReactNative", e);
      return null;
    }
  }
  
  /**
   * Public method to send skip to previous event to React Native
   * Called by RNJWMediaSessionHelper when Previous button pressed in Android Auto
   * @param mediaId The ID of the current media item
   * @return The skip token that was included in the event payload (may be null on failure)
   */
  public static String sendSkipToPreviousEventToReactNative(String mediaId) {
    MBLog.v(TAG, "→ sendSkipToPreviousEventToReactNative(mediaId=" + mediaId + ")");
    try {
      MediaItemsStore store = MediaItemsStore.getInstance();
      ReactContext reactContext = store.getReactApplicationContext();

      if (reactContext != null) {
        String skipToken = generateSkipToken();
        pendingSkipAcks.put(skipToken, System.currentTimeMillis());

        WritableMap event = Arguments.createMap();
        event.putString("type", "skip-to-previous");
        event.putString("mediaId", mediaId);
        event.putString("skipToken", skipToken);
        event.putLong("timestamp", System.currentTimeMillis());

        try {
          reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
            .emit("onSkipToPrevious", event);
        } catch (Exception emitError) {
          pendingSkipAcks.remove(skipToken);
          MBLog.e(TAG, "Error emitting skip-to-previous event: " + emitError.getMessage(), emitError);
          return null;
        }
        return skipToken;
      } else {
        MBLog.w(TAG, "Cannot emit skip-to-previous - no React context");
        return null;
      }
    } catch (Exception e) {
      MBLog.e(TAG, "Error in sendSkipToPreviousEventToReactNative", e);
      return null;
    }
  }
  
  /**
   * Generate a short, unique token for a skip event.
   * Uses the lower 32 bits of a UUID to keep the payload small.
   */
  private static String generateSkipToken() {
    return Long.toHexString(java.util.UUID.randomUUID().getLeastSignificantBits() & 0xFFFFFFFFL);
  }

  /**
   * Acknowledge that a skip event was successfully handled by React Native.
   * Called by MediaBrowserModule.acknowledgeSkip() from the JS bridge.
   * If the token is present it is removed; if absent the call is a no-op.
   */
  public static void acknowledgeSkip(String skipToken) {
    if (skipToken != null) {
      pendingSkipAcks.remove(skipToken);
    }
  }

  /**
   * Returns true if the given skipToken is still pending (i.e. RN has not acknowledged it).
   * Used by RNJWMediaSessionHelper to decide whether to fire the native fallback.
   */
  public static boolean isSkipPending(String skipToken) {
    return skipToken != null && pendingSkipAcks.containsKey(skipToken);
  }

  /**
   * Public method to send playlist complete event to React Native
   * Called by RNJWMediaSessionHelper when onPlaylistComplete is triggered
   * @param mediaId The ID of the media item that just completed
   */
  public static void sendPlaylistCompleteToReactNative(String mediaId) {
    MBLog.v(TAG, "→ sendPlaylistCompleteToReactNative(mediaId=" + mediaId + ")");
    try {
      MediaItemsStore store = MediaItemsStore.getInstance();
      ReactContext reactContext = store.getReactApplicationContext();

      long completionSeq = sCompletionSeq.incrementAndGet();

        LifecycleState lifecycleState = reactContext != null
          ? reactContext.getLifecycleState()
          : null;
        boolean useForegroundRoute = lifecycleState == LifecycleState.RESUMED;

        MBLog.e(TAG, "onPlaylistComplete seq=" + completionSeq
          + " mediaId=" + mediaId + " lifecycleState=" + lifecycleState
          + " route=" + (useForegroundRoute ? "foreground" : "headless"));

        if (useForegroundRoute) {
        WritableMap event = Arguments.createMap();
        event.putString("type", "playlist-complete");
        event.putString("mediaId", mediaId);
        event.putLong("timestamp", System.currentTimeMillis());
        event.putDouble("completionSeq", (double) completionSeq);

        try {
          reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
            .emit("onPlaylistComplete", event);
        } catch (Exception emitError) {
          MBLog.e(TAG, "Error emitting playlist complete event: " + emitError.getMessage(), emitError);
        }
      } else {
        try {
          android.content.Context ctx = reactContext != null
              ? reactContext.getApplicationContext()
              : (sInstance != null ? sInstance.getApplicationContext() : null);
          if (ctx != null) {
            android.content.Intent intent =
              new android.content.Intent(ctx, MediaBrowserHeadlessService.class);
            intent.setAction(MediaBrowserHeadlessService.ACTION_PLAYLIST_COMPLETE);
            intent.putExtra("mediaId", mediaId);
            intent.putExtra("completionSeq", completionSeq);
            ctx.startService(intent);
          } else {
            MBLog.w(TAG, "Cannot start headless completion - no application context");
          }
        } catch (Throwable t) {
          MBLog.w(TAG, "Failed to start headless service for playlist-complete", t);
        }
      }
    } catch (Exception e) {
      MBLog.e(TAG, "Error in static sendPlaylistCompleteToReactNative", e);
    }
  }

  /**
   * Public method to send media item to React Native
   * Called by RNJWMediaSessionHelper when onPlayFromMediaId is triggered
   */
  public static void sendMediaItemToReactNative(String mediaId) {
    MBLog.v(TAG, "→ sendMediaItemToReactNative(mediaId=" + mediaId + ")");
    try {
      // Guard: don't delegate playback for placeholder loading items
      if (mediaId != null && !MediaItemsStore.getInstance().isHierarchyReady()) {
        return;
      }
      // Get the MediaItemsStore instance
      MediaItemsStore store = MediaItemsStore.getInstance();
      MediaBrowserCompat.MediaItem mediaItem = store.getMediaItemById(mediaId);
      ReactContext reactContext = store.getReactApplicationContext();
      
      if (mediaItem != null && reactContext != null) {
        WritableMap mediaItemMap = Arguments.createMap();
        String mediaIdStr = mediaItem.getDescription().getMediaId();
        mediaItemMap.putString("id", mediaIdStr);

        CharSequence title = mediaItem.getDescription().getTitle();
        if (title != null) {
          mediaItemMap.putString("title", title.toString());
        }

        MBLog.d(TAG, "LOG_CAN_BE_REMOVED_ON_RELEASE foreground-dispatch-store-hit mediaId="
          + mediaId
          + ", title=" + (title != null ? title.toString() : "")
          + ", hierarchyReady=" + store.isHierarchyReady());

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
      } else {
        MBLog.w(TAG, "LOG_CAN_BE_REMOVED_ON_RELEASE foreground-dispatch-missing mediaId="
          + mediaId
          + ", hasMediaItem=" + (mediaItem != null)
          + ", hasReactContext=" + (reactContext != null)
          + ", hierarchyReady=" + store.isHierarchyReady());
      }
    } catch (Exception e) {
      MBLog.e(TAG, "Error in static sendMediaItemToReactNative", e);
    }
  }

  private void sendMediaItemToJS(String mediaId) {
    MBLog.d(TAG, "📤 sendMediaItemToJS called for mediaId: " + mediaId);
    // Guard: don't delegate playback for placeholder loading items
    if (mediaId != null && !MediaItemsStore.getInstance().isHierarchyReady()) {
      MBLog.w(TAG, "  ⚠️ Hierarchy not ready, skipping media item emission");
      return;
    }
    ReactContext reactContext = MediaItemsStore.getInstance().getReactApplicationContext();
    boolean active = reactContext != null && reactContext.hasActiveReactInstance();
    
    MediaBrowserCompat.MediaItem mediaItem = MediaItemsStore.getInstance().getMediaItemById(mediaId);
    
    if (mediaItem != null) {
      CharSequence title = mediaItem.getDescription().getTitle();
      CharSequence subtitle = mediaItem.getDescription().getSubtitle();
      
      WritableMap mediaItemMap = Arguments.createMap();
      String mediaIdStr = mediaItem.getDescription().getMediaId();
      mediaItemMap.putString("id", mediaIdStr);
      if (title != null) {
        mediaItemMap.putString("title", title.toString());
      }

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
        MBLog.d(TAG, "  📺 Calling JWPlayerBridge.handleMediaItemSelected");
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
        MBLog.d(TAG, "  ✅ JWPlayerBridge call completed");
      } catch (Exception e) {
        MBLog.e(TAG, "  ❌ Error sending to JWPlayer bridge: " + e.getMessage(), e);
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
        MBLog.d(TAG, "  📨 Starting headless service for media item");
        startService(intent);
        MBLog.d(TAG, "  ✅ Headless service started successfully");
      } catch (Exception e) {
        MBLog.e(TAG, "  ❌ Failed to start headless service: " + e.getMessage(), e);
      }
    }
  }

  private void sendBrowsableItemToJS(String parentMediaId) {
    // MBLog.d(TAG, "🔔 sendBrowsableItemToJS called for: " + parentMediaId);
    ReactContext reactContext = MediaItemsStore.getInstance().getReactApplicationContext();
    WritableMap event = Arguments.createMap();
    event.putString("id", parentMediaId);
    event.putString("playableOrBrowsable", "BROWSABLE");

    // Send to JS if React context is available
    if (reactContext != null) {
        // MBLog.d(TAG, "  ✅ React context available, emitting onBrowsableItemSelected");
        reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit("onBrowsableItemSelected", event);
    } else {
        MBLog.w(TAG, "  ⚠️ React context is NULL, cannot emit onBrowsableItemSelected");
    }
    
    // Send to JWPlayer for headless mode handling
    try {
      JWPlayerBridge.getInstance().handleBrowsableItemSelected(this, parentMediaId);
    } catch (Exception e) {
      MBLog.e(TAG, "Error sending browsable item to JWPlayer bridge", e);
    }
    
    // Trigger headless service for background/killed app scenario
    Intent intent = new Intent(this, MediaBrowserHeadlessService.class);
    intent.setAction(MediaBrowserHeadlessService.ACTION_BROWSABLE_ITEM_SELECTED);
    intent.putExtra("id", parentMediaId);
    intent.putExtra("playableOrBrowsable", "BROWSABLE");
    
    try {
      // MBLog.d(TAG, "  📨 Starting headless service for browsable item");
      startService(intent);
    } catch (Exception e) {
      MBLog.e(TAG, "  ❌ Failed to start headless service: " + e.getMessage());
    }
  }
  
  private void updateMediaSessionForPlayback(String mediaId) {
    MBLog.v(TAG, "→ updateMediaSessionForPlayback(mediaId=" + mediaId + ")");
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

        // Activate the session now that we have real metadata.
        // Doing this here (not in onCreate) prevents Android Auto from
        // navigating to an empty Now Playing screen on launch.
        if (!mSession.isActive()) {
          MBLog.d(TAG, "  🟢 Activating media session (first playback)");
          mSession.setActive(true);
        }
        
        // Update playback state to playing
        mSession.setPlaybackState(new PlaybackStateCompat.Builder()
          .setState(PlaybackStateCompat.STATE_PLAYING, 0, currentPlaybackSpeed)
          .setActions(PlaybackStateCompat.ACTION_PLAY |
                     PlaybackStateCompat.ACTION_PAUSE |
                     PlaybackStateCompat.ACTION_STOP |
                     PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                     PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                     PlaybackStateCompat.ACTION_SEEK_TO)
          .addCustomAction(buildSpeedCustomAction())
          .build());

        // Show media notification
        String titleStr = title != null ? title.toString() : "Playing";
        String subtitleStr = subtitle != null ? subtitle.toString() : "";
        showMediaNotification(titleStr, subtitleStr);
        
      } catch (Exception e) {
        MBLog.e(TAG, "Error updating MediaSession for playback", e);
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
    // JWPlayer's RNJWNotificationHelper owns the MediaStyle notification (id 2005). Posting a
    // second MediaStyle notification here produced two media entries in SystemUI once the ids
    // were split. When no JWPlayer instance is attached there is nothing to show controls for
    // either, so this is now a no-op that only refreshes the session-backed state.
    MBLog.d(TAG, "showMediaNotification: delegated to RNJWNotificationHelper (title=" + title + ")");
    syncSessionFromActivePlayer();
  }
  
  private PendingIntent createMediaActionIntent(String action) {
    Intent intent = new Intent(this, MediaBrowserService.class);
    intent.setAction("media_action_" + action);
    PendingIntent pendingIntent = PendingIntent.getService(this, action.hashCode(), intent,
      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    return pendingIntent;
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    MBLog.v(TAG, "→ onStartCommand(action=" + (intent != null ? intent.getAction() : "null") + ")");
    try {
      final String action = (intent != null) ? intent.getAction() : null;
      if (action == null) {
        return super.onStartCommand(intent, flags, startId);
      }

      // 1) JS ready signal (your new fix)
      if (ACTION_JS_READY.equals(action)) {
        final long now = System.currentTimeMillis();
        if (now - lastRootKickMs >= ROOT_KICK_INTERVAL_MS) {
          lastRootKickMs = now;
          sendBrowsableItemToJS(MEDIA_ROOT_ID);
        }

        // ✅ Remove the useless bootstrap notification once JS is ready
        try { stopForeground(true); } catch (Throwable ignored) {}

        return super.onStartCommand(intent, flags, startId);
      }

      // 2) Legacy notification actions (your old behavior)
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
          }
        } catch (Exception e) {
          MBLog.w(TAG, "Could not stop background player: " + e.getMessage());
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
        stopForegroundAndSelf();
      }

    } catch (Throwable t) {
      MBLog.w(TAG, "onStartCommand failed", t);
    }

    return super.onStartCommand(intent, flags, startId);
  }

  private void stopForegroundAndSelf() {
    // Since NOTIFICATION_ID is now this service's own id (2006) and no longer shared with
    // RNJWMediaService's 2005, stopForeground(true) can no longer demote the playback service.
    // The active-player branch is retained as defence in depth for hosts that still build with
    // an older library where the ids were shared.
    boolean hasActivePlayer = checkHasActivePlayer();
    MBLog.d(TAG, "stopForegroundAndSelf: hasActivePlayer=" + hasActivePlayer
      + " holdsOwnNotification=" + holdsOwnNotification);
    if (hasActivePlayer) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        stopForeground(Service.STOP_FOREGROUND_DETACH);
      }
      holdsOwnNotification = false;
      stopSelf();
      return;
    }
    stopForeground(true);
    holdsOwnNotification = false;
    stopSelf();
  }
}
