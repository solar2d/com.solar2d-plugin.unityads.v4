// LuaLoader.java
// UnityAds Plugin (LevelPlay Backend)
//

package plugin.unityads.v4;

import android.os.SystemClock;
import android.util.Log;

import com.ansca.corona.CoronaActivity;
import com.ansca.corona.CoronaEnvironment;
import com.ansca.corona.CoronaLua;
import com.ansca.corona.CoronaLuaEvent;
import com.ansca.corona.CoronaRuntime;
import com.ansca.corona.CoronaRuntimeListener;
import com.ansca.corona.CoronaRuntimeTask;
import com.ansca.corona.CoronaRuntimeTaskDispatcher;
import com.naef.jnlua.JavaFunction;
import com.naef.jnlua.LuaState;
import com.naef.jnlua.LuaType;
import com.naef.jnlua.NamedJavaFunction;

import com.unity3d.mediation.LevelPlay;
import com.unity3d.mediation.LevelPlayAdError;
import com.unity3d.mediation.LevelPlayAdInfo;
import com.unity3d.mediation.LevelPlayConfiguration;
import com.unity3d.mediation.LevelPlayInitError;
import com.unity3d.mediation.LevelPlayInitListener;
import com.unity3d.mediation.LevelPlayInitRequest;
import com.unity3d.mediation.LevelPlayPrivacySettings;
import com.unity3d.mediation.interstitial.LevelPlayInterstitialAd;
import com.unity3d.mediation.interstitial.LevelPlayInterstitialAdListener;
import com.unity3d.mediation.rewarded.LevelPlayReward;
import com.unity3d.mediation.rewarded.LevelPlayRewardedAd;
import com.unity3d.mediation.rewarded.LevelPlayRewardedAdListener;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implements the Lua interface for the UnityAds plugin.
 * <p>
 * Only one instance of this class will be created by Corona for the lifetime of the application.
 * This instance will be re-used for every new Corona activity that gets created.
 */
@SuppressWarnings({"unused", "RedundantSuppression"})
public class LuaLoader implements JavaFunction, CoronaRuntimeListener {
    private static final String PLUGIN_NAME = "plugin.unityads.v4";
    private static final String PLUGIN_VERSION = "2.1.0";

    private static final String EVENT_NAME = "adsRequest";
    private static final String PROVIDER_NAME = "unityads";

    // event types
    private static final String TYPE_UNITYAD = "unityAd";

    // ad formats accepted by unityads.load()
    private static final String AD_TYPE_INTERSTITIAL = "interstitial";
    private static final String AD_TYPE_REWARDED = "rewarded";

    // data keys
    private static final String DATA_PLACEMENT_ID_KEY = "placementId";
    private static final String DATA_ERROR_MSG_KEY = "errorMsg";
    private static final String DATA_ERROR_CODE_KEY = "errorCode";

    // add missing keys
    private static final String EVENT_PHASE_KEY = "phase";
    private static final String EVENT_TYPE_KEY = "type";
    private static final String EVENT_DATA_KEY = "data";

    // event phases
    private static final String PHASE_INIT = "init";
    private static final String PHASE_DISPLAYED = "displayed";
    private static final String PHASE_FAILED = "failed";
    private static final String PHASE_SKIPPED = "skipped";
    private static final String PHASE_COMPLETED = "completed";
    private static final String PHASE_CLICKED = "clicked";
    private static final String PHASE_LOADED = "loaded";

    // A load that has not reported back after this long is treated as abandoned, so a
    // later unityads.load() is forwarded to the SDK again instead of being dropped.
    private static final long LOAD_STALE_MS = 120_000L;

    private static int coronaListener = CoronaLua.REFNIL;
    private static CoronaRuntimeTaskDispatcher coronaRuntimeTaskDispatcher = null;

    // message constants
    private static final String CORONA_TAG = "Corona";
    private static final String ERROR_MSG = "ERROR: ";
    private static final String WARNING_MSG = "WARNING: ";

