# Consumer ProGuard rules for react-native-android-media-browser (OU headless fork).
#
# Everything kept here is reached by REFLECTION from the JWPlayer fork
# (@jwplayer/jwplayer-react-native), which deliberately has no compile-time
# dependency on this library so a player-only app can build without it.
# R8 cannot see a reflective call, so without these rules it renames or removes
# these members and the bridge fails at runtime in any minified consumer app.
#
# Every entry below was verified to exist in this library's source. Keep this
# file in sync when a reflected member is renamed, moved, or deleted.

# The fork probes for this library with
#   Class.forName("com.mediabrowser.MediaBrowserService", false, loader)
# to decide whether to share this library's MediaSession or create a private
# one. The probe compares by NAME, so the name must survive minification even
# though the manifest <service> entry already keeps the class itself.
-keepnames class com.mediabrowser.MediaBrowserService

# Reflected by the fork from RNJWMediaSessionHelper, RNJWMediaService,
# RNJWPlayerView and JWPlayerNativePlaybackHandler.
-keep class com.mediabrowser.MediaBrowserService {
    public static *** getInstance(...);
    public static *** updateSeekPosition(...);
    public static *** sendMediaItemToReactNative(...);
    public static *** setPlaybackSpeedFromSync(...);
    public static *** onJwPlayerNotificationPosted(...);
    public static *** getSpeedCustomAction(...);
    public static *** sendPlaylistCompleteToReactNative(...);
    public static *** isSkipPending(...);
    public static *** sendSkipToNextEventToReactNative(...);
    public static *** sendSkipToPreviousEventToReactNative(...);
    public *** handleSpeedAction(...);
}

# The single shared MediaSessionCompat holder. RNJWSharedMediaSession resolves
# the session through getInstance(Context); release() is this library's own
# teardown path and is kept alongside it so the contract stays whole.
-keep class com.mediabrowser.MediaSessionSingleton {
    public static *** getInstance(...);
    public static *** release(...);
}

# Reached by the fork to recover a ReactApplicationContext when no player is up.
-keep class com.mediabrowser.MediaItemsStore {
    public static *** getInstance(...);
    public *** getReactApplicationContext(...);
}

# Resume-position lookup used when playback starts from a browse selection.
-keep class com.mediabrowser.MediaItemsResumeProvider {
    public static *** getResumePositionMs(...);
}
