# react-native-android-media-browser

A React Native module that provides a Media Browser Service for Android, allowing you to create a custom media browsing experience for Android Auto and Android Automotive OS.

## Installation

1. Install the library using npm or yarn: `npm install react-native-android-media-browser` or `yarn add react-native-android-media-browser`.

2. Link the native module using `react-native link` (if you're using a version of React Native <= 0.59): `react-native link react-native-android-media-browser`.

## Usage

1. Import the `MediaBrowser` module in your JavaScript code:
```javascript
import MediaBrowser, {
    CONTENT_STYLE_LIST_ITEM,
    CONTENT_STYLE_GRID_ITEM,
    CONTENT_STYLE_CATEGORY_LIST_ITEM,
    CONTENT_STYLE_CATEGORY_GRID_ITEM
} from 'react-native-android-media-browser';
```

2. Define your media items hierarchy:
```javascript
const mediaItems = [
    {
        id: "ROOT",
        children: [
            {
                id: "Home",
                title: "Home",
                subTitle: "Welcome home!",
                icon: "home.png",
                browsableStyle: CONTENT_STYLE_GRID_ITEM,
                playableStyle: CONTENT_STYLE_LIST_ITEM,
                playableOrBrowsable: "BROWSABLE",
                children: [
                    // ... more nested media items
                ],
            },
            // ... other top-level media items
        ],
    },
];
```

3. Set the media items hierarchy in the MediaBrowser module:
```javascript
MediaBrowser.setMediaItems(mediaItems);
```

4. You can also add, delete, and update media items:
```javascript
MediaBrowser.addMediaItem(item);
MediaBrowser.deleteMediaItem(itemId);
MediaBrowser.updateMediaItem(item);
```

## License

MIT

