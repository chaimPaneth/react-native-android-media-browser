package com.mediabrowser;

import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.media.session.MediaButtonReceiver;
import android.util.Log;

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

import android.os.Build;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import androidx.core.app.NotificationCompat;

public class MediaBrowserHeadlessService extends HeadlessJsTaskService {

    private static final String TAG = "MediaBrowserHeadlessService";
    public static final String ACTION_MEDIA_ITEM_SELECTED = "com.mediabrowser.ACTION_MEDIA_ITEM_SELECTED";
    public static final String ACTION_BROWSABLE_ITEM_SELECTED = "com.mediabrowser.ACTION_BROWSABLE_ITEM_SELECTED";
    public static final String ACTION_CAR_CONNECTION_CHANGED = "com.mediabrowser.ACTION_CAR_CONNECTION_CHANGED";

    private static final int NOTIFICATION_ID_MEDIA_BROWSER = 1;
    private static final long EVENT_THROTTLE_MS = 5000; // Throttle events to max once per 5 seconds
    private long lastEventEmitTime = 0;

    @Nullable
    @Override
    protected HeadlessJsTaskConfig getTaskConfig(Intent intent) {
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
        super.onHeadlessJsTaskFinish(taskId);
        stopSelf(taskId);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();
        startForeground(NOTIFICATION_ID_MEDIA_BROWSER, createNotification());

        if (intent != null) {
            String action = intent.getAction();
            if (action != null && (action.equals(ACTION_MEDIA_ITEM_SELECTED) ||
                                action.equals(ACTION_BROWSABLE_ITEM_SELECTED) ||
                                action.equals(ACTION_CAR_CONNECTION_CHANGED))) {
                ReactContext existingContext = MediaItemsStore.getInstance().getReactApplicationContext();
                boolean hasActiveReact = existingContext != null && existingContext.hasActiveReactInstance();

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
                        Log.e(TAG, "[onStartCommand] Error emitting event", e);
                    }
                }
                
                // ALWAYS run headless task regardless of React state
                // This ensures setupMediaBrowser() runs in ALL cases
                super.onStartCommand(intent, flags, startId);
                return START_STICKY;
            }
        }
        
        return START_NOT_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                "MediaBrowserHeadless",
                "Media Browser Background",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Handles Android Auto connection in background");
            channel.setShowBadge(false);
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification() {
        return new NotificationCompat.Builder(this, "MediaBrowserHeadless")
            .setContentTitle("Android Auto")
            .setContentText("Initializing...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(false)
            .build();
    }

    @Override
    public void onDestroy() {
        stopForeground(true);
        super.onDestroy();
    }
}
