# unityads.load()

> --------------------- ------------------------------------------------------------------------------------------
> __Type__              [Function][api.type.Function]
> __Return value__      none
> __Revision__          [REVISION_LABEL](REVISION_URL)
> __Keywords__          ads, advertising, Unity Ads, show
> __See also__          [unityads.show()][plugin.unityads-v4.show]
>						[unityads.*][plugin.unityads-v4]
> --------------------- ------------------------------------------------------------------------------------------


## Overview

Loads Unity Ads video interstitial or rewarded video ad.

Each placement ID is backed by a single ad object that is reused for the whole session. Calling `unityads.load()` again while a load for the same placement is still in progress is ignored (a warning is printed), and calling it while a loaded ad is still waiting to be shown immediately dispatches another `loaded` event instead of requesting a new ad. Call `unityads.load()` again after the ad has been shown (`completed` or `skipped`) or after a `failed` event.


## Syntax

    unityads.load( placementId [, adType] )

##### placementId ~^(required)^~
_[String][api.type.String]._ One of the placement IDs you've configured in the Unity&nbsp;Ads [dashboard](https://unity3d.com/services/ads). With the LevelPlay backend this is the LevelPlay __Ad Unit ID__.

##### adType ~^(optional)^~
_[String][api.type.String]._ `"interstitial"` (default) or `"rewarded"`. Must match the format of the ad unit in the LevelPlay dashboard; a rewarded ad unit loaded without `"rewarded"` fails to load.


## Example

``````lua
local unityads = require( "plugin.unityads.v4" )

-- Unity Ads listener function
local function adListener( event )

	if ( event.phase == "init" ) then  -- Successful initialization
		print( event.provider )
    --Load ad before we show
    unityads.load("YOUR_UNITYADS_PLACEMENT_ID")
	end
end

-- Initialize the Unity Ads plugin
unityads.init( adListener, { gameId="YOUR_UNITYADS_GAME_ID" } )

-- Sometime later, show an ad
if ( unityads.isLoaded( "YOUR_UNITYADS_PLACEMENT_ID" ) ) then
	unityads.show( "YOUR_UNITYADS_PLACEMENT_ID" )
end
``````
