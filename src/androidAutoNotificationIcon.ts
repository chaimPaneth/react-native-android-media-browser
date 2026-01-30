import { NativeModules, Platform } from 'react-native';

const { MediaBrowser } = NativeModules;

// single default fallback for older native builds
const ICON_NAME_DEFAULT = 'ic_stat_notify';

let didWarn = false;

const getIconName = async (): Promise<string> => {
  try {
    const fn = (MediaBrowser as any)?.getAndroidAutoNotificationIconName;
    if (typeof fn === 'function') {
      const name = await fn();
      if (typeof name === 'string' && name.trim()) return name.trim();
    }
  } catch {}
  return ICON_NAME_DEFAULT;
};

export const warnIfMissingAndroidAutoNotificationIcon = async (): Promise<void> => {
  if (!__DEV__ || Platform.OS !== 'android') return;
  if (didWarn) return;
  didWarn = true;

  try {
    const hasCheck = typeof (MediaBrowser as any)?.hasAndroidAutoNotificationIcon === 'function';
    if (!hasCheck) {
      console.warn(
        '⚠️  WARNING: Android Auto icon check unavailable (native method missing). ' +
          'Add "ic_stat_notify" to android/app/src/main/res/drawable.'
      );
      return;
    }

    const exists = await (MediaBrowser as any).hasAndroidAutoNotificationIcon();
    if (exists) return;

    const iconName = await getIconName();
    console.warn(
      [
        ' ',
        '⚠️  WARNING: Android Auto notification icon missing!',
        '',
        '[react-native-android-media-browser] requires a small notification icon.',
        `File name: ${iconName}`,
        '',
        'Fix:',
        '  Add ONE of these files to the host app:',
        `   - android/app/src/main/res/drawable/${iconName}.xml`,
        `   - android/app/src/main/res/drawable/${iconName}.png`,
      ].join('\n')
    );
  } catch {
    console.warn(
      '⚠️  WARNING: Android Auto could not verify notification icon. ' +
        'Ensure "ic_stat_notify" exists in android/app/src/main/res/drawable.'
    );
  }
};