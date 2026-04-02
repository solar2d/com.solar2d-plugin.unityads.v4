//
//  UnityAdsPlugin.mm
//  UnityAds Plugin (LevelPlay Backend)
//
//  Copyright (c) 2016 Corona Labs Inc. All rights reserved.
//

#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>

#import "CoronaRuntime.h"
#import "CoronaAssert.h"
#import "CoronaEvent.h"
#import "CoronaLua.h"
#import "CoronaLuaIOS.h"
#import "CoronaLibrary.h"

#import "UnityAdsPlugin.h"
#import <IronSource/IronSource.h>
#import <AppTrackingTransparency/AppTrackingTransparency.h>

// some macros to make life easier, and code more readable
#define UTF8StringWithFormat(format, ...) [[NSString stringWithFormat:format, ##__VA_ARGS__] UTF8String]
#define UTF8IsEqual(utf8str1, utf8str2) (strcmp(utf8str1, utf8str2) == 0)
#define MsgFormat(format, ...) [NSString stringWithFormat:format, ##__VA_ARGS__]

// ----------------------------------------------------------------------------
// Plugin Constants
// ----------------------------------------------------------------------------

#define PLUGIN_NAME        "plugin.unityads.v4"
#define PLUGIN_VERSION     "2.0.0"

static const char EVENT_NAME[]    = "adsRequest";
static const char PROVIDER_NAME[] = "unityads";

// ad types
static const char TYPE_UNITYAD[] = "unityAd";

// event phases
static NSString * const PHASE_INIT      = @"init";
static NSString * const PHASE_LOADED    = @"loaded";
static NSString * const PHASE_FAILED    = @"failed";
static NSString * const PHASE_SKIPPED   = @"skipped";
static NSString * const PHASE_CLICKED   = @"clicked";
static NSString * const PHASE_COMPLETED = @"completed";
static NSString * const PHASE_DISPLAYED = @"displayed";

// missing Corona event keys
static NSString * const CORONA_EVENT_DATA_KEY = @"data";

// data keys
static NSString * const DATA_PLACEMENT_ID_KEY = @"placementId";
static NSString * const DATA_ERROR_CODE_KEY   = @"errorCode";
static NSString * const DATA_ERROR_MSG_KEY    = @"errorMsg";

// message constants
static NSString * const ERROR_MSG   = @"ERROR: ";
static NSString * const WARNING_MSG = @"WARNING: ";

// Store ad objects keyed by adUnitId
static NSMutableDictionary *adObjects = nil;
// Track loaded ad unit IDs
static NSMutableSet *loadedIds = nil;
// Track rewarded ad unit IDs (for skipped vs completed)
static NSMutableSet *rewardedIds = nil;

// ----------------------------------------------------------------------------
// plugin class and delegate definitions
// ----------------------------------------------------------------------------

// Forward declarations for listener classes
@class CoronaInterstitialAdDelegate;
@class CoronaRewardedAdDelegate;

// ----------------------------------------------------------------------------

class UnityAdsPlugin
{
  public:
    typedef UnityAdsPlugin Self;

  public:
    static const char kName[];

  public:
    static int Open( lua_State *L );
    static int Finalizer( lua_State *L );
    static Self *ToLibrary( lua_State *L );

  protected:
    UnityAdsPlugin();
    bool Initialize( void *platformContext );

  public:
    static int init( lua_State *L );
    static int isLoaded( lua_State *L );
    static int show( lua_State *L );
    static int load( lua_State *L );
    static int setHasUserConsent(lua_State *L);
    static int setPersonalizedAds(lua_State *L);
    static int setPrivacyMode( lua_State *L );

  public: // helper functions used by delegates
    static NSString *getJSONStringForPlacement(NSString *placementId, int errorCode, NSString *errorMsg);

  private: // internal helper functions
    static void logMsg(lua_State *L, NSString *msgType,  NSString *errorMsg);
    static bool isSDKInitialized(lua_State *L);

  private:
    NSString *functionSignature;
    UIViewController *coronaViewController;

  public:
    static CoronaLuaRef coronaListener;
    static id<CoronaRuntime> coronaRuntime;
};

const char UnityAdsPlugin::kName[] = PLUGIN_NAME;
CoronaLuaRef UnityAdsPlugin::coronaListener = NULL;
id<CoronaRuntime> UnityAdsPlugin::coronaRuntime = NULL;

// ----------------------------------------------------------------------------
// Interstitial Ad Delegate
// ----------------------------------------------------------------------------

