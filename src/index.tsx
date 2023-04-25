import { NativeModules, Platform } from 'react-native';

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
};

export default Platform.OS === 'android' ? MediaBrowserWrapper : null;
