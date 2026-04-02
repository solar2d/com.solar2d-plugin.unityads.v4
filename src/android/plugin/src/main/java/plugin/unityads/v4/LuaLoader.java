// LuaLoader.java
// UnityAds Plugin (LevelPlay Backend)
//

package plugin.unityads.v4;

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
import com.unity3d.mediation.interstitial.LevelPlayInterstitialAd;
import com.unity3d.mediation.interstitial.LevelPlayInterstitialAdListener;
import com.unity3d.mediation.rewarded.LevelPlayReward;
import com.unity3d.mediation.rewarded.LevelPlayRewardedAd;
import com.unity3d.mediation.rewarded.LevelPlayRewardedAdListener;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Implements the Lua interface for the UnityAds plugin.
 * <p>
 * Only one instance of this class will be created by Corona for the lifetime of the application.
 * This instance will be re-used for every new Corona activity that gets created.
 */
@SuppressWarnings({"unused", "RedundantSuppression"})
public class LuaLoader implements JavaFunction, CoronaRuntimeListener {
    private static final String PLUGIN_NAME = "plugin.unityads.v4";
    private static final String PLUGIN_VERSION = "2.0.0";

    private static final String EVENT_NAME = "adsRequest";
    private static final String PROVIDER_NAME = "unityads";

    // event types
    private static final String TYPE_UNITYAD = "unityAd";

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

    private static int coronaListener = CoronaLua.REFNIL;
    private static CoronaRuntimeTaskDispatcher coronaRuntimeTaskDispatcher = null;

    // message constants
    private static final String CORONA_TAG = "Corona";
    private static final String ERROR_MSG = "ERROR: ";
    private static final String WARNING_MSG = "WARNING: ";

    private static String functionSignature = "";
    // Store ad objects keyed by adUnitId
    private static final Map<String, Object> adObjects = new HashMap<>();
    // Track which ad unit IDs are loaded and ready
    private static final Set<String> loadedIds = new HashSet<>();
    // Track which ad unit IDs received a reward (for skipped vs completed)
    private static final Set<String> rewardedIds = new HashSet<>();
    private static boolean fInitSuccess = false;
    private static boolean fInitStarted = false;

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
        adObjects.clear();
        loadedIds.clear();
        rewardedIds.clear();
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