    private static String functionSignature = "";

    // One persistent LevelPlay ad object per ad unit, keyed by adUnitId (see AdSlot)
    private static final Map<String, AdSlot> adSlots = new ConcurrentHashMap<>();
    private static volatile boolean fInitSuccess = false;
    private static volatile boolean fInitStarted = false;

    // -------------------------------------------------------------------
    // Ad slot
    // -------------------------------------------------------------------
    //
    // LevelPlay documents LevelPlayInterstitialAd / LevelPlayRewardedAd as reusable
    // instances that handle every load and show for an ad unit during the session.
    // Earlier versions of this plugin created a fresh ad object on every unityads.load()
    // call and dropped it after each close or failure, so apps that call load()
    // repeatedly fanned out into several concurrent SDK loads. A slot keeps exactly one
    // ad object per ad unit instead. Slot state is only mutated on the UI thread (load,
    // show and every SDK callback run there); the Lua thread only reads it.
    private static class AdSlot {
        final String adUnitId;
        final String adType;        // AD_TYPE_INTERSTITIAL or AD_TYPE_REWARDED
        final Object ad;            // LevelPlayInterstitialAd or LevelPlayRewardedAd
        volatile boolean isLoading;    // a loadAd call is in flight
        volatile long loadStartedAt;   // SystemClock.elapsedRealtime() when loadAd was called
        volatile boolean isReady;      // onAdLoaded received and the ad has not been shown yet
        volatile boolean rewardEarned; // rewarded only: onAdRewarded received during the current show

        AdSlot(String adUnitId, String adType, Object ad) {
            this.adUnitId = adUnitId;
            this.adType = adType;
            this.ad = ad;
        }

        boolean isAdReady() {
            if (ad instanceof LevelPlayInterstitialAd) {
                return ((LevelPlayInterstitialAd) ad).isAdReady();
            }
            if (ad instanceof LevelPlayRewardedAd) {
                return ((LevelPlayRewardedAd) ad).isAdReady();
            }
            return false;
        }

        void loadAd() {
            if (ad instanceof LevelPlayInterstitialAd) {
                ((LevelPlayInterstitialAd) ad).loadAd();
            } else if (ad instanceof LevelPlayRewardedAd) {
                ((LevelPlayRewardedAd) ad).loadAd();
            }
        }

        void showAd(CoronaActivity activity) {
            if (ad instanceof LevelPlayInterstitialAd) {
                ((LevelPlayInterstitialAd) ad).showAd(activity);
            } else if (ad instanceof LevelPlayRewardedAd) {
                ((LevelPlayRewardedAd) ad).showAd(activity);
            }
        }
    }

    // -------------------------------------------------------------------
    // Plugin lifecycle events
    // -------------------------------------------------------------------

    public LuaLoader() {
        CoronaEnvironment.addRuntimeListener(this);
    }

    @Override
    public int invoke(LuaState L) {
        NamedJavaFunction[] luaFunctions = new NamedJavaFunction[]{
                new Init(),
                new IsLoaded(),
                new Load(),
                new Show(),
                new SetHasUserConsent(),
                new SetPersonalizedAds(),
                new SetPrivacyMode(),
        };
        String libName = L.toString(1);
        L.register(libName, luaFunctions);
        return 1;
    }

    @Override
    public void onLoaded(CoronaRuntime runtime) {
        if (coronaRuntimeTaskDispatcher == null) {
            coronaRuntimeTaskDispatcher = new CoronaRuntimeTaskDispatcher(runtime);
        }
    }

    @Override
    public void onStarted(CoronaRuntime runtime) {
    }

    @Override
    public void onSuspended(CoronaRuntime runtime) {
    }

    @Override
    public void onResumed(CoronaRuntime runtime) {
    }

