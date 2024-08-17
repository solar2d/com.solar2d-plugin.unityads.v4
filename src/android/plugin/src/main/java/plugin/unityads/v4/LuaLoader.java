// LuaLoader.java
// UnityAds Plugin
//

package plugin.unityads.v4;

import android.util.Log;
import android.view.View;

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
import com.unity3d.ads.IUnityAdsInitializationListener;
import com.unity3d.ads.IUnityAdsLoadListener;
import com.unity3d.ads.IUnityAdsShowListener;
import com.unity3d.ads.UnityAds;
import com.unity3d.ads.metadata.MetaData;
import com.unity3d.services.banners.IUnityBannerListener;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Implements the Lua interface for the UnityAds plugin.
 * <p>
 * Only one instance of this class will be created by Corona for the lifetime of the application.
 * This instance will be re-used for every new Corona activity that gets created.
 */
@SuppressWarnings({"unused", "RedundantSuppression"})
public class LuaLoader implements JavaFunction, CoronaRuntimeListener {
    private static final String PLUGIN_NAME = "plugin.unityads.v4";
    private static final String PLUGIN_VERSION = "1.0.2";
    private static final String PLUGIN_SDK_VERSION = UnityAds.getVersion();

    private static final String EVENT_NAME = "adsRequest";
    private static final String PROVIDER_NAME = "unityads";

    // event types
    private static final String TYPE_UNITYAD = "unityAd";

    // data keys
    private static final String DATA_PLACEMENT_ID_KEY = "placementId";
    private static final String DATA_ERROR_MSG_KEY = "errorMsg";
    private static final String DATA_ERROR_CODE_KEY = "errorCode";
    private static final String DATA_STATUS_CODE_KEY = "statusCode";
    private static final String DATA_STATUS_INFO_KEY = "statusInfo";

    // add missing keys
    private static final String EVENT_PHASE_KEY = "phase";
    private static final String EVENT_TYPE_KEY = "type";
    private static final String EVENT_DATA_KEY = "data";

    // response keys
    private static final String RESPONSE_SHOW_FAILED = "showFailed";

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

    private static String functionSignature = "";                                  // used in error reporting functions
    //keep track of loadedIds
    private static final List<String> loadedIds = new ArrayList<>();
    private static boolean fInitSuccess = false;
    private static boolean fInitStarted = false;

    // -------------------------------------------------------------------
    // Plugin lifecycle events
    // -------------------------------------------------------------------

    /**
     * <p>
     * Note that a new LuaLoader instance will not be created for every CoronaActivity instance.
     * That is, only one instance of this class will be created for the lifetime of the application process.
     * This gives a plugin the option to do operations in the background while the CoronaActivity is destroyed.
     */
    public LuaLoader() {
        // Set up this plugin to listen for Corona runtime events to be received by methods
        // onLoaded(), onStarted(), onSuspended(), onResumed(), and onExiting().
        CoronaEnvironment.addRuntimeListener(this);
    }

    /**
     * Called when this plugin is being loaded via the Lua require() function.
     * <p>
     * Note that this method will be called every time a new CoronaActivity has been launched.
     * This means that you'll need to re-initialize this plugin here.
     * <p>
     * Warning! This method is not called on the main UI thread.
     *
     * @param L Reference to the Lua state that the require() function was called from.
     * @return Returns the number of values that the require() function will return.
     * <p>
     * Expected to return 1, the library that the require() function is loading.
     */
    @Override
    public int invoke(LuaState L) {
        // Register this plugin into Lua with the following functions.
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

        // Returning 1 indicates that the Lua require() function will return the above Lua
        return 1;
    }

    /**
     * Called after the Corona runtime has been created and just before executing the "main.lua" file.
     * <p>
     * Warning! This method is not called on the main thread.
     *
     * @param runtime Reference to the CoronaRuntime object that has just been loaded/initialized.
     *                Provides a LuaState object that allows the application to extend the Lua API.
     */
    @Override
    public void onLoaded(CoronaRuntime runtime) {
        // Note that this method will not be called the first time a Corona activity has been launched.
        // This is because this listener cannot be added to the CoronaEnvironment until after
        // this plugin has been required-in by Lua, which occurs after the onLoaded() event.
        // However, this method will be called when a 2nd Corona activity has been created.

        if (coronaRuntimeTaskDispatcher == null) {
            coronaRuntimeTaskDispatcher = new CoronaRuntimeTaskDispatcher(runtime);
        }
    }

