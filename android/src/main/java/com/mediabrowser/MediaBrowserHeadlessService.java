package com.mediabrowser;

import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.media.session.MediaButtonReceiver;

import com.facebook.react.ReactApplication;
import com.facebook.react.ReactNativeHost;

import com.facebook.react.HeadlessJsTaskService;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.jstasks.HeadlessJsTaskConfig;
import com.facebook.react.jstasks.HeadlessJsTaskContext;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import javax.annotation.Nullable;

import android.app.Service;
import android.os.Build;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import androidx.core.app.NotificationCompat;
import android.os.Handler;
import android.os.Looper;

public class MediaBrowserHeadlessService extends HeadlessJsTaskService {

    private static final String TAG = "MediaBrowserHeadlessService";
    public static final String ACTION_MEDIA_ITEM_SELECTED = "com.mediabrowser.ACTION_MEDIA_ITEM_SELECTED";
    public static final String ACTION_BROWSABLE_ITEM_SELECTED = "com.mediabrowser.ACTION_BROWSABLE_ITEM_SELECTED";
    public static final String ACTION_CAR_CONNECTION_CHANGED = "com.mediabrowser.ACTION_CAR_CONNECTION_CHANGED";
    // Background auto-advance: JWPlayer completes a track while the app is backgrounded/locked.
    // The main-context DeviceEventEmitter is suspended in Doze, so we also route completion
    // through this HeadlessJsTaskService (which holds a wake lock) so the "play next" logic runs.
    public static final String ACTION_PLAYLIST_COMPLETE = "com.mediabrowser.ACTION_PLAYLIST_COMPLETE";

    // Use the same notification ID as MediaBrowserService (2005) so that
    // the media player notification will replace this initializing notification
    // instead of showing two separate notifications
    private static final int NOTIFICATION_ID_MEDIA_BROWSER = MediaBrowserService.NOTIFICATION_ID;
    private static final String CHANNEL_ID = "MediaPlayback";
    private static final long EVENT_THROTTLE_MS = 5000; // Throttle events to max once per 5 seconds
    private long lastEventEmitTime = 0;

    // Safety: ensure we don't keep a foreground service around longer than needed
    private static final long FOREGROUND_GRACE_MS = 20000; // 20s is enough to spin up headless JS
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    @Nullable private Runnable stopRunnable;

    // Track the most recent Service startId (NOT the headless taskId)
    private volatile int lastStartId = 0;

    private static volatile boolean didWarnMissingNotifIcon = false;

    @Nullable
    @Override
    protected HeadlessJsTaskConfig getTaskConfig(Intent intent) {
        // MBLog.v(TAG, "→ getTaskConfig(action=" + (intent != null ? intent.getAction() : "null") + ")");
        if (intent == null) {
            return null;
        }
        
        Bundle extras = intent.getExtras();
        WritableMap data = Arguments.createMap();
        
        String action = intent.getAction();
        if (action != null) {
            switch (action) {
                case ACTION_MEDIA_ITEM_SELECTED:
                    data.putString("type", "media-item-selected");
                    if (extras != null) {
                        if (extras.containsKey("id")) {
                            data.putString("id", extras.getString("id"));
                        }
                        if (extras.containsKey("title")) {
                            data.putString("title", extras.getString("title"));
                        }
                        if (extras.containsKey("subTitle")) {
                            data.putString("subTitle", extras.getString("subTitle"));
                        }
                        if (extras.containsKey("icon")) {
                            data.putString("icon", extras.getString("icon"));
                        }
                        if (extras.containsKey("playableOrBrowsable")) {
                            data.putString("playableOrBrowsable", extras.getString("playableOrBrowsable"));
                        }
                    }
                    break;
                case ACTION_BROWSABLE_ITEM_SELECTED:
                    data.putString("type", "browsable-item-selected");
                    if (extras != null) {
                        if (extras.containsKey("id")) {
                            data.putString("id", extras.getString("id"));
                        }
                        if (extras.containsKey("playableOrBrowsable")) {
                            data.putString("playableOrBrowsable", extras.getString("playableOrBrowsable"));
                        }
                    }
                    break;
                case ACTION_CAR_CONNECTION_CHANGED:
                    data.putString("type", "car-connection-changed");
                    if (extras != null && extras.containsKey("connectionType")) {
                        data.putInt("connectionType", extras.getInt("connectionType"));
                    }
                    break;
                case ACTION_PLAYLIST_COMPLETE:
                    data.putString("type", "playlist-complete");
                    if (extras != null) {
                        if (extras.containsKey("mediaId")) {
                            data.putString("mediaId", extras.getString("mediaId"));
                        }
                        if (extras.containsKey("completionSeq")) {
                            // Monotonic id used by JS to dedupe the emit vs headless routes.
                            data.putDouble("completionSeq", extras.getLong("completionSeq"));
                        }
                    }
                    break;
                default:
                    return null;
            }
        } else {
            return null;
        }
        
        // Return headless task config with longer timeout for Android Auto scenarios
        // Task name must match the headless task registration in React Native lifecycle
        return new HeadlessJsTaskConfig("MediaBrowserService", data, 120000, true);
    }