    @Override
    public void onExiting(CoronaRuntime runtime) {
        adSlots.clear();
        CoronaLua.deleteRef(runtime.getLuaState(), coronaListener);
        coronaListener = CoronaLua.REFNIL;
        coronaRuntimeTaskDispatcher = null;
        fInitSuccess = false;
        fInitStarted = false;
    }

    // -------------------------------------------------------------------
    // helper functions
    // -------------------------------------------------------------------

    private void logMsg(String msgType, String errorMsg) {
        String functionID = functionSignature;
        if (!functionID.isEmpty()) {
            functionID += ", ";
        }
        Log.i(CORONA_TAG, msgType + functionID + errorMsg);
    }

    private boolean isSDKInitialized() {
        return fInitSuccess;
    }

    // Like isSDKInitialized(), but tells the developer why the call is being ignored.
    // isLoaded() stays silent because apps commonly poll it while init is still running.
    private boolean isSDKInitializedOrWarn() {
        if (!fInitSuccess) {
            logMsg(WARNING_MSG, "unityads.init() has not completed successfully yet");
            return false;
        }
        return true;
    }

    private void dispatchLuaEvent(final Map<String, Object> event) {
        if (coronaRuntimeTaskDispatcher != null) {
            coronaRuntimeTaskDispatcher.send(new CoronaRuntimeTask() {
                @Override
                public void executeUsing(CoronaRuntime runtime) {
                    try {
                        if (coronaListener == CoronaLua.REFNIL) {
                            return;
                        }

                        LuaState L = runtime.getLuaState();
                        CoronaLua.newEvent(L, EVENT_NAME);
                        boolean hasErrorKey = false;

                        for (String key : event.keySet()) {
                            CoronaLua.pushValue(L, event.get(key));
                            L.setField(-2, key);

                            if (!hasErrorKey) {
                                hasErrorKey = key.equals(CoronaLuaEvent.ISERROR_KEY);
                            }
                        }

                        if (!hasErrorKey) {
                            L.pushBoolean(false);
                            L.setField(-2, CoronaLuaEvent.ISERROR_KEY);
                        }

                        L.pushString(PROVIDER_NAME);
                        L.setField(-2, CoronaLuaEvent.PROVIDER_KEY);

                        CoronaLua.dispatchEvent(L, coronaListener, 0);
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }
            });
        }
    }

    private String getJSONStringForPlacement(String placementId) {
        return getJSONStringForPlacement(placementId, -1, null);
    }

    private String getJSONStringForPlacement(String placementId, int errorCode, String errorMsg) {
        JSONObject data = new JSONObject();
        try {
            if (placementId != null) {
                data.put(DATA_PLACEMENT_ID_KEY, placementId);
            }
            if (errorCode >= 0) {
                data.put(DATA_ERROR_CODE_KEY, errorCode);
                data.put(DATA_ERROR_MSG_KEY, errorMsg != null ? errorMsg : "Unknown error");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return data.toString();
    }

    private Map<String, Object> adEvent(String phase, String adUnitId) {
        Map<String, Object> coronaEvent = new HashMap<>();
        coronaEvent.put(EVENT_PHASE_KEY, phase);
        coronaEvent.put(EVENT_TYPE_KEY, TYPE_UNITYAD);
        coronaEvent.put(EVENT_DATA_KEY, getJSONStringForPlacement(adUnitId));
        return coronaEvent;
    }

    private Map<String, Object> failedEvent(String adUnitId, LevelPlayAdError error, String fallbackMsg) {
        int errorCode = (error != null) ? error.getErrorCode() : -1;
        String errorMsg = (error != null && error.getErrorMessage() != null) ? error.getErrorMessage() : fallbackMsg;

        Map<String, Object> coronaEvent = new HashMap<>();
        coronaEvent.put(EVENT_PHASE_KEY, PHASE_FAILED);
        coronaEvent.put(EVENT_TYPE_KEY, TYPE_UNITYAD);
        coronaEvent.put(CoronaLuaEvent.ISERROR_KEY, true);
        coronaEvent.put(CoronaLuaEvent.RESPONSE_KEY, errorMsg);
        coronaEvent.put(EVENT_DATA_KEY, getJSONStringForPlacement(adUnitId, errorCode, errorMsg));
        return coronaEvent;
    }

    // Runs on the UI thread. Creates the slot for an ad unit on first use and forwards
    // the load to the SDK unless one is already in flight or an ad is already waiting.
    private void loadOnUiThread(String adUnitId, String requestedType) {
        AdSlot slot = adSlots.get(adUnitId);

        // An ad unit is either interstitial or rewarded. Rebuild the slot if the caller switched type.
        if (slot != null && !slot.adType.equals(requestedType)) {
            logMsg(WARNING_MSG, "placementId '" + adUnitId + "' was loaded as '" + slot.adType
                    + "' before, recreating it as '" + requestedType + "'");
            adSlots.remove(adUnitId);
            slot = null;
        }

        if (slot == null) {
            Object ad;
            if (requestedType.equals(AD_TYPE_REWARDED)) {
                LevelPlayRewardedAd rewardedAd = new LevelPlayRewardedAd(adUnitId);
                rewardedAd.setListener(new RewardedAdListener(adUnitId));
                ad = rewardedAd;
            } else {
                LevelPlayInterstitialAd interstitialAd = new LevelPlayInterstitialAd(adUnitId);
                interstitialAd.setListener(new InterstitialAdListener(adUnitId));
                ad = interstitialAd;
            }
            slot = new AdSlot(adUnitId, requestedType, ad);
            adSlots.put(adUnitId, slot);
        }

        // The SDK answers a second loadAd while one is in flight with error 627, so
        // repeated calls are answered here instead of being forwarded.
        if (slot.isLoading) {
            long elapsed = SystemClock.elapsedRealtime() - slot.loadStartedAt;
            if (elapsed < LOAD_STALE_MS) {
                logMsg(WARNING_MSG, "placementId '" + adUnitId + "' is already loading");
                return;
            }
            logMsg(WARNING_MSG, "placementId '" + adUnitId + "' load did not report back in "
                    + (elapsed / 1000) + " seconds, retrying");
        }

        // A loaded, still showable ad is reported right away instead of requesting another one.
        if (slot.isReady && slot.isAdReady()) {
            slot.isLoading = false;
            dispatchLuaEvent(adEvent(PHASE_LOADED, adUnitId));
            return;
        }

        slot.isLoading = true;
        slot.loadStartedAt = SystemClock.elapsedRealtime();
        slot.loadAd();
    }

    // Shared by both listeners: a load failure, with the "load already called" case
    // (error 627) mapped back to a loaded event when the SDK still holds a showable ad.
    private void handleLoadFailed(String adUnitId, LevelPlayAdError error) {
        AdSlot slot = adSlots.get(adUnitId);

        if (slot != null) {
            slot.isLoading = false;

            if (error != null
                    && error.getErrorCode() == LevelPlayAdError.ERROR_CODE_LOAD_FAILED_ALREADY_CALLED
                    && slot.isAdReady()) {
                slot.isReady = true;
                dispatchLuaEvent(adEvent(PHASE_LOADED, adUnitId));
                return;
            }

            if (error == null || error.getErrorCode() != LevelPlayAdError.ERROR_CODE_LOAD_FAILED_ALREADY_CALLED) {
                slot.isReady = false;
            }
        }

        dispatchLuaEvent(failedEvent(adUnitId, error, "Load failed"));
    }

    // -------------------------------------------------------------------
    // Plugin implementation
    // -------------------------------------------------------------------

    // [Lua] unityads.init(listener, options)
    public class Init implements NamedJavaFunction {
        @Override
        public String getName() {
            return "init";
        }

        @Override
        public int invoke(final LuaState luaState) {
            synchronized (adSlots) {
                if (fInitStarted) {
                    logMsg(ERROR_MSG, "init() should only be called once");
                    return 0;
                }
                fInitStarted = true;
                functionSignature = "unityads.init(listener, options)";

                int nargs = luaState.getTop();
                if (nargs != 2) {
                    logMsg(ERROR_MSG, "2 arguments expected. got " + nargs);
                    return 0;
                }

                String gameId = null;
                boolean testMode = false;

                if (CoronaLua.isListener(luaState, 1, PROVIDER_NAME)) {
                    coronaListener = CoronaLua.newRef(luaState, 1);
                } else {
                    logMsg(ERROR_MSG, "listener function expected, got: " + luaState.typeName(1));
                    return 0;
                }

                if (luaState.type(2) == LuaType.TABLE) {
                    for (luaState.pushNil(); luaState.next(2); luaState.pop(1)) {
                        String key = luaState.toString(-2);

                        switch (key) {
                            case "gameId":
                                if (luaState.type(-1) == LuaType.STRING) {
                                    gameId = luaState.toString(-1);
                                } else {
                                    logMsg(ERROR_MSG, "options.gameId expected (string). Got " + luaState.typeName(-1));
                                    return 0;
                                }
                                break;
                            case "testMode":
                                if (luaState.type(-1) == LuaType.BOOLEAN) {
                                    testMode = luaState.toBoolean(-1);
                                } else {
                                    logMsg(ERROR_MSG, "options.testMode expected (boolean). Got " + luaState.typeName(-1));
                                    return 0;
                                }
                                break;
                            default:
                                logMsg(ERROR_MSG, "Invalid option '" + key + "'");
                                return 0;
                        }
                    }
                } else {
                    logMsg(ERROR_MSG, "options table expected. Got " + luaState.typeName(2));
                    return 0;
                }

                if (gameId == null) {
                    logMsg(ERROR_MSG, "options.gameId is required");
                    return 0;
                }

                Log.i(CORONA_TAG, PLUGIN_NAME + ": " + PLUGIN_VERSION + " (LevelPlay SDK " + LevelPlay.getSdkVersion() + ")");

                final CoronaActivity coronaActivity = CoronaEnvironment.getCoronaActivity();
                final String fGameId = gameId;
                final boolean fTestMode = testMode;

                if (coronaActivity != null) {
                    coronaActivity.runOnUiThread(new Runnable() {
                        public void run() {
                            try {
                                if (fTestMode) {
                                    LevelPlay.setAdaptersDebug(true);
                                }

                                LevelPlayInitRequest initRequest = new LevelPlayInitRequest.Builder(fGameId)
                                        .build();

                                LevelPlay.init(coronaActivity, initRequest, new LevelPlayInitListener() {
                                    @Override
                                    public void onInitSuccess(LevelPlayConfiguration configuration) {
                                        fInitSuccess = true;
                                        Map<String, Object> coronaEvent = new HashMap<>();
                                        coronaEvent.put(EVENT_PHASE_KEY, PHASE_INIT);
                                        dispatchLuaEvent(coronaEvent);
                                    }

                                    @Override
                                    public void onInitFailed(LevelPlayInitError error) {
                                        Map<String, Object> coronaEvent = new HashMap<>();
                                        coronaEvent.put(EVENT_PHASE_KEY, PHASE_INIT);
                                        coronaEvent.put(CoronaLuaEvent.ISERROR_KEY, true);
                                        coronaEvent.put(EVENT_DATA_KEY, getJSONStringForPlacement(null,
                                                error.getErrorCode(),
                                                error.getErrorMessage()));
                                        dispatchLuaEvent(coronaEvent);
                                    }
                                });
                            } catch (Throwable ignore) {
                                Map<String, Object> coronaEvent = new HashMap<>();
                                coronaEvent.put(EVENT_PHASE_KEY, PHASE_INIT);
                                coronaEvent.put(CoronaLuaEvent.ISERROR_KEY, true);
                                coronaEvent.put(EVENT_DATA_KEY, getJSONStringForPlacement(null, -1, "OutOfMemory"));
                                dispatchLuaEvent(coronaEvent);
                            }
                        }
                    });
                }

                return 0;
            }
        }
    }

    // [Lua] unityads.isLoaded(placementId)
    public class IsLoaded implements NamedJavaFunction {
        @Override
        public String getName() {
            return "isLoaded";
        }

        @Override
        public int invoke(LuaState luaState) {
            functionSignature = "unityads.isLoaded(placementId)";

            if (!isSDKInitialized()) {
                return 0;
            }

            int nArgs = luaState.getTop();
            if (nArgs != 1) {
                logMsg(ERROR_MSG, "Expected 1 argument, got " + nArgs);
                return 0;
            }

            String placementId;
            if (luaState.type(1) == LuaType.STRING) {
                placementId = luaState.toString(1);
            } else {
                logMsg(ERROR_MSG, "placementId expected (string), got " + luaState.typeName(1));
                return 0;
            }

            // Ready means the SDK reported a load and still considers the ad showable
            // (not shown, not expired, not capped).
            AdSlot slot = adSlots.get(placementId);
            boolean isLoaded = (slot != null && slot.isReady && slot.isAdReady());
            luaState.pushBoolean(isLoaded);
            return 1;
        }
    }

    // [Lua] unityads.load(placementId [, adType])
    public class Load implements NamedJavaFunction {
        @Override
        public String getName() {
            return "load";
        }

        @Override
        public int invoke(LuaState luaState) {
            functionSignature = "unityads.load(placementId [, adType])";

            if (!isSDKInitializedOrWarn()) {
                return 0;
            }

            int nargs = luaState.getTop();
            if (nargs < 1 || nargs > 2) {
                logMsg(ERROR_MSG, "Expected 1-2 arguments, got " + nargs);
                return 0;
            }

            String placementId;
            if (luaState.type(1) == LuaType.STRING) {
                placementId = luaState.toString(1);
            } else {
                logMsg(ERROR_MSG, "placementId expected (string), got " + luaState.typeName(1));
                return 0;
            }

            String adType = AD_TYPE_INTERSTITIAL;
            if (nargs >= 2) {
                if (luaState.type(2) == LuaType.STRING) {
                    adType = luaState.toString(2);
                } else {
                    logMsg(ERROR_MSG, "adType expected (string), got " + luaState.typeName(2));
                    return 0;
                }
            }

            final CoronaActivity coronaActivity = CoronaEnvironment.getCoronaActivity();
            final String fPlacementId = placementId;
            final String fAdType = adType.equals(AD_TYPE_REWARDED) ? AD_TYPE_REWARDED : AD_TYPE_INTERSTITIAL;

            if (coronaActivity != null) {
                coronaActivity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        loadOnUiThread(fPlacementId, fAdType);
                    }
                });
            }

            return 0;
        }
    }