@interface CoronaInterstitialAdDelegate : NSObject <LPMInterstitialAdDelegate>
@property (nonatomic, strong) NSString *adUnitId;
- (instancetype)initWithAdUnitId:(NSString *)adUnitId;
@end

@implementation CoronaInterstitialAdDelegate

- (instancetype)initWithAdUnitId:(NSString *)adUnitId {
    if (self = [super init]) {
        self.adUnitId = adUnitId;
    }
    return self;
}

- (void)dispatchLuaEvent:(NSDictionary *)dict {
    [[NSOperationQueue mainQueue] addOperationWithBlock:^{
        lua_State *L = UnityAdsPlugin::coronaRuntime.L;
        CoronaLuaRef listener = UnityAdsPlugin::coronaListener;
        bool hasErrorKey = false;

        CoronaLuaNewEvent(L, EVENT_NAME);

        for (NSString *key in dict) {
            CoronaLuaPushValue(L, [dict valueForKey:key]);
            lua_setfield(L, -2, key.UTF8String);

            if (!hasErrorKey) {
                hasErrorKey = [key isEqualToString:@(CoronaEventIsErrorKey())];
            }
        }

        if (!hasErrorKey) {
            lua_pushboolean(L, false);
            lua_setfield(L, -2, CoronaEventIsErrorKey());
        }

        lua_pushstring(L, PROVIDER_NAME);
        lua_setfield(L, -2, CoronaEventProviderKey());

        CoronaLuaDispatchEvent(L, listener, 0);
    }];
}

- (void)didLoadAdWithAdInfo:(LPMAdInfo *)adInfo {
    [loadedIds addObject:self.adUnitId];
    NSDictionary *coronaEvent = @{
        @(CoronaEventPhaseKey()) : PHASE_LOADED,
        @(CoronaEventTypeKey()) : @(TYPE_UNITYAD),
        CORONA_EVENT_DATA_KEY : UnityAdsPlugin::getJSONStringForPlacement(self.adUnitId, -1, nil)
    };
    [self dispatchLuaEvent:coronaEvent];
}

- (void)didFailToLoadAdWithAdUnitId:(NSString *)adUnitId error:(NSError *)error {
    [loadedIds removeObject:self.adUnitId];
    [adObjects removeObjectForKey:self.adUnitId];
    NSDictionary *coronaEvent = @{
        @(CoronaEventPhaseKey()) : PHASE_FAILED,
        @(CoronaEventIsErrorKey()) : @(true),
        @(CoronaEventTypeKey()) : @(TYPE_UNITYAD),
        @(CoronaEventResponseKey()) : error.localizedDescription ?: @"Load failed",
        CORONA_EVENT_DATA_KEY : UnityAdsPlugin::getJSONStringForPlacement(self.adUnitId, (int)error.code, error.localizedDescription)
    };
    [self dispatchLuaEvent:coronaEvent];
}

- (void)didDisplayAdWithAdInfo:(LPMAdInfo *)adInfo {
    [loadedIds removeObject:self.adUnitId];
    NSDictionary *coronaEvent = @{
        @(CoronaEventPhaseKey()) : PHASE_DISPLAYED,
        @(CoronaEventTypeKey()) : @(TYPE_UNITYAD),
        CORONA_EVENT_DATA_KEY : UnityAdsPlugin::getJSONStringForPlacement(self.adUnitId, -1, nil)
    };
    [self dispatchLuaEvent:coronaEvent];
}

- (void)didFailToDisplayAdWithAdInfo:(LPMAdInfo *)adInfo error:(NSError *)error {
    [loadedIds removeObject:self.adUnitId];
    [adObjects removeObjectForKey:self.adUnitId];
    NSDictionary *coronaEvent = @{
        @(CoronaEventPhaseKey()) : PHASE_FAILED,
        @(CoronaEventIsErrorKey()) : @(true),
        @(CoronaEventTypeKey()) : @(TYPE_UNITYAD),
        @(CoronaEventResponseKey()) : error.localizedDescription ?: @"Display failed",
        CORONA_EVENT_DATA_KEY : UnityAdsPlugin::getJSONStringForPlacement(self.adUnitId, (int)error.code, error.localizedDescription)
    };
    [self dispatchLuaEvent:coronaEvent];
}

- (void)didClickAdWithAdInfo:(LPMAdInfo *)adInfo {
    NSDictionary *coronaEvent = @{
        @(CoronaEventPhaseKey()) : PHASE_CLICKED,
        @(CoronaEventTypeKey()) : @(TYPE_UNITYAD),
        CORONA_EVENT_DATA_KEY : UnityAdsPlugin::getJSONStringForPlacement(self.adUnitId, -1, nil)
    };
    [self dispatchLuaEvent:coronaEvent];
}

