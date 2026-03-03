import { NativeModules, DeviceEventEmitter, EmitterSubscription, AppRegistry } from 'react-native';
import { warnIfMissingAndroidAutoNotificationIcon } from './androidAutoNotificationIcon';

// Accessing the native MediaBrowser module
const { MediaBrowser } = NativeModules;

// Store for the event handler function
let eventHandler: ((data: any) => void) | null = null;

// Guard: prevent duplicate AppRegistry.registerHeadlessTask calls.
// When Metro resolves the same module via different paths (e.g. package exports),
// or when registerEventHandler is called more than once, the second registration
// replaces the previous handler and React Native emits a noisy warning.
let headlessTaskRegistered = false;

// Constants defining different content styles.
// They might change the way how media items are displayed in the UI (as a list, grid, etc.)
export const CONTENT_STYLE_LIST_ITEM = 'CONTENT_STYLE_LIST_ITEM';
export const CONTENT_STYLE_GRID_ITEM = 'CONTENT_STYLE_GRID_ITEM';
export const CONTENT_STYLE_CATEGORY_LIST_ITEM = 'CONTENT_STYLE_CATEGORY_LIST_ITEM';
export const CONTENT_STYLE_CATEGORY_GRID_ITEM = 'CONTENT_STYLE_CATEGORY_GRID_ITEM';

// Type for content style. It can be any of the four content styles defined above.
export type ContentStyle =
  | typeof CONTENT_STYLE_LIST_ITEM
  | typeof CONTENT_STYLE_GRID_ITEM
  | typeof CONTENT_STYLE_CATEGORY_LIST_ITEM
  | typeof CONTENT_STYLE_CATEGORY_GRID_ITEM;

// Constants for different download statuses. It allows to communicate the download status of a media item.
export const EXTRA_DOWNLOAD_STATUS = 'android.media.extra.DOWNLOAD_STATUS';
export const STATUS_DOWNLOADED = 2;
export const STATUS_DOWNLOADING = 1;
export const STATUS_NOT_DOWNLOADED = 0;

// Constants for explicit media and advertisement. They can be used to mark media items as explicit or as an advertisement.
export const METADATA_KEY_IS_EXPLICIT = 'android.media.metadata.IS_EXPLICIT';
export const METADATA_VALUE_ATTRIBUTE_PRESENT = 1;

// Constants for completion status and percentage. They can be used to track and communicate the playback progress of a media item.
export const DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS = 'android.media.description.extra.COMPLETION_STATUS';
export const DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED = 0;
export const DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED = 1;
export const DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED = 2;
export const DESCRIPTION_EXTRAS_KEY_COMPLETION_PERCENTAGE = 'android.media.description.extra.COMPLETION_PERCENTAGE';

// Constants for connection types. They can be used to understand the current connection context of the media playback.
export const CONNECTION_TYPE_NOT_CONNECTED = 0;
export const CONNECTION_TYPE_NATIVE = 1; // natively running on a head unit (Android Automotive OS).
export const CONNECTION_TYPE_PROJECTION = 2; // connected to a car head unit by projecting to it (Android Auto).

// Interface for a media item
export interface MediaItem {
  id?: string | undefined | null; // unique identifier for the media item
  title?: string | undefined | null; // title of the media item
  subTitle?: string | undefined | null; // subtitle of the media item
  icon?: string | undefined | null; // icon for the media item
  playableOrBrowsable: 'PLAYABLE' | 'BROWSABLE'; // specifies if the item is directly playable or it's a container that can be browsed to reveal more media items
  children?: MediaItem[] | undefined | null; // if the item is 'BROWSABLE', it can have children media items
  groupTitle?: string | undefined | null; // title for the group this item belongs to
  browsableStyle?: ContentStyle | undefined | null; // style to be applied when the item is displayed in a browsable context
  playableStyle?: ContentStyle | undefined | null; // style to be applied when the item is displayed in a playable context
  extras?: { [key: string]: string | number } | undefined | null; // extra information associated with the media item
}

// Interface for a structure of media items. It defines the hierarchy of media items.
interface MediaItemsStructure {
  id: string; // unique identifier for the media structure
  root: MediaItem[]; // array of root media items
}

// Initial setup for listeners
let mediaItemSelectedListener: EmitterSubscription | null = null;
let mediaItemBrowseListener: EmitterSubscription | null = null;
let carConnectedListener: EmitterSubscription | null = null;