    // [Lua] unityads.show(placementId)
    public class Show implements NamedJavaFunction {
        @Override
        public String getName() {
            return "show";
        }

        @Override
        public int invoke(LuaState luaState) {
            functionSignature = "unityads.show(placementId)";

            if (!isSDKInitializedOrWarn()) {
                return 0;
            }

            int nargs = luaState.getTop();
            if (nargs != 1) {
                logMsg(ERROR_MSG, "Expected 1 argument, got " + nargs);
                return 0;
            }

            String placementId;
            if (luaState.type(1) == LuaType.STRING) {
                placementId = luaState.toString(1);
            } else {
                logMsg(ERROR_MSG, "placementId expected (string), got " + luaState.typeName(1));
                return 0;
            }

            final AdSlot slot = adSlots.get(placementId);
            if (slot == null || !slot.isReady) {
                logMsg(WARNING_MSG, "placementId '" + placementId + "' not loaded");
                return 0;
            }

            final CoronaActivity coronaActivity = CoronaEnvironment.getCoronaActivity();

            if (coronaActivity != null) {
                // An expired or capped ad is reported by the SDK through onAdDisplayFailed as a "failed" event.
                coronaActivity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        slot.showAd(coronaActivity);
                    }
                });
            }

            return 0;
        }
    }

    // [Lua] unityads.setHasUserConsent( bool )
    private class SetHasUserConsent implements NamedJavaFunction {
        @Override
        public String getName() {
            return "setHasUserConsent";
        }

        @Override
        public int invoke(LuaState L) {
            functionSignature = "unityads.setHasUserConsent( bool )";

            int nargs = L.getTop();
            if (nargs != 1) {
                logMsg(ERROR_MSG, "Expected 1 argument, got " + nargs);
                return 0;
            }

            boolean hasUserConsent;
            if (L.type(1) == LuaType.BOOLEAN) {
                hasUserConsent = L.toBoolean(1);
            } else {
                logMsg(ERROR_MSG, "setHasUserConsent (bool) expected, got " + L.typeName(1));
                return 0;
            }

            LevelPlayPrivacySettings.setGDPRConsent(hasUserConsent);

            return 0;
        }
    }

    // [Lua] unityads.setPersonalizedAds( bool )
    private class SetPersonalizedAds implements NamedJavaFunction {
        @Override
        public String getName() {
            return "setPersonalizedAds";
        }

        @Override
        public int invoke(LuaState L) {
            functionSignature = "unityads.setPersonalizedAds( bool )";

            int nargs = L.getTop();
            if (nargs != 1) {
                logMsg(ERROR_MSG, "Expected 1 argument, got " + nargs);
                return 0;
            }

            boolean setPersonalizedAds;
            if (L.type(1) == LuaType.BOOLEAN) {
                setPersonalizedAds = L.toBoolean(1);
            } else {
                logMsg(ERROR_MSG, "setPersonalizedAds (bool) expected, got " + L.typeName(1));
                return 0;
            }

            // Documented semantics (inherited from the Unity Ads "user.nonbehavioral" flag):
            // true means the user may NOT receive personalized ads, so GDPR consent is withheld.
            LevelPlayPrivacySettings.setGDPRConsent(!setPersonalizedAds);

            return 0;
        }
    }

    // [Lua] unityads.setPrivacyMode( privacyMode )
    private class SetPrivacyMode implements NamedJavaFunction {
        @Override
        public String getName() {
            return "setPrivacyMode";
        }

        @Override
        public int invoke(LuaState L) {
            functionSignature = "unityads.setPrivacyMode( privacyMode )";

            int nargs = L.getTop();
            if (nargs != 1) {
                logMsg(ERROR_MSG, "Expected 1 argument, got " + nargs);
                return 0;
            }

            String privacyMode;
            if (L.type(1) == LuaType.STRING) {
                privacyMode = L.toString(1);
            } else {
                logMsg(ERROR_MSG, "setPrivacyMode (string) expected, got " + L.typeName(1));
                return 0;
            }

            // "app" and "mixed" audiences are treated as child directed. LevelPlay requires this
            // flag before initialization, which matches the documented usage of this function.
            boolean childDirected = privacyMode.equals("app") || privacyMode.equals("mixed");
            LevelPlayPrivacySettings.setCOPPA(childDirected);

            return 0;
        }
    }

    // -------------------------------------------------------------------
    // LevelPlay Ad Listeners (callbacks arrive on the UI thread)
    // -------------------------------------------------------------------

    private class InterstitialAdListener implements LevelPlayInterstitialAdListener {
        private final String adUnitId;

        InterstitialAdListener(String adUnitId) {
            this.adUnitId = adUnitId;
        }

        @Override
        public void onAdLoaded(LevelPlayAdInfo adInfo) {
            AdSlot slot = adSlots.get(adUnitId);
            if (slot != null) {
                slot.isLoading = false;
                slot.isReady = true;
            }
            dispatchLuaEvent(adEvent(PHASE_LOADED, adUnitId));
        }

        @Override
        public void onAdLoadFailed(LevelPlayAdError error) {
            handleLoadFailed(adUnitId, error);
        }

        @Override
        public void onAdDisplayed(LevelPlayAdInfo adInfo) {
            AdSlot slot = adSlots.get(adUnitId);
            if (slot != null) {
                slot.isReady = false;
            }
            dispatchLuaEvent(adEvent(PHASE_DISPLAYED, adUnitId));
        }

        @Override
        public void onAdDisplayFailed(LevelPlayAdError error, LevelPlayAdInfo adInfo) {
            AdSlot slot = adSlots.get(adUnitId);
            if (slot != null) {
                slot.isReady = false;
            }
            dispatchLuaEvent(failedEvent(adUnitId, error, "Display failed"));
        }

        @Override
        public void onAdClicked(LevelPlayAdInfo adInfo) {
            dispatchLuaEvent(adEvent(PHASE_CLICKED, adUnitId));
        }

        @Override
        public void onAdClosed(LevelPlayAdInfo adInfo) {
            // The ad object stays in its slot; the next unityads.load() reuses it.
            dispatchLuaEvent(adEvent(PHASE_COMPLETED, adUnitId));
        }

        @Override
        public void onAdInfoChanged(LevelPlayAdInfo adInfo) {
            // Not mapped to existing API
        }
    }

    private class RewardedAdListener implements LevelPlayRewardedAdListener {
        private final String adUnitId;

        RewardedAdListener(String adUnitId) {
            this.adUnitId = adUnitId;
        }

        @Override
        public void onAdLoaded(LevelPlayAdInfo adInfo) {
            AdSlot slot = adSlots.get(adUnitId);
            if (slot != null) {
                slot.isLoading = false;
                slot.isReady = true;
            }
            dispatchLuaEvent(adEvent(PHASE_LOADED, adUnitId));
        }

        @Override
        public void onAdLoadFailed(LevelPlayAdError error) {
            handleLoadFailed(adUnitId, error);
        }

        @Override
        public void onAdDisplayed(LevelPlayAdInfo adInfo) {
            AdSlot slot = adSlots.get(adUnitId);
            if (slot != null) {
                slot.isReady = false;
                slot.rewardEarned = false;
            }
            dispatchLuaEvent(adEvent(PHASE_DISPLAYED, adUnitId));
        }

        @Override
        public void onAdDisplayFailed(LevelPlayAdError error, LevelPlayAdInfo adInfo) {
            AdSlot slot = adSlots.get(adUnitId);
            if (slot != null) {
                slot.isReady = false;
            }
            dispatchLuaEvent(failedEvent(adUnitId, error, "Display failed"));
        }

        @Override
        public void onAdClicked(LevelPlayAdInfo adInfo) {
            dispatchLuaEvent(adEvent(PHASE_CLICKED, adUnitId));
        }

        @Override
        public void onAdRewarded(LevelPlayReward reward, LevelPlayAdInfo adInfo) {
            AdSlot slot = adSlots.get(adUnitId);
            if (slot != null) {
                slot.rewardEarned = true;
            }
        }

        @Override
        public void onAdClosed(LevelPlayAdInfo adInfo) {
            AdSlot slot = adSlots.get(adUnitId);

            // If a reward was received the ad was completed; otherwise it was skipped
            boolean rewarded = (slot != null && slot.rewardEarned);
            if (slot != null) {
                slot.rewardEarned = false;
            }

            // The ad object stays in its slot; the next unityads.load() reuses it.
            dispatchLuaEvent(adEvent(rewarded ? PHASE_COMPLETED : PHASE_SKIPPED, adUnitId));
        }

        @Override
        public void onAdInfoChanged(LevelPlayAdInfo adInfo) {
            // Not mapped to existing API
        }
    }
}