- (void)didCloseAdWithAdInfo:(LPMAdInfo *)adInfo {
    [adObjects removeObjectForKey:self.adUnitId];
    NSDictionary *coronaEvent = @{
        @(CoronaEventPhaseKey()) : PHASE_COMPLETED,
        @(CoronaEventTypeKey()) : @(TYPE_UNITYAD),
        CORONA_EVENT_DATA_KEY : UnityAdsPlugin::getJSONStringForPlacement(self.adUnitId, -1, nil)
    };
    [self dispatchLuaEvent:coronaEvent];
}

- (void)didChangeAdInfo:(LPMAdInfo *)adInfo {
    // Not mapped to existing API
}

@end

// ----------------------------------------------------------------------------
// Rewarded Ad Delegate
// ----------------------------------------------------------------------------

@interface CoronaRewardedAdDelegate : NSObject <LPMRewardedAdDelegate>
@property (nonatomic, strong) NSString *adUnitId;
- (instancetype)initWithAdUnitId:(NSString *)adUnitId;
@end

@implementation CoronaRewardedAdDelegate

- (instancetype)initWithAdUnitId:(NSString *)adUnitId {
    if (self = [super init]) {
        self.adUnitId = adUnitId;
    }
    return self;
}

- (void)dispatchLuaEvent:(NSDictionary *)dict {
    [[NSOperationQueue mainQueue] addOperationWithBlock:^{
        lua_State *L = UnityAdsPlugin::coronaRuntime.L;
        CoronaLuaRef listener = UnityAdsPlugin::coronaListener;
        bool hasErrorKey = false;

        CoronaLuaNewEvent(L, EVENT_NAME);

        for (NSString *key in dict) {
            CoronaLuaPushValue(L, [dict valueForKey:key]);
            lua_setfield(L, -2, key.UTF8String);

            if (!hasErrorKey) {
                hasErrorKey = [key isEqualToString:@(CoronaEventIsErrorKey())];
            }
        }

        if (!hasErrorKey) {
            lua_pushboolean(L, false);
            lua_setfield(L, -2, CoronaEventIsErrorKey());
        }

        lua_pushstring(L, PROVIDER_NAME);
        lua_setfield(L, -2, CoronaEventProviderKey());

        CoronaLuaDispatchEvent(L, listener, 0);
    }];
}

- (void)didLoadAdWithAdInfo:(LPMAdInfo *)adInfo {
    [loadedIds addObject:self.adUnitId];
    NSDictionary *coronaEvent = @{
        @(CoronaEventPhaseKey()) : PHASE_LOADED,
        @(CoronaEventTypeKey()) : @(TYPE_UNITYAD),
        CORONA_EVENT_DATA_KEY : UnityAdsPlugin::getJSONStringForPlacement(self.adUnitId, -1, nil)
    };
    [self dispatchLuaEvent:coronaEvent];
}

- (void)didFailToLoadAdWithAdUnitId:(NSString *)adUnitId error:(NSError *)error {
    [loadedIds removeObject:self.adUnitId];
    [adObjects removeObjectForKey:self.adUnitId];
    NSDictionary *coronaEvent = @{
        @(CoronaEventPhaseKey()) : PHASE_FAILED,
        @(CoronaEventIsErrorKey()) : @(true),
        @(CoronaEventTypeKey()) : @(TYPE_UNITYAD),
        @(CoronaEventResponseKey()) : error.localizedDescription ?: @"Load failed",
        CORONA_EVENT_DATA_KEY : UnityAdsPlugin::getJSONStringForPlacement(self.adUnitId, (int)error.code, error.localizedDescription)
    };
    [self dispatchLuaEvent:coronaEvent];
}

- (void)didDisplayAdWithAdInfo:(LPMAdInfo *)adInfo {
    [loadedIds removeObject:self.adUnitId];
    [rewardedIds removeObject:self.adUnitId];
    NSDictionary *coronaEvent = @{
        @(CoronaEventPhaseKey()) : PHASE_DISPLAYED,
        @(CoronaEventTypeKey()) : @(TYPE_UNITYAD),
        CORONA_EVENT_DATA_KEY : UnityAdsPlugin::getJSONStringForPlacement(self.adUnitId, -1, nil)
    };
    [self dispatchLuaEvent:coronaEvent];
}

