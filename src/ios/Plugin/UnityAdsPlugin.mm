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
#define PLUGIN_VERSION     "2.1.0"

static const char EVENT_NAME[]    = "adsRequest";
static const char PROVIDER_NAME[] = "unityads";

// ad types
static const char TYPE_UNITYAD[] = "unityAd";

// ad formats accepted by unityads.load()
static NSString * const AD_TYPE_INTERSTITIAL = @"interstitial";
static NSString * const AD_TYPE_REWARDED     = @"rewarded";

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

// A load that has not reported back after this long is treated as abandoned, so a
// later unityads.load() is forwarded to the SDK again instead of being dropped.
static const NSTimeInterval LOAD_STALE_SECONDS = 120.0;

// LevelPlay error code reported when loadAd is called while the ad unit already has a
// load in flight or a loaded ad waiting (ERROR_CODE_LOAD_FAILED_ALREADY_CALLED).
static const NSInteger LEVELPLAY_ERROR_LOAD_ALREADY_CALLED = 627;

// ----------------------------------------------------------------------------
// Ad slot: one persistent LevelPlay ad object per ad unit
// ----------------------------------------------------------------------------
//
// LevelPlay documents LPMInterstitialAd / LPMRewardedAd as reusable instances that
// handle every load and show for an ad unit during the session. Earlier versions of
// this plugin allocated a fresh ad object (plus delegate) on every unityads.load()
// call and dropped it after each close or failure, so apps that call load()
// repeatedly fanned out into several concurrent SDK loads and a growing pile of
// SDK-internal state. A slot keeps exactly one ad object per ad unit instead.

@interface CoronaAdSlot : NSObject
@property (nonatomic, strong) NSString *adUnitId;
@property (nonatomic, strong) NSString *adType;       // AD_TYPE_INTERSTITIAL or AD_TYPE_REWARDED
@property (nonatomic, strong) id ad;                  // LPMInterstitialAd or LPMRewardedAd
@property (nonatomic, strong) id delegate;            // kept alive here; the SDK only holds it weakly
@property (nonatomic, assign) BOOL isLoading;         // a loadAd call is in flight
@property (nonatomic, assign) NSTimeInterval loadStartedAt;
@property (nonatomic, assign) BOOL isReady;           // didLoadAd received and the ad has not been shown yet
@property (nonatomic, assign) BOOL rewardEarned;      // rewarded only: didRewardAd received during the current show
- (BOOL)isAdReady;
- (void)loadAd;
- (void)showAdWithViewController:(UIViewController *)viewController;
@end

@implementation CoronaAdSlot

- (BOOL)isAdReady {
    if ([self.ad isKindOfClass:[LPMInterstitialAd class]]) {
        return [(LPMInterstitialAd *)self.ad isAdReady];
    }
    if ([self.ad isKindOfClass:[LPMRewardedAd class]]) {
        return [(LPMRewardedAd *)self.ad isAdReady];
    }
    return NO;
}

- (void)loadAd {
    if ([self.ad isKindOfClass:[LPMInterstitialAd class]]) {
        [(LPMInterstitialAd *)self.ad loadAd];
    }
    else if ([self.ad isKindOfClass:[LPMRewardedAd class]]) {
        [(LPMRewardedAd *)self.ad loadAd];
    }
}

- (void)showAdWithViewController:(UIViewController *)viewController {
    if ([self.ad isKindOfClass:[LPMInterstitialAd class]]) {
        [(LPMInterstitialAd *)self.ad showAdWithViewController:viewController placementName:nil];
    }
    else if ([self.ad isKindOfClass:[LPMRewardedAd class]]) {
        [(LPMRewardedAd *)self.ad showAdWithViewController:viewController placementName:nil];
    }
}

@end

// ad slots keyed by adUnitId
static NSMutableDictionary<NSString *, CoronaAdSlot *> *adSlots = nil;

// ----------------------------------------------------------------------------
// plugin class and delegate definitions
// ----------------------------------------------------------------------------

@interface CoronaInterstitialAdDelegate : NSObject <LPMInterstitialAdDelegate>
@property (nonatomic, strong) NSString *adUnitId;
- (instancetype)initWithAdUnitId:(NSString *)adUnitId;
@end