    /**
     * Called just after the Corona runtime has executed the "main.lua" file.
     * <p>
     * Warning! This method is not called on the main thread.
     *
     * @param runtime Reference to the CoronaRuntime object that has just been started.
     */
    @Override
    public void onStarted(CoronaRuntime runtime) {
    }

    /**
     * Called just after the Corona runtime has been suspended which pauses all rendering, audio, timers,
     * and other Corona related operations. This can happen when another Android activity (ie: window) has
     * been displayed, when the screen has been powered off, or when the screen lock is shown.
     * <p>
     * Warning! This method is not called on the main thread.
     *
     * @param runtime Reference to the CoronaRuntime object that has just been suspended.
     */
    @Override
    public void onSuspended(CoronaRuntime runtime) {
    }

    /**
     * Called just after the Corona runtime has been resumed after a suspend.
     * <p>
     * Warning! This method is not called on the main thread.
     *
     * @param runtime Reference to the CoronaRuntime object that has just been resumed.
     */
    @Override
    public void onResumed(CoronaRuntime runtime) {
    }

    /**
     * Called just before the Corona runtime terminates.
     * <p>
     * This happens when the Corona activity is being destroyed which happens when the user presses the Back button
     * on the activity, when the native.requestExit() method is called in Lua, or when the activity's finish()
     * method is called. This does not mean that the application is exiting.
     * <p>
     * Warning! This method is not called on the main thread.
     *
     * @param runtime Reference to the CoronaRuntime object that is being terminated.
     */
    @Override
    public void onExiting(CoronaRuntime runtime) {
        loadedIds.clear();
        CoronaLua.deleteRef(runtime.getLuaState(), coronaListener);
        coronaListener = CoronaLua.REFNIL;
        coronaRuntimeTaskDispatcher = null;
        fInitSuccess = false;
        fInitStarted = false;

    }

    // -------------------------------------------------------------------
    // helper functions
    // -------------------------------------------------------------------

    // log message to console
    private void logMsg(String msgType, String errorMsg) {
        String functionID = functionSignature;
        if (!functionID.isEmpty()) {
            functionID += ", ";
        }

        Log.i(CORONA_TAG, msgType + functionID + errorMsg);
    }

    // return true if SDK is properly initialized
    private boolean isSDKInitialized() {
        return fInitSuccess;
    }