- (void)didFailToDisplayAdWithAdInfo:(LPMAdInfo *)adInfo error:(NSError *)error {
    [loadedIds removeObject:self.adUnitId];
    [adObjects removeObjectForKey:self.adUnitId];
    NSDictionary *coronaEvent = @{
        @(CoronaEventPhaseKey()) : PHASE_FAILED,
        @(CoronaEventIsErrorKey()) : @(true),
        @(CoronaEventTypeKey()) : @(TYPE_UNITYAD),
        @(CoronaEventResponseKey()) : error.localizedDescription ?: @"Display failed",
        CORONA_EVENT_DATA_KEY : UnityAdsPlugin::getJSONStringForPlacement(self.adUnitId, (int)error.code, error.localizedDescription)
    };
    [self dispatchLuaEvent:coronaEvent];
}

- (void)didClickAdWithAdInfo:(LPMAdInfo *)adInfo {
    NSDictionary *coronaEvent = @{
        @(CoronaEventPhaseKey()) : PHASE_CLICKED,
        @(CoronaEventTypeKey()) : @(TYPE_UNITYAD),
        CORONA_EVENT_DATA_KEY : UnityAdsPlugin::getJSONStringForPlacement(self.adUnitId, -1, nil)
    };
    [self dispatchLuaEvent:coronaEvent];
}

- (void)didRewardAdWithAdInfo:(LPMAdInfo *)adInfo reward:(LPMReward *)reward {
    [rewardedIds addObject:self.adUnitId];
}

- (void)didCloseAdWithAdInfo:(LPMAdInfo *)adInfo {
    [adObjects removeObjectForKey:self.adUnitId];
    NSMutableDictionary *coronaEvent = [@{
        @(CoronaEventTypeKey()) : @(TYPE_UNITYAD),
        CORONA_EVENT_DATA_KEY : UnityAdsPlugin::getJSONStringForPlacement(self.adUnitId, -1, nil)
    } mutableCopy];

    // If reward was received, it's completed; otherwise skipped
    if ([rewardedIds containsObject:self.adUnitId]) {
        coronaEvent[@(CoronaEventPhaseKey())] = PHASE_COMPLETED;
        [rewardedIds removeObject:self.adUnitId];
    } else {
        coronaEvent[@(CoronaEventPhaseKey())] = PHASE_SKIPPED;
    }

    [self dispatchLuaEvent:coronaEvent];
}

- (void)didChangeAdInfo:(LPMAdInfo *)adInfo {
    // Not mapped to existing API
}

@end

// ----------------------------------------------------------------------------
// helper functions
// ----------------------------------------------------------------------------

void
UnityAdsPlugin::logMsg(lua_State *L, NSString* msgType, NSString* errorMsg)
{
  Self *context = ToLibrary(L);

  if (context) {
    Self& library = *context;

    NSString *functionID = [library.functionSignature copy];
    if (functionID.length > 0) {
      functionID = [functionID stringByAppendingString:@", "];
    }

    CoronaLuaLogPrefix(L, [msgType UTF8String], UTF8StringWithFormat(@"%@%@", functionID, errorMsg));
  }
}

bool
UnityAdsPlugin::isSDKInitialized(lua_State *L)
{
  if (coronaListener == NULL) {
    logMsg(L, ERROR_MSG, @"unityads.init() must be called before calling other API methods");
    return false;
  }

  return true;
}

NSString *
UnityAdsPlugin::getJSONStringForPlacement(NSString *placementId, int errorCode, NSString *errorMsg)
{
    NSMutableDictionary *dataDictionary = [NSMutableDictionary new];

    if (placementId != nil) {
        dataDictionary[DATA_PLACEMENT_ID_KEY] = placementId;
    }

    if (errorCode >= 0) {
        dataDictionary[DATA_ERROR_CODE_KEY] = @(errorCode);
        dataDictionary[DATA_ERROR_MSG_KEY] = errorMsg ?: @"Unknown error";
    }

    NSData *jsonData = [NSJSONSerialization dataWithJSONObject:dataDictionary options:0 error:nil];

    return [[NSString alloc] initWithData:jsonData encoding:NSUTF8StringEncoding];
}

// ----------------------------------------------------------------------------
// plugin implementation
// ----------------------------------------------------------------------------

int
UnityAdsPlugin::Open( lua_State *L )
{
  const char kMetatableName[] = __FILE__;
  CoronaLuaInitializeGCMetatable( L, kMetatableName, Finalizer );

  void *platformContext = CoronaLuaGetContext( L );

  Self *library = new Self;

  if ( library->Initialize( platformContext ) ) {
    static const luaL_Reg kFunctions[] = {
      {"init", init},
      {"isLoaded", isLoaded},
      {"load", load},
      {"show", show},
      {"setHasUserConsent", setHasUserConsent},
      {"setPersonalizedAds", setPersonalizedAds},
      {"setPrivacyMode", setPrivacyMode},
      {NULL, NULL}
    };

    {
      CoronaLuaPushUserdata( L, library, kMetatableName );
      luaL_openlib( L, kName, kFunctions, 1 );
    }
  }

  return 1;
}