@interface CoronaRewardedAdDelegate : NSObject <LPMRewardedAdDelegate>
@property (nonatomic, strong) NSString *adUnitId;
- (instancetype)initWithAdUnitId:(NSString *)adUnitId;
@end

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
    static void dispatchLuaEvent(NSDictionary *event);
    static CoronaAdSlot *slotForAdUnitId(NSString *adUnitId);
    static void handleLoadFailed(NSString *adUnitId, NSError *error);

  private: // internal helper functions
    static void logMsg(lua_State *L, NSString *msgType,  NSString *errorMsg);
    static bool isSDKInitialized(lua_State *L);
    static void initializeLevelPlay(NSString *appKey, BOOL testMode);

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

@implementation CoronaInterstitialAdDelegate

- (instancetype)initWithAdUnitId:(NSString *)adUnitId {
    if (self = [super init]) {
        self.adUnitId = adUnitId;
    }
    return self;
}

- (CoronaAdSlot *)slot {
    return UnityAdsPlugin::slotForAdUnitId(self.adUnitId);
}

- (void)didLoadAdWithAdInfo:(LPMAdInfo *)adInfo {
    CoronaAdSlot *slot = [self slot];
    slot.isLoading = NO;
    slot.isReady = YES;

    UnityAdsPlugin::dispatchLuaEvent(@{
        @(CoronaEventPhaseKey()) : PHASE_LOADED,
        @(CoronaEventTypeKey()) : @(TYPE_UNITYAD),
        CORONA_EVENT_DATA_KEY : UnityAdsPlugin::getJSONStringForPlacement(self.adUnitId, -1, nil)
    });
}

- (void)didFailToLoadAdWithAdUnitId:(NSString *)adUnitId error:(NSError *)error {
    UnityAdsPlugin::handleLoadFailed(self.adUnitId, error);
}

- (void)didDisplayAdWithAdInfo:(LPMAdInfo *)adInfo {
    [self slot].isReady = NO;

    UnityAdsPlugin::dispatchLuaEvent(@{
        @(CoronaEventPhaseKey()) : PHASE_DISPLAYED,
        @(CoronaEventTypeKey()) : @(TYPE_UNITYAD),
        CORONA_EVENT_DATA_KEY : UnityAdsPlugin::getJSONStringForPlacement(self.adUnitId, -1, nil)
    });
}

- (void)didFailToDisplayAdWithAdInfo:(LPMAdInfo *)adInfo error:(NSError *)error {
    [self slot].isReady = NO;

    UnityAdsPlugin::dispatchLuaEvent(@{
        @(CoronaEventPhaseKey()) : PHASE_FAILED,
        @(CoronaEventIsErrorKey()) : @(true),
        @(CoronaEventTypeKey()) : @(TYPE_UNITYAD),
        @(CoronaEventResponseKey()) : error.localizedDescription ?: @"Display failed",
        CORONA_EVENT_DATA_KEY : UnityAdsPlugin::getJSONStringForPlacement(self.adUnitId, (int)error.code, error.localizedDescription)
    });
}

- (void)didClickAdWithAdInfo:(LPMAdInfo *)adInfo {
    UnityAdsPlugin::dispatchLuaEvent(@{
        @(CoronaEventPhaseKey()) : PHASE_CLICKED,
        @(CoronaEventTypeKey()) : @(TYPE_UNITYAD),
        CORONA_EVENT_DATA_KEY : UnityAdsPlugin::getJSONStringForPlacement(self.adUnitId, -1, nil)
    });
}

- (void)didCloseAdWithAdInfo:(LPMAdInfo *)adInfo {
    // The ad object stays in its slot; the next unityads.load() reuses it.
    UnityAdsPlugin::dispatchLuaEvent(@{
        @(CoronaEventPhaseKey()) : PHASE_COMPLETED,
        @(CoronaEventTypeKey()) : @(TYPE_UNITYAD),
        CORONA_EVENT_DATA_KEY : UnityAdsPlugin::getJSONStringForPlacement(self.adUnitId, -1, nil)
    });
}

- (void)didChangeAdInfo:(LPMAdInfo *)adInfo {
    // Not mapped to existing API
}

@end

// ----------------------------------------------------------------------------
// Rewarded Ad Delegate
// ----------------------------------------------------------------------------

@implementation CoronaRewardedAdDelegate

- (instancetype)initWithAdUnitId:(NSString *)adUnitId {
    if (self = [super init]) {
        self.adUnitId = adUnitId;
    }
    return self;
}