    // dispatch a Lua event to our callback (dynamic handling of properties through map)
    private void dispatchLuaEvent(final Map<String, Object> event) {
        if (coronaRuntimeTaskDispatcher != null) {
            coronaRuntimeTaskDispatcher.send(new CoronaRuntimeTask() {
                @Override
                public void executeUsing(CoronaRuntime runtime) {
                    try {
                        LuaState L = runtime.getLuaState();
                        CoronaLua.newEvent(L, EVENT_NAME);
                        boolean hasErrorKey = false;

                        // add event parameters from map
                        for (String key : event.keySet()) {
                            CoronaLua.pushValue(L, event.get(key));           // push value
                            L.setField(-2, key);                              // push key

                            if (!hasErrorKey) {
                                hasErrorKey = key.equals(CoronaLuaEvent.ISERROR_KEY);
                            }
                        }

                        // add error key if not in map
                        if (!hasErrorKey) {
                            L.pushBoolean(false);
                            L.setField(-2, CoronaLuaEvent.ISERROR_KEY);
                        }

                        // add provider
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


    // -------------------------------------------------------------------
    // Plugin implementation
    // -------------------------------------------------------------------

    // [Lua] unityads.init(listener , options)
    public class Init implements NamedJavaFunction {
        /**
         * Gets the name of the Lua function as it would appear in the Lua script.
         *
         * @return Returns the name of the custom Lua function.
         */
        @Override
        public String getName() {
            return "init";
        }

        /**
         * This method is called when the Lua function is called.
         * <p>
         * Warning! This method is not called on the main UI thread.
         *
         * @param luaState Reference to the Lua state.
         *                 Needed to retrieve the Lua function's parameters and to return values back to Lua.
         * @return Returns the number of values to be returned by the Lua function.
         */
        @Override
        public int invoke(final LuaState luaState)  {
            synchronized (loadedIds) {
                if(fInitStarted) {
                    logMsg(ERROR_MSG, "init() should only be called once");
                    return 0;
                }
                fInitStarted = true;
                // set function signature for error / warning messages
                functionSignature = "unityads.init(listener, options)";

                // check number of arguments passed
                int nargs = luaState.getTop();
                if (nargs != 2) {
                    logMsg(ERROR_MSG, "2 arguments expected. got " + nargs);
                    return 0;
                }

                String gameId = null;
                boolean testMode = false;

                // get listener (required)
                if (CoronaLua.isListener(luaState, 1, PROVIDER_NAME)) {
                    coronaListener = CoronaLua.newRef(luaState, 1);
                } else {
                    logMsg(ERROR_MSG, "listener function expected, got: " + luaState.typeName(1));
                    return 0;
                }

                // check for options table
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

                // validation section
                if (gameId == null) {
                    logMsg(ERROR_MSG, "options.gameId is required");
                    return 0;
                }

                // log plugin version to the console
                Log.i(CORONA_TAG, PLUGIN_NAME + ": " + PLUGIN_VERSION + " (SDK: " + PLUGIN_SDK_VERSION + ")");

                // declare final variables for inner loop
                final CoronaActivity coronaActivity = CoronaEnvironment.getCoronaActivity();
                final String fGameId = gameId;
                final boolean fTestMode = testMode;

                if (coronaActivity != null) {
                    Runnable runnableActivity = new Runnable() {
                        public void run() {
                            try {
                                UnityAds.initialize(coronaActivity, fGameId, fTestMode, new CoronaUnityAdsDelegate());
                            } catch (Throwable ignore) {
                                Map<String, Object> coronaEvent = new HashMap<>();
                                coronaEvent.put(EVENT_PHASE_KEY, PHASE_INIT);
                                coronaEvent.put(CoronaLuaEvent.ISERROR_KEY, true);
                                coronaEvent.put(EVENT_DATA_KEY, "{\"placementId\":\"" + fGameId + "\", \"errorCode\":-1, \"errorMsg\":\"OutOfMemory\"}");
                                dispatchLuaEvent(coronaEvent);
                            }
                        }
                    };

                    coronaActivity.runOnUiThread(runnableActivity);
                }

                return 0;
            }
        }
    }

    // [Lua] unityads.isLoaded(placementId)
    public class IsLoaded implements NamedJavaFunction {
        /**
         * Gets the name of the Lua function as it would appear in the Lua script.
         *
         * @return Returns the name of the custom Lua function.
         */
        @Override
        public String getName() {
            return "isLoaded";
        }

        /**
         * This method is called when the Lua function is called.
         * <p>
         * Warning! This method is not called on the main UI thread.
         *
         * @param luaState Reference to the Lua state.
         *                 Needed to retrieve the Lua function's parameters and to return values back to Lua.
         * @return Returns the number of values to be returned by the Lua function.
         */
        @Override
        public int invoke(LuaState luaState) {
            functionSignature = "unityads.isLoaded(placementId)";

            if (!isSDKInitialized()) {
                return 0;
            }

            // get number of arguments
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

    // [Lua] unityads.load(placementId)
    public class Load implements NamedJavaFunction {
        /**
         * Gets the name of the Lua function as it would appear in the Lua script.
         *
         * @return Returns the name of the custom Lua function.
         */
        @Override
        public String getName() {
            return "load";
        }

        /**
         * This method is called when the Lua function is called.
         * <p>
         * Warning! This method is not called on the main UI thread.
         *
         * @param luaState Reference to the Lua state.
         *                 Needed to retrieve the Lua function's parameters and to return values back to Lua.
         * @return Returns the number of values to be returned by the Lua function.
         */
        @Override
        public int invoke(LuaState luaState) {
            functionSignature = "unityads.load(placementId)";

            if (!isSDKInitialized()) {
                return 0;
            }

            // get number of arguments
            int nargs = luaState.getTop();
            if (nargs != 1) {
                logMsg(ERROR_MSG, "Expected 1 argument, got " + nargs);
                return 0;
            }

            String placementId;

            // Get the ad type
            if (luaState.type(1) == LuaType.STRING) {
                placementId = luaState.toString(1);
            } else {
                logMsg(ERROR_MSG, "placementId expected (string), got " + luaState.typeName(1));
                return 0;
            }


            // declare final variables for inner loop
            final CoronaActivity coronaActivity = CoronaEnvironment.getCoronaActivity();
            final String fPlacementId = placementId;

            if (coronaActivity != null) {
                coronaActivity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        CoronaUnityAdsDelegate listener = new CoronaUnityAdsDelegate();
                        UnityAds.load(fPlacementId, listener);
                    }
                });
            }

            return 0;
        }
    }

    // [Lua] unityads.show(placementId)
    public class Show implements NamedJavaFunction {
        /**
         * Gets the name of the Lua function as it would appear in the Lua script.
         *
         * @return Returns the name of the custom Lua function.
         */
        @Override
        public String getName() {
            return "show";
        }

        /**
         * This method is called when the Lua function is called.
         * <p>
         * Warning! This method is not called on the main UI thread.
         *
         * @param luaState Reference to the Lua state.
         *                 Needed to retrieve the Lua function's parameters and to return values back to Lua.
         * @return Returns the number of values to be returned by the Lua function.
         */
        @Override
        public int invoke(LuaState luaState) {
            functionSignature = "unityads.show(placementId)";

            if (!isSDKInitialized()) {
                return 0;
            }

            // get number of arguments
            int nargs = luaState.getTop();
            if (nargs != 1) {
                logMsg(ERROR_MSG, "Expected 1 argument, got " + nargs);
                return 0;
            }

            String placementId;

            // Get the ad type
            if (luaState.type(1) == LuaType.STRING) {
                placementId = luaState.toString(1);
            } else {
                logMsg(ERROR_MSG, "placementId expected (string), got " + luaState.typeName(1));
                return 0;
            }

            boolean isLoaded = loadedIds.contains(placementId);

            // can't show unless ad is loaded
            if (!isLoaded) {
                logMsg(WARNING_MSG, "placementId '" + placementId + "' not loaded");
                return 0;
            }

            // declare final variables for inner loop
            final CoronaActivity coronaActivity = CoronaEnvironment.getCoronaActivity();
            final String fPlacementId = placementId;

            if (coronaActivity != null) {
                coronaActivity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        CoronaUnityAdsDelegate listener = new CoronaUnityAdsDelegate();

                        UnityAds.show(coronaActivity, fPlacementId, listener);
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

            if (!isSDKInitialized()) {
                return 0;
            }

            // check number of arguments
            int nargs = L.getTop();
            if (nargs != 1) {
                logMsg(ERROR_MSG, "Expected 1 argument, got " + nargs);
                return 0;
            }

            boolean setHasUserConsent;

            // check options
            if (L.type(1) == LuaType.BOOLEAN) {
                setHasUserConsent = L.toBoolean(1);
            } else {
                logMsg(ERROR_MSG, "setHasUserConsent (bool) expected, got " + L.typeName(1));
                return 0;
            }

            MetaData gdprMetaData = new MetaData(CoronaEnvironment.getApplicationContext());
            gdprMetaData.set("gdpr.consent", setHasUserConsent);
            gdprMetaData.commit();

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

            // check number of arguments
            int nargs = L.getTop();
            if (nargs != 1) {
                logMsg(ERROR_MSG, "Expected 1 argument, got " + nargs);
                return 0;
            }

            boolean setPersonalizedAds;

            // check options
            if (L.type(1) == LuaType.BOOLEAN) {
                setPersonalizedAds = L.toBoolean(1);
            } else {
                logMsg(ERROR_MSG, "setPersonalizedAds (bool) expected, got " + L.typeName(1));
                return 0;
            }

            MetaData gdprMetaData = new MetaData(CoronaEnvironment.getApplicationContext());
            gdprMetaData.set("user.nonbehavioral", setPersonalizedAds);
            gdprMetaData.commit();

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

            // check number of arguments
            int nargs = L.getTop();
            if (nargs != 1) {
                logMsg(ERROR_MSG, "Expected 1 argument, got " + nargs);
                return 0;
            }

            String privacyMode;

            // check options
            if (L.type(1) == LuaType.STRING) {
                privacyMode = L.toString(1);
            } else {
                logMsg(ERROR_MSG, "setPersonalizedAds (string) expected, got " + L.typeName(1));
                return 0;
            }

            MetaData gdprMetaData = new MetaData(CoronaEnvironment.getApplicationContext());
            gdprMetaData.set("privacy.mode", privacyMode);
            gdprMetaData.commit();

            return 0;
        }
    }

    // -------------------------------------------------------------------
    // Delegates
    // -------------------------------------------------------------------

    private class CoronaUnityAdsDelegate implements IUnityAdsLoadListener, IUnityAdsShowListener, IUnityAdsInitializationListener, IUnityBannerListener {
        private String getPlacementErrorInfo(Enum error, String errorType) {
            String errorInfo;

            if (error == UnityAds.UnityAdsInitializationError.INTERNAL_ERROR && errorType.equals("init")) {
                errorInfo = "Initialization failed";
            } else if (error == UnityAds.UnityAdsInitializationError.INVALID_ARGUMENT && errorType.equals("init")) {
                errorInfo = "Invalid parameters during initialization";
            } else if (error == UnityAds.UnityAdsInitializationError.AD_BLOCKER_DETECTED && errorType.equals("init")) {
                errorInfo = "Ad blocker detected";
            } else if (error == UnityAds.UnityAdsShowError.NOT_INITIALIZED && errorType.equals("show")) {
                errorInfo = "UnityAds not initialized";
            } else if (error == UnityAds.UnityAdsShowError.VIDEO_PLAYER_ERROR && errorType.equals("show")) {
                errorInfo = "Video Player failure";
            } else if (error == UnityAds.UnityAdsShowError.INTERNAL_ERROR && errorType.equals("show")) {
                errorInfo = "Internal error";
            } else if (error == UnityAds.UnityAdsShowError.INVALID_ARGUMENT && errorType.equals("show")) {
                errorInfo = "Invalid parameters during show";
            } else if (error == UnityAds.UnityAdsShowError.NO_CONNECTION && errorType.equals("show")) {
                errorInfo = "No Connection";
            } else if (error == UnityAds.UnityAdsShowError.ALREADY_SHOWING && errorType.equals("show")) {
                errorInfo = "Ad already showing";
            } else if (error == UnityAds.UnityAdsShowError.NOT_READY && errorType.equals("show")) {
                errorInfo = "Ad not ready";
            } else if (error == UnityAds.UnityAdsLoadError.INTERNAL_ERROR && errorType.equals("load")) {
                errorInfo = "Internal error";
            } else if (error == UnityAds.UnityAdsLoadError.INVALID_ARGUMENT && errorType.equals("load")) {
                errorInfo = "Invalid parameters during show";
            } else if (error == UnityAds.UnityAdsLoadError.INITIALIZE_FAILED && errorType.equals("load")) {
                errorInfo = "Initialization Failed during load";
            } else if (error == UnityAds.UnityAdsLoadError.TIMEOUT && errorType.equals("load")) {
                errorInfo = "Ad Timeout";
            } else {
                errorInfo = "Unknown error code (" + error + ")";
            }

            return errorInfo;
        }

        private String getJSONStringForPlacement(String placementId) {
            return getJSONStringForPlacement(placementId, null, null);
        }

        private String getJSONStringForPlacement(String placementId, Enum error, String errorType) {
            // create data
            JSONObject data = new JSONObject();
            try {
                if (placementId != null) {
                    data.put(DATA_PLACEMENT_ID_KEY, placementId);
                }

                if (error != null) {
                    data.put(DATA_ERROR_CODE_KEY, error);
                    data.put(DATA_ERROR_MSG_KEY, getPlacementErrorInfo(error, errorType));
                }
            } catch (Exception e) {
                System.err.println();
            }

            return data.toString();
        }




        @Override
        public void onInitializationComplete() {
            fInitSuccess = true;
            Map<String, Object> coronaEvent = new HashMap<>();
            coronaEvent.put(EVENT_PHASE_KEY, PHASE_INIT);
            dispatchLuaEvent(coronaEvent);
        }

        @Override
        public void onInitializationFailed(UnityAds.UnityAdsInitializationError error, String message) {
            Map<String, Object> coronaEvent = new HashMap<>();
            coronaEvent.put(EVENT_PHASE_KEY, PHASE_INIT);
            coronaEvent.put(CoronaLuaEvent.ISERROR_KEY, true);
            coronaEvent.put(EVENT_DATA_KEY, getJSONStringForPlacement(null, error, "init"));
            dispatchLuaEvent(coronaEvent);
        }

        @Override
        public void onUnityAdsAdLoaded(String placementId) {
            if(!loadedIds.contains(placementId)){
                loadedIds.add(placementId);
            }
            Map<String, Object> coronaEvent = new HashMap<>();
            coronaEvent.put(EVENT_PHASE_KEY, PHASE_LOADED);
            coronaEvent.put(EVENT_TYPE_KEY, TYPE_UNITYAD);
            coronaEvent.put(EVENT_DATA_KEY, getJSONStringForPlacement(placementId));
            dispatchLuaEvent(coronaEvent);
        }

        @Override
        public void onUnityAdsFailedToLoad(String placementId, UnityAds.UnityAdsLoadError error, String message) {
            loadedIds.remove(placementId);
            Map<String, Object> coronaEvent = new HashMap<>();
            coronaEvent.put(EVENT_PHASE_KEY, PHASE_FAILED);
            coronaEvent.put(EVENT_TYPE_KEY, TYPE_UNITYAD);
            coronaEvent.put(CoronaLuaEvent.ISERROR_KEY, true);
            coronaEvent.put(CoronaLuaEvent.RESPONSE_KEY, message);
            coronaEvent.put(EVENT_DATA_KEY, getJSONStringForPlacement(null, error, "load"));
            dispatchLuaEvent(coronaEvent);
        }

        @Override
        public void onUnityAdsShowFailure(String placementId, UnityAds.UnityAdsShowError error, String message) {
            loadedIds.remove(placementId);
            Map<String, Object> coronaEvent = new HashMap<>();
            coronaEvent.put(EVENT_PHASE_KEY, PHASE_FAILED);
            coronaEvent.put(EVENT_TYPE_KEY, TYPE_UNITYAD);
            coronaEvent.put(CoronaLuaEvent.ISERROR_KEY, true);
            coronaEvent.put(CoronaLuaEvent.RESPONSE_KEY, message);
            coronaEvent.put(EVENT_DATA_KEY, getJSONStringForPlacement(null, error, "show"));
            dispatchLuaEvent(coronaEvent);
        }

        @Override
        public void onUnityAdsShowStart(String placementId) {
            loadedIds.remove(placementId);
            Map<String, Object> coronaEvent = new HashMap<>();
            coronaEvent.put(EVENT_PHASE_KEY, PHASE_DISPLAYED);
            coronaEvent.put(EVENT_TYPE_KEY, TYPE_UNITYAD);
            coronaEvent.put(EVENT_DATA_KEY, getJSONStringForPlacement(placementId));
            dispatchLuaEvent(coronaEvent);
        }

        @Override
        public void onUnityAdsShowClick(String placementId) {
            Map<String, Object> coronaEvent = new HashMap<>();
            coronaEvent.put(EVENT_PHASE_KEY, PHASE_CLICKED);
            coronaEvent.put(EVENT_TYPE_KEY, TYPE_UNITYAD);
            coronaEvent.put(EVENT_DATA_KEY, getJSONStringForPlacement(placementId));
            dispatchLuaEvent(coronaEvent);

        }

        @Override
        public void onUnityAdsShowComplete(String placementId, UnityAds.UnityAdsShowCompletionState finishState) {
            loadedIds.remove(placementId);
            Map<String, Object> coronaEvent = new HashMap<>();
            coronaEvent.put(EVENT_TYPE_KEY, TYPE_UNITYAD);
            coronaEvent.put(EVENT_DATA_KEY, getJSONStringForPlacement(placementId));

            String phase = null;
            if (finishState == UnityAds.UnityAdsShowCompletionState.SKIPPED) {
                phase = PHASE_SKIPPED;
            } else if (finishState == UnityAds.UnityAdsShowCompletionState.COMPLETED) {
                phase = PHASE_COMPLETED;
            }

            coronaEvent.put(EVENT_PHASE_KEY, phase);

            // send Lua event
            dispatchLuaEvent(coronaEvent);
        }
        @Override
        public void onUnityBannerLoaded(String placementId, View view) {
            if(!loadedIds.contains(placementId)){
                loadedIds.add(placementId);
            }
            Map<String, Object> coronaEvent = new HashMap<>();
            coronaEvent.put(EVENT_PHASE_KEY, PHASE_LOADED);
            coronaEvent.put(EVENT_TYPE_KEY, TYPE_UNITYAD);
            coronaEvent.put(EVENT_DATA_KEY, getJSONStringForPlacement(placementId));
            dispatchLuaEvent(coronaEvent);
        }

        @Override
        public void onUnityBannerUnloaded(String placementId) {
            loadedIds.remove(placementId);
        }

        @Override
        public void onUnityBannerShow(String placementId) {
            loadedIds.remove(placementId);
        }

        @Override
        public void onUnityBannerClick(String placementId) {
            //Not used
        }

        @Override
        public void onUnityBannerHide(String placementId) {
            //Not used
        }

        @Override
        public void onUnityBannerError(String message) {
            Map<String, Object> coronaEvent = new HashMap<>();
            coronaEvent.put(EVENT_PHASE_KEY, PHASE_FAILED);
            coronaEvent.put(EVENT_TYPE_KEY, TYPE_UNITYAD);
            coronaEvent.put(CoronaLuaEvent.ISERROR_KEY, true);
            coronaEvent.put(CoronaLuaEvent.RESPONSE_KEY, message);
            dispatchLuaEvent(coronaEvent);
        }
    }
}