int
UnityAdsPlugin::Finalizer( lua_State *L )
{
  Self *library = (Self *)CoronaLuaToUserdata(L, 1);

  CoronaLuaDeleteRef(L, coronaListener);
  coronaListener = NULL;
  coronaRuntime = NULL;

  [adObjects removeAllObjects];
  [loadedIds removeAllObjects];
  [rewardedIds removeAllObjects];

  delete library;

  return 0;
}

UnityAdsPlugin*
UnityAdsPlugin::ToLibrary( lua_State *L )
{
  Self *library = (Self *)CoronaLuaToUserdata( L, lua_upvalueindex( 1 ) );
  return library;
}

UnityAdsPlugin::UnityAdsPlugin()
: coronaViewController( nil )
{
}

bool
UnityAdsPlugin::Initialize( void *platformContext )
{
  bool shouldInit = (! coronaViewController);

  if ( shouldInit ) {
    id<CoronaRuntime> runtime = (__bridge id<CoronaRuntime>)platformContext;
    coronaViewController = runtime.appViewController;
    coronaRuntime = runtime;

    functionSignature = @"";

    // Initialize storage
    adObjects = [NSMutableDictionary new];
    loadedIds = [NSMutableSet new];
    rewardedIds = [NSMutableSet new];
  }

  return shouldInit;
}