    private void dispatchLuaEvent(final Map<String, Object> event) {
        if (coronaRuntimeTaskDispatcher != null) {
            coronaRuntimeTaskDispatcher.send(new CoronaRuntimeTask() {
                @Override
                public void executeUsing(CoronaRuntime runtime) {
                    try {
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
            synchronized (adObjects) {
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

                Log.i(CORONA_TAG, PLUGIN_NAME + ": " + PLUGIN_VERSION + " (LevelPlay)");

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

            boolean isLoaded = loadedIds.contains(placementId);
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

            if (!isSDKInitialized()) {
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

            String adType = "interstitial";
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
            final String fAdType = adType;

            if (coronaActivity != null) {
                coronaActivity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (fAdType.equals("rewarded")) {
                            LevelPlayRewardedAd rewardedAd = new LevelPlayRewardedAd(fPlacementId);
                            rewardedAd.setListener(new RewardedAdListener(fPlacementId));
                            adObjects.put(fPlacementId, rewardedAd);
                            rewardedAd.loadAd();
                        } else {
                            LevelPlayInterstitialAd interstitialAd = new LevelPlayInterstitialAd(fPlacementId);
                            interstitialAd.setListener(new InterstitialAdListener(fPlacementId));
                            adObjects.put(fPlacementId, interstitialAd);
                            interstitialAd.loadAd();
                        }
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

            if (!isSDKInitialized()) {
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

            if (!loadedIds.contains(placementId)) {
                logMsg(WARNING_MSG, "placementId '" + placementId + "' not loaded");
                return 0;
            }

            final CoronaActivity coronaActivity = CoronaEnvironment.getCoronaActivity();
            final String fPlacementId = placementId;

            if (coronaActivity != null) {
                coronaActivity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Object adObject = adObjects.get(fPlacementId);
                        if (adObject instanceof LevelPlayInterstitialAd) {
                            ((LevelPlayInterstitialAd) adObject).showAd(coronaActivity);
                        } else if (adObject instanceof LevelPlayRewardedAd) {
                            ((LevelPlayRewardedAd) adObject).showAd(coronaActivity);
                        } else {
                            logMsg(WARNING_MSG, "No ad object found for '" + fPlacementId + "'");
                        }
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

            LevelPlay.setConsent(hasUserConsent);

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

            // In LevelPlay, consent controls personalization
            LevelPlay.setConsent(!setPersonalizedAds);

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

            // Map privacy modes to LevelPlay COPPA setting
            if (privacyMode.equals("app")) {
                LevelPlay.setMetaData("is_child_directed", "true");
            } else if (privacyMode.equals("mixed")) {
                LevelPlay.setMetaData("is_child_directed", "true");
            } else {
                LevelPlay.setMetaData("is_child_directed", "false");
            }

            return 0;
        }
    }

    // -------------------------------------------------------------------
    // LevelPlay Ad Listeners
    // -------------------------------------------------------------------

    private class InterstitialAdListener implements LevelPlayInterstitialAdListener {
        private final String adUnitId;

        InterstitialAdListener(String adUnitId) {
            this.adUnitId = adUnitId;
        }

        @Override
        public void onAdLoaded(LevelPlayAdInfo adInfo) {
            loadedIds.add(adUnitId);
            Map<String, Object> coronaEvent = new HashMap<>();
            coronaEvent.put(EVENT_PHASE_KEY, PHASE_LOADED);
            coronaEvent.put(EVENT_TYPE_KEY, TYPE_UNITYAD);
            coronaEvent.put(EVENT_DATA_KEY, getJSONStringForPlacement(adUnitId));
            dispatchLuaEvent(coronaEvent);
        }

        @Override
        public void onAdLoadFailed(LevelPlayAdError error) {
            loadedIds.remove(adUnitId);
            adObjects.remove(adUnitId);
            Map<String, Object> coronaEvent = new HashMap<>();
            coronaEvent.put(EVENT_PHASE_KEY, PHASE_FAILED);
            coronaEvent.put(EVENT_TYPE_KEY, TYPE_UNITYAD);
            coronaEvent.put(CoronaLuaEvent.ISERROR_KEY, true);
            coronaEvent.put(CoronaLuaEvent.RESPONSE_KEY, error.getErrorMessage());
            coronaEvent.put(EVENT_DATA_KEY, getJSONStringForPlacement(adUnitId,
                    error.getErrorCode(), error.getErrorMessage()));
            dispatchLuaEvent(coronaEvent);
        }

        @Override
        public void onAdDisplayed(LevelPlayAdInfo adInfo) {
            loadedIds.remove(adUnitId);
            Map<String, Object> coronaEvent = new HashMap<>();
            coronaEvent.put(EVENT_PHASE_KEY, PHASE_DISPLAYED);
            coronaEvent.put(EVENT_TYPE_KEY, TYPE_UNITYAD);
            coronaEvent.put(EVENT_DATA_KEY, getJSONStringForPlacement(adUnitId));
            dispatchLuaEvent(coronaEvent);
        }

        @Override
        public void onAdDisplayFailed(LevelPlayAdError error, LevelPlayAdInfo adInfo) {
            loadedIds.remove(adUnitId);
            adObjects.remove(adUnitId);
            Map<String, Object> coronaEvent = new HashMap<>();
            coronaEvent.put(EVENT_PHASE_KEY, PHASE_FAILED);
            coronaEvent.put(EVENT_TYPE_KEY, TYPE_UNITYAD);
            coronaEvent.put(CoronaLuaEvent.ISERROR_KEY, true);
            coronaEvent.put(CoronaLuaEvent.RESPONSE_KEY, error.getErrorMessage());
            coronaEvent.put(EVENT_DATA_KEY, getJSONStringForPlacement(adUnitId,
                    error.getErrorCode(), error.getErrorMessage()));
            dispatchLuaEvent(coronaEvent);
        }

        @Override
        public void onAdClicked(LevelPlayAdInfo adInfo) {
            Map<String, Object> coronaEvent = new HashMap<>();
            coronaEvent.put(EVENT_PHASE_KEY, PHASE_CLICKED);
            coronaEvent.put(EVENT_TYPE_KEY, TYPE_UNITYAD);
            coronaEvent.put(EVENT_DATA_KEY, getJSONStringForPlacement(adUnitId));
            dispatchLuaEvent(coronaEvent);
        }

        @Override
        public void onAdClosed(LevelPlayAdInfo adInfo) {
            adObjects.remove(adUnitId);
            Map<String, Object> coronaEvent = new HashMap<>();
            coronaEvent.put(EVENT_PHASE_KEY, PHASE_COMPLETED);
            coronaEvent.put(EVENT_TYPE_KEY, TYPE_UNITYAD);
            coronaEvent.put(EVENT_DATA_KEY, getJSONStringForPlacement(adUnitId));
            dispatchLuaEvent(coronaEvent);
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
            loadedIds.add(adUnitId);
            Map<String, Object> coronaEvent = new HashMap<>();
            coronaEvent.put(EVENT_PHASE_KEY, PHASE_LOADED);
            coronaEvent.put(EVENT_TYPE_KEY, TYPE_UNITYAD);
            coronaEvent.put(EVENT_DATA_KEY, getJSONStringForPlacement(adUnitId));
            dispatchLuaEvent(coronaEvent);
        }

        @Override
        public void onAdLoadFailed(LevelPlayAdError error) {
            loadedIds.remove(adUnitId);
            adObjects.remove(adUnitId);
            Map<String, Object> coronaEvent = new HashMap<>();
            coronaEvent.put(EVENT_PHASE_KEY, PHASE_FAILED);
            coronaEvent.put(EVENT_TYPE_KEY, TYPE_UNITYAD);
            coronaEvent.put(CoronaLuaEvent.ISERROR_KEY, true);
            coronaEvent.put(CoronaLuaEvent.RESPONSE_KEY, error.getErrorMessage());
            coronaEvent.put(EVENT_DATA_KEY, getJSONStringForPlacement(adUnitId,
                    error.getErrorCode(), error.getErrorMessage()));
            dispatchLuaEvent(coronaEvent);
        }

        @Override
        public void onAdDisplayed(LevelPlayAdInfo adInfo) {
            loadedIds.remove(adUnitId);
            rewardedIds.remove(adUnitId);
            Map<String, Object> coronaEvent = new HashMap<>();
            coronaEvent.put(EVENT_PHASE_KEY, PHASE_DISPLAYED);
            coronaEvent.put(EVENT_TYPE_KEY, TYPE_UNITYAD);
            coronaEvent.put(EVENT_DATA_KEY, getJSONStringForPlacement(adUnitId));
            dispatchLuaEvent(coronaEvent);
        }

        @Override
        public void onAdDisplayFailed(LevelPlayAdError error, LevelPlayAdInfo adInfo) {
            loadedIds.remove(adUnitId);
            adObjects.remove(adUnitId);
            Map<String, Object> coronaEvent = new HashMap<>();
            coronaEvent.put(EVENT_PHASE_KEY, PHASE_FAILED);
            coronaEvent.put(EVENT_TYPE_KEY, TYPE_UNITYAD);
            coronaEvent.put(CoronaLuaEvent.ISERROR_KEY, true);
            coronaEvent.put(CoronaLuaEvent.RESPONSE_KEY, error.getErrorMessage());
            coronaEvent.put(EVENT_DATA_KEY, getJSONStringForPlacement(adUnitId,
                    error.getErrorCode(), error.getErrorMessage()));
            dispatchLuaEvent(coronaEvent);
        }

        @Override
        public void onAdClicked(LevelPlayAdInfo adInfo) {
            Map<String, Object> coronaEvent = new HashMap<>();
            coronaEvent.put(EVENT_PHASE_KEY, PHASE_CLICKED);
            coronaEvent.put(EVENT_TYPE_KEY, TYPE_UNITYAD);
            coronaEvent.put(EVENT_DATA_KEY, getJSONStringForPlacement(adUnitId));
            dispatchLuaEvent(coronaEvent);
        }

        @Override
        public void onAdRewarded(LevelPlayReward reward, LevelPlayAdInfo adInfo) {
            rewardedIds.add(adUnitId);
        }

        @Override
        public void onAdClosed(LevelPlayAdInfo adInfo) {
            adObjects.remove(adUnitId);
            Map<String, Object> coronaEvent = new HashMap<>();
            coronaEvent.put(EVENT_TYPE_KEY, TYPE_UNITYAD);
            coronaEvent.put(EVENT_DATA_KEY, getJSONStringForPlacement(adUnitId));

            // If reward was received, it's completed; otherwise skipped
            if (rewardedIds.contains(adUnitId)) {
                coronaEvent.put(EVENT_PHASE_KEY, PHASE_COMPLETED);
                rewardedIds.remove(adUnitId);
            } else {
                coronaEvent.put(EVENT_PHASE_KEY, PHASE_SKIPPED);
            }

            dispatchLuaEvent(coronaEvent);
        }

        @Override
        public void onAdInfoChanged(LevelPlayAdInfo adInfo) {
            // Not mapped to existing API
        }
    }
}