    @Override
    public void onHeadlessJsTaskFinish(int taskId) {
        MBLog.v(TAG, "→ onHeadlessJsTaskFinish(taskId=" + taskId + ")");
        super.onHeadlessJsTaskFinish(taskId);

        // Headless task finished: stop immediately and remove any pending auto-stop.
        cancelAutoStop();
        stopForegroundAndSelf();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        MBLog.v(TAG, "→ onBind()");
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // MBLog.v(TAG, "→ onStartCommand(action=" + (intent != null ? intent.getAction() : "null") + ", startId=" + startId + ")");
        lastStartId = startId;

        if (intent != null) {
            String action = intent.getAction();
            if (action != null && (action.equals(ACTION_MEDIA_ITEM_SELECTED) ||
                                action.equals(ACTION_BROWSABLE_ITEM_SELECTED) ||
                                action.equals(ACTION_CAR_CONNECTION_CHANGED) ||
                                action.equals(ACTION_PLAYLIST_COMPLETE))) {

                ReactContext existingContext = MediaItemsStore.getInstance().getReactApplicationContext();
                boolean hasActiveReact = existingContext != null && existingContext.hasActiveReactInstance();
                
                // Foreground is only needed for cold start (killed app) while headless JS spins up.
                // If React is already active, DO NOT show "Connecting..." because it can overwrite the real media notif (same ID).
                if (!hasActiveReact) {
                    createNotificationChannel();
                    startForeground(NOTIFICATION_ID_MEDIA_BROWSER, createNotification());
                    scheduleAutoStop();
                }

                if (hasActiveReact) {
                    // React is active - emit event directly for fast response
                    try {
                        long currentTime = System.currentTimeMillis();
                        long timeSinceLastEvent = currentTime - lastEventEmitTime;

                        if (timeSinceLastEvent >= EVENT_THROTTLE_MS) {
                            HeadlessJsTaskConfig taskConfig = getTaskConfig(intent);
                            if (taskConfig != null) {
                                existingContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                                    .emit("AndroidAutoHeadlessTask", taskConfig.getData());
                                lastEventEmitTime = currentTime;
                            }
                        }
                    } catch (Exception e) {
                        MBLog.e(TAG, "[onStartCommand] Error emitting event", e);
                    }
                }
                
                // ALWAYS run headless task regardless of React state
                // This ensures setupMediaBrowser() runs in ALL cases
                super.onStartCommand(intent, flags, startId);
            }
        }
        
        return START_NOT_STICKY;
    }

    private void createNotificationChannel() {
        MBLog.d(TAG, "Creating notification channel if needed");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Use the same channel as MediaBrowserService for consistency
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Media Playback",
                // Use IMPORTANCE_MIN to make notification as unobtrusive as possible
                // while still satisfying Android's foreground service requirement
                NotificationManager.IMPORTANCE_MIN
            );
            channel.setDescription("Controls for media playback");
            channel.setShowBadge(false);
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification() {
        MBLog.d(TAG, "Creating foreground service notification");
        // Get app notification icon for better branding
        int appIcon = getResources().getIdentifier(
            MediaBrowserModule.ANDROID_AUTO_NOTIFICATION_ICON_NAME,
            "drawable",
            getPackageName()
        );

        if (appIcon == 0) {
            appIcon = android.R.drawable.ic_media_play;
        }
        
        if (appIcon == android.R.drawable.ic_media_play && !didWarnMissingNotifIcon) {
            didWarnMissingNotifIcon = true;
            MBLog.e(TAG,
                "Android Auto notification icon drawable '" +
                MediaBrowserModule.ANDROID_AUTO_NOTIFICATION_ICON_NAME +
                "' not found in host app. Add it to android/app/src/main/res/drawable for correct branding."
            );
        }

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Android Auto")
            .setContentText("Connecting...")
            .setSmallIcon(appIcon)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build();
    }

    @Override
    public void onDestroy() {
        MBLog.v(TAG, "→ onDestroy()");
        stopForegroundAndSelf();
        cancelAutoStop();
        super.onDestroy();
    }

    private void scheduleAutoStop() {
        MBLog.d(TAG, "Scheduling auto-stop of foreground service in " + FOREGROUND_GRACE_MS + "ms");
        // Do NOT reset the timer on every event. Android Auto can dispatch frequent intents.
        // If we keep re-scheduling, this notification may never disappear.
        if (stopRunnable != null) {
            return;
        }

        stopRunnable = () -> {
            try {
                stopForegroundAndSelf();
            } catch (Throwable t) {
                MBLog.e(TAG, "Failed to auto-stop foreground service", t);
            }
        };
        mainHandler.postDelayed(stopRunnable, FOREGROUND_GRACE_MS);
    }

    private void cancelAutoStop() {
        if (stopRunnable != null) {
            mainHandler.removeCallbacks(stopRunnable);
            stopRunnable = null;
        }
    }

    private void stopForegroundAndSelf() {
        // NOTIFICATION_ID_MEDIA_BROWSER is MediaBrowserService.NOTIFICATION_ID (2005), the same
        // notification RNJWMediaService uses for the JWPlayer media-playback foreground service.
        // stopForeground(true) removes it, which demotes that service; it then cannot be
        // re-promoted from the background and gets killed with "Stopping service due to app idle",
        // cutting the app's network and stopping playback. This service is routinely stopped while
        // playback continues (headless task finish, auto-stop, app idle), so detach instead of
        // removing the shared notification whenever a JWPlayer instance is active.
        boolean hasActivePlayer = MediaBrowserService.checkHasActivePlayer();
        MBLog.d(TAG, "stopForegroundAndSelf: hasActivePlayer=" + hasActivePlayer);
        if (hasActivePlayer) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(Service.STOP_FOREGROUND_DETACH);
            }
            stopSelf();
            return;
        }
        stopForeground(true);
        stopSelf();
    }
}
