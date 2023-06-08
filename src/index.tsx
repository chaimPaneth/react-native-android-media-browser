import { NativeModules, Platform, DeviceEventEmitter, EmitterSubscription } from 'react-native';

// Accessing the native MediaBrowser module
const { MediaBrowser } = NativeModules;

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
interface MediaItem {
  id: string; // unique identifier for the media item
  title: string; // title of the media item
  subTitle: string; // subtitle of the media item
  icon: string; // icon for the media item
  playableOrBrowsable: 'PLAYABLE' | 'BROWSABLE'; // specifies if the item is directly playable or it's a container that can be browsed to reveal more media items
  children?: MediaItem[]; // if the item is 'BROWSABLE', it can have children media items
  groupTitle?: string; // title for the group this item belongs to
  browsableStyle?: ContentStyle; // style to be applied when the item is displayed in a browsable context
  playableStyle?: ContentStyle; // style to be applied when the item is displayed in a playable context
}

// Interface for a structure of media items. It defines the hierarchy of media items.
interface MediaItemsStructure {
  id: string; // unique identifier for the media structure
  root: MediaItem[]; // array of root media items
}

// Initial setup for listeners
let mediaItemSelectedListener: EmitterSubscription | null = null;
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
  onMediaItemSelected: (listener: EmitterSubscription) => {
  if (mediaItemSelectedListener) {
      mediaItemSelectedListener.remove();
    }
    mediaItemSelectedListener = DeviceEventEmitter.addListener(
      'onMediaItemSelected',
      listener,
    );
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
};

export default MediaBrowserWrapper;
