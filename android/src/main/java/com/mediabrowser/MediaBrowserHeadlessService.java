package com.mediabrowser;

import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v4.media.session.MediaButtonReceiver;
import android.util.Log;

import com.facebook.react.HeadlessJsTaskService;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.jstasks.HeadlessJsTaskConfig;

import javax.annotation.Nullable;

/**
 * HeadlessJsTaskService for MediaBrowser to handle events when app is in background or killed state
 * Compatible with React Native New Architecture
 */
public class MediaBrowserHeadlessService extends HeadlessJsTaskService {

    private static final String TAG = "MediaBrowserHeadless";
    public static final String ACTION_MEDIA_ITEM_SELECTED = "com.mediabrowser.ACTION_MEDIA_ITEM_SELECTED";
    public static final String ACTION_BROWSABLE_ITEM_SELECTED = "com.mediabrowser.ACTION_BROWSABLE_ITEM_SELECTED";
    public static final String ACTION_CAR_CONNECTION_CHANGED = "com.mediabrowser.ACTION_CAR_CONNECTION_CHANGED";

    @Nullable
    @Override
    protected HeadlessJsTaskConfig getTaskConfig(Intent intent) {
        Log.d(TAG, "getTaskConfig called with intent: " + (intent != null ? intent.getAction() : "null"));
        
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
                    Log.w(TAG, "Unknown action: " + action);
                    return null;
            }
        } else {
            Log.w(TAG, "Intent action is null");
            return null;
        }
        
        // Return headless task config with longer timeout for Android Auto scenarios
        return new HeadlessJsTaskConfig("MediaBrowserService", data, 120000, true);
    }

    @Override
    public void onHeadlessJsTaskFinish(int taskId) {
        Log.d(TAG, "onHeadlessJsTaskFinish called with taskId: " + taskId);
        // Overridden to prevent the service from being terminated immediately
        // This allows the service to continue running for Android Auto connectivity
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand called with action: " + (intent != null ? intent.getAction() : "null"));
        
        if (intent != null) {
            String action = intent.getAction();
            if (action != null && (action.equals(ACTION_MEDIA_ITEM_SELECTED) || 
                                  action.equals(ACTION_BROWSABLE_ITEM_SELECTED) ||
                                  action.equals(ACTION_CAR_CONNECTION_CHANGED))) {
                super.onStartCommand(intent, flags, startId);
                return START_STICKY;
            }
        }
        
        return START_NOT_STICKY;
    }
    
    @Override
    public void onDestroy() {
        Log.d(TAG, "MediaBrowserHeadlessService destroyed");
        super.onDestroy();
    }
}