- (CoronaAdSlot *)slot {
    return UnityAdsPlugin::slotForAdUnitId(self.adUnitId);
}

- (void)didLoadAdWithAdInfo:(LPMAdInfo *)adInfo {
    CoronaAdSlot *slot = [self slot];
    slot.isLoading = NO;
    slot.isReady = YES;

    UnityAdsPlugin::dispatchLuaEvent(@{
        @(CoronaEventPhaseKey()) : PHASE_LOADED,
        @(CoronaEventTypeKey()) : @(TYPE_UNITYAD),
        CORONA_EVENT_DATA_KEY : UnityAdsPlugin::getJSONStringForPlacement(self.adUnitId, -1, nil)
    });
}

- (void)didFailToLoadAdWithAdUnitId:(NSString *)adUnitId error:(NSError *)error {
    UnityAdsPlugin::handleLoadFailed(self.adUnitId, error);
}

- (void)didDisplayAdWithAdInfo:(LPMAdInfo *)adInfo {
    CoronaAdSlot *slot = [self slot];
    slot.isReady = NO;
    slot.rewardEarned = NO;

    UnityAdsPlugin::dispatchLuaEvent(@{
        @(CoronaEventPhaseKey()) : PHASE_DISPLAYED,
        @(CoronaEventTypeKey()) : @(TYPE_UNITYAD),
        CORONA_EVENT_DATA_KEY : UnityAdsPlugin::getJSONStringForPlacement(self.adUnitId, -1, nil)
    });
}

- (void)didFailToDisplayAdWithAdInfo:(LPMAdInfo *)adInfo error:(NSError *)error {
    [self slot].isReady = NO;

    UnityAdsPlugin::dispatchLuaEvent(@{
        @(CoronaEventPhaseKey()) : PHASE_FAILED,
        @(CoronaEventIsErrorKey()) : @(true),
        @(CoronaEventTypeKey()) : @(TYPE_UNITYAD),
        @(CoronaEventResponseKey()) : error.localizedDescription ?: @"Display failed",
        CORONA_EVENT_DATA_KEY : UnityAdsPlugin::getJSONStringForPlacement(self.adUnitId, (int)error.code, error.localizedDescription)
    });
}

- (void)didClickAdWithAdInfo:(LPMAdInfo *)adInfo {
    UnityAdsPlugin::dispatchLuaEvent(@{
        @(CoronaEventPhaseKey()) : PHASE_CLICKED,
        @(CoronaEventTypeKey()) : @(TYPE_UNITYAD),
        CORONA_EVENT_DATA_KEY : UnityAdsPlugin::getJSONStringForPlacement(self.adUnitId, -1, nil)
    });
}

- (void)didRewardAdWithAdInfo:(LPMAdInfo *)adInfo reward:(LPMReward *)reward {
    [self slot].rewardEarned = YES;
}

