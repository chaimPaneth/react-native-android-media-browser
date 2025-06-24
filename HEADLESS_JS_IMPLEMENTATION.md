# MediaBrowser Headless JS Implementation

This implementation adds headless JS support to the react-native-android-media-browser package, similar to react-native-track-player. This allows the JS layer to wake up when Android Auto connects, even when the app is in a killed state.

## Changes Made

### Android Native Changes

1. **MediaBrowserHeadlessService.java** - New HeadlessJsTaskService that handles events when the app is in background/killed state
2. **MediaBrowserService.java** - Updated to trigger headless service for events
3. **MediaBrowserModule.java** - Added support for registering event handlers
4. **AndroidManifest.xml** - Added headless service registration with proper permissions

### JavaScript Changes

1. **index.tsx** - Added `registerEventHandler` method similar to react-native-track-player

## Usage in all-mishna-app

### 1. Register the headless task in your main index.js/index.ts file:

```javascript
import { AppRegistry } from 'react-native';
import MediaBrowser from 'react-native-android-media-browser';
import App from './App';

// Register your main app
AppRegistry.registerComponent('YourAppName', () => App);

// Register the headless task for MediaBrowser
MediaBrowser.registerEventHandler(async (data) => {
  console.log('MediaBrowser headless event:', data);
  
  switch (data.type) {
    case 'media-item-selected':
      // Handle media item selection from Android Auto
      console.log('Media item selected:', data.id, data.title);
      
      // You can trigger your JW Player here
      // Example: start playback, update state, etc.
      break;
      
    case 'browsable-item-selected':
      // Handle browsable item selection
      console.log('Browsable item selected:', data.id);
      break;
      
    case 'car-connection-changed':
      // Handle Android Auto connection changes
      console.log('Car connection changed:', data.connectionType);
      // 0 = NOT_CONNECTED, 1 = NATIVE, 2 = PROJECTION
      break;
  }
});
```

### 2. In your main app component, you can still use the regular event listeners:

```javascript
import MediaBrowser from 'react-native-android-media-browser';

// Regular event listeners (work when app is active)
MediaBrowser.onMediaItemSelected((item) => {
  console.log('Active app - media item selected:', item);
  // Trigger JW Player
});

MediaBrowser.onCarConnectionChanged((connectionType) => {
  console.log('Active app - car connection changed:', connectionType);
});
```

## Key Benefits

1. **Background Support**: Events are handled even when the app is backgrounded
2. **Killed State Support**: JS layer wakes up when Android Auto connects to a killed app
3. **New Architecture Compatible**: Works with React Native's new architecture
4. **JW Player Integration**: Can trigger JW Player from headless events
5. **Android Auto Ready**: Properly handles Android Auto connectivity scenarios

## Event Types

- `media-item-selected`: Fired when a media item is selected from Android Auto
- `browsable-item-selected`: Fired when a browsable folder is selected
- `car-connection-changed`: Fired when Android Auto connection state changes

## Integration with JW Player

You can now reliably trigger JW Player from the headless events, ensuring your media playback works even when the user connects to Android Auto without opening the app first.

The headless task will wake up the JS bundle, allowing you to:
- Initialize your media session
- Prepare JW Player
- Set up the connection between MediaBrowser and JW Player
- Handle media item selections and start playback

This solves the issue where Android Auto couldn't control your app when it was in a killed state.