// [Lua] unityAds.init(listener, options)
int
UnityAdsPlugin::init( lua_State *L )
{
  Self *context = ToLibrary(L);

  if (! context) {
    return 0;
  }

  Self& library = *context;

  library.functionSignature = @"unityAds.init(listener, options)";

  // prevent init from being called twice
  if (coronaListener != NULL) {
    logMsg(L, WARNING_MSG, @"init() should only be called once");
    return 0;
  }

  int nargs = lua_gettop(L);
  if (nargs != 2) {
    logMsg(L, ERROR_MSG, MsgFormat(@"Expected 2 arguments, got %d", nargs));
    return 0;
  }

  const char *gameId = NULL;
  bool testMode = false;

  // Get listener key (required)
  if (CoronaLuaIsListener(L, 1, PROVIDER_NAME)) {
    coronaListener = CoronaLuaNewRef(L, 1);
  }
  else {
    logMsg(L, ERROR_MSG, MsgFormat(@"listener expected, got: %s", luaL_typename(L, 1)));
    return 0;
  }

  // check for options table (required)
  if (lua_type(L, 2) == LUA_TTABLE) {
    for (lua_pushnil(L); lua_next(L, 2) != 0; lua_pop(L, 1)) {
      const char *key = lua_tostring(L, -2);

      if (UTF8IsEqual(key, "gameId")) {
        if (lua_type(L, -1) == LUA_TSTRING) {
          gameId = lua_tostring(L, -1);
        }
        else {
          logMsg(L, ERROR_MSG, MsgFormat(@"options.gameId (string) expected, got: %s", luaL_typename(L, -1)));
          return 0;
        }
      }
      else if (UTF8IsEqual(key, "testMode")) {
        if (lua_type(L, -1) == LUA_TBOOLEAN) {
          testMode = lua_toboolean(L, -1);
        }
        else {
          logMsg(L, ERROR_MSG, MsgFormat(@"options.testMode (boolean) expected, got: %s", luaL_typename(L, -1)));
          return 0;
        }
      }
      else {
        logMsg(L, ERROR_MSG, MsgFormat(@"Invalid option '%s'", key));
        return 0;
      }
    }
  }
  else {
    logMsg(L, ERROR_MSG, MsgFormat(@"options table expected, got %s", luaL_typename(L, 2)));
    return 0;
  }

  // validate gameId
  if (gameId == NULL) {
    logMsg(L, ERROR_MSG, @"options.gameId required");
    return 0;
  }

  NSLog(@"%s: %s (LevelPlay)", PLUGIN_NAME, PLUGIN_VERSION);

  NSString *appKey = @(gameId);
  BOOL fTestMode = testMode;

  // Request ATT before initializing
  bool noAtt = true;
  if (@available(iOS 14, tvOS 14, *)) {
    if ([[NSBundle mainBundle] objectForInfoDictionaryKey:@"NSUserTrackingUsageDescription"]) {
      noAtt = false;
      [ATTrackingManager requestTrackingAuthorizationWithCompletionHandler:^(ATTrackingManagerAuthorizationStatus status) {
        [[NSOperationQueue mainQueue] addOperationWithBlock:^{
          if (fTestMode) {
            [LevelPlay setAdaptersDebug:YES];
          }

          LPMInitRequestBuilder *requestBuilder = [[LPMInitRequestBuilder alloc] initWithAppKey:appKey];
          LPMInitRequest *initRequest = [requestBuilder build];

          [LevelPlay initWithRequest:initRequest completion:^(LPMConfiguration *_Nullable config, NSError *_Nullable error) {
            if (error) {
              NSDictionary *coronaEvent = @{
                @(CoronaEventPhaseKey()) : PHASE_INIT,
                @(CoronaEventIsErrorKey()) : @(true),
                CORONA_EVENT_DATA_KEY : UnityAdsPlugin::getJSONStringForPlacement(nil, (int)error.code, error.localizedDescription)
              };
              // Use interstitial delegate's dispatch method via a temporary helper
              [[NSOperationQueue mainQueue] addOperationWithBlock:^{
                lua_State *Lua = coronaRuntime.L;
                CoronaLuaNewEvent(Lua, EVENT_NAME);

                for (NSString *key in coronaEvent) {
                  CoronaLuaPushValue(Lua, [coronaEvent valueForKey:key]);
                  lua_setfield(Lua, -2, key.UTF8String);
                }

                lua_pushstring(Lua, PROVIDER_NAME);
                lua_setfield(Lua, -2, CoronaEventProviderKey());

                CoronaLuaDispatchEvent(Lua, coronaListener, 0);
              }];
            } else {
              NSDictionary *coronaEvent = @{
                @(CoronaEventPhaseKey()) : PHASE_INIT
              };
              [[NSOperationQueue mainQueue] addOperationWithBlock:^{
                lua_State *Lua = coronaRuntime.L;
                CoronaLuaNewEvent(Lua, EVENT_NAME);

                for (NSString *key in coronaEvent) {
                  CoronaLuaPushValue(Lua, [coronaEvent valueForKey:key]);
                  lua_setfield(Lua, -2, key.UTF8String);
                }

                lua_pushboolean(Lua, false);
                lua_setfield(Lua, -2, CoronaEventIsErrorKey());

                lua_pushstring(Lua, PROVIDER_NAME);
                lua_setfield(Lua, -2, CoronaEventProviderKey());

                CoronaLuaDispatchEvent(Lua, coronaListener, 0);
              }];
            }
          }];
        }];
      }];
    }
  }
  if (noAtt) {
    if (fTestMode) {
      [LevelPlay setAdaptersDebug:YES];
    }

    LPMInitRequestBuilder *requestBuilder = [[LPMInitRequestBuilder alloc] initWithAppKey:appKey];
    LPMInitRequest *initRequest = [requestBuilder build];

    [LevelPlay initWithRequest:initRequest completion:^(LPMConfiguration *_Nullable config, NSError *_Nullable error) {
      if (error) {
        NSDictionary *coronaEvent = @{
          @(CoronaEventPhaseKey()) : PHASE_INIT,
          @(CoronaEventIsErrorKey()) : @(true),
          CORONA_EVENT_DATA_KEY : UnityAdsPlugin::getJSONStringForPlacement(nil, (int)error.code, error.localizedDescription)
        };
        [[NSOperationQueue mainQueue] addOperationWithBlock:^{
          lua_State *Lua = coronaRuntime.L;
          CoronaLuaNewEvent(Lua, EVENT_NAME);

          for (NSString *key in coronaEvent) {
            CoronaLuaPushValue(Lua, [coronaEvent valueForKey:key]);
            lua_setfield(Lua, -2, key.UTF8String);
          }

          lua_pushstring(Lua, PROVIDER_NAME);
          lua_setfield(Lua, -2, CoronaEventProviderKey());

          CoronaLuaDispatchEvent(Lua, coronaListener, 0);
        }];
      } else {
        NSDictionary *coronaEvent = @{
          @(CoronaEventPhaseKey()) : PHASE_INIT
        };
        [[NSOperationQueue mainQueue] addOperationWithBlock:^{
          lua_State *Lua = coronaRuntime.L;
          CoronaLuaNewEvent(Lua, EVENT_NAME);

          for (NSString *key in coronaEvent) {
            CoronaLuaPushValue(Lua, [coronaEvent valueForKey:key]);
            lua_setfield(Lua, -2, key.UTF8String);
          }

          lua_pushboolean(Lua, false);
          lua_setfield(Lua, -2, CoronaEventIsErrorKey());

          lua_pushstring(Lua, PROVIDER_NAME);
          lua_setfield(Lua, -2, CoronaEventProviderKey());

          CoronaLuaDispatchEvent(Lua, coronaListener, 0);
        }];
      }
    }];
  }

  return 0;
}