// Wrapper for the MediaBrowser native module
const MediaBrowserWrapper = {
  ...MediaBrowser,
  // Method to set media items. It converts the media items to a JSON string before passing to the native module.
  setMediaItems: (items: MediaItemsStructure) => {
    MediaBrowser?.setMediaItems(items);
  },
  // Method to add a new media item. It converts the media item to a JSON string before passing to the native module.
  pushMediaItem: (parentId: string, newItem: MediaItem) => {
    MediaBrowser?.pushMediaItem(parentId, newItem);
  },
  // Method to delete a media item by its id.
  deleteMediaItem: (itemId: string) => {
    MediaBrowser?.deleteMediaItem(itemId);
  },
  // Method to update a media item. It converts the updated media item to a JSON string before passing to the native module.
  updateMediaItem: (updatedItem: MediaItem) => {
    MediaBrowser?.updateMediaItem(updatedItem);
  },
  // Method to register a listener for media item selection events.
  onMediaItemSelected: (listener: (item: MediaItem) => void) => {
    if (mediaItemSelectedListener) {
      mediaItemSelectedListener.remove();
    }
    mediaItemSelectedListener = DeviceEventEmitter.addListener(
      'onMediaItemSelected',
      listener,
    );
  },
  // Method to register a listener for browsing events.
  onBrowsableItemSelected: (listener: (item: MediaItem) => void) => {
    if (mediaItemBrowseListener)  {
      mediaItemBrowseListener.remove();
    }
    mediaItemBrowseListener = DeviceEventEmitter.addListener('onBrowsableItemSelected', listener);
  },
  // Method to update multiple media items under a specific parent ID. 
  // It converts the updated items to a JSON string before passing to the native module.
  updateMediaItems: (parentId: string, updatedItems: MediaItem[], replace: boolean) => {
    MediaBrowser?.updateMediaItems(parentId, updatedItems, replace);
  },
  // Method to register a listener for car connection change events.
  onCarConnectionChanged: (listener: EmitterSubscription) => {
    if (carConnectedListener) {
      carConnectedListener.remove();
    }
    carConnectedListener = DeviceEventEmitter.addListener(
      'onCarConnectionChanged',
      listener,
    );
  },

  /**
   * Syncs the playback speed to the Android Auto MediaSession.
   * Call this whenever the in-app player speed changes so the custom action
   * button (icon + label) stays in sync with the actual playback rate.
   */
  setPlaybackSpeed: (speed: number) => {
    MediaBrowser?.setPlaybackSpeed(speed);
  },

  /**
   * Returns the current playback speed from the Android Auto MediaSession.
   * The native side retains the speed across headless sessions, so this can
   * be used to resync the app UI after a headless Android Auto session.
   */
  getPlaybackSpeed: (): Promise<number> => {
    if (MediaBrowser?.getPlaybackSpeed) {
      return MediaBrowser.getPlaybackSpeed();
    }
    return Promise.resolve(1);
  },

  /**
   * Registers a listener for speed-change events that originate from the
   * Android Auto UI (e.g. the user tapping the speed custom action button).
   * Returns an EmitterSubscription that should be removed on cleanup.
   *
   * @deprecated Use {@link onSpeedButtonPressed} instead – the native side no
   * longer computes the next speed; it only signals the button press.
   */
  onSpeedChange: (listener: (event: { speed: number }) => void): EmitterSubscription => {
    return DeviceEventEmitter.addListener('onSpeedChange', listener);
  },

  /**
   * Registers a listener for the Android Auto speed custom-action button press.
   * The native side does NOT compute the next speed – the JS handler should call
   * {@link getNextSpeed} from the shared speed utility and then invoke
   * {@link setPlaybackSpeed} with the result.
   */
  onSpeedButtonPressed: (listener: () => void): EmitterSubscription => {
    return DeviceEventEmitter.addListener('onSpeedButtonPressed', listener);
  },
  
  // Method to register event handler for headless JS tasks
  registerEventHandler: (handler: (data: any) => void | Promise<void>) => {
    // Always update the handler so the latest caller wins
    eventHandler = handler;

    // One-time dev warning so engineers remember to add the Android Auto notif icon in the parent app
    void warnIfMissingAndroidAutoNotificationIcon();

    // Only register the headless task with AppRegistry once.
    // Subsequent calls update `eventHandler` above (the closure captures the
    // mutable variable), so the newest handler is always invoked.
    if (!headlessTaskRegistered) {
      const headlessTask = async (data: any) => {
        try {
          if (eventHandler) {
            const result = eventHandler(data);
            // If the handler returns a promise, await it
            if (result && typeof result.then === 'function') {
              await result;
            }
          }
          return Promise.resolve();
        } catch (error) {
          console.error('Error in MediaBrowser headless task:', error);
          return Promise.resolve(); // Always return a resolved promise
        }
      };

      AppRegistry.registerHeadlessTask('MediaBrowserService', () => headlessTask);
      headlessTaskRegistered = true;
    }
    
    // Call native method to enable headless task registration
    MediaBrowser?.registerEventHandler();
  },
};

export default MediaBrowserWrapper;

// Export the registerEventHandler function separately for easier access
export const { registerEventHandler } = MediaBrowserWrapper;
