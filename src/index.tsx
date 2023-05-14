import { NativeModules, Platform, DeviceEventEmitter, EmitterSubscription } from 'react-native';

const { MediaBrowser } = NativeModules;

export const CONTENT_STYLE_LIST_ITEM = 'CONTENT_STYLE_LIST_ITEM';
export const CONTENT_STYLE_GRID_ITEM = 'CONTENT_STYLE_GRID_ITEM';
export const CONTENT_STYLE_CATEGORY_LIST_ITEM = 'CONTENT_STYLE_CATEGORY_LIST_ITEM';
export const CONTENT_STYLE_CATEGORY_GRID_ITEM = 'CONTENT_STYLE_CATEGORY_GRID_ITEM';

export type ContentStyle =
  | typeof CONTENT_STYLE_LIST_ITEM
  | typeof CONTENT_STYLE_GRID_ITEM
  | typeof CONTENT_STYLE_CATEGORY_LIST_ITEM
  | typeof CONTENT_STYLE_CATEGORY_GRID_ITEM;

export const EXTRA_DOWNLOAD_STATUS = 'android.media.extra.DOWNLOAD_STATUS';
export const STATUS_DOWNLOADED = 2;
export const STATUS_DOWNLOADING = 1;
export const STATUS_NOT_DOWNLOADED = 0;
export const METADATA_KEY_IS_EXPLICIT = 'android.media.metadata.IS_EXPLICIT';
export const METADATA_VALUE_ATTRIBUTE_PRESENT = 1;
export const DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS = 'android.media.description.extra.COMPLETION_STATUS';
export const DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED = 0;
export const DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED = 1;
export const DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED = 2;
export const DESCRIPTION_EXTRAS_KEY_COMPLETION_PERCENTAGE = 'android.media.description.extra.COMPLETION_PERCENTAGE';

interface MediaItem {
  id: string;
  title: string;
  subTitle: string;
  icon: string;
  playableOrBrowsable: 'PLAYABLE' | 'BROWSABLE';
  children?: MediaItem[];
  groupTitle?: string;
  browsableStyle?: ContentStyle;
  playableStyle?: ContentStyle;
}

interface MediaItemsStructure {
  id: string;
  root: MediaItem[];
}

let mediaItemSelectedListener: EmitterSubscription | null = null;

const MediaBrowserWrapper = {
  ...MediaBrowser,
  setMediaItems: (items: MediaItemsStructure) => {
    const convertedItems = JSON.stringify(items);
    MediaBrowser.setMediaItems(convertedItems);
  },
  pushMediaItem: (parentId: string, newItem: MediaItem) => {
    MediaBrowser.pushMediaItem(parentId, JSON.stringify(newItem));
  },
  deleteMediaItem: (itemId: string) => {
    MediaBrowser.deleteMediaItem(itemId);
  },
  updateMediaItem: (updatedItem: MediaItem) => {
    MediaBrowser.updateMediaItem(JSON.stringify(updatedItem));
  },
  onMediaItemSelected: (listener: EmitterSubscription) => {
    if (mediaItemSelectedListener) {
      mediaItemSelectedListener.remove();
    }

    mediaItemSelectedListener = DeviceEventEmitter.addListener(
      'onMediaItemSelected',
      listener,
    );
  },
  onCarConnectionChanged: (listener: EmitterSubscription) => {
    if (mediaItemSelectedListener) {
      mediaItemSelectedListener.remove();
    }

    mediaItemSelectedListener = DeviceEventEmitter.addListener(
      'onCarConnectionChanged',
      listener,
    );
  },
};

export default Platform.OS === 'android' ? MediaBrowserWrapper : null;
