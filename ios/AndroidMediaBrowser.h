
#ifdef RCT_NEW_ARCH_ENABLED
#import "RNAndroidMediaBrowserSpec.h"

@interface AndroidMediaBrowser : NSObject <NativeAndroidMediaBrowserSpec>
#else
#import <React/RCTBridgeModule.h>

@interface AndroidMediaBrowser : NSObject <RCTBridgeModule>
#endif

@end
