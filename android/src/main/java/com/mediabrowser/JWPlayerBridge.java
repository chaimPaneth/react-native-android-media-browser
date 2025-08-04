package com.mediabrowser;

import android.content.Context;
import android.util.Log;
import java.util.Map;
import java.lang.reflect.Method;

/**
 * Bridge between MediaBrowserService and JWPlayer for headless mode communication
 * Uses reflection to access JWPlayer classes since MediaBrowser can't directly depend on JWPlayer
 */
public class JWPlayerBridge {
    private static final String TAG = "JWPlayerBridge";
    private static JWPlayerBridge instance;
    private static final String JWPLAYER_HANDLER_CLASS = "com.jwplayer.rnjwplayer.JWPlayerNativePlaybackHandler";
    
    private JWPlayerBridge() {}
    
    public static synchronized JWPlayerBridge getInstance() {
        if (instance == null) {
            instance = new JWPlayerBridge();
        }
        return instance;
    }
    
    /**
     * Handle media item selection from MediaBrowserService
     * Uses reflection to call JWPlayer methods since we can't directly import JWPlayer classes
     */
    public void handleMediaItemSelected(Context context, String mediaId, String title, 
                                      String subtitle, String icon, Map<String, Object> extras) {
        try {
            // Check if JWPlayer is available first
            if (!isJWPlayerAvailable()) {
                return;
            }
            
            // Use reflection to access JWPlayer classes
            Class<?> handlerClass = Class.forName(JWPLAYER_HANDLER_CLASS);
            
            // Get the getInstance method
            Method getInstanceMethod = handlerClass.getMethod("getInstance", Context.class);
            Object handlerInstance = getInstanceMethod.invoke(null, context);
            
            // Get the handleHeadlessMediaSelection method
            Method handleMethod = handlerClass.getMethod("handleHeadlessMediaSelection", 
                String.class, String.class, String.class, String.class, Map.class);
            
            // Call the method
            handleMethod.invoke(handlerInstance, mediaId, title, subtitle, icon, extras);
            
        } catch (ClassNotFoundException e) {
            Log.w(TAG, "JWPlayer handler class not found - JWPlayer may not be available");
        } catch (Exception e) {
            Log.e(TAG, "Error handling media item selection via reflection", e);
        }
    }
    
    /**
     * Handle browsable item selection from MediaBrowserService
     */
    public void handleBrowsableItemSelected(Context context, String parentMediaId) {
        // For browsable items, we don't need to do anything special in JWPlayer
        // The MediaBrowser will handle loading the child items
    }
    
    /**
     * Control JWPlayer playback from MediaSession callbacks
     */
    public void playFromMediaSession(Context context) {
        try {
            if (!isJWPlayerAvailable()) {
                return;
            }
            
            Class<?> handlerClass = Class.forName(JWPLAYER_HANDLER_CLASS);
            Method getInstanceMethod = handlerClass.getMethod("getInstance", Context.class);
            Object handlerInstance = getInstanceMethod.invoke(null, context);
            
            Method playMethod = handlerClass.getMethod("playFromMediaSession");
            playMethod.invoke(handlerInstance);
            
        } catch (Exception e) {
            Log.e(TAG, "Error controlling JWPlayer play via bridge", e);
        }
    }
    
    public void pauseFromMediaSession(Context context) {
        try {
            if (!isJWPlayerAvailable()) {
                return;
            }
            
            Class<?> handlerClass = Class.forName(JWPLAYER_HANDLER_CLASS);
            Method getInstanceMethod = handlerClass.getMethod("getInstance", Context.class);
            Object handlerInstance = getInstanceMethod.invoke(null, context);
            
            Method pauseMethod = handlerClass.getMethod("pauseFromMediaSession");
            pauseMethod.invoke(handlerInstance);
            
        } catch (Exception e) {
            Log.e(TAG, "Error controlling JWPlayer pause via bridge", e);
        }
    }
    
    public void stopFromMediaSession(Context context) {
        try {
            if (!isJWPlayerAvailable()) {
                return;
            }
            
            Class<?> handlerClass = Class.forName(JWPLAYER_HANDLER_CLASS);
            Method getInstanceMethod = handlerClass.getMethod("getInstance", Context.class);
            Object handlerInstance = getInstanceMethod.invoke(null, context);
            
            Method stopMethod = handlerClass.getMethod("stopFromMediaSession");
            stopMethod.invoke(handlerInstance);
            
        } catch (Exception e) {
            Log.e(TAG, "Error controlling JWPlayer stop via bridge", e);
        }
    }
    
    /**
     * Check if JWPlayer is available in the classpath
     */
    public boolean isJWPlayerAvailable() {
        try {
            Class.forName(JWPLAYER_HANDLER_CLASS);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