// [Lua] unityads.isLoaded(placementId) -> boolean
int
UnityAdsPlugin::isLoaded( lua_State *L )
{
  Self *context = ToLibrary(L);

  if (! context) {
    return 0;
  }

  Self& library = *context;

  library.functionSignature = @"unityads.isLoaded(placementId)";

  if (! isSDKInitialized(L)) {
    return 0;
  }

  int nargs = lua_gettop(L);
  if (nargs != 1) {
    logMsg(L, ERROR_MSG, MsgFormat(@"Expected 1 argument, got %d", nargs));
    return 0;
  }

  const char *placementId = NULL;

  if (lua_type(L, 1) == LUA_TSTRING) {
    placementId = lua_tostring(L, 1);
  }
  else {
    logMsg(L, ERROR_MSG, MsgFormat(@"placementId expected (string), got %s", luaL_typename(L, 1)));
    return 0;
  }

  bool isLoaded = [loadedIds containsObject:@(placementId)];
  lua_pushboolean(L, isLoaded);

  return 1;
}

// [Lua] unityads.load(placementId [, adType])
int
UnityAdsPlugin::load( lua_State *L )
{
  Self *context = ToLibrary(L);

  if (! context) {
    return 0;
  }

  Self& library = *context;

  library.functionSignature = @"unityads.load(placementId [, adType])";

  if (! isSDKInitialized(L)) {
    return 0;
  }

  int nargs = lua_gettop(L);
  if (nargs < 1 || nargs > 2) {
    logMsg(L, ERROR_MSG, MsgFormat(@"Expected 1-2 arguments, got %d", nargs));
    return 0;
  }

  const char *placementId = NULL;

  if (lua_type(L, 1) == LUA_TSTRING) {
    placementId = lua_tostring(L, 1);
  }
  else {
    logMsg(L, ERROR_MSG, MsgFormat(@"placementId expected (string), got %s", luaL_typename(L, 1)));
    return 0;
  }

  NSString *adType = @"interstitial";
  if (nargs >= 2) {
    if (lua_type(L, 2) == LUA_TSTRING) {
      adType = [NSString stringWithUTF8String:lua_tostring(L, 2)];
    }
    else {
      logMsg(L, ERROR_MSG, MsgFormat(@"adType expected (string), got %s", luaL_typename(L, 2)));
      return 0;
    }
  }

  NSString *adUnitId = @(placementId);

  if ([adType isEqualToString:@"rewarded"]) {
    LPMRewardedAd *rewardedAd = [[LPMRewardedAd alloc] initWithAdUnitId:adUnitId];
    CoronaRewardedAdDelegate *delegate = [[CoronaRewardedAdDelegate alloc] initWithAdUnitId:adUnitId];
    [rewardedAd setDelegate:delegate];
    adObjects[adUnitId] = @{@"ad": rewardedAd, @"delegate": delegate};
    [rewardedAd loadAd];
  } else {
    LPMInterstitialAd *interstitialAd = [[LPMInterstitialAd alloc] initWithAdUnitId:adUnitId];
    CoronaInterstitialAdDelegate *delegate = [[CoronaInterstitialAdDelegate alloc] initWithAdUnitId:adUnitId];
    [interstitialAd setDelegate:delegate];
    adObjects[adUnitId] = @{@"ad": interstitialAd, @"delegate": delegate};
    [interstitialAd loadAd];
  }

  return 0;
}

