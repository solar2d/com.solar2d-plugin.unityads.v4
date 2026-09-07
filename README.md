Sources for the plugin `plugin.unityads.v4`.

Add following to your `build.settings` to use:
```lua
{
    plugins = {
        "plugin.unityads.v4" = {
            publisherId = "com.solar2d",
        },
    },
}
```

## iOS build notes

* The iOS plugin is built on top of the LevelPlay (ironSource) SDK. The bundled `IronSource.xcframework` is version 9.6.0; new releases are published at https://github.com/ironsource-mobile/iOS-sdk/releases.
* To update the SDK, replace `src/ios/IronSource.xcframework`, copy its `ios-arm64` slice to `src/ios/EmbeddedFrameworks/IronSource.framework` and its `ios-arm64_x86_64-simulator` slice to `src/ios/EmbeddedFrameworksSim/IronSource.framework`.
* Run `src/ios/build.sh <output dir>`. It produces `<output dir>/BuiltPlugin/iphone` and `<output dir>/BuiltPlugin/iphone-sim`; copy `libUnityAdsPlugin.a` and `IronSource.framework` from those folders into `plugins/<build>/iphone` and `plugins/<build>/iphone-sim`.

## Android build notes

* The Android plugin is built on top of the LevelPlay (ironSource) mediation SDK, pulled from Maven Central by `plugins/<build>/android/corona.gradle` (`com.unity3d.ads-mediation:mediation-sdk`). Keep the version there in sync with `src/android/plugin/build.gradle`.
* LevelPlay 9.x runs on Kotlin coroutines but does not declare them in its POM, so `corona.gradle` adds `kotlinx-coroutines-android` explicitly, together with the Google identifier libraries (`play-services-appset`, `play-services-ads-identifier`, `play-services-basement`) that the LevelPlay integration guide requires. Without the coroutines dependency the SDK logs `Failed to launch coroutine` (`NoClassDefFoundError: kotlinx.coroutines.Dispatchers`) at runtime.
* Minimum Android API level is 23 (enforced by `corona.gradle`).
* To rebuild the plugin jar run `./gradlew :plugin:extractPluginJar` in `src/android` (needs `ANDROID_HOME`); copy `plugin/build/outputs/plugin.unityads.v4.jar` to `plugins/<build>/android/`. `./gradlew :app:assembleDebug` builds the sample app in `src/Corona` with the local plugin for emulator testing.