- (void)didCloseAdWithAdInfo:(LPMAdInfo *)adInfo {
    CoronaAdSlot *slot = [self slot];

    // If a reward was received the ad was completed; otherwise it was skipped
    NSString *phase = slot.rewardEarned ? PHASE_COMPLETED : PHASE_SKIPPED;
    slot.rewardEarned = NO;

    // The ad object stays in its slot; the next unityads.load() reuses it.
    UnityAdsPlugin::dispatchLuaEvent(@{
        @(CoronaEventPhaseKey()) : phase,
        @(CoronaEventTypeKey()) : @(TYPE_UNITYAD),
        CORONA_EVENT_DATA_KEY : UnityAdsPlugin::getJSONStringForPlacement(self.adUnitId, -1, nil)
    });
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

CoronaAdSlot *
UnityAdsPlugin::slotForAdUnitId(NSString *adUnitId)
{
  return (adUnitId != nil) ? adSlots[adUnitId] : nil;
}

// Shared by both delegates: a load failure, with the "load already called" case
// (error 627) mapped back to a loaded event when the SDK still holds a showable ad.
void
UnityAdsPlugin::handleLoadFailed(NSString *adUnitId, NSError *error)
{
  CoronaAdSlot *slot = slotForAdUnitId(adUnitId);
  bool alreadyCalled = (error != nil && error.code == LEVELPLAY_ERROR_LOAD_ALREADY_CALLED);

  if (slot != nil) {
    slot.isLoading = NO;

    if (alreadyCalled && [slot isAdReady]) {
      slot.isReady = YES;
      dispatchLuaEvent(@{
        @(CoronaEventPhaseKey()) : PHASE_LOADED,
        @(CoronaEventTypeKey()) : @(TYPE_UNITYAD),
        CORONA_EVENT_DATA_KEY : getJSONStringForPlacement(adUnitId, -1, nil)
      });
      return;
    }

    if (! alreadyCalled) {
      slot.isReady = NO;
    }
  }

  dispatchLuaEvent(@{
    @(CoronaEventPhaseKey()) : PHASE_FAILED,
    @(CoronaEventIsErrorKey()) : @(true),
    @(CoronaEventTypeKey()) : @(TYPE_UNITYAD),
    @(CoronaEventResponseKey()) : error.localizedDescription ?: @"Load failed",
    CORONA_EVENT_DATA_KEY : getJSONStringForPlacement(adUnitId, (int)error.code, error.localizedDescription)
  });
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

// Delivers an adsRequest event to the Lua listener on the main thread.
// Adds isError=false when the event does not carry its own isError value.
void
UnityAdsPlugin::dispatchLuaEvent(NSDictionary *event)
{
  [[NSOperationQueue mainQueue] addOperationWithBlock:^{
    // SDK callbacks can still be queued after the runtime was torn down (Finalizer)
    if (coronaRuntime == nil || coronaListener == NULL) {
      return;
    }

    lua_State *L = coronaRuntime.L;
    if (L == NULL) {
      return;
    }

    bool hasErrorKey = false;

    CoronaLuaNewEvent(L, EVENT_NAME);

    for (NSString *key in event) {
      CoronaLuaPushValue(L, [event objectForKey:key]);
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

    CoronaLuaDispatchEvent(L, coronaListener, 0);
  }];
}

void
UnityAdsPlugin::initializeLevelPlay(NSString *appKey, BOOL testMode)
{
  if (testMode) {
    [LevelPlay setAdaptersDebug:YES];
  }

  LPMInitRequestBuilder *requestBuilder = [[LPMInitRequestBuilder alloc] initWithAppKey:appKey];
  LPMInitRequest *initRequest = [requestBuilder build];

  [LevelPlay initWithRequest:initRequest completion:^(LPMConfiguration *_Nullable config, NSError *_Nullable error) {
    if (error) {
      dispatchLuaEvent(@{
        @(CoronaEventPhaseKey()) : PHASE_INIT,
        @(CoronaEventIsErrorKey()) : @(true),
        CORONA_EVENT_DATA_KEY : getJSONStringForPlacement(nil, (int)error.code, error.localizedDescription)
      });
    }
    else {
      dispatchLuaEvent(@{
        @(CoronaEventPhaseKey()) : PHASE_INIT
      });
    }
  }];
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

  // Dropping the slots releases our delegates; the SDK only holds them weakly, so any
  // callback that is still in flight is discarded instead of reaching a dead Lua state.
  [adSlots removeAllObjects];

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
    adSlots = [NSMutableDictionary new];
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

  NSLog(@"%s: %s (LevelPlay SDK %@)", PLUGIN_NAME, PLUGIN_VERSION, [LevelPlay sdkVersion]);

  NSString *appKey = @(gameId);
  BOOL fTestMode = testMode;

  // Request ATT before initializing, but only when the app declares a usage description
  bool attRequested = false;
  if (@available(iOS 14, tvOS 14, *)) {
    if ([[NSBundle mainBundle] objectForInfoDictionaryKey:@"NSUserTrackingUsageDescription"]) {
      attRequested = true;
      [ATTrackingManager requestTrackingAuthorizationWithCompletionHandler:^(ATTrackingManagerAuthorizationStatus status) {
        [[NSOperationQueue mainQueue] addOperationWithBlock:^{
          initializeLevelPlay(appKey, fTestMode);
        }];
      }];
    }
  }

  if (! attRequested) {
    initializeLevelPlay(appKey, fTestMode);
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

  // Ready means the SDK reported a load and still considers the ad showable
  // (not shown, not expired, not capped).
  CoronaAdSlot *slot = slotForAdUnitId(@(placementId));
  bool isLoaded = (slot != nil && slot.isReady && [slot isAdReady]);
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

  NSString *adType = AD_TYPE_INTERSTITIAL;
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
  NSString *requestedType = [adType isEqualToString:AD_TYPE_REWARDED] ? AD_TYPE_REWARDED : AD_TYPE_INTERSTITIAL;

  CoronaAdSlot *slot = slotForAdUnitId(adUnitId);

  // An ad unit is either interstitial or rewarded. Rebuild the slot if the caller switched type.
  if (slot != nil && ! [slot.adType isEqualToString:requestedType]) {
    logMsg(L, WARNING_MSG, MsgFormat(@"placementId '%s' was loaded as '%@' before, recreating it as '%@'", placementId, slot.adType, requestedType));
    [adSlots removeObjectForKey:adUnitId];
    slot = nil;
  }

  if (slot == nil) {
    slot = [CoronaAdSlot new];
    slot.adUnitId = adUnitId;
    slot.adType = requestedType;

    if ([requestedType isEqualToString:AD_TYPE_REWARDED]) {
      LPMRewardedAd *rewardedAd = [[LPMRewardedAd alloc] initWithAdUnitId:adUnitId];
      CoronaRewardedAdDelegate *delegate = [[CoronaRewardedAdDelegate alloc] initWithAdUnitId:adUnitId];
      [rewardedAd setDelegate:delegate];
      slot.ad = rewardedAd;
      slot.delegate = delegate;
    }
    else {
      LPMInterstitialAd *interstitialAd = [[LPMInterstitialAd alloc] initWithAdUnitId:adUnitId];
      CoronaInterstitialAdDelegate *delegate = [[CoronaInterstitialAdDelegate alloc] initWithAdUnitId:adUnitId];
      [interstitialAd setDelegate:delegate];
      slot.ad = interstitialAd;
      slot.delegate = delegate;
    }

    adSlots[adUnitId] = slot;
  }

  // The SDK refuses a second loadAd while one is in flight, so answer repeated
  // calls here instead of forwarding them.
  if (slot.isLoading) {
    NSTimeInterval elapsed = [NSDate timeIntervalSinceReferenceDate] - slot.loadStartedAt;
    if (elapsed < LOAD_STALE_SECONDS) {
      logMsg(L, WARNING_MSG, MsgFormat(@"placementId '%s' is already loading", placementId));
      return 0;
    }
    logMsg(L, WARNING_MSG, MsgFormat(@"placementId '%s' load did not report back in %.0f seconds, retrying", placementId, elapsed));
  }

  // A loaded, still showable ad is reported right away instead of requesting another one.
  if (slot.isReady && [slot isAdReady]) {
    slot.isLoading = NO;
    dispatchLuaEvent(@{
      @(CoronaEventPhaseKey()) : PHASE_LOADED,
      @(CoronaEventTypeKey()) : @(TYPE_UNITYAD),
      CORONA_EVENT_DATA_KEY : getJSONStringForPlacement(adUnitId, -1, nil)
    });
    return 0;
  }

  slot.isReady = NO;
  slot.isLoading = YES;
  slot.loadStartedAt = [NSDate timeIntervalSinceReferenceDate];
  [slot loadAd];

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

  CoronaAdSlot *slot = slotForAdUnitId(@(placementId));

  if (slot == nil || ! slot.isReady) {
    logMsg(L, WARNING_MSG, MsgFormat(@"placementId '%s' not loaded", placementId));
    return 0;
  }

  // Show on the main queue; an expired or capped ad is reported by the SDK through
  // didFailToDisplayAdWithAdInfo:error: as a "failed" event.
  UIViewController *viewController = library.coronaViewController;
  [[NSOperationQueue mainQueue] addOperationWithBlock:^{
    [slot showAdWithViewController:viewController];
  }];

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

    [LPMPrivacySettings setGDPRConsent:(hasUserConsent != 0)];

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

    // Documented semantics (inherited from the Unity Ads "user.nonbehavioral" flag):
    // true means the user may NOT receive personalized ads, so GDPR consent is withheld.
    [LPMPrivacySettings setGDPRConsent:(setPersonalizedAds == 0)];

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

    // "app" and "mixed" audiences are treated as child directed. LevelPlay requires this
    // flag before initialization, which matches the documented usage of this function.
    BOOL childDirected = ([privacyMode isEqualToString:@"app"] || [privacyMode isEqualToString:@"mixed"]);
    [LPMPrivacySettings setCOPPA:childDirected];

    return 0;
}

// ----------------------------------------------------------------------------

CORONA_EXPORT int
luaopen_plugin_unityads_v4( lua_State *L )
{
  return UnityAdsPlugin::Open( L );
}