//  [Lua] unityads.show(placementId)
int
UnityAdsPlugin::show( lua_State *L )
{
  Self *context = ToLibrary(L);

  if (! context) {
    return 0;
  }

  Self& library = *context;

  library.functionSignature = @"unityads.show(placementId)";

  if ( ! isSDKInitialized(L) ) {
    return 0;
  }

  int nargs = lua_gettop(L);
  if (nargs != 1) {
    logMsg(L, ERROR_MSG, MsgFormat(@"Expected 1 argument, got %d", nargs));
    return 0;
  }

  const char *placementId = NULL;

  if (lua_type(L, 1) == LUA_TSTRING) {
    placementId = lua_tostring(L, 1);
  }
  else {
    logMsg(L, ERROR_MSG, MsgFormat(@"placementId expected (string), got %s", luaL_typename(L, 1)));
    return 0;
  }

  NSString *adUnitId = @(placementId);

  if (![loadedIds containsObject:adUnitId]) {
    logMsg(L, WARNING_MSG, MsgFormat(@"placementId '%s' not loaded", placementId));
    return 0;
  }

  NSDictionary *adEntry = adObjects[adUnitId];
  if (adEntry) {
    id adObject = adEntry[@"ad"];
    [[NSOperationQueue mainQueue] addOperationWithBlock:^{
      if ([adObject isKindOfClass:[LPMInterstitialAd class]]) {
        [(LPMInterstitialAd *)adObject showAdWithViewController:library.coronaViewController placementName:nil];
      } else if ([adObject isKindOfClass:[LPMRewardedAd class]]) {
        [(LPMRewardedAd *)adObject showAdWithViewController:library.coronaViewController placementName:nil];
      }
    }];
  } else {
    logMsg(L, WARNING_MSG, MsgFormat(@"No ad object found for '%s'", placementId));
  }

  return 0;
}

// [Lua] unityads.setHasUserConsent( bool )
int
UnityAdsPlugin::setHasUserConsent(lua_State *L)
{
    Self *context = ToLibrary(L);

    if (! context) {
        return 0;
    }

    Self& library = *context;

    library.functionSignature = @"unityads.setHasUserConsent( bool )";

    int nargs = lua_gettop(L);
    if (nargs != 1) {
        logMsg(L, ERROR_MSG, MsgFormat(@"Expected 1 argument, got %d", nargs));
        return 0;
    }

    int hasUserConsent = 0;

    if (lua_type(L, 1) == LUA_TBOOLEAN) {
        hasUserConsent = lua_toboolean(L, -1);
    }
    else {
        logMsg(L, ERROR_MSG, MsgFormat(@"hasUserConsent (bool) expected, got %s", luaL_typename(L, 1)));
        return 0;
    }

    [LevelPlay setConsent:(hasUserConsent != 0)];

    return 0;
}

// [Lua] unityads.setPersonalizedAds( bool )
int
UnityAdsPlugin::setPersonalizedAds(lua_State *L)
{
    Self *context = ToLibrary(L);

    if (! context) {
        return 0;
    }

    Self& library = *context;

    library.functionSignature = @"unityads.setPersonalizedAds( bool )";

    int nargs = lua_gettop(L);
    if (nargs != 1) {
        logMsg(L, ERROR_MSG, MsgFormat(@"Expected 1 argument, got %d", nargs));
        return 0;
    }

    int setPersonalizedAds = 0;

    if (lua_type(L, 1) == LUA_TBOOLEAN) {
        setPersonalizedAds = lua_toboolean(L, -1);
    }
    else {
        logMsg(L, ERROR_MSG, MsgFormat(@"setPersonalizedAds (bool) expected, got %s", luaL_typename(L, 1)));
        return 0;
    }

    // In LevelPlay, consent controls personalization
    [LevelPlay setConsent:(setPersonalizedAds == 0)];

    return 0;
}

// [Lua] unityads.setPrivacyMode( privacyMode )
int
UnityAdsPlugin::setPrivacyMode(lua_State *L)
{
    Self *context = ToLibrary(L);

    if (! context) {
        return 0;
    }

    Self& library = *context;

    library.functionSignature = @"unityads.setPrivacyMode( privacyMode )";

    int nargs = lua_gettop(L);
    if (nargs != 1) {
        logMsg(L, ERROR_MSG, MsgFormat(@"Expected 1 argument, got %d", nargs));
        return 0;
    }
    NSString *privacyMode = @"none";

    if (lua_type(L, 1) == LUA_TSTRING) {
        privacyMode = [NSString stringWithUTF8String:lua_tostring( L, 1 )];
    }
    else {
        logMsg(L, ERROR_MSG, MsgFormat(@"setPrivacyMode (string) expected, got %s", luaL_typename(L, 1)));
        return 0;
    }

    // Map privacy modes to LevelPlay COPPA setting
    if ([privacyMode isEqualToString:@"app"] || [privacyMode isEqualToString:@"mixed"]) {
        [LevelPlay setMetaDataWithKey:@"is_child_directed" value:@"true"];
    } else {
        [LevelPlay setMetaDataWithKey:@"is_child_directed" value:@"false"];
    }

    return 0;
}

// ----------------------------------------------------------------------------

CORONA_EXPORT int
luaopen_plugin_unityads_v4( lua_State *L )
{
  return UnityAdsPlugin::Open( L );
}
